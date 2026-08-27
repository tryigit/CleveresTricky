const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/policy.js', 'utf8');
const loadStart = source.indexOf('async function loadReferenceData()');
const loadEnd = source.indexOf('\nfunction renderAll()', loadStart);
assert.ok(loadStart >= 0 && loadEnd > loadStart, 'policy reference loader is missing');
const loader = source.slice(loadStart, loadEnd);
assert.match(loader, /referenceDataController\.abort\(\)/);
assert.match(loader, /request\('\/api\/packages', requestOptions\)/);
assert.match(loader, /request\('\/api\/keyboxes', requestOptions\)/);
assert.match(loader, /request\('\/api\/config', requestOptions\)/);
assert.match(loader, /MAX_REFERENCE_PACKAGES/);
assert.match(loader, /MAX_REFERENCE_KEYBOXES/);
assert.match(loader, /MAX_REFERENCE_TEMPLATES/);
assert.match(loader, /Array\.from\(new Set/);
assert.match(loader, /typeof item === 'string'/);

const normalizeStart = source.indexOf('function normalizedPackageNames()');
const normalizeEnd = source.indexOf('\nfunction installPackagePicker', normalizeStart);
assert.ok(normalizeStart >= 0 && normalizeEnd > normalizeStart, 'package normalization helper is missing');
const normalizer = source.slice(normalizeStart, normalizeEnd);
assert.match(normalizer, /MAX_REFERENCE_PACKAGES/);
assert.match(normalizer, /value\.length <= 255/);
assert.match(normalizer, /A-Za-z0-9_\./);

let pendingFirst = [];
const calls = [];
const context = {
  console,
  AbortController,
  bridge: { listPackages() { return []; } },
  request(path, options) {
    assert.ok(options && options.signal, 'reference requests must carry an AbortSignal');
    calls.push({ path, signal: options.signal });
    if (calls.length <= 3) {
      return new Promise(resolve => pendingFirst.push({ path, resolve }));
    }
    if (path === '/api/packages') return Promise.resolve(Array.from({ length: 11000 }, (_, index) => index === 0 ? '<bad>' : `com.example.app${index}`));
    if (path === '/api/keyboxes') return Promise.resolve(Array.from({ length: 5000 }, (_, index) => index === 0 ? '<bad>' : `box-${index}.xml`));
    if (path === '/api/config') return Promise.resolve({ templates: Array.from({ length: 500 }, (_, index) => `template-${index}`) });
    throw new Error(`unexpected path: ${path}`);
  }
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  const MAX_REFERENCE_PACKAGES = 10000;
  const MAX_REFERENCE_KEYBOXES = 4096;
  const MAX_REFERENCE_TEMPLATES = 256;
  let referenceDataController = null;
  let packages = [];
  let keyboxes = [];
  let templates = [];
  ${loader}
  ${normalizer}
  this.loadReferenceData = loadReferenceData;
  this.current = () => ({ packages, keyboxes, templates, normalized: normalizedPackageNames() });
`, context, { filename: 'policy.js#reference-bounds' });

(async () => {
  const first = context.loadReferenceData();
  await new Promise(resolve => setImmediate(resolve));
  const second = context.loadReferenceData();
  const secondState = await second.then(() => context.current());
  assert.equal(calls.length, 6, 'replacement reference load must issue one replacement request per endpoint');
  assert.equal(calls.slice(0, 3).every(call => call.signal.aborted), true, 'stale reference requests must be aborted');
  assert.equal(calls.slice(3).every(call => !call.signal.aborted), true, 'replacement reference requests must remain active');
  assert.equal(secondState.packages.length, 10000, 'package references must be capped');
  assert.equal(secondState.keyboxes.length, 4096, 'keybox references must be capped');
  assert.equal(secondState.templates.length, 256, 'template references must be capped');
  assert.equal(secondState.normalized.includes('<bad>'), false, 'invalid package names must not reach package picker suggestions');
  assert.equal(secondState.normalized.length, 9999, 'valid package references should remain available under the cap');

  pendingFirst.forEach(({ path, resolve }) => {
    if (path === '/api/packages') resolve(['stale.package']);
    else if (path === '/api/keyboxes') resolve(['stale.xml']);
    else resolve({ templates: ['stale-template'] });
  });
  await first;
  const finalState = context.current();
  assert.equal(finalState.packages.length, 10000, 'delayed stale package response must not overwrite newest state');
  assert.equal(finalState.packages[1], 'com.example.app1');
  assert.equal(finalState.keyboxes[1], 'box-1.xml');
  assert.equal(finalState.templates[0], 'template-0');
  console.log('Policy reference-data bounds and request-owner regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
