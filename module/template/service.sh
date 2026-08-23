#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="/data/adb/cleverestricky"

(
retry_delay=2
max_retry_delay=60
stable_runtime=120
BUILD_IDENTITY_STATUS_FILE="$CONFIG_DIR/build_identity_runtime_status"
BUILD_IDENTITY_RESTART_FILE="$CONFIG_DIR/build_identity_zygote_restart"

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

write_build_identity_status() {
  configured=$1
  properties_effective=$2
  app_process_effective=$3
  reason=$4
  mismatch_keys=$5
  zygote_refresh=$6

  [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ] || return 0
  tmp="$BUILD_IDENTITY_STATUS_FILE.tmp.$$"
  umask 077
  {
    printf 'configured=%s\n' "$configured"
    printf 'properties_effective=%s\n' "$properties_effective"
    printf 'app_process_effective=%s\n' "$app_process_effective"
    printf 'reason=%s\n' "$reason"
    printf 'mismatches=%s\n' "$mismatch_keys"
    printf 'zygote_refresh=%s\n' "$zygote_refresh"
    printf 'ksu_late_load=%s\n' "${KSU_LATE_LOAD:-0}"
    printf 'ksu_runtime_mode=%s\n' "${KSU_RUNTIME_MODE:-unknown}"
  } > "$tmp" || { rm -f "$tmp"; return 0; }
  chown 0:0 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chmod 600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 0; }
  chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
  mv -f "$tmp" "$BUILD_IDENTITY_STATUS_FILE" 2>/dev/null || rm -f "$tmp"
}

load_build_identity_vars() {
  CT_FINGERPRINT=
  CT_BRAND=
  CT_DEVICE=
  CT_PRODUCT=
  CT_MANUFACTURER=
  CT_MODEL=
  CT_BUILD_ID=
  CT_RELEASE=
  CT_INCREMENTAL=
  CT_TYPE=
  CT_TAGS=
  CT_SECURITY_PATCH=
  BUILD_IDENTITY_LOAD_REASON=not_configured

  if [ ! -d "$CONFIG_DIR" ] || [ -L "$CONFIG_DIR" ]; then
    BUILD_IDENTITY_LOAD_REASON=unsafe_config_root
    return 1
  fi
  [ -f "$CONFIG_DIR/spoof_enabled" ] && [ ! -L "$CONFIG_DIR/spoof_enabled" ] || return 1
  [ -f "$CONFIG_DIR/spoof_build_identity" ] && [ ! -L "$CONFIG_DIR/spoof_build_identity" ] || return 1

  boot_mode=auto
  if [ -f "$CONFIG_DIR/boot_props_mode" ] && [ ! -L "$CONFIG_DIR/boot_props_mode" ]; then
    IFS= read -r boot_mode < "$CONFIG_DIR/boot_props_mode"
  fi
  case "$boot_mode" in
    auto|force) ;;
    disable)
      BUILD_IDENTITY_LOAD_REASON=disabled_by_boot_props_mode
      return 1
      ;;
    *) boot_mode=auto ;;
  esac

  vars_file="$CONFIG_DIR/spoof_build_vars"
  if [ ! -f "$vars_file" ] || [ -L "$vars_file" ]; then
    BUILD_IDENTITY_LOAD_REASON=missing_build_vars
    return 1
  fi
  vars_size=$(wc -c < "$vars_file" 2>/dev/null) || {
    BUILD_IDENTITY_LOAD_REASON=unreadable_build_vars
    return 1
  }
  if [ "$vars_size" -lt 1 ] || [ "$vars_size" -gt 1048576 ]; then
    BUILD_IDENTITY_LOAD_REASON=invalid_build_vars_size
    return 1
  fi

  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    key=${line%%=*}
    [ "$key" != "$line" ] || continue
    value=${line#*=}
    [ "${#value}" -le 512 ] || continue
    case "$value" in *[![:print:]]*) continue ;; esac
    case "$key" in
      FINGERPRINT) CT_FINGERPRINT=$value ;;
      BRAND) CT_BRAND=$value ;;
      DEVICE) CT_DEVICE=$value ;;
      PRODUCT) CT_PRODUCT=$value ;;
      MANUFACTURER) CT_MANUFACTURER=$value ;;
      MODEL) CT_MODEL=$value ;;
      BUILD_ID) CT_BUILD_ID=$value ;;
      RELEASE) CT_RELEASE=$value ;;
      INCREMENTAL) CT_INCREMENTAL=$value ;;
      TYPE) CT_TYPE=$value ;;
      TAGS) CT_TAGS=$value ;;
      SECURITY_PATCH) CT_SECURITY_PATCH=$value ;;
    esac
  done < "$vars_file"

  [ -n "$CT_FINGERPRINT" ] || {
    BUILD_IDENTITY_LOAD_REASON=missing_fingerprint
    return 1
  }
  case "$CT_FINGERPRINT" in
    *[!A-Za-z0-9._:/+-]*)
      BUILD_IDENTITY_LOAD_REASON=invalid_fingerprint
      return 1
      ;;
  esac
  BUILD_IDENTITY_LOAD_REASON=loaded
  return 0
}

