#!/usr/bin/env bash
# Append one Wi-Fi MAC to the MQTT allowlist and reload iptables.
# Installed on the Pi as /usr/local/sbin/bh-allow-mqtt-mac (root only).
#
#   sudo /usr/local/sbin/bh-allow-mqtt-mac aa:bb:cc:dd:ee:ff
set -euo pipefail

STATE_DIR="${STATE_DIR:-/etc/breathing-house}"
STATE_ALLOW="${STATE_ALLOW:-${STATE_DIR}/mqtt-firewall.allow}"
FIREWALL="${FIREWALL:-/usr/local/sbin/setup-pi-mqtt-firewall}"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

if [[ "$(id -u)" -ne 0 ]]; then
  die "run as root"
fi
[[ $# -eq 1 ]] || die "usage: $0 <mac>"

mac=$1
if [[ ! "${mac}" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
  die "invalid MAC address: ${mac}"
fi
mac="${mac^^}"

[[ -x "${FIREWALL}" ]] || die "firewall script not installed: ${FIREWALL}"

install -d -m 755 "${STATE_DIR}"
touch "${STATE_ALLOW}"
if ! grep -qi "^${mac}$" "${STATE_ALLOW}"; then
  printf '%s\n' "${mac}" >> "${STATE_ALLOW}"
fi

ALLOW_FILE="${STATE_ALLOW}" "${FIREWALL}"
