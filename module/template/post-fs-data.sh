#!/system/bin/sh
CONFIG_DIR="${CLEVERES_TRICKY_CONFIG_DIR:-/data/adb/cleverestricky}"
CONFIG_ROOT_SAFE=false

if [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ]; then
  if chown 0:0 "$CONFIG_DIR" 2>/dev/null && chmod 700 "$CONFIG_DIR"; then
    CONFIG_ROOT_SAFE=true
  else
    log -t CleveresTricky "Config root permissions could not be secured; early config processing was skipped"
  fi
  chcon u:object_r:system_file:s0 "$CONFIG_DIR" 2>/dev/null
fi

boot_policy_feature_enabled() {
  feature=$1
  state="$CONFIG_DIR/boot_policy_state"

  # Upgrades may have v2 policy before the managed service has emitted its first
  # projection. In that case fail closed instead of treating a profile-derived
  # legacy marker as global policy. Legacy-only installations keep marker fallback.
  if [ ! -e "$state" ] && [ ! -L "$state" ]; then
    legacy_state="$CONFIG_DIR/policy_state_v2.json"
    if [ -e "$legacy_state" ] || [ -L "$legacy_state" ]; then
      return 1
    fi
    return 2
  fi
  [ -f "$state" ] && [ ! -L "$state" ] || return 1

  state_size=$(wc -c < "$state" 2>/dev/null) || return 1
  case "$state_size" in ''|*[!0-9]*) return 1 ;; esac
  [ "$state_size" -ge 1 ] && [ "$state_size" -le 128 ] || return 1

  projection_version=
  projection_build=
  projection_region=
  projection_refresh=
  while IFS= read -r line || [ -n "$line" ]; do
    [ "${#line}" -le 32 ] || return 1
    key=${line%%=*}
    [ "$key" != "$line" ] || return 1
    value=${line#*=}
    case "$value" in 0|1) ;; *)
      [ "$key" = version ] && [ "$value" = 1 ] || return 1
      ;;
    esac
    case "$key" in
      version)
        [ -z "$projection_version" ] || return 1
        projection_version=$value
        ;;
      build)
        [ -z "$projection_build" ] || return 1
        projection_build=$value
        ;;
      region)
        [ -z "$projection_region" ] || return 1
        projection_region=$value
        ;;
      refresh)
        [ -z "$projection_refresh" ] || return 1
        projection_refresh=$value
        ;;
      *) return 1 ;;
    esac
  done < "$state"

  [ "$projection_version" = 1 ] || return 1
  case "$projection_build:$projection_region:$projection_refresh" in
    [01]:[01]:[01]) ;;
    *) return 1 ;;
  esac

  case "$feature" in
    buildIdentity) [ "$projection_build" = 1 ] ;;
    regionIdentity) [ "$projection_region" = 1 ] ;;
    identityRefresh) [ "$projection_refresh" = 1 ] ;;
    *) return 1 ;;
  esac
}

optional_marker_enabled() {
  feature=$1
  marker=$2
  [ -f "$CONFIG_DIR/$marker" ] && [ ! -L "$CONFIG_DIR/$marker" ] || return 1

  boot_policy_feature_enabled "$feature"
  policy_status=$?
  [ "$policy_status" -eq 2 ] && return 0
  [ "$policy_status" -eq 0 ]
}

promote_staged_identity() {
  staged_file="$CONFIG_DIR/spoof_build_vars.next"
  active_file="$CONFIG_DIR/spoof_build_vars"

  [ "$CONFIG_ROOT_SAFE" = true ] || return 0
  if [ ! -e "$staged_file" ] && [ ! -L "$staged_file" ]; then
    return 0
  fi
  if [ -L "$staged_file" ]; then
    rm -f "$staged_file"
    log -t CleveresTricky "Removed an unsafe staged identity link"
    return 0
  fi
  if [ ! -f "$staged_file" ]; then
    log -t CleveresTricky "Non-regular staged identity was ignored"
    return 0
  fi

  if [ ! -f "$CONFIG_DIR/spoof_enabled" ] || [ -L "$CONFIG_DIR/spoof_enabled" ] ||
    ! optional_marker_enabled identityRefresh random_on_boot; then
    rm -f "$staged_file"
    return 0
  fi
  if [ -L "$active_file" ] || { [ -e "$active_file" ] && [ ! -f "$active_file" ]; }; then
    log -t CleveresTricky "Unsafe active identity path; staged identity was ignored"
    return 0
  fi

  staged_size=$(wc -c < "$staged_file" 2>/dev/null) || return 0
  if [ "$staged_size" -lt 1 ] || [ "$staged_size" -gt 1048576 ]; then
    rm -f "$staged_file"
    log -t CleveresTricky "Invalid staged identity size; staged identity was removed"
    return 0
  fi

  if ! chown 0:0 "$staged_file" 2>/dev/null || ! chmod 600 "$staged_file"; then
    log -t CleveresTricky "Staged identity permissions could not be secured"
    return 0
  fi
  chcon u:object_r:system_file:s0 "$staged_file" 2>/dev/null
  if mv -f "$staged_file" "$active_file"; then
    log -t CleveresTricky "Activated the prepared identity snapshot"
  else
    log -t CleveresTricky "Could not activate the prepared identity snapshot"
  fi
}

