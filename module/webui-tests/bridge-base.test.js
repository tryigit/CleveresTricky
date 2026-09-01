const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const indexSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const uxSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');

function encodeBody(value) {
    return Buffer.from(value, 'utf8').toString('base64url');
}

function envelope(body = '{"status":"ok"}') {
    return JSON.stringify({
        version: 1,
        status: 200,
        statusText: '200 OK',
        mimeType: 'application/json',
        size: Buffer.byteLength(body),
        body: encodeBody(body)
    });
}

function createElement(tagName) {
    return {
        tagName: tagName.toUpperCase(),
        id: '',
        style: {},
        children: [],
        attributes: Object.create(null),
        appendChild(child) { this.children.push(child); return child; },
        setAttribute(name, value) { this.attributes[name] = String(value); }
    };
}

function createDocument() {
    const body = createElement('body');
    const dashboard = createElement('div');
    dashboard.id = 'dashboard';
    body.appendChild(dashboard);
    return {
        readyState: 'complete',
        documentElement: { dataset: Object.create(null) },
        body,
        createElement,
        getElementById(id) {
            const queue = [...body.children];
            while (queue.length) {
                const node = queue.shift();
                if (node.id === id) return node;
                queue.push(...node.children);
            }
            return null;
        },
        addEventListener() {}
    };
}

function createBridge(callbackFactory, document = null, commandObserver = null) {
    const context = {
        console,
        setTimeout,
        clearTimeout,
        URL,
        URLSearchParams,
        TextEncoder,
        TextDecoder,
        Uint8Array,
        ArrayBuffer,
        Blob,
        Headers,
        FormData,
        File: globalThis.File || class File extends Blob {},
        DOMException,
        atob: value => Buffer.from(value, 'base64').toString('binary'),
        btoa: value => Buffer.from(value, 'binary').toString('base64')
    };
    if (document) context.document = document;
    context.window = context;
    context.ksu = {
        exec(command, _options, callbackName) {
            if (commandObserver) commandObserver(command);
            callbackFactory(context[callbackName]);
        },
        enableEdgeToEdge() {},
        enableInsets() {},
        listPackages() { return '[]'; }
    };
    vm.createContext(context);
    vm.runInContext(bridgeSource, context, { filename: 'bridge.js' });
    return context.CleveresBridge;
}

function loadMessageNormalizer() {
    const start = indexSource.indexOf('const maxEncodedUiMessageLength');
    const end = indexSource.indexOf('\n        function notify', start);
    assert.ok(start >= 0 && end > start, 'UI message normalizer source is missing');
    const context = {
        TextDecoder,
        Uint8Array,
        atob: value => Buffer.from(value, 'base64').toString('binary')
    };
    vm.createContext(context);
    vm.runInContext(`${indexSource.slice(start, end)}; this.normalizeUiMessage = normalizeUiMessage;`, context);
    return context.normalizeUiMessage;
}

