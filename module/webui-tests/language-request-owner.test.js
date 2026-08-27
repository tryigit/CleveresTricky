const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const start = source.indexOf('async function loadLanguage()');
const end = source.indexOf('function t(', start);
assert.ok(start >= 0 && end > start, 'loadLanguage implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /previousController\.abort\(\)/);
assert.match(implementation, /fetchAuth\('\/api\/language', \{ signal: controller\.signal \}\)/);
assert.match(implementation, /if \(controller\.signal\.aborted\) return;/);

let releaseFirst;
const calls = [];
const context = {
  console,
  AbortController,
  fetchAuth(path, options) {
    calls.push({ path, signal: options.signal });
    if (calls.length === 1) {
      return new Promise(resolve => {
        releaseFirst = () => resolve({ ok: true, async json() { return { tab_dashboard: 'Stale' }; } });
      });
    }
    return Promise.resolve({ ok: true, async json() { return { tab_dashboard: 'Newest' }; } });
  }
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let translations = {};
  let languageController = null;
  let applyCount = 0;
  function applyTranslations() { applyCount += 1; }
  ${implementation}
  this.loadLanguage = loadLanguage;
  this.currentTranslations = () => translations;
  this.applyCount = () => applyCount;
`, context, { filename: 'index.html#loadLanguage' });

(async () => {
  const first = context.loadLanguage();
  await new Promise(resolve => setImmediate(resolve));
  const second = context.loadLanguage();
  await second;
  assert.equal(calls.length, 2, 'a replacement language load should start one new request');
  assert.equal(calls[0].signal.aborted, true, 'the stale language request must be aborted');
  assert.equal(context.currentTranslations().tab_dashboard, 'Newest', 'the newest language catalog must win');
  assert.equal(context.applyCount(), 1, 'only the newest language catalog may be applied');

  releaseFirst();
  await first;
  assert.equal(context.currentTranslations().tab_dashboard, 'Newest', 'a delayed stale language catalog must not overwrite current translations');
  assert.equal(context.applyCount(), 1, 'a delayed stale catalog must not be applied');
  console.log('Language request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
