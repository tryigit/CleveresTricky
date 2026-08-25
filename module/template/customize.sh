#!/system/bin/sh
# Keep the verified installer implementation intact while allowing the package
# to carry auxiliary WebUI assets that are loaded after the core extraction.
# shellcheck disable=SC1091
core="$TMPDIR/cleveres_customize_core.sh"
core_hash="$TMPDIR/cleveres_customize_core.sh.sha256"
unzip -o "$ZIPFILE" 'customize-core.sh' 'customize-core.sh.sha256' -d "$TMPDIR" >&2 \
  || abort "! Unable to extract installer core"
if [ -L "$core" ] || [ ! -f "$core" ] || [ -L "$core_hash" ] || [ ! -f "$core_hash" ]; then
  abort "! Installer core is unsafe"
fi
expected_core_hash=$(tr -d '[:space:]' < "$core_hash")
case "$expected_core_hash" in
  ''|*[!0-9A-Fa-f]*) abort "! Invalid installer core checksum" ;;
esac
[ "${#expected_core_hash}" -eq 64 ] || abort "! Invalid installer core checksum length"
printf '%s  %s\n' "$expected_core_hash" "$core" | sha256sum -c - >/dev/null 2>&1 \
  || abort "! Failed to verify installer core"
# The core installer owns all existing validation and installation behavior.
. "$core" || abort "! Installer core failed"

# ux.js is a small bootstrap on this branch; keep the original UX implementation
# as a separately verified payload next to it.
extract "$ZIPFILE" 'webroot/ux-core.js' "$MODPATH" \
  || abort "! Unable to install UX core"
if [ -L "$MODPATH/webroot/ux-core.js" ] || [ ! -f "$MODPATH/webroot/ux-core.js" ]; then
  abort "! UX core is unsafe"
fi
