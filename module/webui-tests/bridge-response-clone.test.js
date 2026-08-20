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

function createBridge({ failRead = false } = {}) {
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
                if (failRead) callback(1, '', 'staged read failed');
                else callback(0, encodedBody, '');
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
    {
        const { bridge, stats } = createBridge();
        const response = await bridge.fetch('/api/config');
        const abandonedClone = response.clone();

        abandonedClone.headers.set('x-clone-only', 'clone');
        assert.strictEqual(response.headers.get('x-clone-only'), null, 'clone header mutations must not affect the source response');
        response.headers.set('x-source-only', 'source');
        assert.strictEqual(abandonedClone.headers.get('x-source-only'), null, 'source header mutations must not affect the clone');

        assert.strictEqual(await response.text(), body);
        assert.strictEqual(response.bodyUsed, true);
        assert.strictEqual(abandonedClone.bodyUsed, false);
        assert.deepStrictEqual(
            stats(),
            { stageReads: 1, stageDrops: 1 },
            'the staged file must be released after the first materialization even when a clone is abandoned'
        );

        assert.strictEqual(await abandonedClone.text(), body, 'an unconsumed clone must remain readable from shared materialized bytes');
        assert.deepStrictEqual(stats(), { stageReads: 1, stageDrops: 1 }, 'later clone consumption must not re-read or re-drop the stage');
        await assert.rejects(() => response.text(), /already been consumed/);
        assert.throws(() => response.clone(), /Cannot clone a consumed response/);
    }

    {
        const { bridge, stats } = createBridge();
        const response = await bridge.fetch('/api/config');
        const clone = response.clone();
        const [sourceText, cloneText] = await Promise.all([response.text(), clone.text()]);

        assert.strictEqual(sourceText, body);
        assert.strictEqual(cloneText, body);
        assert.deepStrictEqual(
            stats(),
            { stageReads: 1, stageDrops: 1 },
            'concurrent clone consumption must coalesce to one staged read and one cleanup'
        );
    }

    {
        const { bridge, stats } = createBridge({ failRead: true });
        const response = await bridge.fetch('/api/config');
        const clone = response.clone();

        await assert.rejects(() => response.text(), /staged read failed/);
        assert.deepStrictEqual(stats(), { stageReads: 1, stageDrops: 1 }, 'failed staged reads must still clean up exactly once');
        await assert.rejects(() => clone.text(), /staged read failed/);
        assert.deepStrictEqual(
            stats(),
            { stageReads: 1, stageDrops: 1 },
            'a shared staged-read failure must be memoized instead of repeating I/O or cleanup'
        );
    }

    {
        const { bridge } = createBridge();
        const bytesResponse = await bridge.fetch('/api/config');
        const bytes = await bytesResponse.bytes();
        assert.strictEqual(Buffer.from(bytes).toString('utf8'), body);
        assert.strictEqual(bytesResponse.bodyUsed, true, 'bytes() must consume the response body');
        await assert.rejects(() => bytesResponse.arrayBuffer(), /already been consumed/);
    }

    console.log('Native WebUI staged response clone tests passed');
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
