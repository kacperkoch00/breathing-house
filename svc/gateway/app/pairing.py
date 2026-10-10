#!/usr/bin/env python3
"""LAN pairing HTTP API for the Sensors page Scan / Add flow.

POST /scan        start a timed BLE scan for boards in pairing mode
GET  /candidates  boards seen in the current (or last) window
POST /admit       tell the board it is admitted, allow its MAC on MQTT 1883

The browser is expected to call these on the Pi directly. CORS is open.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from pairing_ble import PairingError, admit_device, cancel_scan, start_scan as start_ble_scan

HTTP_HOST = os.environ.get("HTTP_HOST", "0.0.0.0")
HTTP_PORT = int(os.environ.get("HTTP_PORT", "8090"))
SCAN_WINDOW = float(os.environ.get("SCAN_WINDOW", "60"))

log = logging.getLogger("gateway.pairing")
_lock = threading.Lock()
_session: dict[str, Any] | None = None


def _state(session: dict[str, Any], now: float) -> str:
    return "scanning" if now < session["deadline"] else "complete"


def _public(session: dict[str, Any] | None) -> dict[str, Any]:
    if session is None:
        return {
            "state": "idle",
            "requestId": None,
            "expiresAt": None,
            "candidates": [],
            "admitted": [],
        }
    body = {
        "state": _state(session, time.monotonic()),
        "requestId": session["requestId"],
        "expiresAt": session["expiresAt"],
        "candidates": list(session["candidates"]),
        "admitted": list(session.get("admittedIds") or []),
    }
    if session.get("bleError"):
        body["bleError"] = session["bleError"]
    return body


def _remember(parsed: dict[str, Any]) -> None:
    with _lock:
        if _session is None:
            return
        if parsed["sensorId"] in _session.get("admittedIds", []):
            return
        candidates: list[dict[str, Any]] = _session["candidates"]
        for index, existing in enumerate(candidates):
            if existing["sensorId"] == parsed["sensorId"]:
                candidates[index] = parsed
                return
        candidates.append(parsed)
        log.info("candidate %s type=%s rssi=%s", parsed["sensorId"], parsed.get("type"), parsed.get("rssi"))


def _ble_error(message: str) -> None:
    with _lock:
        if _session is not None:
            _session["bleError"] = message


def start_scan() -> dict[str, Any]:
    global _session
    if SCAN_WINDOW <= 0:
        raise ValueError("SCAN_WINDOW must be > 0")
    now = time.monotonic()
    expires_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + SCAN_WINDOW))
    session = {
        "requestId": str(uuid.uuid4()),
        "deadline": now + SCAN_WINDOW,
        "expiresAt": expires_at,
        "candidates": [],
        "admittedIds": [],
        "bleError": None,
    }
    start_ble_scan(session["deadline"], _remember, _ble_error)
    with _lock:
        _session = session
        log.info("scan started requestId=%s window=%ss", session["requestId"], SCAN_WINDOW)
        body = _public(_session)
    body["windowSeconds"] = SCAN_WINDOW
    return body


def list_candidates() -> dict[str, Any]:
    with _lock:
        return _public(_session)


def admit(sensor_id: str) -> dict[str, Any]:
    sensor_id = sensor_id.strip()
    if not sensor_id:
        raise ValueError("sensorId must be a non-blank string")

    with _lock:
        if _session is None:
            raise PairingError(404, "not_found", "no scan results; POST /scan first")
        match = next((c for c in _session["candidates"] if c["sensorId"] == sensor_id), None)
        if match is None:
            raise PairingError(404, "not_found", f"sensor {sensor_id} is not a current candidate")
        candidate = dict(match)

    result = admit_device(candidate["bleAddress"], candidate.get("wifiMac"))
    with _lock:
        if _session is not None:
            admitted = _session.setdefault("admittedIds", [])
            if sensor_id not in admitted:
                admitted.append(sensor_id)
            _session["candidates"] = [c for c in _session["candidates"] if c["sensorId"] != sensor_id]
    log.info("admitted %s wifiMac=%s", sensor_id, result["wifiMac"])
    return {"sensorId": sensor_id, "state": "admitted", "wifiMac": result["wifiMac"]}


def reset_state() -> None:
    """Test helper."""
    global _session
    cancel_scan()
    with _lock:
        _session = None


class PairingHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: object) -> None:
        log.info("%s " + fmt, self.address_string(), *args)

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, status: int, body: dict[str, Any]) -> None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _path(self) -> str:
        path = self.path.split("?", 1)[0]
        if path != "/" and path.endswith("/"):
            path = path.rstrip("/")
        return path

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length > 0 else b""
        if not raw:
            return {}
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("body must be a JSON object")
        return data

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self) -> None:
        if self._path() == "/candidates":
            self._json(200, list_candidates())
            return
        self._json(404, {"error": "not_found", "message": "unknown path"})

    def do_POST(self) -> None:
        path = self._path()
        if path == "/scan":
            try:
                self._json(200, start_scan())
            except PairingError as exc:
                self._json(exc.status, {"error": exc.error, "message": exc.message})
            except ValueError as exc:
                self._json(400, {"error": "bad_request", "message": str(exc)})
            return
        if path == "/admit":
            try:
                body = self._read_json()
            except (ValueError, json.JSONDecodeError, UnicodeDecodeError):
                self._json(400, {"error": "bad_request", "message": "invalid JSON body"})
                return
            sensor_id = body.get("sensorId")
            if not isinstance(sensor_id, str):
                self._json(400, {"error": "bad_request", "message": "sensorId is required"})
                return
            try:
                self._json(200, admit(sensor_id))
            except PairingError as exc:
                self._json(exc.status, {"error": exc.error, "message": exc.message})
            except ValueError as exc:
                self._json(400, {"error": "bad_request", "message": str(exc)})
            return
        self._json(404, {"error": "not_found", "message": "unknown path"})


def start_in_thread(host: str = HTTP_HOST, port: int = HTTP_PORT) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), PairingHandler)
    thread = threading.Thread(target=server.serve_forever, name="pairing-http", daemon=True)
    thread.start()
    log.info("pairing HTTP on %s:%s", host, port)
    return server


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if SCAN_WINDOW <= 0:
        log.error("SCAN_WINDOW must be > 0")
        return 1
    server = start_in_thread()
    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        cancel_scan()
        server.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
