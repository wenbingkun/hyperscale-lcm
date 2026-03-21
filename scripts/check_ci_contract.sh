#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C
export LANG=C

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

"$ROOT_DIR/scripts/check_quarkus_ci_contract.sh"
"$ROOT_DIR/scripts/check_load_test_contract.sh"