async function main() {
    const raw = envelope();

    for (const callbackFactory of [
        callback => callback(0, raw, ''),
        callback => callback(raw),
        callback => callback({ errno: 0, stdout: raw, stderr: '' }),
        callback => callback(JSON.stringify({ errno: 0, stdout: raw, stderr: '' })),
        callback => callback(JSON.parse(raw)),
        callback => callback(1, raw, ''),
        callback => callback(1, '', raw),
        callback => callback(raw, '', 0),
        callback => callback({ errno: 1, stderr: raw }),
        callback => callback(new Error(raw))
    ]) {
        const bridge = createBridge(callbackFactory);
        const response = await bridge.fetch('/api/config');
        assert.strictEqual(response.status, 200);
        assert.strictEqual(response.ok, true);
        assert.strictEqual(JSON.stringify(await response.json()), JSON.stringify({ status: 'ok' }));
    }

    const failing = createBridge(callback => callback(5, '', 'permission denied'));
    await assert.rejects(() => failing.fetch('/api/config'), /permission denied/);

    let rawCommand = '';
    const rawCallbackBridge = createBridge(
        callback => {
            if (rawCommand.includes("'stage-create'")) {
                callback(1, '', '0123456789abcdef0123456789abcdef\n__CT_NATIVE_OK__');
            } else if (rawCommand.includes("'stage-append'")) {
                callback(1, '', '__CT_NATIVE_OK__');
            } else if (rawCommand.includes("'export'")) {
                callback(1, '', '/storage/emulated/0/Download/bridge-test.bin\n__CT_NATIVE_OK__');
            } else {
                callback(5, '', 'unexpected command');
            }
        },
        null,
        command => { rawCommand = command; }
    );
    const exportedPath = await rawCallbackBridge.exportBlob(new Blob([Uint8Array.from([1, 2, 3])]), 'bridge-test.bin');
    assert.strictEqual(exportedPath, '/storage/emulated/0/Download/bridge-test.bin', 'validated success marker must win over manager-specific callback status/channel ordering');
    assert.match(rawCommand, /__CT_NATIVE_OK__/, 'raw native commands must append an authenticated-by-exit-status success marker');

    let rawFailureCommand = '';
    const rawFailureBridge = createBridge(
        callback => callback(1, '', 'permission denied'),
        null,
        command => { rawFailureCommand = command; }
    );
    await assert.rejects(() => rawFailureBridge.exportBlob(new Blob([Uint8Array.from([1])]), 'bridge-fail.bin'), /permission denied/);
    assert.match(rawFailureCommand, /__CT_NATIVE_OK__/, 'raw failures must still use the marker-aware command wrapper');

    let delayedCallback = null;
    const aborting = createBridge(callback => { delayedCallback = callback; });
    const abortController = new AbortController();
    const abortedRequest = aborting.fetch('/api/config', { signal: abortController.signal });
    await new Promise(resolve => setTimeout(resolve, 0));
    abortController.abort();
    await assert.rejects(abortedRequest, error => error && error.name === 'AbortError');
    if (delayedCallback) delayedCallback(0, raw, '');

    const malformed = createBridge(callback => callback('{"version":1,"status":200}'));
    await assert.rejects(() => malformed.fetch('/api/config'), /Invalid response/);

    const unsupported = createBridge(callback => callback({ unexpected: true }));
    await assert.rejects(() => unsupported.fetch('/api/config'), /Unsupported native exec result/);

    const incompleteErrorEnvelope = createBridge(callback => callback(1, '', '{"version":1,"status":200}'));
    await assert.rejects(() => incompleteErrorEnvelope.fetch('/api/config'), /version.*status|Native bridge failed|Invalid response/i);

    const emptyListRaw = envelope('[]');
    const errorChannelSuccess = createBridge(callback => callback(1, '', emptyListRaw));
    const emptyListResponse = await errorChannelSuccess.fetch('/api/keyboxes');
    assert.strictEqual(emptyListResponse.ok, true);
    assert.deepStrictEqual(Array.from(await emptyListResponse.json()), []);

    const bootPolicyBridge = createBridge(callback => callback(1, '', envelope('auto\n')));
    const bootPolicyResponse = await bootPolicyBridge.fetch('/api/file?filename=boot_props_mode');
    assert.strictEqual((await bootPolicyResponse.text()).trim(), 'auto');

    const resourceBody = JSON.stringify({ real_ram_kb: 4096, real_cpu: 1.5, environment: 'KernelSU' });
    const resourceBridge = createBridge(callback => callback(1, envelope(resourceBody), ''));
    const resourceResponse = await resourceBridge.fetch('/api/resource_usage');
    assert.deepStrictEqual(
        JSON.parse(JSON.stringify(await resourceResponse.json())),
        JSON.parse(resourceBody)
    );

    let profileMutationCommand = '';
    const profileState = JSON.stringify({
        features: {},
        profiles: [{ name: 'Daily', enabled: false }]
    });
    const profileBridge = createBridge(
        callback => callback(0, envelope(profileState), ''),
        null,
        command => { profileMutationCommand = command; }
    );
    const updatedProfileState = await profileBridge.setProfileEnabled({ name: 'Daily', enabled: true }, false);
    assert.strictEqual(updatedProfileState.profiles[0].enabled, false, 'profile toggle must use the canonical response state');
    const encodedProfileRequest = profileMutationCommand.match(/'call' '([^']+)'/);
    assert.ok(encodedProfileRequest, 'profile toggle must issue a native bridge call');
    const profileRequest = JSON.parse(Buffer.from(encodedProfileRequest[1], 'base64url').toString('utf8'));
    assert.strictEqual(profileRequest.path, '/api/profile_v2');
    assert.strictEqual(profileRequest.parameters.action[0], 'edit');
    assert.strictEqual(JSON.parse(profileRequest.parameters.data[0]).profile.enabled, false);

    let communityCommand = '';
    const communityBridge = createBridge(
        callback => callback(0, '', ''),
        null,
        command => { communityCommand = command; }
    );
    await communityBridge.openCommunity();
    assert.match(communityCommand, /android\.intent\.action\.VIEW/);
    assert.match(communityCommand, /android\.intent\.category\.BROWSABLE/);
    assert.match(communityCommand, /https:\/\/t\.me\/cleverestech/);
    assert.match(communityCommand, /-p com\.android\.chrome/);
    assert.ok(!communityCommand.includes('tg:'), 'Telegram custom schemes must never be used from KernelSU WebUI');

    let keyboxHubCommand = '';
    const keyboxHubBridge = createBridge(
        callback => callback(0, '', ''),
        null,
        command => { keyboxHubCommand = command; }
    );
    await keyboxHubBridge.openKeyboxHub();
    assert.match(keyboxHubCommand, /android\.intent\.action\.VIEW/);
    assert.match(keyboxHubCommand, /android\.intent\.category\.BROWSABLE/);
    assert.match(keyboxHubCommand, /https:\/\/keybox\.tryigit\.dev\//);
    assert.match(keyboxHubCommand, /-p com\.android\.chrome/);

    const communityDocument = createDocument();
    createBridge(() => {}, communityDocument);
    const communityCard = communityDocument.getElementById('cleveresCommunityCard');
    assert.ok(communityCard, 'Telegram community card was not appended');
    const communityDashboard = communityDocument.getElementById('dashboard');
    assert.strictEqual(communityDashboard.children.at(-1), communityCard, 'Community card must stay at the bottom of Dashboard');
    assert.ok(!communityDocument.body.children.includes(communityCard), 'Community card must never be a global body-level widget');
    const communityPanel = communityCard.children[0];
    const communityCopy = communityPanel.children[1];
    const communityLink = communityPanel.children[2];
    assert.match(communityCopy.textContent, /mutual help.*development/i);
    assert.strictEqual(communityLink.href, 'https://t.me/cleverestech');
    assert.strictEqual(communityLink.target, '_blank');
    assert.strictEqual(communityLink.rel, 'noopener noreferrer');
    assert.strictEqual(communityLink.textContent, 'Join Telegram Community');

    assert.ok(!uxSource.includes('ux-base.js'), 'ux.js must contain the UX implementation directly');
    assert.ok(!uxSource.includes('ux-patch.js'), 'The retired patch layer must not be loaded');
    assert.match(uxSource, /\['en', 'English'\]/);
    assert.match(uxSource, /\['tr', 'Türkçe'\]/);
    assert.match(uxSource, /\['zh-CN', '简体中文'\]/);
    assert.match(uxSource, /\['ru', 'Русский'\]/);
    assert.match(uxSource, /\['id', 'Bahasa Indonesia'\]/);
    assert.match(uxSource, /\['hi', 'हिन्दी'\]/);
    assert.match(uxSource, /\['ar', 'العربية'\]/);
    assert.match(uxSource, /document\.documentElement\.dir = locale === 'ar' \? 'rtl' : 'ltr'/);
    assert.match(uxSource, /html\[dir="rtl"\]/);
    assert.match(uxSource, /record\.source = current/);
    assert.match(uxSource, /if \(current !== rendered\) node\.nodeValue = rendered/);
    assert.match(uxSource, /new global\.MutationObserver/);
    assert.match(uxSource, /attributeFilter: \['placeholder','title','aria-label','data-label','data-i18n'\]/);
    assert.match(uxSource, /Identity is currently disabled\. You can enable it from Dashboard\./);
    assert.match(uxSource, /ct_language_panel/);
    assert.match(uxSource, /To add a locale:/);
    assert.match(uxSource, /const featureCenter = document\.getElementById\('ct_dashboard_controls'\)/);
    assert.match(uxSource, /ct_debug_panel/);
    assert.match(uxSource, /All major features and runtime paths in one place\./);
    assert.ok(!/setInterval\s*\(/.test(uxSource), 'UX presentation must not add permanent polling');

    assert.match(bridgeSource, /const nativeSuccessMarker = '__CT_NATIVE_OK__'/);
    assert.match(bridgeSource, /nativeFilePickerIds = new Set\(\['kbFilePicker', 'restoreInput'\]\)/);
    assert.match(bridgeSource, /input\.accept = '\*\/\*'/);

    const normalizeUiMessage = loadMessageNormalizer();
    assert.strictEqual(normalizeUiMessage(envelope('{"error":"keybox rejected"}')), 'keybox rejected');
    assert.strictEqual(normalizeUiMessage('<img src=x onerror=alert(1)>'), '<img src=x onerror=alert(1)>');
    const oversized = JSON.stringify({
        version: 1,
        status: 500,
        statusText: 'Server Error',
        body: 'A'.repeat(16 * 1024 + 1)
    });
    assert.strictEqual(normalizeUiMessage(oversized), 'HTTP 500 Server Error: response body is too large to display');
    assert.ok(indexSource.includes('text.textContent = normalizeUiMessage(msg);'));
    assert.ok(indexSource.includes('<script src="bridge.js?revision=14"></script>'));
    assert.match(bridgeSource, /ux\.js\?revision=9/);
    assert.ok(!bridgeSource.includes('ux.js?revision=3'), 'Bridge must not request the retired cached UX loader');

    const uploadFunctionStart = indexSource.indexOf('async function loadFileContent');
    const uploadFunctionEnd = indexSource.indexOf('\n        function resetDropZone', uploadFunctionStart);
    assert.ok(uploadFunctionStart >= 0 && uploadFunctionEnd > uploadFunctionStart, 'upload handler source is missing');
    const uploadNodes = new Map([
        ['dropZoneContent', { innerHTML: '', style: {} }],
        ['dropZone', { style: {} }],
        ['kbContent', { value: '' }],
        ['keyboxStatus', { innerText: '' }]
    ]);
    class FakeFile {
        constructor(name, size) { this.name = name; this.size = size; }
    }
    class FakeFormData {
        append() {}
    }
    const uploadDocument = {
        createElement() {
            let value = '';
            return {
                set innerText(next) { value = String(next); },
                get innerHTML() {
                    return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
                }
            };
        },
        getElementById(id) { return uploadNodes.get(id) || null; }
    };
    const uploadContext = {
        console,
        File: FakeFile,
        FormData: FakeFormData,
        document: uploadDocument,
        setTimeout() {},
        notify() {},
        loadKeyInfo() {},
        loadKeyboxes() {},
        resetDropZone() {},
        fetchAuth: async () => ({
            ok: true,
            clone: () => ({ json: async () => ({ filename: 'keybox_1.xml', keybox_count: 1 }) })
        })
    };
    vm.createContext(uploadContext);
    vm.runInContext(`${indexSource.slice(uploadFunctionStart, uploadFunctionEnd)}; this.loadFileContent = loadFileContent;`, uploadContext, { filename: 'index-upload-handler.js' });
    await uploadContext.loadFileContent(new FakeFile('keybox (1).xml', 64));
    assert.match(uploadNodes.get('dropZoneContent').innerHTML, /OK - keybox_1\.xml/);
    assert.ok(!uploadNodes.get('dropZoneContent').innerHTML.includes('keybox (1).xml'), 'UI must show the effective stored filename');

    const escapingContext = {
        ...uploadContext,
        fetchAuth: async () => ({
            ok: true,
            clone: () => ({ json: async () => ({ filename: '<img src=x onerror=alert(1)>', keybox_count: 1 }) })
        })
    };
    vm.createContext(escapingContext);
    vm.runInContext(`${indexSource.slice(uploadFunctionStart, uploadFunctionEnd)}; this.loadFileContent = loadFileContent;`, escapingContext, { filename: 'index-upload-handler-escaping.js' });
    await escapingContext.loadFileContent(new FakeFile('safe.xml', 64));
    assert.ok(escapingContext.document.getElementById('dropZoneContent').innerHTML.includes('&lt;img'), 'effective filename must be HTML-escaped');
    assert.ok(!escapingContext.document.getElementById('dropZoneContent').innerHTML.includes('<img'), 'effective filename must not create markup');

    console.log('Native WebUI bridge compatibility tests passed');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
