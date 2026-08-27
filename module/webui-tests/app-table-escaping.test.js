'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const start = source.indexOf('function renderAppTable()');
const end = source.indexOf('function addAppRule()', start);
assert.ok(start >= 0 && end > start, 'renderAppTable implementation is missing');
const implementation = source.slice(start, end);
assert.doesNotMatch(implementation, /tr\.innerHTML\s*=/, 'app rows must not interpolate untrusted data into innerHTML');
assert.match(implementation, /cell\.textContent\s*=\s*value/);
assert.match(implementation, /button\.setAttribute\('aria-label'/);

function makeElement(tagName) {
  return {
    tagName: String(tagName).toUpperCase(),
    children: [],
    attributes: {},
    style: {},
    textContent: '',
    className: '',
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    append(...children) {
      children.filter(Boolean).forEach(child => this.appendChild(child));
    },
    replaceChildren(...children) {
      this.children = [];
      children.filter(Boolean).forEach(child => this.appendChild(child));
    },
    setAttribute(name, value) {
      this.attributes[name] = String(value);
    }
  };
}

const rows = [];
const tbody = makeElement('tbody');
tbody.appendChild = row => {
  rows.push(row);
  tbody.children.push(row);
  return row;
};
tbody.replaceChildren = (...children) => {
  rows.length = 0;
  tbody.children = [];
  children.filter(Boolean).forEach(child => tbody.appendChild(child));
};
const filter = { value: '' };
const clear = { style: {} };
const context = {
  console,
  document: {
    getElementById(id) {
      if (id === 'appFilter') return filter;
      if (id === 'clearAppFilterBtn') return clear;
      return null;
    },
    querySelector(selector) {
      return selector === '#appTable tbody' ? tbody : null;
    },
    createElement: makeElement
  }
};
context.window = context;
vm.createContext(context);
vm.runInContext(`
  let appRules = [
    { package: '\"><IMG SRC=x ONERROR=alert(1)>', template: '<SCRIPT>alert(2)</SCRIPT>', keybox: '&evil', privacy: 'isolate' },
    null,
    { package: 7, template: { bad: true }, keybox: null, privacy: null }
  ];
  ${implementation}
  this.renderAppTable = renderAppTable;
`, context, { filename: 'index.html#renderAppTable' });

context.renderAppTable();
assert.equal(rows.length, 3, 'malformed and attacker-shaped rules should render safely, not crash the table');
assert.equal(rows[0].children[0].textContent, '\"><IMG SRC=x ONERROR=alert(1)>', 'package data must be assigned as text');
assert.equal(rows[0].children[1].textContent, '<SCRIPT>alert(2)</SCRIPT>', 'template data must be assigned as text');
assert.equal(rows[0].children[2].textContent, '&evil', 'keybox data must be assigned as text');
assert.equal(rows[0].children[4].children[0].attributes['aria-label'], 'Edit rule for \"><IMG SRC=x ONERROR=alert(1)>', 'dynamic accessible names must use attribute APIs');
assert.equal(rows[0].children[0].children.length, 0, 'package attacker data must not create child markup nodes');
assert.equal(rows[0].children[1].children.length, 0, 'template attacker data must not create child markup nodes');
assert.equal(rows[1].children[0].textContent, '', 'null rule values must normalize to empty safe text');
assert.equal(rows[2].children[0].textContent, '', 'non-string package values must normalize to empty safe text');
console.log('Apps table DOM text rendering and malformed-response regression checks passed');
