const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const downloadId = '0123456789abcdef0123456789abcdef';
const body = 'staged clone response';
const encodedBody = Buffer.from(body, 'utf8').toString('base64url');
const envelope = JSON.stringify({
    version: 1,
    status: 200,
    statusText: '200 OK',
    mimeType: 'text/plain',
    size: Buffer.byteLength(body),
    downloadId
});

function createBridge() {
    let stageReads = 0;
    let stageDrops = 0;
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
        exec(command, _options, callbackName) {
            const callback = context[callbackName];
            if (command.includes("'stage-read'")) {
                stageReads += 1;
                callback(0, encodedBody, '');
            } else if (command.includes("'stage-drop'")) {
                stageDrops += 1;
                callback(0, '', '');
            } else if (command.includes("'call'")) {
                callback(0, envelope, '');
            } else {
                callback(1, '', `unexpected command: ${command}`);
            }
        },
        enableEdgeToEdge() {},
        enableInsets() {},
        listPackages() { return '[]'; }
    };
    vm.createContext(context);
    vm.runInContext(bridgeSource, context, { filename: 'bridge.js' });
    return {
        bridge: context.CleveresBridge,
        stats: () => ({ stageReads, stageDrops })
    };
}

async function main() {
    const { bridge, stats } = createBridge();
    const response = await bridge.fetch('/api/config');
    const clone = response.clone();

    assert.strictEqual(response.bodyUsed, false);
    assert.strictEqual(clone.bodyUsed, false);
    assert.strictEqual(await response.text(), body);
    assert.strictEqual(response.bodyUsed, true);
    assert.strictEqual(clone.bodyUsed, false);
    assert.strictEqual(stats().stageDrops, 0, 'shared stage must stay alive until every clone consumes it');

    assert.strictEqual(await clone.text(), body);
    assert.strictEqual(clone.bodyUsed, true);
    assert.deepStrictEqual(stats(), { stageReads: 2, stageDrops: 1 });

    await assert.rejects(() => response.text(), /already been consumed/);
    assert.throws(() => response.clone(), /Cannot clone a consumed response/);

    const bytesResponse = await bridge.fetch('/api/config');
    const bytes = await bytesResponse.bytes();
    assert.strictEqual(Buffer.from(bytes).toString('utf8'), body);
    assert.strictEqual(bytesResponse.bodyUsed, true, 'bytes() must consume the response body');
    await assert.rejects(() => bytesResponse.arrayBuffer(), /already been consumed/);

    console.log('Native WebUI staged response clone tests passed');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
