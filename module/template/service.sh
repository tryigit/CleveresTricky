#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="/data/adb/cleverestricky"
NATIVE_LOG="$CONFIG_DIR/native_runtime.log"
SUPERVISOR_PID_FILE="$CONFIG_DIR/supervisor.pid"
DAEMON_PID_FILE="$CONFIG_DIR/daemon.pid"

# BEGIN PID SAFETY HELPERS
process_start_ticks() {
  target_pid=$1
  proc_stat=$(dd if="/proc/$target_pid/stat" bs=16384 count=1 2>/dev/null) || return 1
  case "$proc_stat" in
    *') '*) ;;
    *) return 1 ;;
  esac
  stat_fields=${proc_stat##*) }
  # All fields after the parenthesized command are kernel-generated scalar tokens.
  # shellcheck disable=SC2086
  set -- $stat_fields
  [ "$#" -ge 20 ] || return 1
  start_ticks=${20}
  case "$start_ticks" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s' "$start_ticks"
}

process_matches_expected() {
  target_pid=$1
  expected_executable=$2
  expected_comm=$3
  expected_argument=$4

  if [ -n "$expected_executable" ]; then
    actual_executable=$(readlink "/proc/$target_pid/exe" 2>/dev/null) || return 1
    [ "$actual_executable" = "$expected_executable" ] ||
      [ "$actual_executable" = "$expected_executable (deleted)" ] || return 1
  fi
  if [ -n "$expected_comm" ]; then
    actual_comm=$(dd if="/proc/$target_pid/comm" bs=64 count=1 2>/dev/null) || return 1
    [ "$actual_comm" = "$expected_comm" ] || return 1
  fi
  if [ -n "$expected_argument" ]; then
    dd if="/proc/$target_pid/cmdline" bs=4096 count=1 2>/dev/null |
      tr '\000' '\n' | grep -F -x -- "$expected_argument" >/dev/null 2>&1 || return 1
  fi
  return 0
}

owned_process_alive() {
  target_pid=$1
  expected_start=$2
  expected_executable=$3
  expected_comm=$4
  expected_argument=$5
  [ -d "/proc/$target_pid" ] || return 1
  current_start=$(process_start_ticks "$target_pid") || return 1
  [ "$current_start" = "$expected_start" ] || return 1
  process_matches_expected "$target_pid" "$expected_executable" "$expected_comm" "$expected_argument"
}

terminate_pid() {
  pid_file=$1
  name=$2
  max_wait=$3
  expected_executable=$4
  expected_comm=$5
  expected_argument=$6
  if [ -L "$pid_file" ] || { [ -e "$pid_file" ] && [ ! -f "$pid_file" ]; }; then
    rm -f "$pid_file" 2>/dev/null || true
    return 0
  fi
  [ -f "$pid_file" ] || return 0
  pid_record=$(dd if="$pid_file" bs=65 count=1 2>/dev/null) || pid_record=
  if [ -z "$pid_record" ] || [ "${#pid_record}" -gt 64 ]; then
    rm -f "$pid_file" 2>/dev/null || true
    return 0
  fi
  case "$pid_record" in
    *' '* ) ;;
    *) rm -f "$pid_file" 2>/dev/null || true; return 0 ;;
  esac
  old_pid=${pid_record%% *}
  old_start=${pid_record#* }
  case "$old_pid:$old_start" in
    *[!0-9:]*|0:*|1:*) rm -f "$pid_file" 2>/dev/null || true; return 0 ;;
  esac
  if [ "${#old_pid}" -gt 10 ] || [ "${#old_start}" -gt 20 ]; then
    rm -f "$pid_file" 2>/dev/null || true
    return 0
  fi
  if owned_process_alive "$old_pid" "$old_start" "$expected_executable" "$expected_comm" "$expected_argument"; then
    log -t CleveresTricky "Stopping previous $name (PID $old_pid)"
    kill -TERM "$old_pid" 2>/dev/null || true
    wait_count=0
    while owned_process_alive "$old_pid" "$old_start" "$expected_executable" "$expected_comm" "$expected_argument" &&
      [ "$wait_count" -lt "$max_wait" ]; do
      sleep 0.1
      wait_count=$((wait_count + 1))
    done
    if owned_process_alive "$old_pid" "$old_start" "$expected_executable" "$expected_comm" "$expected_argument"; then
      kill -9 "$old_pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pid_file" 2>/dev/null || true
}
# END PID SAFETY HELPERS

terminate_previous_instances() {
  terminate_pid "$CONFIG_DIR/supervisor.pid" "supervisor" 15 "" "" "$MODDIR/service.sh"
  terminate_pid "$MODDIR/supervisor.pid" "supervisor" 15 "" "" "$MODDIR/service.sh"
  terminate_pid "$CONFIG_DIR/daemon.pid" "daemon" 10 "$MODDIR/cleverestrickyd" "" ""
  terminate_pid "$CONFIG_DIR/adapter.pid" "adapter" 20 "" "CleveresTricky" ""
  terminate_pid "$CONFIG_DIR/backend.pid" "backend" 10 "$MODDIR/cleverestricky_backend" "" ""
  rm -f "$CONFIG_DIR"/.native_runtime.pipe.* "$CONFIG_DIR"/.native_runtime.log.* "$CONFIG_DIR"/.policy_state_v2.json.* 2>/dev/null || true
  if [ -d "$CONFIG_DIR/keyboxes" ] && [ ! -L "$CONFIG_DIR/keyboxes" ]; then
    rm -f "$CONFIG_DIR"/keyboxes/.*.tmp.* 2>/dev/null || true
  fi
  if [ -f "$NATIVE_LOG" ] && [ ! -L "$NATIVE_LOG" ]; then
    : > "$NATIVE_LOG" 2>/dev/null || true
  fi
}