apply_prop() {
  resetprop -n "$1" "$2" >/dev/null 2>&1 || {
    log -t CleveresTricky "Failed to apply an app-visible boot property: $1"
    return 1
  }
}

remove_prop() {
  resetprop --delete "$1" >/dev/null 2>&1 || {
    log -t CleveresTricky "Failed to remove a legacy boot property: $1"
    return 1
  }
}

hide_boot_mode() {
  current_value=$(getprop "$1")
  case "$current_value" in
    *recovery*|*RECOVERY*) apply_prop "$1" unknown || return 1 ;;
  esac
}

apply_core_boot_properties() {
  apply_prop ro.boot.vbmeta.device_state locked || true
  apply_prop ro.boot.verifiedbootstate green || true
  apply_prop ro.boot.flash.locked 1 || true
  apply_prop ro.boot.warranty_bit 0 || true
  apply_prop ro.warranty_bit 0 || true
  apply_prop ro.debuggable 0 || true
  apply_prop ro.force.debuggable 0 || true
  apply_prop ro.secure 1 || true
  apply_prop ro.adb.secure 1 || true
  apply_prop ro.build.type user || true
  apply_prop ro.build.tags release-keys || true
  apply_prop ro.vendor.boot.warranty_bit 0 || true
  apply_prop ro.vendor.warranty_bit 0 || true
  android_sdk=$(getprop ro.build.version.sdk)
  case "$android_sdk" in ''|*[!0-9]*) android_sdk=0 ;; esac
  if [ "$android_sdk" -ge 36 ]; then
    remove_prop sys.oem_unlock_allowed || true
  else
    apply_prop sys.oem_unlock_allowed 0 || true
  fi
  apply_prop ro.secureboot.lockstate locked || true
  apply_prop ro.boot.realmebootstate green || true
  apply_prop ro.boot.realme.lockstate 1 || true
  hide_boot_mode ro.bootmode || true
  hide_boot_mode ro.boot.bootmode || true
  hide_boot_mode vendor.boot.bootmode || true
}

