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

# Label the service archive and injected native payloads for platform access.
find "$MODDIR" -maxdepth 1 -name '*.apk' -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null
find "$MODDIR" -maxdepth 1 -name '*.so' -exec chcon u:object_r:system_file:s0 {} + 2>/dev/null

# Executables need to be executable by daemon
[ -f "$MODDIR/inject" ] && chcon u:object_r:system_file:s0 "$MODDIR/inject" 2>/dev/null
[ -f "$MODDIR/daemon" ] && chcon u:object_r:system_file:s0 "$MODDIR/daemon" 2>/dev/null
