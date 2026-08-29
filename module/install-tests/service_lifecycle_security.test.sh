#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
SERVICE_SH="$REPO_ROOT/module/template/service.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  needle=$1
  grep -Fq -- "$needle" "$SERVICE_SH" || fail "service.sh is missing lifecycle contract: $needle"
}

assert_absent() {
  needle=$1
  if grep -Fq -- "$needle" "$SERVICE_SH"; then
    fail "service.sh contains unsafe pattern: $needle"
  fi
}

# Contract checks: No blind pkill hacks, proper PID tracking and singleton enforcement
assert_absent 'pkill'
assert_contains 'SUPERVISOR_PID_FILE='
assert_contains 'DAEMON_PID_FILE='
assert_contains 'terminate_previous_instances'

echo "PASS: service lifecycle security contract satisfied"
