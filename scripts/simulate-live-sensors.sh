#!/usr/bin/env bash
# Continuously publish realistic MQTT events for every sensor in home_api.sensor.
# Each sensor emits about once every INTERVAL seconds; publishes are staggered
# so they do not fire in a synchronized burst.
#
# Usage:
#   ./scripts/simulate-live-sensors.sh [--interval 3] [--host localhost] [--port 1883]
#
# Stop with Ctrl-C, or kill the process recorded in SIM_PID_FILE.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INTERVAL=3
HOST="localhost"
PORT=1883
QOS=1
SIM_PID_FILE="${SIM_PID_FILE:-/tmp/bh-live-sensors.pid}"
SIM_LOG_FILE="${SIM_LOG_FILE:-/tmp/bh-live-sensors.log}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interval)
      [[ $# -ge 2 ]] || die "--interval requires a value"
      INTERVAL="$2"
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
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      die "unknown option: $1"
      ;;
  esac
done

[[ "${INTERVAL}" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--interval must be a positive number"
command -v mosquitto_pub >/dev/null 2>&1 || die "mosquitto_pub is required"
command -v kubectl >/dev/null 2>&1 || die "kubectl is required"

mapfile -t SENSOR_IDS < <(
  kubectl exec deployment/postgres -- \
    psql -U bh -d breathing_house -Atc \
    "SELECT sensor_id FROM home_api.sensor ORDER BY sensor_id;"
)

[[ "${#SENSOR_IDS[@]}" -gt 0 ]] || die "no sensors found in home_api.sensor"

infer_type() {
  local id=$1
  case "${id}" in
    air-*|air_*) printf 'air\n' ;;
    room-*|room_*) printf 'room\n' ;;
    opening-*|opening_*) printf 'opening\n' ;;
    presence-*|presence_*) printf 'presence\n' ;;
    *)
      # Fall back to air — most common telemetry shape.
      printf 'air\n'
      ;;
  esac
}

# Soft random walk helpers keep values looking continuous.
declare -A TEMP HUM CO2 LIGHT OPENING PRESENCE

seed_state() {
  local id=$1 type=$2
  case "${type}" in
    air)
      TEMP["$id"]="$(awk -v s="$RANDOM" 'BEGIN{srand(s); printf "%.1f", 19+rand()*6}')"
      HUM["$id"]=$((35 + RANDOM % 25))
      CO2["$id"]=$((450 + RANDOM % 450))
      ;;
    room)
      TEMP["$id"]="$(awk -v s="$RANDOM" 'BEGIN{srand(s); printf "%.1f", 19+rand()*6}')"
      LIGHT["$id"]=$((80 + RANDOM % 500))
      ;;
    opening)
      if (( RANDOM % 2 )); then OPENING["$id"]=OPEN; else OPENING["$id"]=CLOSED; fi
      ;;
    presence)
      if (( RANDOM % 2 )); then PRESENCE["$id"]=DETECTED; else PRESENCE["$id"]=CLEAR; fi
      ;;
  esac
}

walk_float() {
  local cur=$1 min=$2 max=$3
  awk -v c="$cur" -v lo="$min" -v hi="$max" -v s="$RANDOM" 'BEGIN{
    srand(s);
    n = c + (rand()*0.6 - 0.3);
    if (n < lo) n = lo;
    if (n > hi) n = hi;
    printf "%.1f", n;
  }'
}

walk_int() {
  local cur=$1 min=$2 max=$3 step=$4
  local delta=$(( (RANDOM % (2*step+1)) - step ))
  local n=$((cur + delta))
  if (( n < min )); then n=$min; fi
  if (( n > max )); then n=$max; fi
  printf '%s' "$n"
}

build_payload() {
  local id=$1 type=$2
  case "${type}" in
    air)
      TEMP["$id"]="$(walk_float "${TEMP[$id]}" 17 28)"
      HUM["$id"]="$(walk_int "${HUM[$id]}" 25 70 2)"
      CO2["$id"]="$(walk_int "${CO2[$id]}" 400 1600 25)"
      printf '{"sensorId":"%s","temperature":%s,"humidity":%s,"co2":%s}' \
        "$id" "${TEMP[$id]}" "${HUM[$id]}" "${CO2[$id]}"
      ;;
    room)
      TEMP["$id"]="$(walk_float "${TEMP[$id]}" 17 28)"
      LIGHT["$id"]="$(walk_int "${LIGHT[$id]}" 20 900 40)"
      printf '{"sensorId":"%s","temperature":%s,"light":%s}' \
        "$id" "${TEMP[$id]}" "${LIGHT[$id]}"
      ;;
    opening)
      # Sticky state: flip only ~12% of ticks so openings look real.
      if (( RANDOM % 8 == 0 )); then
        if [[ "${OPENING[$id]}" == OPEN ]]; then OPENING["$id"]=CLOSED; else OPENING["$id"]=OPEN; fi
      fi
      printf '{"sensorId":"%s","state":"%s"}' "$id" "${OPENING[$id]}"
      ;;
    presence)
      if (( RANDOM % 8 == 0 )); then
        if [[ "${PRESENCE[$id]}" == DETECTED ]]; then PRESENCE["$id"]=CLEAR; else PRESENCE["$id"]=DETECTED; fi
      fi
      printf '{"sensorId":"%s","presence":"%s"}' "$id" "${PRESENCE[$id]}"
      ;;
  esac
}

TYPES=()
for id in "${SENSOR_IDS[@]}"; do
  t="$(infer_type "$id")"
  TYPES+=("$t")
  seed_state "$id" "$t"
done

N=${#SENSOR_IDS[@]}
# Sleep between consecutive publishes so each sensor hits ~INTERVAL seconds.
GAP="$(awk -v i="$INTERVAL" -v n="$N" 'BEGIN{printf "%.3f", i/n}')"

printf 'simulating %s sensors every ~%ss (stagger gap %ss) via %s:%s\n' \
  "$N" "$INTERVAL" "$GAP" "$HOST" "$PORT"
printf 'sensors: %s\n' "${SENSOR_IDS[*]}"
printf '%s\n' "$$" >"$SIM_PID_FILE"

cleanup() {
  rm -f "$SIM_PID_FILE"
}
trap cleanup EXIT INT TERM

i=0
while true; do
  id="${SENSOR_IDS[$i]}"
  type="${TYPES[$i]}"
  topic="home/sensors/${type}"
  payload="$(build_payload "$id" "$type")"
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '%s %s %s\n' "$ts" "$topic" "$payload"
  mosquitto_pub -h "$HOST" -p "$PORT" -q "$QOS" -t "$topic" -m "$payload"
  i=$(( (i + 1) % N ))
  sleep "$GAP"
done
