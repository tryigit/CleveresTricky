const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');

function sliceIife(marker, endMarker) {
  const start = source.indexOf(marker);
  const end = source.indexOf(endMarker, start);
  assert.ok(start >= 0 && end > start, `missing UX IIFE: ${marker}`);
  return source.slice(start, end + endMarker.length);
}

const zipSource = sliceIife('// ZIP keybox import UX;', '})(window);');
const keyboxSource = sliceIife('// Source-aware Stored Keyboxes and Verification UX.', '})(window);');

function createTimerContext(document) {
  const timers = [];
  const context = {
    console,
    document,
    setTimeout(callback, delay) {
      timers.push({ callback, delay });
      return timers.length;
    },
    clearTimeout() {},
    setInterval() { throw new Error('permanent polling is not allowed'); },
    clearInterval() {}
  };
  context.window = context;
  vm.createContext(context);
  return { context, timers };
}

function runScheduled(timers) {
  assert.ok(timers.length > 0, 'expected a scheduled installation callback');
  const timer = timers.shift();
  timer.callback();
  return timer;
}

function assertBoundedMissingPrerequisites(iifeSource, label) {
  const document = {
    readyState: 'complete',
    getElementById() { return null; },
    addEventListener() {}
  };
  const { context, timers } = createTimerContext(document);
  vm.runInContext(iifeSource, context, { filename: `${label}-missing.js` });

  const initial = runScheduled(timers);
  assert.strictEqual(initial.delay, 0, `${label} should defer its initial installation`);
  let retries = 0;
  while (timers.length > 0) {
    const timer = runScheduled(timers);
    assert.strictEqual(timer.delay, 50, `${label} retry delay must remain bounded and predictable`);
    retries += 1;
    assert.ok(retries <= 100, `${label} scheduled more than its 100 retry budget`);
  }
  assert.strictEqual(retries, 100, `${label} must use the finite 100-attempt retry budget`);
  assert.strictEqual(timers.length, 0, `${label} must stop scheduling when prerequisites never arrive`);
}

function element(tagName, parentNode = null) {
  return {
    tagName: tagName.toUpperCase(),
    id: '',
    hidden: false,
    disabled: false,
    checked: false,
    value: '',
    textContent: '',
    style: {},
    dataset: Object.create(null),
    children: [],
    parentNode,
    appendChild(child) { this.children.push(child); child.parentNode = this; return child; },
    append(...children) { children.forEach(child => this.appendChild(child)); },
    insertBefore(child) { this.children.unshift(child); child.parentNode = this; },
    insertAdjacentElement(_position, child) { this.parentNode?.appendChild(child); },
    addEventListener() {},
    setAttribute() {}
  };
}

(function testZipInstallsAfterLatePrerequisites() {
  const dropZoneParent = element('div');
  const dropZone = element('div', dropZoneParent);
  const picker = element('input');
  const nodes = new Map();
  const document = {
    readyState: 'complete',
    getElementById(id) { return nodes.get(id) || null; },
    addEventListener() {},
    createElement: tagName => element(tagName)
  };
  const { context, timers } = createTimerContext(document);
  vm.runInContext(zipSource, context, { filename: 'zip-late.js' });
  runScheduled(timers);
  assert.strictEqual(timers.length, 1, 'ZIP importer should wait for late prerequisites');

  nodes.set('kbFilePicker', picker);
  nodes.set('dropZone', dropZone);
  context.loadFileContent = () => {};
  runScheduled(timers);
  assert.strictEqual(timers.length, 0, 'ZIP importer must not keep retrying after installation');
  assert.strictEqual(picker.accept, '.xml,.cbox,.zip', 'ZIP importer should preserve late installation behavior');
}
)();

(function testKeyboxInstallsAfterLatePrerequisites() {
  const nodes = new Map();
  const document = {
    readyState: 'complete',
    getElementById(id) { return nodes.get(id) || null; },
    addEventListener() {},
    createElement: tagName => element(tagName)
  };
  const { context, timers } = createTimerContext(document);
  vm.runInContext(keyboxSource, context, { filename: 'keybox-late.js' });
  runScheduled(timers);
  assert.strictEqual(timers.length, 1, 'KeyboxHub should wait for late prerequisites');

  const listParent = element('div');
  const list = element('div', listParent);
  listParent.appendChild(list);
  nodes.set('storedKeyboxesList', list);
  const originalLoad = () => Promise.resolve();
  context.loadKeyboxes = originalLoad;
  runScheduled(timers);
  assert.strictEqual(timers.length, 0, 'KeyboxHub must not keep retrying after installation');
  assert.strictEqual(typeof context.renderKeyboxes, 'function', 'KeyboxHub should install its renderer after late prerequisites');
  assert.notStrictEqual(context.loadKeyboxes, originalLoad, 'KeyboxHub should retain its load wrapper after late installation');
}
)();

assertBoundedMissingPrerequisites(zipSource, 'zip-import');
assertBoundedMissingPrerequisites(keyboxSource, 'keybox-inventory');
console.log('WebUI UX installation retry regression tests passed');
