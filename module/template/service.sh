#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="/data/adb/cleverestricky"
NATIVE_LOG="$CONFIG_DIR/native_runtime.log"

(
retry_delay=2
max_retry_delay=60
stable_runtime=120

has_legacy_optional_policy() {
  for flag in spoof_enabled spoof_build_identity telephony random_on_boot spoof_region_cn; do
    if [ -f "$CONFIG_DIR/$flag" ] && [ ! -L "$CONFIG_DIR/$flag" ]; then
      return 0
    fi
  done
  if [ -f "$CONFIG_DIR/security_patch.txt" ] && [ ! -L "$CONFIG_DIR/security_patch.txt" ] &&
    grep -q '^[[:space:]]*[^#[:space:]]' "$CONFIG_DIR/security_patch.txt" 2>/dev/null; then
    return 0
  fi
  return 1
}

bootstrap_default_policy() {
  state="$CONFIG_DIR/policy_state_v2.json"
  [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ] || return 0
  if [ -e "$state" ] || [ -L "$state" ]; then
    return 0
  fi

  # Do not reinterpret an upgrading user's legacy identity/security settings.
  # This bootstrap is only for a clean policy surface. Fresh defaults keep every
  # optional Identity/Security Patch child disabled; automatic patch *mode* is
  # preselected only for when the user explicitly enables Security Patch later.
  if has_legacy_optional_policy; then
    return 0
  fi

  tmp="$CONFIG_DIR/.policy_state_v2.json.$$"
  umask 077
  if ! cat > "$tmp" <<EOF
{"version":2,"features":{"buildIdentity":false,"attestationIdentity":false,"telephonyIdentity":false,"regionIdentity":false,"identityRefresh":false,"securityPatch":false},"securityPatch":{"automaticThresholdMonths":6,"system":{"mode":"automatic"},"vendor":{"mode":"automatic"},"boot":{"mode":"automatic"}},"profiles":[],"activeProfile":null}
EOF
  then
    rm -f "$tmp"
    return 0
  fi
  chown 0:0 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chmod 600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
  if mv -f "$tmp" "$state"; then
    log -t CleveresTricky "Initialized policy defaults: Global Mode independent, Identity and Security Patch off"
  else
    rm -f "$tmp"
  fi
}

mirror_root_keyboxes() {
  [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ] || return 0
  keybox_dir="$CONFIG_DIR/keyboxes"
  if [ -e "$keybox_dir" ] || [ -L "$keybox_dir" ]; then
    [ -d "$keybox_dir" ] && [ ! -L "$keybox_dir" ] || return 0
  else
    mkdir -p "$keybox_dir" 2>/dev/null || return 0
  fi
  chown 0:0 "$keybox_dir" 2>/dev/null
  chmod 700 "$keybox_dir" 2>/dev/null
  chcon u:object_r:system_file:s0 "$keybox_dir" 2>/dev/null

  for source in "$CONFIG_DIR"/*.xml "$CONFIG_DIR"/*.cbox; do
    if [ ! -f "$source" ] || [ -L "$source" ]; then
      continue
    fi
    base=${source##*/}
    # keybox.xml is the legacy primary source and is already loaded directly.
    [ "$base" != "keybox.xml" ] || continue
    case "$base" in
      .*|*[!A-Za-z0-9_.-]*) continue ;;
    esac
    lower=$(printf '%s' "$base" | tr '[:upper:]' '[:lower:]')
    case "$lower" in *.xml|*.cbox) ;; *) continue ;; esac

    destination="$keybox_dir/$base"
    [ ! -L "$destination" ] || continue
    tmp="$keybox_dir/.${base}.tmp.$$"
    if cp -f "$source" "$tmp" 2>/dev/null; then
      chown 0:0 "$tmp" 2>/dev/null
      chmod 600 "$tmp" 2>/dev/null
      chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
      mv -f "$tmp" "$destination" 2>/dev/null || rm -f "$tmp"
    else
      rm -f "$tmp"
    fi
  done
}

generate_backend_auth() {
  # Keep the backend capability in process environment only. Regenerate for every
  # daemon lifetime so stale workers from a previous supervisor iteration cannot
  # authenticate to the new Android adapter.
  token=$(dd if=/dev/urandom bs=32 count=1 2>/dev/null | od -An -tx1 2>/dev/null | tr -d '[:space:]')
  case "$token" in
    ''|*[!0-9a-f]*) return 1 ;;
  esac
  [ "${#token}" -eq 64 ] || return 1
  [ "$token" != "0000000000000000000000000000000000000000000000000000000000000000" ] || return 1
  CLEVERES_TRICKY_BACKEND_AUTH=$token
  export CLEVERES_TRICKY_BACKEND_AUTH
  token=
  return 0
}

rotate_native_log() {
  [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ] || return 1
  [ -f "$NATIVE_LOG" ] && [ ! -L "$NATIVE_LOG" ] || return 1
  log_size=$(wc -c < "$NATIVE_LOG" 2>/dev/null) || return 1
  case "$log_size" in ''|*[!0-9]*) return 1 ;; esac
  [ "$log_size" -gt 524288 ] || return 0

  tmp_log="$CONFIG_DIR/.native_runtime.log.$$"
  [ ! -e "$tmp_log" ] && [ ! -L "$tmp_log" ] || return 1
  umask 077
  if tail -c 262144 "$NATIVE_LOG" > "$tmp_log" 2>/dev/null; then
    chown 0:0 "$tmp_log" 2>/dev/null || { rm -f "$tmp_log"; return 1; }
    chmod 600 "$tmp_log" 2>/dev/null || { rm -f "$tmp_log"; return 1; }
    chcon u:object_r:system_file:s0 "$tmp_log" 2>/dev/null
    mv -f "$tmp_log" "$NATIVE_LOG" 2>/dev/null || { rm -f "$tmp_log"; return 1; }
  else
    rm -f "$tmp_log"
    return 1
  fi
}

