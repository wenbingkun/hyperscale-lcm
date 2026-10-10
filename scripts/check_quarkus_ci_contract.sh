#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C
export LANG=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APP_PROPS="$ROOT_DIR/core/src/main/resources/application.properties"
failures=0

fail() {
  printf 'CI contract violation: %s\n' "$1" >&2
  failures=1
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

extract_cron_value() {
  local raw="$1"

  if [[ "$raw" =~ ^\$\{[^:}]+:(.*)\}$ ]]; then
    printf '%s' "${BASH_REMATCH[1]}"
    return
  fi

  printf '%s' "$raw"
}

validate_cron_value() {
  local key="$1"
  local line="$2"
  local raw_value="$3"
  local cron_value field_count

  cron_value=$(trim "$(extract_cron_value "$raw_value")")
  if [[ -z "$cron_value" ]]; then
    fail "$APP_PROPS:$line -> $key has an empty cron value"
    return
  fi

  field_count=$(awk '{ print NF }' <<<"$cron_value")
  if [[ "$field_count" != "6" && "$field_count" != "7" ]]; then
    fail "$APP_PROPS:$line -> $key must use a 6 or 7 field Quarkus cron, got '$cron_value'"
  fi
}

printf 'Checking Quarkus CI contract...\n'

empty_default_matches=$(rg -n 'defaultValue\s*=\s*""' "$ROOT_DIR/core/src/main/java" --glob '*.java' || true)
if [[ -n "$empty_default_matches" ]]; then
  printf '%s\n' "$empty_default_matches" >&2
  fail "empty-string @ConfigProperty defaults are forbidden in core/src/main/java"
fi

mapfile -t cron_keys < <(
  find "$ROOT_DIR/core/src/main/java" -name '*.java' -print0 \
    | xargs -0 perl -ne 'while (/@Scheduled\(cron = "\{([^"]+)\}"\)/g) { print "$1\n" }' \
    | sort -u
)

for key in "${cron_keys[@]}"; do
  [[ -n "$key" ]] || continue

  escaped_key=$(sed 's/[][(){}.^$+*?|\\/]/\\&/g' <<<"$key")
  matches=$(grep -nE "^(${escaped_key}|%[[:alnum:]_.-]+\.${escaped_key})=" "$APP_PROPS" || true)

  if [[ -z "$matches" ]]; then
    fail "$APP_PROPS -> missing cron property for scheduled key '$key'"
    continue
  fi

  while IFS= read -r match; do
    [[ -n "$match" ]] || continue
    line_number=${match%%:*}
    property_line=${match#*:}
    property_value=${property_line#*=}
    validate_cron_value "$key" "$line_number" "$property_value"
  done <<<"$matches"
done

PROD_PROPS="$ROOT_DIR/core/src/main/resources/application-prod.properties"
# gRPC shares the HTTP server, so prod mTLS must be configured on quarkus.http.ssl.* (build-time client-auth).
for expected in \
  '^quarkus\.http\.insecure-requests=enabled$' \
  '^quarkus\.http\.ssl-port=8443$' \
  '^quarkus\.http\.ssl\.client-auth=required$' \
  '^lcm\.grpc\.require-tls=true$'; do
  grep -qE "$expected" "$PROD_PROPS" || fail "$PROD_PROPS -> missing prod mTLS setting matching '$expected'"
done
if grep -qE '^quarkus\.http\.insecure-requests=redirect' "$PROD_PROPS"; then
  fail "$PROD_PROPS -> insecure-requests=redirect breaks prod startup without HTTP SSL certs"
fi

if [[ "$failures" -ne 0 ]]; then
  exit 1
fi

printf 'Quarkus CI contract checks passed.\n'
