#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="/data/adb/cleverestricky"

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
  # This bootstrap is only for a clean policy surface.
  if has_legacy_optional_policy; then
    return 0
  fi

  patch_enabled=false
  patch_mode=device_default
  rom_patch=$(getprop ro.build.version.security_patch 2>/dev/null)
  now=$(date +%Y-%m-%d 2>/dev/null)

  case "$rom_patch:$now" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      patch_year=${rom_patch%%-*}
      patch_rest=${rom_patch#*-}
      patch_month=${patch_rest%%-*}
      patch_day=${patch_rest##*-}
      now_year=${now%%-*}
      now_rest=${now#*-}
      now_month=${now_rest%%-*}
      now_day=${now_rest##*-}

      patch_month=${patch_month#0}; [ -n "$patch_month" ] || patch_month=0
      patch_day=${patch_day#0}; [ -n "$patch_day" ] || patch_day=0
      now_month=${now_month#0}; [ -n "$now_month" ] || now_month=0
      now_day=${now_day#0}; [ -n "$now_day" ] || now_day=0

      case "$patch_year:$patch_month:$patch_day:$now_year:$now_month:$now_day" in
        *[!0-9:]*) ;;
        *)
          patch_serial=$((patch_year * 12 + patch_month))
          now_serial=$((now_year * 12 + now_month))
          month_age=$((now_serial - patch_serial))
          if [ "$month_age" -gt 6 ] || { [ "$month_age" -eq 6 ] && [ "$now_day" -gt "$patch_day" ]; }; then
            patch_enabled=true
            patch_mode=automatic
          fi
          ;;
      esac
      ;;
  esac

  tmp="$CONFIG_DIR/.policy_state_v2.json.$$"
  umask 077
  if ! cat > "$tmp" <<EOF
{"version":2,"features":{"buildIdentity":false,"attestationIdentity":false,"telephonyIdentity":false,"regionIdentity":false,"identityRefresh":false,"securityPatch":$patch_enabled},"securityPatch":{"automaticThresholdMonths":6,"system":{"mode":"$patch_mode"},"vendor":{"mode":"$patch_mode"},"boot":{"mode":"$patch_mode"}},"profiles":[],"activeProfile":null}
EOF
  then
    rm -f "$tmp"
    return 0
  fi
  chown 0:0 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chmod 600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
  if mv -f "$tmp" "$state"; then
    if [ "$patch_enabled" = true ]; then
      log -t CleveresTricky "Security Patch + Auto Security Patch enabled: ROM patch is older than six months"
    else
      log -t CleveresTricky "Initialized policy defaults: Global Mode independent, Identity and Security Patch off"
    fi
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

  started_at=$(date +%s)
  "$MODDIR/daemon"
  exit_code=$?
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
