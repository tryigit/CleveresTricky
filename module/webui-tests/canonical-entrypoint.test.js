const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const indexPath = path.resolve(process.cwd(), 'module/template/webroot/index.html');
const html = fs.readFileSync(indexPath, 'utf8');

function elementOpeningTag(id) {
    const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const match = html.match(new RegExp(`<[^>]+\\bid=["']${escaped}["'][^>]*>`, 'i'));
    assert.ok(match, `canonical index must contain #${id}`);
    return match[0];
}

function makeClassList(className) {
    const values = new Set(String(className || '').split(/\s+/).filter(Boolean));
    return {
        add(value) { values.add(value); },
        remove(value) { values.delete(value); },
        contains(value) { return values.has(value); }
    };
}

function makeDomElement(id, className) {
    const attributes = {};
    return {
        id,
        classList: makeClassList(className),
        setAttribute(name, value) { attributes[name] = String(value); },
        getAttribute(name) { return attributes[name]; },
        value: '',
        className,
        focus() {}
    };
}

function loadSwitchTab() {
    const tabIds = ['dashboard', 'spoof', 'apps', 'keys', 'info', 'guide', 'log', 'editor', 'donate'];
    const tabs = tabIds.map(id => makeDomElement(`tab_${id}`, id === 'dashboard' ? 'tab active' : 'tab'));
    const contents = tabIds.map(id => makeDomElement(id, id === 'dashboard' ? 'content active' : 'content'));
    const elements = Object.fromEntries([...tabs, ...contents].map(element => [element.id, element]));
    const document = {
        querySelectorAll(selector) {
            if (selector === '.tab') return tabs;
            if (selector === '.content') return contents;
            return [];
        },
        getElementById(id) {
            return elements[id] || null;
        }
    };
    const window = {
        matchMedia() { return { matches: false }; },
        scrollTo() {},
        cancelKeyboxVerification: null
    };
    const start = html.indexOf('function switchTab(id) {');
    const end = html.indexOf('function handleTabNavigation', start);
    assert.ok(start >= 0 && end > start, 'canonical switchTab controller must remain discoverable');
    const source = html.slice(start, end);
    const context = {
        document,
        window,
        console: { log() {}, warn() {}, error() {} },
        notify() {},
        updateSaveButtonState() {},
        loadAppConfig() {},
        loadKeyInfo() {},
        loadResourceUsage() {},
        AbortController,
        setTimeout,
        clearTimeout,
        currentFile: null,
        editorUnsavedBypass: false,
        originalContent: '',
        logsRequestController: null,
        keyInfoController: null,
        serverListController: null,
        keyboxListController: null,
        appConfigController: null,
        editorFileController: null,
        resourceUsageController: null
    };
    vm.runInNewContext(`${source}\nthis.__switchTab = switchTab;`, context, { filename: 'index.html#switchTab' });
    assert.strictEqual(typeof context.__switchTab, 'function', 'switchTab must be executable in the canonical DOM context');
    return { context, tabs, contents };
}

