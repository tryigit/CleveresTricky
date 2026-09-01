const assert = require('assert');
const fs = require('fs');

const index = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const policy = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

const fields = [
  ['imei','inputImei'], ['meid','inputMeid'], ['imsi','inputImsi'], ['iccid','inputIccid'], ['phone_number','inputPhoneNumber'],
  ['imei2','inputImei2'], ['meid2','inputMeid2'], ['imsi2','inputImsi2'], ['iccid2','inputIccid2'], ['phone_number2','inputPhoneNumber2'],
  ['serial','inputSerial']
];
for (const [field,id] of fields) {
  assert.ok(index.includes(`id="${id}"`), `missing identity input ${id}`);
  assert.ok(index.includes(`randomizeIdentityField('${field}')`), `missing per-value Random action for ${field}`);
}
assert.ok(index.includes("generateRandomIdentity('template')"), 'template Random action is missing');
assert.ok(index.includes("generateRandomIdentity('all')"), 'Randomize All action is missing');
assert.ok(index.includes("/api/random_identity?field="), 'randomization must use the bounded on-demand API');
assert.ok(!/setInterval\s*\([^)]*random/i.test(index), 'randomization must not create a periodic worker');

const featureStart = policy.indexOf('function buildFeatureCenterMarkup(prefix)');
const featureEnd = policy.indexOf('function renderFeatureCenter()', featureStart);
assert.ok(featureStart >= 0 && featureEnd > featureStart, 'Feature Center function is missing');
const featureCenter = policy.slice(featureStart, featureEnd);
assert.ok(featureCenter.includes('identityFeatureCardsMarkup(`${prefix}_identity`)'), 'Identity master and child switches must live on Dashboard');
assert.ok(policy.includes('bindIdentityControls(panel, `${prefix}_identity`)'), 'Dashboard Identity controls must be bound');
assert.ok(!policy.includes("panel.id = 'ct_identity_controls'"), 'Identity Controls must not return as a separate Identity-page panel');
assert.ok(policy.includes("const stale = document.getElementById('ct_identity_controls')"), 'legacy Identity Controls panel cleanup is missing');
assert.ok(policy.includes('Open Identity settings'), 'Dashboard must navigate into Identity detail settings');

console.log('Identity randomization and Dashboard placement regression tests passed');

assert(index.includes('id="inputVisibleSimCount"'), 'visible SIM count control must exist');
assert(index.includes("randomizeIdentityField('visible_sim_count')"), 'visible SIM count must support single-field randomization');
assert(index.includes("generateRandomIdentity('telephony')"), 'Telephony section must support grouped randomization');
assert(index.includes("visible_sim_count: 'inputVisibleSimCount'"), 'random payload must map visible SIM count');

assert(index.includes('id="inputVisibleCameraCount"'), 'visible camera count control must exist');
assert(index.includes("randomizeIdentityField('visible_camera_count')"), 'camera count must support single-field randomization');
assert(index.includes("visible_camera_count: 'inputVisibleCameraCount'"), 'random payload must map visible camera count');
assert(policy.includes("setLegacyToggle('camera_visibility'"), 'camera visibility must be an explicit opt-in legacy toggle');
assert(policy.includes('Disabled means no cameraserver interceptor is started.'), 'camera control must document disabled lifecycle');

// Verify telephony section visibility hides the action container when disabled
const mockTelephonyHeader = {
  style: {},
  getAttribute(name) { return name === 'data-section' ? 'telephony' : null; },
  nextElementSibling: {
    style: {},
    nextElementSibling: {
      style: {}
    }
  }
};
const mockSimHeader = {
  style: {},
  getAttribute(name) { return name === 'data-section' ? 'sim1' : null; },
  nextElementSibling: {
    style: {}
  }
};

const visibilityBlockMatch = policy.match(/headers\.forEach\(header => \{[\s\S]*?\n    \}\);/);
assert.ok(visibilityBlockMatch, 'headers visibility block must exist in policy.js');
const runVisibility = new Function('headers', 'telephonyOn', 'cameraOn', `
  ${visibilityBlockMatch[0]}
`);

// Test with telephonyOn = false
runVisibility([mockTelephonyHeader, mockSimHeader], false, false);
assert.strictEqual(mockTelephonyHeader.style.display, 'none');
assert.strictEqual(mockTelephonyHeader.nextElementSibling.style.display, 'none');
assert.strictEqual(mockTelephonyHeader.nextElementSibling.nextElementSibling.style.display, 'none');
assert.strictEqual(mockSimHeader.style.display, 'none');
assert.strictEqual(mockSimHeader.nextElementSibling.style.display, 'none');

// Test with telephonyOn = true
runVisibility([mockTelephonyHeader, mockSimHeader], true, false);
assert.strictEqual(mockTelephonyHeader.style.display, '');
assert.strictEqual(mockTelephonyHeader.nextElementSibling.style.display, '');
assert.strictEqual(mockTelephonyHeader.nextElementSibling.nextElementSibling.style.display, '');
assert.strictEqual(mockSimHeader.style.display, '');
assert.strictEqual(mockSimHeader.nextElementSibling.style.display, '');
