'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const indexSource = fs.readFileSync(
  path.join(__dirname, '..', 'template', 'webroot', 'index.html'),
  'utf8',
);
const policySource = fs.readFileSync(
  path.join(__dirname, '..', 'template', 'webroot', 'policy.js'),
  'utf8',
);
const uxSource = fs.readFileSync(
  path.join(__dirname, '..', 'template', 'webroot', 'ux.js'),
  'utf8',
);

for (const id of ['srvContentPassword', 'srvContentPublicKey', 'kbFilenameInput']) {
  assert.match(
    indexSource,
    new RegExp(`<label[^>]+for=["']${id}["']`),
    `${id} must have an explicit accessible label`,
  );
}
for (const [id, label] of [
  ['ct_effective_package', 'Package to inspect'],
  ['ct_patch_package', 'Package to resolve'],
]) {
  assert.match(
    policySource,
    new RegExp(`<label for=["']${id}["']>${label}</label>`),
    `${id} must have an explicit generated-page label`,
  );
}

assert.match(
  policySource,
  /\.ct-help summary\{[^}]*min-height:44px/s,
  'generated help summaries must retain a mobile-sized touch target',
);
assert.match(
  policySource,
  /\.ct-chip button\{[^}]*min-width:44px[^}]*min-height:44px/s,
  'profile chip remove actions must retain a mobile-sized touch target',
);
assert.doesNotMatch(
  policySource,
  /\.ct-chip-option\{/,
  'profile chip target styling must use the generated button selector',
);
assert.match(
  uxSource,
  /#ct_keyboxhub_hint \.ct-keyboxhub-action \{[^}]*min-height:44px/s,
  'KeyboxHub action must retain a mobile-sized touch target',
);
assert.match(
  uxSource,
  /row\.style\.cssText = '[^']*min-height:44px/s,
  'ZIP confirmation label row must provide a full-size clickable target',
);
assert.match(
  indexSource,
  /\.row \{[^}]*min-height: 48px/s,
  'switch rows must provide a mobile-sized activation lane around the visual switch',
);

console.log('All-pages responsive and accessible-control regression checks passed');
