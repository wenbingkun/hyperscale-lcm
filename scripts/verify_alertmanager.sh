#!/usr/bin/env bash
# verify_alertmanager.sh — Dev routing smoke test for Alertmanager.
#
# Prerequisites:
#   docker-compose up -d prometheus alertmanager  (or docker compose)
#   curl, jq installed
#
# Return codes:
#   0 — all checks passed
#   1 — verification failed
#   2 — preconditions not met (missing tools or services)

set -euo pipefail

# ---------------------------------------------------------------------------
# Coloured output (matches check_ci_contract.sh / demo.sh style)
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()  { printf "${CYAN}[verify-am]${NC} %s\n" "$*"; }
ok()    { printf "${GREEN}[verify-am] ✓${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[verify-am] ⚠${NC} %s\n" "$*"; }
fail()  { printf "${RED}[verify-am] ✗${NC} %s\n" "$*" >&2; }
die()   { fail "$*"; exit 1; }

# ---------------------------------------------------------------------------
# Detect docker-compose (v1) vs docker compose (v2)
# ---------------------------------------------------------------------------
COMPOSE_CMD=""
if command -v docker &>/dev/null && docker compose version &>/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif command -v docker-compose &>/dev/null; then
  COMPOSE_CMD="docker-compose"
fi

# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------
preflight() {
  local missing=()
  for cmd in curl jq; do
    if ! command -v "$cmd" &>/dev/null; then
      missing+=("$cmd")
    fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "Missing required tools: ${missing[*]} (exit 2)"
    exit 2
  fi

  if [[ -z "$COMPOSE_CMD" ]]; then
    warn "Neither 'docker compose' (v2) nor 'docker-compose' (v1) found — skipping container status check"
  else
    info "Using compose command: $COMPOSE_CMD"
    for svc in alertmanager prometheus; do
      # docker compose ps returns container name, status, etc.
      if ! $COMPOSE_CMD ps --status running 2>/dev/null | grep -q "$svc"; then
        # Fallback for older docker-compose that doesn't support --status
        if ! $COMPOSE_CMD ps 2>/dev/null | grep "$svc" | grep -qi "up"; then
          die "Service '$svc' is not running. Start it with: $COMPOSE_CMD up -d prometheus alertmanager (exit 2)"
          exit 2
        fi
      fi
    done
    ok "alertmanager and prometheus containers are running"
  fi
}

# ---------------------------------------------------------------------------
# Check Alertmanager health
# ---------------------------------------------------------------------------
check_health() {
  info "Checking Alertmanager health at localhost:9093"
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9093/-/healthy 2>/dev/null || echo "000")
  if [[ "$status" != "200" ]]; then
    die "Alertmanager health check failed (HTTP $status). Is alertmanager running on port 9093?"
  fi
  ok "Alertmanager is healthy (HTTP 200)"
}

# ---------------------------------------------------------------------------
# Push a fake alert
# ---------------------------------------------------------------------------
ALERT_NAME="VerifyAlertmanagerSmoke_$(date +%s)"

push_alert() {
  info "Pushing fake critical alert: $ALERT_NAME"
  local http_code
  http_code=$(curl -s -o /dev/null -w '%{http_code}' \
    -XPOST http://localhost:9093/api/v2/alerts \
    -H 'Content-Type: application/json' \
    -d "[{
      \"labels\": {
        \"alertname\": \"$ALERT_NAME\",
        \"severity\": \"critical\",
        \"job\": \"verify-alertmanager\"
      },
      \"annotations\": {
        \"summary\": \"Smoke test alert from verify_alertmanager.sh\"
      }
    }]" 2>/dev/null || echo "000")

  if [[ "$http_code" != "200" ]]; then
    die "Failed to push alert (HTTP $http_code)"
  fi
  ok "Alert pushed successfully (HTTP 200)"
}

# ---------------------------------------------------------------------------
# Poll for the alert to appear
# ---------------------------------------------------------------------------
poll_alert() {
  info "Polling for alert to appear (max 10 attempts, 1s interval)"
  local attempt
  for attempt in $(seq 1 10); do
    local alerts
    alerts=$(curl -s http://localhost:9093/api/v2/alerts 2>/dev/null || echo "[]")
    if printf '%s' "$alerts" | jq -e ".[] | select(.labels.alertname == \"$ALERT_NAME\")" &>/dev/null; then
      ok "Alert '$ALERT_NAME' found after $attempt attempt(s)"
      return 0
    fi
    sleep 1
  done
  die "Alert '$ALERT_NAME' not found after 10 attempts"
}

# ---------------------------------------------------------------------------
# Check cluster status
# ---------------------------------------------------------------------------
check_status() {
  info "Checking Alertmanager cluster status"
  local status_json
  status_json=$(curl -s http://localhost:9093/api/v2/status 2>/dev/null || echo "{}")
  local cluster_status
  cluster_status=$(printf '%s' "$status_json" | jq -r '.cluster.status // "unknown"' 2>/dev/null || echo "unknown")
  if [[ "$cluster_status" == "ready" || "$cluster_status" == "disabled" ]]; then
    ok "Alertmanager cluster status: $cluster_status"
  else
    warn "Unexpected cluster status: $cluster_status (non-fatal)"
  fi

  # Show config summary
  local receiver_count
  receiver_count=$(printf '%s' "$status_json" | jq -r '.config.original' 2>/dev/null \
    | grep -c '^ *- name:' 2>/dev/null || echo "?")
  info "Active receivers in config: $receiver_count"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
  info "=== Alertmanager Dev Routing Smoke Test ==="
  echo ""
  preflight
  check_health
  push_alert
  poll_alert
  check_status
  echo ""
  ok "All checks passed ✓"
}

main "$@"
