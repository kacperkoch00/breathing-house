#!/usr/bin/env bash
# Install Mosquitto on Raspberry Pi OS (Debian). Run on the Pi:
#   sudo ./svc/gateway/setup/setup-pi-mqtt.sh
#
# Writes /etc/mosquitto/conf.d/breathing-house.conf so MQTT listens on LAN
# (Debian 2.x is localhost-only until a listener is set).
set -euo pipefail

MQTT_CONF="/etc/mosquitto/conf.d/breathing-house.conf"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    die "run as root (sudo $0)"
  fi
}

mosquitto_installed() {
  dpkg -s mosquitto >/dev/null 2>&1
}

install_mosquitto() {
  command -v apt-get >/dev/null 2>&1 || die "apt-get not found; this script targets Raspberry Pi OS / Debian"

  log "refreshing apt package lists"
  apt-get update -y

  if mosquitto_installed; then
    log "checking mosquitto for upgrades"
    apt-get install -y --only-upgrade mosquitto mosquitto-clients
  else
    log "installing mosquitto and mosquitto-clients"
    apt-get install -y mosquitto mosquitto-clients
  fi

  systemctl enable mosquitto
}

configure_mosquitto() {
  local tmp
  mkdir -p /etc/mosquitto/conf.d
  tmp="$(mktemp)"
  cat > "${tmp}" <<'EOF'
listener 1883
allow_anonymous true
EOF

  if [[ -f "${MQTT_CONF}" ]] && cmp -s "${tmp}" "${MQTT_CONF}"; then
    rm -f "${tmp}"
    log "mosquitto LAN listener already configured"
  else
    log "configuring mosquitto listener 1883 (anonymous, all interfaces)"
    mv "${tmp}" "${MQTT_CONF}"
    chmod 644 "${MQTT_CONF}"
  fi

  log "restarting mosquitto"
  systemctl restart mosquitto
  systemctl is-active --quiet mosquitto || die "mosquitto failed to start"
}

require_root
install_mosquitto
configure_mosquitto
log "Mosquitto ready"
