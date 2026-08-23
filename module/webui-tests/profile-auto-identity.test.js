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
const cronOwner = fs.readFileSync(
  path.join(repoRoot, 'service/src/main/java/cleveres/tricky/cleverestech/CronAutoIdentity.kt'),
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
  policy,
  /Assigned apps use the refreshed identity; profile-only refresh never resets device-wide Build properties\./,
  'profile Auto Identity copy must explain application scoping and no global resetprop',
);

assert.match(
  policyOwner,
  /globalCronEnabled && PolicyState\.isTopLevelFeatureEnabled\(PolicyState\.Feature\.BUILD_IDENTITY\)/,
  'global Cron must use only top-level Build Identity authority',
);
assert.match(
  policyOwner,
  /val profileScoped = PolicyState\.hasProfileAutoIdentityWork\(\)/,
  'profile Auto Identity scheduling must delegate to the canonical PolicyState owner',
);
assert.match(
  stateOwner,
  /activeProfile\(current\)\?\.featureOverrides\?\.get\(Feature\.IDENTITY_REFRESH\) \?: false/,
  'profile Auto Identity inheritance must originate from profile overrides, not the global boot refresh flag',
);
assert.match(
  stateOwner,
  /profile\?\.featureOverrides\?\.get\(Feature\.IDENTITY_REFRESH\) \?: activeOverride/,
  'selected profile must be able to override inherited Auto Identity',
);
assert.match(
  stateOwner,
  /profile\.applications\.isNotEmpty\(\)/,
  'non-active profile Auto Identity work must require application scope',
);
assert.match(
  stateOwner,
  /return resolved\.profileAutoIdentity && resolved\.features\.buildIdentity/,
  'profile Auto Identity must remain ineffective when Build Identity is disabled for that UID',
);
assert.match(
  cronOwner,
  /if \(!decision\.globalLiveApply\) \{[\s\S]*?global Build properties were left unchanged[\s\S]*?return/,
  'profile-only Auto Identity must not execute device-wide live apply',
);

console.log('Profile Auto Identity policy ownership remains app-scoped while global Identity Refresh keeps its boot-only semantics.');
