#!/usr/bin/env bash
# Publish one or more randomized valid MQTT sensor events to a broker.
#
# Usage:
#   ./scripts/publish-sensor-event.sh <type> [--sensor-id <id>] [--count 1]
#     [--host localhost] [--port 1883] [--qos 1]
#
# <type> is one of: room | air | opening | presence | status
#
# Defaults target the desktop host Mosquitto (localhost:1883). Collector pods
# use host.minikube.internal — do not publish with that address from the host.
set -euo pipefail

MQTT_IMAGE="${MQTT_IMAGE:-eclipse-mosquitto:2.0.18}"

TYPE=""
SENSOR_ID=""
COUNT=1
HOST="localhost"
PORT=1883
QOS=1

usage() {
  cat <<'USAGE'
Usage: ./scripts/publish-sensor-event.sh <type> [options]

Publish randomized valid MQTT payloads for Breathing House sensors.

Types:
  room       home/sensors/room      {"sensorId":…,"temperature":…,"light":…}
  air        home/sensors/air       {"sensorId":…,"temperature":…,"humidity":…,"co2":…}
  opening    home/sensors/opening   {"sensorId":…,"state":"OPEN"|"CLOSED"}
  presence   home/sensors/presence  {"sensorId":…,"presence":"DETECTED"|"CLEAR"}
  status     home/gateway/status    {"status":"…"}

Options:
  --sensor-id <id> Sensor identifier (default: <type>-1, max 200 chars); ignored for status
  --count <n>      Number of messages to publish (default: 1)
  --host <host>    Broker host from the publisher side (default: localhost)
  --port <port>    Broker port (default: 1883)
  --qos <0|1|2>    MQTT QoS (default: 1)
  -h, --help       Show this help

Examples:
  ./scripts/publish-sensor-event.sh air
  ./scripts/publish-sensor-event.sh room --sensor-id living-room-1 --count 3
USAGE
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

rand_int() {
  local min=$1 max=$2
  echo $((min + RANDOM % (max - min + 1)))
}

rand_float() {
  # one decimal place in [min, max] inclusive of integer bounds
  local min=$1 max=$2
  local whole frac
  whole="$(rand_int "${min}" "${max}")"
  frac="$(rand_int 0 9)"
  if [[ "${whole}" -eq "${max}" ]]; then
    frac=0
  fi
  printf '%s.%s' "${whole}" "${frac}"
}

pick() {
  local n=$#
  local i
  i="$(rand_int 1 "${n}")"
  printf '%s' "${!i}"
}

build_topic() {
  case "${TYPE}" in
    status) printf 'home/gateway/status\n' ;;
    room|air|opening|presence) printf 'home/sensors/%s\n' "${TYPE}" ;;
    *) die "unknown type: ${TYPE} (expected room|air|opening|presence|status)" ;;
  esac
}

build_payload() {
  local temp humidity co2 light state presence status
  case "${TYPE}" in
    room)
      temp="$(rand_float 18 26)"
      light="$(rand_int 50 800)"
      printf '{"sensorId":"%s","temperature":%s,"light":%s}\n' "${SENSOR_ID}" "${temp}" "${light}"
      ;;
    air)
      temp="$(rand_float 18 26)"
      humidity="$(rand_int 30 60)"
      co2="$(rand_int 400 1200)"
      printf '{"sensorId":"%s","temperature":%s,"humidity":%s,"co2":%s}\n' "${SENSOR_ID}" "${temp}" "${humidity}" "${co2}"
      ;;
    opening)
      state="$(pick OPEN CLOSED)"
      printf '{"sensorId":"%s","state":"%s"}\n' "${SENSOR_ID}" "${state}"
      ;;
    presence)
      presence="$(pick DETECTED CLEAR)"
      printf '{"sensorId":"%s","presence":"%s"}\n' "${SENSOR_ID}" "${presence}"
      ;;
    status)
      status="$(pick ONLINE OK READY)"
      # occasionally a short random token instead of a fixed label
      if [[ "$(rand_int 0 3)" -eq 0 ]]; then
        status="tok-$(rand_int 1000 9999)"
      fi
      printf '{"status":"%s"}\n' "${status}"
      ;;
  esac
}

detect_runtime() {
  if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    printf 'podman\n'
    return
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    printf 'docker\n'
    return
  fi
  die "need mosquitto_pub on PATH, or a working podman/docker runtime"
}

publish_one() {
  local topic=$1 payload=$2
  printf 'topic=%s\npayload=%s\n' "${topic}" "${payload}"

  if command -v mosquitto_pub >/dev/null 2>&1; then
    mosquitto_pub -h "${HOST}" -p "${PORT}" -q "${QOS}" -t "${topic}" -m "${payload}"
    return
  fi

  local runtime
  runtime="$(detect_runtime)"
  "${runtime}" run --rm --network host \
    "${MQTT_IMAGE}" \
    mosquitto_pub -h "${HOST}" -p "${PORT}" -q "${QOS}" -t "${topic}" -m "${payload}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --sensor-id)
      [[ $# -ge 2 ]] || die "--sensor-id requires a value"
      SENSOR_ID="$2"
      shift 2
      ;;
    --count)
      [[ $# -ge 2 ]] || die "--count requires a value"
      COUNT="$2"
      shift 2
      ;;
    --host)
      [[ $# -ge 2 ]] || die "--host requires a value"
      HOST="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || die "--port requires a value"
      PORT="$2"
      shift 2
      ;;
    --qos)
      [[ $# -ge 2 ]] || die "--qos requires a value"
      QOS="$2"
      shift 2
      ;;
    -*)
      die "unknown option: $1 (try --help)"
      ;;
    *)
      if [[ -n "${TYPE}" ]]; then
        die "unexpected argument: $1"
      fi
      TYPE="$1"
      shift
      ;;
  esac
done

[[ -n "${TYPE}" ]] || { usage >&2; die "missing <type>"; }
[[ "${COUNT}" =~ ^[1-9][0-9]*$ ]] || die "--count must be a positive integer"
[[ "${QOS}" =~ ^[012]$ ]] || die "--qos must be 0, 1, or 2"
[[ -n "${SENSOR_ID}" ]] || SENSOR_ID="${TYPE}-1"
[[ "${SENSOR_ID}" =~ ^[A-Za-z0-9._:-]{1,200}$ ]] || die "--sensor-id must be 1-200 chars of [A-Za-z0-9._:-]"

topic="$(build_topic)"
i=0
while [[ "${i}" -lt "${COUNT}" ]]; do
  payload="$(build_payload)"
  publish_one "${topic}" "${payload}"
  i=$((i + 1))
  if [[ "${i}" -lt "${COUNT}" ]]; then
    sleep 0.2
  fi
done
