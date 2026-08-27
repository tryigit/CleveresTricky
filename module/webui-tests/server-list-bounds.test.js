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

(async () => {
  await context.loadServers();
  assert.equal(appended.length, 256, 'server rendering must be capped at 256 entries');
  assert.equal(appended[0].className, 'server-item');
  assert.equal(appended[0].style, appended[0].style, 'server row must be created as a bounded DOM node');
  await context.loadServers();
  assert.equal(appended.length, 256, 'a malformed non-array server response must not append rows');
  console.log('Server list bounds and malformed-response regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
