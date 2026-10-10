# Gateway

Raspberry Pi MQTT broker and heartbeat process for Breathing House. Sensors
and this gateway publish to Mosquitto on the Pi. Cluster
`sensors-data-collector` consumes those topics and forwards them to Kafka.

```text
svc/gateway/
  README.md
  app/                         Python heartbeat + pairing HTTP process
    heartbeat.py
    pairing.py
    pairing_ble.py
    requirements.txt
  setup/                       Pi + server install scripts
    configure-from-server.sh   run on the microservice host
    setup-pi-mqtt.sh           run on the Pi (or via the orchestrator)
    setup-pi-firewall.sh
    setup-pi-app.sh
    allow-mqtt-mac.sh
```

## MQTT topics

| Topic | Publisher | MQTT payload | Kafka topic |
| :---- | :-------- | :----------- | :---------- |
| `home/sensors/room` | room sensors | `sensorId`, `temperature`, `light` | `sensor-data` (schema v2) |
| `home/sensors/air` | air sensors | `sensorId`, `temperature`, `humidity`, `co2` | `sensor-data` (schema v2) |
| `home/sensors/opening` | opening sensors | `sensorId`, `state` | `event-data` (schema v2) |
| `home/sensors/presence` | presence sensors | `sensorId`, `presence` | `event-data` (schema v2) |
| `home/gateway/status` | this gateway | `{"status":"ONLINE"}` | `status-data` (schema v1) |

Mosquitto listens on TCP **1883** on all interfaces, anonymous (same as CI).
`setup-pi-mqtt.sh` writes that to `/etc/mosquitto/conf.d/breathing-house.conf`.

## Heartbeat

`app/heartbeat.py` publishes `{"status":"ONLINE"}` to `home/gateway/status`
every 10s (QoS 1). The collector wraps it as a schema-v1 STATUS envelope
(`roomId` = `gateway`) on Kafka `status-data`.

`home-api` treats the gateway as online when the newest heartbeat for
`SENSOR_GATEWAY_ID` (`gateway`) has `received_at` within
`SENSOR_GATEWAY_HEARTBEAT_TIMEOUT` (default 30s). The `status` string is
diagnostic only.

```bash
curl http://home-api.local/api/v1/sensor-gateway/status
# {"online":true}
```

On the Pi the app is installed at `/opt/breathing-house/gateway` and run by
systemd unit `breathing-house-gateway`. Logs go to the journal (no log file):

```bash
journalctl -u breathing-house-gateway -f
journalctl -u breathing-house-gateway -n 50 --no-pager
systemctl status breathing-house-gateway
```

Environment knobs (defaults): `MQTT_HOST=127.0.0.1`, `MQTT_PORT=1883`,
`HEARTBEAT_INTERVAL=10`, `MQTT_TOPIC`, `MQTT_QOS`, `GATEWAY_STATUS`,
`HTTP_HOST=0.0.0.0`, `HTTP_PORT=8090`, `SCAN_WINDOW=60`.

## Pairing HTTP

The same process listens on TCP **8090**. The browser calls the Pi on the LAN
(CORS is open). Press Pair on the sensor (LED), then Scan.

`POST /scan` listens for BLE advertisements of service
`f0b10000-4e00-40b1-8000-00805f9b34fb`. Service data is UTF-8 `TYPE:sensorId`
(`AIR`, `ROOM`, `OPENING`, `PRESENCE`). After the window ends the last list is
kept (`state: complete`) until the next scan.

`POST /admit` does not send Wi-Fi or broker settings. Sensors only talk to
the Pi. Admit reads the board's MQTT MAC over GATT, writes `1` on the admit
characteristic so it may start publishing, and allows that MAC on 1883.

| Method | Path | Body | Result |
| :----- | :--- | :--- | :----- |
| `POST` | `/scan` | none | start a 60s BLE window |
| `GET` | `/candidates` | none | devices currently / last seen in pairing |
| `POST` | `/admit` | `{"sensorId":"air-11"}` | admit + allow MAC; `{sensorId, state, wifiMac}` |

```bash
curl -sS -X POST http://breathinghouse.local:8090/scan
curl -sS http://breathinghouse.local:8090/candidates
curl -sS -X POST http://breathinghouse.local:8090/admit \
  -H 'Content-Type: application/json' \
  -d '{"sensorId":"air-11"}'
```

### Windows air-sensor simulator

There is no firmware in this repo. To exercise Scan → Add → live air data from a
PC, run **Windows Python** (not WSL) on a laptop next to the Pi:

