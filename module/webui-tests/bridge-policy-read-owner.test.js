const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const start = source.indexOf('    async function readPolicyState()');
const end = source.indexOf('    function setProfileEnabled', start);
assert.ok(start >= 0 && end > start, 'bridge policy-state reader is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /if \(policyStateRequest\) return policyStateRequest/);
assert.match(implementation, /policyStateRequest = request/);
assert.match(implementation, /policyStateRequest === request/);

let releaseFirst;
const calls = [];
const state = { features: { buildIdentity: true }, profiles: [] };
const context = {
  console,
  nativeFetch() {
    calls.push(true);
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseFirst = () => resolve({ ok: true, async json() { return state; }, async text() { return ''; } });
      });
    }
    return Promise.resolve({ ok: true, async json() { return { ...state, second: true }; }, async text() { return ''; } });
  },
  rememberPolicyState(value) { context.remembered = value; }
};
vm.createContext(context);
vm.runInContext(`
  let policyStateRequest = null;
  ${implementation}
  this.read = readPolicyState;
`, context, { filename: 'bridge.js#policy-read-owner' });

(async () => {
  const first = context.read();
  await new Promise(resolve => setImmediate(resolve));
  const second = context.read();
  assert.equal(calls.length, 1, 'concurrent policy-state reads must share one native request');
  releaseFirst();
  const [firstState, secondState] = await Promise.all([first, second]);
  assert.equal(firstState, secondState, 'coalesced readers must receive the same state object');
  assert.equal(calls.length, 1);
  const thirdState = await context.read();
  assert.equal(calls.length, 2, 'a later read after settlement must be allowed to refresh state');
  assert.equal(thirdState.second, true);
  console.log('Bridge policy-state request coalescing regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
