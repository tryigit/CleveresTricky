const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('// Source-aware Stored Keyboxes and Verification UX.');
const end = source.indexOf('})(window);', start);
assert.ok(start >= 0 && end > start, 'KeyboxHub UX IIFE is missing');
const marker = '    function install() {';
const markerIndex = source.indexOf(marker, start);
assert.ok(markerIndex > start && markerIndex < end, 'KeyboxHub install marker is missing');
const keyboxSource = source.slice(start, markerIndex)
  + "    global.__testRefreshInventory = refreshInventory; global.__testInventory = () => inventory;\n"
  + source.slice(markerIndex, end + '})(window);'.length);

assert.match(keyboxSource, /const previousController = inventoryController/);
assert.match(keyboxSource, /previousController\.abort\(\)/);
assert.match(keyboxSource, /global\.fetchAuth\('\/api\/keybox_inventory', requestOptions\)/);
assert.match(keyboxSource, /if \(controller\.signal\.aborted\) return;/);
assert.match(keyboxSource, /if \(inventoryController !== controller\) return;/);

let releaseFirst;
const calls = [];
function response(items) {
  return { ok: true, async json() { return items; }, async text() { return ''; } };
}
const context = {
  console,
  AbortController,
  document: {
    readyState: 'loading',
    getElementById() { return null; },
    addEventListener() {}
  },
  fetchAuth(path, options) {
    calls.push({ path, signal: options.signal });
    if (calls.length === 1) return new Promise(resolve => { releaseFirst = () => resolve(response([{ id: 'stale', filename: 'stale.xml', scope: 'managed' }])); });
    return Promise.resolve(response([{ id: 'newest', filename: 'newest.xml', scope: 'managed' }]));
  },
  notify() {}
};
context.window = context;
vm.createContext(context);
vm.runInContext(keyboxSource, context, { filename: 'ux.js#refreshInventory' });

(async () => {
  const first = context.__testRefreshInventory();
  await Promise.resolve();
  const second = context.__testRefreshInventory();
  await second;
  assert.equal(calls.length, 2, 'a replacement inventory load should start exactly one new request');
  assert.equal(calls[0].path, '/api/keybox_inventory');
  assert.equal(calls[0].signal.aborted, true, 'the stale inventory request must be aborted');
  assert.equal(calls[1].signal.aborted, false, 'the replacement inventory request must remain active');
  assert.deepEqual(JSON.parse(JSON.stringify(context.__testInventory())), [{ id: 'newest', filename: 'newest.xml', scope: 'managed', certificate_serial: '' }], 'the newest inventory response must win');

  releaseFirst();
  await first;
  assert.deepEqual(JSON.parse(JSON.stringify(context.__testInventory())), [{ id: 'newest', filename: 'newest.xml', scope: 'managed', certificate_serial: '' }], 'a delayed stale inventory response must not overwrite current data');
  console.log('KeyboxHub inventory request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
