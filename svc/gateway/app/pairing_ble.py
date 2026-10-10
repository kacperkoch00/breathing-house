"""BLE scan + GATT admit for Breathing House pairing.

Firmware in pairing mode must advertise SERVICE_UUID. Service data is UTF-8
`TYPE:sensorId` (TYPE is AIR, ROOM, OPENING, or PRESENCE). Sensors talk only
to the Pi; no Wi-Fi password or broker settings are sent.

GATT (all under SERVICE_UUID):

    f0b10001  read   JSON {"sensorId","type","wifiMac"}
    f0b10005  write  "1" when the user admits the board
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import subprocess
import threading
import time
from typing import Any, Callable

SERVICE_UUID = "f0b10000-4e00-40b1-8000-00805f9b34fb"
CHAR_INFO = "f0b10001-4e00-40b1-8000-00805f9b34fb"
CHAR_ADMIT = "f0b10005-4e00-40b1-8000-00805f9b34fb"
# Windows laptop radios often cannot advertise a connectable GATT server.
# The desktop simulator falls back to this manufacturer section.
SIM_COMPANY_ID = 0xFFFF

SENSOR_TYPES = ("AIR", "ROOM", "OPENING", "PRESENCE")
_MAC_RE = re.compile(r"^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

ALLOW_MQTT_MAC = os.environ.get("ALLOW_MQTT_MAC", "/usr/local/sbin/bh-allow-mqtt-mac")
ADMIT_TIMEOUT = float(os.environ.get("ADMIT_TIMEOUT", "30"))

log = logging.getLogger("gateway.pairing")

OnCandidate = Callable[[dict[str, Any]], None]
OnError = Callable[[str], None]


class PairingError(Exception):
    def __init__(self, status: int, error: str, message: str):
        super().__init__(message)
        self.status = status
        self.error = error
        self.message = message


def normalize_mac(mac: str) -> str:
    mac = mac.strip().upper().replace("-", ":")
    if len(mac) == 12 and ":" not in mac:
        mac = ":".join(mac[i : i + 2] for i in range(0, 12, 2))
    if not _MAC_RE.fullmatch(mac):
        raise ValueError(f"invalid MAC address: {mac}")
    return mac


def infer_type(sensor_id: str) -> str | None:
    lower = sensor_id.lower()
    for kind in SENSOR_TYPES:
        prefix = kind.lower()
        if lower.startswith(prefix + "-") or lower.startswith(prefix + "_"):
            return kind
    return None


def parse_sim_manufacturer(raw: bytes) -> dict[str, str] | None:
    """`TYPE:sensorId` or `TYPE:sensorId:WIFI_MAC` (MAC with or without colons)."""
    text = raw.decode("utf-8", errors="replace").strip("\x00 ").strip()
    parts = text.split(":")
    if len(parts) < 2:
        return None
    kind = parts[0].strip().upper()
    ident = parts[1].strip()
    if kind not in SENSOR_TYPES or not ident:
        return None
    found = {"sensorId": ident, "type": kind}
    if len(parts) >= 3 and parts[2].strip():
        try:
            found["wifiMac"] = normalize_mac(parts[2])
        except ValueError:
            return None
    return found


def parse_advertisement(
    address: str,
    name: str | None,
    service_uuids: list[str],
    service_data: dict[str, bytes],
    rssi: int | None = None,
) -> dict[str, Any] | None:
    uuids = {str(u).lower() for u in service_uuids}
    data_by_uuid = {str(key).lower(): value for key, value in service_data.items()}
    has_service = SERVICE_UUID in uuids or SERVICE_UUID in data_by_uuid

    sensor_id = None
    sensor_type = None
    raw = data_by_uuid.get(SERVICE_UUID)
    if raw:
        text = raw.decode("utf-8", errors="replace").strip()
        if ":" in text:
            kind, _, ident = text.partition(":")
            kind = kind.strip().upper()
            ident = ident.strip()
            if kind in SENSOR_TYPES and ident:
                sensor_type = kind
                sensor_id = ident

    if not sensor_id:
        sensor_id = (name or "").strip() or None
    if sensor_id and not sensor_type:
        sensor_type = infer_type(sensor_id)
    if not sensor_id:
        return None
    # Windows often advertises our UUID with the PC name, or the name only.
    if not has_service and sensor_type is None:
        return None

    return {
        "sensorId": sensor_id,
        "type": sensor_type,
        "bleAddress": address.upper(),
        "rssi": rssi,
    }


async def _fill_from_gatt(address: str, parsed: dict[str, Any]) -> dict[str, Any]:
    """Prefer CHAR_INFO when the advert has no TYPE:id service data (Windows)."""
    try:
        from bleak import BleakClient
    except ImportError:
        return parsed
    try:
        async with BleakClient(address, timeout=8.0) as client:
            raw = await client.read_gatt_char(CHAR_INFO)
            info = json.loads(raw.decode("utf-8"))
        if not isinstance(info, dict) or not info.get("sensorId"):
            return parsed
        filled = dict(parsed)
        filled["sensorId"] = str(info["sensorId"])
        if info.get("type"):
            filled["type"] = str(info["type"]).upper()
        return filled
    except Exception as exc:
        log.info("GATT info during scan failed (%s); using advert", exc)
        return parsed


def candidate_from_bleak(device: Any, adv: Any) -> dict[str, Any] | None:
    rssi = getattr(adv, "rssi", None)
    for company_id, raw in (getattr(adv, "manufacturer_data", None) or {}).items():
        if int(company_id) != SIM_COMPANY_ID:
            continue
        sim = parse_sim_manufacturer(bytes(raw))
        if sim is None:
            continue
        found = {
            "sensorId": sim["sensorId"],
            "type": sim["type"],
            "bleAddress": device.address.upper(),
            "rssi": rssi,
            "beacon": True,
        }
        if sim.get("wifiMac"):
            found["wifiMac"] = sim["wifiMac"]
        return found

    name = getattr(adv, "local_name", None) or getattr(device, "name", None)
    uuids = [str(u) for u in (getattr(adv, "service_uuids", None) or [])]
    data = {str(k): v for k, v in (getattr(adv, "service_data", None) or {}).items()}
    return parse_advertisement(device.address, name, uuids, data, rssi)


_loop: asyncio.AbstractEventLoop | None = None
_loop_thread: threading.Thread | None = None
_scan_future: asyncio.Future[None] | None = None
_loop_lock = threading.Lock()


def _ensure_loop() -> asyncio.AbstractEventLoop:
    global _loop, _loop_thread
    with _loop_lock:
        if _loop is not None:
            return _loop
        loop = asyncio.new_event_loop()

        def _run() -> None:
            asyncio.set_event_loop(loop)
            loop.run_forever()

        thread = threading.Thread(target=_run, name="ble-loop", daemon=True)
        thread.start()
        _loop = loop
        _loop_thread = thread
        return loop


def _submit(coro: Any) -> Any:
    loop = _ensure_loop()
    return asyncio.run_coroutine_threadsafe(coro, loop)


def cancel_scan() -> None:
    global _scan_future
    if _scan_future is None:
        return
    _scan_future.cancel()
    _scan_future = None


def start_scan(deadline: float, on_candidate: OnCandidate, on_error: OnError) -> None:
    try:
        import bleak  # noqa: F401
    except ImportError as exc:
        raise PairingError(503, "ble_unavailable", "bleak is not installed") from exc

    cancel_scan()
    global _scan_future
    _scan_future = _submit(_scan_until(deadline, on_candidate, on_error))


async def _scan_until(deadline: float, on_candidate: OnCandidate, on_error: OnError) -> None:
    try:
        from bleak import BleakScanner
    except ImportError as exc:
        on_error("bleak is not installed")
        raise PairingError(503, "ble_unavailable", "bleak is not installed") from exc

    probed: set[str] = set()

    async def _publish(address: str, parsed: dict[str, Any]) -> None:
        if address not in probed:
            probed.add(address)
            if not parsed.get("beacon"):
                parsed = await _fill_from_gatt(address, parsed)
        on_candidate(parsed)

    def _cb(device: Any, adv: Any) -> None:
        parsed = candidate_from_bleak(device, adv)
        if parsed is None:
            return
        asyncio.get_running_loop().create_task(_publish(device.address.upper(), parsed))

    # Do not pass service_uuids to BlueZ: Windows often omits the 128-bit UUID
    # from the 31-byte packet, and the OS filter would hide the device.
    scanner = BleakScanner(detection_callback=_cb)
    try:
        await scanner.start()
        while time.monotonic() < deadline:
            await asyncio.sleep(0.25)
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        log.warning("BLE scan failed: %s", exc)
        on_error(str(exc))
    finally:
        try:
            await scanner.stop()
        except Exception:
            pass


async def signal_admit(ble_address: str) -> dict[str, str]:
    try:
        from bleak import BleakClient
    except ImportError as exc:
        raise PairingError(503, "ble_unavailable", "bleak is not installed") from exc

    try:
        async with BleakClient(ble_address, timeout=15.0) as client:
            wifi_mac = ble_address
            try:
                raw = await client.read_gatt_char(CHAR_INFO)
                info = json.loads(raw.decode("utf-8"))
                if isinstance(info, dict) and info.get("wifiMac"):
                    wifi_mac = normalize_mac(str(info["wifiMac"]))
            except Exception as exc:
                log.info("GATT info read failed (%s); using BLE address as wifi MAC", exc)
                wifi_mac = normalize_mac(ble_address)
            await client.write_gatt_char(CHAR_ADMIT, b"1", response=True)
            return {"wifiMac": wifi_mac}
    except PairingError:
        raise
    except Exception as exc:
        raise PairingError(502, "admit_failed", f"GATT admit failed: {exc}") from exc


def admit_device(ble_address: str, wifi_mac: str | None = None) -> dict[str, str]:
    if wifi_mac:
        mac = normalize_mac(wifi_mac)
        allow_wifi_mac(mac)
        log.info("admitted beacon %s without GATT; wifiMac=%s", ble_address, mac)
        return {"wifiMac": mac}
    try:
        result = _submit(signal_admit(ble_address)).result(timeout=ADMIT_TIMEOUT)
    except PairingError:
        raise
    except Exception as exc:
        raise PairingError(502, "admit_failed", f"GATT admit failed: {exc}") from exc
    allow_wifi_mac(result["wifiMac"])
    return result


def allow_wifi_mac(mac: str) -> None:
    mac = normalize_mac(mac)
    try:
        completed = subprocess.run(
            ["sudo", "-n", ALLOW_MQTT_MAC, mac],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except FileNotFoundError as exc:
        raise PairingError(503, "firewall_unavailable", "sudo or MAC helper is missing") from exc
    except subprocess.TimeoutExpired as exc:
        raise PairingError(502, "firewall_failed", "MAC allow helper timed out") from exc
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "allow-mac failed").strip()
        raise PairingError(502, "firewall_failed", detail)
    log.info("allowed MQTT MAC %s", mac)
