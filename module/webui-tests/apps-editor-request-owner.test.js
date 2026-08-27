const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');

const appStart = source.indexOf('async function loadAppConfig()');
const appEnd = source.indexOf('function renderAppTable()', appStart);
assert.ok(appStart >= 0 && appEnd > appStart, 'loadAppConfig implementation is missing');
const appSource = source.slice(appStart, appEnd);
assert.match(appSource, /const previousController = appConfigController/);
assert.match(appSource, /previousController\.abort\(\)/);
assert.match(appSource, /fetchAuth\(getAuthUrl\('\/api\/app_config_structured'\), options\)/);
assert.match(appSource, /if \(controller\.signal\.aborted\) return;/);

const editorStart = source.indexOf('async function loadFile()');
const editorEnd = source.indexOf('async function handleSave', editorStart);
assert.ok(editorStart >= 0 && editorEnd > editorStart, 'loadFile implementation is missing');
const editorSource = source.slice(editorStart, editorEnd);
assert.match(editorSource, /const previousController = editorFileController/);
assert.match(editorSource, /previousController\.abort\(\)/);
assert.match(editorSource, /fetchAuth\('\/api\/file\?filename=' \+ encodeURIComponent\(f\), \{ signal: controller\.signal \}\)/);
assert.match(editorSource, /if \(controller\.signal\.aborted\) return;/);

const appCalls = [];
let releaseFirstApp;
const appTbody = { innerHTML: '' };
const appContext = {
  console,
  AbortController,
  document: { querySelector(selector) { return selector === '#appTable tbody' ? appTbody : null; } },
  getAuthUrl(path) { return path; },
  fetchAuth(path, options) {
    appCalls.push({ path, signal: options.signal });
    if (appCalls.length === 1) return new Promise(resolve => { releaseFirstApp = () => resolve({ ok: true, async json() { return [{ package: 'stale' }]; }, async text() { return ''; } }); });
    return Promise.resolve({ ok: true, async json() { return [{ package: 'newest' }]; }, async text() { return ''; } });
  },
  notify() {}
};
appContext.window = appContext;
vm.createContext(appContext);
vm.runInContext(`
  let appRules = [];
  let appConfigController = null;
  let renderCount = 0;
  function renderAppTable() { renderCount += 1; }
  ${appSource}
  this.loadAppConfig = loadAppConfig;
  this.renderCount = () => renderCount;
  this.currentRules = () => appRules;
`, appContext, { filename: 'index.html#loadAppConfig' });

const editorCalls = [];
let releaseFirstEditor;
const selector = { value: 'target.txt' };
const editor = { value: '', disabled: false };
const editorContext = {
  console,
  AbortController,
  document: {
    getElementById(id) {
      if (id === 'fileSelector') return selector;
      if (id === 'fileEditor') return editor;
      return null;
    }
  },
  fetchAuth(path, options) {
    editorCalls.push({ path, signal: options.signal });
    if (editorCalls.length === 1) return new Promise(resolve => { releaseFirstEditor = () => resolve({ ok: true, async text() { return 'stale file'; } }); });
    return Promise.resolve({ ok: true, async text() { return 'newest file'; } });
  },
  notify() {},
  updateSaveButtonState() {}
};
editorContext.window = editorContext;
vm.createContext(editorContext);
vm.runInContext(`
  let editorUnsavedBypass = false;
  let currentFile = '';
  let originalContent = '';
  let editorFileController = null;
  ${editorSource}
  this.loadFile = loadFile;
  this.allowDiscard = () => { editorUnsavedBypass = true; };
  this.editorState = () => ({ currentFile, originalContent, value: document.getElementById('fileEditor').value });
`, editorContext, { filename: 'index.html#loadFile' });

(async () => {
  const firstApp = appContext.loadAppConfig();
  await Promise.resolve();
  const secondApp = appContext.loadAppConfig();
  await secondApp;
  assert.equal(appCalls.length, 2, 'a replacement Apps load should start one new request');
  assert.equal(appCalls[0].signal.aborted, true, 'the stale Apps request must be aborted');
  assert.deepEqual(appContext.currentRules(), [{ package: 'newest' }], 'the newest Apps response must win');
  releaseFirstApp();
  await firstApp;
  assert.equal(appContext.renderCount(), 1, 'a delayed stale Apps response must not rerender the table');

  const firstEditor = editorContext.loadFile();
  await Promise.resolve();
  selector.value = 'identity_target.txt';
  editorContext.allowDiscard();
  const secondEditor = editorContext.loadFile();
  await secondEditor;
  assert.equal(editorCalls.length, 2, 'a replacement Editor load should start one new request');
  assert.equal(editorCalls[0].signal.aborted, true, 'the stale Editor request must be aborted');
  const newestEditorState = editorContext.editorState();
  assert.equal(newestEditorState.currentFile, 'identity_target.txt', 'the newest Editor selection must remain active');
  assert.equal(newestEditorState.originalContent, 'newest file', 'the newest Editor response must win');
  assert.equal(newestEditorState.value, 'newest file', 'the newest Editor content must remain visible');
  releaseFirstEditor();
  await firstEditor;
  const finalEditorState = editorContext.editorState();
  assert.equal(finalEditorState.currentFile, 'identity_target.txt', 'a stale Editor response must not change the active selection');
  assert.equal(finalEditorState.originalContent, 'newest file', 'a delayed stale Editor response must not overwrite the newest file');
  assert.equal(finalEditorState.value, 'newest file', 'a delayed stale Editor response must not overwrite visible content');
  console.log('Apps and Editor request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
