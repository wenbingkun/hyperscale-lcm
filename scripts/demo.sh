#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${LCM_DEMO_RUNTIME_DIR:-/tmp/hyperscale-lcm-demo}"
PID_DIR="$RUNTIME_DIR/pids"

CORE_URL="${LCM_DEMO_CORE_URL:-http://127.0.0.1:8080}"
GRPC_TARGET="${LCM_DEMO_GRPC_TARGET:-127.0.0.1:8080}"
DEMO_CLUSTER="${LCM_DEMO_CLUSTER:-demo-lab-$(date +%s)}"
REDISHOST="${LCM_DEMO_REDFISH_HOST:-127.0.0.1}"
REDFISH_PORT="${LCM_DEMO_REDFISH_PORT:-18443}"
REDFISH_ENDPOINT="${LCM_DEMO_REDFISH_ENDPOINT:-${REDISHOST}:${REDFISH_PORT}}"
SSH_HOST="${LCM_DEMO_SSH_HOST:-127.0.0.1}"
SSH_PORT="${LCM_DEMO_SSH_PORT:-22222}"
SSH_USER="${LCM_DEMO_SSH_USER:-demo}"
REDFISH_PROFILE="${LCM_DEMO_REDFISH_PROFILE:-openbmc-baseline}"
PROFILE_NAME="${LCM_DEMO_PROFILE_NAME:-demo-${REDFISH_PROFILE}-profile}"
DEVICE_MAC="${LCM_DEMO_DEVICE_MAC:-52:54:00:12:34:56}"
DISCOVERY_METHOD="${LCM_DEMO_DISCOVERY_METHOD:-REDFISH}"
MOCK_SSH_GOMODCACHE="${LCM_DEMO_GO_MOD_CACHE:-/tmp/go-mod-cache-demo}"
MOCK_SSH_GOCACHE="${LCM_DEMO_GO_BUILD_CACHE:-/tmp/go-build-cache-demo}"
SATELLITE_CONTAINER_NAME="${LCM_DEMO_SATELLITE_CONTAINER_NAME:-hyperscale-lcm-demo-satellite}"
HOST_GO_MOD_CACHE="${LCM_DEMO_HOST_GO_MOD_CACHE:-$HOME/go/pkg/mod}"

# Demo credentials — override via environment variables for non-local environments
DEMO_ADMIN_USER="${LCM_DEMO_ADMIN_USER:-admin}"
DEMO_ADMIN_PASSWORD="${LCM_DEMO_ADMIN_PASSWORD:-admin123}"
DEMO_BMC_USER="${LCM_DEMO_BMC_USER:-admin}"
DEMO_BMC_PASSWORD="${LCM_DEMO_BMC_PASSWORD:-admin123}"
DEMO_MANAGED_USER="${LCM_DEMO_MANAGED_USER:-lcm-demo}"
DEMO_MANAGED_PASSWORD="${LCM_DEMO_MANAGED_PASSWORD:-lcm-demo-pass}"

CORE_LOG="$RUNTIME_DIR/core.log"
SATELLITE_LOG="$RUNTIME_DIR/satellite.log"
REDFISH_LOG="$RUNTIME_DIR/mock-redfish.log"
SSH_LOG="$RUNTIME_DIR/mock-ssh.log"
WS_LOG="$RUNTIME_DIR/dashboard-ws.log"

CLIENT_KEY="$RUNTIME_DIR/demo_client_rsa"
HOST_KEY="$RUNTIME_DIR/demo_host_key"

AUTH_TOKEN=""
SATELLITE_ID=""

log() {
  printf '[demo] %s\n' "$*"
}

die() {
  printf '[demo] ERROR: %s\n' "$*" >&2
  exit 1
}

record_pid() {
  local name="$1"
  local pid="$2"
  mkdir -p "$PID_DIR"
  printf '%s\n' "$pid" > "$PID_DIR/$name.pid"
}

