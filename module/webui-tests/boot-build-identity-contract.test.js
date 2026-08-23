'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..');
const postFs = fs.readFileSync(path.join(repoRoot, 'module/template/post-fs-data.sh'), 'utf8');
const service = fs.readFileSync(path.join(repoRoot, 'module/template/service.sh'), 'utf8');

function requirePostFsToken(token, message) {
  assert.ok(postFs.includes(token), message || `missing early boot identity contract: ${token}`);
}

function requireServiceToken(token, message) {
  assert.ok(service.includes(token), message || `missing runtime build identity contract: ${token}`);
}

// Identity is an explicit user feature: both owner markers and the canonical
// persisted build-vars file must gate the early-boot application path.
requirePostFsToken('[ -f "$CONFIG_DIR/spoof_enabled" ] || return 0');
requirePostFsToken('[ -f "$CONFIG_DIR/spoof_build_identity" ] || return 0');
requirePostFsToken('vars_file="$CONFIG_DIR/spoof_build_vars"');
requirePostFsToken('done < "$vars_file"');

// A competing PIF/Play Integrity identity provider is useful diagnostic
// information, but must never silently turn an enabled CleveresTricky feature
// into a no-op.
const conflictMatch = postFs.match(
  /if \[ "\$identity_conflict" = true \]; then([\s\S]*?)\n\s*fi\n\s*fi\n\n\s*CT_FINGERPRINT=/,
);
assert.ok(conflictMatch, 'boot identity conflict branch must remain explicit and adjacent to identity application');
assert.doesNotMatch(
  conflictMatch[1],
  /\breturn\s+0\b/,
  'an enabled Build Identity must not be skipped just because another identity provider is installed',
);
assert.match(
  conflictMatch[1],
  /applying the enabled CleveresTricky Build Identity anyway/,
  'provider conflicts must be logged while honoring the enabled feature',
);

// The saved Identity Manager fields must reach both owners: post-fs-data writes
// them before a normal Zygote starts, while service.sh reconciles the same
// fields if a later property provider overwrites them or KernelSU is late-loaded.
for (const mapping of [
  ['FINGERPRINT', 'ro.build.fingerprint'],
  ['BRAND', 'ro.product.brand'],
  ['DEVICE', 'ro.product.device'],
  ['PRODUCT', 'ro.product.name'],
  ['MANUFACTURER', 'ro.product.manufacturer'],
  ['MODEL', 'ro.product.model'],
  ['BUILD_ID', 'ro.build.id'],
  ['RELEASE', 'ro.build.version.release'],
  ['RELEASE', 'ro.build.version.release_or_codename'],
  ['INCREMENTAL', 'ro.build.version.incremental'],
  ['TYPE', 'ro.build.type'],
  ['TAGS', 'ro.build.tags'],
  ['SECURITY_PATCH', 'ro.build.version.security_patch'],
]) {
  const [field, property] = mapping;
  requirePostFsToken(`apply_prop ${property} "$CT_${field}"`, `${field} must be applied early to ${property}`);
  requireServiceToken(
    `reconcile_prop ${property} "$CT_${field}"`,
    `${field} must be reconciled at runtime for ${property}`,
  );
}

requirePostFsToken('resetprop -n "$1" "$2"', 'early identity must use KernelSU/APatch-safe resetprop -n');
requirePostFsToken('apply_early_properties', 'post-fs-data must execute the early property application owner');

// A successful resetprop call is not evidence that applications see the value.
// The runtime owner must read each property back and treat a mismatch as a
// failure before any Zygote refresh is requested.
requireServiceToken('current=$(getprop "$prop_name" 2>/dev/null)');
requireServiceToken('resetprop -n "$prop_name" "$expected"');
requireServiceToken('if [ "$current" != "$expected" ]; then');
requireServiceToken('apply_failed=true');
requireServiceToken('property_reconcile_failed');

// KernelSU late-load happens after the system has fully booted, so Build.* was
// already snapshotted in Zygote. A normal boot only needs the refresh if a later
// provider overwrote our early values. Both cases must refresh Zygote once.
requireServiceToken('if [ "${KSU_LATE_LOAD:-0}" = 1 ] || [ "$had_mismatch" = true ]; then');
requireServiceToken('cat /proc/sys/kernel/random/boot_id');
requireServiceToken('BUILD_IDENTITY_RESTART_FILE="$CONFIG_DIR/build_identity_zygote_restart"');
requireServiceToken('if [ "$previous_boot_id" = "$boot_id" ]; then');
requireServiceToken('setprop ctl.restart zygote_secondary');
requireServiceToken('setprop ctl.restart zygote');
requireServiceToken('zygote_refresh_already_requested');

// Diagnostics must distinguish configuration from effective runtime state; the
// old build_identity=true line alone was insufficient to explain a stale app
// process on physical devices.
requireServiceToken('BUILD_IDENTITY_STATUS_FILE="$CONFIG_DIR/build_identity_runtime_status"');
for (const field of [
  'configured=',
  'properties_effective=',
  'app_process_effective=',
  'reason=',
  'mismatches=',
  'zygote_refresh=',
  'ksu_late_load=',
  'ksu_runtime_mode=',
]) {
  requireServiceToken(`printf '${field}%s\\n'`, `runtime status must expose ${field}`);
}

console.log(
  'Build Identity contract now covers early property writes, readback reconciliation, KernelSU late-load, and one-shot Zygote refresh.',
);