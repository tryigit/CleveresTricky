const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const policySource = fs.readFileSync('module/template/webroot/policy.js', 'utf8');

function makeResponse({ ok = true, jsonValue = {}, textValue = '', contentType = 'application/json' } = {}) {
    return {
        ok,
        status: ok ? 200 : 500,
        headers: {
            get(name) {
                return String(name).toLowerCase() === 'content-type' ? contentType : '';
            }
        },
        async json() {
            return jsonValue;
        },
        async text() {
            return textValue;
        }
    };
}

function makeElement(tagName = 'div') {
    return {
        tagName: String(tagName).toUpperCase(),
        id: '',
        className: '',
        textContent: '',
        style: {},
        dataset: {},
        hidden: false,
        disabled: false,
        setAttribute() {},
        removeAttribute() {},
        append() {},
        appendChild() {},
        replaceChildren() {},
        querySelector() { return null; },
        querySelectorAll() { return []; },
        addEventListener() {}
    };
}

function loadPolicyRuntime(fetchImpl = async () => makeResponse()) {
    const notifications = [];
    const quietConsole = { log() {}, warn() {}, error() {} };
    const document = {
        body: null,
        head: { appendChild() {} },
        documentElement: { classList: { add() {}, remove() {} } },
        addEventListener() {},
        getElementById() { return null; },
        createElement: makeElement,
        createTextNode(text) { return { textContent: String(text) }; }
    };
    const window = {
        CleveresBridge: { fetch: fetchImpl },
        notify(message, type) {
            notifications.push({ message: String(message), type: type || 'normal' });
        },
        setTimeout,
        clearTimeout,
        requestAnimationFrame(callback) { callback(); },
        console: quietConsole
    };

    const hookExport = `global.__ctPolicyTestHooks = Object.freeze({
        escapeHtml,
        policyCompatibilityWarning,
        notifyPolicyMutation,
        savePolicy,
        setPolicyStateForTest(value) { policyState = value; },
        getPolicyStateForTest() { return policyState; },
        refreshSavedBuildIdentityBestEffort,
        installIdentityManagerState,
        installAutoIdentityOverride,
        removeLegacySurfaces,
        normalizePolicyState,
        normalizeProfile,
        normalizeSecurityPatch
    });`;
    const instrumented = policySource.replace('onReady(initialize);', hookExport);
    assert.notStrictEqual(instrumented, policySource, 'policy.js test hook injection point is missing');

    vm.runInNewContext(instrumented, {
        window,
        document,
        console: quietConsole,
        URLSearchParams,
        Event: class Event {
            constructor(type, options) {
                this.type = type;
                this.options = options;
            }
        },
        setTimeout,
        clearTimeout
    }, { filename: 'policy.js' });

    assert.ok(window.__ctPolicyTestHooks, 'policy.js runtime hooks were not exposed to the test harness');
    return { window, document, notifications, hooks: window.__ctPolicyTestHooks };
}

function basePolicy(overrides = {}) {
    return {
        version: 2,
        features: {
            buildIdentity: false,
            attestationIdentity: false,
            telephonyIdentity: false,
            regionIdentity: false,
            identityRefresh: false,
            securityPatch: false
        },
        securityPatch: {
            automaticThresholdMonths: 6,
            system: { mode: 'device_default' },
            vendor: { mode: 'device_default' },
            boot: { mode: 'device_default' }
        },
        profiles: [],
        activeProfile: null,
        ...overrides
    };
}

async function testEscapingPrimitive() {
    const { hooks } = loadPolicyRuntime();
    assert.strictEqual(
        hooks.escapeHtml(`&<>"'`),
        '&amp;&lt;&gt;&quot;&#39;',
        'HTML escaping must preserve complete entities for all markup-significant characters'
    );
    assert.strictEqual(
        hooks.escapeHtml('value" autofocus onfocus="boom'),
        'value&quot; autofocus onfocus=&quot;boom',
        'double quotes must remain safely encoded when escaped text is placed in an attribute value'
    );
}

