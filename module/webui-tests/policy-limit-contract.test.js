const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

assert.match(source, /const MAX_POLICY_PROFILES = 64;/, 'WebUI profile limit must match PolicyState');
assert.match(source, /const MAX_PROFILE_APPLICATIONS = 256;/, 'WebUI per-profile assignment limit must match PolicyState');
assert.match(source, /const MAX_TOTAL_ASSIGNMENTS = 2048;/, 'WebUI total assignment limit must match PolicyState');
assert.match(source, /validatePolicyLimits\(normalized\);/, 'policy saves must validate the complete normalized state');

const categoriesMatch = source.match(/const KEYBOX_PRIORITY_CATEGORIES = \[([\s\S]*?)\];/);
assert.ok(categoriesMatch, 'keybox priority category allowlist is missing');
const extractedCategories = Function(`return [${categoriesMatch[1]}];`)();
assert.equal(extractedCategories.length, 16, 'keybox priority allowlist must cover all 16 validity categories');
assert.equal(extractedCategories[0], 'VALID_RKP', 'keybox priority allowlist must start with VALID_RKP');
assert.equal(
  extractedCategories[extractedCategories.length - 1],
  'INVALID_VERIFICATION_FAILED_UNKNOWN',
  'keybox priority allowlist must end with INVALID_VERIFICATION_FAILED_UNKNOWN'
);

const start = source.indexOf('function safeClone(value)');
const end = source.indexOf('function transitionRequiresReboot', start);
assert.ok(start >= 0 && end > start, 'policy normalization implementation is missing');
const implementation = source.slice(start, end);

const context = { console };
vm.createContext(context);
vm.runInContext(`
  const PROFILE_FEATURES = [
    ['buildIdentity'], ['attestationIdentity'], ['telephonyIdentity'],
    ['regionIdentity'], ['identityRefresh'], ['securityPatch']
  ];
  const PATCH_COMPONENTS = [['system', 'System'], ['vendor', 'Vendor'], ['boot', 'Boot']];
  const PATCH_MODES = [
    ['device_default', 'Device default'], ['prop', 'ROM property'], ['manual', 'Manual date'],
    ['automatic', 'Automatic'], ['no', 'Omit']
  ];
  const MAX_POLICY_PROFILES = 64;
  const MAX_PROFILE_APPLICATIONS = 256;
  const MAX_TOTAL_ASSIGNMENTS = 2048;
  const MAX_PROFILE_VALUE_LENGTH = 256;
  const KEYBOX_PRIORITY_CATEGORIES = ${JSON.stringify(extractedCategories)};
  ${implementation}
  this.normalizePolicyState = normalizePolicyState;
  this.stateForSave = stateForSave;
`, context, { filename: 'policy.js#limit-contract' });

function features() {
  return {
    buildIdentity: false,
    attestationIdentity: false,
    telephonyIdentity: false,
    regionIdentity: false,
    identityRefresh: false,
    securityPatch: false
  };
}

function securityPatch() {
  return {
    automaticThresholdMonths: 6,
    system: { mode: 'device_default' },
    vendor: { mode: 'device_default' },
    boot: { mode: 'device_default' }
  };
}

function profile(name, applicationCount) {
  return {
    name,
    applications: Array.from({ length: applicationCount }, (_, index) => `com.example.${name}.${index}`),
    template: null,
    keybox: null,
    privacy: 'inherit',
    features: {},
    securityPatch: {},
    rkpPassthrough: null,
    drmPassthrough: null
  };
}

function policy(profiles) {
  return {
    version: 2,
    features: features(),
    securityPatch: securityPatch(),
    profiles,
    activeProfile: null
  };
}

const assignments = Array.from({ length: 256 }, (_, index) => `com.example.roundtrip.${index}`);
const canonical = policy([{ ...profile('roundtrip', 0), applications: assignments }]);
const normalized = context.normalizePolicyState(canonical);
assert.equal(normalized.profiles[0].applications.length, 256, 'canonical 256-assignment profile must survive WebUI normalization');
const saved = context.stateForSave(normalized);
assert.equal(saved.profiles[0].applications.length, 256, 'canonical 256-assignment profile must survive save normalization');
assert.equal(saved.profiles[0].applications.join('\n'), assignments.join('\n'), 'policy round-trip must not drop assignments 65-256');

