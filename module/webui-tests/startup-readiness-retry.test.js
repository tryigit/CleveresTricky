const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const start = source.indexOf('        async function fetchAuth(url, options = {})');
const end = source.indexOf('        async function downloadBlob(blob, filename)', start);
assert.ok(start >= 0 && end > start, 'fetchAuth implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /Native WebUI bridge is unavailable/);

const startupMessage = 'Native WebUI runtime is starting; retry shortly';
let calls = 0;
const options = { method: 'GET' };
const startupResponse = {
  status: 503,
  ok: false,
  async text() { return startupMessage; },
  clone() { return this; }
};
const readyResponse = { status: 200, ok: true };
const context = {
  console,
  DOMException,
  setTimeout(callback) { callback(); return 1; },
  clearTimeout() {},
  getAuthUrl(path) { return path; },
  window: {
    CleveresBridge: {
      fetch(path, receivedOptions) {
        calls += 1;
        assert.equal(path, '/api/config');
        assert.equal(receivedOptions, options);
        return Promise.resolve(calls === 1 ? startupResponse : readyResponse);
      }
    }
  }
};
vm.createContext(context);
vm.runInContext(`${implementation}\nthis.fetchAuth = fetchAuth;`, context, { filename: 'index.html#startup-readiness' });

(async () => {
  const response = await context.fetchAuth('/api/config', options);
  assert.equal(response.status, 200, 'startup 503 must be retried until the bridge becomes ready');
  assert.equal(calls, 2, 'startup retry must be bounded and issue one replacement request');

  let normal503Calls = 0;
  context.window.CleveresBridge.fetch = () => {
    normal503Calls += 1;
    return Promise.resolve({
      status: 503,
      ok: false,
      async text() { return 'Rust backend unavailable'; },
      clone() { return this; }
    });
  };
  const normal503 = await context.fetchAuth('/api/config', options);
  assert.equal(normal503.status, 503, 'non-startup 503 must remain visible to the caller');
  assert.equal(normal503Calls, 1, 'non-startup 503 must not create a retry loop');

  let abortTimer;
  const abortController = new AbortController();
  context.setTimeout = callback => { abortTimer = callback; return 2; };
  context.window.CleveresBridge.fetch = () => Promise.resolve({
    status: 503,
    ok: false,
    async text() { return startupMessage; },
    clone() { return this; }
  });
  const aborted = context.fetchAuth('/api/config', { signal: abortController.signal });
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(abortTimer, 'startup retry must install a cancellable timer');
  abortController.abort();
  await assert.rejects(aborted, error => error && error.name === 'AbortError');
  console.log('Startup readiness retry regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
