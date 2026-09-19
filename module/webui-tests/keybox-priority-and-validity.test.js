'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');

function makeElement(tagName) {
  const element = {
    tagName: String(tagName).toUpperCase(),
    children: [],
    attributes: {},
    style: {},
    _textContent: '',
    className: '',
    value: '',
    disabled: false,
    addEventListener(event, handler) {
      if (!this._handlers) this._handlers = {};
      this._handlers[event] = handler;
    },
    trigger(event) {
      if (this._handlers && this._handlers[event]) {
        this._handlers[event]();
      }
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    append(...children) {
      children.filter(Boolean).forEach(child => this.appendChild(child));
    },
    setAttribute(name, value) {
      this.attributes[name] = String(value);
      // Mirror browser reflection so title-overwrite regressions surface.
      if (name === 'title') this.title = String(value);
    }
  };
  Object.defineProperty(element, 'textContent', {
    get() { return this._textContent + this.children.map(child => child.textContent).join(''); },
    set(value) { this._textContent = String(value); this.children = []; }
  });
  Object.defineProperty(element, 'innerHTML', {
    get() { return ''; },
    set(value) { if (value === '') this.children = []; }
  });
  return element;
}

const list = makeElement('div');
const verifyResult = makeElement('div');
const priorityModeSelect = makeElement('select');
const priorityList = makeElement('div');
const priorityActions = makeElement('div');
const prioritySaveBtn = makeElement('button');
const priorityResetBtn = makeElement('button');
const priorityModeHint = makeElement('p');

const elements = {
  storedKeyboxesList: list,
  verifyResult: verifyResult,
  ct_keybox_priority_mode: priorityModeSelect,
  ct_keybox_priority_list: priorityList,
  ct_keybox_priority_actions: priorityActions,
  ct_keybox_priority_save: prioritySaveBtn,
  ct_keybox_priority_reset: priorityResetBtn,
  ct_keybox_priority_mode_hint: priorityModeHint
};

const posts = [];
const priorityResponse = { mode: 'default' };
const context = {
  console,
  URLSearchParams,
  JSON,
  priorityResponse,
  Date: { parse: Date.parse, now: () => Date.now() },
  locale() { return 'en'; },
  VALUE_POPUP_COPY: { en: { title: 'Details', copy: 'Copy', copied: 'Copied', close: 'Close', hold: 'Hold to view and copy' } },
  document: {
    getElementById(id) {
      return elements[id] || null;
    },
    createElement: makeElement
  },
  fetchAuth(url, opts) {
    if (opts && opts.method === 'POST') {
      posts.push({ url, body: opts.body ? opts.body.toString() : '' });
      return Promise.resolve({
        ok: true,
        text: () => Promise.resolve('ok'),
        json: () => Promise.resolve({ success: true })
      });
    }
    return Promise.resolve({
      ok: true,
      json: () => Promise.resolve(context.priorityResponse)
    });
  },
  notify() {},
  t(key) { return key; },
  longPressAttachments: [],
  recordLongPressAttachment(node, label, value) {
    // Mirror the production helper, which stores its hold hint in the title
    // attribute that browsers reflect into node.title.
    node.setAttribute('title', 'Hold to view and copy');
    context.longPressAttachments.push({ node, label, value });
  },
  ensureControls() {},
  updateControls() {},
  ensureVerificationControls() {},
  updateVerificationPager() {},
  deleteOne() {},
  toggleDisabled() {}
};
context.window = context;
context.global = context;
vm.createContext(context);

// Extract render and renderVerification and priority order functions
const start = source.indexOf('    function render() {');
const end = source.indexOf('    function normalizeKeyboxScope(value) {', start);
const renderCode = source.slice(start, end);

const startVerify = source.indexOf('    function renderVerification() {');
const endVerify = source.indexOf('    async function verify() {', startVerify);
const verifyCode = source.slice(startVerify, endVerify);

const startExpired = source.indexOf('    function isKeyboxExpired(notAfter) {');
const endExpired = source.indexOf('    function statusLabel() {', startExpired);
const expiredCode = source.slice(startExpired, endExpired);

const priorityStart = source.indexOf('    const DEFAULT_PRIORITY_CATEGORIES = [');
const priorityEnd = source.indexOf('    function scheduleInstallRetry() {', priorityStart);
const priorityCode = source.slice(priorityStart, priorityEnd);

vm.runInContext(`
  let page = 1;
  const PAGE_SIZE = 5;
  let loading = false;
  let inventory = [];
  let selected = new Set();
  const togglingIds = new Set();
  function filtered() { return inventory; }
  ${expiredCode}
  ${renderCode}
  this.setInventory = items => { inventory = items; };
  this.renderKeyboxes = render;

  let verificationPage = 1;
  let verificationItems = [];
  function filteredVerification() { return verificationItems; }
  ${verifyCode}
  this.setVerificationItems = items => { verificationItems = items; };
  this.renderVerification = renderVerification;

  ${priorityCode}
  this.renderPriorityOrder = renderPriorityOrder;
  this.loadPriorityOrder = loadPriorityOrder;
  this.savePriorityOrder = savePriorityOrder;
  this.resetPriorityOrder = resetPriorityOrder;
  this.getCurrentOrder = () => currentPriorityOrder;
  this.getPriorityMode = () => currentPriorityMode;
  this.setPriorityMode = m => { currentPriorityMode = m; };
  this.setPriorityResponse = value => { priorityResponse = value; };
`, context);

// The expired-code slice also carries the real attachKeyboxLongPress
// declaration, which shadows the harness recorder during setup. Reinstall the
// recorder from the host so long-press wiring stays observable.
context.attachKeyboxLongPress = context.recordLongPressAttachment;

// Test 1: Validity badges for stored keyboxes
context.setInventory([
  { id: '1', filename: 'valid.xml', scope: 'root', security_level: 'TEE', validity_state: 'VALID' },
  { id: '2', filename: 'expired.xml', scope: 'root', security_level: 'TEE', validity_state: 'INVALID', invalid_reason: 'EXPIRED' },
  { id: '3', filename: 'revoked.xml', scope: 'root', security_level: 'StrongBox', validity_state: 'INVALID', invalid_reason: 'REVOKED' },
  { id: '4', filename: 'failed.xml', scope: 'root', security_level: 'RKP', validity_state: 'INVALID', invalid_reason: 'VERIFICATION_FAILED' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 4);

// Item 1: Valid badge
const name1 = list.children[0].children[1].children[0];
const badge1 = name1.children.find(c => c.className.includes('ct-status-badge'));
assert.ok(badge1, 'valid item must have status badge');
assert.ok(badge1.className.includes('ct-badge-valid'));
assert.equal(badge1.textContent, 'valid');

// Item 2: Invalid - Expired badge
const name2 = list.children[1].children[1].children[0];
const badge2 = name2.children.find(c => c.className.includes('ct-status-badge'));
assert.ok(badge2, 'expired item must have status badge');
assert.ok(badge2.className.includes('ct-badge-expired'));
assert.equal(badge2.textContent, 'invalid_expired');

// Item 3: Invalid - Revoked badge
const name3 = list.children[2].children[1].children[0];
const badge3 = name3.children.find(c => c.className.includes('ct-status-badge'));
assert.ok(badge3, 'revoked item must have status badge');
assert.ok(badge3.className.includes('ct-badge-revoked'));
assert.equal(badge3.textContent, 'invalid_revoked');

// Item 4: Invalid - Verification Failed badge
const name4 = list.children[3].children[1].children[0];
const badge4 = name4.children.find(c => c.className.includes('ct-status-badge'));
assert.ok(badge4, 'failed item must have status badge');
assert.ok(badge4.className.includes('ct-badge-invalid'));
assert.equal(badge4.textContent, 'invalid_verification_failed');

// Test 2: Verification result sub-reason badges
context.setVerificationItems([
  { filename: 'v_revoked.xml', status: 'INVALID', validity_state: 'INVALID', invalid_reason: 'REVOKED' },
  { filename: 'v_expired.xml', status: 'INVALID', validity_state: 'INVALID', invalid_reason: 'EXPIRED' },
  { filename: 'v_failed.xml', status: 'INVALID', validity_state: 'INVALID', invalid_reason: 'VERIFICATION_FAILED' }
]);
verifyResult.children = [];
context.renderVerification();
assert.equal(verifyResult.children.length, 3);

const vBadgesRevoked = verifyResult.children[0].children[0].children[1];
assert.ok(vBadgesRevoked.children[0].className.includes('ct-badge-revoked'));
assert.equal(vBadgesRevoked.children[0].textContent, 'invalid_revoked');

const vBadgesExpired = verifyResult.children[1].children[0].children[1];
assert.ok(vBadgesExpired.children[0].className.includes('ct-badge-expired'));
assert.equal(vBadgesExpired.children[0].textContent, 'invalid_expired');

const vBadgesFailed = verifyResult.children[2].children[0].children[1];
assert.ok(vBadgesFailed.children[0].className.includes('ct-badge-invalid'));
assert.equal(vBadgesFailed.children[0].textContent, 'invalid_verification_failed');

// Test 3: Priority order rendering in default mode
context.setPriorityMode('default');
context.renderPriorityOrder();
assert.equal(priorityList.style.display, 'none');
assert.equal(priorityActions.style.display, 'none');
assert.notEqual(priorityModeHint.style.display, 'none', 'mode hint must be visible in default mode');

// Test 4: Priority order rendering in custom mode
context.setPriorityMode('custom');
context.longPressAttachments.length = 0;
context.renderPriorityOrder();
assert.equal(priorityList.style.display, 'flex');
assert.equal(priorityActions.style.display, 'flex');
assert.equal(priorityModeHint.style.display, 'none', 'mode hint must hide when the list carries the detail');
assert.equal(priorityList.children.length, 6);

// Verify first item has up disabled, last has down disabled
const firstItem = priorityList.children[0];
const firstUpBtn = firstItem.children[1].children[0];
const firstDownBtn = firstItem.children[1].children[1];
assert.equal(firstUpBtn.disabled, true, 'first item up button must be disabled');
assert.equal(firstDownBtn.disabled, false, 'first item down button must be enabled');

const lastItem = priorityList.children[5];
const lastUpBtn = lastItem.children[1].children[0];
const lastDownBtn = lastItem.children[1].children[1];
assert.equal(lastUpBtn.disabled, false, 'last item up button must be enabled');
assert.equal(lastDownBtn.disabled, true, 'last item down button must be disabled');

// Every item label keeps its full text available via title and long-press popup
assert.equal(context.longPressAttachments.length, 6, 'each priority label must offer its full text on long-press');
for (const attachment of context.longPressAttachments) {
  assert.ok(attachment.value.length > 0, 'long-press value must not be empty');
  assert.equal(attachment.node.title, attachment.value, 'label title must carry the full text');
}

// Test 5: Reordering via down button on first item
const initialFirst = context.getCurrentOrder()[0];
const initialSecond = context.getCurrentOrder()[1];
firstDownBtn.onclick();
assert.equal(context.getCurrentOrder()[0], initialSecond);
assert.equal(context.getCurrentOrder()[1], initialFirst);

// Test 6: Reordering back via up button
const newSecondUpBtn = priorityList.children[1].children[1].children[0];
newSecondUpBtn.onclick();
assert.equal(context.getCurrentOrder()[0], initialFirst);
assert.equal(context.getCurrentOrder()[1], initialSecond);

(async () => {
  // Test 7: Save priority order sends POST to /api/keybox_priority_order
  posts.length = 0;
  await context.savePriorityOrder();
  assert.equal(posts.length, 1);
  assert.equal(posts[0].url, '/api/keybox_priority_order');
  const params = new URLSearchParams(posts[0].body);
  const data = JSON.parse(params.get('data'));
  assert.equal(data.mode, 'custom');
  assert.equal(data.customOrder.length, 6);
  assert.equal(data.customOrder[0], 'VALID_RKP');

  // Test 8: A complete permutation is accepted from the backend
  const reversedOrder = [...context.getCurrentOrder()].reverse();
  context.setPriorityResponse({ mode: 'custom', customOrder: reversedOrder });
  await context.loadPriorityOrder();
  assert.equal(context.getPriorityMode(), 'custom');
  assert.deepEqual(Array.from(context.getCurrentOrder()), reversedOrder);

  // Test 9: Incomplete, duplicate, and unknown orders force default mode
  const invalidOrders = [
    reversedOrder.slice(1),
    [...reversedOrder.slice(0, -1), reversedOrder[0]],
    [...reversedOrder.slice(0, -1), 'VALID_FUTURE_CATEGORY']
  ];
  for (const customOrder of invalidOrders) {
    context.setPriorityResponse({ mode: 'custom', customOrder });
    await context.loadPriorityOrder();
    assert.equal(context.getPriorityMode(), 'default');
    assert.deepEqual(Array.from(context.getCurrentOrder()), Array.from(reversedOrder).reverse());
  }

  // Test 10: Reset priority order resets to default
  await context.resetPriorityOrder();
  assert.equal(context.getPriorityMode(), 'default');
  assert.equal(context.getCurrentOrder()[0], 'VALID_RKP');

  // Test 11: Legacy 16-category orders project onto the six exposed categories
  const legacySixteen = [
    'VALID_RKP', 'VALID_STRONGBOX', 'VALID_TEE', 'VALID_UNKNOWN',
    'INVALID_EXPIRED_RKP', 'INVALID_EXPIRED_STRONGBOX', 'INVALID_EXPIRED_TEE', 'INVALID_EXPIRED_UNKNOWN',
    'INVALID_REVOKED_RKP', 'INVALID_REVOKED_STRONGBOX', 'INVALID_REVOKED_TEE', 'INVALID_REVOKED_UNKNOWN',
    'INVALID_VERIFICATION_FAILED_RKP', 'INVALID_VERIFICATION_FAILED_STRONGBOX',
    'INVALID_VERIFICATION_FAILED_TEE', 'INVALID_VERIFICATION_FAILED_UNKNOWN'
  ];
  context.setPriorityResponse({ mode: 'custom', customOrder: legacySixteen });
  await context.loadPriorityOrder();
  assert.equal(context.getPriorityMode(), 'custom');
  assert.deepEqual(Array.from(context.getCurrentOrder()), [
    'VALID_RKP', 'VALID_TEE',
    'INVALID_EXPIRED_RKP', 'INVALID_EXPIRED_TEE',
    'INVALID_REVOKED_RKP', 'INVALID_REVOKED_TEE'
  ]);

  console.log('Keybox priority ordering and explicit validity state tests passed');
})().catch(err => {
  console.error(err);
  process.exit(1);
});
