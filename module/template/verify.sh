# shellcheck shell=sh
# shellcheck disable=SC2154
TMPDIR_FOR_VERIFY="$TMPDIR/.vunzip"
mkdir -p "$TMPDIR_FOR_VERIFY" || abort "! Could not create verification directory"

abort_verify() {
  ui_print "*********************************************************"
  ui_print "! $1"
  ui_print "! This zip may be corrupted; download it again"
  abort "*********************************************************"
}

verify_hash() {
  target=$1
  hash_file=$2
  expected=$(tr -d '[:space:]' < "$hash_file")
  case "$expected" in
    *[!0-9A-Fa-f]*|'') abort_verify "Invalid checksum for $(basename "$target")" ;;
  esac
  [ "${#expected}" -eq 64 ] || abort_verify "Invalid checksum length for $(basename "$target")"
  printf '%s  %s\n' "$expected" "$target" | sha256sum -c -s - \
    || abort_verify "Failed to verify $(basename "$target")"
}

# extract <zip> <file> <target dir> [junk paths]
extract() {
  zip=$1
  file=$2
  dir=$3
  junk_paths=${4:-false}

  if [ "$junk_paths" = true ]; then
    file_path="$dir/$(basename "$file")"
    hash_path="$dir/$(basename "$file").sha256"
    unzip -oj "$zip" "$file" -d "$dir" >&2 || abort_verify "Could not extract $file"
    unzip -oj "$zip" "$file.sha256" -d "$dir" >&2 || abort_verify "Checksum missing for $file"
  else
    file_path="$dir/$file"
    hash_path="$dir/$file.sha256"
    unzip -o "$zip" "$file" -d "$dir" >&2 || abort_verify "Could not extract $file"
    unzip -o "$zip" "$file.sha256" -d "$dir" >&2 || abort_verify "Checksum missing for $file"
  fi

  [ -f "$file_path" ] || abort_verify "$file does not exist"
  [ -f "$hash_path" ] || abort_verify "Checksum missing for $file"
  verify_hash "$file_path" "$hash_path"
  ui_print "- Verified $file"
}
