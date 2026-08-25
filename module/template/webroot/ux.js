(function (global) {
    'use strict';

    const document = global.document;
    if (!document) return;

    const STORAGE_KEY = 'cleverestricky.pending_reboot.v1';
    const REBOOT_TEXT = /(?:requires?\s+(?:a\s+)?reboot|reboot\s+required|require(?:s)?\s+restart|next\s+boot\s+only)/i;
    const FEATURE_REBOOT = new Set(['buildIdentity', 'regionIdentity']);
    let bootIdPromise = null;

    function storage() {
        try {
            return global.localStorage || null;
        } catch (_) {
            return null;
        }
    }

    function readState() {
        const store = storage();
        if (!store) return { bootId: '', settings: [] };
        try {
            const parsed = JSON.parse(store.getItem(STORAGE_KEY) || '{}');
            const settings = Array.isArray(parsed.settings)
                ? parsed.settings.filter(value => typeof value === 'string' && value.length <= 160)
                : [];
            return {
                bootId: typeof parsed.bootId === 'string' ? parsed.bootId : '',
                settings: Array.from(new Set(settings))
            };
        } catch (_) {
            return { bootId: '', settings: [] };
        }
    }

    function writeState(state) {
        const store = storage();
        if (!store) return;
        try {
            store.setItem(STORAGE_KEY, JSON.stringify({
                bootId: typeof state.bootId === 'string' ? state.bootId : '',
                settings: Array.from(new Set(state.settings || []))
            }));
        } catch (_) {
        }
    }

    function currentBootId() {
        if (bootIdPromise) return bootIdPromise;
        const nativeApi = global.ksu;
        if (!nativeApi || typeof nativeApi.exec !== 'function') {
            bootIdPromise = Promise.resolve('');
            return bootIdPromise;
        }
        bootIdPromise = new Promise(resolve => {
            const callbackName = `ct_pending_boot_${Date.now()}_${Math.floor(Math.random() * 10000)}`;
            let settled = false;
            const finish = value => {
                if (settled) return;
                settled = true;
                try { delete global[callbackName]; } catch (_) {}
                const normalized = typeof value === 'string' ? value.trim() : '';
                resolve(/^[0-9a-f-]{16,128}$/i.test(normalized) ? normalized : '');
            };
            global[callbackName] = (...values) => {
                const output = values.length > 1 ? values[1] : values[0];
                if (output && typeof output === 'object') {
                    finish(output.stdout ?? output.out ?? output.result ?? '');
                } else {
                    finish(output);
                }
            };
            try {
                nativeApi.exec('cat /proc/sys/kernel/random/boot_id', '{}', callbackName);
            } catch (_) {
                finish('');
            }
            global.setTimeout(() => finish(''), 2500);
        });
        return bootIdPromise;
    }

    async function reconcileBoot() {
        const bootId = await currentBootId();
        if (!bootId) return;
        const state = readState();
        if (state.bootId && state.bootId !== bootId) {
            writeState({ bootId, settings: [] });
        } else if (state.bootId !== bootId) {
            writeState({ bootId, settings: state.settings });
        }
    }

    function settingKey(input) {
        if (!input) return '';
        const feature = input.dataset && input.dataset.policyFeature;
        if (feature) return `feature:${feature}`;
        if (input.id && input.id.includes('global_identity')) return 'setting:global_identity_mode';
        return input.id ? `control:${input.id}` : '';
    }

    function looksRebootRequired(input) {
        if (!input || input.type !== 'checkbox') return false;
        const feature = input.dataset && input.dataset.policyFeature;
        if (FEATURE_REBOOT.has(feature)) return true;
        if (input.id && input.id.includes('global_identity')) return true;
        const row = input.closest && input.closest('.row');
        const text = row ? String(row.textContent || '') : String(input.parentElement?.textContent || '');
        return REBOOT_TEXT.test(text);
    }

    function isPending(input) {
        const key = settingKey(input);
        return Boolean(key && readState().settings.includes(key));
    }

    function addPending(input) {
        const key = settingKey(input);
        if (!key) return;
        const state = readState();
        state.settings = Array.from(new Set([...state.settings, key]));
        writeState(state);
        input.classList.add('pending-reboot');
        input.setAttribute('data-pending-reboot', 'true');
        currentBootId().then(bootId => {
            if (!bootId) return;
            const latest = readState();
            if (latest.bootId && latest.bootId !== bootId) {
                writeState({ bootId, settings: [] });
                return;
            }
            latest.bootId = bootId;
            writeState(latest);
        }).catch(() => {});
    }

    function removePending(input) {
        input.classList.remove('pending-reboot');
        input.removeAttribute('data-pending-reboot');
    }

    function paint() {
        const inputs = document.querySelectorAll('input[type="checkbox"]');
        inputs.forEach(input => {
            if (isPending(input)) addPending(input);
            else if (looksRebootRequired(input)) removePending(input);
        });
    }

    async function afterChange(input) {
        const feature = input.dataset && input.dataset.policyFeature;
        if (input.id && input.id.includes('global_identity')) {
            addPending(input);
            return;
        }
        if (!looksRebootRequired(input)) return;
        if (!FEATURE_REBOOT.has(feature)) {
            addPending(input);
            return;
        }

        global.setTimeout(async () => {
            try {
                const bridge = global.CleveresBridge;
                if (!bridge || typeof bridge.fetch !== 'function' || typeof bridge.applyIdentityLive !== 'function') {
                    addPending(input);
                    return;
                }
                const response = await bridge.fetch('/api/policy_state');
                if (!response.ok) {
                    addPending(input);
                    return;
                }
                const state = await response.json();
                const result = await bridge.applyIdentityLive(state);
                if (result && result.rebootRequired) addPending(input);
                else removePending(input);
            } catch (_) {
                addPending(input);
            }
        }, 650);
    }

    function install() {
        if (document.documentElement.dataset.ctPendingRebootInstalled) return;
        document.documentElement.dataset.ctPendingRebootInstalled = '1';

        document.addEventListener('change', event => {
            const input = event.target;
            if (!input || input.type !== 'checkbox') return;
            afterChange(input).catch(() => addPending(input));
        }, true);

        if (typeof global.MutationObserver === 'function') {
            new global.MutationObserver(() => paint()).observe(document.body || document.documentElement, {
                childList: true,
                subtree: true
            });
        }

        reconcileBoot().then(paint).catch(() => paint());
        paint();
    }

    global.setTimeout(install, 0);

    const core = document.createElement('script');
    core.src = 'ux-core.js?revision=9';
    core.defer = true;
    document.head.appendChild(core);
})(window);
