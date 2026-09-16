'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('    function render() {');
const end = source.indexOf('    function normalizeKeyboxScope(value) {', start);
assert.ok(start >= 0 && end > start, 'render implementation is missing');
const implementation = source.slice(start, end);

const startVerify = source.indexOf('    function renderVerification() {');
const endVerify = source.indexOf('    async function verify() {', startVerify);
assert.ok(startVerify >= 0 && endVerify > startVerify, 'renderVerification implementation is missing');
const verifyImplementation = source.slice(startVerify, endVerify);

const startExpired = source.indexOf('    function isKeyboxExpired(notAfter) {');
const endExpired = source.indexOf('    function statusLabel() {', startExpired);
assert.ok(startExpired >= 0 && endExpired > startExpired, 'isKeyboxExpired implementation is missing');
const expiredImplementation = source.slice(startExpired, endExpired);

function makeElement(tagName) {
  return {
    tagName: String(tagName).toUpperCase(),
    children: [],
    attributes: {},
    style: {},
    textContent: '',
    className: '',
    addEventListener() {},
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    append(...children) {
      children.filter(Boolean).forEach(child => this.appendChild(child));
    },
    setAttribute(name, value) {
      this.attributes[name] = String(value);
    }
  };
}

const list = makeElement('div');
const verifyResult = makeElement('div');
const context = {
  console,
  document: {
    getElementById(id) {
      if (id === 'storedKeyboxesList') return list;
      if (id === 'verifyResult') return verifyResult;
      return null;
    },
    createElement: makeElement
  },
  t(key) { return key; },
  ensureControls() {},
  updateControls() {},
  ensureVerificationControls() {},
  updateVerificationPager() {},
  deleteOne() {}
};
context.window = context;
context.global = context;
vm.createContext(context);
vm.runInContext(`
  let page = 1;
  const PAGE_SIZE = 5;
  let loading = false;
  let inventory = [];
  let selected = new Set();
  function filtered() { return inventory; }
  ${expiredImplementation}
  ${implementation}
  this.setInventory = items => { inventory = items; };
  this.renderKeyboxes = render;

  let verificationPage = 1;
  let verificationItems = [];
  function filteredVerification() { return verificationItems; }
  ${verifyImplementation}
  this.setVerificationItems = items => { verificationItems = items; };
  this.renderVerification = renderVerification;
`, context, { filename: 'ux.js#renderKeyboxes' });

// Test 1: StrongBox badge
context.setInventory([
  { id: '1', filename: 'sb.xml', scope: 'root', certificate_serial: '123', security_level: 'StrongBox' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const sbRow = list.children[0];
const sbBody = sbRow.children[1];
const sbName = sbBody.children[0];
assert.equal(sbName.children[0].textContent, 'sb.xml');
assert.equal(sbName.children[1].className, 'ct-badge ct-badge-strongbox');
assert.equal(sbName.children[1].textContent, 'StrongBox');

// Test 2: Non-RKP TEE does NOT render TEE badge
context.setInventory([
  { id: '2', filename: 'tee.xml', scope: 'managed', certificate_serial: '456', security_level: 'TEE' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const teeRow = list.children[0];
const teeBody = teeRow.children[1];
const teeName = teeBody.children[0];
assert.equal(teeName.children[0].textContent, 'tee.xml');
assert.equal(teeName.children.length, 1, 'Non-RKP keybox must not render TEE badge');

// Test 3: Unknown badge
context.setInventory([
  { id: '3', filename: 'unknown.xml', scope: 'managed', certificate_serial: '', security_level: 'Unknown' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const unkRow = list.children[0];
const unkBody = unkRow.children[1];
const unkName = unkBody.children[0];
assert.equal(unkName.children[0].textContent, 'unknown.xml');
assert.equal(unkName.children[1].className, 'ct-badge ct-badge-unknown');
assert.equal(unkName.children[1].textContent, 'Unknown');

// Test 4: Missing / other security_level has no badge
context.setInventory([
  { id: '4', filename: 'plain.xml', scope: 'managed', certificate_serial: '', security_level: '' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const plainRow = list.children[0];
const plainBody = plainRow.children[1];
const plainName = plainBody.children[0];
assert.equal(plainName.children[0].textContent, 'plain.xml');
assert.equal(plainName.children.length, 1, 'plain item should not render any security badge');

// Test 5: RKP badge (mutually exclusive with TEE)
context.setInventory([
  { id: '5', filename: 'tee_rkp.xml', scope: 'managed', certificate_serial: '789', security_level: 'TEE', is_rkp: true }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const rkpRow = list.children[0];
const rkpBody = rkpRow.children[1];
const rkpName = rkpBody.children[0];
assert.equal(rkpName.children[0].textContent, 'tee_rkp.xml');
assert.equal(rkpName.children.length, 2, 'RKP keybox must render only RKP badge, not both TEE and RKP');
assert.equal(rkpName.children[1].className, 'ct-badge ct-badge-rkp');
assert.equal(rkpName.children[1].textContent, 'RKP');

// Test 6: RSA badge
context.setInventory([
  { id: '6', filename: 'rsa_kb.xml', scope: 'managed', certificate_serial: '111', security_level: '', has_rsa: true }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const rsaRow = list.children[0];
const rsaName = rsaRow.children[1].children[0];
assert.equal(rsaName.className, 'ct-keybox-name');
assert.equal(rsaName.children[0].textContent, 'rsa_kb.xml');
assert.equal(rsaName.children.length, 2);
assert.equal(rsaName.children[1].className, 'ct-badge ct-badge-rsa');
assert.equal(rsaName.children[1].textContent, 'RSA');

// Test 7: ECDSA badge when no RSA
context.setInventory([
  { id: '7', filename: 'ec_kb.xml', scope: 'managed', certificate_serial: '222', security_level: '', has_rsa: false, has_ec: true }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const ecRow = list.children[0];
const ecName = ecRow.children[1].children[0];
assert.equal(ecName.children[0].textContent, 'ec_kb.xml');
assert.equal(ecName.children.length, 2);
assert.equal(ecName.children[1].className, 'ct-badge ct-badge-ecdsa');
assert.equal(ecName.children[1].textContent, 'ECDSA');

// Test 8: Both RSA and EC present -> RSA badge is rendered
context.setInventory([
  { id: '8', filename: 'combo.xml', scope: 'managed', certificate_serial: '333', security_level: '', has_rsa: true, has_ec: true }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const comboRow = list.children[0];
const comboName = comboRow.children[1].children[0];
assert.equal(comboName.children.length, 2);
assert.equal(comboName.children[1].className, 'ct-badge ct-badge-rsa');
assert.equal(comboName.children[1].textContent, 'RSA');

// Test 9: RKP + RSA together (no redundant TEE badge)
context.setInventory([
  { id: '9', filename: 'full.xml', scope: 'managed', certificate_serial: '444', security_level: 'TEE', is_rkp: true, has_rsa: true }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const fullRow = list.children[0];
const fullName = fullRow.children[1].children[0];
assert.equal(fullName.children.length, 3);
assert.equal(fullName.children[0].textContent, 'full.xml');
assert.equal(fullName.children[1].className, 'ct-badge ct-badge-rkp');
assert.equal(fullName.children[2].className, 'ct-badge ct-badge-rsa');

// Test 10: Verify index.html contains badge and layout style definitions
const htmlSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');
assert.ok(htmlSource.includes('.ct-badge-rkp'), 'index.html must define .ct-badge-rkp');
assert.ok(htmlSource.includes('.ct-badge-rsa'), 'index.html must define .ct-badge-rsa');
assert.ok(htmlSource.includes('.ct-badge-ecdsa'), 'index.html must define .ct-badge-ecdsa');
assert.ok(htmlSource.includes('.ct-keybox-name'), 'index.html must define .ct-keybox-name');
assert.ok(htmlSource.includes('.ct-status-badge'), 'index.html must define .ct-status-badge');
assert.ok(htmlSource.includes('.ct-status-valid'), 'index.html must define .ct-status-valid');
assert.ok(htmlSource.includes('.ct-status-invalid'), 'index.html must define .ct-status-invalid');
assert.ok(htmlSource.includes('.ct-verification-title'), 'index.html must define .ct-verification-title');
assert.ok(htmlSource.includes('.ct-verification-filename'), 'index.html must define .ct-verification-filename');
assert.ok(htmlSource.includes('.ct-verification-badges'), 'index.html must define .ct-verification-badges');
assert.ok(htmlSource.includes('.ct-status-expired'), 'index.html must define .ct-status-expired');
assert.ok(htmlSource.includes('.ct-badge-expired'), 'index.html must define .ct-badge-expired');

// Test 11: Verification badge rendering with status pill and algorithm badges
context.setVerificationItems([
  {
    filename: 'verify_valid.xml',
    status: 'VALID',
    security_level: 'TEE',
    is_rkp: true,
    has_rsa: true,
    certificate_serial: '777',
    details: 'Active keybox'
  },
  {
    filename: 'verify_invalid.xml',
    status: 'INVALID',
    security_level: 'StrongBox',
    has_ec: true,
    certificate_serial: '',
    details: 'Revoked'
  }
]);
verifyResult.children = [];
context.renderVerification();
assert.equal(verifyResult.children.length, 2);

// Item 1: VALID, RKP (no TEE), RSA
const vRow1 = verifyResult.children[0];
const vTitle1 = vRow1.children[0];
assert.equal(vTitle1.className, 'ct-verification-title');
const vFilename1 = vTitle1.children[0];
assert.equal(vFilename1.className, 'ct-verification-filename');
assert.equal(vFilename1.textContent, 'verify_valid.xml');

const vBadges1 = vTitle1.children[1];
assert.equal(vBadges1.className, 'ct-verification-badges');
assert.equal(vBadges1.children.length, 3);
assert.equal(vBadges1.children[0].className, 'ct-badge ct-status-badge ct-status-valid');
assert.equal(vBadges1.children[0].textContent, 'status_valid');
assert.equal(vBadges1.children[1].className, 'ct-badge ct-badge-rkp');
assert.equal(vBadges1.children[1].textContent, 'RKP');
assert.equal(vBadges1.children[2].className, 'ct-badge ct-badge-rsa');
assert.equal(vBadges1.children[2].textContent, 'RSA');

const vDetails1 = vRow1.children[2];
assert.equal(vDetails1.textContent, 'active_keybox');

// Item 2: INVALID, StrongBox, ECDSA
const vRow2 = verifyResult.children[1];
const vTitle2 = vRow2.children[0];
const vBadges2 = vTitle2.children[1];
assert.equal(vBadges2.children.length, 3);
assert.equal(vBadges2.children[0].className, 'ct-badge ct-status-badge ct-status-invalid');
assert.equal(vBadges2.children[0].textContent, 'status_invalid');
assert.equal(vBadges2.children[1].className, 'ct-badge ct-badge-strongbox');
assert.equal(vBadges2.children[1].textContent, 'StrongBox');
assert.equal(vBadges2.children[2].className, 'ct-badge ct-badge-ecdsa');
assert.equal(vBadges2.children[2].textContent, 'ECDSA');

// Test 12: Stored keybox with expired not_after renders expired badge and includes expiry in meta
context.setInventory([
  { id: '12', filename: 'expired.xml', scope: 'managed', certificate_serial: '111', security_level: 'StrongBox', not_after: '2020-01-01' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const expRow = list.children[0];
const expBody = expRow.children[1];
const expName = expBody.children[0];
assert.equal(expName.children[0].textContent, 'expired.xml');
assert.equal(expName.children[1].className, 'ct-badge ct-badge-strongbox');
assert.equal(expName.children[2].className, 'ct-badge ct-status-badge ct-badge-expired ct-status-expired');
assert.equal(expName.children[2].textContent, 'status_expired');
const expMeta = expBody.children[1];
assert.ok(expMeta.textContent.includes('2020-01-01'), 'meta must include expiry date');

// Test 13: Stored keybox with future not_after does NOT render expired badge
context.setInventory([
  { id: '13', filename: 'future.xml', scope: 'managed', certificate_serial: '222', security_level: 'StrongBox', not_after: '2099-01-01' }
]);
list.children = [];
context.renderKeyboxes();
assert.equal(list.children.length, 1);
const futRow = list.children[0];
const futBody = futRow.children[1];
const futName = futBody.children[0];
assert.equal(futName.children.length, 2); // name + StrongBox only, no expired badge
const futMeta = futBody.children[1];
assert.ok(futMeta.textContent.includes('2099-01-01'), 'meta must include expiry date');

// Test 14: Verification item with status VALID but expired not_after renders ct-status-expired
context.setVerificationItems([
  {
    filename: 'verify_expired.xml',
    status: 'VALID',
    security_level: 'TEE',
    is_rkp: true,
    certificate_serial: '333',
    not_after: '2020-01-01',
    details: 'Active keybox'
  }
]);
verifyResult.children = [];
context.renderVerification();
assert.equal(verifyResult.children.length, 1);
const veRow = verifyResult.children[0];
const veTitle = veRow.children[0];
const veBadges = veTitle.children[1];
assert.equal(veBadges.children[0].className, 'ct-badge ct-status-badge ct-status-expired');
assert.equal(veBadges.children[0].textContent, 'status_expired');
const veMeta = veRow.children[1];
assert.ok(veMeta.textContent.includes('2020-01-01'), 'verification meta must include expiry date');

console.log('Keybox security and algorithm badge rendering regression checks passed');
