const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');

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

function createBridge(callbackFactory) {
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
    context.window = context;
    context.ksu = {
        exec(_command, _options, callbackName) {
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

async function main() {
    const raw = envelope();

    for (const callbackFactory of [
        callback => callback(0, raw, ''),
        callback => callback(raw),
        callback => callback({ errno: 0, stdout: raw, stderr: '' }),
        callback => callback(JSON.stringify({ errno: 0, stdout: raw, stderr: '' }))
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

    console.log('Native WebUI bridge compatibility tests passed');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
