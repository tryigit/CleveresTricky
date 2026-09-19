'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const scopeStart = source.indexOf('    function normalizeKeyboxScope(value) {');
assert.ok(scopeStart >= 0, 'Keybox scope normalizer is missing');
const refreshStart = source.indexOf('    async function refreshInventory(options = {})');
const enqueueStart = source.indexOf('    function enqueueKeyboxMutation(task)', refreshStart);
assert.ok(refreshStart >= 0 && enqueueStart > refreshStart, 'Keybox inventory refresh implementation is missing');
const toggleStart = source.indexOf('    async function toggleDisabled(item)', enqueueStart);
const toggleEnd = source.indexOf('    async function bulkDelete()', toggleStart);
assert.ok(toggleStart > enqueueStart && toggleEnd > toggleStart, 'Keybox disable toggle implementation is missing');

const scopeEnd = source.indexOf('    async function refreshInventory(options = {})', scopeStart);
assert.ok(scopeEnd > scopeStart, 'Keybox scope normalizer boundary is missing');
const scopeImplementation = source.slice(scopeStart, scopeEnd);
const refreshImplementation = source.slice(refreshStart, enqueueStart);
const toggleImplementation = source.slice(toggleStart, toggleEnd);

assert.match(toggleImplementation, /togglingIds\.has\(item\.id\)/);
assert.match(toggleImplementation, /'\/api\/toggle_keybox_disabled'/);
assert.match(toggleImplementation, /body\.set\('disabled', target \? 'true' : 'false'\)/);
assert.match(refreshImplementation, /disabled: item\?\.disabled === true/);

const item = { id: 'keyboxes:one.xml', filename: 'one.xml', scope: 'keyboxes', disabled: false };
const calls = [];
const notifications = [];
let releaseToggle;
let failNextToggle = false;
let refreshedWithDisabled = null;

const context = {
  console,
  URLSearchParams,
  AbortController,
  notify(message, type) { notifications.push({ message, type }); },
  render() {},
  fetchAuth(path, options) {
    calls.push({ path, options });
    if (path === '/api/keybox_inventory') {
      return Promise.resolve({
        ok: true,
        async text() { return ''; },
        clone() { return this; },
        async json() {
          return [{
            id: 'keyboxes:one.xml',
            filename: 'one.xml',
            scope: 'keyboxes',
            certificate_serial: '',
            disabled: refreshedWithDisabled
          }];
        }
      });
    }
    if (path === '/api/toggle_keybox_disabled') {
      if (failNextToggle) {
        return Promise.resolve({ ok: false, async text() { return 'denied'; }, clone() { return this; } });
      }
      return new Promise(resolve => {
        releaseToggle = () => resolve({
          ok: true,
          async text() { return ''; },
          clone() { return this; },
          async json() { return { status: 'ok', disabled: true }; }
        });
      });
    }
    return Promise.resolve({ ok: true, async text() { return ''; }, clone() { return this; }, async json() { return []; } });
  }
};
context.window = context;
context.global = context;
vm.createContext(context);
vm.runInContext(`
  let deletingIds = new Set();
  const togglingIds = new Set();
  let bulkDeleteBusy = false;
  let keyboxMutationQueue = Promise.resolve();
  let inventoryController = null;
  let loading = false;
  let inventory = [];
  function statusLabel() {}
  function enqueueKeyboxMutation(task) {
    const operation = keyboxMutationQueue.catch(() => {}).then(task);
    keyboxMutationQueue = operation.catch(() => {});
    return operation;
  }
  let selected = new Set();
  function t(key) { return key; }
  ${scopeImplementation}
  ${refreshImplementation}
  ${toggleImplementation}
  this.toggleDisabled = toggleDisabled;
  this.refreshInventory = refreshInventory;
  this.getInventory = () => inventory;
`, context, { filename: 'ux.js#keybox-toggle' });

process.on('unhandledRejection', error => {
  console.error(error);
  process.exitCode = 1;
});

(async () => {
  // Double-click must serialize to a single disable request.
  const first = context.toggleDisabled(item);
  const duplicate = context.toggleDisabled(item);
  await new Promise(resolve => setImmediate(resolve));
  const toggleCalls = () => calls.filter(call => call.path === '/api/toggle_keybox_disabled');
  assert.equal(toggleCalls().length, 1, 'duplicate toggle clicks must issue only one request');

  const body = toggleCalls()[0].options.body;
  assert.equal(body.get('filename'), 'one.xml');
  assert.equal(body.get('scope'), 'keyboxes');
  assert.equal(body.get('disabled'), 'true', 'an enabled keybox must request disabled=true');
  assert.ok(notifications.some(entry => entry.type === 'working'), 'toggle must announce progress');

  releaseToggle();
  await Promise.all([first, duplicate]);
  assert.equal(notifications.filter(entry => entry.type === 'error').length, 0,
    'a successful toggle must not report an error');

  // A successful toggle must be reflected by a canonical inventory refresh.
  refreshedWithDisabled = true;
  await context.refreshInventory();
  assert.equal(context.getInventory()[0].disabled, true, 'inventory must carry the canonical disabled flag');

  // A failing backend toggle must surface the error and must not fake success.
  notifications.length = 0;
  failNextToggle = true;
  await context.toggleDisabled(context.getInventory()[0]);
  assert.ok(
    notifications.some(entry => entry.type === 'error' && String(entry.message).includes('denied')),
    'a failed toggle must surface the backend error',
  );
  assert.equal(toggleCalls().length, 2, 'failed toggle must not be retried silently');

  // Enabling a previously disabled keybox must request disabled=false.
  failNextToggle = false;
  const enable = context.toggleDisabled(context.getInventory()[0]);
  await new Promise(resolve => setImmediate(resolve));
  releaseToggle();
  await enable;
  assert.equal(toggleCalls().length, 3);
  assert.equal(toggleCalls()[2].options.body.get('disabled'), 'false',
    'a disabled keybox must request disabled=false');

  console.log('Keybox disable toggle regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
