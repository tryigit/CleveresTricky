const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const webroot = 'module/template/webroot';
const bridgeSource = fs.readFileSync(path.join(webroot, 'bridge.js'), 'utf8');
const uxSource = fs.readFileSync(path.join(webroot, 'ux.js'), 'utf8');
const policySource = fs.readFileSync(path.join(webroot, 'policy.js'), 'utf8');
const indexSource = fs.readFileSync(path.join(webroot, 'index.html'), 'utf8');

new vm.Script(uxSource, { filename: 'ux.js' });
new vm.Script(policySource, { filename: 'policy.js' });

const runtimeJs = fs.readdirSync(webroot).filter(name => name.endsWith('.js')).sort();
const runtimeCss = fs.readdirSync(webroot).filter(name => name.endsWith('.css')).sort();
assert.deepStrictEqual(runtimeJs, ['bridge.js', 'policy.js', 'ux.js'], 'WebUI runtime JS layout is fixed; extend an existing owner instead of adding another layer');
assert.deepStrictEqual(runtimeCss, [], 'WebUI must not grow standalone runtime CSS files without an intentional architecture redesign');
assert.ok(!fs.existsSync(path.join(webroot, 'ux-base.js')), 'ux-base.js was consolidated into ux.js and must not return');
assert.ok(!uxSource.includes('ux-patch.js'), 'The retired patch overlay must not be loaded');
assert.ok(!uxSource.includes('ux-base.js'), 'ux.js must be the real UX implementation, not another loader');
assert.ok(!/setInterval\s*\(/.test(uxSource), 'UX presentation must not add permanent polling');
assert.ok(!/setInterval\s*\(/.test(policySource), 'Policy UI must not add permanent polling');

assert.match(uxSource, /\['en', 'English'\]/);
assert.match(uxSource, /\['tr', 'Türkçe'\]/);
assert.match(uxSource, /ct_language_panel/);
assert.match(uxSource, /Open Telegram Community/);
assert.match(uxSource, /const DIAGNOSTIC_FIELDS = Object\.freeze/);
assert.match(uxSource, /function formatDiagnosticsSnapshot\(data\)/);
assert.match(uxSource, /bridge\.fetch\('\/api\/resource_usage'\)/);
assert.match(uxSource, /Copy a bounded support snapshot without logs, package names, keybox names, identity values, credentials, or key material\./);
const diagnosticsFormatter = uxSource.slice(
    uxSource.indexOf('const DIAGNOSTIC_FIELDS'),
    uxSource.indexOf('async function copyDiagnosticsSnapshot')
);
assert.match(diagnosticsFormatter, /native_failure: runtime\.failure/);
assert.match(diagnosticsFormatter, /schema=2/);
['pid', 'entry', 'timestamp_ms', 'package_name', 'keybox_name', 'filename', 'token', 'auth_data'].forEach(field => {
    assert.ok(!new RegExp(`['"]${field}['"]`).test(diagnosticsFormatter), `Diagnostic snapshot must not expose ${field}`);
});
assert.ok(!diagnosticsFormatter.includes('JSON.stringify'), 'Diagnostic snapshot must use its fixed allowlist');

assert.match(policySource, /id=\"keyboxStatus\"/);
assert.match(policySource, /class=\"ct-switch\"/);
assert.match(policySource, /DRM App Passthrough/);
assert.match(policySource, /DRM Identifier Privacy/);
assert.match(policySource, /ct_effective_apps_host/);
assert.match(policySource, /installPackagePickers/);
assert.match(policySource, /slice\(0,24\)/);
assert.match(policySource, /Estimated impact:/);
assert.match(policySource, /CPU very low per UID decision; RAM low with a bounded UID cache\./);
assert.match(policySource, /function installTabNavigationOwner\(\)/);
assert.match(policySource, /event\.stopImmediatePropagation\(\)/);
assert.ok(!policySource.includes('bindCommunityExternally'), 'Policy must not own the community link');
assert.ok(!bridgeSource.includes('installIdentityPolicyTransitionWatcher'), 'Policy saves already reconcile live Identity transitions; bridge must not apply them a second time from change events');
assert.ok(!policySource.includes('watchCommunityBriefly'), 'Policy must not poll/watch for the community card');
assert.ok(!policySource.includes('ctPolicyExternal'), 'Policy must not attach a second community click handler');
assert.ok(!policySource.includes('global.switchTab = wrapped'), 'Policy must not monkey-patch the global tab router');
assert.match(policySource, /cleverestricky-profiles\.json/);
assert.ok(!/Profiles\s+v2/i.test(policySource), 'Profiles v2 wording must not return');
assert.ok(!policySource.includes("makeTab('effective','Effective State','profiles')"));
assert.ok(!policySource.includes("['ct_dashboard_controls','ct_resources_controls']"));
assert.match(policySource, /statusCell\.replaceChildren\(\)/);
assert.match(policySource, /removeLegacySurfaces\(\)/);
assert.match(policySource, /retireLegacyLocalization\(\)/);

assert.ok(!indexSource.includes('One-Click Reset (Refresh Environment)'));
assert.match(indexSource, /Synchronize Runtime/);
assert.ok(!indexSource.includes('<h3>System Control</h3>'));
assert.match(indexSource, /policy\.js\?revision=5/);
assert.match(indexSource, /bridge\.js\?revision=14/);
assert.match(policySource, /request\('\/api\/packages'\)/);
assert.match(policySource, /bridge\.listPackages\(\)/);
assert.match(policySource, /function refreshPresentation\(\)/);
assert.match(policySource, /ct_language_selector/);
assert.ok(!policySource.includes('ct_community_slot'), 'Policy must not create a duplicate community slot');
assert.ok(!indexSource.includes('<h3>Identity Controls</h3>'), 'Retired Identity Controls panel must stay removed');
assert.ok(!indexSource.includes('class=\"toggle\"'), 'Legacy toggle class must stay removed from static WebUI markup');
assert.match(indexSource, /class=\"ct-switch\" id=\"srvAutoRefresh\"/);
assert.match(policySource, /ct_keybox_status_panel/);
assert.match(policySource, /ct_restore_defaults/);
assert.match(policySource, /body\.set\('profile','default'\)/);
assert.ok(!policySource.includes("request('/api/reset_environment'"), 'Restore Defaults must not use destructive environment reset');

require('./bridge-base.test.js');
require('./localization.test.js');
require('./identity-security-patch-layout.test.js');
require('./log-responsive.test.js');
