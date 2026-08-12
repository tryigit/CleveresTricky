const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const loaderSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const policySource = fs.readFileSync('module/template/webroot/policy.js', 'utf8');
const indexSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');

new vm.Script(loaderSource, { filename: 'ux.js' });
new vm.Script(policySource, { filename: 'policy.js' });

assert.match(loaderSource, /ux-base\.js\?revision=4/);
assert.ok(!loaderSource.includes('ux-patch.js'), 'The retired patch overlay must not be loaded');
assert.ok(!/setInterval\s*\(/.test(loaderSource), 'WebUI loader must not add permanent polling');
assert.ok(!/setInterval\s*\(/.test(policySource), 'Policy UI must not add permanent polling');

assert.match(policySource, /id=\"keyboxStatus\"/);
assert.match(policySource, /class=\"ct-switch\"/);
assert.match(policySource, /DRM App Passthrough/);
assert.match(policySource, /DRM Identifier Privacy/);
assert.match(policySource, /ct_effective_apps_host/);
assert.match(policySource, /installPackagePickers/);
assert.match(policySource, /slice\(0,24\)/);
assert.match(policySource, /Estimated impact:/);
assert.match(policySource, /CPU very low per UID decision; RAM low with a bounded UID cache\./);
assert.match(policySource, /event\.stopImmediatePropagation\(\)/);
assert.match(policySource, /bridge\.openCommunity\(\)/);
assert.match(policySource, /cleverestricky-profiles\.json/);
assert.ok(!/Profiles\s+v2/i.test(policySource), 'Profiles v2 wording must not return');
assert.ok(!policySource.includes("makeTab('effective','Effective State','profiles')"));
assert.ok(!policySource.includes("['ct_dashboard_controls','ct_resources_controls']"));
assert.match(policySource, /statusCell\.replaceChildren\(\)/);
assert.match(policySource, /removeLegacySurfaces\(\)/);

assert.ok(!indexSource.includes('One-Click Reset (Refresh Environment)'));
assert.match(indexSource, /Synchronize Runtime/);
assert.ok(!indexSource.includes('<h3>System Control</h3>'));
assert.match(indexSource, /policy\.js\?revision=2/);

require('./bridge-base.test.js');
