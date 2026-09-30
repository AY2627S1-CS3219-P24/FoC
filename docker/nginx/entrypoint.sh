#!/bin/sh
set -eu

export CERT_DIR="${CERT_DIR:-/etc/nginx/certs}"
export DOMAIN="${DOMAIN:-localhost}"

if [ ! -f "${CERT_DIR}/fullchain.pem" ] || [ ! -f "${CERT_DIR}/privkey.pem" ]; then
  echo "No TLS certs found; generating self-signed certificate for ${DOMAIN}..."
  sh /generate-certs.sh
fi

exec /docker-entrypoint.sh nginx -g 'daemon off;'