function testCanonicalInitialStateAndRuntimeSurface() {
    const dashboard = elementOpeningTag('dashboard');
    const spoof = elementOpeningTag('spoof');
    const dashboardTab = elementOpeningTag('tab_dashboard');
    assert.match(dashboard, /class=["'][^"']*\bcontent\b[^"']*\bactive\b/i, 'Dashboard must be the clean direct-open panel');
    assert.doesNotMatch(spoof, /class=["'][^"']*\bactive\b/i, 'Identity must not be the direct-open panel');
    assert.match(dashboardTab, /aria-selected=["']true["']/i, 'Dashboard tab must be selected on direct open');
    assert.doesNotMatch(html, /index\.(php|phtml|htm)(?:["'\s<]|$)|(?:ux-core|zip-import|ux-base|ux-patch)\.js/i, 'canonical entrypoint must not reference retired legacy files');

    const scriptSources = [...html.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["'][^>]*>/gi)].map(match => match[1].split('?')[0]);
    assert.deepStrictEqual(scriptSources, ['bridge.js', 'policy.js'], 'canonical index must statically load only the bridge and policy owners');
    assert.strictEqual((html.match(/<script\b(?![^>]*\bsrc=)[^>]*>/gi) || []).length, 1, 'canonical index must have one inline controller block');
    const bridgeSource = fs.readFileSync(path.resolve(process.cwd(), 'module/template/webroot/bridge.js'), 'utf8');
    assert.match(bridgeSource, /script\.src = ['"]ux\.js\?revision=[^'"]+['"]/i, 'bridge must dynamically load the single canonical UX owner');
    assert.doesNotMatch(bridgeSource, /(?:ux-core|zip-import|ux-base|ux-patch)\.js/i, 'bridge must not load retired UX bundles');

    assert.match(html, /\.panel-hero\s*\{/i, 'direct-open dashboard must expose the modern hero surface');
    assert.doesNotMatch(html, /class=["'][^"']*\bstatus-grid\b/i, 'direct-open dashboard must not contain obsolete status-grid');
    const mobileStart = html.lastIndexOf('@media screen and (max-width: 700px)');
    assert.ok(mobileStart >= 0, 'canonical index must keep a mobile-specific layout contract');
    const mobileBlock = html.slice(mobileStart);
    assert.match(mobileBlock, /\.tabs\s*\{[\s\S]*?position:\s*fixed/i, 'mobile navigation must be fixed to remain reachable');
    assert.match(mobileBlock, /\.tabs\s*\{[\s\S]*?flex-direction:\s*row/i, 'mobile navigation must become a horizontal touch surface');
}

function testSwitchTabBehavior() {
    const { context, tabs, contents } = loadSwitchTab();
    context.__switchTab('spoof');
    assert.strictEqual(tabs[0].classList.contains('active'), false, 'switchTab must deactivate the previous tab');
    assert.strictEqual(tabs[1].classList.contains('active'), true, 'switchTab must activate the requested tab');
    assert.strictEqual(tabs[1].getAttribute('aria-selected'), 'true', 'switchTab must update aria-selected');
    assert.strictEqual(tabs[1].getAttribute('tabindex'), '0', 'switchTab must move keyboard focus entry to the requested tab');
    assert.strictEqual(contents[0].classList.contains('active'), false, 'switchTab must hide the previous panel');
    assert.strictEqual(contents[1].classList.contains('active'), true, 'switchTab must show the requested panel');
}

function loadFileFailureHarness() {
    const tabIds = ['dashboard', 'spoof', 'apps', 'keys', 'info', 'guide', 'log', 'editor', 'donate'];
    const tabs = tabIds.map(id => makeDomElement(`tab_${id}`, id === 'dashboard' ? 'tab active' : 'tab'));
    const contents = tabIds.map(id => makeDomElement(id, id === 'dashboard' ? 'content active' : 'content'));
    const fileSelector = makeDomElement('fileSelector');
    fileSelector.value = 'target.txt';
    const editor = makeDomElement('fileEditor');
    editor.value = '';
    editor.disabled = false;
    const elements = Object.fromEntries([...tabs, ...contents, fileSelector, editor].map(element => [element.id, element]));
    const notifications = [];
    const document = {
        querySelectorAll(selector) {
            if (selector === '.tab') return tabs;
            if (selector === '.content') return contents;
            return [];
        },
        getElementById(id) {
            return elements[id] || null;
        }
    };
    const context = {
        document,
        window: { matchMedia() { return { matches: false }; }, scrollTo() {}, cancelKeyboxVerification: null },
        console: { log() {}, warn() {}, error() {} },
        notify(message) { notifications.push(message); },
        updateSaveButtonState() {},
        fetchAuth: async () => ({ ok: false, status: 503 }),
        AbortController,
        setTimeout,
        clearTimeout,
        currentFile: '',
        editorUnsavedBypass: false,
        originalContent: '',
        logsRequestController: null,
        keyInfoController: null,
        serverListController: null,
        keyboxListController: null,
        appConfigController: null,
        editorFileController: null,
        resourceUsageController: null
    };
    const loadStart = html.indexOf('async function loadFile() {');
    const loadEnd = html.indexOf('async function handleSave', loadStart);
    assert.ok(loadStart >= 0 && loadEnd > loadStart, 'loadFile implementation is missing');
    const switchStart = html.indexOf('function switchTab(id) {');
    const switchEnd = html.indexOf('function handleTabNavigation', switchStart);
    vm.runInNewContext(`${html.slice(loadStart, loadEnd)}\n${html.slice(switchStart, switchEnd)}\nthis.__loadFile = loadFile;\nthis.__switchTab = switchTab;`, context, { filename: 'index.html#file-failure' });
    return { context, editor, fileSelector, tabs, contents, notifications };
}

async function testDirtyEditorGuardDoesNotLeaveAStaleRequestOwner() {
    const { context, editor, fileSelector, notifications } = loadFileFailureHarness();
    context.currentFile = 'previous.txt';
    context.originalContent = 'previous content';
    editor.value = 'unsaved content';
    await context.__loadFile();
    assert.strictEqual(context.editorFileController, null, 'dirty-editor rejection must not leave a stale request owner');
    assert.strictEqual(fileSelector.value, 'previous.txt', 'dirty-editor rejection must restore the previously loaded filename');
    assert.strictEqual(editor.value, 'unsaved content', 'dirty-editor rejection must preserve user input');
    assert.ok(notifications.some(message => message === 'You have unsaved changes. Select file again to discard.'), 'dirty-editor rejection must remain visible to the user');
}

async function testBackendFileFailureDoesNotCreateUnsavedEditorGuard() {
    const { context, editor, tabs, contents, notifications } = loadFileFailureHarness();
    await context.__loadFile();
    assert.strictEqual(context.currentFile, '', 'failed initial file load must not claim an editable file');
    assert.strictEqual(context.originalContent, '', 'failed initial file load must clear the comparison baseline');
    assert.strictEqual(editor.value, '', 'failed initial file load must not leave an error placeholder as editable content');
    assert.strictEqual(editor.disabled, true, 'failed initial file load must leave the editor disabled');
    context.__switchTab('spoof');
    assert.strictEqual(tabs[0].classList.contains('active'), false, 'tab navigation must remain available after backend failure');
    assert.strictEqual(tabs[1].classList.contains('active'), true, 'Identity must be reachable after backend failure');
    assert.strictEqual(contents[0].classList.contains('active'), false, 'Dashboard must be hidden after Identity navigation');
    assert.strictEqual(contents[1].classList.contains('active'), true, 'Identity panel must be shown after navigation');
    assert.ok(notifications.some(message => message === 'Failed to load file'), 'backend file failure must remain visible to the user');
}

(async () => {
    testCanonicalInitialStateAndRuntimeSurface();
    testSwitchTabBehavior();
    await testDirtyEditorGuardDoesNotLeaveAStaleRequestOwner();
    await testBackendFileFailureDoesNotCreateUnsavedEditorGuard();
    console.log('Canonical WebUI entrypoint, switchTab, and startup failure checks passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
