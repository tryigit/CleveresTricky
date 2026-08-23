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
  /features\.has\("identityRefresh"\) && features\.optBoolean\("identityRefresh", false\)/,
  'profile Auto Identity must require an explicit profile opt-in',
);
assert.match(
  policyOwner,
  /hasApplicationScope\(profile\)/,
  'non-active profile Auto Identity must require assigned applications',
);
assert.match(
  cronOwner,
  /if \(!decision\.globalLiveApply\) \{[\s\S]*?global Build properties were left unchanged[\s\S]*?return/,
  'profile-only Auto Identity must persist refreshed data without device-wide live apply',
);

console.log('Profile Auto Identity remains app-scoped while global Identity Refresh keeps its boot-only semantics.');
