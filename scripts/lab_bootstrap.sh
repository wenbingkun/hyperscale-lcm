#!/usr/bin/env bash
# Register the lab assets without network discovery; repeated runs preserve approval and rejection.
set -euo pipefail

CORE="${LCM_CORE_URL:-http://127.0.0.1:8080}"
USER_NAME="${LCM_LAB_USER:-admin}"
PASSWORD="${LCM_LAB_PASSWORD:-admin123}"
SATELLITE_IP="172.30.77.20"
SSHD_IP="172.30.77.30"
APPROVE_TARGET=false
case "${1:-}" in
  "") ;;
  --approve-target) APPROVE_TARGET=true ;;
  *) echo "Usage: $0 [--approve-target]" >&2; exit 2 ;;
esac

LOGIN="$(jq -cn --arg username "$USER_NAME" --arg password "$PASSWORD" \
  '{username:$username,password:$password,tenantId:"default"}')"
TOKEN="$(curl -fsS -X POST "$CORE/api/auth/login" -H 'Content-Type: application/json' -d "$LOGIN" | jq -er .token)"
api() { curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

find_or_add_device() { # ip hostname -> device JSON
  local existing
  existing="$(api "$CORE/api/discovery" | jq -c --arg ip "$1" \
    '[.[] | select(.ipAddress == $ip)] | if length == 0 then empty elif length == 1 then .[0] else error("Multiple devices have the lab IP") end')" || return
  if [[ -n "$existing" ]]; then
    echo "$existing"
  else
    api -X POST "$CORE/api/discovery" -d "$(jq -cn --arg ip "$1" --arg hostname "$2" '{ipAddress:$ip,hostname:$hostname}')"
  fi
}

approve_device() {
  local id status
  id="$(jq -er .id <<< "$1")"
  status="$(jq -er .status <<< "$1")"
  case "$status" in
    PENDING) api -X POST "$CORE/api/discovery/$id/approve" >/dev/null ;;
    APPROVED|MANAGED) ;;
    *) echo "Refusing to approve lab device $id in status $status; review it manually." >&2; return 1 ;;
  esac
}

SAT="$(find_or_add_device "$SATELLITE_IP" lab-satellite)"
approve_device "$SAT"
echo "Satellite device $(jq -r .id <<< "$SAT") approved ($SATELLITE_IP); registration may take ~1 minute."

TARGET="$(find_or_add_device "$SSHD_IP" lab-sshd)"
TARGET_ID="$(jq -er .id <<< "$TARGET")"
TARGET_STATUS="$(jq -er .status <<< "$TARGET")"
if $APPROVE_TARGET; then
  approve_device "$TARGET"
  echo "SSH target $TARGET_ID approved ($SSHD_IP)."
else
  case "$TARGET_STATUS" in
    PENDING) echo "SSH target $TARGET_ID is PENDING ($SSHD_IP): approve it on the Discovery page." ;;
    APPROVED|MANAGED) echo "SSH target $TARGET_ID is already $TARGET_STATUS ($SSHD_IP)." ;;
    *) echo "SSH target $TARGET_ID has status $TARGET_STATUS; review it manually." >&2; exit 1 ;;
  esac
fi
