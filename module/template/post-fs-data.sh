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

policy_feature_enabled() {
  feature=$1
  state="$CONFIG_DIR/policy_state_v2.json"

  # No v2 state means legacy marker compatibility remains authoritative.
  if [ ! -e "$state" ] && [ ! -L "$state" ]; then
    return 2
  fi
  if [ -L "$state" ] || [ ! -f "$state" ]; then
    return 1
  fi

  state_size=$(wc -c < "$state" 2>/dev/null) || return 1
  case "$state_size" in ''|*[!0-9]*) return 1 ;; esac
  [ "$state_size" -ge 1 ] && [ "$state_size" -le 524288 ] || return 1

  case "$feature" in
    buildIdentity|regionIdentity|identityRefresh) ;;
    *) return 1 ;;
  esac

  # policy_state_v2.json can also contain per-profile feature overrides. Only
  # the top-level `features` object may authorize global early-boot properties.
  # Scan JSON structure rather than grepping the whole file so a nested profile
  # override can never resurrect a disabled global feature.
  awk -v target="$feature" '
    BEGIN {
      depth = 0
      in_string = 0
      escaped = 0
      capture_key = 0
      candidate = 0
      seek_object = 0
      in_features = 0
      object = ""
      result = -1
    }
    {
      for (i = 1; i <= length($0); i++) {
        char = substr($0, i, 1)
        if (in_string) {
          if (escaped) {
            escaped = 0
            if (capture_key) token = token char
            if (in_features) object = object char
            continue
          }
          if (char == "\\") {
            escaped = 1
            if (in_features) object = object char
            continue
          }
          if (char == "\"") {
            in_string = 0
            if (capture_key) {
              capture_key = 0
              candidate = (depth == 1 && token == "features")
              token = ""
            }
            if (in_features) object = object char
            continue
          }
          if (capture_key) token = token char
          if (in_features) object = object char
          continue
        }

        if (char == "\"") {
          in_string = 1
          if (in_features) object = object char
          else if (depth == 1) {
            capture_key = 1
            token = ""
          }
          continue
        }

        if (candidate) {
          if (char ~ /[[:space:]]/) continue
          if (char == ":") {
            seek_object = 1
            candidate = 0
            continue
          }
          candidate = 0
        }

        if (seek_object) {
          if (char ~ /[[:space:]]/) continue
          if (char == "{" && depth == 1) {
            depth++
            in_features = 1
            object = "{"
            seek_object = 0
            continue
          }
          result = 1
          exit
        }

        if (char == "{") {
          depth++
          if (in_features) object = object char
          continue
        }
        if (char == "}") {
          if (in_features) {
            object = object char
            if (depth == 2) {
              pattern = "\\\"" target "\\\"[[:space:]]*:[[:space:]]*true([[:space:],}]|$)"
              result = object ~ pattern ? 0 : 1
              exit
            }
          }
          depth--
          if (depth < 0) {
            result = 1
            exit
          }
          continue
        }
        if (in_features) object = object char
      }
    }
    END {
      if (result < 0) result = 1
      exit result
    }
  ' "$state"
}

optional_marker_enabled() {
  feature=$1
  marker=$2
  [ -f "$CONFIG_DIR/$marker" ] && [ ! -L "$CONFIG_DIR/$marker" ] || return 1

  policy_feature_enabled "$feature"
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

  # Identity refresh is optional and belongs to Spoof Engine. Core boot protection
  # below never depends on either of these files.
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
  # Core bootloader / verified-boot property protection is intentionally
  # unconditional. Each property is independent: one vendor/property-service
  # incompatibility must not prevent the remaining protections or the optional
  # Build Identity phase from running.
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
  case "$android_sdk" in
    ''|*[!0-9]*) android_sdk=0 ;;
  esac
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

  # Everything in this phase belongs to optional identity spoofing.
  [ -f "$CONFIG_DIR/spoof_enabled" ] || return 0
  [ ! -L "$CONFIG_DIR/spoof_enabled" ] || return 0

  boot_mode=auto
  if [ -f "$CONFIG_DIR/boot_props_mode" ] && [ ! -L "$CONFIG_DIR/boot_props_mode" ]; then
    IFS= read -r boot_mode < "$CONFIG_DIR/boot_props_mode"
  fi
  case "$boot_mode" in
    force|disable|auto) ;;
    *) boot_mode=auto ;;
  esac
  [ "$boot_mode" != disable ] || return 0

  if optional_marker_enabled regionIdentity spoof_region_cn; then
    # Region Identity and Build Identity are separate child features. A failure in
    # one region property must never suppress the enabled Build Identity below.
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
  [ "$vars_size" -le 1048576 ] || return 0

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
    log -t CleveresTricky "Build identity is enabled, but no persisted fingerprint is available"
    return 0
  }
  case "$CT_FINGERPRINT" in *[!A-Za-z0-9._:/+-]*) return 0 ;; esac

  # Attempt every persisted Build field independently. This prevents one
  # vendor-specific property failure from turning the remaining identity into a
  # no-op, while each individual resetprop failure is still logged above.
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
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
        apply_prop ro.build.version.security_patch "$CT_SECURITY_PATCH" || true
        ;;
    esac
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
  # post-mount runs after root-manager system.prop loading. Reassert only the
  # optional identity phase so a later identity provider cannot silently win the
  # pre-Zygote property race.
  apply_optional_identity_properties
else
  promote_staged_identity
  apply_early_properties
fi
