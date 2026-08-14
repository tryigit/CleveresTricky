'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const moduleRoot = path.resolve(__dirname, '..');
const templateRoot = path.join(moduleRoot, 'template');

const policy = JSON.parse(fs.readFileSync(path.join(templateRoot, 'policy_state_v2.json'), 'utf8'));
assert.strictEqual(policy.version, 2);
assert.deepStrictEqual(policy.features, {
  buildIdentity: false,
  attestationIdentity: false,
  telephonyIdentity: false,
  regionIdentity: false,
  identityRefresh: false,
  securityPatch: false
});
assert.strictEqual(policy.securityPatch.automaticThresholdMonths, 6);
for (const component of ['system', 'vendor', 'boot']) {
  assert.strictEqual(policy.securityPatch[component].mode, 'automatic');
}
assert.deepStrictEqual(policy.profiles, []);
assert.strictEqual(policy.activeProfile, null);

const installer = fs.readFileSync(path.join(templateRoot, 'customize.sh'), 'utf8');
const start = installer.indexOf('# Fresh installs use the recommended minimal default:');
const end = installer.indexOf('if [ ! -f "$CONFIG_DIR/spoof_build_vars" ]', start);
assert.ok(start >= 0 && end > start, 'recommended default installer block must exist');
const defaultsBlock = installer.slice(start, end);
assert.match(defaultsBlock, /CONFIG_DIR\/global_mode/);
assert.match(defaultsBlock, /CONFIG_DIR\/auto_keybox_check/);
assert.match(defaultsBlock, /policy_state_v2\.json/);
assert.doesNotMatch(defaultsBlock, /: > "\$CONFIG_DIR\/spoof_enabled"/);
assert.doesNotMatch(defaultsBlock, /: > "\$CONFIG_DIR\/drm_passthrough"/);
assert.doesNotMatch(defaultsBlock, /: > "\$CONFIG_DIR\/telephony"/);

console.log('default-settings.test.js passed');