kill_recorded_pid() {
  local name="$1"
  local pid_file="$PID_DIR/$name.pid"
  if [[ -f "$pid_file" ]]; then
    local pid
    pid="$(cat "$pid_file")"
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
      wait "$pid" 2>/dev/null || true
    fi
    rm -f "$pid_file"
  fi
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

require_commands() {
  local required=(curl jq grpcurl websocat docker docker-compose ssh ssh-keygen go java python3 nc stdbuf)
  local missing=()
  local cmd
  for cmd in "${required[@]}"; do
    if ! command_exists "$cmd"; then
      missing+=("$cmd")
    fi
  done
  if [[ "${#missing[@]}" -gt 0 ]]; then
    die "missing required commands: ${missing[*]}"
  fi
}

ensure_runtime_dir() {
  mkdir -p "$RUNTIME_DIR" "$PID_DIR"
}

ensure_keys() {
  if [[ ! -f "$ROOT_DIR/certs/client.pem" || ! -f "$ROOT_DIR/certs/server.pem" || ! -f "$ROOT_DIR/core/src/main/resources/META-INF/resources/privateKey.pem" ]]; then
    log "Generating JWT and mTLS keys"
    chmod +x "$ROOT_DIR/scripts/generate_keys.sh"
    (cd "$ROOT_DIR" && ./scripts/generate_keys.sh)
  fi
}

ensure_ssh_material() {
  if [[ ! -f "$CLIENT_KEY" ]]; then
    ssh-keygen -m PEM -t rsa -b 2048 -N '' -f "$CLIENT_KEY" >/dev/null
  fi
  if [[ ! -f "$HOST_KEY" ]]; then
    ssh-keygen -t ed25519 -N '' -f "$HOST_KEY" >/dev/null
  fi
}

start_infra() {
  log "Starting Postgres, Redis, Kafka and Jaeger"
  (cd "$ROOT_DIR" && docker-compose up -d postgres redis kafka jaeger >/dev/null)
}

start_mock_redfish() {
  if curl -sk --max-time 2 "https://${REDFISH_ENDPOINT}/redfish/v1/Systems" >/dev/null 2>&1; then
    log "Mock Redfish server already reachable at https://${REDFISH_ENDPOINT}"
    return
  fi

  log "Starting mock Redfish server on https://${REDFISH_ENDPOINT}"
  python3 "$ROOT_DIR/scripts/demo/mock_redfish_server.py" \
    --bind "$REDISHOST" \
    --port "$REDFISH_PORT" \
    --cert "$ROOT_DIR/certs/server.pem" \
    --key "$ROOT_DIR/certs/server.key" \
    --profile "$REDFISH_PROFILE" \
    >"$REDFISH_LOG" 2>&1 &
  record_pid "mock-redfish" "$!"
}

start_mock_ssh() {
  if nc -z "$SSH_HOST" "$SSH_PORT" >/dev/null 2>&1; then
    log "Mock SSH server already reachable at ${SSH_HOST}:${SSH_PORT}"
    return
  fi

  log "Starting mock SSH server on ${SSH_HOST}:${SSH_PORT}"
  (
    cd "$ROOT_DIR/scripts/demo"
    env GOTOOLCHAIN=local GOMODCACHE="$MOCK_SSH_GOMODCACHE" GOCACHE="$MOCK_SSH_GOCACHE" \
      go run ./mock_ssh_server.go \
      --listen "${SSH_HOST}:${SSH_PORT}" \
      --host-key "$HOST_KEY" \
      --authorized-key "${CLIENT_KEY}.pub" \
      --user "$SSH_USER" \
      --output-prefix "demo-ssh-ok"
  ) >"$SSH_LOG" 2>&1 &
  record_pid "mock-ssh" "$!"
}

start_core() {
  if curl -fsS --max-time 2 "$CORE_URL/q/openapi" >/dev/null 2>&1; then
    log "Core API already reachable at $CORE_URL"
    return
  fi

  log "Starting Quarkus core service"
  (
    cd "$ROOT_DIR/core"
    env \
      DB_REACTIVE_URL=postgresql://localhost:5432/lcm_db \
      DB_URL=jdbc:postgresql://localhost:5432/lcm_db \
      DB_USERNAME=lcm_user \
      DB_PASSWORD=lcm_password \
      LCM_DISCOVERY_REQUIRE_APPROVAL=false \
      REDIS_URL=redis://localhost:6379 \
      KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
      GRPC_CERT_PATH="$ROOT_DIR/certs/server.pem" \
      GRPC_KEY_PATH="$ROOT_DIR/certs/server-pkcs8.key" \
      GRPC_TRUSTSTORE_PATH="$ROOT_DIR/certs/truststore.jks" \
      ./gradlew --no-daemon quarkusDev
  ) >"$CORE_LOG" 2>&1 &
  record_pid "core" "$!"
}

start_satellite() {
  if docker ps --format '{{.Names}}' | grep -Fxq "$SATELLITE_CONTAINER_NAME"; then
    log "Demo satellite container already running as ${SATELLITE_CONTAINER_NAME}"
    return
  fi

  if [[ -n "${AUTH_TOKEN:-}" ]]; then
    local existing_id
    existing_id="$(find_online_satellite_id || true)"
    if [[ -n "$existing_id" ]]; then
      SATELLITE_ID="$existing_id"
      log "Demo satellite already online as ${SATELLITE_ID}"
      return
    fi
  fi

  log "Starting demo satellite container in cluster ${DEMO_CLUSTER}"
  docker rm -f "$SATELLITE_CONTAINER_NAME" >/dev/null 2>&1 || true
  docker run --rm --name "$SATELLITE_CONTAINER_NAME" --network host \
    -v "$ROOT_DIR:/workspace" \
    -v "$RUNTIME_DIR:/runtime" \
    -v "$HOST_GO_MOD_CACHE:/go/pkg/mod" \
    -w /workspace/satellite \
    -e LCM_CORE_ADDR="${GRPC_TARGET}" \
    -e LCM_CERTS_DIR=/workspace/certs \
    -e LCM_GRPC_PLAINTEXT=true \
    -e LCM_PXE_TFTP_ADDR=:1069 \
    -e LCM_PXE_HTTP_ADDR=:18090 \
    -e LCM_PXE_DHCP_PROXY_ADDR=:14011 \
    -e LCM_PXE_TFTP_ROOT=/runtime/tftpboot \
    -e LCM_PXE_IMAGE_DIR=/runtime/images \
    -e LCM_PXE_BOOT_SERVER_HOST=127.0.0.1 \
    -e GOPROXY=off \
    -e GOSUMDB=off \
    -e GOMODCACHE=/go/pkg/mod \
    -e GOCACHE=/runtime/go-build-cache \
    golang:1.24.7 \
    /usr/local/go/bin/go run ./cmd/satellite --cluster "$DEMO_CLUSTER" \
    >"$SATELLITE_LOG" 2>&1 &
  record_pid "satellite" "$!"
}

wait_for_http() {
  local url="$1"
  local timeout="${2:-120}"
  local deadline=$((SECONDS + timeout))
  until curl -fsS --max-time 2 "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for $url"
    fi
    sleep 2
  done
}

wait_for_redfish() {
  local deadline=$((SECONDS + 30))
  until curl -sk --max-time 2 -u "${DEMO_BMC_USER}:${DEMO_BMC_PASSWORD}" "https://${REDFISH_ENDPOINT}/redfish/v1/Systems" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for mock Redfish server"
    fi
    sleep 1
  done
}

wait_for_ssh() {
  local deadline=$((SECONDS + 30))
  until nc -z "$SSH_HOST" "$SSH_PORT" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for mock SSH server"
    fi
    sleep 1
  done
}

authenticate() {
  AUTH_TOKEN="$(
    curl -fsS -H 'Content-Type: application/json' \
      -d "{\"username\":\"${DEMO_ADMIN_USER}\",\"password\":\"${DEMO_ADMIN_PASSWORD}\",\"tenantId\":\"default\"}" \
      "$CORE_URL/api/auth/login" | jq -r '.token'
  )"
  [[ -n "$AUTH_TOKEN" && "$AUTH_TOKEN" != "null" ]] || die "failed to obtain JWT token"
}

