const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const switchStart = source.indexOf('function switchTab(id)');
const switchEnd = source.indexOf('async function fetchLogs()', switchStart);
assert.ok(switchStart >= 0 && switchEnd > switchStart, 'switchTab implementation is missing');
const switchSource = source.slice(switchStart, switchEnd);
assert.match(switchSource, /if \(id !== 'keys'\)[\s\S]*?keyInfoController\.abort\(\)[\s\S]*?serverListController\.abort\(\)[\s\S]*?keyboxListController\.abort\(\)/, 'leaving Keys must abort every pending Keys loader owner');

const loadKeyInfoStart = source.indexOf('async function loadKeyInfo()');
const loadKeyInfoEnd = source.indexOf('async function unlockCbox', loadKeyInfoStart);
assert.ok(loadKeyInfoStart >= 0 && loadKeyInfoEnd > loadKeyInfoStart, 'loadKeyInfo implementation is missing');
const loadKeyInfoSource = source.slice(loadKeyInfoStart, loadKeyInfoEnd);
assert.match(loadKeyInfoSource, /const previousController = keyInfoController/);
assert.match(loadKeyInfoSource, /previousController\.abort\(\)/);
assert.match(loadKeyInfoSource, /loadKeyboxes\(options\)/);
assert.match(loadKeyInfoSource, /loadServers\(options\)/);
assert.match(loadKeyInfoSource, /fetchAuth\('\/api\/config', options\)/);
assert.match(loadKeyInfoSource, /fetchAuth\('\/api\/cbox_status', options\)/);
assert.match(loadKeyInfoSource, /if \(controller\.signal\.aborted\) return;/);

const loadServersStart = source.indexOf('async function loadServers(options = {})');
const loadServersEnd = source.indexOf('function resetServerForm()', loadServersStart);
assert.ok(loadServersStart >= 0 && loadServersEnd > loadServersStart, 'loadServers implementation is missing');
assert.match(source.slice(loadServersStart, loadServersEnd), /fetchAuth\('\/api\/servers', requestOptions\)/);

const loadKeyboxesStart = source.indexOf('async function loadKeyboxes(options = {})');
const loadKeyboxesEnd = source.indexOf('function renderKeyboxes()', loadKeyboxesStart);
assert.ok(loadKeyboxesStart >= 0 && loadKeyboxesEnd > loadKeyboxesStart, 'loadKeyboxes implementation is missing');
assert.match(source.slice(loadKeyboxesStart, loadKeyboxesEnd), /fetchAuth\('\/api\/keyboxes', requestOptions\)/);

function responseFor(path) {
  return {
    ok: true,
    async json() {
      return path === '/api/config' ? { keybox_count: 2 } : { locked: [] };
    },
    async text() { return ''; }
  };
}

const firstResponses = [];
const fetchCalls = [];
const childCalls = [];
const notifications = [];
const elements = {
  keyboxStatus: { innerText: '' },
  lockedList: { innerHTML: '' },
  lockedSection: { style: {} }
};
const context = {
  console,
  AbortController,
  document: {
    getElementById(id) { return elements[id] || null; },
    createElement() { throw new Error('stale test response must not create locked-item DOM'); }
  },
  fetchAuth(path, options) {
    fetchCalls.push({ path, signal: options.signal });
    if (fetchCalls.length <= 2) {
      return new Promise(resolve => firstResponses.push(() => resolve(responseFor(path))));
    }
    return Promise.resolve(responseFor(path));
  },
  notify(message) { notifications.push(message); },
  childCalls
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let keyInfoController = null;
  async function loadKeyboxes(options) { childCalls.push({ owner: options.signal, path: 'keyboxes' }); }
  async function loadServers(options) { childCalls.push({ owner: options.signal, path: 'servers' }); }
  function childCallsPush(value) { childCalls.push(value); }
  ${loadKeyInfoSource}
  this.loadKeyInfo = loadKeyInfo;
`, context, { filename: 'index.html#loadKeyInfo' });

(async () => {
  const first = context.loadKeyInfo();
  await Promise.resolve();
  const second = context.loadKeyInfo();
  await second;

  assert.equal(fetchCalls.length, 4, 'two Keys loads should issue two config/status pairs');
  assert.equal(fetchCalls[0].signal.aborted, true, 'the stale config request must be aborted');
  assert.equal(fetchCalls[1].signal.aborted, true, 'the stale status request must be aborted');
  assert.equal(childCalls.length, 4, 'both loads must pass request ownership to child loaders');
  assert.equal(childCalls[0].owner.aborted, true, 'the stale keybox child request must be aborted');
  assert.equal(childCalls[1].owner.aborted, true, 'the stale server child request must be aborted');
  assert.equal(elements.keyboxStatus.innerText, '2 Keys Loaded', 'the newest Keys response must update the UI');

  firstResponses.splice(0).forEach(resolve => resolve());
  await first;
  assert.equal(elements.keyboxStatus.innerText, '2 Keys Loaded', 'a delayed stale Keys response must not repaint the UI');
  assert.deepEqual(notifications, [], 'aborted stale Keys requests must not create error noise');
  console.log('Keys request-owner cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
