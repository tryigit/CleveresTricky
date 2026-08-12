const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const loaderSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const patchSource = fs.readFileSync('module/template/webroot/ux-patch.js', 'utf8');

new vm.Script(loaderSource, { filename: 'ux.js' });
new vm.Script(patchSource, { filename: 'ux-patch.js' });

assert.match(loaderSource, /ux-base\.js\?revision=1/);
assert.match(loaderSource, /ux-patch\.js\?revision=1/);
assert.match(patchSource, /ct_dash_drm_passthrough/);
assert.match(patchSource, /ct_drm_feature_children/);
assert.match(patchSource, /ct_resources_controls/);
assert.match(patchSource, /tab_effective/);
assert.match(patchSource, /removeAttribute\('list'\)/);
assert.match(patchSource, /slice\(0,24\)/);
assert.match(patchSource, /Synchronize Runtime/);
assert.match(patchSource, /bridge\.fetch\('\/api\/reload'/);
assert.match(patchSource, /body\.set\('value'/);
assert.ok(!patchSource.includes("bridge.fetch('/api/reset_environment'"), 'Runtime sync must not invoke destructive environment reset');
assert.match(patchSource, /Backup Password \(required, at least 12 characters\)/);
assert.match(patchSource, /Language & Localization/);
assert.match(patchSource, /Estimated impact:/);
assert.match(patchSource, /CPU low per matching call; RAM low and bounded\./);
assert.ok(!/setInterval\s*\(/.test(patchSource), 'Runtime UX patch must not add permanent polling');

require('./bridge-base.test.js');