auth_header() {
  printf 'Authorization: Bearer %s' "$AUTH_TOKEN"
}

api_get() {
  curl -fsS -H "$(auth_header)" "$1"
}

api_post_json() {
  local url="$1"
  local body="$2"
  curl -fsS -H "$(auth_header)" -H 'Content-Type: application/json' -d "$body" "$url"
}

api_delete() {
  curl -fsS -X DELETE -H "$(auth_header)" "$1" >/dev/null
}

find_online_satellite_id() {
  api_get "$CORE_URL/api/clusters/$DEMO_CLUSTER/nodes?status=online" | jq -r '.[0].id // empty'
}

wait_for_online_satellite() {
  local deadline=$((SECONDS + 120))
  while true; do
    SATELLITE_ID="$(find_online_satellite_id || true)"
    if [[ -n "$SATELLITE_ID" ]]; then
      return
    fi
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for an online satellite in cluster ${DEMO_CLUSTER}"
    fi
    sleep 2
  done
}

cleanup_existing_demo_resources() {
  local profile_ids device_ids
  profile_ids="$(api_get "$CORE_URL/api/credential-profiles?limit=200" | jq -r --arg name "$PROFILE_NAME" '.[] | select(.name == $name) | .id')"
  if [[ -n "$profile_ids" ]]; then
    while IFS= read -r profile_id; do
      [[ -z "$profile_id" ]] && continue
      api_delete "$CORE_URL/api/credential-profiles/$profile_id"
    done <<<"$profile_ids"
  fi

  device_ids="$(api_get "$CORE_URL/api/discovery?limit=200" | jq -r --arg ip "$REDFISH_ENDPOINT" '.[] | select(.ipAddress == $ip) | .id')"
  if [[ -n "$device_ids" ]]; then
    while IFS= read -r device_id; do
      [[ -z "$device_id" ]] && continue
      api_delete "$CORE_URL/api/discovery/$device_id"
    done <<<"$device_ids"
  fi
}

