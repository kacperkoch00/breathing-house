#!/usr/bin/env python3
from __future__ import annotations

import json
import unittest
import urllib.error
import urllib.request
from pairing_ble import (
    SERVICE_UUID,
    infer_type,
    normalize_mac,
    parse_advertisement,
    parse_sim_manufacturer,
)


class ParseAdvertisementTest(unittest.TestCase):
    def test_ignores_other_devices(self):
        self.assertIsNone(
            parse_advertisement("AA:BB:CC:DD:EE:FF", "DESKTOP-PC", [], {}, -40)
        )

    def test_accepts_inferable_name_without_uuid(self):
        found = parse_advertisement("AA:BB:CC:DD:EE:FF", "air-11", [], {}, -40)
        self.assertEqual(found["sensorId"], "air-11")
        self.assertEqual(found["type"], "AIR")

    def test_parses_service_data(self):
        found = parse_advertisement(
            "aa:bb:cc:dd:ee:ff",
            "ignored",
            [SERVICE_UUID],
            {SERVICE_UUID: b"AIR:air-11"},
            -55,
        )
        self.assertEqual(
            found,
            {
                "sensorId": "air-11",
                "type": "AIR",
                "bleAddress": "AA:BB:CC:DD:EE:FF",
                "rssi": -55,
            },
        )

    def test_infers_type_from_name(self):
        found = parse_advertisement(
            "AA:BB:CC:DD:EE:00",
            "opening-1",
            [SERVICE_UUID.upper()],
            {},
            None,
        )
        self.assertEqual(found["sensorId"], "opening-1")
        self.assertEqual(found["type"], "OPENING")

    def test_infer_type(self):
        self.assertEqual(infer_type("room_2"), "ROOM")
        self.assertIsNone(infer_type("kitchen"))

    def test_parses_windows_beacon(self):
        found = parse_sim_manufacturer(b"AIR:air-sim-1:9CC7D3E38ECC")
        self.assertEqual(found["sensorId"], "air-sim-1")
        self.assertEqual(found["type"], "AIR")
        self.assertEqual(found["wifiMac"], "9C:C7:D3:E3:8E:CC")

    def test_normalize_mac(self):
        self.assertEqual(normalize_mac("aa-bb-cc-dd-ee-ff"), "AA:BB:CC:DD:EE:FF")
        with self.assertRaises(ValueError):
            normalize_mac("not-a-mac")


class AdmitGuardTest(unittest.TestCase):
    def test_unknown_candidate_is_not_found(self):
        import pairing

        pairing.reset_state()
        from pairing_ble import PairingError

        with self.assertRaises(PairingError) as raised:
            pairing.admit("air-11")
        self.assertEqual(raised.exception.status, 404)

    def test_admitted_device_is_not_relisted(self):
        from unittest.mock import patch

        import pairing

        pairing.reset_state()
        candidate = {
            "sensorId": "air-sim-1",
            "type": "AIR",
            "bleAddress": "AA:BB:CC:DD:EE:FF",
            "rssi": -50,
            "wifiMac": "9C:C7:D3:E3:8E:CC",
        }
        with patch("pairing.start_ble_scan"), patch(
            "pairing.admit_device", return_value={"wifiMac": "9C:C7:D3:E3:8E:CC"}
        ):
            pairing.start_scan()
            pairing._remember(candidate)
            pairing.admit("air-sim-1")
            pairing._remember(candidate)
            body = pairing.list_candidates()
        self.assertEqual(body["candidates"], [])
        self.assertEqual(body["admitted"], ["air-sim-1"])


class PairingHttpTest(unittest.TestCase):
    def setUp(self):
        import pairing

        pairing.reset_state()
        self.pairing = pairing
        self.server = pairing.start_in_thread("127.0.0.1", 0)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.pairing.reset_state()

    def _json(self, path: str, method: str = "GET", body: dict | None = None) -> tuple[int, dict]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base}{path}",
            data=data,
            method=method,
            headers={"Content-Type": "application/json"} if data is not None else {},
        )
        try:
            with urllib.request.urlopen(request, timeout=2) as response:
                return response.status, json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            payload = json.loads(exc.read().decode("utf-8"))
            exc.close()
            return exc.code, payload

    def test_candidates_idle(self):
        status, body = self._json("/candidates")
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "idle")
        self.assertEqual(body["candidates"], [])
        self.assertEqual(body["admitted"], [])

    def test_admit_without_scan_is_not_found(self):
        status, body = self._json("/admit", method="POST", body={"sensorId": "air-sim-1"})
        self.assertEqual(status, 404)
        self.assertEqual(body["error"], "not_found")


if __name__ == "__main__":
    unittest.main()