async function testCanonicalSaveWarningIsNotReportedAsFailure() {
    const { hooks, notifications } = loadPolicyRuntime();
    hooks.notifyPolicyMutation('Policy saved', {
        compatibilitySync: 'pending',
        compatibilityWarning: 'Retry compatibility sync before reboot.'
    });

    assert.deepStrictEqual(notifications, [{
        message: 'Policy saved. Warning: Retry compatibility sync before reboot.',
        type: 'normal'
    }], 'a committed canonical policy with a pending compatibility sync must remain a successful action with a warning');
}

async function testSaveUsesCanonicalReadbackInsteadOfStaleUiState() {
    let posted = null;
    const canonical = basePolicy({
        serverGeneration: 'B',
        compatibilitySync: 'pending',
        compatibilityWarning: 'marker sync pending'
    });
    canonical.features = { ...canonical.features, buildIdentity: true };

    const runtime = loadPolicyRuntime(async (path, options = {}) => {
        assert.strictEqual(path, '/api/policy_state', 'policy mutation must use the canonical policy endpoint');
        assert.strictEqual(options.method, 'POST', 'policy mutation must be a POST');
        posted = JSON.parse(options.body.get('data'));
        return makeResponse({ jsonValue: canonical });
    });
    runtime.hooks.setPolicyStateForTest(basePolicy({ serverGeneration: 'A' }));

    await runtime.hooks.savePolicy(next => {
        next.features.buildIdentity = true;
    }, 'Identity updated');

    assert.ok(posted, 'policy mutation payload must be sent');
    assert.strictEqual(posted.features.buildIdentity, true, 'the intended mutation must be present in the request payload');
    const current = runtime.hooks.getPolicyStateForTest();
    assert.strictEqual(current.serverGeneration, 'B', 'UI state must be replaced by canonical server readback after a committed mutation');
    assert.strictEqual(current.features.buildIdentity, true, 'canonical returned feature state must become the next UI source of truth');
    assert.ok(runtime.notifications.some(item => item.message === 'Identity updated. Warning: marker sync pending' && item.type === 'normal'),
        'a pending compatibility sync must warn without reverting to stale UI state or reporting the committed mutation as failed');
}

async function testPolicyNormalizationRejectsMalformedAndOversizedState() {
    const { hooks } = loadPolicyRuntime();
    const malformed = {
        features: {
            buildIdentity: 'true',
            attestationIdentity: 1,
            telephonyIdentity: true,
            regionIdentity: {},
            identityRefresh: false,
            securityPatch: 'false'
        },
        securityPatch: {
            automaticThresholdMonths: 999,
            system: { mode: 'manual', value: '2026-08-27-extra' },
            vendor: { mode: 'not-allowed', value: '<script>' },
            boot: null
        },
        profiles: Array.from({ length: 300 }, (_, index) => ({
            name: `<profile-${index}>`,
            applications: Array.from({ length: 100 }, (_, appIndex) => `com.example.${index}.${appIndex}`),
            template: 't'.repeat(300),
            keybox: 'k'.repeat(300),
            privacy: 'unknown',
            features: { buildIdentity: 'true', securityPatch: 1 },
            securityPatch: { system: { mode: 'manual', value: '2026-08-27-extra' } },
            rkpPassthrough: 'yes',
            drmPassthrough: true
        })),
        activeProfile: 'a'.repeat(400)
    };

    const normalized = hooks.normalizePolicyState(malformed);
    assert.strictEqual(normalized.features.buildIdentity, false, 'string feature values must not become truthy');
    assert.strictEqual(normalized.features.attestationIdentity, false, 'numeric feature values must not become truthy');
    assert.strictEqual(normalized.features.telephonyIdentity, true, 'actual boolean feature values must be retained');
    assert.strictEqual(normalized.features.regionIdentity, false, 'object feature values must not become truthy');
    assert.strictEqual(normalized.features.identityRefresh, false, 'false feature values must remain false');
    assert.strictEqual(normalized.features.securityPatch, false, 'string false feature values must remain false');
    assert.strictEqual(normalized.securityPatch.automaticThresholdMonths, 24, 'security patch threshold must be capped');
    assert.strictEqual(normalized.securityPatch.system.mode, 'manual', 'valid patch modes must be retained');
    assert.strictEqual(normalized.securityPatch.system.value, '2026-08-27', 'manual dates must be bounded');
    assert.strictEqual(normalized.securityPatch.vendor.mode, 'device_default', 'unknown patch modes must fall back safely');
    assert.strictEqual(normalized.profiles.length, 256, 'policy profiles must be capped');
    assert.strictEqual(normalized.profiles[0].applications.length, 64, 'profile assignments must be capped');
    assert.strictEqual(normalized.profiles[0].template, null, 'oversized profile template references must be rejected');
    assert.strictEqual(normalized.profiles[0].keybox, null, 'oversized profile keybox references must be rejected');
    assert.strictEqual(normalized.profiles[0].privacy, 'inherit', 'unknown privacy modes must fall back safely');
    assert.strictEqual(normalized.profiles[0].securityPatch.system.mode, 'manual', 'profile patch modes must be retained');
    assert.strictEqual(normalized.profiles[0].securityPatch.system.value, '2026-08-27', 'profile patch dates must be bounded');
    assert.strictEqual(normalized.profiles[0].rkpPassthrough, null, 'malformed passthrough flags must be removed');
    assert.strictEqual(normalized.profiles[0].drmPassthrough, true, 'valid passthrough flags must be retained');
    assert.strictEqual(normalized.activeProfile.length, 256, 'active profile reference must be bounded');

    const saved = hooks.normalizePolicyState({ features: { securityPatch: 'true' } });
    assert.strictEqual(saved.features.securityPatch, false, 'malformed feature values must not become truthy after readback normalization');
}