create_demo_profile() {
  local body profile_id
  body="$(jq -cn \
    --arg name "$PROFILE_NAME" \
    --arg deviceType "BMC_ENABLED" \
    --arg template "$REDFISH_PROFILE" \
    --arg bmcUser "literal://$DEMO_BMC_USER" \
    --arg bmcPass "literal://$DEMO_BMC_PASSWORD" \
    --arg mgdUser "literal://$DEMO_MANAGED_USER" \
    --arg mgdPass "literal://$DEMO_MANAGED_PASSWORD" \
    '{
      name: $name,
      deviceType: $deviceType,
      redfishTemplate: $template,
      autoClaim: true,
      managedAccountEnabled: true,
      usernameSecretRef: $bmcUser,
      passwordSecretRef: $bmcPass,
      managedUsernameSecretRef: $mgdUser,
      managedPasswordSecretRef: $mgdPass,
      description: ("Local demo profile for mock Redfish profile " + $template)
    }')"
  profile_id="$(api_post_json "$CORE_URL/api/credential-profiles" "$body" | jq -r '.id')"
  [[ -n "$profile_id" && "$profile_id" != "null" ]] || die "failed to create demo credential profile"

  api_post_json "$CORE_URL/api/credential-profiles/$profile_id/validate" '{}' >/dev/null
}

start_dashboard_ws() {
  kill_recorded_pid "dashboard-ws"
  : >"$WS_LOG"
  (
    tail -f /dev/null | stdbuf -oL -eL websocat -t - "ws://127.0.0.1:8080/ws/dashboard?token=${AUTH_TOKEN}"
  ) >"$WS_LOG" 2>&1 &
  record_pid "dashboard-ws" "$!"
}

