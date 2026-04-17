#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${LCM_DEMO_RUNTIME_DIR:-/tmp/hyperscale-lcm-demo}"
CORE_URL="${LCM_DEMO_CORE_URL:-http://127.0.0.1:8080}"
GRPC_TARGET="${LCM_DEMO_GRPC_TARGET:-127.0.0.1:8080}"
DEMO_CLUSTER="${LCM_DEMO_CLUSTER:-demo-lab-$(date +%s)}"

CORE_JAR="$ROOT_DIR/core/build/quarkus-app/quarkus-run.jar"
SATELLITE_BIN="$ROOT_DIR/satellite/satellite"
CORE_LOG="$RUNTIME_DIR/core.log"
SATELLITE_LOG="$RUNTIME_DIR/satellite.log"
DEMO_LOG="$RUNTIME_DIR/demo-smoke.log"
REDFISH_LOG="$RUNTIME_DIR/mock-redfish.log"
SSH_LOG="$RUNTIME_DIR/mock-ssh.log"
WS_LOG="$RUNTIME_DIR/dashboard-ws.log"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

CORE_PID=""
SATELLITE_PID=""

info() { printf "${CYAN}[demo-smoke]${NC} %s\n" "$*"; }
ok() { printf "${GREEN}[demo-smoke] ✓${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}[demo-smoke] ⚠${NC} %s\n" "$*"; }
fail() { printf "${RED}[demo-smoke] ✗${NC} %s\n" "$*" >&2; }
die() { fail "$*"; exit 1; }

require_command() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || die "missing required command: $cmd"
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || die "required file not found: $path"
}

ensure_prerequisites() {
  local required=(
    curl
    docker
    docker-compose
    go
    java
    jq
    nc
    python3
    ssh
    ssh-keygen
    stdbuf
    websocat
    grpcurl
  )
  local cmd
  for cmd in "${required[@]}"; do
    require_command "$cmd"
  done

  require_file "$CORE_JAR"
  require_file "$SATELLITE_BIN"
  require_file "$ROOT_DIR/certs/server.pem"
  require_file "$ROOT_DIR/certs/server-pkcs8.key"
  require_file "$ROOT_DIR/certs/truststore.jks"
  require_file "$ROOT_DIR/core/src/main/resources/META-INF/resources/privateKey.pem"

  mkdir -p "$RUNTIME_DIR"
  : >"$CORE_LOG"
  : >"$SATELLITE_LOG"
  : >"$DEMO_LOG"
}

wait_for_http() {
  local url="$1"
  local timeout="${2:-120}"
  local deadline=$((SECONDS + timeout))

  until curl -fsS --max-time 2 "$url" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      die "timed out waiting for $url"
    fi
    sleep 2
  done
}

wait_for_port() {
  local host="$1"
  local port="$2"
  local timeout="${3:-120}"
  local deadline=$((SECONDS + timeout))

  until nc -z "$host" "$port" >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      die "timed out waiting for ${host}:${port}"
    fi
    sleep 2
  done
}

start_infra() {
  info "Starting Postgres, Redis, Kafka and Jaeger"
  (
    cd "$ROOT_DIR"
    docker-compose up -d postgres redis kafka jaeger >/dev/null
  )

  wait_for_port 127.0.0.1 5432 180
  wait_for_port 127.0.0.1 6379 120
  wait_for_port 127.0.0.1 9092 180
  ok "Demo infrastructure is ready"
}

print_log_excerpt() {
  local label="$1"
  local path="$2"

  if [[ -f "$path" ]]; then
    printf '\n===== %s (%s) =====\n' "$label" "$path"
    tail -n 80 "$path" || true
  fi
}

cleanup() {
  local status="$1"
  set +e

  if (( status != 0 )); then
    warn "demo smoke failed; printing recent log excerpts"
    print_log_excerpt "demo smoke" "$DEMO_LOG"
    print_log_excerpt "core" "$CORE_LOG"
    print_log_excerpt "satellite" "$SATELLITE_LOG"
    print_log_excerpt "mock redfish" "$REDFISH_LOG"
    print_log_excerpt "mock ssh" "$SSH_LOG"
    print_log_excerpt "dashboard websocket" "$WS_LOG"
  fi

  if [[ -n "$SATELLITE_PID" ]] && kill -0 "$SATELLITE_PID" >/dev/null 2>&1; then
    kill "$SATELLITE_PID" >/dev/null 2>&1 || true
    wait "$SATELLITE_PID" 2>/dev/null || true
  fi

  if [[ -n "$CORE_PID" ]] && kill -0 "$CORE_PID" >/dev/null 2>&1; then
    kill "$CORE_PID" >/dev/null 2>&1 || true
    wait "$CORE_PID" 2>/dev/null || true
  fi

  LCM_DEMO_SKIP_SATELLITE=1 \
    LCM_DEMO_RUNTIME_DIR="$RUNTIME_DIR" \
    "$ROOT_DIR/scripts/demo.sh" cleanup >/dev/null 2>&1 || true

  trap - EXIT
  exit "$status"
}

