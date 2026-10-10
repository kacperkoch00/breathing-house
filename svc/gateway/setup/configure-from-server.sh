#!/usr/bin/env bash
# Run on the main server (the machine with the microservices). SSHes to the
# Raspberry Pi and applies the gateway scripts there.
#
#   ./svc/gateway/setup/configure-from-server.sh
#
# Knobs (defaults shown):
#   PI_HOST=breathinghouse.local  PI_USER=pi  SERVER_MAC=  ALLOW_FILE=./mqtt-firewall.allow
#
# Extra MAC identities (sensors, other machines) can go in mqtt-firewall.allow
# or as arguments. The Pi sudo password is prompted once if sudo -n is denied.
#
# On WSL, *.local is resolved via Windows DNS (mDNS does not work in the VM).
# Auto-detected MAC is the WSL NIC, not what the Pi sees; set SERVER_MAC to the
# Windows/LAN adapter MAC.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/../app" && pwd)"
ALLOW_FILE="${ALLOW_FILE:-${SCRIPT_DIR}/mqtt-firewall.allow}"
REMOTE_SETUP="${REMOTE_SETUP:-/tmp/breathing-house-gateway-setup}"
REMOTE_APP_SRC="${REMOTE_APP_SRC:-${REMOTE_SETUP}/app}"
PI_HOST="${PI_HOST:-breathinghouse.local}"
PI_USER="${PI_USER:-pi}"
MQTT_PORT="${MQTT_PORT:-1883}"
PI_SSH_IDENTITY="${PI_SSH_IDENTITY:-}"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new)
PI_HOST_ALIAS=""

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

