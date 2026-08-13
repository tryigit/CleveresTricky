const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '../template/webroot/defaults.js'), 'utf8');

function makeElement(id = '') {
    return {
        id,
        disabled: false,
        textContent: '',
        className: '',
        style: {},
        attributes: {},
        children: [],
        parentElement: null,
        addEventListener(type, handler) { this[`on${type}`] = handler; },
        setAttribute(name, value) { this.attributes[name] = value; },
        appendChild(child) { child.parentElement = this; this.children.push(child); },
        closest(selector) {
            if (selector === '.ct-config-actions' && this.parentElement && this.parentElement.className.includes('ct-config-actions')) return this.parentElement;
            return null;
        }
    };
}

async function run() {
    const elements = Object.create(null);
    const actions = makeElement('actions');
    actions.className = 'grid-2 ct-config-actions';
    const sync = makeElement('runtimeSyncBtn');
    actions.appendChild(sync);
    elements.runtimeSyncBtn = sync;

    const requests = [];
    let reloaded = false;
    let notice = null;
    let languageChangeHandler = null;

    const document = {
        readyState: 'complete',
        documentElement: { lang: 'en' },
        getElementById(id) { return elements[id] || null; },
        createElement() {
            const element = makeElement();
            const originalAppend = actions.appendChild.bind(actions);
            const register = child => { if (child.id) elements[child.id] = child; originalAppend(child); };
            element._registerOnAppend = register;
            return element;
        },
        addEventListener(type, handler) {
            if (type === 'change') languageChangeHandler = handler;
        }
    };
    actions.appendChild = child => {
        if (child.id) elements[child.id] = child;
        child.parentElement = actions;
        actions.children.push(child);
    };

    class URLSearchParamsPolyfill {
        constructor() { this.values = new Map(); }
        set(key, value) { this.values.set(key, value); }
        get(key) { return this.values.get(key); }
    }

    const window = {
        document,
        CleveresBridge: {
            async fetch(url, options) {
                requests.push({ url, options });
                return { ok: true, text: async () => '' };
            }
        },
        CleveresI18n: { locale: 'en' },
        confirm: () => true,
        notify: (message, kind) => { notice = { message, kind }; },
        location: { reload: () => { reloaded = true; } },
        setTimeout(callback) { callback(); return 1; }
    };

    const context = { window, document, URLSearchParams: URLSearchParamsPolyfill, console };
    vm.runInNewContext(source, context, { filename: 'defaults.js' });

    const button = elements.ct_restore_defaults;
    const hint = elements.ct_restore_defaults_hint;
    if (!button || !hint) throw new Error('Restore-defaults controls were not installed');
    if (button.textContent !== 'Restore Defaults') throw new Error('English restore-defaults copy was not rendered');
    if (!hint.textContent.includes('Stored keyboxes')) throw new Error('Restore-defaults scope disclosure is missing');

    await button.onclick();
    if (requests.length !== 1) throw new Error('Restore-defaults must perform exactly one request');
    if (requests[0].url !== '/api/apply_profile' || requests[0].options.method !== 'POST') throw new Error('Unexpected restore-defaults endpoint');
    if (requests[0].options.body.get('profile') !== 'default') throw new Error('Restore-defaults must apply the built-in default profile');
    if (!notice || notice.kind) throw new Error('Successful restore-defaults notification is missing');
    if (!reloaded) throw new Error('Successful restore-defaults must reload the WebUI');

    window.CleveresI18n.locale = 'tr';
    languageChangeHandler({ target: { id: 'ct_language_selector' } });
    if (button.textContent !== 'Varsayılanlara Dön') throw new Error('Restore-defaults did not follow the active WebUI locale');

    const localeMarkers = ['en:', 'tr:', "'zh-CN':", 'es:', 'de:', 'ru:', 'id:', 'hi:', 'ar:'];
    for (const marker of localeMarkers) {
        if (!source.includes(marker)) throw new Error(`Missing restore-defaults locale: ${marker}`);
    }
}

run().catch(error => {
    console.error(error);
    process.exit(1);
});
