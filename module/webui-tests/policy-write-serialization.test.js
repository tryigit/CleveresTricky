const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/policy.js', 'utf8');
const start = source.indexOf('async function performLegacyToggle');
const end = source.indexOf('function installFeatureCenter()', start);
assert.ok(start >= 0 && end > start, 'legacy toggle implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /legacyToggleQueue\.catch\(\(\) => \{\}\)\.then/);
assert.match(implementation, /legacyToggleQueue = operation\.catch\(\(\) => \{\}\)/);
assert.match(source, /if \(this\.disabled\) return;/, 'custom-template save must reject duplicate clicks while pending');
assert.match(source, /if \(saveButton\.disabled\) return;/, 'kernel identity save must reject duplicate clicks while pending');

const calls = [];
let releaseFirst;
const context = {
  console,
  URLSearchParams,
  request(path, options) {
    const setting = options.body.get('setting');
    const enabled = options.body.get('value');
    calls.push({ path, setting, enabled });
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseFirst = () => resolve({ ok: true });
      });
    }
    return Promise.resolve({ ok: true });
  },
  loadLegacyConfig: async () => {},
  renderFeatureCenter() {},
  renderIdentityControls() {},
  refreshPresentation() {},
  notify() {}
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let legacyToggleQueue = Promise.resolve();
  ${implementation}
  this.setLegacyToggle = setLegacyToggle;
`, context, { filename: 'policy.js#legacy-toggle-queue' });

(async () => {
  const first = context.setLegacyToggle('global_mode', true);
  const second = context.setLegacyToggle('global_mode', false);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 1, 'the second legacy toggle must wait for the first POST');
  assert.equal(calls[0].enabled, 'true');
  releaseFirst();
  await Promise.all([first, second]);
  assert.equal(calls.length, 2, 'the queued legacy toggle must eventually execute');
  assert.equal(calls[1].enabled, 'false', 'legacy toggles must execute in user order');
  console.log('Policy legacy-toggle serialization regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
