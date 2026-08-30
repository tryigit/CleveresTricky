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
  uxSource,
  /#ct_debug_panel \.row \{[^}]*flex-direction:\s*row\s*!important/s,
  'Debug logging panel row must retain horizontal flex layout',
);
assert.match(
  uxSource,
  /#ct_debug_panel \.row > input\[type="checkbox"\]\s*\{[^}]*flex:\s*0 0 48px\s*!important/s,
  'Debug logging switch must retain fixed width dimensions',
);
assert.match(
  indexSource,
  /<div class="panel">\s*<h3>Verification<\/h3>\s*<div class="row ct-verify-header"/s,
  'Verification panel header must be a direct child of panel',
);
assert.match(
  indexSource,
  /<div class="success-icon">&#10003;<\/div>/,
  'Dynamic island success icon must use a checkmark symbol',
);
assert.doesNotMatch(
  indexSource,
  /<div class="success-icon">OK<\/div>/,
  'Dynamic island success icon must not use text OK',
);
assert.doesNotMatch(
  indexSource,
  /#tab_donate\s*\{[^}]*background:\s*transparent\s*!important/s,
  'Donate tab must not force transparent background',
);
assert.match(
  indexSource,
  /select\s*\{[^}]*appearance:\s*none/s,
  'select elements must use appearance: none with custom chevron arrow',
);
assert.match(
  indexSource,
  /select\s*\{[^}]*background-position:\s*right 14px center/s,
  'select elements must position down chevron with right margin',
);
assert.doesNotMatch(
  indexSource,
  /<div class="status-grid"/,
  'Dashboard must not contain obsolete status-grid',
);
assert.match(
  indexSource,
  /::selection\s*\{[^}]*background-color:\s*rgba\(10,\s*132,\s*255/s,
  'theme selection styling must use accent color',
);
assert.match(
  indexSource,
  /button\s*\{[^}]*text-align:\s*center/s,
  'buttons must center text alignment',
);
assert.match(
  uxSource,
  /#ct_diagnostics_copy\s*\{[^}]*text-align:\s*center/s,
  'diagnostics copy button must explicitly center text',
);
assert.match(
  uxSource,
  /#storedKeyboxesList\s+\.ct-keybox-item[^}]*flex-wrap:\s*nowrap/s,
  'stored keyboxes rows must prevent vertical wrap squeezing',
);
assert.match(
  uxSource,
  /#storedKeyboxesList\s+\.ct-keybox-item\s*>\s*button[^}]*width:\s*auto/s,
  'stored keyboxes delete button must not expand to full width',
);
assert.match(
  uxSource,
  /#ct_diagnostics_copy\s*\{[^}]*justify-content:\s*center/s,
  'diagnostics copy button must justify-content center',
);
assert.doesNotMatch(
  uxSource,
  /#ct_diagnostics_panel \.row > \*,/s,
  'diagnostics row children must not have padding-right stripped',
);
assert.match(
  indexSource,
  /function dismissLoadingOverlay\(\)/,
  'index.html must define dismissLoadingOverlay helper',
);
assert.doesNotMatch(
  indexSource,
  /document\.getElementById\(['"]status_global['"]\)/,
  'loading screen must not poll on obsolete status_global element',
);

console.log('All-pages responsive and accessible-control regression checks passed');
