const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const start = source.indexOf('async function loadServers(options = {})');
const end = source.indexOf('function resetServerForm()', start);
assert.ok(start >= 0 && end > start, 'loadServers implementation is missing');
const implementation = source.slice(start, end);
assert.match(implementation, /rawServers/);
assert.match(implementation, /rawServers\.slice\(0, 256\)/);
assert.match(implementation, /String\(server\?\.url \?\? ''\)\.slice\(0, 2048\)/);
assert.match(implementation, /\.filter\(server => server\.id && server\.url\)/);

const appended = [];
const list = { innerHTML: '', appendChild(node) { appended.push(node); } };
let calls = 0;
const context = {
  console,
  AbortController,
  document: {
    getElementById(id) { return id === 'serverList' ? list : null; },
    createElement() {
      return {
        style: {},
        append() {},
        appendChild() {},
        setAttribute() {},
        textContent: '',
        className: '',
        onclick: null
      };
    }
  },
  fetchAuth(path, options) {
    calls += 1;
    assert.equal(path, '/api/servers');
    assert.ok(options.signal);
    if (calls === 1) {
      return Promise.resolve({
        ok: true,
        async json() {
          return Array.from({ length: 300 }, (_, index) => ({
            id: `id-${index}`,
            name: index === 0 ? '<img src=x onerror=1>' : `server-${index}`,
            url: index === 0 ? 'https://' + 'u'.repeat(3000) : `https://example.test/${index}`,
            lastStatus: 'OK'
          }));
        },
        async text() { return ''; }
      });
    }
    return Promise.resolve({ ok: true, async json() { return { malformed: true }; }, async text() { return ''; } });
  },
  runWithState() {},
  requireConfirm() {},
  refreshServer() {},
  deleteServer() {}
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let serverListController = null;
  ${implementation}
  this.loadServers = loadServers;
`, context, { filename: 'index.html#loadServers' });

const addServerStart = source.indexOf('async function addServer()');
const addServerEnd = source.indexOf('async function deleteServer', addServerStart);
assert.ok(addServerStart >= 0 && addServerEnd > addServerStart, 'addServer implementation is missing');
const addServerImpl = source.slice(addServerStart, addServerEnd);

const serverInputs = {
  srvName: { value: 'Primary Feed' },
  srvUrl: { value: 'https://example.test/repo' },
  srvAuthType: { value: 'BEARER' },
  srvAuthToken: { value: 'secret-token' },
  srvPriority: { value: '10' },
  srvRefreshHours: { value: '6' },
  srvAutoRefresh: { checked: true },
  srvContentPassword: { value: 'pass' },
  srvContentPublicKey: { value: 'pubkey' }
};

let addServerPostPayload = null;
let addServerOptions = null;
let resetCalled = false;
let loadCalled = false;

class TestFormData {
  constructor() { this.entries = {}; }
  append(key, value) { this.entries[key] = value; }
}

context.FormData = TestFormData;
context.URL = URL;
context.crypto = crypto;
context.notify = () => {};
context.resetServerForm = () => { resetCalled = true; };

const origGetElementById = context.document.getElementById;
context.document.getElementById = (id) => {
  if (serverInputs[id]) return serverInputs[id];
  return origGetElementById(id);
};

const origFetchAuth = context.fetchAuth;
context.fetchAuth = (path, options) => {
  if (path === '/api/server/add') {
    addServerOptions = options;
    addServerPostPayload = JSON.parse(options.body.entries.data);
    return Promise.resolve({ ok: true, text: async () => 'Saved' });
  }
  return origFetchAuth(path, options);
};

vm.runInContext(`
  ${addServerImpl}
  this.addServer = addServer;
`, context, { filename: 'index.html#addServer' });

(async () => {
  await context.loadServers();
  assert.equal(appended.length, 256, 'server rendering must be capped at 256 entries');
  assert.equal(appended[0].className, 'server-item');
  assert.equal(appended[0].style, appended[0].style, 'server row must be created as a bounded DOM node');
  await context.loadServers();
  assert.equal(appended.length, 256, 'a malformed non-array server response must not append rows');

  context.loadServers = async () => { loadCalled = true; };
  await context.addServer();
  assert.ok(addServerOptions, 'addServer must invoke fetchAuth');
  assert.equal(addServerOptions.method, 'POST');
  assert.equal(addServerOptions.idempotent, true, 'addServer must be idempotent');
  assert.ok(addServerPostPayload, 'addServer must send serialized data');
  assert.ok(addServerPostPayload.id, 'addServer must include a stable client-generated ID');
  assert.match(addServerPostPayload.id, /^[A-Za-z0-9_-]{1,64}$/, 'server ID must match validServerId regex');
  assert.equal(addServerPostPayload.name, 'Primary Feed');
  assert.equal(addServerPostPayload.url, 'https://example.test/repo');
  assert.equal(resetCalled, true, 'resetServerForm must be called');
  assert.equal(loadCalled, true, 'loadServers must be called');

  console.log('Server list bounds, malformed-response, and addServer replay-safe regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