function makeCleanupSurface(classNames = [], textContent = '') {
    return {
        classList: { contains(name) { return classNames.includes(name); } },
        textContent,
        parentElement: null,
        removed: false,
        remove() { this.removed = true; },
        contains(node) { return this.containsNode === node || this.children?.includes(node); },
        children: [],
        querySelector() { return null; },
        querySelectorAll() { return []; }
    };
}

async function testLegacyCleanupPreservesCanonicalStatusGrid() {
    const canonical = loadPolicyRuntime();
    const dashboard = makeCleanupSurface();
    const statusGrid = makeCleanupSurface(['status-grid']);
    const engineCard = makeCleanupSurface();
    const globalCard = makeCleanupSurface();
    const statusEngine = makeCleanupSurface();
    const statusGlobal = makeCleanupSurface();
    statusGrid.parentElement = dashboard;
    statusGrid.children = [engineCard, globalCard];
    statusGrid.containsNode = statusGlobal;
    engineCard.parentElement = statusGrid;
    globalCard.parentElement = statusGrid;
    statusEngine.parentElement = engineCard;
    statusGlobal.parentElement = globalCard;
    canonical.document.getElementById = id => ({
        dashboard,
        status_engine: statusEngine,
        status_global: statusGlobal
    }[id] || null);
    canonical.hooks.removeLegacySurfaces();
    assert.strictEqual(statusGrid.removed, false, 'legacy cleanup must preserve the canonical status-grid');

    const legacy = loadPolicyRuntime();
    const legacyDashboard = makeCleanupSurface();
    const legacyStrip = makeCleanupSurface();
    const legacyEngineCard = makeCleanupSurface();
    const legacyGlobalCard = makeCleanupSurface();
    const legacyEngine = makeCleanupSurface();
    const legacyGlobal = makeCleanupSurface();
    legacyStrip.parentElement = legacyDashboard;
    legacyStrip.children = [legacyEngineCard, legacyGlobalCard];
    legacyStrip.containsNode = legacyGlobal;
    legacyEngineCard.parentElement = legacyStrip;
    legacyGlobalCard.parentElement = legacyStrip;
    legacyEngine.parentElement = legacyEngineCard;
    legacyGlobal.parentElement = legacyGlobalCard;
    legacy.document.getElementById = id => ({
        dashboard: legacyDashboard,
        status_engine: legacyEngine,
        status_global: legacyGlobal
    }[id] || null);
    legacy.hooks.removeLegacySurfaces();
    assert.strictEqual(legacyStrip.removed, true, 'legacy cleanup must still remove an unclassified old status strip');
}

