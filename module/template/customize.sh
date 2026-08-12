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
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2 \
  || abort "! Unable to extract verify.sh"
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
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
extract "$ZIPFILE" 'service.sh'      "$MODPATH"
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
if [ -L "$MODPATH/webroot" ] || [ ! -d "$MODPATH/webroot" ] || \
  [ -L "$MODPATH/webroot/index.html" ] || [ ! -f "$MODPATH/webroot/index.html" ] || \
  [ -L "$MODPATH/webroot/bridge.js" ] || [ ! -f "$MODPATH/webroot/bridge.js" ] || \
  [ -L "$MODPATH/webroot/policy.js" ] || [ ! -f "$MODPATH/webroot/policy.js" ]; then
  abort "! Native WebUI files are unsafe"
fi
rm -f "$MODPATH/action.sh" "$MODPATH/action.sh.sha256" || abort "! Could not remove legacy WebUI launcher"

case "$ARCH" in
  "x64")
    ui_print "- Extracting x64 libraries"
    extract "$ZIPFILE" "lib/x86_64/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/inject" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/webui_bridge" "$MODPATH" true
    ;;
  "arm64")
    ui_print "- Extracting arm64 libraries"
    extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/inject" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/webui_bridge" "$MODPATH" true
    ;;
  *)
    abort "! Unsupported ARCH: $ARCH"
    ;;
esac

chmod 755 "$MODPATH/inject" "$MODPATH/webui_bridge" "$MODPATH/daemon" "$MODPATH/service.sh" \
  "$MODPATH/post-fs-data.sh" || abort "! Could not set module executable permissions"

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

for config_file in spoof_build_vars security_patch.txt target.txt drm_packages.txt boot_props_mode \
  spoof_enabled spoof_switch_initialized spoof_build_identity global_mode tee_broken_mode \
  auto_keybox_check random_on_boot rkp_passthrough drm_passthrough hide_sensitive_props \
  spoof_region_cn telephony privacy_seed boot_key boot_hash app_config templates.json custom_templates module_hash \
  servers.json keybox.xml lang.json spoof_build_vars.next apply_profile; do
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

# Fresh installs start with the core protection path enabled globally. Identity
# spoofing stays opt-in; upgrading users keep their existing switch files.
if [ ! -e "$CONFIG_DIR/spoof_switch_initialized" ]; then
  ui_print "- Enabling Global Mode (identity spoofing remains off by default)"
  [ -e "$CONFIG_DIR/global_mode" ] || : > "$CONFIG_DIR/global_mode" \
    || abort "! Could not enable Global Mode"
  : > "$CONFIG_DIR/spoof_switch_initialized" \
    || abort "! Could not write the default-settings migration marker"
fi
chmod 600 "$CONFIG_DIR/spoof_switch_initialized" || abort "! Could not secure migration marker"
[ ! -e "$CONFIG_DIR/global_mode" ] || chmod 600 "$CONFIG_DIR/global_mode" \
  || abort "! Could not secure Global Mode switch"
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

for optional_flag in auto_keybox_check rkp_passthrough drm_passthrough hide_sensitive_props; do
  [ ! -e "$CONFIG_DIR/$optional_flag" ] || chmod 600 "$CONFIG_DIR/$optional_flag" \
    || abort "! Could not secure $optional_flag"
done

chown 0:0 "$CONFIG_DIR/spoof_build_vars" "$CONFIG_DIR/security_patch.txt" \
  "$CONFIG_DIR/target.txt" "$CONFIG_DIR/drm_packages.txt" \
  "$CONFIG_DIR/boot_props_mode" "$CONFIG_DIR/spoof_switch_initialized" \
  || abort "! Could not set configuration file ownership"
[ ! -e "$CONFIG_DIR/global_mode" ] || chown 0:0 "$CONFIG_DIR/global_mode" \
  || abort "! Could not set Global Mode switch ownership"
[ ! -e "$CONFIG_DIR/auto_keybox_check" ] || chown 0:0 "$CONFIG_DIR/auto_keybox_check" \
  || abort "! Could not set keybox revocation switch ownership"
[ ! -e "$CONFIG_DIR/spoof_enabled" ] || chown 0:0 "$CONFIG_DIR/spoof_enabled" \
  || abort "! Could not set identity Spoof Engine switch ownership"
[ ! -e "$CONFIG_DIR/spoof_build_identity" ] || chown 0:0 "$CONFIG_DIR/spoof_build_identity"
[ ! -e "$CONFIG_DIR/rkp_passthrough" ] || chown 0:0 "$CONFIG_DIR/rkp_passthrough"
[ ! -e "$CONFIG_DIR/drm_passthrough" ] || chown 0:0 "$CONFIG_DIR/drm_passthrough"
[ ! -e "$CONFIG_DIR/hide_sensitive_props" ] || chown 0:0 "$CONFIG_DIR/hide_sensitive_props"
