#!/usr/bin/env python3
"""Windows BLE + MQTT stand-in for a Breathing House air sensor.

Run with Windows Python, not WSL. The laptop must be in radio range of the Pi.

  py -3 -m pip install -r scripts/requirements-air-sensor-windows.txt
  py -3 scripts/simulate-air-sensor-windows.py --broker <pi-ipv4> --wifi-mac 9C:C7:D3:E3:8E:CC

Then press Scan / Add on the dashboard. After admit, this process publishes
home/sensors/air until you Ctrl-C.

Windows usually advertises the PC Bluetooth name. The script also puts
AIR:<sensor-id> in advertisement service data so the Pi can parse the id.
If Scan still shows the PC name, rename the Bluetooth radio to air-sim-1
in Device Manager.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

log = logging.getLogger("air-sim")

SERVICE_UUID = uuid.UUID("f0b10000-4e00-40b1-8000-00805f9b34fb")
CHAR_INFO = uuid.UUID("f0b10001-4e00-40b1-8000-00805f9b34fb")
CHAR_ADMIT = uuid.UUID("f0b10005-4e00-40b1-8000-00805f9b34fb")
TOPIC = "home/sensors/air"


def die(message: str) -> None:
    raise SystemExit(f"error: {message}")


def normalize_mac(mac: str) -> str:
    mac = mac.strip().upper().replace("-", ":")
    if len(mac) == 12 and ":" not in mac:
        mac = ":".join(mac[i : i + 2] for i in range(0, 12, 2))
    parts = mac.split(":")
    if len(parts) != 6 or any(len(p) != 2 for p in parts):
        die(f"invalid MAC address: {mac}")
    return mac


def detect_wifi_mac() -> str | None:
    node = uuid.getnode()
    if node >> 40 & 1:
        return None
    return ":".join(f"{(node >> shift) & 0xFF:02X}" for shift in range(40, -1, -8))


class AirWalk:
    """Slow random walk, same idea as scripts/simulate-live-sensors.sh."""

    def __init__(self, alarm: bool) -> None:
        self.temperature = round(random.uniform(19.0, 23.0), 1)
        self.humidity = float(random.randint(38, 52))
        if alarm:
            self.co2 = float(random.randint(1600, 1750))
            self.co2_lo, self.co2_hi, self.co2_step = 1550.0, 1950.0, 18.0
        else:
            self.co2 = float(random.randint(520, 780))
            self.co2_lo, self.co2_hi, self.co2_step = 420.0, 1100.0, 22.0

    def _walk(self, value: float, low: float, high: float, step: float) -> float:
        nxt = value + random.uniform(-step, step)
        return max(low, min(high, nxt))

    def next_payload(self, sensor_id: str) -> str:
        self.temperature = round(self._walk(self.temperature, 17.5, 26.5, 0.12), 1)
        self.humidity = round(self._walk(self.humidity, 28.0, 68.0, 0.8), 1)
        self.co2 = round(self._walk(self.co2, self.co2_lo, self.co2_hi, self.co2_step))
        return json.dumps(
            {
                "sensorId": sensor_id,
                "temperature": self.temperature,
                "humidity": self.humidity,
                "co2": int(self.co2),
            },
            separators=(",", ":"),
        )


def mqtt_client():
    import paho.mqtt.client as mqtt

    if hasattr(mqtt, "CallbackAPIVersion"):
        return mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
    return mqtt.Client()


_beacon = None


def start_beacon(sensor_id: str, wifi_mac: str) -> None:
    """Non-connectable advert. This radio can send it; it cannot host GATT."""
    global _beacon
    from winrt.windows.devices.bluetooth.advertisement import (
        BluetoothLEAdvertisement,
        BluetoothLEAdvertisementPublisher,
        BluetoothLEManufacturerData,
    )
    from winrt.windows.storage.streams import DataWriter

    payload = f"AIR:{sensor_id}:{wifi_mac.replace(':', '')}".encode("utf-8")
    writer = DataWriter()
    writer.write_bytes(payload)
    manufacturer = BluetoothLEManufacturerData()
    manufacturer.company_id = 0xFFFF
    manufacturer.data = writer.detach_buffer()
    advert = BluetoothLEAdvertisement()
    advert.manufacturer_data.append(manufacturer)
    publisher = BluetoothLEAdvertisementPublisher(advert)
    publisher.start()
    _beacon = publisher
    log.info("beacon %s (Scan can see it; Add skips GATT)", payload.decode("utf-8"))


def pairing_base(args) -> str:
    return (args.pairing_url or f"http://{args.broker}:8090").rstrip("/")


def wait_for_pairing_admit(pairing_url: str, sensor_id: str) -> None:
    """Same handshake as firmware: stay in pairing until POST /admit removes us."""
    log.info("pairing mode — Scan, then Accept (Ctrl-C to cancel)")
    while True:
        try:
            with urllib.request.urlopen(f"{pairing_url}/candidates", timeout=3) as response:
                body = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            log.info("waiting for pairing API %s (%s)", pairing_url, exc)
            time.sleep(1)
            continue
        admitted = body.get("admitted") or []
        ids = [item.get("sensorId") for item in body.get("candidates") or []]
        if sensor_id in admitted:
            log.info("admitted; leaving pairing mode")
            return
        if sensor_id in ids:
            log.info("Pi scan sees %s; starting MQTT (beacon cannot receive GATT admit)", sensor_id)
            return
        time.sleep(0.5)


def stop_beacon() -> None:
    global _beacon
    if _beacon is None:
        return
    try:
        _beacon.stop()
    except Exception as exc:
        log.info("beacon stop: %s", exc)
    _beacon = None


def publish_until_stopped(host: str, port: int, qos: int, sensor_id: str, interval: float, alarm: bool) -> None:
    client = mqtt_client()
    client.connect(host, port, keepalive=30)
    client.loop_start()
    log.info("publishing %s to %s:%s every %ss (Ctrl-C to stop)", TOPIC, host, port, interval)
    walk = AirWalk(alarm)
    try:
        while True:
            payload = walk.next_payload(sensor_id)
            info = client.publish(TOPIC, payload, qos=qos)
            info.wait_for_publish(timeout=5)
            log.info("%s", payload)
            time.sleep(interval)
    finally:
        client.loop_stop()
        client.disconnect()


def _as_bytes(value) -> bytes:
    if value is None:
        return b""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    return bytes(value)


def run_ble(args) -> None:
    try:
        from winrt.windows.devices.bluetooth import BluetoothError
        from winrt.windows.devices.bluetooth.genericattributeprofile import (
            GattCharacteristicProperties,
            GattLocalCharacteristicParameters,
            GattProtectionLevel,
            GattServiceProvider,
            GattServiceProviderAdvertisementStatus,
            GattServiceProviderAdvertisingParameters,
            GattWriteOption,
        )
        from winrt.windows.storage.streams import DataReader, DataWriter
    except ImportError as exc:
        die(
            "WinRT BLE packages missing. In PowerShell run: "
            r'& "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe" '
            r"-m pip install --upgrade -r scripts\requirements-air-sensor-windows.txt"
            f" ({exc})"
        )

    info = json.dumps(
        {"sensorId": args.sensor_id, "type": "AIR", "wifiMac": args.wifi_mac},
        separators=(",", ":"),
    ).encode("utf-8")
    def to_buffer(data: bytes):
        writer = DataWriter()
        writer.write_bytes(data)
        return writer.detach_buffer()

    async def serve() -> None:
        loop = asyncio.get_running_loop()
        admitted = asyncio.Event()

        created = await GattServiceProvider.create_async(SERVICE_UUID)
        if created.error != BluetoothError.SUCCESS or created.service_provider is None:
            die(f"GattServiceProvider.create_async failed: {created.error}")
        provider = created.service_provider
        service = provider.service
        if service is None:
            die("GATT service was not created")

        read_params = GattLocalCharacteristicParameters()
        read_params.characteristic_properties = GattCharacteristicProperties.READ
        read_params.read_protection_level = GattProtectionLevel.PLAIN
        read_params.user_description = "sensor info"
        read_params.static_value = to_buffer(info)
        read_result = await service.create_characteristic_async(CHAR_INFO, read_params)
        if read_result.error != BluetoothError.SUCCESS or read_result.characteristic is None:
            die(f"CHAR_INFO create failed: {read_result.error}")
        info_char = read_result.characteristic

        write_params = GattLocalCharacteristicParameters()
        write_params.characteristic_properties = GattCharacteristicProperties.WRITE
        write_params.write_protection_level = GattProtectionLevel.PLAIN
        write_params.user_description = "admit"
        write_result = await service.create_characteristic_async(CHAR_ADMIT, write_params)
        if write_result.error != BluetoothError.SUCCESS or write_result.characteristic is None:
            die(f"CHAR_ADMIT create failed: {write_result.error}")
        admit_char = write_result.characteristic

        def on_read(_sender, event_args) -> None:
            deferral = event_args.get_deferral()

            async def respond() -> None:
                try:
                    request = await event_args.get_request_async()
                    if request is None:
                        return
                    request.respond_with_value(to_buffer(info))
                finally:
                    if deferral is not None:
                        deferral.complete()

            asyncio.run_coroutine_threadsafe(respond(), loop)

        def on_write(_sender, event_args) -> None:
            deferral = event_args.get_deferral()

            async def accept() -> None:
                try:
                    request = await event_args.get_request_async()
                    if request is None or request.value is None:
                        return
                    reader = DataReader.from_buffer(request.value)
                    raw = _as_bytes(reader.read_bytes(reader.unconsumed_buffer_length)).strip()
                    log.info("GATT admit write %s", raw)
                    if raw in (b"1", b"true", b"TRUE"):
                        admitted.set()
                    if request.option == GattWriteOption.WRITE_WITH_RESPONSE:
                        request.respond()
                finally:
                    if deferral is not None:
                        deferral.complete()

            asyncio.run_coroutine_threadsafe(accept(), loop)

        # Keep tokens so the handlers are not collected.
        read_token = info_char.add_read_requested(on_read)
        write_token = admit_char.add_write_requested(on_write)

        statuses: asyncio.Queue = asyncio.Queue()

        def on_status(_sender, event_args) -> None:
            asyncio.run_coroutine_threadsafe(statuses.put(event_args.status), loop)

        status_token = provider.add_advertisement_status_changed(on_status)
        # Do not put AIR:<id> in the 31-byte packet — it crowds out the 128-bit
        # service UUID and the Pi then never sees the device.
        adv = GattServiceProviderAdvertisingParameters()
        adv.is_discoverable = True
        adv.is_connectable = True
        provider.start_advertising_with_parameters(adv)
        try:
            status = await asyncio.wait_for(statuses.get(), timeout=8)
        except TimeoutError:
            status = provider.advertisement_status
        log.info("advertisement status %s", status)
        if status == GattServiceProviderAdvertisementStatus.ABORTED:
            provider.stop_advertising()
            provider.remove_advertisement_status_changed(status_token)
            info_char.remove_read_requested(read_token)
            admit_char.remove_write_requested(write_token)
            log.warning("connectable GATT aborted; pairing as a BLE beacon (same Scan/Accept flow)")
            start_beacon(args.sensor_id, args.wifi_mac)
            wait_for_pairing_admit(pairing_base(args), args.sensor_id)
            stop_beacon()
            return
        log.info(
            "pairing mode as %s — Scan on the dashboard, then Add (Ctrl-C to cancel)",
            args.sensor_id,
        )
        try:
            await admitted.wait()
            log.info("admitted; leaving pairing mode")
        finally:
            provider.stop_advertising()
            provider.remove_advertisement_status_changed(status_token)
            info_char.remove_read_requested(read_token)
            admit_char.remove_write_requested(write_token)

    asyncio.run(serve())


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Windows BLE/MQTT air-sensor simulator")
    parser.add_argument("--sensor-id", default="air-sim-1", help="id advertised and published (default: air-sim-1)")
    parser.add_argument("--broker", default="breathinghouse.local", help="Pi MQTT host (default: breathinghouse.local)")
    parser.add_argument("--port", type=int, default=1883, help="MQTT port (default: 1883)")
    parser.add_argument("--qos", type=int, default=1, choices=(0, 1, 2))
    parser.add_argument("--interval", type=float, default=3.0, help="seconds between air readings after admit")
    parser.add_argument(
        "--wifi-mac",
        default="",
        help="Windows NIC MAC the Pi should allow on 1883 (same as SERVER_MAC / getmac)",
    )
    parser.add_argument(
        "--pairing-url",
        default="",
        help="Pi pairing HTTP (default: http://<broker>:8090)",
    )
    parser.add_argument("--skip-ble", action="store_true", help="publish MQTT only (no pairing radio)")
    parser.add_argument("--alarm", action="store_true", help="publish CO2 1400-2000 so a high-CO2 room rule can fire")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = parse_args(argv or sys.argv[1:])
    if sys.platform != "win32":
        die("run this with Windows Python, not WSL or Linux")
    if args.interval <= 0:
        die("--interval must be > 0")
    mac = args.wifi_mac.strip() or (detect_wifi_mac() or "")
    if not mac:
        die("pass --wifi-mac <Windows NIC MAC> (getmac); auto-detect failed")
    args.wifi_mac = normalize_mac(mac)
    log.info("sensorId=%s wifiMac=%s broker=%s:%s", args.sensor_id, args.wifi_mac, args.broker, args.port)

    if not args.skip_ble:
        try:
            run_ble(args)
        except KeyboardInterrupt:
            stop_beacon()
            log.info("cancelled before admit")
            return 0
    try:
        publish_until_stopped(
            args.broker, args.port, args.qos, args.sensor_id, args.interval, args.alarm
        )
    except KeyboardInterrupt:
        log.info("stopped")
    finally:
        stop_beacon()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
