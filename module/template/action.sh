#!/system/bin/sh
PKG="cleveres.tricky.cleverestech"
APK="/data/adb/modules/cleveres_tricky/service.apk"

if ! pm list packages | grep -q "$PKG"; then
  echo "- Installing CleveresTricky Manager..."
  pm install -r "$APK"
fi

echo "- Launching Manager..."
am start -n "$PKG/.ui.MainActivity"