wait_for_ws_event() {
  local pattern="$1"
  local timeout="${2:-60}"
  local deadline=$((SECONDS + timeout))
  while true; do
    if grep -Fq "$pattern" "$WS_LOG"; then
      return
    fi
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for websocket event ${pattern}"
    fi
    sleep 1
  done
}

report_redfish_discovery() {
  grpcurl \
    -plaintext \
    -import-path "$ROOT_DIR/core/src/main/proto" \
    -proto lcm.proto \
    -d "$(jq -cn --arg sat "$SATELLITE_ID" --arg ip "$REDFISH_ENDPOINT" --arg mac "$DEVICE_MAC" --arg method "$DISCOVERY_METHOD" '{satellite_id:$sat,discovered_ip:$ip,mac_address:$mac,discovery_method:$method}')" \
    "$GRPC_TARGET" \
    lcm.LcmService/ReportDiscovery >/dev/null
}

wait_for_device_ready_to_claim() {
  local deadline=$((SECONDS + 60))
  while true; do
    local payload status
    payload="$(api_get "$CORE_URL/api/discovery?limit=200")"
    status="$(printf '%s' "$payload" | jq -r --arg ip "$REDFISH_ENDPOINT" '.[] | select(.ipAddress == $ip) | .claimStatus // empty' | head -n 1)"
    if [[ "$status" == "READY_TO_CLAIM" || "$status" == "CLAIMED" ]]; then
      printf '%s' "$payload"
      return
    fi
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for discovered BMC to become READY_TO_CLAIM"
    fi
    sleep 1
  done
}

approve_and_claim_device() {
  local payload device_id
  payload="$(api_get "$CORE_URL/api/discovery?limit=200")"
  device_id="$(printf '%s' "$payload" | jq -r --arg ip "$REDFISH_ENDPOINT" '.[] | select(.ipAddress == $ip) | .id // empty' | head -n 1)"
  [[ -n "$device_id" ]] || die "discovered BMC device not found"

  api_post_json "$CORE_URL/api/discovery/$device_id/approve" '{}' >/dev/null
  api_post_json "$CORE_URL/api/discovery/$device_id/claim" '{}' >/dev/null
}

verify_managed_account() {
  local accounts
  accounts="$(curl -sk -u "${DEMO_BMC_USER}:${DEMO_BMC_PASSWORD}" "https://${REDFISH_ENDPOINT}/redfish/v1/AccountService/Accounts")"
  printf '%s' "$accounts" | jq -e '.Members | length >= 2' >/dev/null || die "managed account was not provisioned"
}

submit_ssh_job() {
  local private_key ssh_payload request job_id
  private_key="$(<"$CLIENT_KEY")"
  ssh_payload="$(jq -cn \
    --arg host "$SSH_HOST" \
    --argjson port "$SSH_PORT" \
    --arg user "$SSH_USER" \
    --arg key "$private_key" \
    --arg command "echo hyperscale-demo" \
    '{host:$host,port:$port,user:$user,privateKey:$key,command:$command,insecureIgnoreHostKey:true}')"
  request="$(jq -cn \
    --arg cluster "$DEMO_CLUSTER" \
    --arg payload "$ssh_payload" \
    '{
      name: "Demo SSH Job",
      description: "Validate scheduling and remote SSH execution",
      cpuCores: 1,
      memoryGb: 1,
      gpuCount: 0,
      requiresNvlink: false,
      minNvlinkBandwidthGbps: 0,
      tenantId: "default",
      clusterId: $cluster,
      executionType: "SSH",
      executionPayload: $payload
    }')"
  job_id="$(api_post_json "$CORE_URL/api/jobs" "$request" | jq -r '.id')"
  [[ -n "$job_id" && "$job_id" != "null" ]] || die "failed to submit SSH job"
  printf '%s' "$job_id"
}

