#!/usr/bin/env bash
# lab_prepare.sh - generate everything the single-host lab needs under lab/data (git-ignored):
# mTLS certs (via generate_keys.sh if missing), a per-Satellite client certificate, the SSH lab key pair,
# the sshd host key and a strict known_hosts entry. Idempotent; never overwrites existing lab/data files.
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
DATA="lab/data"
SSHD_IP="172.30.77.30"

[[ -f certs/ca.pem ]] || ./scripts/generate_keys.sh

mkdir -p "$DATA/ssh/keys" "$DATA/sshd"

if [[ ! -f "$DATA/satellite-certs/client.pem" ]]; then
  ./scripts/issue_satellite_cert.sh lab "$DATA/.issued"
  mv "$DATA/.issued/lab" "$DATA/satellite-certs" && rmdir "$DATA/.issued"
fi

[[ -f "$DATA/ssh/keys/lab_ed25519" ]] || ssh-keygen -q -t ed25519 -N '' -C lcm-lab -f "$DATA/ssh/keys/lab_ed25519"
[[ -f "$DATA/sshd/ssh_host_ed25519_key" ]] || ssh-keygen -q -t ed25519 -N '' -C lcm-lab-host -f "$DATA/sshd/ssh_host_ed25519_key"
[[ -f "$DATA/sshd/authorized_keys" ]] || cp "$DATA/ssh/keys/lab_ed25519.pub" "$DATA/sshd/authorized_keys"
[[ -f "$DATA/ssh/known_hosts" ]] || echo "$SSHD_IP $(cut -d' ' -f1,2 "$DATA/sshd/ssh_host_ed25519_key.pub")" > "$DATA/ssh/known_hosts"

# Both lab readers run as root in their containers; keep private keys private on the host.
chmod 700 "$DATA" "$DATA/ssh" "$DATA/ssh/keys" "$DATA/satellite-certs"
chmod 755 "$DATA/sshd"
chmod 600 "$DATA/sshd/ssh_host_ed25519_key" "$DATA/ssh/keys/lab_ed25519" "$DATA/satellite-certs/client.key"
chmod 644 "$DATA/sshd/authorized_keys" "$DATA/sshd/ssh_host_ed25519_key.pub" "$DATA/ssh/keys/lab_ed25519.pub"
echo "Lab data ready in $DATA (sshd target ${SSHD_IP}, Satellite 172.30.77.20)."
