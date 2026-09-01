const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const uxSource = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const indexSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');
const policySource = fs.readFileSync('module/template/webroot/policy.js', 'utf8');
const catalogMarker = '    let locale = readLocale();';
const instrumentedSource = uxSource.replace(
    catalogMarker,
    '    global.__CleveresCatalogs = TRANSLATIONS;\n' + catalogMarker
);
assert.notStrictEqual(instrumentedSource, uxSource, 'localization catalog instrumentation marker is missing');

function loadI18n(localeOrOptions) {
    const options = typeof localeOrOptions === 'string' ? { savedLocale: localeOrOptions } : (localeOrOptions || {});
    const savedLocale = typeof options.savedLocale === 'string' ? options.savedLocale : null;
    const cachedSystemLocale = typeof options.cachedSystemLocale === 'string' ? options.cachedSystemLocale : null;
    const browserLocale = typeof options.browserLocale === 'string' ? options.browserLocale : null;
    const runtimeSystemLocale = typeof options.systemLocale === 'string' ? options.systemLocale : null;
    const document = {
        readyState: 'loading',
        documentElement: {},
        body: null,
        addEventListener() {}
    };
    const context = {
        console,
        document,
        localStorage: {
            getItem: key => {
                if (key === 'cleverestricky.language.v1') return savedLocale;
                if (key === 'cleverestricky.system_locale.v1') return cachedSystemLocale;
                return null;
            },
            setItem() {}
        },
        navigator: browserLocale ? { language: browserLocale } : {},
        CleveresBridge: {},
        setTimeout() {},
        clearTimeout() {},
        addEventListener() {}
    };
    if (runtimeSystemLocale) context.CleveresSystemLocale = runtimeSystemLocale;
    context.window = context;
    vm.createContext(context);
    vm.runInContext(instrumentedSource, context, { filename: 'ux.js' });
    return { i18n: context.CleveresI18n, catalogs: context.__CleveresCatalogs };
}

const sharedCoreCopy = [
    'Dashboard',
    'Info & Resources',
    'Runtime Health',
    'Resource Monitor',
    'Module Logs',
    'Language'
];

const localizedLocales = ['tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar'];
const canonicalCatalog = loadI18n('tr').catalogs.tr;
const canonicalKeys = Object.keys(canonicalCatalog);
assert.ok(canonicalKeys.length >= 430, 'complete WebUI catalog unexpectedly shrank');

for (const locale of localizedLocales) {
    const { i18n, catalogs } = loadI18n(locale);
    assert.strictEqual(i18n.locale, locale);
    const missing = canonicalKeys.filter(source => {
        const translated = catalogs[locale] && catalogs[locale][source];
        return typeof translated !== 'string' || translated.trim() === '';
    });
    assert.deepStrictEqual(missing, [], `${locale} is missing complete catalog entries`);
    for (const source of sharedCoreCopy) {
        assert.notStrictEqual(i18n.translate(source), source, `${locale} is missing core copy: ${source}`);
    }
}

const completeSurfaces = [
    'Always active.',
    'Identity Engine',
    'Select the attestation identity used for configured target applications.',
    'Attestation and Telephony Identifiers',
    'Application Privacy Shield',
    'Remote Servers',
    'Upload Keybox / CBOX',
    'Upload Keybox or CBOX file',
    'Stored Keyboxes',
    'Checking module state...',
    'The last native activation attempt failed before the Keystore interceptor became operational.',
    'Measured daemon CPU and resident memory are shown above. Runtime rows describe configuration and execution scope. Hardware bootloader and root-of-trust warnings can remain visible because this page reports module state, not a physically relocked device.',
    'Feature Center',
    'Identity Controls',
    'Enable only the identity paths you need. Disabled paths do not start optional interceptors.',
    'Identity is currently disabled. Enable only the identity paths you need below.',
    'Random',
    'Identity value randomized',
    'What does this do?',
    'Security Patch is independent from Identity. It controls system, vendor, and boot security patch levels; use Device Default to keep captured values, Automatic for calendar-based policy, or Manual for an explicit date.',
    "DRM App Passthrough keeps configured packages on Android's genuine Keystore path. It does not fake a DRM security level; use Profiles > Privacy > Isolate for app-scoped DRM device identifiers.",
    'Main controls are here. Parent features reveal only the settings that belong to them.',
    'Security Patch',
    'Profiles',
    'Profile Editor',
    'App-centric configuration. Assign installed apps or wildcards, then choose privacy, identity, keybox and feature overrides.',
    'Effective State',
    'Matched profile',
    'Estimated impact: CPU very low while idle and low per matching Binder call; RAM low.',
    'View recent logs from the module. You can also download them for sharing.',
    'Support the Development',
    'Thank you for your support!',
    'CleveresTech Telegram community',
    'CleveresTech Community',
    'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.',
    'Restore Defaults',
    'Restores module settings using the built-in default profile. Stored keyboxes and encrypted backups are not deleted.'
];

