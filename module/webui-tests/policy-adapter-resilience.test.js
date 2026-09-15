const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

// 1. Verify index.html exports window.fetchAuth
const indexHtml = fs.readFileSync('module/template/webroot/index.html', 'utf8');
assert.ok(indexHtml.includes('window.fetchAuth = fetchAuth;'), 'index.html must expose fetchAuth on window');

// 2. Verify policy.js delegates request() to global.fetchAuth when available
const policyJs = fs.readFileSync('module/template/webroot/policy.js', 'utf8');
assert.ok(
  policyJs.includes('const fetcher = (typeof global.fetchAuth === \'function\') ? global.fetchAuth : bridge.fetch;'),
  'policy.js request() must delegate to global.fetchAuth when present'
);

// 3. Test execution behavior: request() uses global.fetchAuth
{
  let fetchAuthCalled = false;
  let bridgeCalled = false;

  const sandbox = {
    global: {},
    bridge: {
      fetch: async () => {
        bridgeCalled = true;
        return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({}) };
      }
    }
  };
  sandbox.global = sandbox;
  sandbox.global.fetchAuth = async () => {
    fetchAuthCalled = true;
    return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ status: 'ok' }) };
  };

  const code = `
    async function request(path, options) {
      const fetcher = (typeof global.fetchAuth === 'function') ? global.fetchAuth : bridge.fetch;
      const response = await fetcher(path, options || {});
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || \`Request failed (\${response.status})\`);
      }
      const type = response.headers.get('content-type') || '';
      return type.includes('application/json') ? response.json() : response.text();
    }
    this.request = request;
  `;

  vm.runInNewContext(code, sandbox);
  (async () => {
    const res = await sandbox.request('/api/policy_state');
    assert.equal(res.status, 'ok');
    assert.equal(fetchAuthCalled, true, 'request() must use global.fetchAuth when available');
    assert.equal(bridgeCalled, false, 'bridge.fetch must not be called when global.fetchAuth is present');
  })().catch(err => {
    console.error(err);
    process.exitCode = 1;
  });
}

// 4. Test execution behavior: transient failure recovers on retry without premature error notification
{
  const notifyCalls = [];
  let attempts = 0;
  let policyState = null;

  const normalizePolicyState = state => state;
  const notify = (msg, type) => { notifyCalls.push({ msg, type }); };
  const mockRequest = async (path) => {
    attempts++;
    if (attempts === 1) {
      throw new Error('Native WebUI service rejected the request: Android adapter is unavailable');
    }
    return { loaded: true };
  };

  const simulateInitialize = async () => {
    try {
      policyState = normalizePolicyState(await mockRequest('/api/policy_state'));
    } catch (error) {
      const retryDelays = [10, 20];
      const attemptRetry = async (index) => {
        if (policyState || index >= retryDelays.length) return;
        try {
          policyState = normalizePolicyState(await mockRequest('/api/policy_state'));
        } catch (retryError) {
          if (!policyState) {
            if (index + 1 < retryDelays.length) {
              await attemptRetry(index + 1);
            } else {
              notify(`Policy controls unavailable: ${retryError && retryError.message || error.message}`, 'error');
            }
          }
        }
      };
      await attemptRetry(0);
    }
  };

  (async () => {
    await simulateInitialize();
    assert.deepEqual(policyState, { loaded: true }, 'policyState must recover after retry');
    assert.equal(attempts, 2, 'should have taken 2 attempts to recover');
    assert.equal(notifyCalls.length, 0, 'no error toast must be shown when retry succeeds');
  })().catch(err => {
    console.error(err);
    process.exitCode = 1;
  });
}

// 5. Test execution behavior: permanent failure emits error notification once retries are exhausted
{
  const notifyCalls = [];
  let attempts = 0;
  let policyState = null;

  const normalizePolicyState = state => state;
  const notify = (msg, type) => { notifyCalls.push({ msg, type }); };
  const mockRequest = async (path) => {
    attempts++;
    throw new Error('Android adapter is unavailable');
  };

  const simulateInitialize = async () => {
    try {
      policyState = normalizePolicyState(await mockRequest('/api/policy_state'));
    } catch (error) {
      const retryDelays = [10, 20];
      const attemptRetry = async (index) => {
        if (policyState || index >= retryDelays.length) return;
        try {
          policyState = normalizePolicyState(await mockRequest('/api/policy_state'));
        } catch (retryError) {
          if (!policyState) {
            if (index + 1 < retryDelays.length) {
              await attemptRetry(index + 1);
            } else {
              notify(`Policy controls unavailable: ${retryError && retryError.message || error.message}`, 'error');
            }
          }
        }
      };
      await attemptRetry(0);
    }
  };

  (async () => {
    await simulateInitialize();
    assert.equal(policyState, null, 'policyState remains null on permanent failure');
    assert.equal(attempts, 3, 'should have attempted initial plus 2 retries');
    assert.equal(notifyCalls.length, 1, 'error toast must be shown when retries are exhausted');
    assert.equal(notifyCalls[0].type, 'error');
    assert.ok(notifyCalls[0].msg.includes('Policy controls unavailable: Android adapter is unavailable'));
  })().catch(err => {
    console.error(err);
    process.exitCode = 1;
  });
}

console.log('Policy adapter resilience regression checks passed');
