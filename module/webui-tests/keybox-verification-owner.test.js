const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('    async function verify()');
const end = source.indexOf('    function cancelVerification()', start);
assert.ok(start >= 0 && end > start, 'verification implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /verificationController\.abort\(\)/);
assert.match(implementation, /signal: controller\.signal/);
assert.match(implementation, /data\.slice\(0, 4096\)/);
assert.match(implementation, /String\(item\?\.details \?\? ''\)\.slice\(0, 2048\)/);

let releaseFirst;
const calls = [];
let rendered = 0;
const context = {
  console,
  AbortController,
  document: { getElementById() { return null; } },
  ensureVerificationControls() {},
  t(key) { return key; },
  renderVerification() { rendered += 1; },
  fetchAuth(path, options) {
    calls.push({ path, signal: options.signal });
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseFirst = () => resolve({ ok: true, async json() { return [{ filename: 'stale.xml', status: 'VALID', details: 'stale' }]; }, async text() { return ''; } });
      });
    }
    return Promise.resolve({
      ok: true,
      async json() {
        return Array.from({ length: 5000 }, (_, index) => ({
          filename: index === 0 ? 'newest.xml' : `box-${index}.xml`,
          status: 'VALID',
          certificate_serial: 'serial',
          details: 'd'.repeat(3000)
        }));
      },
      async text() { return ''; }
    });
  }
};
context.window = context;
context.global = context;
vm.createContext(context);
vm.runInContext(`
  let verificationController = null;
  let verificationItems = [];
  let verificationPage = 7;
  let verificationQuery = 'old';
  ${implementation}
  this.verify = verify;
  this.currentItems = () => verificationItems;
  this.currentPage = () => verificationPage;
  this.currentQuery = () => verificationQuery;
`, context, { filename: 'ux.js#verify' });

(async () => {
  const first = context.verify();
  await new Promise(resolve => setImmediate(resolve));
  const second = context.verify();
  await second;
  assert.equal(calls.length, 2, 'replacement verification must start one new request');
  assert.equal(calls[0].signal.aborted, true, 'stale verification must be aborted');
  assert.equal(Array.from(context.currentItems()).length, 4096, 'verification results must be capped');
  assert.equal(context.currentItems()[0].filename, 'newest.xml');
  assert.equal(context.currentItems()[0].details.length, 2048, 'verification details must be bounded');
  assert.equal(context.currentPage(), 1);
  assert.equal(context.currentQuery(), '');
  assert.equal(rendered, 1, 'only the newest verification result may render');

  releaseFirst();
  await first;
  assert.equal(context.currentItems()[0].filename, 'newest.xml', 'delayed stale verification must not overwrite current results');
  assert.equal(rendered, 1);
  console.log('Keybox verification request-owner and bounds regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