assert.match(uxSource, /card\.setAttribute\('aria-label', tr\('CleveresTech Telegram community'\)\)/, 'Telegram card aria-label must use the localization owner');
assert.match(uxSource, /title\.textContent = tr\('CleveresTech Community'\)/, 'Telegram card title must use the localization owner');
assert.match(uxSource, /copy\.textContent = tr\('Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky\.'\)/, 'Telegram card description must use the localization owner');
assert.match(uxSource, /link\.textContent = tr\('Open Telegram Community'\)/, 'Telegram card action must use the localization owner');
assert.match(indexSource, /id="dropZone"[^>]*aria-label="Upload Keybox or CBOX file"/, 'Keybox drop zone must expose a localized accessible name');
assert.match(policySource, /const secPatchHelp = helpMarkup\('Security Patch is independent from Identity\./, 'Security Patch must expose a card-level What Does This Do help block');
assert.match(policySource, /const drmHelp = helpMarkup\("DRM App Passthrough keeps configured packages/, 'DRM App Passthrough must expose a card-level What Does This Do help block');
assert.match(policySource, /secPatchOn,secPatchHelp \+ secPatchChildren/, 'Security Patch help must be attached to its card');
assert.match(policySource, /drmOn,drmHelp \+ drmChildren/, 'DRM App Passthrough help must be attached to its card');

const runtimeGlobal = 'Native runtime is active with 4 verified keyboxes. Global application scope is enabled. Core boot/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine.';
const runtimeTargeted = 'Native runtime is active with 2 verified keyboxes. Targeted mode is enabled, so app rules determine scope. Core boot/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine.';
for (const locale of localizedLocales) {
    const { i18n } = loadI18n(locale);
    for (const source of completeSurfaces) {
        assert.notStrictEqual(i18n.translate(source), source, `${locale} surface copy is missing: ${source}`);
    }
    for (const source of [runtimeGlobal, runtimeTargeted, '4 Keys Loaded', 'Delete profile "Gaming"?', 'Download failed: timeout']) {
        const translated = i18n.translate(source);
        assert.notStrictEqual(translated, source, `${locale} dynamic copy is missing: ${source}`);
        assert.match(translated, /(?:4|2|Gaming|timeout)/, `${locale} dynamic value was lost: ${source}`);
    }
    assert.strictEqual(i18n.translate('com.example.app'), 'com.example.app');
}

const turkish = loadI18n('tr').i18n;
assert.match(turkish.translate(runtimeGlobal), /4 doğrulanmış keybox/);
assert.strictEqual(turkish.translate('4 Keys Loaded'), '4 anahtar yüklendi');
assert.strictEqual(turkish.translate('noServers'), 'Yapılandırılmış sunucu yok. Keybox\'ları otomatik olarak almak için aşağıdan bir tane ekleyin.');
assert.strictEqual(turkish.translate('Refresh'), 'Yenile');
assert.strictEqual(turkish.translate('Remove'), 'Kaldır');
assert.strictEqual(turkish.translate('Failed to load servers.'), 'Sunucular yüklenemedi.');
assert.strictEqual(loadI18n('en').i18n.translate('Runtime Health'), 'Runtime Health');

assert.strictEqual(loadI18n({ savedLocale: 'de', systemLocale: 'tr-TR', browserLocale: 'zh-CN' }).i18n.locale, 'de');
assert.strictEqual(loadI18n({ systemLocale: 'tr-TR', browserLocale: 'de-DE' }).i18n.locale, 'tr');
assert.strictEqual(loadI18n({ cachedSystemLocale: 'zh-CN', browserLocale: 'de-DE' }).i18n.locale, 'zh-CN');
assert.strictEqual(loadI18n({ browserLocale: 'es-MX' }).i18n.locale, 'es');
assert.strictEqual(loadI18n({ browserLocale: 'pt-BR' }).i18n.locale, 'en');

assert.strictEqual(
    loadI18n({ systemLocale: 'zh-Hans-CN', browserLocale: 'de-DE' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh_Hans_CN', browserLocale: 'de-DE' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh_CN', browserLocale: 'de-DE' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ browserLocale: 'zh-Hans' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ browserLocale: 'zh' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ browserLocale: 'zh-Hans-CN' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh-hans-cn' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh_hans_cn' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh-cn' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'zh-sg' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'ZH_CN' }).i18n.locale,
    'zh-CN'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'tr-tr' }).i18n.locale,
    'tr'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'TR-TR' }).i18n.locale,
    'tr'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'de-de' }).i18n.locale,
    'de'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'DE-DE' }).i18n.locale,
    'de'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'es-mx' }).i18n.locale,
    'es'
);
assert.strictEqual(
    loadI18n({ systemLocale: 'ES-MX' }).i18n.locale,
    'es'
);

