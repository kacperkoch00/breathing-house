#!/bin/sh
set -eu

export HOME_API_UPSTREAM="${HOME_API_UPSTREAM:-http://home-api.default.svc.cluster.local:8082}"

# Strip a trailing slash so /api/... is forwarded intact to home-api.
HOME_API_UPSTREAM="${HOME_API_UPSTREAM%/}"
export HOME_API_UPSTREAM

# First nameserver from the container (CoreDNS in k8s, 127.0.0.11 in Docker).
if [ -z "${NGINX_RESOLVER:-}" ]; then
  NGINX_RESOLVER="$(awk '/^nameserver/{print $2; exit}' /etc/resolv.conf)"
fi
if [ -z "${NGINX_RESOLVER}" ]; then
  echo "home-dashboard: no DNS nameserver found in /etc/resolv.conf" >&2
  exit 1
fi
export NGINX_RESOLVER

envsubst '${HOME_API_UPSTREAM} ${NGINX_RESOLVER}' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf

if ! nginx -t -c /tmp/nginx.conf; then
  echo "home-dashboard: nginx config test failed" >&2
  exit 1
fi

exec nginx -c /tmp/nginx.conf -g 'daemon off;'
