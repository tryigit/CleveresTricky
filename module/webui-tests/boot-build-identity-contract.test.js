'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..');
const postFs = fs.readFileSync(path.join(repoRoot, 'module/template/post-fs-data.sh'), 'utf8');
const postMount = fs.readFileSync(path.join(repoRoot, 'module/template/post-mount.sh'), 'utf8');

function requireToken(token, message) {
  assert.ok(postFs.includes(token), message || `missing boot identity contract: ${token}`);
}

// Identity is still an explicit user feature, but v2 policy is authoritative when
// it exists. The legacy marker remains the fallback only when no v2 state exists.
requireToken('[ -f "$CONFIG_DIR/spoof_enabled" ] || return 0');
requireToken('policy_feature_enabled() {', 'v2 policy gate must remain explicit');
requireToken('depth == 1 && token == "features"', 'only the top-level features object may authorize global boot identity');
requireToken('[ "$policy_status" -eq 2 ] && return 0', 'legacy marker fallback must remain available when v2 state is absent');
requireToken('optional_marker_enabled buildIdentity spoof_build_identity || return 0', 'Build Identity must pass the v2/legacy compatibility gate');
requireToken('vars_file="$CONFIG_DIR/spoof_build_vars"');
requireToken('done < "$vars_file"');

// A competing PIF/Play Integrity identity provider is useful diagnostic
// information, but must never silently turn an enabled CleveresTricky feature
// into a no-op. This is the physical-device regression that previously left
// Build.*, fingerprint and model values untouched even while the UI reported
// Build Identity as enabled.
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
  /reasserting the enabled CleveresTricky Build Identity/,
  'provider conflicts must be logged while honoring the enabled feature',
);

// Core verified-boot protection and optional identity are separate phases. One
// unsupported Android/vendor property must never short-circuit Build Identity.
requireToken('apply_core_boot_properties');
requireToken('apply_optional_identity_properties');
const earlyOwner = postFs.match(/apply_early_properties\(\) \{([\s\S]*?)\n\}/);
assert.ok(earlyOwner, 'early property owner must remain explicit');
assert.match(earlyOwner[1], /apply_core_boot_properties/);
assert.match(earlyOwner[1], /apply_optional_identity_properties/);

// The saved Identity Manager fields must reach the properties Android Build
// snapshots before Zygote starts. Keep this list exhaustive for the fields the
// persisted template exposes to applications.
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
  requireToken(`apply_prop ${property} "$CT_${field}"`, `${field} must be applied to ${property}`);
}

requireToken('resetprop -n "$1" "$2"', 'boot identity must use KernelSU/APatch-safe resetprop -n');
requireToken('apply_early_properties', 'post-fs-data must execute the early property application owner');
assert.match(
  postMount,
  /CLEVERES_TRICKY_IDENTITY_ONLY=1/,
  'post-mount must select identity-only reapplication after root-manager property loading',
);
assert.match(
  postMount,
  /\. "\$MODDIR\/post-fs-data\.sh"/,
  'post-mount must reuse the same validated Build Identity owner instead of duplicating property mapping',
);

console.log('Build Identity boot contract is policy-authoritative, failure-isolated, exhaustive and reasserted after root-manager property loading.');
