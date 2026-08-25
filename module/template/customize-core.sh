#!/system/bin/sh
# shellcheck disable=SC2034,SC2154
SKIPUNZIP=1

DEBUG=@DEBUG@
SONAME=@SONAME@
SUPPORTED_ABIS="@SUPPORTED_ABIS@"
MIN_SDK=@MIN_SDK@
MAX_SDK=@MAX_SDK@

if [ "$BOOTMODE" ] && [ "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
  ui_print "- KernelSU version: $KSU_KERNEL_VER_CODE (kernel) + $KSU_VER_CODE (ksud)"
elif [ "$BOOTMODE" ] && [ "$APATCH" ]; then
  ui_print "- Installing from APatch app"
  ui_print "- APatch version: $APATCH_VER_CODE"
elif [ "$MAGISK_VER_CODE" ] || command -v magisk >/dev/null 2>&1; then
  ui_print "*********************************************************"
  ui_print "! Magisk is NOT supported!"
  ui_print "! Magisk has been detected. Installation is blocked because Magisk causes issues."
  ui_print "! Please use KernelSU or APatch instead."
  abort    "*********************************************************"
else
  ui_print "*********************************************************"
  ui_print "! Install from recovery or unsupported root is not supported"
  ui_print "! Please install from KernelSU or APatch app"
  abort    "*********************************************************"
fi

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Installing $SONAME $VERSION"

case " $SUPPORTED_ABIS " in
  *" $ARCH "*) support=true ;;
  *) support=false ;;
esac
if [ "$support" != "true" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH (Supported)"
fi

case "$API" in
  ''|*[!0-9]*) abort "! Invalid Android SDK value: $API" ;;
esac
if [ "$API" -lt "$MIN_SDK" ]; then
  ui_print "! Unsupported sdk: $API"
  abort "! Minimal supported sdk is $MIN_SDK"
fi
if [ "$API" -gt "$MAX_SDK" ]; then
  ui_print "! Unsupported sdk: $API"
  abort "! Maximum validated sdk is $MAX_SDK"
fi
ui_print "- Device sdk: $API (Supported)"

ui_print "- Extracting verify.sh"
for bootstrap_target in "$TMPDIR/verify.sh" "$TMPDIR/verify.sh.sha256"; do
  if [ -L "$bootstrap_target" ] || { [ -e "$bootstrap_target" ] && [ ! -f "$bootstrap_target" ]; }; then
    abort "! Existing installer verification target is unsafe"
  fi
  rm -f "$bootstrap_target" || abort "! Could not prepare installer verification target"
done
unzip -o "$ZIPFILE" 'verify.sh' 'verify.sh.sha256' -d "$TMPDIR" >&2 \
  || abort "! Unable to extract installer verification files"
if [ -L "$TMPDIR/verify.sh" ] || [ ! -f "$TMPDIR/verify.sh" ] || \
  [ -L "$TMPDIR/verify.sh.sha256" ] || [ ! -f "$TMPDIR/verify.sh.sha256" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh safely!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
bootstrap_hash=$(tr -d '[:space:]' < "$TMPDIR/verify.sh.sha256")
case "$bootstrap_hash" in
  ''|*[!0-9A-Fa-f]*) abort "! Invalid verify.sh checksum" ;;
esac
[ "${#bootstrap_hash}" -eq 64 ] || abort "! Invalid verify.sh checksum length"
printf '%s  %s\n' "$bootstrap_hash" "$TMPDIR/verify.sh" | sha256sum -c - >/dev/null 2>&1 \
  || abort "! Failed to verify verify.sh before execution"
bootstrap_hash=
# shellcheck disable=SC1091
. "$TMPDIR/verify.sh"
extract "$ZIPFILE" 'customize.sh'  "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'verify.sh'     "$TMPDIR/.vunzip"

if ! command -v busybox >/dev/null 2>&1; then
  abort "! busybox is required for installation"
fi

ui_print "- Extracting module files"
extract "$ZIPFILE" 'module.prop'     "$MODPATH"
extract "$ZIPFILE" 'post-fs-data.sh' "$MODPATH"
extract "$ZIPFILE" 'service.sh' "$MODPATH"
extract "$ZIPFILE" 'action.sh' "$MODPATH"
extract "$ZIPFILE" 'service.apk'     "$MODPATH"
extract "$ZIPFILE" 'sepolicy.rule'   "$MODPATH"
extract "$ZIPFILE" 'daemon'          "$MODPATH"
chmod 755 "$MODPATH/daemon" || abort "! Could not make daemon executable"
if [ -L "$MODPATH/webroot" ] || { [ -e "$MODPATH/webroot" ] && [ ! -d "$MODPATH/webroot" ]; }; then
  abort "! Existing native WebUI path is unsafe"
