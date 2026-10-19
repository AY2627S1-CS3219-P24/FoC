#!/usr/bin/env sh
set -eu

CERT_DIR="${CERT_DIR:-$(cd "$(dirname "$0")" && pwd)/certs}"
DOMAIN="${DOMAIN:-localhost}"
DAYS="${CERT_DAYS:-825}"

mkdir -p "${CERT_DIR}"

if [ -f "${CERT_DIR}/fullchain.pem" ] && [ -f "${CERT_DIR}/privkey.pem" ]; then
  echo "TLS certs already exist in ${CERT_DIR}"
  exit 0
fi

echo "Generating self-signed TLS certificate for ${DOMAIN}..."
openssl req -x509 -nodes -newkey rsa:2048 -days "${DAYS}" \
  -keyout "${CERT_DIR}/privkey.pem" \
  -out "${CERT_DIR}/fullchain.pem" \
  -subj "/CN=${DOMAIN}" \
  -addext "subjectAltName=DNS:${DOMAIN},DNS:localhost,IP:127.0.0.1"

chmod 644 "${CERT_DIR}/fullchain.pem"
chmod 600 "${CERT_DIR}/privkey.pem"
echo "Wrote ${CERT_DIR}/fullchain.pem and ${CERT_DIR}/privkey.pem"
