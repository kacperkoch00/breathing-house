#!/bin/sh
set -eu

export HOME_API_UPSTREAM="${HOME_API_UPSTREAM:-http://home-api:8082}"

# Strip a trailing slash so /api/... is forwarded intact to home-api.
HOME_API_UPSTREAM="${HOME_API_UPSTREAM%/}"
export HOME_API_UPSTREAM

envsubst '${HOME_API_UPSTREAM}' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf

exec nginx -c /tmp/nginx.conf -g 'daemon off;'