wait_for_job_completion() {
  local job_id="$1"
  local deadline=$((SECONDS + 90))
  while true; do
    local payload status
    payload="$(api_get "$CORE_URL/api/jobs/$job_id/status")"
    status="$(printf '%s' "$payload" | jq -r '.status')"
    if [[ "$status" == "COMPLETED" ]]; then
      printf '%s' "$payload"
      return
    fi
    if [[ "$status" == "FAILED" || "$status" == "CANCELLED" ]]; then
      die "job ${job_id} finished unexpectedly with status ${status}"
    fi
    if (( SECONDS >= deadline )); then
      die "Timed out waiting for job ${job_id} to complete"
    fi
    sleep 2
  done
}

print_summary() {
  local device_payload="$1"
  local job_payload="$2"

  jq -n \
    --arg satelliteId "$SATELLITE_ID" \
    --arg discoveryIp "$REDFISH_ENDPOINT" \
    --arg redfishProfile "$REDFISH_PROFILE" \
    --argjson discovery "$(printf '%s' "$device_payload" | jq --arg ip "$REDFISH_ENDPOINT" '[.[] | select(.ipAddress == $ip)][0]')" \
    --argjson job "$job_payload" \
    '{
      satelliteId: $satelliteId,
      discoveryIp: $discoveryIp,
      redfishProfile: $redfishProfile,
      discovery: $discovery,
      job: $job,
      websocketLog: "'$WS_LOG'"
    }'
}

cleanup() {
  log "Stopping demo helper processes"
  kill_recorded_pid "dashboard-ws"
  kill_recorded_pid "satellite"
  kill_recorded_pid "core"
  kill_recorded_pid "mock-redfish"
  kill_recorded_pid "mock-ssh"
  docker rm -f "$SATELLITE_CONTAINER_NAME" >/dev/null 2>&1 || true
  (cd "$ROOT_DIR" && docker-compose down >/dev/null) || true
}

run_demo() {
  require_commands
  ensure_runtime_dir
  ensure_keys
  ensure_ssh_material
  start_infra
  start_mock_redfish
  start_mock_ssh
  wait_for_redfish
  wait_for_ssh
  start_core
  wait_for_http "$CORE_URL/q/openapi" 180
  authenticate
  start_dashboard_ws
  wait_for_ws_event '"type":"CONNECTED"' 30
  start_satellite
  wait_for_online_satellite
  cleanup_existing_demo_resources
  create_demo_profile
  report_redfish_discovery
  wait_for_ws_event '"type":"DISCOVERY_EVENT"' 30
  wait_for_ws_event '"type":"HEARTBEAT_UPDATE"' 30
  local device_payload
  device_payload="$(wait_for_device_ready_to_claim)"
  approve_and_claim_device
  verify_managed_account
  local job_id job_payload
  job_id="$(submit_ssh_job)"
  job_payload="$(wait_for_job_completion "$job_id")"
  wait_for_ws_event '"type":"JOB_STATUS"' 60
  print_summary "$device_payload" "$job_payload"
}

usage() {
  cat <<EOF
Usage:
  ./scripts/demo.sh run
  ./scripts/demo.sh cleanup

Environment overrides:
  LCM_DEMO_RUNTIME_DIR   Runtime directory for logs and generated SSH keys
  LCM_DEMO_CLUSTER       Cluster ID used by the demo satellite
  LCM_DEMO_CORE_URL      Core REST base URL
  LCM_DEMO_GRPC_TARGET   Core gRPC target used by grpcurl and satellite
  LCM_DEMO_REDFISH_PROFILE  Mock Redfish fixture profile (openbmc-baseline, dell-idrac, hpe-ilo, lenovo-xcc)
EOF
}

main() {
  local command="${1:-run}"
  case "$command" in
    run)
      run_demo
      ;;
    cleanup)
      cleanup
      ;;
    -h|--help|help)
      usage
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