fi
if [ -d "$MODPATH/webroot" ]; then
  rm -rf "$MODPATH/webroot" || abort "! Could not replace the native WebUI"
fi
extract "$ZIPFILE" 'webroot/index.html' "$MODPATH"
extract "$ZIPFILE" 'webroot/bridge.js'  "$MODPATH"
extract "$ZIPFILE" 'webroot/policy.js'  "$MODPATH"
extract "$ZIPFILE" 'webroot/ux.js'      "$MODPATH"
if [ -L "$MODPATH/webroot" ] || [ ! -d "$MODPATH/webroot" ] || \
  [ -L "$MODPATH/webroot/index.html" ] || [ ! -f "$MODPATH/webroot/index.html" ] || \
  [ -L "$MODPATH/webroot/bridge.js" ] || [ ! -f "$MODPATH/webroot/bridge.js" ] || \
  [ -L "$MODPATH/webroot/policy.js" ] || [ ! -f "$MODPATH/webroot/policy.js" ] || \
  [ -L "$MODPATH/webroot/ux.js" ] || [ ! -f "$MODPATH/webroot/ux.js" ]; then
  abort "! Native WebUI files are unsafe"
fi

case "$ARCH" in
  "x64")
    ui_print "- Extracting x64 libraries"
    extract "$ZIPFILE" "lib/x86_64/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/inject" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/webui_bridge" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/cleverestrickyd" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/cleverestricky_backend" "$MODPATH" true
    ;;
  "arm64")
    ui_print "- Extracting arm64 libraries"
    extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/inject" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/webui_bridge" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/cleverestrickyd" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/cleverestricky_backend" "$MODPATH" true
    ;;
  *)
    abort "! Unsupported ARCH: $ARCH"
    ;;
esac

for module_payload in module.prop post-fs-data.sh service.sh action.sh service.apk sepolicy.rule daemon \
  "lib$SONAME.so" inject webui_bridge cleverestrickyd cleverestricky_backend; do
  payload_path="$MODPATH/$module_payload"
  if [ -L "$payload_path" ] || [ ! -f "$payload_path" ]; then
    abort "! Extracted module payload is unsafe: $module_payload"
  fi
done

chmod 755 "$MODPATH/inject" "$MODPATH/webui_bridge" "$MODPATH/cleverestrickyd" \
  "$MODPATH/cleverestricky_backend" "$MODPATH/daemon" "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/post-fs-data.sh" \
  || abort "! Could not set module executable permissions"

CONFIG_DIR=/data/adb/cleverestricky
if [ -L "$CONFIG_DIR" ]; then
  abort "! Refusing symlinked configuration directory: $CONFIG_DIR"
fi
if [ -e "$CONFIG_DIR" ] && [ ! -d "$CONFIG_DIR" ]; then
  abort "! Configuration path is not a directory: $CONFIG_DIR"
fi
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR" || abort "! Could not create configuration directory"
fi
chmod 700 "$CONFIG_DIR" || abort "! Could not secure configuration directory"
chown 0:0 "$CONFIG_DIR" || abort "! Could not set configuration directory ownership"

for legacy_webui_file in web_port web_token.txt; do
  if [ -e "$CONFIG_DIR/$legacy_webui_file" ] || [ -L "$CONFIG_DIR/$legacy_webui_file" ]; then
    rm -f "$CONFIG_DIR/$legacy_webui_file" || abort "! Could not remove legacy WebUI metadata"
  fi
done

for config_file in spoof_build_vars security_patch.txt target.txt identity_target.txt drm_packages.txt boot_props_mode \
  spoof_enabled spoof_switch_initialized spoof_build_identity global_mode global_identity_mode tee_broken_mode \
  auto_keybox_check random_on_boot rkp_passthrough drm_passthrough hide_sensitive_props \
  spoof_region_cn telephony privacy_seed boot_key boot_hash app_config templates.json custom_templates module_hash \
  servers.json keybox.xml lang.json spoof_build_vars.next apply_profile policy_state_v2.json \
  policy_state_v2.last_good.json debug_logging settings_schema_v3; do
  config_path="$CONFIG_DIR/$config_file"
  if [ -e "$config_path" ] || [ -L "$config_path" ]; then
    if [ -L "$config_path" ] || [ ! -f "$config_path" ]; then
      abort "! Refusing unsafe configuration file: $config_file"
    fi
    chmod 600 "$config_path" || abort "! Could not secure configuration file: $config_file"
    chown 0:0 "$config_path" || abort "! Could not set configuration ownership: $config_file"
  fi
