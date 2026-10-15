#!/usr/bin/env bash
# lab_bootstrap.sh - after `docker compose ... up -d`, register the lab devices through the REST API
# (no network discovery needed): the Satellite's own address (approved, so it may register) and the SSH
# target (left PENDING so you can approve it in the UI, or pass --approve-target).
set -euo pipefail

CORE="${LCM_CORE_URL:-http://127.0.0.1:8080}"
USER_NAME="${LCM_LAB_USER:-admin}"
PASSWORD="${LCM_LAB_PASSWORD:-admin123}"
SATELLITE_IP="172.30.77.20"
SSHD_IP="172.30.77.30"
APPROVE_TARGET=false
[[ "${1:-}" == "--approve-target" ]] && APPROVE_TARGET=true

TOKEN="$(curl -sf -X POST "$CORE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\",\"tenantId\":\"default\"}" | jq -r .token)"
[[ -n "$TOKEN" && "$TOKEN" != null ]] || { echo "login failed" >&2; exit 1; }
api() { curl -sf -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

add_device() { # ip hostname -> id
  api -X POST "$CORE/api/discovery" -d "{\"ipAddress\":\"$1\",\"hostname\":\"$2\"}" | jq -r .id
}

SAT_ID="$(add_device "$SATELLITE_IP" lab-satellite)"
api -X POST "$CORE/api/discovery/$SAT_ID/approve" >/dev/null
echo "Satellite device $SAT_ID approved ($SATELLITE_IP); it registers within ~1 minute."

TARGET_ID="$(add_device "$SSHD_IP" lab-sshd)"
if $APPROVE_TARGET; then
  api -X POST "$CORE/api/discovery/$TARGET_ID/approve" >/dev/null
  echo "SSH target $TARGET_ID approved ($SSHD_IP)."
else
  echo "SSH target $TARGET_ID registered as PENDING ($SSHD_IP): approve it on the Discovery page."
fi
