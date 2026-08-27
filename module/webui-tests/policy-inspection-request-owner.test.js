const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

function extract(startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `missing policy function: ${startMarker}`);
  return source.slice(start, end);
}

const patchSource = extract('async function inspectPatch()', 'function renderProfiles()');
const effectiveSource = extract('async function inspectEffective()', 'function parseSavedBuildIdentity');

assert.match(patchSource, /patchInspectionController\.abort\(\)/);
assert.match(patchSource, /request\([^\n]+, \{ signal: controller\.signal \}\)/);
assert.match(patchSource, /if \(controller\.signal\.aborted\) return;/);
assert.match(effectiveSource, /effectiveInspectionController\.abort\(\)/);
assert.match(effectiveSource, /request\([^\n]+, \{ signal: controller\.signal \}\)/);
assert.match(effectiveSource, /if \(controller\.signal\.aborted\) return;/);

function runInspection(functionSource, kind) {
  const input = { value: 'com.example.old' };
  const result = { innerHTML: '', textContent: '' };
  const calls = [];
  let releaseFirst;
  const context = {
    console,
    AbortController,
    PATCH_COMPONENTS: [['system', 'System'], ['vendor', 'Vendor'], ['boot', 'Boot']],
    document: {
      getElementById(id) {
        if (kind === 'patch' && id === 'ct_patch_package') return input;
        if (kind === 'patch' && id === 'ct_patch_result') return result;
        if (kind === 'effective' && id === 'ct_effective_package') return input;
        if (kind === 'effective' && id === 'ct_effective_result') return result;
        return null;
      }
    },
    bridge: {
      fetch(path, options) {
        calls.push({ path, signal: options.signal });
        if (calls.length === 1) {
          return new Promise(resolve => {
            releaseFirst = () => resolve({
              securityPatch: { system: { captured: 'stale', configured: 'stale', effective: 'stale' } },
              scope: 'stale',
              matchedProfile: 'stale'
            });
          });
        }
        return Promise.resolve(kind === 'patch'
          ? { securityPatch: { system: { captured: 'new', configured: 'new', effective: 'new' } } }
          : { scope: 'new', matchedProfile: 'new', matchedApplicationRule: 'new' });
      }
    }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(`
    let patchInspectionController = null;
    let effectiveInspectionController = null;
    function escapeHtml(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char])); }
    async function request(path, options) { return bridge.fetch(path, options || {}); }
    ${functionSource}
    this.inspect = ${kind === 'patch' ? 'inspectPatch' : 'inspectEffective'};
  `, context, { filename: `policy.js#${kind}` });
  return { context, input, result, calls, releaseFirst: () => { assert.ok(releaseFirst, `${kind} first response was not registered`); releaseFirst(); } };
}

(async () => {
  for (const kind of ['patch', 'effective']) {
    const test = runInspection(kind === 'patch' ? patchSource : effectiveSource, kind);
    const first = test.context.inspect();
    await Promise.resolve();
    test.input.value = 'com.example.new';
    const second = test.context.inspect();
    await second;
    assert.equal(test.calls.length, 2, `${kind} should start one replacement request`);
    assert.equal(test.calls[0].signal.aborted, true, `${kind} stale request must be aborted`);
    assert.match(test.result.innerHTML, /new|com\.example\.new/, `${kind} newest response must render`);
    test.releaseFirst();
    await first;
    assert.match(test.result.innerHTML, /new|com\.example\.new/, `${kind} stale response must not repaint the result`);
    assert.doesNotMatch(test.result.innerHTML, /stale/, `${kind} stale response content must not remain visible`);
  }
  console.log('Policy inspection request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