assert.doesNotThrow(() => context.stateForSave(policy(Array.from({ length: 64 }, (_, index) => profile(`p${index}`, 0)))));

const normalized65Profiles = context.normalizePolicyState(policy(Array.from({ length: 65 }, (_, index) => profile(`p${index}`, 0))));
assert.equal(normalized65Profiles.profiles.length, 65, 'normalization must preserve the 65th profile for limit validation');
assert.throws(
  () => context.stateForSave(normalized65Profiles),
  /at most 64 profiles/,
  '65th profile must be rejected instead of silently discarded'
);

const normalized257Assignments = context.normalizePolicyState(policy([profile('too-many-apps', 257)]));
assert.equal(normalized257Assignments.profiles[0].applications.length, 257, 'normalization must preserve the 257th application assignment for limit validation');
assert.throws(
  () => context.stateForSave(normalized257Assignments),
  /at most 256 application assignments/,
  '257th application assignment must be rejected instead of silently discarded'
);

const filteredBeforeValidation = profile('filtered', 256);
filteredBeforeValidation.applications.push(filteredBeforeValidation.applications[0], 'x'.repeat(257));
assert.doesNotThrow(
  () => context.stateForSave(policy([filteredBeforeValidation])),
  'save validation must run after assignment deduplication and value filtering'
);

const totalLimitProfiles = Array.from({ length: 8 }, (_, index) => profile(`full${index}`, 256));
totalLimitProfiles.push(profile('overflow', 1));
assert.throws(
  () => context.stateForSave(policy(totalLimitProfiles)),
  /at most 2048 total application assignments/,
  'global assignment overflow must fail in WebUI before backend rejection'
);

// Values cross the vm realm boundary, so structural assertions compare JSON
// snapshots instead of relying on prototype-sensitive deep equality.
const snapshot = value => JSON.stringify(value);
const customOrder = [...extractedCategories].reverse();
const keyboxPolicy = policy([]);
keyboxPolicy.blockInvalidKeyboxes = false;
keyboxPolicy.keyboxPriorityOrder = { mode: 'custom', customOrder };
const normalizedKeybox = context.normalizePolicyState(keyboxPolicy);
assert.strictEqual(normalizedKeybox.blockInvalidKeyboxes, false, 'disabled block flag must survive normalization');
assert.strictEqual(
  snapshot(normalizedKeybox.keyboxPriorityOrder),
  snapshot({ mode: 'custom', customOrder }),
  'complete custom priority permutation must survive normalization'
);
const savedKeybox = context.stateForSave(normalizedKeybox);
assert.strictEqual(savedKeybox.blockInvalidKeyboxes, false, 'disabled block flag must survive save normalization');
assert.strictEqual(
  snapshot(savedKeybox.keyboxPriorityOrder),
  snapshot({ mode: 'custom', customOrder }),
  'complete custom priority permutation must survive save normalization'
);

const defaultedKeybox = context.stateForSave(policy([]));
assert.strictEqual(defaultedKeybox.blockInvalidKeyboxes, true, 'missing block flag must default to enabled like the backend');
assert.strictEqual(
  snapshot(defaultedKeybox.keyboxPriorityOrder),
  snapshot({ mode: 'default' }),
  'missing priority order must default like the backend'
);

for (const broken of [
  { mode: 'custom', customOrder: customOrder.slice(1) },
  { mode: 'custom', customOrder: [...customOrder.slice(0, -1), customOrder[0]] },
  { mode: 'custom', customOrder: [...customOrder.slice(0, -1), 'VALID_FUTURE'] },
  { mode: 'custom' }
]) {
  const normalized = context.normalizePolicyState({ ...policy([]), keyboxPriorityOrder: broken });
  assert.strictEqual(
    snapshot(normalized.keyboxPriorityOrder),
    snapshot({ mode: 'default' }),
    'incomplete, duplicate, unknown, or missing custom orders must fall back to default before save'
  );
}

console.log('Policy WebUI/backend limit contract regression checks passed');
