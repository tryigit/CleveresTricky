#!/system/bin/sh
# shellcheck disable=SC2034,SC2154
SKIPUNZIP=1

DEBUG=@DEBUG@
SONAME=@SONAME@
SUPPORTED_ABIS="@SUPPORTED_ABIS@"
MIN_SDK=@MIN_SDK@

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

# check architecture
case " $SUPPORTED_ABIS " in
  *" $ARCH "*) support=true ;;
  *) support=false ;;
esac
if [ "$support" != "true" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH (Supported)"
fi

# Check Android API after validating the installer-provided value.
case "$API" in
  ''|*[!0-9]*) abort "! Invalid Android SDK value: $API" ;;
esac
if [ "$API" -lt "$MIN_SDK" ]; then
  ui_print "! Unsupported sdk: $API"
  abort "! Minimal supported sdk is $MIN_SDK"
else
  ui_print "- Device sdk: $API (Supported)"
fi

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
chmod 755 "$MODPATH/daemon"
extract "$ZIPFILE" 'action.sh'       "$MODPATH"
chmod 755 "$MODPATH/action.sh"

case "$ARCH" in
  "x64")
    ui_print "- Extracting x64 libraries"
    extract "$ZIPFILE" "lib/x86_64/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/x86_64/inject" "$MODPATH" true
    ;;
  "arm64")
    ui_print "- Extracting arm64 libraries"
    extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH" true
    extract "$ZIPFILE" "lib/arm64-v8a/inject" "$MODPATH" true
    ;;
  *)
    abort "! Unsupported ARCH: $ARCH"
    ;;
esac

chmod 755 "$MODPATH/inject" "$MODPATH/daemon" "$MODPATH/service.sh" \
  "$MODPATH/post-fs-data.sh"

CONFIG_DIR=/data/adb/cleverestricky
if [ -L "$CONFIG_DIR" ]; then
  abort "! Refusing symlinked configuration directory: $CONFIG_DIR"
fi
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR"
fi
chmod 700 "$CONFIG_DIR"
chown 0:0 "$CONFIG_DIR"

for config_file in spoof_build_vars security_patch.txt target.txt drm_packages.txt boot_props_mode \
  spoof_enabled spoof_switch_initialized spoof_build_identity global_mode tee_broken_mode \
  auto_keybox_check random_on_boot rkp_passthrough drm_passthrough hide_sensitive_props \
  spoof_region_cn telephony; do
  if [ -L "$CONFIG_DIR/$config_file" ]; then
    abort "! Refusing symlinked configuration file: $config_file"
  fi
done

# Migrate existing installations once without re-enabling a switch the user
# intentionally disabled on a later update.
if [ ! -e "$CONFIG_DIR/spoof_switch_initialized" ]; then
  ui_print "- Enabling the master Spoof Engine switch"
  [ -e "$CONFIG_DIR/spoof_enabled" ] || : > "$CONFIG_DIR/spoof_enabled" \
    || abort "! Could not enable the Spoof Engine"
  : > "$CONFIG_DIR/spoof_switch_initialized" \
    || abort "! Could not write the Spoof Engine migration marker"
fi
chmod 600 "$CONFIG_DIR/spoof_switch_initialized"
[ -e "$CONFIG_DIR/spoof_enabled" ] && chmod 600 "$CONFIG_DIR/spoof_enabled"

if [ ! -f "$CONFIG_DIR/spoof_build_vars" ]; then
  ui_print "- Adding default spoof_build_vars"
  extract "$ZIPFILE" 'spoof_build_vars' "$TMPDIR"
  mv "$TMPDIR/spoof_build_vars" "$CONFIG_DIR/spoof_build_vars" \
    || abort "! Could not install spoof_build_vars"
fi
[ -f "$CONFIG_DIR/spoof_build_vars" ] && chmod 600 "$CONFIG_DIR/spoof_build_vars"

if [ ! -f "$CONFIG_DIR/security_patch.txt" ]; then
  ui_print "- Adding default security_patch.txt"
  extract "$ZIPFILE" 'security_patch.txt' "$TMPDIR"
  mv "$TMPDIR/security_patch.txt" "$CONFIG_DIR/security_patch.txt" \
    || abort "! Could not install security_patch.txt"
