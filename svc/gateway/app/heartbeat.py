#!/usr/bin/env python3
"""Publish gateway STATUS heartbeats to MQTT.

sensors-data-collector consumes home/gateway/status, wraps the payload as a
schema-v1 STATUS envelope, and publishes it to Kafka status-data. home-api
treats a fresh heartbeat for SENSOR_GATEWAY_ID=gateway as online
(SENSOR_GATEWAY_HEARTBEAT_TIMEOUT, default 30s). The MQTT body only needs a
non-blank status string; ONLINE is what CI uses.
"""

from __future__ import annotations

import json
import logging
import os
import signal
import sys
import time

import paho.mqtt.client as mqtt

import pairing

MQTT_HOST = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_TOPIC = os.environ.get("MQTT_TOPIC", "home/gateway/status")
MQTT_QOS = int(os.environ.get("MQTT_QOS", "1"))
STATUS = os.environ.get("GATEWAY_STATUS", "ONLINE")
INTERVAL = float(os.environ.get("HEARTBEAT_INTERVAL", "10"))

log = logging.getLogger("gateway")
_stop = False


def _on_connect(client, userdata, flags, reason_code, properties=None):
    if reason_code != 0:
        log.error("MQTT connect failed: %s", reason_code)
        return
    log.info("connected to %s:%s", MQTT_HOST, MQTT_PORT)


def _handle_stop(signum, frame):
    global _stop
    _stop = True


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if INTERVAL <= 0:
        log.error("HEARTBEAT_INTERVAL must be > 0")
        return 1

    payload = json.dumps({"status": STATUS}, separators=(",", ":"))
    signal.signal(signal.SIGINT, _handle_stop)
    signal.signal(signal.SIGTERM, _handle_stop)

    pairing.start_in_thread()

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="breathing-house-gateway")
    client.on_connect = _on_connect
    client.reconnect_delay_set(min_delay=1, max_delay=30)
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)
    client.loop_start()

    log.info("publishing %s to %s every %ss", payload, MQTT_TOPIC, INTERVAL)
    try:
        while not _stop:
            info = client.publish(MQTT_TOPIC, payload, qos=MQTT_QOS)
            try:
                info.wait_for_publish(timeout=5)
            except RuntimeError:
                log.warning("publish not confirmed; will retry")
            else:
                log.info("heartbeat sent")
            deadline = time.monotonic() + INTERVAL
            while not _stop and time.monotonic() < deadline:
                time.sleep(0.2)
    finally:
        client.loop_stop()
        client.disconnect()
    return 0


if __name__ == "__main__":
    sys.exit(main())
