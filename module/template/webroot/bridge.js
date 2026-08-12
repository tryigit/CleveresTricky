(function (global) {
    'use strict';

    const nativeApi = global.ksu;
    const modulePaths = [
        '/data/adb/modules/cleverestricky/webui_bridge',
        '/data/adb/ksu/modules/cleverestricky/webui_bridge',
        '/data/adb/ap/modules/cleverestricky/webui_bridge'
    ];
    const chunkBytes = 48 * 1024;
    const maxUploadBytes = 20 * 1024 * 1024;
    const maxResponseBytes = 20 * 1024 * 1024;
    const maxEnvelopeChars = 1024 * 1024;
    const responseFields = new Set(['version', 'status', 'statusText', 'mimeType', 'size', 'body', 'downloadId']);
    const communityUrl = 'https://t.me/cleverestech';
    const debugFlag = '/data/adb/cleverestricky/debug_logging';
    let callbackCounter = 0;

    function encodeBytes(bytes) {
        let binary = '';
        for (let offset = 0; offset < bytes.length; offset += 0x8000) {
            binary += String.fromCharCode.apply(null, bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
        }
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    }

    function decodeBytes(value) {
        if (typeof value !== 'string' || !/^[A-Za-z0-9_-]*$/.test(value)) throw new Error('Invalid bridge payload');
        const padded = value.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((value.length + 3) % 4);
        const binary = atob(padded);
        const bytes = new Uint8Array(binary.length);
        for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
        return bytes;
    }

    function encodeText(value) {
        return encodeBytes(new TextEncoder().encode(value));
    }

    function shellCommand(args) {
        if (!args.every(value => typeof value === 'string' && /^[A-Za-z0-9_-]+$/.test(value))) {
            throw new Error('Invalid bridge argument');
        }
        const paths = modulePaths.map(path => `'${path}'`).join(' ');
        return `CT_BRIDGE=''; for CT_PATH in ${paths}; do [ -x "$CT_PATH" ] && { CT_BRIDGE="$CT_PATH"; break; }; done; [ -n "$CT_BRIDGE" ] || { echo 'Native WebUI bridge is unavailable' >&2; exit 127; }; exec "$CT_BRIDGE" ${args.map(value => `'${value}'`).join(' ')}`;
    }

    function validateResponseEnvelope(envelope) {
        if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) throw new Error('Invalid response envelope');
        const keys = Object.keys(envelope);
        if (!keys.every(key => responseFields.has(key)) || envelope.version !== 1 || !Number.isInteger(envelope.status) || envelope.status < 100 || envelope.status > 599) throw new Error('Invalid response envelope');
        if (typeof envelope.statusText !== 'string' || envelope.statusText.length > 256 || /[\u0000-\u001F\u007F]/.test(envelope.statusText) || typeof envelope.mimeType !== 'string' || envelope.mimeType.length < 1 || envelope.mimeType.length > 256 || /[\u0000-\u001F\u007F]/.test(envelope.mimeType)) throw new Error('Invalid response metadata');
        if (!Number.isSafeInteger(envelope.size) || envelope.size < 0 || envelope.size > maxResponseBytes) throw new Error('Invalid response size');
        const hasBody = typeof envelope.body === 'string';
        const hasDownload = typeof envelope.downloadId === 'string';
        if (hasBody === hasDownload || (hasDownload && !/^[0-9a-f]{32}$/.test(envelope.downloadId))) throw new Error('Invalid response payload');
        if (hasBody && (envelope.body.length > maxEnvelopeChars || !/^[A-Za-z0-9_-]*$/.test(envelope.body))) throw new Error('Invalid response payload');
        return envelope;
    }

    function extractResponseEnvelope(value, depth = 0) {
        if (value === null || value === undefined || depth > 4) return null;
        if (typeof value === 'string') {
            const raw = value.trim();
            if (!raw || raw.length > maxEnvelopeChars) return null;
            try {
                return extractResponseEnvelope(JSON.parse(raw), depth + 1);
            } catch (_) {
                return null;
            }
        }
        if (typeof value !== 'object' || Array.isArray(value)) return null;
        try {
            return JSON.stringify(validateResponseEnvelope(value));
        } catch (_) {
        }
        for (const key of ['stdout', 'out', 'stderr', 'err', 'message', 'result', 'data', 'output']) {
            if (key in value) {
                const envelope = extractResponseEnvelope(value[key], depth + 1);
                if (envelope) return envelope;
            }
        }
        return null;
    }

    function normalizeExecResult(values, expectEnvelope = false) {
        if (expectEnvelope) {
            for (const value of [values[1], values[0], values[2]]) {
                const envelope = extractResponseEnvelope(value);
                if (envelope) return { errno: 0, stdout: envelope, stderr: '' };
            }
        }
        let errno = values[0];
        let stdout = values[1];
        let stderr = values[2];

        if (values.length === 1 && errno && typeof errno === 'object' && !Array.isArray(errno)) {
            const result = errno;
            if ('errno' in result || 'stdout' in result || 'stderr' in result || 'code' in result || 'out' in result || 'err' in result) {
                errno = result.errno ?? result.code ?? 0;
                stdout = result.stdout ?? result.out ?? '';
                stderr = result.stderr ?? result.err ?? '';
            } else if (result.version === 1 && Number.isInteger(result.status)) {
                errno = 0;
                stdout = JSON.stringify(result);
                stderr = '';
            } else {
                errno = -1;
                stdout = '';
                stderr = 'Unsupported native exec result';
            }
        } else if (values.length === 1 && typeof errno === 'string') {
            const raw = errno.trim();
            let parsed = null;
            try { parsed = JSON.parse(raw); } catch (_) {}
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) &&
                ('errno' in parsed || 'stdout' in parsed || 'stderr' in parsed || 'code' in parsed)) {
                errno = parsed.errno ?? parsed.code ?? 0;
                stdout = parsed.stdout ?? parsed.out ?? '';
                stderr = parsed.stderr ?? parsed.err ?? '';
            } else {
                errno = 0;
                stdout = raw;
                stderr = '';
            }
        }

        const numericErrno = Number(errno);
        return {
            errno: Number.isFinite(numericErrno) ? numericErrno : -1,
            stdout: String(stdout ?? '').trim(),
            stderr: String(stderr ?? '').trim()
        };
    }

    function execHostCommand(command, timeoutMs = 10000) {
        if (!nativeApi || typeof nativeApi.exec !== 'function') return Promise.reject(new Error('Open this page from the KernelSU or APatch WebUI button'));
        const boundedTimeout = Math.min(Math.max(Number(timeoutMs) || 10000, 1000), 30000);
        return new Promise((resolve, reject) => {
            const callbackName = `ct_host_${Date.now()}_${callbackCounter++}`;
            let settled = false;
            const timer = setTimeout(() => {
                if (settled) return;
                settled = true;
                delete global[callbackName];
                reject(new Error('Host command timed out'));
            }, boundedTimeout + 2000);
            global[callbackName] = (...values) => {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                delete global[callbackName];
                const result = normalizeExecResult(values, false);
                if (result.errno === 0) resolve(result.stdout);
                else reject(new Error(result.stderr || result.stdout || `Host command failed with code ${result.errno}`));
            };
            try {
                nativeApi.exec(command, '{}', callbackName);
            } catch (error) {
                settled = true;
                clearTimeout(timer);
                delete global[callbackName];
                reject(error);
            }
        });
    }

    function execNative(args, timeoutMs, expectEnvelope = false) {
        if (!nativeApi || typeof nativeApi.exec !== 'function') return Promise.reject(new Error('Open this page from the KernelSU or APatch WebUI button'));
        const boundedTimeout = Math.min(Math.max(Number(timeoutMs) || 60000, 1000), 125000);
        return new Promise((resolve, reject) => {
            const callbackName = `ct_exec_${Date.now()}_${callbackCounter++}`;
            let settled = false;
            const timer = setTimeout(() => {
                if (settled) return;
                settled = true;
                delete global[callbackName];
                reject(new Error('Native bridge timed out'));
            }, boundedTimeout + 5000);
            global[callbackName] = (...values) => {
                if (settled) return;
                settled = true;
                clearTimeout(timer);
                delete global[callbackName];
                let result;
                try {
                    result = normalizeExecResult(values, expectEnvelope);
                } catch (error) {
                    reject(error);
                    return;
                }
                if (result.errno === 0) resolve(result.stdout);
                else reject(new Error(result.stderr || result.stdout || `Native bridge failed with code ${result.errno}`));
            };
            try {
                nativeApi.exec(shellCommand(args), '{}', callbackName);
            } catch (error) {
                settled = true;
                clearTimeout(timer);
                delete global[callbackName];
                reject(error);
            }
        });
    }

    async function openCommunity() {
        const command = `/system/bin/am start --user current -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${communityUrl}' -p com.android.chrome >/dev/null 2>&1 || /system/bin/am start --user current -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${communityUrl}' >/dev/null 2>&1`;
        await execHostCommand(command, 12000);
        return true;
    }

    async function getDebugLogging() {
        const output = await execHostCommand(`[ -f '${debugFlag}' ] && [ ! -L '${debugFlag}' ] && printf on || printf off`, 5000);
        return output === 'on';
    }

    async function setDebugLogging(enabled) {
        const command = enabled
            ? `umask 077; [ ! -L '${debugFlag}' ] || exit 2; : > '${debugFlag}'; chmod 0600 '${debugFlag}'`
            : `[ ! -L '${debugFlag}' ] || exit 2; rm -f '${debugFlag}'`;
        await execHostCommand(command, 5000);
        return Boolean(enabled);
    }

    async function createStage(kind, timeoutMs) {
        const id = await execNative(['stage-create', kind], timeoutMs);
        if (!/^[0-9a-f]{32}$/.test(id)) throw new Error('Invalid staging identifier');
        return id;
    }

    async function dropStage(kind, id) {
        if (!/^[0-9a-f]{32}$/.test(id)) return;
        try {
            await execNative(['stage-drop', kind, id], 10000);
        } catch (_) {
        }
    }

    async function appendStage(kind, id, bytes, timeoutMs) {
        for (let offset = 0; offset < bytes.length; offset += chunkBytes) {
            const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.length));
            await execNative(['stage-append', kind, id, encodeBytes(chunk)], timeoutMs);
        }
    }

    async function stageBlob(kind, blob, timeoutMs) {
        const limit = kind === 'export' ? maxResponseBytes : maxUploadBytes;
        if (!['upload', 'export'].includes(kind) || !(blob instanceof Blob) || blob.size <= 0 || blob.size > limit) throw new Error('File size is outside the supported range');
        const id = await createStage(kind, timeoutMs);
        try {
            for (let offset = 0; offset < blob.size; offset += chunkBytes) {
                const bytes = new Uint8Array(await blob.slice(offset, Math.min(offset + chunkBytes, blob.size)).arrayBuffer());
                await execNative(['stage-append', kind, id, encodeBytes(bytes)], timeoutMs);
            }
            return id;
        } catch (error) {
            await dropStage(kind, id);
            throw error;
        }
    }

    async function readDownload(id, size, timeoutMs) {
        if (!/^[0-9a-f]{32}$/.test(id) || !Number.isSafeInteger(size) || size < 0 || size > maxResponseBytes) {
            throw new Error('Invalid staged response');
        }
        const output = new Uint8Array(size);
        try {
            for (let offset = 0; offset < size; offset += chunkBytes) {
                const length = Math.min(chunkBytes, size - offset);
                const encoded = await execNative(['stage-read', 'download', id, String(offset), String(length)], timeoutMs);
                const chunk = decodeBytes(encoded);
                if (chunk.length !== length) throw new Error('Incomplete staged response');
                output.set(chunk, offset);
            }
            return output;
        } finally {
            await dropStage('download', id);
        }
    }

    class NativeResponse {
        constructor(envelope, timeoutMs) {
            this.status = Number(envelope.status);
            this.statusText = String(envelope.statusText || '');
            this.ok = this.status >= 200 && this.status < 300;
            this.type = 'basic';
            this.url = '';
            this.redirected = false;
            this.headers = new Headers({ 'content-type': String(envelope.mimeType || 'application/octet-stream') });
            this.bodyUsed = false;
            this.bodyEncoded = typeof envelope.body === 'string' ? envelope.body : null;
            this.downloadId = typeof envelope.downloadId === 'string' ? envelope.downloadId : null;
            this.size = Number(envelope.size || 0);
            this.timeoutMs = timeoutMs;
            this.cachedBytes = null;
        }

        async bytes() {
            if (this.cachedBytes) return this.cachedBytes;
            if (this.bodyEncoded !== null) this.cachedBytes = decodeBytes(this.bodyEncoded);
            else if (this.downloadId) {
                this.cachedBytes = await readDownload(this.downloadId, this.size, this.timeoutMs);
                this.downloadId = null;
            }
            else this.cachedBytes = new Uint8Array(0);
            return this.cachedBytes;
        }

        async text() {
            this.bodyUsed = true;
            return new TextDecoder('utf-8', { fatal: false }).decode(await this.bytes());
        }

        async json() {
            return JSON.parse(await this.text());
        }

        async blob() {
            this.bodyUsed = true;
            return new Blob([await this.bytes()], { type: this.headers.get('content-type') || 'application/octet-stream' });
        }

        async arrayBuffer() {
            this.bodyUsed = true;
            const bytes = await this.bytes();
            return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        }

        clone() {
            const copy = Object.create(NativeResponse.prototype);
            Object.assign(copy, this);
            copy.bodyUsed = false;
            return copy;
        }
    }

    function addParameter(parameters, key, value) {
        if (typeof key !== 'string' || typeof value !== 'string') throw new Error('Unsupported request parameter');
        if (key.length > 128 || value.length > 1024 * 1024) throw new Error('Request parameter is too large');
        if (!parameters[key]) parameters[key] = [];
        if (parameters[key].length >= 32) throw new Error('Too many request values');
        parameters[key].push(value);
    }

    async function prepareRequest(url, options, timeoutMs) {
        const parsed = new URL(String(url), 'https://native.cleverestricky.invalid');
        if (parsed.origin !== 'https://native.cleverestricky.invalid' || !parsed.pathname.startsWith('/api/')) throw new Error('Unsupported WebUI request path');
        const parameters = Object.create(null);
        parsed.searchParams.forEach((value, key) => addParameter(parameters, key, value));
        let uploadId = null;
        let uploadField = null;
        try {
            const body = options.body;
            if (body instanceof URLSearchParams) {
                body.forEach((value, key) => addParameter(parameters, key, value));
            } else if (body instanceof FormData) {
                for (const [key, value] of body.entries()) {
                    if (value instanceof File) {
                        if (uploadId) throw new Error('Only one file can be uploaded at a time');
                        uploadId = await stageBlob('upload', value, timeoutMs);
                        uploadField = key;
                        if (!parameters.filename && value.name) addParameter(parameters, 'filename', value.name);
                    } else {
                        addParameter(parameters, key, String(value));
                    }
                }
            } else if (body !== undefined && body !== null) {
                throw new Error('Unsupported request body');
            }
        } catch (error) {
            if (uploadId) await dropStage('upload', uploadId);
            throw error;
        }
        return {
            version: 1,
            method: String(options.method || 'GET').toUpperCase(),
            path: parsed.pathname,
            parameters,
            uploadId,
            uploadField
        };
    }

    async function callRequest(request, timeoutMs) {
        const bytes = new TextEncoder().encode(JSON.stringify(request));
        if (bytes.length > 1024 * 1024) throw new Error('Request is too large');
        let raw;
        if (bytes.length <= chunkBytes) {
            raw = await execNative(['call', encodeBytes(bytes), String(timeoutMs)], timeoutMs, true);
        } else {
            const stageId = await createStage('request', timeoutMs);
            try {
                await appendStage('request', stageId, bytes, timeoutMs);
                raw = await execNative(['call-file', stageId, String(timeoutMs)], timeoutMs, true);
            } catch (error) {
                await dropStage('request', stageId);
                throw error;
            }
        }
        let envelope;
        try {
            envelope = JSON.parse(raw);
        } catch (_) {
            throw new Error('Invalid response from native bridge');
        }
        validateResponseEnvelope(envelope);
        const hasBody = typeof envelope.body === 'string';
        const response = new NativeResponse(envelope, timeoutMs);
        if (hasBody) {
            const decoded = decodeBytes(envelope.body);
            if (decoded.length !== envelope.size) throw new Error('Incomplete inline response');
            response.cachedBytes = decoded;
            response.bodyEncoded = null;
        }
        return response;
    }

    async function nativeFetch(url, options = {}) {
        const requestedTimeout = Number(options.timeoutMs ?? 60000);
        const timeoutMs = Number.isFinite(requestedTimeout) ? Math.min(Math.max(Math.trunc(requestedTimeout), 1000), 120000) : 60000;
        if (options.signal && options.signal.aborted) throw new DOMException('The request was aborted', 'AbortError');
        let request;
        try {
            request = await prepareRequest(url, options, timeoutMs);
            return await callRequest(request, timeoutMs);
        } catch (error) {
            if (request && request.uploadId) await dropStage('upload', request.uploadId);
            throw error;
        }
    }

    async function exportBlob(blob, filename) {
        if (!(blob instanceof Blob) || typeof filename !== 'string' || filename.length < 1 || filename.length > 128) throw new Error('Invalid download');
        const kind = 'export';
        const id = await stageBlob(kind, blob, 120000);
        try {
            return await execNative(['export', kind, id, encodeText(filename)], 120000);
        } catch (error) {
            await dropStage(kind, id);
            throw error;
        }
    }

    async function exportResponse(response, filename) {
        if (!(response instanceof NativeResponse) || typeof filename !== 'string' || filename.length < 1 || filename.length > 128) throw new Error('Invalid download');
        if (response.downloadId) {
            const id = response.downloadId;
            response.downloadId = null;
            response.bodyUsed = true;
            try {
                return await execNative(['export', 'download', id, encodeText(filename)], 120000);
            } catch (error) {
                await dropStage('download', id);
                throw error;
            }
        }
        return exportBlob(await response.blob(), filename);
    }

    function listPackages() {
        if (!nativeApi || typeof nativeApi.listPackages !== 'function') return [];
        try {
            const parsed = JSON.parse(nativeApi.listPackages('all'));
            if (!Array.isArray(parsed) || parsed.length > 100000) return [];
            return Array.from(new Set(parsed.filter(value => typeof value === 'string' && value.length <= 255 && /^[A-Za-z0-9_.]+$/.test(value)))).sort();
        } catch (_) {
            return [];
        }
    }

    function installCommunityCard() {
        const document = global.document;
        if (!document || !document.body || document.getElementById('cleveresCommunityCard')) return;

        const card = document.createElement('section');
        card.id = 'cleveresCommunityCard';
        card.setAttribute('aria-label', 'CleveresTech Telegram community');
        card.style.cssText = 'box-sizing:border-box;max-width:800px;margin:0 auto;padding:0 20px max(28px,env(safe-area-inset-bottom));text-align:center;';

        const panel = document.createElement('div');
        panel.style.cssText = 'background:#161616;border:1px solid #333;border-radius:12px;padding:20px;box-shadow:0 4px 6px rgba(0,0,0,0.1);';

        const title = document.createElement('strong');
        title.textContent = 'CleveresTech Community';
        title.style.cssText = 'display:block;color:#E5E7EB;font-size:1.05em;margin-bottom:8px;';

        const copy = document.createElement('p');
        copy.textContent = 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.';
        copy.style.cssText = 'color:#999;line-height:1.5;margin:0 0 16px;';

        const link = document.createElement('a');
        link.href = communityUrl;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = 'Join Telegram Community';
        link.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;min-height:44px;padding:0 22px;border-radius:6px;background:#D1D5DB;color:#000;text-decoration:none;font-weight:600;letter-spacing:.3px;';

        panel.appendChild(title);
        panel.appendChild(copy);
        panel.appendChild(link);
        card.appendChild(panel);
        document.body.appendChild(card);
    }

    function scheduleCommunityCard() {
        const document = global.document;
        if (!document) return;
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', installCommunityCard, { once: true });
        } else {
            installCommunityCard();
        }
    }

    function loadUxEnhancements() {
        const document = global.document;
        if (!document || !document.head || !document.createElement || document.getElementById('ct_ux_script')) return;
        const script = document.createElement('script');
        script.id = 'ct_ux_script';
        script.src = 'ux.js?revision=4';
        script.defer = true;
        document.head.appendChild(script);
    }

    try {
        if (nativeApi && typeof nativeApi.enableEdgeToEdge === 'function') nativeApi.enableEdgeToEdge(true);
    } catch (_) {
    }
    try {
        if (nativeApi && typeof nativeApi.enableInsets === 'function') nativeApi.enableInsets(true);
    } catch (_) {
    }

    scheduleCommunityCard();
    global.CleveresBridge = Object.freeze({
        revision: 6,
        fetch: nativeFetch,
        exportBlob,
        exportResponse,
        listPackages,
        openCommunity,
        getDebugLogging,
        setDebugLogging
    });
    loadUxEnhancements();
})(window);