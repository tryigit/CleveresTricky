(function (global) {
    'use strict';

    const bridge = global.CleveresBridge;
    const document = global.document;
    if (!bridge || !document) return;

    const COPY = Object.freeze({
        en: {
            button: 'Restore Defaults',
            hint: 'Restore built-in feature defaults. Stored keyboxes and encrypted backups are not changed.',
            confirm: 'Restore built-in feature settings to their defaults? Stored keyboxes and encrypted backups will be kept.',
            working: 'Restoring defaults...',
            done: 'Default settings restored',
            failed: 'Could not restore default settings'
        },
        tr: {
            button: 'Varsayılanlara Dön',
            hint: 'Yerleşik özellik varsayılanlarını geri yükler. Kayıtlı keyboxlar ve şifreli yedekler değiştirilmez.',
            confirm: 'Yerleşik özellik ayarları varsayılanlarına döndürülsün mü? Kayıtlı keyboxlar ve şifreli yedekler korunur.',
            working: 'Varsayılanlar geri yükleniyor...',
            done: 'Varsayılan ayarlar geri yüklendi',
            failed: 'Varsayılan ayarlar geri yüklenemedi'
        },
        'zh-CN': {
            button: '恢复默认设置',
            hint: '恢复内置功能默认值。已存储的 Keybox 和加密备份不会更改。',
            confirm: '要将内置功能设置恢复为默认值吗？已存储的 Keybox 和加密备份将保留。',
            working: '正在恢复默认设置…',
            done: '默认设置已恢复',
            failed: '无法恢复默认设置'
        },
        es: {
            button: 'Restaurar valores predeterminados',
            hint: 'Restaura los valores predeterminados de las funciones integradas. Los keyboxes almacenados y las copias cifradas no se modifican.',
            confirm: '¿Restaurar la configuración de las funciones integradas a sus valores predeterminados? Se conservarán los keyboxes almacenados y las copias cifradas.',
            working: 'Restaurando valores predeterminados...',
            done: 'Configuración predeterminada restaurada',
            failed: 'No se pudo restaurar la configuración predeterminada'
        },
        de: {
            button: 'Standardeinstellungen wiederherstellen',
            hint: 'Stellt die integrierten Funktionsstandards wieder her. Gespeicherte Keyboxen und verschlüsselte Backups bleiben unverändert.',
            confirm: 'Integrierte Funktionseinstellungen auf Standard zurücksetzen? Gespeicherte Keyboxen und verschlüsselte Backups bleiben erhalten.',
            working: 'Standardeinstellungen werden wiederhergestellt...',
            done: 'Standardeinstellungen wiederhergestellt',
            failed: 'Standardeinstellungen konnten nicht wiederhergestellt werden'
        },
        ru: {
            button: 'Восстановить настройки по умолчанию',
            hint: 'Восстанавливает встроенные настройки функций. Сохранённые keybox и зашифрованные резервные копии не изменяются.',
            confirm: 'Восстановить встроенные настройки функций по умолчанию? Сохранённые keybox и зашифрованные резервные копии будут сохранены.',
            working: 'Восстановление настроек по умолчанию...',
            done: 'Настройки по умолчанию восстановлены',
            failed: 'Не удалось восстановить настройки по умолчанию'
        },
        id: {
            button: 'Pulihkan Pengaturan Default',
            hint: 'Memulihkan default fitur bawaan. Keybox tersimpan dan cadangan terenkripsi tidak diubah.',
            confirm: 'Pulihkan pengaturan fitur bawaan ke default? Keybox tersimpan dan cadangan terenkripsi akan dipertahankan.',
            working: 'Memulihkan pengaturan default...',
            done: 'Pengaturan default dipulihkan',
            failed: 'Pengaturan default tidak dapat dipulihkan'
        },
        hi: {
            button: 'डिफ़ॉल्ट सेटिंग्स बहाल करें',
            hint: 'बिल्ट-इन फीचर डिफ़ॉल्ट बहाल करता है। सहेजे गए Keybox और एन्क्रिप्टेड बैकअप नहीं बदले जाते।',
            confirm: 'बिल्ट-इन फीचर सेटिंग्स को डिफ़ॉल्ट पर बहाल करें? सहेजे गए Keybox और एन्क्रिप्टेड बैकअप सुरक्षित रहेंगे।',
            working: 'डिफ़ॉल्ट सेटिंग्स बहाल की जा रही हैं...',
            done: 'डिफ़ॉल्ट सेटिंग्स बहाल हो गईं',
            failed: 'डिफ़ॉल्ट सेटिंग्स बहाल नहीं हो सकीं'
        },
        ar: {
            button: 'استعادة الإعدادات الافتراضية',
            hint: 'يعيد الإعدادات الافتراضية للميزات المدمجة. لا يتم تغيير صناديق المفاتيح المحفوظة أو النسخ الاحتياطية المشفرة.',
            confirm: 'هل تريد استعادة إعدادات الميزات المدمجة إلى الوضع الافتراضي؟ سيتم الاحتفاظ بصناديق المفاتيح المحفوظة والنسخ الاحتياطية المشفرة.',
            working: 'جار استعادة الإعدادات الافتراضية...',
            done: 'تمت استعادة الإعدادات الافتراضية',
            failed: 'تعذر استعادة الإعدادات الافتراضية'
        }
    });

    function currentLocale() {
        const i18nLocale = global.CleveresI18n && global.CleveresI18n.locale;
        const htmlLocale = document.documentElement && document.documentElement.lang;
        const candidate = i18nLocale || htmlLocale || 'en';
        return Object.prototype.hasOwnProperty.call(COPY, candidate) ? candidate : 'en';
    }

    function copy(key) {
        return (COPY[currentLocale()] || COPY.en)[key] || COPY.en[key] || key;
    }

    function localize() {
        const button = document.getElementById('ct_restore_defaults');
        const hint = document.getElementById('ct_restore_defaults_hint');
        if (hint) hint.textContent = copy('hint');
        if (button && !button.disabled) {
            button.textContent = copy('button');
            button.setAttribute('aria-label', copy('button'));
        }
    }

    async function restoreDefaults(button) {
        if (!button || button.disabled) return;
        if (typeof global.confirm === 'function' && !global.confirm(copy('confirm'))) return;

        button.disabled = true;
        button.textContent = copy('working');
        try {
            const body = new URLSearchParams();
            body.set('profile', 'default');
            const response = await bridge.fetch('/api/apply_profile', { method: 'POST', body });
            if (!response.ok) throw new Error(await response.text());
            if (typeof global.notify === 'function') global.notify(copy('done'));
            global.setTimeout(() => global.location.reload(), 700);
        } catch (_) {
            if (typeof global.notify === 'function') global.notify(copy('failed'), 'error');
            button.disabled = false;
            localize();
        }
    }

    function install() {
        const syncButton = document.getElementById('runtimeSyncBtn');
        const actions = syncButton && syncButton.closest('.ct-config-actions');
        if (!actions) return false;

        let hint = document.getElementById('ct_restore_defaults_hint');
        let button = document.getElementById('ct_restore_defaults');
        if (!hint) {
            hint = document.createElement('div');
            hint.id = 'ct_restore_defaults_hint';
            hint.className = 'ct-config-field-note';
            hint.style.gridColumn = '1 / -1';
            actions.appendChild(hint);
        }
        if (!button) {
            button = document.createElement('button');
            button.id = 'ct_restore_defaults';
            button.type = 'button';
            button.className = 'danger';
            button.style.gridColumn = '1 / -1';
            button.addEventListener('click', () => restoreDefaults(button));
            actions.appendChild(button);
        }
        localize();
        return true;
    }

    function start() {
        install();
        [200, 700, 1500, 3000, 6000].forEach(delay => global.setTimeout(install, delay));
        document.addEventListener('change', event => {
            if (event.target && event.target.id === 'ct_language_selector') global.setTimeout(localize, 0);
        }, true);
        if (typeof global.MutationObserver === 'function' && document.documentElement) {
            const observer = new global.MutationObserver(mutations => {
                if (mutations.some(mutation => mutation.type === 'attributes' && mutation.attributeName === 'lang')) localize();
            });
            observer.observe(document.documentElement, { attributes: true, attributeFilter: ['lang'] });
        }
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
    else start();
})(window);
