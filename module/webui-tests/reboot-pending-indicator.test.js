const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');

function makeInput(id, feature = '') {
    const classes = new Set();
    return {
        id,
        type: 'checkbox',
        checked: false,
        dataset: feature ? { policyFeature: feature } : {},
        parentElement: null,
        classList: {
            add(name) { classes.add(name); },
            remove(name) { classes.delete(name); },
            contains(name) { return classes.has(name); }
        },
        setAttribute() {},
        removeAttribute() {},
        closest() { return this.parentElement; },
        hasPendingClass() { return classes.has('pending-reboot'); }
    };
}

function nextTick() {
    return new Promise(resolve => setImmediate(resolve));
}

function runWithBoot(storageData, bootId, input) {
    const changeListeners = [];
    const storage = new Map(Object.entries(storageData));
    const documentElement = { dataset: {} };
    const document = {
        documentElement,
        body: {},
        head: { appendChild() {} },
        addEventListener(type, listener) {
            if (type === 'change') changeListeners.push(listener);
        },
        querySelectorAll(selector) {
            assert.strictEqual(selector, 'input[type="checkbox"]');
            return [input];
        },
        createElement() { return { src: '', defer: false }; }
    };

    const context = {
        document,
        localStorage: {
            getItem(key) { return storage.has(key) ? storage.get(key) : null; },
            setItem(key, value) { storage.set(key, value); }
        },
        setTimeout(fn) { fn(); return 1; },
        Math, JSON, Date, Promise, Set, Array, console,
        ksu: {
            exec(command, _options, callbackName) {
                assert.strictEqual(command, 'cat /proc/sys/kernel/random/boot_id');
                context[callbackName]({ stdout: bootId });
            }
        },
        CleveresBridge: null
    };
    context.window = context;

    vm.runInNewContext(source, context, { filename: 'ux.js' });
    return {
        changeListeners,
        storage,
        fireChange() { changeListeners.forEach(listener => listener({ target: input })); }
    };
}

(async () => {
    const bootA = '7f9a9f5b-4e28-4f2a-8f53-bc4d8fd0d9e1';
    const bootB = '0f1e2d3c-4b5a-6978-8f90-1a2b3c4d5e6f';
    const input = makeInput('ct_dash_global_identity');
    input.parentElement = { textContent: 'Global Identity Requires reboot' };
    const first = runWithBoot({}, bootA, input);
    await nextTick();
    assert.strictEqual(input.hasPendingClass(), false, 'a reboot marker must not appear before a change');

    first.fireChange();
    await nextTick();
    assert.strictEqual(input.hasPendingClass(), true, 'Global Identity must become yellow immediately after a change');
    const persisted = JSON.parse(first.storage.get('cleverestricky.pending_reboot.v1'));
    assert.deepStrictEqual(persisted.settings, ['setting:global_identity_mode']);
    assert.strictEqual(persisted.bootId, bootA);

    const secondInput = makeInput('ct_other_global_identity');
    secondInput.parentElement = { textContent: 'Global Identity Requires reboot' };
    runWithBoot(Object.fromEntries(first.storage), bootA, secondInput);
    await nextTick();
    assert.strictEqual(secondInput.hasPendingClass(), true, 'the marker must survive a new WebUI session');

    const rebootedInput = makeInput('ct_dash_global_identity');
    rebootedInput.parentElement = { textContent: 'Global Identity Requires reboot' };
    const afterReboot = runWithBoot(Object.fromEntries(first.storage), bootB, rebootedInput);
    await nextTick();
    assert.strictEqual(rebootedInput.hasPendingClass(), false, 'the marker must clear when Android boot_id changes');
    const cleared = JSON.parse(afterReboot.storage.get('cleverestricky.pending_reboot.v1'));
    assert.deepStrictEqual(cleared.settings, []);
    assert.strictEqual(cleared.bootId, bootB);

    console.log('Reboot-pending indicator persistence checks passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
