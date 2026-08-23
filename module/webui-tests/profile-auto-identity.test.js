'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..', '..');
const policy = fs.readFileSync(path.join(repoRoot, 'module/template/webroot/policy.js'), 'utf8');
const policyOwner = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/AutoIdentityPolicy.kt'),
  'utf8',
);
const stateOwner = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/PolicyState.kt'),
  'utf8',
);
const configOwner = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/Config.kt'),
  'utf8',
);
const cronOwner = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/CronAutoIdentity.kt'),
  'utf8',
);
const profileStore = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/ProfileAutoIdentityStore.kt'),
  'utf8',
);

assert.match(
  policy,
  /\['identityRefresh', 'Identity refresh', 'Prepares a new identity for the next boot only while this option is enabled\.'\]/,
  'global Identity Refresh must keep its next-boot randomization meaning',
);
assert.match(
  policy,
  /feature\[0\] === 'identityRefresh'[\s\S]*?'Auto Identity \(Pixel Beta\)'/,
  'Profiles must expose identityRefresh as Auto Identity (Pixel Beta)',
);
assert.match(
  policyOwner,
  /globalCronEnabled && PolicyState\.isTopLevelFeatureEnabled\(PolicyState\.Feature\.BUILD_IDENTITY\)/,
  'global Cron must use only top-level Build Identity authority',
);
assert.match(
  policyOwner,
  /val profileScoped = PolicyState\.hasProfileAutoIdentityWork\(\)/,
  'profile Auto Identity scheduling must delegate to PolicyState',
);
assert.match(
  stateOwner,
  /profile\.applications\.isNotEmpty\(\)/,
  'non-active profile Auto Identity work must require application scope',
);
assert.match(
  configOwner,
  /if \(PolicyState\.isProfileAutoIdentityEnabled\(uid\)\) \{[\s\S]*?ProfileAutoIdentityStore\.get\(key\)\?\.let \{ return it \}/,
  'UID Build resolution must use the isolated snapshot only inside profile Auto Identity scope',
);
assert.match(
  cronOwner,
  /if \(decision\.profileScoped\) \{[\s\S]*?ProfileAutoIdentityStore\.save\(root, resolved\)\.getOrThrow\(\)/,
  'profile-scoped refresh must persist its own snapshot',
);
assert.match(
  cronOwner,
  /if \(!decision\.globalLiveApply\) \{[\s\S]*?global identity storage and Build properties were left unchanged[\s\S]*?return[\s\S]*?AutoIdentityPersistence\.save\(root, resolved\)\.getOrThrow\(\)/,
  'profile-only refresh must return before global identity persistence',
);
assert.doesNotMatch(
  profileStore,
  /File\(configDir,\s*"spoof_build_vars"\)/,
  'profile Auto Identity store must never address the global spoof_build_vars file',
);

console.log('Profile Auto Identity storage is isolated from global persistence and device-wide live apply.');
