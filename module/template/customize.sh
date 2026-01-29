# shellcheck disable=SC2034
SKIPUNZIP=1

DEBUG=@DEBUG@
SONAME=@SONAME@
SUPPORTED_ABIS="@SUPPORTED_ABIS@"
MIN_SDK=@MIN_SDK@

if [ "$BOOTMODE" ] && [ "$KSU" ]; then
  ui_print "- Installing from KernelSU app"
  ui_print "- KernelSU version: $KSU_KERNEL_VER_CODE (kernel) + $KSU_VER_CODE (ksud)"
  if [ "$(which magisk)" ]; then
    ui_print "*********************************************************"
    ui_print "! Multiple root implementation is NOT supported!"
    ui_print "! Please uninstall Magisk before installing this module"
    abort    "*********************************************************"
  fi
elif [ "$BOOTMODE" ] && [ "$MAGISK_VER_CODE" ]; then
  ui_print "- Installing from Magisk app"
else
  ui_print "*********************************************************"
  ui_print "! Install from recovery is not supported"
  ui_print "! Please install from KernelSU or Magisk app"
  abort    "*********************************************************"
fi

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Installing $SONAME $VERSION"

# check architecture
support=false
for abi in $SUPPORTED_ABIS
do
  if [ "$ARCH" == "$abi" ]; then
    support=true
  fi
done
if [ "$support" == "false" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH (Supported)"
fi

# check android
if [ "$API" -lt $MIN_SDK ]; then
  ui_print "! Unsupported sdk: $API"
  abort "! Minimal supported sdk is $MIN_SDK"
else
  ui_print "- Device sdk: $API (Supported)"
fi

ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"
extract "$ZIPFILE" 'customize.sh'  "$TMPDIR/.vunzip"
extract "$ZIPFILE" 'verify.sh'     "$TMPDIR/.vunzip"

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

if [ "$ARCH" = "x64" ]; then
  ui_print "- Extracting x64 libraries"
  extract "$ZIPFILE" "lib/x86_64/lib$SONAME.so" "$MODPATH" true
  extract "$ZIPFILE" "lib/x86_64/libinject.so" "$MODPATH" true
else
  ui_print "- Extracting arm64 libraries"
  extract "$ZIPFILE" "lib/arm64-v8a/lib$SONAME.so" "$MODPATH" true
  extract "$ZIPFILE" "lib/arm64-v8a/libinject.so" "$MODPATH" true
fi

mv "$MODPATH/libinject.so" "$MODPATH/inject"
[ -f "$MODPATH/libinject.so.sha256" ] && mv "$MODPATH/libinject.so.sha256" "$MODPATH/inject.sha256"
chmod 755 "$MODPATH/inject"

CONFIG_DIR=/data/adb/cleveres_tricky
if [ ! -d "$CONFIG_DIR" ]; then
  ui_print "- Creating configuration directory"
  mkdir -p "$CONFIG_DIR"
  [ ! -f "$CONFIG_DIR/spoof_build_vars" ] && touch "$CONFIG_DIR/spoof_build_vars"
fi
chmod 700 "$CONFIG_DIR"
[ -f "$CONFIG_DIR/spoof_build_vars" ] && chmod 600 "$CONFIG_DIR/spoof_build_vars"

if [ ! -f "$CONFIG_DIR/keybox.xml" ]; then
  ui_print "- Adding default software keybox"
  extract "$ZIPFILE" 'keybox.xml' "$TMPDIR"
  mv "$TMPDIR/keybox.xml" "$CONFIG_DIR/keybox.xml"
fi
[ -f "$CONFIG_DIR/keybox.xml" ] && chmod 600 "$CONFIG_DIR/keybox.xml"

if [ ! -f "$CONFIG_DIR/target.txt" ]; then
  ui_print "- Adding default target scope"
  extract "$ZIPFILE" 'target.txt' "$TMPDIR"
  mv "$TMPDIR/target.txt" "$CONFIG_DIR/target.txt"
fi
[ -f "$CONFIG_DIR/target.txt" ] && chmod 600 "$CONFIG_DIR/target.txt"

if [ ! -d "/data/adb/modules/playintegrityfix" ]; then
  nohup am start -a android.intent.action.VIEW -d https://t.me/cleverestech >/dev/null 2>&1 &
fi
