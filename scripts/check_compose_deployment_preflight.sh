#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-}"
DOCKER_TAG="${DOCKER_TAG:-v0.1.0}"
DB_PASSWORD="${DB_PASSWORD:-}"
GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-}"
GRPC_TRUSTSTORE_PASSWORD="${GRPC_TRUSTSTORE_PASSWORD:-}"
REQUIRE_IMAGES=true

usage() {
  cat <<'USAGE'
Usage:
  scripts/check_compose_deployment_preflight.sh --namespace <dockerhub-user> [options]

Options:
  --namespace <name>    Docker namespace that owns lcm-core/lcm-satellite/lcm-frontend.
  --tag <tag>           Release image tag to validate. Defaults to DOCKER_TAG or v0.1.0.
  --skip-image-check    Do not require docker images to exist locally.
  -h, --help            Show this help.

Required environment:
  DB_PASSWORD
  GRAFANA_PASSWORD
  GRPC_TRUSTSTORE_PASSWORD

This script is non-destructive. It validates compose configuration, mTLS files,
fail-fast secret handling, fixed image tags, and optionally local image presence.
It does not start, stop, pull, or restart containers.
USAGE
}

fail() {
  echo "[compose-preflight] ERROR: $*" >&2
  exit 1
}

ok() {
  echo "[compose-preflight] OK: $*"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --namespace)
      DOCKER_NAMESPACE="${2:-}"
      shift 2
      ;;
    --tag)
      DOCKER_TAG="${2:-}"
      shift 2
      ;;
    --skip-image-check)
      REQUIRE_IMAGES=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$DOCKER_NAMESPACE" ]] || fail "DOCKER namespace is required. Pass --namespace or set DOCKER_NAMESPACE."
[[ -n "$DOCKER_TAG" ]] || fail "DOCKER tag is required. Pass --tag or set DOCKER_TAG."
[[ "$DOCKER_TAG" != "latest" ]] || fail "DOCKER_TAG must be a fixed release tag, not latest."
[[ -n "$DB_PASSWORD" ]] || fail "DB_PASSWORD is required."
[[ -n "$GRAFANA_PASSWORD" ]] || fail "GRAFANA_PASSWORD is required."
[[ -n "$GRPC_TRUSTSTORE_PASSWORD" ]] || fail "GRPC_TRUSTSTORE_PASSWORD is required."

for required in docker-compose awk grep mktemp sort; do
  command -v "$required" >/dev/null 2>&1 || fail "Required command not found: $required"
done

if [[ "$REQUIRE_IMAGES" == true ]]; then
  command -v docker >/dev/null 2>&1 || fail "Required command not found: docker"
  if ! docker info >/dev/null 2>&1; then
    fail "Docker daemon is not accessible. Fix Docker permissions/service first, or rerun with --skip-image-check for config-only validation."
  fi
fi

cert_files=(
  "certs/ca.pem"
  "certs/server.pem"
  "certs/server-pkcs8.key"
  "certs/client.pem"
  "certs/client.key"
  "certs/truststore.jks"
)

missing_certs=()
for cert_file in "${cert_files[@]}"; do
  if [[ ! -f "$ROOT_DIR/$cert_file" ]]; then
    missing_certs+=("$cert_file")
  fi
done

if [[ "${#missing_certs[@]}" -gt 0 ]]; then
  printf '[compose-preflight] Missing mTLS files:\n' >&2
  printf '  - %s\n' "${missing_certs[@]}" >&2
  fail "Prepare certs first. For test-only environments run scripts/generate_keys.sh."
fi
ok "mTLS certificate files are present"

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

empty_env="$tmp_dir/empty.env"
negative_log="$tmp_dir/negative.log"
config_file="$tmp_dir/docker-compose.prod.rendered.yml"
: >"$empty_env"

set +e
env -u DOCKER_NAMESPACE -u DOCKER_TAG -u DB_PASSWORD -u GRAFANA_PASSWORD -u GRPC_TRUSTSTORE_PASSWORD \
  docker-compose --env-file "$empty_env" -f "$ROOT_DIR/docker-compose.prod.yml" config \
  >"$negative_log" 2>&1
negative_status=$?
set -e

if [[ "$negative_status" -eq 0 ]]; then
  fail "docker-compose config succeeded without required secrets; fail-fast contract is broken."
fi

if ! grep -Eq 'DB_PASSWORD|GRAFANA_PASSWORD|GRPC_TRUSTSTORE_PASSWORD|DOCKER_NAMESPACE' "$negative_log"; then
  fail "fail-fast negative check did not mention a required variable."
fi
ok "missing required env vars fail during compose config"

env \
  DOCKER_NAMESPACE="$DOCKER_NAMESPACE" \
  DOCKER_TAG="$DOCKER_TAG" \
  DB_PASSWORD="$DB_PASSWORD" \
  GRAFANA_PASSWORD="$GRAFANA_PASSWORD" \
  GRPC_TRUSTSTORE_PASSWORD="$GRPC_TRUSTSTORE_PASSWORD" \
  docker-compose -f "$ROOT_DIR/docker-compose.prod.yml" config >"$config_file"
ok "docker-compose.prod.yml renders with provided env"

if grep -Eq 'image: .*:latest($|[[:space:]])' "$config_file"; then
  grep -En 'image: .*:latest($|[[:space:]])' "$config_file" >&2 || true
  fail "rendered compose config still contains latest image tags."
fi
ok "rendered compose config uses fixed image tags"

grep -Fq "prom/prometheus:v2.53.5" "$config_file" || fail "prometheus image is not pinned to prom/prometheus:v2.53.5."
grep -Eq 'LCM_CORE_ADDR(:[[:space:]]+|=)lcm-core:8080' "$config_file" || fail "satellite LCM_CORE_ADDR does not target lcm-core:8080."
ok "deployment-specific compose invariants are present"

if [[ "$REQUIRE_IMAGES" == true ]]; then
  mapfile -t images < <(awk '/^[[:space:]]+image:/ { print $2 }' "$config_file" | sort -u)
  missing_images=()
  for image in "${images[@]}"; do
    if ! docker image inspect "$image" >/dev/null 2>&1; then
      missing_images+=("$image")
    fi
  done

  if [[ "${#missing_images[@]}" -gt 0 ]]; then
    printf '[compose-preflight] Missing local images:\n' >&2
    printf '  - %s\n' "${missing_images[@]}" >&2
    fail "Load the offline bundle, pull images on a connected host, or rerun with --skip-image-check for config-only validation."
  fi
  ok "all compose images exist locally"
else
  ok "local image presence check skipped"
fi

ok "compose deployment preflight completed"
