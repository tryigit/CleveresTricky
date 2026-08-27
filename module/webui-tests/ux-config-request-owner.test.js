const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('    async function requestConfig()');
const end = source.indexOf('    function syncCompatibilityToggles', start);
assert.ok(start >= 0 && end > start, 'UX requestConfig implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /compatibilityConfigController\.abort\(\)/);
assert.match(implementation, /bridge\.fetch\('\/api\/config', \{ signal: controller\.signal \}\)/);
assert.match(implementation, /if \(controller\.signal\.aborted\) return compatibilityConfig/);

let releaseFirst;
const calls = [];
const context = {
  console,
  AbortController,
  bridge: {
    fetch(path, options) {
      calls.push({ path, signal: options.signal });
      if (calls.length === 1) {
        return new Promise(resolve => {
          releaseFirst = () => resolve({ ok: true, async json() { return { drm_passthrough: true }; }, async text() { return ''; } });
        });
      }
      return Promise.resolve({ ok: true, async json() { return { drm_passthrough: false }; }, async text() { return ''; } });
    }
  }
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let compatibilityConfig = null;
  let compatibilityConfigController = null;
  ${implementation}
  this.requestConfig = requestConfig;
  this.currentConfig = () => compatibilityConfig;
`, context, { filename: 'ux.js#requestConfig' });

(async () => {
  const first = context.requestConfig();
  await new Promise(resolve => setImmediate(resolve));
  const second = context.requestConfig();
  const newest = await second;
  assert.equal(calls.length, 2, 'a replacement compatibility read should start one new request');
  assert.equal(calls[0].signal.aborted, true, 'the stale compatibility read must be aborted');
  assert.equal(newest.drm_passthrough, false, 'the newest compatibility response must be returned');
  assert.equal(context.currentConfig().drm_passthrough, false, 'the newest compatibility response must be stored');

  releaseFirst();
  const staleReturn = await first;
  assert.equal(staleReturn.drm_passthrough, false, 'an aborted compatibility read must return the newest cached state');
  assert.equal(context.currentConfig().drm_passthrough, false, 'a delayed stale compatibility response must not overwrite current state');
  console.log('UX compatibility config request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
