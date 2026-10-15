#!/usr/bin/env bash
# lab_prepare.sh - generate everything the single-host lab needs under lab/data (git-ignored):
# mTLS certs (via generate_keys.sh if missing), a per-Satellite client certificate, the SSH lab key pair,
# the sshd host key and a strict known_hosts entry. Idempotent; never overwrites existing lab/data files.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
DATA="lab/data"
SSHD_IP="172.30.77.30"

[[ -f certs/ca.pem ]] || ./scripts/generate_keys.sh

umask 077
mkdir -p "$DATA/ssh/keys" "$DATA/sshd"

if [[ ! -f "$DATA/satellite-certs/client.pem" ]]; then
  ./scripts/issue_satellite_cert.sh lab "$DATA/.issued"
  mv "$DATA/.issued/lab" "$DATA/satellite-certs" && rmdir "$DATA/.issued"
fi

[[ -f "$DATA/ssh/keys/lab_ed25519" ]] || ssh-keygen -q -t ed25519 -N '' -C lcm-lab -f "$DATA/ssh/keys/lab_ed25519"
[[ -f "$DATA/sshd/ssh_host_ed25519_key" ]] || ssh-keygen -q -t ed25519 -N '' -C lcm-lab-host -f "$DATA/sshd/ssh_host_ed25519_key"
cp "$DATA/ssh/keys/lab_ed25519.pub" "$DATA/sshd/authorized_keys"
echo "$SSHD_IP $(cut -d' ' -f1,2 "$DATA/sshd/ssh_host_ed25519_key.pub")" > "$DATA/ssh/known_hosts"

# Containers run as other uids; the files are private to this lab, so make them readable.
chmod -R a+rX "$DATA"
chmod 600 "$DATA/sshd/ssh_host_ed25519_key" 2>/dev/null || true
chmod a+r "$DATA/sshd/ssh_host_ed25519_key" "$DATA/ssh/keys/lab_ed25519" "$DATA/satellite-certs/client.key"
echo "Lab data ready in $DATA (sshd target ${SSHD_IP}, Satellite 172.30.77.20)."