fi
[ -f "$CONFIG_DIR/security_patch.txt" ] && chmod 600 "$CONFIG_DIR/security_patch.txt"

if [ ! -f "$CONFIG_DIR/target.txt" ]; then
  ui_print "- Adding default target scope"
  extract "$ZIPFILE" 'target.txt' "$TMPDIR"
  mv "$TMPDIR/target.txt" "$CONFIG_DIR/target.txt" \
    || abort "! Could not install target.txt"
fi
[ -f "$CONFIG_DIR/target.txt" ] && chmod 600 "$CONFIG_DIR/target.txt"

INSTALL_COMPAT_DEFAULTS=false
if [ ! -f "$CONFIG_DIR/drm_packages.txt" ]; then
  INSTALL_COMPAT_DEFAULTS=true
  ui_print "- Adding default DRM passthrough scope"
  extract "$ZIPFILE" 'drm_packages.txt' "$TMPDIR"
  mv "$TMPDIR/drm_packages.txt" "$CONFIG_DIR/drm_packages.txt" \
    || abort "! Could not install drm_packages.txt"
fi
[ -f "$CONFIG_DIR/drm_packages.txt" ] && chmod 600 "$CONFIG_DIR/drm_packages.txt"

if [ ! -f "$CONFIG_DIR/boot_props_mode" ]; then
  ui_print "- Adding automatic boot-property policy"
  extract "$ZIPFILE" 'boot_props_mode' "$TMPDIR"
  mv "$TMPDIR/boot_props_mode" "$CONFIG_DIR/boot_props_mode" \
    || abort "! Could not install boot_props_mode"
fi
[ -f "$CONFIG_DIR/boot_props_mode" ] && chmod 600 "$CONFIG_DIR/boot_props_mode"

if [ "$INSTALL_COMPAT_DEFAULTS" = true ] && [ ! -e "$CONFIG_DIR/auto_keybox_check" ]; then
  ui_print "- Enabling daily keybox revocation checks"
  : > "$CONFIG_DIR/auto_keybox_check" \
    || abort "! Could not enable keybox revocation checks"
fi
[ -e "$CONFIG_DIR/auto_keybox_check" ] && chmod 600 "$CONFIG_DIR/auto_keybox_check"
for default_flag in rkp_passthrough drm_passthrough hide_sensitive_props; do
  if [ "$INSTALL_COMPAT_DEFAULTS" = true ] && [ ! -e "$CONFIG_DIR/$default_flag" ]; then
    ui_print "- Enabling $default_flag"
    : > "$CONFIG_DIR/$default_flag" \
      || abort "! Could not enable $default_flag"
  fi
  [ -e "$CONFIG_DIR/$default_flag" ] && chmod 600 "$CONFIG_DIR/$default_flag"
done
chown 0:0 "$CONFIG_DIR/spoof_build_vars" "$CONFIG_DIR/security_patch.txt" \
  "$CONFIG_DIR/target.txt" "$CONFIG_DIR/drm_packages.txt" \
  "$CONFIG_DIR/boot_props_mode" "$CONFIG_DIR/spoof_switch_initialized"
[ -e "$CONFIG_DIR/auto_keybox_check" ] && chown 0:0 "$CONFIG_DIR/auto_keybox_check"
[ -e "$CONFIG_DIR/spoof_enabled" ] && chown 0:0 "$CONFIG_DIR/spoof_enabled"
[ -e "$CONFIG_DIR/spoof_build_identity" ] && chown 0:0 "$CONFIG_DIR/spoof_build_identity"
[ -e "$CONFIG_DIR/rkp_passthrough" ] && chown 0:0 "$CONFIG_DIR/rkp_passthrough"
[ -e "$CONFIG_DIR/drm_passthrough" ] && chown 0:0 "$CONFIG_DIR/drm_passthrough"
[ -e "$CONFIG_DIR/hide_sensitive_props" ] && chown 0:0 "$CONFIG_DIR/hide_sensitive_props"
