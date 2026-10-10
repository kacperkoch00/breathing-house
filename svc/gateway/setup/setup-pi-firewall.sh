#!/usr/bin/env bash
# Restrict MQTT (TCP 1883) to localhost plus a MAC allowlist.
# MAC is the LAN identity: DHCP IPs change, MACs stay with the NIC.
# SSH and other ports are left alone.
#
# Run on the Pi:
#   sudo ./svc/gateway/setup/setup-pi-firewall.sh
#   sudo ./svc/gateway/setup/setup-pi-firewall.sh dc:a6:32:11:22:33
#
# Extra MACs can go in svc/gateway/setup/mqtt-firewall.allow or as arguments.
set -euo pipefail

if [[ -n "${BASH_SOURCE[0]:-}" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
  SCRIPT_DIR=""
fi
ALLOW_FILE="${ALLOW_FILE:-${SCRIPT_DIR:+${SCRIPT_DIR}/}mqtt-firewall.allow}"
STATE_DIR="${STATE_DIR:-/etc/breathing-house}"
STATE_ALLOW="${STATE_ALLOW:-${STATE_DIR}/mqtt-firewall.allow}"
CHAIN="BH-MQTT"
MQTT_PORT=1883

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_root() {
  if [[ "$(id -u)" -ne 0 ]]; then
    die "run as root (sudo $0)"
  fi
}

normalize_mac() {
  local mac=$1
  if [[ ! "${mac}" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
    die "invalid MAC address: ${mac}"
  fi
  printf '%s\n' "${mac^^}"
}

add_mac() {
  local mac existing
  mac="$(normalize_mac "$1")"
  for existing in "${MACS[@]+"${MACS[@]}"}"; do
    if [[ "${existing}" == "${mac}" ]]; then
      return
    fi
  done
  MACS+=("${mac}")
}

load_macs_from_file() {
  local file=$1 mac
  [[ -f "${file}" ]] || return 0
  while IFS= read -r mac || [[ -n "${mac}" ]]; do
    mac="${mac%%#*}"
    mac="${mac//[[:space:]]/}"
    [[ -z "${mac}" ]] && continue
    add_mac "${mac}"
  done < "${file}"
}

load_macs() {
  local mac
  MACS=()
  load_macs_from_file "${ALLOW_FILE}"
  if [[ "${STATE_ALLOW}" != "${ALLOW_FILE}" ]]; then
    load_macs_from_file "${STATE_ALLOW}"
  fi
  for mac in "$@"; do
    add_mac "${mac}"
  done

  if [[ "${#MACS[@]}" -eq 0 ]]; then
    die "no MAC identities given. Add them to ${ALLOW_FILE} or pass as arguments.
Example: sudo $0 dc:a6:32:11:22:33"
  fi
}

persist_allow_file() {
  install -d -m 755 "${STATE_DIR}"
  printf '%s\n' "${MACS[@]}" > "${STATE_ALLOW}"
  chmod 644 "${STATE_ALLOW}"
}

install_self() {
  local dest="/usr/local/sbin/setup-pi-mqtt-firewall"
  local src="${BASH_SOURCE[0]:-}"
  [[ -n "${src}" && -f "${src}" ]] || return 0
  [[ "${src}" -ef "${dest}" ]] && return 0
  install -m 755 "${src}" "${dest}"
}

ensure_iptables() {
  command -v apt-get >/dev/null 2>&1 || die "apt-get not found; this script targets Raspberry Pi OS / Debian"
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q 'Status: active'; then
    die "ufw is active and would override these rules; disable it first (sudo ufw disable)"
  fi
  if ! command -v iptables >/dev/null 2>&1; then
    log "installing iptables"
    apt-get update -y
    apt-get install -y iptables
  fi
}

apply_chain() {
  local ipt=$1 localhost=$2
  local mac

  if "${ipt}" -n -L "${CHAIN}" >/dev/null 2>&1; then
    "${ipt}" -F "${CHAIN}"
  else
    "${ipt}" -N "${CHAIN}"
  fi

  while "${ipt}" -D INPUT -p tcp --dport "${MQTT_PORT}" -j "${CHAIN}" 2>/dev/null; do
    :
  done
  "${ipt}" -I INPUT -p tcp --dport "${MQTT_PORT}" -j "${CHAIN}"

  "${ipt}" -A "${CHAIN}" -p tcp -s "${localhost}" -j ACCEPT
  for mac in "${MACS[@]}"; do
    "${ipt}" -A "${CHAIN}" -p tcp -m mac --mac-source "${mac}" -j ACCEPT
  done
  # iptables-nft requires -p tcp with tcp-reset; without it RULE_APPEND fails.
  "${ipt}" -A "${CHAIN}" -p tcp -j REJECT --reject-with tcp-reset
}

persist_rules() {
  if ! dpkg -s iptables-persistent >/dev/null 2>&1; then
    log "installing iptables-persistent"
    DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
  fi
  netfilter-persistent save
}

require_root
load_macs "$@"
ensure_iptables

log "allowing MQTT ${MQTT_PORT} from localhost and ${#MACS[@]} MAC identity(ies)"
apply_chain iptables "127.0.0.0/8"
if command -v ip6tables >/dev/null 2>&1; then
  apply_chain ip6tables "::1"
fi
persist_rules
persist_allow_file
install_self
log "MQTT firewall ready"
