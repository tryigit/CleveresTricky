const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const indexSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const uxSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const uxBaseSource = fs.readFileSync('module/template/webroot/ux-base.js', 'utf8');

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

    assert.match(uxSource, /ux-base\.js\?revision=5/);
    assert.ok(!uxSource.includes('ux-patch.js'), 'The retired patch layer must not be loaded');
    assert.match(uxBaseSource, /\['en', 'English'\]/);
    assert.match(uxBaseSource, /\['tr', 'Türkçe'\]/);
    assert.match(uxBaseSource, /\['zh-CN', '简体中文'\]/);
    assert.match(uxBaseSource, /\['ru', 'Русский'\]/);
    assert.match(uxBaseSource, /\['id', 'Bahasa Indonesia'\]/);
    assert.match(uxBaseSource, /\['hi', 'हिन्दी'\]/);
    assert.match(uxBaseSource, /\['ar', 'العربية'\]/);
    assert.match(uxBaseSource, /document\.documentElement\.dir = locale === 'ar' \? 'rtl' : 'ltr'/);
    assert.match(uxBaseSource, /html\[dir="rtl"\]/);
    assert.match(uxBaseSource, /node\.nodeValue = leading \+ tr\(trimmed\) \+ trailing/);
    assert.match(uxBaseSource, /Identity is currently disabled\. You can enable it from Dashboard\./);
    assert.match(uxBaseSource, /ct_language_panel/);
    assert.match(uxBaseSource, /To add a locale:/);
    assert.match(uxBaseSource, /const featureCenter = document\.getElementById\('ct_dashboard_controls'\)/);
    assert.match(uxBaseSource, /ct_debug_panel/);
    assert.match(uxBaseSource, /All major features and runtime paths in one place\./);
    assert.ok(!/setInterval\s*\(/.test(uxBaseSource), 'UX presentation must not add permanent polling');

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
    assert.ok(indexSource.includes('<script src="bridge.js?revision=7"></script>'));
    assert.match(bridgeSource, /ux\.js\?revision=5/);
    assert.ok(!bridgeSource.includes('ux.js?revision=3'), 'Bridge must not request the retired cached UX loader');

    console.log('Native WebUI bridge compatibility tests passed');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
