#!/system/bin/sh
# CleveresTricky AutoPIF - Automatic Pixel Beta Fingerprint Fetcher
# Goal: MEETS_STRONG_INTEGRITY
# Battery-optimized background updates

MODDIR="${MODDIR:-/data/adb/modules/cleverestricky}"
DATADIR="/data/adb/cleverestricky"
VERSION=$(grep "^version=" "$MODDIR/module.prop" 2>/dev/null | sed 's/version=//g')

# Tmpfs for processing (RAM-only, no disk I/O)
TEMPDIR="$MODDIR/temp"
[ -w /dev ] && TEMPDIR="/dev/cleverestricky"
[ -w /debug_ramdisk ] && TEMPDIR="/debug_ramdisk/cleverestricky"
mkdir -p "$TEMPDIR" 2>/dev/null

# Logging
log() { echo "[CleveresTricky] $1"; }
log_error() { echo "[!] $1" >&2; }

# Download helper (curl preferred, wget fallback)
download() {
    local url="$1" output="$2"
    if command -v curl >/dev/null 2>&1; then
        curl --connect-timeout 10 -Ls "$url" > "$output" 2>/dev/null
    else
        busybox wget -T 10 --no-check-certificate -qO - "$url" > "$output" 2>/dev/null
    fi
}

# Get model/product list as JSON (for Web UI)
get_device_list_json() {
    printf '{"devices":['
    local first=true
    echo "$MODEL_LIST" | while IFS='|' read -r model product; do
        [ -z "$model" ] && continue
        [ "$first" = true ] && first=false || printf ','
        printf '{"model":"%s","product":"%s"}' "$model" "$product"
    done
    printf ']}'
}

# Select random beta device
set_random_beta() {
    local count=$(echo "$MODEL_LIST" | wc -l)
    local rand=$((RANDOM % count + 1))
    MODEL=$(echo "$MODEL_LIST" | sed -n "${rand}p" | cut -d'|' -f1)
    PRODUCT=$(echo "$MODEL_LIST" | sed -n "${rand}p" | cut -d'|' -f2)
    
    # Fallback to Pixel 8 Pro
    [ -z "$MODEL" ] && MODEL="Pixel 8 Pro" && PRODUCT="husky_beta"
}

# Fetch latest Pixel Beta/Canary info from Google
fetch_pixel_beta() {
    log "Fetching latest Pixel Beta information..."
    
    # Get Pixel versions page
    download "https://developer.android.com/about/versions" "$TEMPDIR/versions.html"
    if [ ! -s "$TEMPDIR/versions.html" ]; then
        log_error "Failed to fetch Android versions page"
        return 1
    fi
    
    # Find latest version URL
    LATEST_URL=$(grep -o 'https://developer.android.com/about/versions/[0-9]*' "$TEMPDIR/versions.html" | sort -ru | head -n1)
    [ -z "$LATEST_URL" ] && LATEST_URL="https://developer.android.com/about/versions/16"
    
    download "$LATEST_URL" "$TEMPDIR/latest.html"
    
    # Get Flash Info page
    FI_URL="https://developer.android.com$(grep -o 'href="/about/versions/[^"]*download[^"]*"' "$TEMPDIR/latest.html" | head -n1 | cut -d'"' -f2)"
    [ -z "$FI_URL" ] && FI_URL="https://developer.android.com/about/versions/16/download"
    download "$FI_URL" "$TEMPDIR/fi.html"
    
    # Extract device information
    MODEL_LIST=$(grep -A1 'tr id=' "$TEMPDIR/fi.html" 2>/dev/null | \
        grep 'td' | sed 's;.*<td>\([^<]*\)</td>.*;\1;' | \
        paste -d'|' - - 2>/dev/null)
    
    if [ -z "$MODEL_LIST" ]; then
        # Fallback to known devices
        MODEL_LIST="Pixel 9 Pro XL|komodo_beta
Pixel 9 Pro|caiman_beta
Pixel 9|tokay_beta
Pixel 8 Pro|husky_beta
Pixel 8|shiba_beta
Pixel 7 Pro|cheetah_beta
Pixel 7|panther_beta
Pixel 6 Pro|raven_beta
Pixel 6|oriole_beta"
    fi
    
    return 0
}

