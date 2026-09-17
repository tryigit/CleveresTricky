'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const start = source.indexOf('    function popupCopy() {');
const end = source.indexOf('    function statusLabel() {', start);
assert.ok(start >= 0 && end > start, 'keybox value popup helpers are missing');
const implementation = source.slice(start, end);

function createDocument() {
    const document = { activeElement: null, listeners: new Map() };
    function createElement(tagName) {
        const element = {
            tagName: String(tagName).toUpperCase(),
            attributes: Object.create(null),
            children: [],
            className: '',
            classList: { add() {}, toggle() {} },
            id: '',
            style: {},
            textContent: '',
            parentNode: null,
            listeners: new Map(),
            appendChild(child) { this.children.push(child); child.parentNode = this; return child; },
            append(...children) { children.forEach(child => this.appendChild(child)); },
            setAttribute(name, value) { this.attributes[name] = String(value); },
            addEventListener(type, listener) { this.listeners.set(type, listener); },
            dispatch(type, event = {}) { this.listeners.get(type)?.({ currentTarget: this, target: this, preventDefault() { this.defaultPrevented = true; }, ...event }); },
            focus() { document.activeElement = this; },
            select() {},
            remove() {
                if (!this.parentNode) return;
                this.parentNode.children = this.parentNode.children.filter(child => child !== this);
                this.parentNode = null;
            }
        };
        Object.defineProperty(element, 'isConnected', { get: () => document.contains(element) });
        return element;
    }
    document.body = createElement('body');
    document.createElement = createElement;
    document.contains = element => {
        const visit = node => node === element || node.children.some(visit);
        return visit(document.body);
    };
    document.getElementById = id => {
        const find = node => node.id === id ? node : node.children.map(find).find(Boolean);
        return find(document.body);
    };
    document.addEventListener = (type, listener) => document.listeners.set(type, listener);
    document.removeEventListener = (type, listener) => { if (document.listeners.get(type) === listener) document.listeners.delete(type); };
    document.execCommand = () => true;
    return document;
}

function findByClass(node, className) {
    if (node.className === className) return node;
    return node.children.map(child => findByClass(child, className)).find(Boolean);
}

const document = createDocument();
const timers = [];
const context = {
    document,
    navigator: { clipboard: { writeText: async () => {} } },
    requestAnimationFrame(callback) { callback(); },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; },
    clearTimeout() {}
};
context.window = context;
context.global = context;
context.VALUE_POPUP_COPY = { en: { title: 'Details', copy: 'Copy', copied: 'Copied', close: 'Close', hold: 'Hold to view and copy' } };
vm.createContext(context);
vm.runInContext(`
    function locale() { return 'en'; }
    ${implementation}
    this.appendKeyboxValue = appendKeyboxValue;
    this.showKeyboxValuePopup = showKeyboxValuePopup;
`, context, { filename: 'ux.js#keyboxValuePopup' });

const parent = document.createElement('div');
document.body.appendChild(parent);
context.appendKeyboxValue(parent, 'Certificate', '1234');
const value = parent.children[0].children[1];
assert.equal(value.tabIndex, 0, 'certificate values must be keyboard-focusable');
assert.equal(value.attributes.role, 'button');
assert.equal(value.attributes['aria-haspopup'], 'dialog');

let spacePrevented = false;
value.focus();
value.dispatch('keydown', { key: ' ', preventDefault() { spacePrevented = true; } });
assert.equal(spacePrevented, true, 'Space must not scroll when opening the dialog');
let popup = document.getElementById('ct_keybox_value_popup');
let copyButton = findByClass(popup, 'ct-keybox-value-popup-copy');
assert.equal(document.activeElement, copyButton, 'opening by Space must focus Copy');
findByClass(popup, 'ct-keybox-value-popup-close').dispatch('click');
assert.equal(document.activeElement, value, 'closing the dialog must restore focus to its trigger');

value.dispatch('keydown', { key: 'Enter' });
popup = document.getElementById('ct_keybox_value_popup');
copyButton = findByClass(popup, 'ct-keybox-value-popup-copy');
assert.equal(document.activeElement, copyButton, 'opening by Enter must focus Copy');
findByClass(popup, 'ct-keybox-value-popup-close').dispatch('click');

value.dispatch('pointerdown');
assert.equal(timers.at(-1).delay, 650, 'long-press delay must remain unchanged');
timers.at(-1).callback();
popup = document.getElementById('ct_keybox_value_popup');
assert.ok(popup, 'long-press must continue opening the dialog');
findByClass(popup, 'ct-keybox-value-popup-close').dispatch('click');

let contextmenuPrevented = false;
value.dispatch('contextmenu', { preventDefault() { contextmenuPrevented = true; }, stopPropagation() {} });
assert.equal(contextmenuPrevented, true, 'contextmenu must be prevented');
popup = document.getElementById('ct_keybox_value_popup');
assert.ok(popup, 'contextmenu must open the dialog for mobile and desktop callers');
findByClass(popup, 'ct-keybox-value-popup-close').dispatch('click');

context.navigator = {};
context.showKeyboxValuePopup('Certificate', '5678', value);
popup = document.getElementById('ct_keybox_value_popup');
copyButton = findByClass(popup, 'ct-keybox-value-popup-copy');
assert.equal(document.activeElement, copyButton, 'clipboard fallback must preserve initial Copy focus');
findByClass(popup, 'ct-keybox-value-popup-close').dispatch('click');

console.log('Keybox value popup accessibility tests passed');