start_core() {
  info "Starting Core fast-jar"
  (
    cd "$ROOT_DIR/core"
    env \
      QUARKUS_DATASOURCE_REACTIVE_URL=postgresql://localhost:5432/lcm_db \
      QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/lcm_db \
      QUARKUS_DATASOURCE_USERNAME=lcm_user \
      QUARKUS_DATASOURCE_PASSWORD=lcm_password \
      QUARKUS_HTTP_INSECURE_REQUESTS=enabled \
      QUARKUS_HTTP_AUTH_PERMISSION_PUBLIC_PATHS=/api/auth/* \
      QUARKUS_HTTP_AUTH_PERMISSION_PUBLIC_POLICY=permit \
      QUARKUS_REDIS_HOSTS=redis://localhost:6379 \
      KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
      GRPC_CERT_PATH="$ROOT_DIR/certs/server.pem" \
      GRPC_KEY_PATH="$ROOT_DIR/certs/server-pkcs8.key" \
      GRPC_TRUSTSTORE_PATH="$ROOT_DIR/certs/truststore.jks" \
      LCM_DISCOVERY_REQUIRE_APPROVAL=false \
      QUARKUS_OTEL_SDK_DISABLED="${QUARKUS_OTEL_SDK_DISABLED:-true}" \
      java \
        -Dlcm.auth.dev-users.enabled=true \
        -Dlcm.claim.secret-resolver.allow-literal=true \
        -Dlcm.claim.redfish.insecure=true \
        -jar build/quarkus-app/quarkus-run.jar
  ) >"$CORE_LOG" 2>&1 &
  CORE_PID="$!"
  wait_for_http "$CORE_URL/health/ready" 180
  ok "Core is ready at $CORE_URL"
}

start_satellite() {
  info "Starting Satellite binary for cluster ${DEMO_CLUSTER}"
  (
    cd "$ROOT_DIR/satellite"
    env \
      LCM_CORE_ADDR="$GRPC_TARGET" \
      LCM_CERTS_DIR="$ROOT_DIR/certs" \
      LCM_GRPC_PLAINTEXT=true \
      LCM_PXE_TFTP_ADDR=:1069 \
      LCM_PXE_HTTP_ADDR=:18090 \
      LCM_PXE_DHCP_PROXY_ADDR=:14011 \
      LCM_PXE_TFTP_ROOT="$RUNTIME_DIR/tftpboot" \
      LCM_PXE_IMAGE_DIR="$RUNTIME_DIR/images" \
      LCM_PXE_BOOT_SERVER_HOST=127.0.0.1 \
      ./satellite --cluster "$DEMO_CLUSTER"
  ) >"$SATELLITE_LOG" 2>&1 &
  SATELLITE_PID="$!"
  ok "Satellite started with PID ${SATELLITE_PID}"
}

run_demo() {
  info "Resetting previous demo state"
  LCM_DEMO_SKIP_SATELLITE=1 \
    LCM_DEMO_RUNTIME_DIR="$RUNTIME_DIR" \
    "$ROOT_DIR/scripts/demo.sh" cleanup >/dev/null 2>&1 || true

  start_infra
  start_core
  start_satellite

  info "Running end-to-end demo smoke"
  LCM_DEMO_SKIP_SATELLITE=1 \
    LCM_DEMO_RUNTIME_DIR="$RUNTIME_DIR" \
    LCM_DEMO_CLUSTER="$DEMO_CLUSTER" \
    LCM_DEMO_CORE_URL="$CORE_URL" \
    LCM_DEMO_GRPC_TARGET="$GRPC_TARGET" \
    "$ROOT_DIR/scripts/demo.sh" run | tee "$DEMO_LOG"

  ok "Demo smoke completed successfully"
}

main() {
  trap 'cleanup "$?"' EXIT
  ensure_prerequisites
  run_demo
}

main "$@"