# Get fingerprint from Flash Tool
fetch_fingerprint() {
    local product="$1"
    local device=$(echo "$product" | sed 's/_beta//')
    
    log "Fetching fingerprint for $device..."
    
    # Get Flash Tool API key
    download "https://flash.android.com" "$TEMPDIR/flash.html"
    FLASH_KEY=$(grep -o 'data-client-config=[^;]*;[^&]*' "$TEMPDIR/flash.html" | cut -d';' -f2 | cut -d'&' -f1)
    
    if [ -z "$FLASH_KEY" ]; then
        log_error "Failed to get Flash Tool API key"
        return 1
    fi
    
    # Fetch build info
    if command -v curl >/dev/null 2>&1; then
        curl --connect-timeout 10 -H "Referer: https://flash.android.com" \
            -s "https://content-flashstation-pa.googleapis.com/v1/builds?product=$product&key=$FLASH_KEY" \
            > "$TEMPDIR/builds.json"
    else
        busybox wget -T 10 --header "Referer: https://flash.android.com" \
            -qO - "https://content-flashstation-pa.googleapis.com/v1/builds?product=$product&key=$FLASH_KEY" \
            > "$TEMPDIR/builds.json"
    fi
    
    # Extract canary/beta build info
    busybox tac "$TEMPDIR/builds.json" 2>/dev/null | grep -m1 -A13 '"canary": true' > "$TEMPDIR/canary.json"
    
    BUILD_ID=$(grep 'releaseCandidateName' "$TEMPDIR/canary.json" | cut -d'"' -f4)
    INCREMENTAL=$(grep '"buildId"' "$TEMPDIR/canary.json" | cut -d'"' -f4)
    
    if [ -z "$BUILD_ID" ] || [ -z "$INCREMENTAL" ]; then
        log_error "Failed to extract build info"
        return 1
    fi
    
    FINGERPRINT="google/$product/$device:CANARY/$BUILD_ID/$INCREMENTAL:user/release-keys"
    
    # Get security patch from bulletins
    download "https://source.android.com/docs/security/bulletin/pixel" "$TEMPDIR/bulletin.html"
    CANARY_DATE=$(grep '"id"' "$TEMPDIR/canary.json" | sed -e 's;.*canary-\([^"]*\)".*;\1;' -e 's;^\(....\);\1-;')
    SECURITY_PATCH=$(grep "<td>$CANARY_DATE" "$TEMPDIR/bulletin.html" | sed 's;.*<td>\([^<]*\)</td>;\1;' | head -n1)
    
    # Fallback security patch
    [ -z "$SECURITY_PATCH" ] && SECURITY_PATCH="${CANARY_DATE}-05"
    
    return 0
}

# Write spoof_build_vars
write_spoof_vars() {
    local output="$DATADIR/spoof_build_vars"
    
    log "Writing spoof_build_vars..."
    
    # Preserve user overrides if exist
    local user_overrides=""
    if [ -f "$output" ]; then
        user_overrides=$(grep -E '^#|^(ATTESTATION_|MODULE_|KEYMINT_)' "$output" 2>/dev/null)
    fi
    
    cat > "$output" << EOF
# Auto-generated by CleveresTricky AutoPIF
# Generated: $(date '+%Y-%m-%d %H:%M:%S')
# Device: $MODEL ($PRODUCT)

MANUFACTURER=Google
MODEL=$MODEL
BRAND=google
PRODUCT=$PRODUCT
DEVICE=$(echo "$PRODUCT" | sed 's/_beta//')
FINGERPRINT=$FINGERPRINT
SECURITY_PATCH=$SECURITY_PATCH
ID=$BUILD_ID
INCREMENTAL=$INCREMENTAL
RELEASE=16
TYPE=user
TAGS=release-keys

# Hidden props for deep inspection bypass
ro.boot.verifiedbootstate=green
ro.boot.flash.locked=1
ro.boot.vbmeta.device_state=locked

$user_overrides
EOF

    log "Saved to $output"
}

# Update security patch
update_security_patch() {
    local target="$DATADIR/security_patch.txt"
    
    if [ ! -f "$DATADIR/auto_security_patch" ]; then
        return 0
    fi
    
    log "Updating security patch configuration..."
    
    local short_patch=$(echo "$SECURITY_PATCH" | tr -d '-')
    
    cat > "$target" << EOF
# Auto-generated security patch config
# For STRONG integrity, keep in sync with spoof_build_vars
system=$short_patch
boot=$SECURITY_PATCH
vendor=$SECURITY_PATCH
EOF
    
    log "Security patch updated: $SECURITY_PATCH"
}

# Cleanup temp files
cleanup() {
    rm -rf "$TEMPDIR"
}

# Kill GMS to apply changes
kill_gms() {
    for pid in $(busybox pidof com.google.android.gms.unstable com.android.vending 2>/dev/null); do
        log "Killing GMS process $pid"
        kill -9 "$pid" 2>/dev/null
    done
}

# Main execution
main() {
    log "CleveresTricky AutoPIF v${VERSION:-1.0}"
    
    case "$1" in
        --list|-l)
            fetch_pixel_beta
            get_device_list_json
            cleanup
            exit 0
            ;;
        --device|-d)
            PRODUCT="$2"
            ;;
    esac
    
    # Fetch data
    fetch_pixel_beta || { cleanup; exit 1; }
    
    # Select device
    if [ -z "$PRODUCT" ] || ! echo "$MODEL_LIST" | grep -q "$PRODUCT"; then
        set_random_beta
    else
        MODEL=$(echo "$MODEL_LIST" | grep "$PRODUCT" | cut -d'|' -f1)
    fi
    
    log "Selected: $MODEL ($PRODUCT)"
    
    # Fetch fingerprint
    fetch_fingerprint "$PRODUCT" || { cleanup; exit 1; }
    
    log "Fingerprint: $FINGERPRINT"
    log "Security Patch: $SECURITY_PATCH"
    
    # Write config
    write_spoof_vars
    update_security_patch
    
    # Cleanup
    cleanup
    
    # Kill GMS to apply
    kill_gms
    
    log "Done! Reboot recommended for full effect."
}

main "$@"
