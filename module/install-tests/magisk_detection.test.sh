#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CUSTOMIZE_TEMPLATE="$REPO_ROOT/module/template/customize.sh"
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

mkdir -p "$fixture/tmp" "$fixture/mod"
printf 'version=Vtest\n' > "$fixture/tmp/module.prop"

sed \
  -e 's/@DEBUG@/false/g' \
  -e 's/@SONAME@/cleverestricky/g' \
  -e 's/@SUPPORTED_ABIS@/arm64 x64/g' \
  -e 's/@MIN_SDK@/31/g' \
  -e 's/@MAX_SDK@/37/g' \
  "$CUSTOMIZE_TEMPLATE" > "$fixture/customize.sh"

ui_print() { echo "UI: $1"; }
grep_prop() { printf 'Vtest\n'; }
abort() { echo "ABORT: $1"; exit 97; }
export -f ui_print grep_prop abort

# Test 1: Magisk environment is accepted and not blocked
set +e
output=$(
  BOOTMODE=1 \
  KSU= \
  APATCH= \
  MAGISK_VER_CODE=27000 \
  MAGISK_VER="27.0" \
  ARCH=arm64 \
  API=31 \
  TMPDIR="$fixture/tmp" \
  MODPATH="$fixture/mod" \
  ZIPFILE="$fixture/dummy.zip" \
  bash -c '
    unzip() {
      echo "UNZIP_EXTRACT_REACHED: $*"
      touch "$TMPDIR/verify.sh" "$TMPDIR/verify.sh.sha256"
      exit 0
    }
    export -f unzip
    source "'"$fixture/customize.sh"'"
  ' 2>&1
)
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "FAIL: customize.sh aborted unexpectedly in Magisk environment with code $status" >&2
  echo "$output" >&2
  exit 1
fi

if ! echo "$output" | grep -q "UNZIP_EXTRACT_REACHED"; then
  echo "FAIL: customize.sh did not reach post-detection extraction" >&2
  echo "$output" >&2
  exit 1
fi

if echo "$output" | grep -q "! Magisk is NOT supported!"; then
  echo "FAIL: customize.sh still rejected Magisk environment" >&2
  exit 1
fi

if ! echo "$output" | grep -q "Installing from Magisk app"; then
  echo "FAIL: customize.sh did not recognize Magisk environment" >&2
  echo "$output" >&2
  exit 1
fi

if ! echo "$output" | grep -q "Magisk is NOT recommended"; then
  echo "FAIL: customize.sh missing recommendation warning for Magisk" >&2
  echo "$output" >&2
  exit 1
fi

# Test 2: Unsupported root / recovery environment is rejected with abort exit code
set +e
output_unsupported=$(
  BOOTMODE= \
  KSU= \
  APATCH= \
  MAGISK_VER_CODE= \
  ARCH=arm64 \
  API=31 \
  TMPDIR="$fixture/tmp" \
  MODPATH="$fixture/mod" \
  ZIPFILE="$fixture/dummy.zip" \
  bash -c '
    source "'"$fixture/customize.sh"'"
  ' 2>&1
)
status_unsupported=$?
set -e

if [[ $status_unsupported -ne 97 ]]; then
  echo "FAIL: customize.sh did not exit with abort code 97 (got $status_unsupported)" >&2
  echo "$output_unsupported" >&2
  exit 1
fi

if ! echo "$output_unsupported" | grep -q "Install from recovery or unsupported root is not supported"; then
  echo "FAIL: customize.sh failed to reject unsupported environment" >&2
  echo "$output_unsupported" >&2
  exit 1
fi

echo "Magisk installation detection test passed"
