#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SERVICE="$REPO_ROOT/module/template/service.sh"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

CONFIG_DIR="$TEST_ROOT/config"
MODDIR="$TEST_ROOT/module"
mkdir -p "$CONFIG_DIR" "$MODDIR/keyboxes"
sed -n '/^mirror_root_keyboxes() {$/,/^}$/p' "$SERVICE" > "$TEST_ROOT/mirror.sh"
# shellcheck source=/dev/null
source "$TEST_ROOT/mirror.sh"
chcon() { :; }
export CONFIG_DIR MODDIR

printf '<valid/>\n' > "$MODDIR/valid.xml"
truncate -s 10485813 "$MODDIR/oversized.cbox"
truncate -s 10485761 "$MODDIR/oversized.xml"
printf 'outside\n' > "$TEST_ROOT/outside"
mkdir -p "$CONFIG_DIR/keyboxes"
ln -s "$TEST_ROOT/outside" "$CONFIG_DIR/keyboxes/.valid.xml.tmp.$$"

for index in $(seq 1 257); do
  printf '<k/>\n' > "$MODDIR/keyboxes/keybox_$index.xml"
done

mirror_root_keyboxes

[[ $(< "$TEST_ROOT/outside") == outside ]]
[[ -f "$CONFIG_DIR/keyboxes/valid.xml" ]]
[[ ! -e "$CONFIG_DIR/keyboxes/oversized.cbox" ]]
[[ ! -e "$CONFIG_DIR/keyboxes/oversized.xml" ]]
mirrored_count=$(find "$CONFIG_DIR/keyboxes" -maxdepth 1 -type f ! -name '.*.tmp.*' | wc -l)
[[ "$mirrored_count" -eq 256 ]]
[[ -z $(find "$CONFIG_DIR/keyboxes" -maxdepth 1 -name '.*.tmp.*' -print -quit) ]]

printf '%s\n' 'service keybox mirror resource-bound tests passed'