normalize_mac() {
  local mac=$1
  if [[ ! "${mac}" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
    die "invalid MAC address: ${mac}"
  fi
  printf '%s\n' "${mac^^}"
}

mac_seen() {
  local needle=$1 mac
  for mac in "${MACS[@]}"; do
    [[ "${mac}" == "${needle}" ]] && return 0
  done
  return 1
}

add_mac() {
  local mac
  mac="$(normalize_mac "$1")"
  if mac_seen "${mac}"; then
    return
  fi
  MACS+=("${mac}")
}

detect_server_mac() {
  local iface mac
  iface="$(ip -o route show default 2>/dev/null | awk '{print $5; exit}')"
  [[ -n "${iface}" ]] || return 1
  [[ -r "/sys/class/net/${iface}/address" ]] || return 1
  mac="$(<"/sys/class/net/${iface}/address")"
  [[ -n "${mac}" ]] || return 1
  printf '%s\n' "${mac}"
}

load_macs() {
  local mac
  MACS=()

  if [[ -f "${ALLOW_FILE}" ]]; then
    while IFS= read -r mac || [[ -n "${mac}" ]]; do
      mac="${mac%%#*}"
      mac="${mac//[[:space:]]/}"
      [[ -z "${mac}" ]] && continue
      add_mac "${mac}"
    done < "${ALLOW_FILE}"
  fi

  for mac in "$@"; do
    add_mac "${mac}"
  done

  if [[ -n "${SERVER_MAC:-}" ]]; then
    add_mac "${SERVER_MAC}"
  else
    mac="$(detect_server_mac || true)"
    if [[ -n "${mac}" ]]; then
      log "detected this server MAC ${mac} (override with SERVER_MAC if the Pi sees a different NIC)"
      add_mac "${mac}"
    fi
  fi

  if [[ "${#MACS[@]}" -eq 0 ]]; then
    die "no MAC identities. Set SERVER_MAC, add them to ${ALLOW_FILE}, or pass as arguments."
  fi
}

copy_to_pi() {
  [[ -f "${APP_DIR}/heartbeat.py" ]] || die "missing ${APP_DIR}/heartbeat.py"
  [[ -f "${APP_DIR}/pairing.py" ]] || die "missing ${APP_DIR}/pairing.py"
  [[ -f "${APP_DIR}/pairing_ble.py" ]] || die "missing ${APP_DIR}/pairing_ble.py"
  [[ -f "${APP_DIR}/requirements.txt" ]] || die "missing ${APP_DIR}/requirements.txt"
  require_bin scp

  log "copying setup scripts and app to ${PI_USER}@${PI_HOST}:${REMOTE_SETUP}"
  ssh "${SSH_OPTS[@]}" "${PI_USER}@${PI_HOST}" \
    "rm -rf '${REMOTE_SETUP}' && mkdir -p '${REMOTE_SETUP}' '${REMOTE_APP_SRC}'"
  scp "${SSH_OPTS[@]}" \
    "${SCRIPT_DIR}/setup-pi-mqtt.sh" \
    "${SCRIPT_DIR}/setup-pi-firewall.sh" \
    "${SCRIPT_DIR}/setup-pi-app.sh" \
    "${SCRIPT_DIR}/allow-mqtt-mac.sh" \
    "${PI_USER}@${PI_HOST}:${REMOTE_SETUP}/"
  scp "${SSH_OPTS[@]}" \
    "${APP_DIR}/heartbeat.py" \
    "${APP_DIR}/pairing.py" \
    "${APP_DIR}/pairing_ble.py" \
    "${APP_DIR}/requirements.txt" \
    "${PI_USER}@${PI_HOST}:${REMOTE_APP_SRC}/"
}

run_setup_on_pi() {
  local mac_args="" mac
  for mac in "${MACS[@]}"; do
    mac_args+=" $(printf '%q' "${mac}")"
  done

  log "running Pi setup (sudo may ask for ${PI_USER}'s password)"
  ssh -t "${SSH_OPTS[@]}" "${PI_USER}@${PI_HOST}" \
    "sudo -v && \
     sudo bash '${REMOTE_SETUP}/setup-pi-mqtt.sh' && \
     sudo bash '${REMOTE_SETUP}/setup-pi-firewall.sh'${mac_args} && \
     sudo bash '${REMOTE_SETUP}/setup-pi-app.sh' '${REMOTE_APP_SRC}' '${PI_USER}'"
}

probe_mqtt() {
  log "probing MQTT on ${PI_HOST}:${MQTT_PORT}"
  if command -v nc >/dev/null 2>&1 && nc -z -w 5 "${PI_HOST}" "${MQTT_PORT}"; then
    log "MQTT reachable from this server"
    return
  fi
  warn "MQTT ${PI_HOST}:${MQTT_PORT} not reachable yet (Mosquitto may still be localhost-only)"
}

require_bin() {
  command -v "$1" >/dev/null 2>&1 || die "required binary not found: $1"
}

in_wsl() {
  [[ -f /proc/sys/fs/binfmt_misc/WSLInterop ]]
}

resolve_wsl_host() {
  in_wsl || return 0
  [[ "${PI_HOST}" == *.local ]] || return 0
  command -v powershell.exe >/dev/null 2>&1 || return 0
  local ip
  ip="$(powershell.exe -NoProfile -Command "(Resolve-DnsName '${PI_HOST}' -Type A -ErrorAction Stop).IPAddress" 2>/dev/null | tr -d '\r' | awk '/^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ { print $1; exit }')"
  if [[ -z "${ip}" ]]; then
    warn "WSL cannot resolve ${PI_HOST}; set PI_HOST to the Pi IPv4 (Windows: ping -4 ${PI_HOST})"
    return 0
  fi
  log "resolved ${PI_HOST} via Windows to ${ip}"
  PI_HOST_ALIAS="${PI_HOST}"
  PI_HOST="${ip}"
}

configure_ssh() {
  if [[ -z "${PI_SSH_IDENTITY}" && -f "${HOME}/.ssh/id_ed25519_windows" ]]; then
    PI_SSH_IDENTITY="${HOME}/.ssh/id_ed25519_windows"
  fi
  if [[ -n "${PI_SSH_IDENTITY}" ]]; then
    SSH_OPTS+=(-o "IdentityFile=${PI_SSH_IDENTITY}" -o IdentitiesOnly=yes)
  fi
  if [[ -n "${PI_HOST_ALIAS}" ]]; then
    SSH_OPTS+=(-o "HostKeyAlias=${PI_HOST_ALIAS}")
  fi
}

unlock_ssh_identity() {
  [[ -n "${PI_SSH_IDENTITY}" ]] || return 0
  [[ -f "${PI_SSH_IDENTITY}" ]] || die "SSH identity not found: ${PI_SSH_IDENTITY}"

  if [[ -z "${SSH_AUTH_SOCK:-}" ]] || ! ssh-add -l >/dev/null 2>&1; then
    log "starting ssh-agent"
    eval "$(ssh-agent -s)" >/dev/null
  fi

  local fp
  fp="$(ssh-keygen -lf "${PI_SSH_IDENTITY}" | awk '{print $2}')"
  if ssh-add -l 2>/dev/null | grep -qF -- "${fp}"; then
    return 0
  fi

  log "unlock ${PI_SSH_IDENTITY} (same passphrase as Windows ssh)"
  if [[ -r /dev/tty ]]; then
    ssh-add "${PI_SSH_IDENTITY}" < /dev/tty
  else
    ssh-add "${PI_SSH_IDENTITY}"
  fi
}

check_ssh() {
  local err
  log "checking SSH to ${PI_USER}@${PI_HOST}"
  if err="$(ssh "${SSH_OPTS[@]}" "${PI_USER}@${PI_HOST}" true 2>&1)"; then
    return
  fi
  if [[ "${err}" == *"Could not resolve hostname"* ]]; then
    die "cannot resolve ${PI_HOST}. From WSL, set PI_HOST to the Pi IPv4 (Windows: ping -4 breathinghouse.local)."
  fi
  if [[ "${err}" == *"Connection timed out"* ]]; then
    die "no SSH response from ${PI_HOST}:22. Confirm the Pi IPv4 with Windows: ping -4 breathinghouse.local"
  fi
  if [[ "${err}" == *"Host key verification failed"* ]]; then
    die "SSH host key mismatch for ${PI_HOST}. The Pi host key is not in ~/.ssh/known_hosts (this is not your login key). From Windows, copy the breathinghouse.local line from %USERPROFILE%\\.ssh\\known_hosts into ~/.ssh/known_hosts."
  fi
  die "cannot SSH to ${PI_USER}@${PI_HOST}: ${err}
If Windows ssh asks for a key passphrase, unlock it once: ssh-add ${PI_SSH_IDENTITY:-~/.ssh/id_ed25519_windows}"
}

main() {
  require_bin ssh
  load_macs "$@"
  resolve_wsl_host
  configure_ssh
  unlock_ssh_identity
  check_ssh
  copy_to_pi
  run_setup_on_pi
  probe_mqtt

  cat <<EOF

Pi gateway configured at ${PI_HOST}:${MQTT_PORT}
Allowed MACs: ${MACS[*]}

Point the cluster collector at the Pi:
  MQTT_MODE=external MQTT_BROKER_IP=${PI_HOST} MQTT_BROKER_PORT=${MQTT_PORT} \\
    ./scripts/setup-desktop.sh

Or only override the collector:
  helm upgrade --install sensors-data-collector deploy/helm/sensors-data-collector \\
    --set env.MQTT_BROKER_IP=${PI_HOST} \\
    --set env.MQTT_BROKER_PORT_NUMBER=${MQTT_PORT}
EOF
}

main "$@"
