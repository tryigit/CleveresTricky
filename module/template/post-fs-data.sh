#!/system/bin/sh
MODDIR=${0%/*}
CONFIG_DIR="/data/adb/cleverestricky"

# Keep private material root-only. Apply labels without recursively crossing
# into unexpected mounts or following symlinks.
if [ -d "$CONFIG_DIR" ] && [ ! -L "$CONFIG_DIR" ]; then
  chown 0:0 "$CONFIG_DIR" 2>/dev/null
  chmod 700 "$CONFIG_DIR"
  chcon u:object_r:system_file:s0 "$CONFIG_DIR" 2>/dev/null
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type d -exec chmod 700 {} + 2>/dev/null
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type f -exec chmod 600 {} + 2>/dev/null
  find "$CONFIG_DIR" -xdev -maxdepth 2 -type f \
    -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null
fi

# Apply boot-only property views before Zygote snapshots android.os.Build.
# Every value comes from a fixed property name or a strictly bounded config
# field, and resetprop receives it as a quoted argument rather than shell code.
apply_early_properties() {
  [ -f "$CONFIG_DIR/spoof_enabled" ] || return 0
  [ ! -L "$CONFIG_DIR/spoof_enabled" ] || return 0
  command -v resetprop >/dev/null 2>&1 || {
    log -t CleveresTricky "resetprop is unavailable; boot property controls were skipped"
    return 0
  }

  boot_mode=auto
  if [ -f "$CONFIG_DIR/boot_props_mode" ] && [ ! -L "$CONFIG_DIR/boot_props_mode" ]; then
    IFS= read -r boot_mode < "$CONFIG_DIR/boot_props_mode"
  fi
  case "$boot_mode" in
    force|disable|auto) ;;
    *) boot_mode=auto ;;
  esac
  [ "$boot_mode" != disable ] || return 0

  apply_prop() {
    resetprop -n "$1" "$2" >/dev/null 2>&1 || {
      log -t CleveresTricky "Failed to apply an app-visible boot property"
      return 1
    }
  }

  hide_allowed=true
  if [ "$boot_mode" = auto ]; then
    for module_root in /data/adb/modules /data/adb/ksu/modules /data/adb/ap/modules; do
      [ -d "$module_root/zygisk_shamiko" ] && hide_allowed=false
    done
    vendor_identity="$(getprop ro.product.manufacturer) $(getprop ro.product.brand) $(getprop ro.product.vendor.manufacturer) $(getprop ro.product.vendor.brand)"
    vendor_identity=$(printf '%s' "$vendor_identity" | tr '[:upper:]' '[:lower:]')
    case "$vendor_identity" in
      *oplus*|*oppo*|*oneplus*|*realme*) hide_allowed=false ;;
    esac
  fi

  if [ "$hide_allowed" = true ] && [ -f "$CONFIG_DIR/hide_sensitive_props" ] && [ ! -L "$CONFIG_DIR/hide_sensitive_props" ]; then
    apply_prop ro.boot.vbmeta.device_state locked || return 0
    apply_prop ro.boot.verifiedbootstate green || return 0
    apply_prop ro.boot.flash.locked 1 || return 0
    apply_prop ro.boot.warranty_bit 0 || return 0
    apply_prop ro.warranty_bit 0 || return 0
    apply_prop ro.debuggable 0 || return 0
    apply_prop ro.force.debuggable 0 || return 0
    apply_prop ro.secure 1 || return 0
    apply_prop ro.adb.secure 1 || return 0
    apply_prop ro.build.type user || return 0
    apply_prop ro.build.tags release-keys || return 0
    apply_prop ro.vendor.boot.warranty_bit 0 || return 0
    apply_prop ro.vendor.warranty_bit 0 || return 0
    apply_prop sys.oem_unlock_allowed 0 || return 0
    apply_prop ro.secureboot.lockstate locked || return 0
    apply_prop ro.boot.realmebootstate green || return 0
    apply_prop ro.boot.realme.lockstate 1 || return 0
  fi

  if [ -f "$CONFIG_DIR/spoof_region_cn" ] && [ ! -L "$CONFIG_DIR/spoof_region_cn" ]; then
    apply_prop ro.boot.hwc CN || return 0
    apply_prop gsm.operator.iso-country cn || return 0
    apply_prop gsm.sim.operator.iso-country cn || return 0
    apply_prop ro.boot.hwlevel MP || return 0
    apply_prop persist.radio.skhwc_matchres MATCH || return 0
  fi

  [ -f "$CONFIG_DIR/spoof_build_identity" ] || return 0
  [ ! -L "$CONFIG_DIR/spoof_build_identity" ] || return 0
  vars_file="$CONFIG_DIR/spoof_build_vars"
  [ -f "$vars_file" ] && [ ! -L "$vars_file" ] || return 0
  vars_size=$(wc -c < "$vars_file" 2>/dev/null) || return 0
  [ "$vars_size" -le 1048576 ] || return 0

  if [ "$boot_mode" = auto ]; then
    identity_conflict=false
    for module_root in /data/adb/modules /data/adb/ksu/modules /data/adb/ap/modules; do
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
          *playintegrity*|*autopif*|*auto_pif*|pif|pif_*|*playcurl*) identity_conflict=true ;;
        esac
      done
    done
    if [ "$identity_conflict" = true ]; then
      log -t CleveresTricky "Another build-identity provider is active; template properties were skipped in auto mode"
      return 0
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

  apply_prop ro.build.fingerprint "$CT_FINGERPRINT" || return 0
  if [ -n "$CT_BRAND" ]; then apply_prop ro.product.brand "$CT_BRAND"; fi
  if [ -n "$CT_DEVICE" ]; then apply_prop ro.product.device "$CT_DEVICE"; fi
  if [ -n "$CT_PRODUCT" ]; then apply_prop ro.product.name "$CT_PRODUCT"; fi
  if [ -n "$CT_MANUFACTURER" ]; then apply_prop ro.product.manufacturer "$CT_MANUFACTURER"; fi
  if [ -n "$CT_MODEL" ]; then apply_prop ro.product.model "$CT_MODEL"; fi
  if [ -n "$CT_BUILD_ID" ]; then apply_prop ro.build.id "$CT_BUILD_ID"; fi
  if [ -n "$CT_RELEASE" ]; then
    apply_prop ro.build.version.release "$CT_RELEASE"
    apply_prop ro.build.version.release_or_codename "$CT_RELEASE"
  fi
  if [ -n "$CT_INCREMENTAL" ]; then apply_prop ro.build.version.incremental "$CT_INCREMENTAL"; fi
  if [ -n "$CT_TYPE" ]; then apply_prop ro.build.type "$CT_TYPE"; fi
  if [ -n "$CT_TAGS" ]; then apply_prop ro.build.tags "$CT_TAGS"; fi
  if [ -n "$CT_SECURITY_PATCH" ]; then
    case "$CT_SECURITY_PATCH" in
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])
        apply_prop ro.build.version.security_patch "$CT_SECURITY_PATCH"
        ;;
    esac
  fi
}

apply_early_properties

# Label the service archive and injected native payloads for platform access.
find "$MODDIR" -maxdepth 1 -name '*.apk' -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null
find "$MODDIR" -maxdepth 1 -name '*.so' -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null

# Executables need to be executable by daemon
[ -f "$MODDIR/inject" ] && chcon u:object_r:system_file:s0 "$MODDIR/inject" 2>/dev/null
[ -f "$MODDIR/daemon" ] && chcon u:object_r:system_file:s0 "$MODDIR/daemon" 2>/dev/null