```bat
py -3 -m pip install -r scripts\requirements-air-sensor-windows.txt
py -3 scripts\simulate-air-sensor-windows.py --broker <pi-ipv4> --wifi-mac 9C:C7:D3:E3:8E:CC
```

`--wifi-mac` must be the Windows NIC the Pi already allows (`SERVER_MAC` /
`getmac`). If the laptop radio cannot host a connectable GATT server, the
script falls back to a BLE beacon (`AIR:<id>:<mac>`). The Pi still does a
real BLE scan; Accept is `POST /admit`. A live scan keeps re-advertising the
beacon, so MQTT starts once Scan sees it (GATT admit is not possible).
`--alarm` publishes CO2 in 1550–1950 so the default `high-co2` rule (1500 ppm
for 5 minutes) can fire.

`--skip-ble` publishes MQTT only (same as `publish-sensor-event.sh`).

## Setup from the server

Do this once on the Pi so it stays `breathinghouse.local` after reboot:

```bash
sudo hostnamectl set-hostname breathinghouse
# /etc/hosts: 127.0.1.1	breathinghouse
sudo systemctl restart avahi-daemon
```

Same via `sudo raspi-config` → **System Options** → **Hostname**. Avahi only
advertises `.local`. A name like `breathinghouse.gateway` needs router DNS or
an `/etc/hosts` entry on each client.

From the **repository root** on the machine that runs the microservices:

```bash
PI_USER=kochansk SERVER_MAC=9c:c7:d3:e3:8e:cc \
  ./svc/gateway/setup/configure-from-server.sh
```

`PI_HOST` defaults to `breathinghouse.local`. Extra MAC arguments (sensors)
can also go in `setup/mqtt-firewall.allow`, one MAC per line.

The orchestrator:

1. Resolves `*.local` via Windows DNS when run from WSL
2. Unlocks `~/.ssh/id_ed25519_windows` (or `PI_SSH_IDENTITY`) with `ssh-add`
3. Copies `setup/*.sh` and `app/` to the Pi
4. Runs, in order: Mosquitto (install/upgrade + LAN bind), MQTT firewall
   (localhost + listed MACs on port 1883), heartbeat app reinstall + start
5. Probes `PI_HOST:1883`

SSH needs key auth. sudo prompts for the Pi user password once. Optional
passwordless sudo on the Pi:

```bash
echo 'kochansk ALL=(ALL) NOPASSWD:ALL' | sudo tee /etc/sudoers.d/kochansk
sudo chmod 440 /etc/sudoers.d/kochansk
```

### WSL

WSL cannot resolve `*.local` itself and its NIC MAC (`00:15:5d:…`) is not what
the Pi sees. The orchestrator looks up the IPv4 with Windows
(`ping -4 breathinghouse.local`). Set `SERVER_MAC` to the Windows Wi-Fi or
Ethernet adapter MAC (`getmac`). Copy the Windows OpenSSH key to
`~/.ssh/id_ed25519_windows` if it is not already there.

### Knobs

| Variable | Default | Meaning |
| :------- | :------ | :------ |
| `PI_HOST` | `breathinghouse.local` | Pi hostname or LAN IPv4 |
| `PI_USER` | `pi` | SSH / service user on the Pi |
| `SERVER_MAC` | auto-detect | MAC the Pi should allow on 1883 |
| `PI_SSH_IDENTITY` | `~/.ssh/id_ed25519_windows` if present | SSH private key |
| `MQTT_PORT` | `1883` | Broker port used for the probe |

### Scripts on the Pi only

```bash
sudo ./svc/gateway/setup/setup-pi-mqtt.sh
sudo ./svc/gateway/setup/setup-pi-firewall.sh aa:bb:cc:dd:ee:ff
sudo ./svc/gateway/setup/setup-pi-app.sh ./svc/gateway/app "$USER"
```

`setup-pi-app.sh` always reinstalls: stops the unit, deletes
`/opt/breathing-house/gateway`, recreates the venv, enables the service.

## Point the collector at the Pi

From WSL use the Pi IPv4 from `ping -4 breathinghouse.local` on Windows, not
the `.local` name (pods will not resolve mDNS):

```bash
MQTT_MODE=external MQTT_BROKER_IP=<pi-ipv4> MQTT_BROKER_PORT=1883 \
  SKIP_HOSTS=1 ./scripts/setup-desktop.sh
```

Publish a test air reading from the host (not from a pod):

```bash
./scripts/publish-sensor-event.sh air --sensor-id air-1 --host <pi-ipv4> --count 1
```
