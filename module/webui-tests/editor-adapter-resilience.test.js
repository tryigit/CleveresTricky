const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');

// 1. Verify fetchAuth retries across adapter unavailability for idempotent/safe calls
{
  const start = source.indexOf('        async function fetchAuth(url, options = {})');
  const end = source.indexOf('        async function downloadBlob(blob, filename)', start);
  assert.ok(start >= 0 && end > start, 'fetchAuth implementation must be found');
  const implementation = source.slice(start, end);

  let bridgeCalls = 0;
  const context = {
    console,
    DOMException,
    setTimeout(callback) { callback(); return 1; },
    clearTimeout() {},
    window: {
      CleveresBridge: {
        fetch(url, options) {
          bridgeCalls++;
          if (bridgeCalls === 1) {
            return Promise.reject(new Error('Native WebUI service rejected the request: Android adapter is unavailable'));
          }
          return Promise.resolve({
            status: 200,
            ok: true,
            text: async () => '{"status":"ok"}'
          });
        }
      }
    }
  };
  vm.createContext(context);
  vm.runInContext(`${implementation}\nthis.fetchAuth = fetchAuth;`, context);

  (async () => {
    // Safe GET call
    bridgeCalls = 0;
    const getRes = await context.fetchAuth('/api/config');
    assert.equal(getRes.status, 200, 'GET call should retry and succeed when adapter recovers');
    assert.equal(bridgeCalls, 2, 'GET call should have retried once');

    // Idempotent POST call
    bridgeCalls = 0;
    const postRes = await context.fetchAuth('/api/save', { method: 'POST', idempotent: true });
    assert.equal(postRes.status, 200, 'Idempotent POST call should retry and succeed when adapter recovers');
    assert.equal(bridgeCalls, 2, 'Idempotent POST call should have retried once');

    // Non-idempotent POST call without adapter unavailable error
    bridgeCalls = 0;
    context.window.CleveresBridge.fetch = () => {
      bridgeCalls++;
      return Promise.reject(new Error('Syntax error'));
    };
    await assert.rejects(
      () => context.fetchAuth('/api/upload', { method: 'POST' }),
      /Syntax error/
    );
    assert.equal(bridgeCalls, 1, 'Non-idempotent non-transient call must not retry');
  })().catch(err => {
    console.error(err);
    process.exit(1);
  });
}

// 2. Verify editor functions guard against saving "Loading..." and update button states
{
  assert.match(source, /editor\.value\s*===\s*['"]Loading\.\.\.['"]/);
  assert.match(source, /updateSaveButtonState\(\)/);
  assert.match(source, /idempotent:\s*true/);

  // Extract handleSave implementation
  const handleSaveStart = source.indexOf('async function handleSave(btn)');
  assert.ok(handleSaveStart >= 0, 'handleSave must exist');
  const handleSaveEnd = source.indexOf('function updateSaveButtonState()', handleSaveStart);
  assert.ok(handleSaveEnd > handleSaveStart, 'handleSave must end before updateSaveButtonState');
  const handleSaveSource = source.slice(handleSaveStart, handleSaveEnd);

  // Assert handleSave checks for Loading...
  assert.ok(
    handleSaveSource.includes("editor.value === 'Loading...'") ||
    handleSaveSource.includes('editor.value === "Loading..."') ||
    handleSaveSource.includes("content === 'Loading...'"),
    'handleSave must refuse to save when editor content is "Loading..."'
  );

  // Extract updateSaveButtonState implementation
  const updateBtnStart = source.indexOf('function updateSaveButtonState()');
  assert.ok(updateBtnStart >= 0, 'updateSaveButtonState must exist');
  const updateBtnEnd = source.indexOf('function revertEditor()', updateBtnStart);
  assert.ok(updateBtnEnd > updateBtnStart, 'updateSaveButtonState must end before revertEditor');
  const updateBtnSource = source.slice(updateBtnStart, updateBtnEnd);

  assert.ok(
    updateBtnSource.includes("editor.value === 'Loading...'") ||
    updateBtnSource.includes('editor.value === "Loading..."'),
    'updateSaveButtonState must disable save button while editor is "Loading..."'
  );

  // Assert switchTab reloads editor if stuck on Loading...
  const switchTabStart = source.indexOf('function switchTab(id)');
  assert.ok(switchTabStart >= 0, 'switchTab must exist');
  const switchTabEnd = source.indexOf('function handleTabNavigation', switchTabStart);
  assert.ok(switchTabEnd > switchTabStart, 'switchTab must end before handleTabNavigation');
  const switchTabSource = source.slice(switchTabStart, switchTabEnd);

  assert.ok(
    switchTabSource.includes("id === 'editor'") && switchTabSource.includes('loadFile()'),
    'switchTab must reload editor on tab activate if uninitialized or stuck'
  );
}

console.log('editor-adapter-resilience.test.js: All tests passed.');
