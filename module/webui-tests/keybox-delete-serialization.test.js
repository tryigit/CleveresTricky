const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('    async function deleteOne(item)');
const end = source.indexOf('    async function reloadAll()', start);
assert.ok(start >= 0 && end > start, 'Keybox delete implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /deletingIds\.has\(item\.id\)/);
assert.match(implementation, /if \(bulkDeleteBusy\) return/);
assert.match(implementation, /bulkDeleteBusy = true/);
assert.match(implementation, /bulkDeleteBusy = false/);

const item = { id: 'one', filename: 'one.xml', scope: 'managed' };
const calls = [];
let releaseDelete;
const context = {
  console,
  URLSearchParams,
  confirm: () => true,
  notify() {},
  render() {},
  fetchAuth(path, options) {
    calls.push({ path, options });
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseDelete = () => resolve({ ok: true, async text() { return ''; }, clone() { return this; }, async json() { return { deleted: 1 }; } });
      });
    }
    return Promise.resolve({ ok: true, async text() { return ''; }, clone() { return this; }, async json() { return { deleted: 1 }; } });
  }
};
context.window = context;
context.global = context;
vm.createContext(context);
vm.runInContext(`
  let deletingIds = new Set();
  let bulkDeleteBusy = false;
  let keyboxMutationQueue = Promise.resolve();
  function enqueueKeyboxMutation(task) {
    const operation = keyboxMutationQueue.catch(() => {}).then(task);
    keyboxMutationQueue = operation.catch(() => {});
    return operation;
  }
  let selected = new Set();
  let inventory = [];
  async function reloadAll() {}
  function t(key) { return key; }
  ${implementation}
  this.deleteOne = deleteOne;
  this.bulkDelete = bulkDelete;
  this.setItem = value => { inventory = [value]; selected = new Set([value.id]); };
`, context, { filename: 'ux.js#keybox-delete' });

(async () => {
  const firstDelete = context.deleteOne(item);
  const duplicateDelete = context.deleteOne(item);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 1, 'duplicate single-delete clicks must issue only one request');
  releaseDelete();
  await Promise.all([firstDelete, duplicateDelete]);

  context.setItem(item);
  const firstBulk = context.bulkDelete();
  const duplicateBulk = context.bulkDelete();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 2, 'duplicate bulk-delete clicks must issue only one additional request');
  await Promise.all([firstBulk, duplicateBulk]);
  assert.equal(calls[1].path, '/api/delete_keyboxes');
  console.log('Keybox delete serialization regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
