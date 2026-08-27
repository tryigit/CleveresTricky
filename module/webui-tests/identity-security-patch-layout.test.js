const assert = require('assert');
const fs = require('fs');

const policy = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

assert.ok(policy.includes("makeTab('patch','Security Patch','spoof')"), 'Security Patch must be a standalone top-level tab');
assert.ok(policy.includes("makePage('patch','spoof')"), 'Security Patch must own a standalone page');
assert.ok(policy.includes("const stalePatchHost = document.getElementById('ct_identity_patch')"), 'retired embedded Security Patch host in Identity must be removed');

const featureStart = policy.indexOf('function buildFeatureCenterMarkup(prefix)');
const featureEnd = policy.indexOf('function renderFeatureCenter()', featureStart);
assert.ok(featureStart >= 0 && featureEnd > featureStart, 'Feature Center block must exist');
const featureBlock = policy.slice(featureStart, featureEnd);
assert.ok(featureBlock.includes('identityFeatureCardsMarkup(`${prefix}_identity`)'), 'Dashboard must own Identity master and child switches');
assert.ok(featureBlock.includes("cardMarkup(`${prefix}_sec_patch`,'Security Patch'"), 'Dashboard must expose Security Patch as a separate top-level card');

const identityStart = policy.indexOf('function identityFeatureCardsMarkup(prefix)');
const identityEnd = policy.indexOf('function identityControlsMarkup', identityStart);
const identityBlock = policy.slice(identityStart, identityEnd);
assert.ok(!identityBlock.includes('data-policy-feature="securityPatch"'), 'Security Patch must not appear inside Identity child rows');
assert.ok(identityBlock.includes('Open Identity settings'), 'Dashboard Identity group must still open detail settings');

assert.ok(!policy.includes("panel.id = 'ct_identity_controls'"), 'Identity page must not own a duplicate toggle panel');
assert.ok(policy.includes("const stale = document.getElementById('ct_identity_controls')"), 'legacy Identity-page toggle panel must be removed');
assert.ok(!policy.includes('id="ct_patch_master"'), 'Security Patch must not expose a second master toggle on the detail page');
assert.ok(!policy.includes("document.getElementById('ct_patch_master')"), 'Security Patch renderer must not bind a retired detail-page master toggle');
assert.ok(policy.includes('securityPatch: source.securityPatch === true'), 'Security Patch persistence must preserve the explicit global switch with strict boolean normalization');
assert.ok(!policy.includes('securityPatch: Boolean(features.securityPatch) || FEATURE_KEYS.some'), 'other Identity child switches must not silently force Security Patch back on');
assert.ok(policy.includes("FEATURE_KEYS.some(([key]) => Boolean(policyState.features[key]))"), 'Identity master state must follow only its own identity features');
assert.ok(policy.includes("['securityPatch', 'Security Patch'"), 'per-profile Security Patch compatibility must remain intact');
assert.ok(policy.includes('data-open-tab="patch"'), 'UI control on Dashboard must navigate to the Security Patch tab');

const bindStart = policy.indexOf('function bindIdentityControls(panel, prefix)');
const bindEnd = policy.indexOf('function installIdentityControls()', bindStart);
const bindBlock = policy.slice(bindStart, bindEnd);
assert.ok(!bindBlock.includes('next.features.securityPatch = enabled'), 'Identity master must not mutate the independent Security Patch feature');
assert.ok(bindBlock.includes("panel.querySelectorAll('[data-policy-feature]')"), 'individual Identity child switches must share the policy binding path');

const patchRenderStart = policy.indexOf('function renderPatchPage()');
const patchRenderEnd = policy.indexOf('async function inspectPatch()', patchRenderStart);
const patchRenderBlock = policy.slice(patchRenderStart, patchRenderEnd);
assert.ok(patchRenderBlock.includes('const patchOn = Boolean(policyState.features && policyState.features.securityPatch)'), 'detail controls must follow only the Security Patch switch');
assert.ok(!patchRenderBlock.includes('next.features.securityPatch = true'), 'editing patch modes must not silently re-enable a disabled Security Patch feature');

console.log('Dashboard Identity / Standalone Security Patch ownership checks passed');
