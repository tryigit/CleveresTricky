#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
VERIFY_SH="$REPO_ROOT/module/template/verify.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

make_fixture() {
  fixture=$1
  mkdir -p "$fixture/src" "$fixture/dest" "$fixture/tmp"
  printf 'verified payload\n' > "$fixture/src/payload.bin"
  (
    cd "$fixture/src"
    sha256sum payload.bin | awk '{print $1}' > payload.bin.sha256
    zip -q "$fixture/payload.zip" payload.bin payload.bin.sha256
  )
}

run_extract() {
  fixture=$1
  (
    export TMPDIR="$fixture/tmp"
    ui_print() { :; }
    abort() {
      echo "$*" >&2
      exit 97
    }
    # shellcheck source=../template/verify.sh
    . "$VERIFY_SH"
    extract "$fixture/payload.zip" payload.bin "$fixture/dest" true
  )
}

assert_rejects_symlink() {
  target_kind=$1
  fixture=$(mktemp -d)
  trap 'rm -rf "$fixture"' RETURN
  make_fixture "$fixture"

  printf 'outside sentinel\n' > "$fixture/outside"
  case "$target_kind" in
    payload)
      ln -s "$fixture/outside" "$fixture/dest/payload.bin"
      ;;
    checksum)
      ln -s "$fixture/outside" "$fixture/dest/payload.bin.sha256"
      ;;
    *) fail "unknown target kind: $target_kind" ;;
  esac

  if run_extract "$fixture" >"$fixture/stdout" 2>"$fixture/stderr"; then
    fail "extract accepted pre-existing $target_kind symlink"
  fi
  [[ $(cat "$fixture/outside") == 'outside sentinel' ]] \
    || fail "extract modified data through $target_kind symlink"

  rm -rf "$fixture"
  trap - RETURN
}

assert_rejects_symlink_parent() {
  fixture=$(mktemp -d)
  trap 'rm -rf "$fixture"' RETURN
  make_fixture "$fixture"

  rm -rf "$fixture/dest"
  mkdir "$fixture/outside-dir"
  ln -s "$fixture/outside-dir" "$fixture/dest"

  if run_extract "$fixture" >"$fixture/stdout" 2>"$fixture/stderr"; then
    fail "extract accepted a symlinked destination root"
  fi
  [[ ! -e "$fixture/outside-dir/payload.bin" ]] \
    || fail "extract wrote payload through a symlinked destination root"
  [[ ! -e "$fixture/outside-dir/payload.bin.sha256" ]] \
    || fail "extract wrote checksum through a symlinked destination root"

  rm -rf "$fixture"
  trap - RETURN
}

assert_rejects_non_regular_target() {
  fixture=$(mktemp -d)
  trap 'rm -rf "$fixture"' RETURN
  make_fixture "$fixture"
  mkdir "$fixture/dest/payload.bin"

  if run_extract "$fixture" >"$fixture/stdout" 2>"$fixture/stderr"; then
    fail "extract accepted pre-existing non-regular payload target"
  fi

  rm -rf "$fixture"
  trap - RETURN
}

assert_normal_extract_succeeds() {
  fixture=$(mktemp -d)
  trap 'rm -rf "$fixture"' RETURN
  make_fixture "$fixture"
  run_extract "$fixture" >/dev/null
  cmp "$fixture/src/payload.bin" "$fixture/dest/payload.bin" \
    || fail "verified payload changed during normal extraction"
  rm -rf "$fixture"
  trap - RETURN
}

assert_rejects_symlink payload
assert_rejects_symlink checksum
assert_rejects_symlink_parent
assert_rejects_non_regular_target
assert_normal_extract_succeeds
bash "$REPO_ROOT/module/install-tests/customize_bootstrap_security.test.sh"
bash "$REPO_ROOT/module/install-tests/action_bugreport_security.test.sh"
bash "$REPO_ROOT/module/install-tests/service_pid_security.test.sh"
bash "$REPO_ROOT/module/install-tests/service_keybox_mirror_security.test.sh"

echo "installer extraction security tests passed"
