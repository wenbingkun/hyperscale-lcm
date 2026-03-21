#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C
export LANG=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
failures=0

fail() {
  printf 'CI contract violation: %s\n' "$1" >&2
  failures=1
}

require_fixed() {
  local needle="$1"
  local message="$2"
  if ! grep -nF "$needle" "$WORKFLOW" >/dev/null; then
    fail "$message"
  fi
}

require_regex() {
  local pattern="$1"
  local message="$2"
  if ! grep -nE "$pattern" "$WORKFLOW" >/dev/null; then
    fail "$message"
  fi
}

printf 'Checking load-test CI contract...\n'

require_fixed "name: Backend Tests" "backend-test job is missing"
require_fixed "name: Core Load Test" "load-test job is missing"
require_fixed "image: postgres:15" "PostgreSQL service declaration is missing"
require_fixed "image: redis:7-alpine" "Redis service declaration is missing"
require_fixed "image: confluentinc/cp-kafka:7.4.0" "Kafka service declaration is missing"
require_regex 'needs:\s*\[[^]]*backend-test[^]]*satellite-build[^]]*\]' "load-test must depend on backend-test and satellite-build"
require_fixed "./gradlew build -Dquarkus.package.type=fast-jar -x test" "load-test must build core as fast-jar"
require_fixed "QUARKUS_HTTP_INSECURE_REQUESTS: enabled" "load-test must explicitly enable insecure HTTP requests"
require_fixed "LCM_DISCOVERY_REQUIRE_APPROVAL: false" "load-test must disable discovery approval"
require_fixed "curl -sf http://localhost:8080/health/ready" "load-test readiness probe must target /health/ready"
require_fixed "curl -sf http://localhost:8080/health/live" "post-load health probe must target /health/live"
require_regex '\./loadgen .* -plaintext' "loadgen must run in plaintext mode in CI"

loadgen_line=$(grep -nF "./loadgen " "$WORKFLOW" | head -n 1 || true)
if [[ -z "$loadgen_line" ]]; then
  fail "loadgen command is missing"
else
  sat_count=$(sed -n 's/.*-sats \([0-9][0-9]*\).*/\1/p' <<<"$loadgen_line")
  duration_value=$(sed -n 's/.*-duration \([^[:space:]]\+\).*/\1/p' <<<"$loadgen_line")
  step_name=$(grep -n "name: Execute Load Profile" "$WORKFLOW" | head -n 1 || true)

  if [[ -z "$sat_count" ]]; then
    fail "loadgen command must declare -sats"
  elif (( sat_count <= 0 )); then
    fail "loadgen satellite count must be positive"
  elif (( sat_count > 200 )); then
    fail "loadgen satellite count must stay at or below 200 for GitHub runner stability (got $sat_count)"
  fi

  if [[ -z "$duration_value" ]]; then
    fail "loadgen command must declare -duration"
  fi

  if [[ -n "$step_name" ]]; then
    step_sat_count=$(sed -n 's/.*(\([0-9][0-9]*\) satellites.*/\1/p' <<<"$step_name")
    step_duration=$(sed -n 's/.*for \([^)]\+\)).*/\1/p' <<<"$step_name")

    if [[ -n "$step_sat_count" && -n "$sat_count" && "$step_sat_count" != "$sat_count" ]]; then
      fail "load profile step name satellite count ($step_sat_count) does not match loadgen -sats $sat_count"
    fi

    if [[ -n "$step_duration" && -n "$duration_value" && "$step_duration" != "$duration_value" ]]; then
      fail "load profile step name duration ($step_duration) does not match loadgen -duration $duration_value"
    fi
  fi
fi

if [[ "$failures" -ne 0 ]]; then
  exit 1
fi

printf 'Load-test CI contract checks passed.\n'