restart_zygote_once() {
  boot_id=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
  case "$boot_id" in
    ''|*[!A-Za-z0-9._-]*) return 1 ;;
  esac

  previous_boot_id=
  if [ -f "$BUILD_IDENTITY_RESTART_FILE" ] && [ ! -L "$BUILD_IDENTITY_RESTART_FILE" ]; then
    IFS= read -r previous_boot_id < "$BUILD_IDENTITY_RESTART_FILE"
  fi
  if [ "$previous_boot_id" = "$boot_id" ]; then
    return 2
  fi

  tmp="$BUILD_IDENTITY_RESTART_FILE.tmp.$$"
  umask 077
  printf '%s\n' "$boot_id" > "$tmp" || { rm -f "$tmp"; return 1; }
  chown 0:0 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
  chmod 600 "$tmp" 2>/dev/null || { rm -f "$tmp"; return 1; }
  chcon u:object_r:system_file:s0 "$tmp" 2>/dev/null
  mv -f "$tmp" "$BUILD_IDENTITY_RESTART_FILE" 2>/dev/null || { rm -f "$tmp"; return 1; }

  secondary_state=$(getprop init.svc.zygote_secondary 2>/dev/null)
  if [ -n "$secondary_state" ] && [ "$secondary_state" != stopped ]; then
    if ! setprop ctl.restart zygote_secondary; then
      rm -f "$BUILD_IDENTITY_RESTART_FILE"
      return 1
    fi
  fi
  if ! setprop ctl.restart zygote; then
    rm -f "$BUILD_IDENTITY_RESTART_FILE"
    return 1
  fi
  return 0
}

reconcile_build_identity_runtime() {
  if ! load_build_identity_vars; then
    write_build_identity_status false false false "$BUILD_IDENTITY_LOAD_REASON" "" none
    return 0
  fi
  if ! command -v resetprop >/dev/null 2>&1 || ! command -v getprop >/dev/null 2>&1; then
    write_build_identity_status true false false resetprop_unavailable "" none
    log -t CleveresTricky "Build Identity is configured, but runtime property tools are unavailable"
    return 0
  fi

  had_mismatch=false
  apply_failed=false
  mismatch_keys=
  reconcile_prop() {
    prop_name=$1
    expected=$2
    [ -n "$expected" ] || return 0
    current=$(getprop "$prop_name" 2>/dev/null)
    if [ "$current" != "$expected" ]; then
      had_mismatch=true
      if [ -n "$mismatch_keys" ]; then
        mismatch_keys="$mismatch_keys,$prop_name"
      else
        mismatch_keys=$prop_name
      fi
      if ! resetprop -n "$prop_name" "$expected" >/dev/null 2>&1; then
        apply_failed=true
        return 0
      fi
      current=$(getprop "$prop_name" 2>/dev/null)
      if [ "$current" != "$expected" ]; then
        apply_failed=true
      fi
    fi
  }

  reconcile_prop ro.build.fingerprint "$CT_FINGERPRINT"
  reconcile_prop ro.product.brand "$CT_BRAND"
  reconcile_prop ro.product.device "$CT_DEVICE"
  reconcile_prop ro.product.name "$CT_PRODUCT"
  reconcile_prop ro.product.manufacturer "$CT_MANUFACTURER"
  reconcile_prop ro.product.model "$CT_MODEL"
  reconcile_prop ro.build.id "$CT_BUILD_ID"
  reconcile_prop ro.build.version.release "$CT_RELEASE"
  reconcile_prop ro.build.version.release_or_codename "$CT_RELEASE"
  reconcile_prop ro.build.version.incremental "$CT_INCREMENTAL"
  reconcile_prop ro.build.type "$CT_TYPE"
  reconcile_prop ro.build.tags "$CT_TAGS"
  case "$CT_SECURITY_PATCH" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
      reconcile_prop ro.build.version.security_patch "$CT_SECURITY_PATCH"
      ;;
  esac

  if [ "$apply_failed" = true ]; then
    write_build_identity_status true false false property_reconcile_failed "$mismatch_keys" failed
    log -t CleveresTricky "Build Identity runtime reconciliation failed: $mismatch_keys"
    return 0
  fi

  needs_zygote_refresh=false
  if [ "${KSU_LATE_LOAD:-0}" = 1 ] || [ "$had_mismatch" = true ]; then
    needs_zygote_refresh=true
  fi

  if [ "$needs_zygote_refresh" = true ]; then
    restart_zygote_once
    restart_result=$?
    case "$restart_result" in
      0)
        write_build_identity_status true true true zygote_refresh_requested "$mismatch_keys" requested
        log -t CleveresTricky "Build Identity properties reconciled; requested one-time Zygote refresh so app processes inherit the configured identity"
        ;;
      2)
        write_build_identity_status true true true zygote_refresh_already_requested "$mismatch_keys" already_requested
        ;;
      *)
        write_build_identity_status true true false zygote_refresh_failed "$mismatch_keys" failed
        log -t CleveresTricky "Build Identity properties are correct, but Zygote refresh failed; app processes may still expose the pre-reconcile identity"
        ;;
    esac
  else
    write_build_identity_status true true true verified_without_runtime_reconcile "" not_required
  fi
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

# post-fs-data runs before Zygote on standard KernelSU/APatch and remains the
# first owner of boot properties. service.sh is the final safety net: it catches
# properties overwritten later in boot and KernelSU late-load, where module
# scripts necessarily start after the existing Zygote has already snapshotted
# android.os.Build static fields.
reconcile_build_identity_runtime

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
  "$MODDIR/daemon"
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