if [ -L "$CONFIG_DIR" ]; then
  log -t CleveresTricky "Config directory is a symlink; refusing supervisor startup"
  exit 1
fi

if [ ! -d "$CONFIG_DIR" ]; then
  if ! mkdir -p "$CONFIG_DIR" 2>/dev/null; then
    log -t CleveresTricky "Failed to create config directory; refusing supervisor startup"
    exit 1
  fi
  chmod 700 "$CONFIG_DIR" 2>/dev/null || true
  chcon u:object_r:system_file:s0 "$CONFIG_DIR" 2>/dev/null || true
fi

if [ ! -d "$CONFIG_DIR" ] || [ -L "$CONFIG_DIR" ]; then
  log -t CleveresTricky "Config path is not a safe directory; refusing supervisor startup"
  exit 1
fi

terminate_previous_instances

if ! : > "$SUPERVISOR_PID_FILE" 2>/dev/null || ! chmod 600 "$SUPERVISOR_PID_FILE" 2>/dev/null; then
  log -t CleveresTricky "Failed to initialize supervisor PID file; refusing supervisor startup"
  exit 1
fi

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
  max_mirrored_keyboxes=256
  mirror_count=0
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

  for source_dir in "$CONFIG_DIR" "$MODDIR" "$MODDIR/keyboxes"; do
    if [ ! -d "$source_dir" ] || [ -L "$source_dir" ] || [ "$source_dir" = "$keybox_dir" ]; then
      continue
    fi

    for source in "$source_dir"/*.xml "$source_dir"/*.cbox; do
      [ "$mirror_count" -lt "$max_mirrored_keyboxes" ] || return 0
      if [ ! -f "$source" ] || [ -L "$source" ]; then
        continue
      fi
      base=${source##*/}
      # keybox.xml is the legacy primary source and is already loaded directly if in CONFIG_DIR.
      if [ "$source_dir" = "$CONFIG_DIR" ] && [ "$base" = "keybox.xml" ]; then
        continue
      fi
      case "$base" in
        .*|*[!A-Za-z0-9_.-]*) continue ;;
      esac
      lower=$(printf '%s' "$base" | tr '[:upper:]' '[:lower:]')
      case "$lower" in
        *.xml) max_mirrored_keybox_bytes=10485760 ;;
        *.cbox) max_mirrored_keybox_bytes=10485812 ;;
        *) continue ;;
      esac

      destination="$keybox_dir/$base"
      [ ! -L "$destination" ] || continue
      tmp="$keybox_dir/.${base}.tmp.$$"
      rm -f "$tmp" 2>/dev/null || continue
      if dd if="$source" of="$tmp" bs=65536 count=161 2>/dev/null; then
        copied_size=$(wc -c < "$tmp" 2>/dev/null) || copied_size=
        case "$copied_size" in ''|*[!0-9]*) rm -f "$tmp"; continue ;; esac
        if [ "$copied_size" -lt 1 ] || [ "$copied_size" -gt "$max_mirrored_keybox_bytes" ]; then
          rm -f "$tmp"
          continue
        fi
        chown 0:0 "$tmp" 2>/dev/null
        chmod 600 "$tmp" 2>/dev/null
        chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
        if mv -f "$tmp" "$destination" 2>/dev/null; then
          mirror_count=$((mirror_count + 1))
        else
          rm -f "$tmp"
        fi
      else
        rm -f "$tmp"
      fi
    done
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
          if [ "$line_count" -ge 128 ]; then
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

  if [ "$exit_code" -eq 0 ]; then
    log -t CleveresTricky "Daemon exited cleanly or deferred to existing active instance; supervisor finished"
    break
  fi

  if [ "$runtime" -ge "$stable_runtime" ]; then
    retry_delay=2
  fi

  if module_stopping; then
    log -t CleveresTricky "Module disabled or pending removal after daemon exit; supervisor stopped"
    break
  fi

  terminate_pid "$DAEMON_PID_FILE" "daemon" 5 "$MODDIR/cleverestrickyd" "" ""
  terminate_pid "$CONFIG_DIR/backend.pid" "backend" 5 "$MODDIR/cleverestricky_backend" "" ""
  terminate_pid "$CONFIG_DIR/adapter.pid" "adapter" 5 "" "CleveresTricky" ""

  log -t CleveresTricky \
    "Daemon exited with code $exit_code after ${runtime}s; retrying in ${retry_delay}s"
  sleep "$retry_delay"

  if [ "$runtime" -lt "$stable_runtime" ] && [ "$retry_delay" -lt "$max_retry_delay" ]; then
    retry_delay=$((retry_delay * 2))
    [ "$retry_delay" -gt "$max_retry_delay" ] && retry_delay=$max_retry_delay
  fi
done
) &
supervisor_pid=$!
supervisor_start=
start_probe=0
while [ "$start_probe" -lt 10 ] && [ -z "$supervisor_start" ]; do
  supervisor_start=$(process_start_ticks "$supervisor_pid") || supervisor_start=
  [ -n "$supervisor_start" ] || sleep 0.01
  start_probe=$((start_probe + 1))
done
if [ -z "$supervisor_start" ] ||
  ! printf '%s %s\n' "$supervisor_pid" "$supervisor_start" > "$SUPERVISOR_PID_FILE" 2>/dev/null ||
  [ ! -s "$SUPERVISOR_PID_FILE" ]; then
  log -t CleveresTricky "Failed to record supervisor PID; terminating supervisor"
  kill -9 "$supervisor_pid" 2>/dev/null || true
  rm -f "$SUPERVISOR_PID_FILE" 2>/dev/null || true
  exit 1
fi