prepare_native_log() {
  [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ] || return 1
  if [ -L "$NATIVE_LOG" ] || { [ -e "$NATIVE_LOG" ] && [ ! -f "$NATIVE_LOG" ]; }; then
    log -t CleveresTricky "Unsafe native runtime log path; native stderr capture was skipped"
    return 1
  fi

  if [ ! -f "$NATIVE_LOG" ]; then
    umask 077
    : > "$NATIVE_LOG" || return 1
  fi
  chown 0:0 "$NATIVE_LOG" 2>/dev/null || return 1
  chmod 600 "$NATIVE_LOG" 2>/dev/null || return 1
  chcon u:object_r:system_file:s0 "$NATIVE_LOG" 2>/dev/null
  rotate_native_log || return 1
  return 0
}

run_daemon_with_bounded_log() {
  if ! prepare_native_log; then
    log -t CleveresTricky "Native runtime log capture is unavailable; running daemon without file capture"
    "$MODDIR/daemon"
    return $?
  fi

  runtime_pipe="$CONFIG_DIR/.native_runtime.pipe.$$"
  if [ -e "$runtime_pipe" ] || [ -L "$runtime_pipe" ]; then
    log -t CleveresTricky "Native runtime log pipe is unavailable; running daemon without file capture"
    "$MODDIR/daemon"
    return $?
  fi
  umask 077
  if ! mkfifo "$runtime_pipe" 2>/dev/null; then
    log -t CleveresTricky "Native runtime log pipe could not be created; running daemon without file capture"
    "$MODDIR/daemon"
    return $?
  fi
  if ! chmod 600 "$runtime_pipe" 2>/dev/null || ! chown 0:0 "$runtime_pipe" 2>/dev/null; then
    rm -f "$runtime_pipe"
    log -t CleveresTricky "Native runtime log pipe permissions failed; running daemon without file capture"
    "$MODDIR/daemon"
    return $?
  fi
  chcon u:object_r:system_file:s0 "$runtime_pipe" 2>/dev/null

  (
    capture_ok=true
    line_count=0
    while IFS= read -r line || [ -n "$line" ]; do
      if [ "$capture_ok" = true ]; then
        if ! printf '%.8192s\n' "$line" >> "$NATIVE_LOG" 2>/dev/null; then
          capture_ok=false
        else
          line_count=$((line_count + 1))
          if [ "$line_count" -ge 32 ]; then
            rotate_native_log || capture_ok=false
            line_count=0
          fi
        fi
      fi
    done < "$runtime_pipe"
    if [ "$capture_ok" = true ]; then
      rotate_native_log || true
    fi
  ) &
  log_reader_pid=$!

  "$MODDIR/daemon" > "$runtime_pipe" 2>&1
  daemon_status=$?
  wait "$log_reader_pid" 2>/dev/null || true
  rm -f "$runtime_pipe"
  return "$daemon_status"
}

if [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ]; then
  chown 0:0 "$CONFIG_DIR" 2>/dev/null
  chmod 700 "$CONFIG_DIR" 2>/dev/null
  chcon u:object_r:system_file:s0 "$CONFIG_DIR" 2>/dev/null
  bootstrap_default_policy
  mirror_root_keyboxes
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type d -exec chmod 700 {} + 2>/dev/null
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type f -exec chmod 600 {} + 2>/dev/null
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type f -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null
fi

chcon u:object_r:system_file:s0 "$MODDIR/daemon" 2>/dev/null
chcon u:object_r:system_file:s0 "$MODDIR/cleverestrickyd" 2>/dev/null
chcon u:object_r:system_file:s0 "$MODDIR/cleverestricky_backend" 2>/dev/null
chcon u:object_r:system_file:s0 "$MODDIR/inject" 2>/dev/null
chcon u:object_r:system_file:s0 "$MODDIR/webui_bridge" 2>/dev/null
find "$MODDIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.so' \) \
  -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null

module_stopping() {
  [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]
}

while true; do
  if module_stopping; then
    log -t CleveresTricky "Module disabled or pending removal; daemon supervisor stopped"
    break
  fi
  if [ ! -x "$MODDIR/daemon" ]; then
    log -t CleveresTricky "Daemon executable is unavailable; daemon supervisor stopped"
    break
  fi
  if ! generate_backend_auth; then
    log -t CleveresTricky "Backend capability entropy unavailable; refusing to start daemon"
    sleep "$retry_delay"
    if [ "$retry_delay" -lt "$max_retry_delay" ]; then
      retry_delay=$((retry_delay * 2))
      [ "$retry_delay" -gt "$max_retry_delay" ] && retry_delay=$max_retry_delay
    fi
    continue
  fi

  started_at=$(date +%s)
  run_daemon_with_bounded_log
  exit_code=$?
  unset CLEVERES_TRICKY_BACKEND_AUTH
  stopped_at=$(date +%s)
  runtime=$((stopped_at - started_at))

  if [ "$runtime" -ge "$stable_runtime" ]; then
    retry_delay=2
  fi

  if module_stopping; then
    log -t CleveresTricky "Module disabled or pending removal after daemon exit; supervisor stopped"
    break
  fi

  log -t CleveresTricky \
    "Daemon exited with code $exit_code after ${runtime}s; retrying in ${retry_delay}s"
  sleep "$retry_delay"

  if [ "$runtime" -lt "$stable_runtime" ] && [ "$retry_delay" -lt "$max_retry_delay" ]; then
    retry_delay=$((retry_delay * 2))
    [ "$retry_delay" -gt "$max_retry_delay" ] && retry_delay=$max_retry_delay
  fi
done
) &