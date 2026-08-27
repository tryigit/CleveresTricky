const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const start = source.indexOf('    async function performProfileEnabledMutation');
const end = source.indexOf('    function decorateProfileEnablement', start);
assert.ok(start >= 0 && end > start, 'profile mutation implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /profileMutationQueue\.catch/);
assert.match(implementation, /performProfileEnabledMutation\(profile, enabled\)/);

let releaseFirst;
const calls = [];
const context = {
  console,
  URLSearchParams,
  nativeFetch(path, options) {
    calls.push({ path, options });
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseFirst = () => resolve({ ok: true, async json() { return { profiles: [] }; }, async text() { return ''; } });
      });
    }
    return Promise.resolve({ ok: true, async json() { return { profiles: [], second: true }; }, async text() { return ''; } });
  },
  rememberPolicyState(value) { context.latest = value; }
};
vm.createContext(context);
vm.runInContext(`
  let profileMutationQueue = Promise.resolve();
  ${implementation}
  this.set = setProfileEnabled;
`, context, { filename: 'bridge.js#profile-mutation-queue' });

(async () => {
  const profileA = { name: 'Alpha', enabled: true };
  const profileB = { name: 'Beta', enabled: false };
  const first = context.set(profileA, false);
  const second = context.set(profileB, true);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 1, 'concurrent profile mutations must not overlap');
  assert.equal(calls[0].path, '/api/profile_v2');
  releaseFirst();
  await Promise.all([first, second]);
  assert.equal(calls.length, 2, 'queued profile mutation must run after the first settles');
  assert.equal(calls[1].path, '/api/profile_v2');
  assert.match(String(calls[0].options.body), /Alpha/);
  assert.match(String(calls[1].options.body), /Beta/);
  console.log('Bridge profile mutation serialization regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
