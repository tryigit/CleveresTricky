const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');

function createContext(locale) {
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
        DOMException,
        Intl,
        atob: value => Buffer.from(value, 'base64').toString('binary'),
        btoa: value => Buffer.from(value, 'binary').toString('base64'),
        CleveresI18n: { locale }
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(bridgeSource, context, { filename: 'bridge.js' });
    return context;
}

const statusSamples = [
    'AUTH_ERROR: API key is missing, invalid, or expired. Update the Remote Server API credentials.',
    'ACCESS_DENIED: API key access denied or temporarily banned. Check provider access or ban status.',
    'RATE_LIMITED: Too many requests. Wait before refreshing again.',
    'SERVICE_UNAVAILABLE: Remote Server unavailable or no eligible keybox is currently available.'
];

for (const locale of ['tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar']) {
    const context = createContext(locale);
    const translate = context.CleveresBridge.translateRemoteStatus;
    assert.strictEqual(typeof translate, 'function', `${locale}: remote status translator must be exported`);

    for (const source of statusSamples) {
        const translated = translate(source);
        const code = source.slice(0, source.indexOf(':'));
        assert.ok(translated.startsWith(`${code}: `), `${locale}: machine status code must stay stable`);
        assert.notStrictEqual(translated, source, `${locale}: ${code} should be localized`);
    }

    const accessRetry = translate('ACCESS_DENIED: API key temporarily banned. Retry after 90 seconds.');
    assert.ok(accessRetry.startsWith('ACCESS_DENIED: '), `${locale}: ACCESS_DENIED prefix must stay stable`);
    assert.ok(!accessRetry.includes('Retry after'), `${locale}: Retry-After copy should be localized`);
    assert.ok(accessRetry.includes('90') || locale === 'ar', `${locale}: bounded Retry-After value should remain visible`);

    const rateRetry = translate('RATE_LIMITED: Too many requests. Retry after 120 seconds.');
    assert.ok(rateRetry.startsWith('RATE_LIMITED: '), `${locale}: RATE_LIMITED prefix must stay stable`);
    assert.ok(!rateRetry.includes('Retry after'), `${locale}: rate-limit Retry-After copy should be localized`);

    const unsafeRetry = translate('ACCESS_DENIED: API key temporarily banned. Retry after 9999999999 seconds.');
    assert.ok(!unsafeRetry.includes('9999999999'), `${locale}: unbounded Retry-After values must not be interpolated`);
}

const english = createContext('en').CleveresBridge.translateRemoteStatus;
for (const source of statusSamples) {
    assert.strictEqual(english(source), source, 'English must preserve canonical backend status text');
}
assert.strictEqual(
    english('ACCESS_DENIED: API key temporarily banned. Retry after 90 seconds.'),
    'ACCESS_DENIED: API key temporarily banned. Retry after 90 seconds.',
    'English Retry-After status must stay canonical'
);
assert.strictEqual(
    createContext('tr').CleveresBridge.translateRemoteStatus('BAD_REQUEST: Remote Server rejected the request.'),
    'BAD_REQUEST: Remote Server rejected the request.',
    'Unowned status codes must not be rewritten'
);

assert.strictEqual(
    createContext('tr').CleveresBridge.translateRemoteStatus('OK'),
    'Tamam',
    'Turkish locale should translate OK status to Tamam'
);
assert.strictEqual(
    createContext('zh-CN').CleveresBridge.translateRemoteStatus('OK'),
    '正常',
    'Chinese locale should translate OK status to 正常'
);
assert.strictEqual(
    createContext('es').CleveresBridge.translateRemoteStatus('OK'),
    'Correcto',
    'Spanish locale should translate OK status to Correcto'
);
assert.strictEqual(
    english('OK'),
    'OK',
    'English locale must preserve canonical OK status'
);

assert.match(bridgeSource, /addEventListener\('ct_retranslate', refreshRemoteServerStatusCopy\)/);
assert.match(bridgeSource, /dataset\.ctRemoteStatusSource/);
assert.match(bridgeSource, /maxRemoteRetryAfterSeconds = 31 \* 24 \* 60 \* 60/);

console.log('Remote server status i18n tests passed');
