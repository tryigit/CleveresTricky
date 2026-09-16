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
const hubHint = { id: 'ct_keyboxhub_hint', style: { display: '' } };

function collectNodes(root, matcher) {
  const matches = [];
  function traverse(node) {
    if (!node) return;
    if (matcher(node)) matches.push(node);
    if (node.children) node.children.forEach(traverse);
  }
  if (root && root.children) root.children.forEach(traverse);
  return matches;
}

function createNode(tag) {
  return {
    tagName: tag || 'div',
    style: {},
    children: [],
    append(...items) { items.forEach(item => this.appendChild(item)); },
    appendChild(child) { this.children.push(child); return child; },
    setAttribute(k, v) { this[k] = v; },
    getAttribute(k) { return this[k]; },
    querySelectorAll(sel) {
      return collectNodes(this, node => {
        if (sel.includes('ct-server-url') && node.className && node.className.includes('ct-server-url')) return true;
        if (sel.includes('.server-item div') && node.tagName === 'div') return true;
        return false;
      });
    },
    textContent: '',
    className: '',
    onclick: null
  };
}

const list = Object.assign(createNode('div'), {
  innerHTML: '',
  appendChild(node) { appended.push(node); this.children.push(node); return node; }
});
let calls = 0;
const context = {
  console,
  AbortController,
  document: {
    getElementById(id) {
      if (id === 'serverList') return list;
      if (id === 'ct_keyboxhub_hint') return hubHint;
      return null;
    },
    createElement(tag) {
      return createNode(tag);
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
    if (calls === 2) {
      return Promise.resolve({ ok: true, async json() { return { malformed: true }; }, async text() { return ''; } });
    }
    if (calls === 3) {
      return Promise.resolve({
        ok: true,
        async json() {
          return [
            {
              id: 'hub-server',
              name: 'My KeyboxHub',
              url: 'https://keybox.tryigit.dev/feed',
              lastStatus: 'OK',
              keyboxCount: 4,
              rkpCount: 2,
              rsaCount: 3,
              cboxCount: 4
            }
          ];
        },
        async text() { return ''; }
      });
    }
    return Promise.resolve({ ok: true, async json() { return []; }, async text() { return ''; } });
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
  assert.equal(hubHint.style.display, '', 'hub recommendation must remain visible when no keybox.tryigit.dev is configured');

  await context.loadServers();
  assert.equal(appended.length, 256, 'a malformed non-array server response must not append rows');

  appended.length = 0;
  await context.loadServers();
  assert.equal(appended.length, 1);
  assert.equal(hubHint.style.display, 'none', 'hub recommendation must be hidden when keybox.tryigit.dev is present');
  const serverItem = appended[0];
  const infoCol = serverItem.children[0];
  const statsDiv = infoCol.children.find(c => c.className === 'ct-server-stats');
  assert.ok(statsDiv, 'stats breakdown must be rendered');
  const badgeTexts = statsDiv.children.map(c => c.textContent);
  assert.deepEqual(badgeTexts, ['Keybox: 4', 'CBOX: 4', 'RKP: 2', 'RSA: 3']);
  // Ensure no sensitive or internal names/IDs are rendered
  assert.ok(!JSON.stringify(badgeTexts).includes('.xml'));
  assert.ok(!JSON.stringify(badgeTexts).includes('hub-server'));

  // Test removing keybox.tryigit.dev server restores recommendation
  appended.length = 0;
  await context.loadServers();
  assert.equal(appended.length, 0);
  assert.equal(hubHint.style.display, '', 'hub recommendation must be restored when keybox.tryigit.dev server is removed');

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

  // Verify ux.js syncKeyboxHubHintVisibility behavior
  const uxSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
  assert.ok(uxSource.includes('function syncKeyboxHubHintVisibility()'), 'ux.js must define syncKeyboxHubHintVisibility');
  const syncStart = uxSource.indexOf('function syncKeyboxHubHintVisibility()');
  const syncEnd = uxSource.indexOf('function installKeyboxHubHint()', syncStart);
  assert.ok(syncStart >= 0 && syncEnd > syncStart);
  const syncImpl = uxSource.slice(syncStart, syncEnd);
  vm.runInContext(`${syncImpl}; this.syncKeyboxHubHintVisibility = syncKeyboxHubHintVisibility;`, context);

  // Test 1: item with exact hostname hides hint
  list.children = [];
  const hubItem = createNode('div');
  hubItem.className = 'server-item';
  const urlNode = createNode('div');
  urlNode.className = 'ct-server-url';
  urlNode.textContent = 'https://keybox.tryigit.dev/feed';
  hubItem.appendChild(urlNode);
  list.appendChild(hubItem);

  context.syncKeyboxHubHintVisibility();
  assert.equal(hubHint.style.display, 'none', 'ux.js must hide recommendation if list contains keybox.tryigit.dev hostname');

  // Test 2: server name contains keybox.tryigit.dev but URL is different -> recommendation must NOT be hidden
  list.children = [];
  const fakeItem = createNode('div');
  fakeItem.className = 'server-item';
  const nameNode = createNode('div');
  nameNode.textContent = 'keybox.tryigit.dev mirror';
  const fakeUrlNode = createNode('div');
  fakeUrlNode.className = 'ct-server-url';
  fakeUrlNode.textContent = 'https://other-domain.com/feed';
  fakeItem.appendChild(nameNode);
  fakeItem.appendChild(fakeUrlNode);
  list.appendChild(fakeItem);

  context.syncKeyboxHubHintVisibility();
  assert.equal(hubHint.style.display, '', 'ux.js must not hide recommendation when only server name matches');

  // Test 3: URL path contains keybox.tryigit.dev but hostname is different -> recommendation must NOT be hidden
  fakeUrlNode.textContent = 'https://other-domain.com/keybox.tryigit.dev';
  context.syncKeyboxHubHintVisibility();
  assert.equal(hubHint.style.display, '', 'ux.js must not hide recommendation when only URL path matches');

  console.log('Server list bounds, malformed-response, and addServer replay-safe regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