// Verify bridge.js normalizeLocale handles all lowercase/uppercase/underscore variants
const bridgeSource = fs.readFileSync('module/template/webroot/bridge.js', 'utf8');
const bridgeNormMatch = bridgeSource.match(/function normalizeLocale\([\s\S]*?\n    \}/);
assert.ok(bridgeNormMatch, 'normalizeLocale must exist in bridge.js');
const supportedLocales = new Set(['en', 'tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar']);
const bridgeNormalizeLocale = new Function('supportedLocales', `return (${bridgeNormMatch[0]});`)(supportedLocales);

// Verify ux.js second normalizeSupportedLocale (ZIP importer)
const zipNormMatches = [...uxSource.matchAll(/function normalizeSupportedLocale\([\s\S]*?\n    \}/g)];
assert.strictEqual(zipNormMatches.length, 2, 'ux.js must define normalizeSupportedLocale in both IIFEs');
const SUPPORTED_LOCALES = new Set(['en', 'tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar']);
const zipNormalizeLocale = new Function('SUPPORTED_LOCALES', `return (${zipNormMatches[1][0]});`)(SUPPORTED_LOCALES);

const testVariants = [
    ['zh-Hans-CN', 'zh-CN'],
    ['zh-hans-cn', 'zh-CN'],
    ['zh_Hans_CN', 'zh-CN'],
    ['zh_hans_cn', 'zh-CN'],
    ['zh-Hans', 'zh-CN'],
    ['zh-hans', 'zh-CN'],
    ['zh_CN', 'zh-CN'],
    ['zh-cn', 'zh-CN'],
    ['ZH_CN', 'zh-CN'],
    ['ZH-CN', 'zh-CN'],
    ['zh-SG', 'zh-CN'],
    ['zh-sg', 'zh-CN'],
    ['zh', 'zh-CN'],
    ['ZH', 'zh-CN'],
    ['tr-TR', 'tr'],
    ['tr-tr', 'tr'],
    ['TR-TR', 'tr'],
    ['de-DE', 'de'],
    ['de-de', 'de'],
    ['DE-DE', 'de'],
    ['es-MX', 'es'],
    ['es-mx', 'es'],
    ['ES-MX', 'es'],
    ['ru-RU', 'ru'],
    ['ru-ru', 'ru'],
    ['id-ID', 'id'],
    ['id-id', 'id'],
    ['hi-IN', 'hi'],
    ['hi-in', 'hi'],
    ['ar-EG', 'ar'],
    ['ar-eg', 'ar'],
    ['pt-BR', null]
];

for (const [tag, expected] of testVariants) {
    const uxExpected = expected || 'en';
    const resolvedUx = loadI18n({ systemLocale: tag }).i18n.locale;
    assert.strictEqual(resolvedUx, uxExpected, `ux.js normalizeSupportedLocale (site 1) mismatch for tag "${tag}"`);

    const resolvedBridge = bridgeNormalizeLocale(tag);
    assert.strictEqual(resolvedBridge, expected, `bridge.js normalizeLocale mismatch for tag "${tag}"`);

    const resolvedZip = zipNormalizeLocale(tag);
    assert.strictEqual(resolvedZip, expected, `ux.js normalizeSupportedLocale (site 2) mismatch for tag "${tag}"`);
}

// Verify data-i18n restoration when switching back to English (including when tr(key) === key)
const fallbackLeafElement = {
    hasAttribute(name) { return name === 'data-i18n'; },
    getAttribute(name) { return name === 'data-i18n' ? 'Fallback Leaf Text' : null; },
    children: [],
    textContent: 'Türkçe Metin'
};
const mappedLeafElement = {
    hasAttribute(name) { return name === 'data-i18n'; },
    getAttribute(name) { return name === 'data-i18n' ? 'refresh' : null; },
    children: [],
    textContent: 'Yenile'
};
const { catalogs } = loadI18n('en');
function testTranslateLeaf(el, loc) {
    const key = el.getAttribute('data-i18n');
    const rendered = (catalogs[loc] && catalogs[loc][key]) || key;
    if (typeof rendered === 'string' && (!el.children || el.children.length === 0)) {
        if (el.textContent !== rendered) el.textContent = rendered;
    }
}
testTranslateLeaf(fallbackLeafElement, 'en');
assert.strictEqual(fallbackLeafElement.textContent, 'Fallback Leaf Text', 'leaf element must restore source key when tr(key) === key');

testTranslateLeaf(mappedLeafElement, 'en');
assert.strictEqual(mappedLeafElement.textContent, 'Refresh', 'leaf element must restore mapped English translation');

console.log('WebUI localization coverage tests passed');
