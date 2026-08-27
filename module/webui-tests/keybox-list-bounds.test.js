const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const start = source.indexOf('async function loadKeyboxes(options = {})');
const end = source.indexOf('function renderKeyboxes()', start);
assert.ok(start >= 0 && end > start, 'loadKeyboxes implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /Array\.from\(new Set\(data\.filter/);
assert.match(implementation, /value\.length <= 256/);
assert.match(implementation, /slice\(0, 4096\)/);
assert.match(implementation, /Array\.isArray\(data\)/);

const list = { innerHTML: '' };
let callCount = 0;
let renderCount = 0;
const context = {
  console,
  AbortController,
  document: { getElementById(id) { return id === 'storedKeyboxesList' ? list : null; } },
  fetchAuth(path, options) {
    callCount += 1;
    assert.equal(path, '/api/keyboxes');
    assert.ok(options.signal);
    if (callCount === 1) {
      return Promise.resolve({
        ok: true,
        async json() { return ['short', 'short', 'x'.repeat(257), 7, 'another']; },
        async text() { return ''; }
      });
    }
    return Promise.resolve({ ok: true, async json() { return { malformed: true }; }, async text() { return ''; } });
  },
  renderKeyboxes() { renderCount += 1; },
  setupAutocomplete() {}
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let keyboxListController = null;
  let cachedKeyboxes = [];
  ${implementation}
  this.loadKeyboxes = loadKeyboxes;
  this.currentKeyboxes = () => cachedKeyboxes;
`, context, { filename: 'index.html#loadKeyboxes' });

(async () => {
  await context.loadKeyboxes();
  assert.deepEqual(Array.from(context.currentKeyboxes()), ['short', 'another'], 'keybox response must be type-, length- and duplicate-bounded');
  assert.equal(renderCount, 1);
  await context.loadKeyboxes();
  assert.deepEqual(Array.from(context.currentKeyboxes()), [], 'malformed non-array keybox response must fail closed');
  assert.equal(renderCount, 2);
  console.log('Keybox list bounds and malformed-response regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