apply_optional_identity_properties() {
  [ "$CONFIG_ROOT_SAFE" = true ] || return 0
  command -v resetprop >/dev/null 2>&1 || {
    log -t CleveresTricky "resetprop is unavailable; identity properties were skipped"
    return 0
  }

  [ -f "$CONFIG_DIR/spoof_enabled" ] || return 0
  [ ! -L "$CONFIG_DIR/spoof_enabled" ] || return 0

  [ -f "$CONFIG_DIR/global_identity_mode" ] || return 0
  [ ! -L "$CONFIG_DIR/global_identity_mode" ] || return 0

  boot_mode=auto
  if [ -f "$CONFIG_DIR/boot_props_mode" ] && [ ! -L "$CONFIG_DIR/boot_props_mode" ]; then
    IFS= read -r boot_mode < "$CONFIG_DIR/boot_props_mode"
  fi
  case "$boot_mode" in force|disable|auto) ;; *) boot_mode=auto ;; esac
  [ "$boot_mode" != disable ] || return 0

  if optional_marker_enabled regionIdentity spoof_region_cn; then
    apply_prop ro.boot.hwc CN || true
    apply_prop gsm.operator.iso-country cn || true
    apply_prop gsm.sim.operator.iso-country cn || true
    apply_prop ro.boot.hwlevel MP || true
    apply_prop persist.radio.skhwc_matchres MATCH || true
  fi

  optional_marker_enabled buildIdentity spoof_build_identity || return 0
  vars_file="$CONFIG_DIR/spoof_build_vars"
  [ -f "$vars_file" ] && [ ! -L "$vars_file" ] || return 0
  vars_size=$(wc -c < "$vars_file" 2>/dev/null) || return 0
  case "$vars_size" in ''|*[!0-9]*) return 0 ;; esac
  [ "$vars_size" -ge 1 ] && [ "$vars_size" -le 1048576 ] || return 0

  if [ "$boot_mode" = auto ]; then
    identity_conflict=false
    for module_root in /data/adb/modules /data/adb/ksu/modules /data/adb/ap/modules; do
      [ "$identity_conflict" = false ] || break
      if [ ! -d "$module_root" ] || [ -L "$module_root" ]; then
        continue
      fi
      for candidate in "$module_root"/*; do
        if [ ! -d "$candidate" ] || [ -L "$candidate" ] || [ -f "$candidate/disable" ]; then
          continue
        fi
        module_id=${candidate##*/}
        module_id=$(printf '%s' "$module_id" | tr '[:upper:]' '[:lower:]')
        case "$module_id" in
          *playintegrity*|*autopif*|*auto_pif*|pif|pif_*|*playcurl*)
            identity_conflict=true
            break
            ;;
        esac
      done
    done
    if [ "$identity_conflict" = true ]; then
      log -t CleveresTricky "Another build-identity provider is active; reasserting the enabled CleveresTricky Build Identity"
    fi
  fi

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
  CT_SERIAL=
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
      SERIAL|ATTESTATION_ID_SERIAL) CT_SERIAL=$value ;;
    esac
  done < "$vars_file"

  [ -n "$CT_FINGERPRINT" ] || {
    log -t CleveresTricky "Build identity is enabled, but no persisted fingerprint is available"
    return 0
  }
  case "$CT_FINGERPRINT" in *[!A-Za-z0-9._:/+-]*) return 0 ;; esac

  apply_prop ro.build.fingerprint "$CT_FINGERPRINT" || true
  if [ -n "$CT_BRAND" ]; then apply_prop ro.product.brand "$CT_BRAND" || true; fi
  if [ -n "$CT_DEVICE" ]; then apply_prop ro.product.device "$CT_DEVICE" || true; fi
  if [ -n "$CT_PRODUCT" ]; then apply_prop ro.product.name "$CT_PRODUCT" || true; fi
  if [ -n "$CT_MANUFACTURER" ]; then apply_prop ro.product.manufacturer "$CT_MANUFACTURER" || true; fi
  if [ -n "$CT_MODEL" ]; then apply_prop ro.product.model "$CT_MODEL" || true; fi
  if [ -n "$CT_BUILD_ID" ]; then apply_prop ro.build.id "$CT_BUILD_ID" || true; fi
  if [ -n "$CT_RELEASE" ]; then
    apply_prop ro.build.version.release "$CT_RELEASE" || true
    apply_prop ro.build.version.release_or_codename "$CT_RELEASE" || true
  fi
  if [ -n "$CT_INCREMENTAL" ]; then apply_prop ro.build.version.incremental "$CT_INCREMENTAL" || true; fi
  if [ -n "$CT_TYPE" ]; then apply_prop ro.build.type "$CT_TYPE" || true; fi
  if [ -n "$CT_TAGS" ]; then apply_prop ro.build.tags "$CT_TAGS" || true; fi
  if [ -n "$CT_SECURITY_PATCH" ]; then
    case "$CT_SECURITY_PATCH" in
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) apply_prop ro.build.version.security_patch "$CT_SECURITY_PATCH" || true ;;
    esac
  fi
  if [ -n "$CT_SERIAL" ]; then
    apply_prop ro.serialno "$CT_SERIAL" || true
    apply_prop ro.boot.serialno "$CT_SERIAL" || true
    apply_prop ro.vendor.serialno "$CT_SERIAL" || true
    apply_prop ro.odm.serialno "$CT_SERIAL" || true
    apply_prop vendor.serialno "$CT_SERIAL" || true
    apply_prop vendor.boot.serialno "$CT_SERIAL" || true
    apply_prop persist.sys.serialno "$CT_SERIAL" || true
    apply_prop ro.ril.oem.sno "$CT_SERIAL" || true
    apply_prop ro.ril.oem.psno "$CT_SERIAL" || true
    apply_prop sys.serialno "$CT_SERIAL" || true
    apply_prop gsm.serial "$CT_SERIAL" || true
  fi
}

apply_early_properties() {
  [ "$CONFIG_ROOT_SAFE" = true ] || return 0
  command -v resetprop >/dev/null 2>&1 || {
    log -t CleveresTricky "resetprop is unavailable; boot property protection was skipped"
    return 0
  }
  apply_core_boot_properties
  apply_optional_identity_properties
}

if [ "${CLEVERES_TRICKY_IDENTITY_ONLY:-0}" = "1" ]; then
  apply_optional_identity_properties
else
  promote_staged_identity
  apply_early_properties
fi