done

if [ -e "$CONFIG_DIR/keyboxes" ] || [ -L "$CONFIG_DIR/keyboxes" ]; then
  if [ -L "$CONFIG_DIR/keyboxes" ] || [ ! -d "$CONFIG_DIR/keyboxes" ]; then
    abort "! Refusing unsafe keybox directory"
  fi
  chmod 700 "$CONFIG_DIR/keyboxes" || abort "! Could not secure keybox directory"
  chown 0:0 "$CONFIG_DIR/keyboxes" || abort "! Could not set keybox directory ownership"
fi

# Schema v3 retires the historical RKP user switch. RKP infrastructure UIDs are
# protected by the runtime unconditionally, so retaining this file only creates
# conflicting Dashboard/Resources state. CBOX device caches are disposable and
# are regenerated after an upgrade to avoid carrying stale serialization state.
if [ ! -e "$CONFIG_DIR/settings_schema_v3" ]; then
  ui_print "- Migrating persisted settings to schema v3"
  if [ -e "$CONFIG_DIR/rkp_passthrough" ]; then
    rm -f "$CONFIG_DIR/rkp_passthrough" || abort "! Could not retire the old RKP setting"
  fi
  if [ -d "$CONFIG_DIR/keyboxes" ]; then
    for stale_cache in "$CONFIG_DIR"/keyboxes/*.cbox.cache; do
      [ -e "$stale_cache" ] || continue
      if [ -L "$stale_cache" ] || [ ! -f "$stale_cache" ]; then
        abort "! Refusing unsafe CBOX cache during migration"
      fi
      rm -f "$stale_cache" || abort "! Could not invalidate stale CBOX cache"
    done
  fi
  : > "$CONFIG_DIR/settings_schema_v3" || abort "! Could not write settings migration marker"
  chmod 600 "$CONFIG_DIR/settings_schema_v3" || abort "! Could not secure settings migration marker"
  chown 0:0 "$CONFIG_DIR/settings_schema_v3" || abort "! Could not set settings migration marker ownership"
fi

# Fresh installs use the recommended minimal default: global core coverage and
# automatic keybox checking are enabled; identity/privacy extras stay off. The
# v2 patch policy follows the device's captured/property patch level and only
# advances stale values through Automatic mode.
if [ ! -e "$CONFIG_DIR/spoof_switch_initialized" ]; then
  ui_print "- Applying recommended default settings"
  [ -e "$CONFIG_DIR/global_mode" ] || : > "$CONFIG_DIR/global_mode" \
    || abort "! Could not enable Global Mode"
  [ -e "$CONFIG_DIR/auto_keybox_check" ] || : > "$CONFIG_DIR/auto_keybox_check" \
    || abort "! Could not enable automatic keybox checking"
  if [ ! -e "$CONFIG_DIR/policy_state_v2.json" ]; then
    extract "$ZIPFILE" 'policy_state_v2.json' "$TMPDIR"
    mv "$TMPDIR/policy_state_v2.json" "$CONFIG_DIR/policy_state_v2.json" \
      || abort "! Could not install the default policy state"
  fi
  : > "$CONFIG_DIR/recommended_defaults_pending" \
    || abort "! Could not schedule device-aware default evaluation"
  chmod 600 "$CONFIG_DIR/recommended_defaults_pending" \
    || abort "! Could not secure device-aware default marker"
  chown 0:0 "$CONFIG_DIR/recommended_defaults_pending" \
    || abort "! Could not set device-aware default marker ownership"
  : > "$CONFIG_DIR/spoof_switch_initialized" \
    || abort "! Could not write the default-settings migration marker"
fi
chmod 600 "$CONFIG_DIR/spoof_switch_initialized" || abort "! Could not secure migration marker"
[ ! -e "$CONFIG_DIR/global_mode" ] || chmod 600 "$CONFIG_DIR/global_mode" \
  || abort "! Could not secure Global Mode switch"
[ ! -e "$CONFIG_DIR/auto_keybox_check" ] || chmod 600 "$CONFIG_DIR/auto_keybox_check" \
  || abort "! Could not secure automatic keybox checking"
[ ! -e "$CONFIG_DIR/policy_state_v2.json" ] || chmod 600 "$CONFIG_DIR/policy_state_v2.json" \
  || abort "! Could not secure default policy state"
[ ! -e "$CONFIG_DIR/spoof_enabled" ] || chmod 600 "$CONFIG_DIR/spoof_enabled" \
  || abort "! Could not secure identity Spoof Engine switch"

if [ ! -f "$CONFIG_DIR/spoof_build_vars" ]; then
  ui_print "- Adding default spoof_build_vars"
  extract "$ZIPFILE" 'spoof_build_vars' "$TMPDIR"
  mv "$TMPDIR/spoof_build_vars" "$CONFIG_DIR/spoof_build_vars" \
    || abort "! Could not install spoof_build_vars"
fi
chmod 600 "$CONFIG_DIR/spoof_build_vars" || abort "! Could not secure spoof_build_vars"

if [ ! -f "$CONFIG_DIR/security_patch.txt" ]; then
  ui_print "- Adding default security_patch.txt"
  extract "$ZIPFILE" 'security_patch.txt' "$TMPDIR"
  mv "$TMPDIR/security_patch.txt" "$CONFIG_DIR/security_patch.txt" \
    || abort "! Could not install security_patch.txt"
fi
chmod 600 "$CONFIG_DIR/security_patch.txt" || abort "! Could not secure security_patch.txt"

if [ ! -f "$CONFIG_DIR/target.txt" ]; then
  ui_print "- Adding default target scope"
  extract "$ZIPFILE" 'target.txt' "$TMPDIR"
  mv "$TMPDIR/target.txt" "$CONFIG_DIR/target.txt" \
    || abort "! Could not install target.txt"
fi
chmod 600 "$CONFIG_DIR/target.txt" || abort "! Could not secure target.txt"

if [ ! -f "$CONFIG_DIR/identity_target.txt" ]; then
  ui_print "- Adding default identity target scope"
  extract "$ZIPFILE" 'identity_target.txt' "$TMPDIR"
  mv "$TMPDIR/identity_target.txt" "$CONFIG_DIR/identity_target.txt" \
    || abort "! Could not install identity_target.txt"
fi
chmod 600 "$CONFIG_DIR/identity_target.txt" || abort "! Could not secure identity_target.txt"

if [ ! -f "$CONFIG_DIR/drm_packages.txt" ]; then
  ui_print "- Adding default DRM passthrough scope"
  extract "$ZIPFILE" 'drm_packages.txt' "$TMPDIR"
  mv "$TMPDIR/drm_packages.txt" "$CONFIG_DIR/drm_packages.txt" \
    || abort "! Could not install drm_packages.txt"
fi
chmod 600 "$CONFIG_DIR/drm_packages.txt" || abort "! Could not secure drm_packages.txt"

# Kept as an internal identity-build compatibility policy. Core bootloader/TEE
# property protection ignores this file and is always applied.
if [ ! -f "$CONFIG_DIR/boot_props_mode" ]; then
  ui_print "- Adding automatic identity-build compatibility policy"
  extract "$ZIPFILE" 'boot_props_mode' "$TMPDIR"
  mv "$TMPDIR/boot_props_mode" "$CONFIG_DIR/boot_props_mode" \
    || abort "! Could not install boot_props_mode"
fi
chmod 600 "$CONFIG_DIR/boot_props_mode" || abort "! Could not secure boot_props_mode"

for optional_flag in auto_keybox_check drm_passthrough hide_sensitive_props debug_logging; do
  [ ! -e "$CONFIG_DIR/$optional_flag" ] || chmod 600 "$CONFIG_DIR/$optional_flag" \
    || abort "! Could not secure $optional_flag"
done

chown 0:0 "$CONFIG_DIR/spoof_build_vars" "$CONFIG_DIR/security_patch.txt" \
  "$CONFIG_DIR/target.txt" "$CONFIG_DIR/identity_target.txt" "$CONFIG_DIR/drm_packages.txt" \
  "$CONFIG_DIR/boot_props_mode" "$CONFIG_DIR/spoof_switch_initialized" \
  || abort "! Could not set configuration file ownership"
[ ! -e "$CONFIG_DIR/global_mode" ] || chown 0:0 "$CONFIG_DIR/global_mode" \
  || abort "! Could not set Global Mode switch ownership"
[ ! -e "$CONFIG_DIR/global_identity_mode" ] || chown 0:0 "$CONFIG_DIR/global_identity_mode" \
  || abort "! Could not set Global Identity Mode switch ownership"
[ ! -e "$CONFIG_DIR/auto_keybox_check" ] || chown 0:0 "$CONFIG_DIR/auto_keybox_check" \
  || abort "! Could not set keybox revocation switch ownership"
[ ! -e "$CONFIG_DIR/policy_state_v2.json" ] || chown 0:0 "$CONFIG_DIR/policy_state_v2.json" \
  || abort "! Could not set policy state ownership"
[ ! -e "$CONFIG_DIR/spoof_enabled" ] || chown 0:0 "$CONFIG_DIR/spoof_enabled" \
  || abort "! Could not set identity Spoof Engine switch ownership"
[ ! -e "$CONFIG_DIR/spoof_build_identity" ] || chown 0:0 "$CONFIG_DIR/spoof_build_identity" \
  || abort "! Could not set identity build switch ownership"
[ ! -e "$CONFIG_DIR/drm_passthrough" ] || chown 0:0 "$CONFIG_DIR/drm_passthrough" \
  || abort "! Could not set DRM passthrough ownership"
[ ! -e "$CONFIG_DIR/hide_sensitive_props" ] || chown 0:0 "$CONFIG_DIR/hide_sensitive_props" \
  || abort "! Could not set sensitive-property switch ownership"
[ ! -e "$CONFIG_DIR/debug_logging" ] || chown 0:0 "$CONFIG_DIR/debug_logging" \
  || abort "! Could not set debug logging switch ownership"

normalize_conflicting_module_name() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]_.-'
}

is_conflicting_attestation_module() {
  conflict_id=$1
  conflict_name=$2

  case "$conflict_id" in
    tricky_store|teesim) return 0 ;;
  esac

  normalized_conflict_name=$(normalize_conflicting_module_name "$conflict_name")
  case "$normalized_conflict_name" in
    trickystore|trickystoreoss|teesimulator|teesimulatorrs) return 0 ;;
  esac

  return 1
}

remove_conflicting_modules_from_root() {
  modules_root=$1
  [ -e "$modules_root" ] || return 0
  [ -L "$modules_root" ] && abort "! Refusing symlinked modules root: $modules_root"
  [ -d "$modules_root" ] || abort "! Modules root is not a directory: $modules_root"

  for candidate in "$modules_root"/*; do
    [ -e "$candidate" ] || [ -L "$candidate" ] || continue
    candidate_dir=${candidate##*/}
    [ "$candidate_dir" = "cleverestricky" ] && continue

    if [ -L "$candidate" ]; then
      case "$candidate_dir" in
        tricky_store|teesim)
          ui_print "- Removing conflicting module link: $candidate_dir"
          rm -f "$candidate" || abort "! Could not remove conflicting module link: $candidate_dir"
          ;;
      esac
      continue
    fi

    [ -d "$candidate" ] || continue
    prop_file="$candidate/module.prop"

    if [ -L "$prop_file" ]; then
      case "$candidate_dir" in
        tricky_store|teesim)
          abort "! Refusing unsafe conflicting module metadata: $candidate_dir/module.prop"
          ;;
      esac
      continue
    fi

    if [ ! -f "$prop_file" ]; then
      case "$candidate_dir" in
        tricky_store|teesim)
          ui_print "- Removing incomplete conflicting module: $candidate_dir"
          rm -rf "$candidate" || abort "! Could not remove incomplete conflicting module: $candidate_dir"
          ;;
      esac
      continue
    fi

    conflict_id=$(grep_prop id "$prop_file" 2>/dev/null || true)
    conflict_name=$(grep_prop name "$prop_file" 2>/dev/null || true)
    [ "$conflict_id" = "cleverestricky" ] && continue

    if is_conflicting_attestation_module "$conflict_id" "$conflict_name"; then
      ui_print "- Removing conflicting module: ${conflict_name:-$candidate_dir} (${conflict_id:-unknown})"
      rm -rf "$candidate" || abort "! Could not remove conflicting module: $candidate_dir"
      if [ -e "$candidate" ] || [ -L "$candidate" ]; then
        abort "! Conflicting module still exists after removal: $candidate_dir"
      fi
    fi
  done
}

remove_conflicting_modules_from_root /data/adb/modules
remove_conflicting_modules_from_root /data/adb/modules_update
