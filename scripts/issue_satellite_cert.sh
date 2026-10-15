#!/usr/bin/env bash
# issue_satellite_cert.sh - issue a per-Satellite client certificate from the existing CA (certs/ca.*).
# Each Satellite needs its own certificate: Core identifies a Satellite by its certificate fingerprint.
# Usage: scripts/issue_satellite_cert.sh <name> [out-dir]
#   Writes <out-dir>/<name>/client.pem and client.key (default out-dir: certs/satellites).
#   Mount that directory as the Satellite's LCM_CERTS_DIR together with ca.pem.

set -euo pipefail

NAME="${1:-}"
if [[ ! "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo "Usage: $0 <name> [out-dir]   (name: letters, digits, . _ -)" >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CA_DIR="$ROOT_DIR/certs"
OUT_DIR="${2:-$CA_DIR/satellites}/$NAME"

[[ -f "$CA_DIR/ca.pem" && -f "$CA_DIR/ca.key" ]] || { echo "Run scripts/generate_keys.sh first (certs/ca.* missing)." >&2; exit 1; }
[[ ! -e "$OUT_DIR/client.pem" ]] || { echo "$OUT_DIR/client.pem already exists; refusing to overwrite." >&2; exit 1; }

mkdir -p "$OUT_DIR"
umask 077
openssl genpkey -algorithm RSA -out "$OUT_DIR/client.key" -pkeyopt rsa_keygen_bits:2048
openssl req -new -key "$OUT_DIR/client.key" -out "$OUT_DIR/client.csr" -subj "/CN=satellite-$NAME"
openssl x509 -req -days 365 -in "$OUT_DIR/client.csr" -CA "$CA_DIR/ca.pem" -CAkey "$CA_DIR/ca.key" \
  -CAcreateserial -CAserial "$OUT_DIR/ca.srl" -out "$OUT_DIR/client.pem"
cp "$CA_DIR/ca.pem" "$OUT_DIR/ca.pem"
rm -f "$OUT_DIR/client.csr" "$OUT_DIR/ca.srl"

echo "Issued $OUT_DIR/client.pem"
echo "SHA-256 fingerprint: $(openssl x509 -in "$OUT_DIR/client.pem" -outform DER | sha256sum | cut -d' ' -f1)"
