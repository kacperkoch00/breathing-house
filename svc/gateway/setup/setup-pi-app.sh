#!/usr/bin/env bash
# Install (or reinstall) the gateway heartbeat app on the Pi as a systemd service.
#
#   sudo ./svc/gateway/setup/setup-pi-app.sh <app-src-dir> [user]
#
# <app-src-dir> must contain heartbeat.py, pairing.py, pairing_ble.py, and requirements.txt.
# Reinstall removes /opt/breathing-house/gateway and the unit, then installs fresh.
set -euo pipefail

APP_SRC="${1:-}"
APP_USER="${2:-${SUDO_USER:-root}}"
APP_DEST="${APP_DEST:-/opt/breathing-house/gateway}"
UNIT_NAME="breathing-house-gateway"
UNIT_PATH="/etc/systemd/system/${UNIT_NAME}.service"
if [[ -n "${BASH_SOURCE[0]:-}" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
  SCRIPT_DIR=""
fi

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    die "run as root (sudo $0)"
  fi
}

run_as_user() {
  if [[ "${APP_USER}" == "root" ]]; then
    "$@"
  else
    sudo -u "${APP_USER}" -H "$@"
  fi
}

remove_existing() {
  if [[ -f "${UNIT_PATH}" ]]; then
    log "stopping existing ${UNIT_NAME}"
    systemctl stop "${UNIT_NAME}" || true
    systemctl disable "${UNIT_NAME}" || true
    rm -f "${UNIT_PATH}"
  fi
  if [[ -e "${APP_DEST}" ]]; then
    log "removing previous install at ${APP_DEST}"
    rm -rf "${APP_DEST}"
  fi
}

install_python() {
  command -v apt-get >/dev/null 2>&1 || die "apt-get not found; this script targets Raspberry Pi OS / Debian"
  log "installing python3 venv"
  apt-get update -y
  DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv python3-pip bluez
  systemctl enable --now bluetooth || true
}

install_app() {
  local group
  group="$(id -gn "${APP_USER}")"
  id -u "${APP_USER}" >/dev/null 2>&1 || die "user not found: ${APP_USER}"

  log "installing heartbeat app to ${APP_DEST} as ${APP_USER}"
  install -d -o "${APP_USER}" -g "${group}" "${APP_DEST}"
  install -m 755 -o "${APP_USER}" -g "${group}" "${APP_SRC}/heartbeat.py" "${APP_DEST}/heartbeat.py"
  install -m 644 -o "${APP_USER}" -g "${group}" "${APP_SRC}/pairing.py" "${APP_DEST}/pairing.py"
  install -m 644 -o "${APP_USER}" -g "${group}" "${APP_SRC}/pairing_ble.py" "${APP_DEST}/pairing_ble.py"
  install -m 644 -o "${APP_USER}" -g "${group}" "${APP_SRC}/requirements.txt" "${APP_DEST}/requirements.txt"
  if getent group bluetooth >/dev/null; then
    usermod -aG bluetooth "${APP_USER}" || true
  fi
  if [[ -n "${SCRIPT_DIR}" && -f "${SCRIPT_DIR}/setup-pi-firewall.sh" ]]; then
    install -m 755 "${SCRIPT_DIR}/setup-pi-firewall.sh" /usr/local/sbin/setup-pi-mqtt-firewall
  fi
  if [[ -n "${SCRIPT_DIR}" && -f "${SCRIPT_DIR}/allow-mqtt-mac.sh" ]]; then
    install -m 755 "${SCRIPT_DIR}/allow-mqtt-mac.sh" /usr/local/sbin/bh-allow-mqtt-mac
  fi
  cat > /etc/sudoers.d/breathing-house-gateway <<EOF
${APP_USER} ALL=(root) NOPASSWD: /usr/local/sbin/bh-allow-mqtt-mac
EOF
  chmod 440 /etc/sudoers.d/breathing-house-gateway

  run_as_user python3 -m venv "${APP_DEST}/.venv"
  run_as_user "${APP_DEST}/.venv/bin/pip" install -U pip
  run_as_user "${APP_DEST}/.venv/bin/pip" install -r "${APP_DEST}/requirements.txt"

  cat > "${UNIT_PATH}" <<EOF
[Unit]
Description=Breathing House gateway heartbeat
After=network-online.target mosquitto.service bluetooth.service
Wants=network-online.target bluetooth.service

[Service]
Type=simple
User=${APP_USER}
SupplementaryGroups=bluetooth
WorkingDirectory=${APP_DEST}
Environment=MQTT_HOST=127.0.0.1
Environment=MQTT_PORT=1883
Environment=HTTP_HOST=0.0.0.0
Environment=HTTP_PORT=8090
ExecStart=${APP_DEST}/.venv/bin/python ${APP_DEST}/heartbeat.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable --now "${UNIT_NAME}"
  systemctl --no-pager --full status "${UNIT_NAME}" || true
  systemctl is-active --quiet "${UNIT_NAME}" || die "${UNIT_NAME} failed to start"
}

require_root
[[ -n "${APP_SRC}" ]] || die "usage: sudo $0 <app-src-dir> [user]"
[[ -f "${APP_SRC}/heartbeat.py" ]] || die "missing ${APP_SRC}/heartbeat.py"
[[ -f "${APP_SRC}/pairing.py" ]] || die "missing ${APP_SRC}/pairing.py"
[[ -f "${APP_SRC}/pairing_ble.py" ]] || die "missing ${APP_SRC}/pairing_ble.py"
[[ -f "${APP_SRC}/requirements.txt" ]] || die "missing ${APP_SRC}/requirements.txt"

remove_existing
install_python
install_app
log "gateway heartbeat app ready (${UNIT_NAME})"