async function testApplyIdentitySurvivesPresentationRefreshFailure() {
    const runtime = loadPolicyRuntime(async () => makeResponse({
        ok: false,
        textValue: 'saved identity view unavailable',
        contentType: 'text/plain'
    }));
    runtime.window.applySpoofing = async () => 'persisted';

    runtime.hooks.installIdentityManagerState();
    const result = await runtime.window.applySpoofing();

    assert.strictEqual(result, 'persisted', 'Apply Identity success must not be rejected by a later read-only refresh failure');
    const warning = runtime.notifications.find(item => item.message.startsWith('Identity was applied. Warning:'));
    assert.ok(warning, 'Apply Identity must surface a separate presentation warning after persistence succeeds');
    assert.strictEqual(warning.type, 'normal', 'a presentation refresh warning must not be rendered as an action failure');
}

function makeAutoIdentityButton() {
    const listeners = {};
    const button = makeElement('button');
    button.textContent = 'AUTO IDENTITY';
    button.onclick = () => {};
    button.removeAttribute = name => {
        if (name === 'onclick') button.onclick = null;
    };
    button.addEventListener = (type, listener) => {
        listeners[type] = listener;
    };
    return { button, listeners };
}

async function testAutoIdentitySurvivesPresentationRefreshFailure() {
    const runtime = loadPolicyRuntime(async path => {
        if (path === '/api/auto_identity') {
            return makeResponse({ jsonValue: { model: 'Pixel Test', build_id: 'AP3A.TEST' } });
        }
        if (String(path).startsWith('/api/file?filename=spoof_build_vars')) {
            return makeResponse({ ok: false, contentType: 'text/plain', textValue: 'refresh unavailable' });
        }
        return makeResponse();
    });
    const { button, listeners } = makeAutoIdentityButton();
    const spoof = { querySelectorAll(selector) { return selector === 'button' ? [button] : []; } };
    runtime.document.getElementById = id => id === 'spoof' ? spoof : null;
    runtime.window.loadIdentity = async () => { throw new Error('legacy view refresh failed'); };

    runtime.hooks.installAutoIdentityOverride();
    assert.strictEqual(typeof listeners.click, 'function', 'Auto Identity click handler must be installed');
    await listeners.click({ preventDefault() {} });

    const success = runtime.notifications.find(item => item.message.startsWith('Identity ready:'));
    assert.ok(success, 'Auto Identity backend success must remain visible even if presentation refresh fails');
    assert.match(success.message, /Warning: the Identity Manager view could not be fully refreshed/);
    assert.strictEqual(success.type, 'normal', 'Auto Identity presentation refresh failure must not turn a successful backend action into an error state');
    assert.ok(!runtime.notifications.some(item => /Auto Identity failed/.test(item.message)), 'presentation refresh failures must not enter the Auto Identity backend failure path');
    assert.strictEqual(button.disabled, false, 'Auto Identity button must be re-enabled after completion');
}

async function testAutoIdentityBackendFailureStillFails() {
    const runtime = loadPolicyRuntime(async path => {
        if (path === '/api/auto_identity') {
            return makeResponse({ ok: false, contentType: 'text/plain', textValue: 'identity backend rejected request' });
        }
        return makeResponse();
    });
    const { button, listeners } = makeAutoIdentityButton();
    const spoof = { querySelectorAll(selector) { return selector === 'button' ? [button] : []; } };
    runtime.document.getElementById = id => id === 'spoof' ? spoof : null;

    runtime.hooks.installAutoIdentityOverride();
    await listeners.click({ preventDefault() {} });

    const failure = runtime.notifications.find(item => item.message === 'identity backend rejected request');
    assert.ok(failure, 'real Auto Identity backend failures must still be reported');
    assert.strictEqual(failure.type, 'error', 'real backend failures must retain error presentation');
}

(async () => {
    await testEscapingPrimitive();
    await testCanonicalSaveWarningIsNotReportedAsFailure();
    await testSaveUsesCanonicalReadbackInsteadOfStaleUiState();
    await testPolicyNormalizationRejectsMalformedAndOversizedState();
    await testLegacyCleanupPreservesCanonicalStatusGrid();
    await testApplyIdentitySurvivesPresentationRefreshFailure();
    await testAutoIdentitySurvivesPresentationRefreshFailure();
    await testAutoIdentityBackendFailureStillFails();
    console.log('Executable WebUI runtime contract checks passed');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
