#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
UPDATE_BINARY="$REPO_ROOT/module/template/META-INF/com/google/android/update-binary"
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT

data_root="$fixture/data"
mkdir -p "$data_root/adb"
sed "s#/data#$data_root#g" "$UPDATE_BINARY" > "$fixture/update-binary"

run_installer() {
  set +e
  sh "$fixture/update-binary" unused unused "$fixture/module.zip" >/dev/null 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status"
}

malicious="$fixture/malicious-util-functions.sh"
cat > "$malicious" <<EOF
touch "$fixture/sourced-symlink"
install_module() { return 0; }
EOF

for manager in magisk ksu ap; do
  rm -rf "$data_root/adb/magisk" "$data_root/adb/ksu" "$data_root/adb/ap"
  mkdir -p "$data_root/adb/$manager"
  ln -s "$malicious" "$data_root/adb/$manager/util_functions.sh"
  [[ $(run_installer) -eq 1 ]] || {
    echo "FAIL: update-binary accepted a symlinked $manager helper" >&2
    exit 1
  }
  [[ ! -e "$fixture/sourced-symlink" ]] || {
    echo "FAIL: update-binary sourced a symlinked $manager helper" >&2
    exit 1
  }
done

rm -rf "$data_root/adb/magisk" "$data_root/adb/ksu" "$data_root/adb/ap"
mkdir -p "$data_root/adb/magisk"
cat > "$data_root/adb/magisk/util_functions.sh" <<'EOF'
MAGISK_VER_CODE=20400
install_module() { return "${INSTALL_MODULE_STATUS:-0}"; }
EOF

[[ $(INSTALL_MODULE_STATUS=0 run_installer) -eq 0 ]] || {
  echo 'FAIL: update-binary rejected a successful install_module helper' >&2
  exit 1
}
[[ $(INSTALL_MODULE_STATUS=23 run_installer) -eq 1 ]] || {
  echo 'FAIL: update-binary did not propagate install_module failure' >&2
  exit 1
}

echo 'update-binary helper security checks passed'
