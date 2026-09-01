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
    const keyboxHubUrl = 'https://keybox.tryigit.dev/';
    const debugFlag = '/data/adb/cleverestricky/debug_logging';
    const nativeSuccessMarker = '__CT_NATIVE_OK__';
    const nativeFilePickerIds = new Set(['kbFilePicker', 'restoreInput']);
    const languageStorageKey = 'cleverestricky.language.v1';
    const systemLocaleStorageKey = 'cleverestricky.system_locale.v1';
    const supportedLocales = new Set(['en', 'tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar']);
    let callbackCounter = 0;

    const configRoot = '/data/adb/cleverestricky';
    const cronAutoIdentityFlag = `${configRoot}/cron_auto_identity`;
    const nativeRuntimeLog = `${configRoot}/native_runtime.log`;
    const policyResponsePaths = new Set(['/api/policy_state', '/api/profile_v2']);
    let latestPolicyState = null;
    let policyStateRequest = null;
    let profileMutationQueue = Promise.resolve();
    let logsReady = false;
    const profileEnabledByName = new Map();
    const profileEnabledBySignature = new Map();
    let identityWrapperInstallStarted = false;

    const EXTENSION_COPY = {
        tr: {
            'Cron Auto Identity': 'Cron Otomatik Kimlik',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Build Identity açıkken her 24 saatte bir yeni Otomatik Kimlik indirir ve uygular.',
            'Profile enabled': 'Profil etkin',
            'Profile disabled': 'Profil devre dışı',
            'Enabled': 'Etkin',
            'Disabled': 'Devre dışı',
            'Auto Identity saved. Build Identity remains disabled.': 'Otomatik Kimlik kaydedildi. Build Identity devre dışı kalmaya devam ediyor.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'Kimlik yeniden başlatma olmadan uygulandı. Açık uygulamalar eski Build değerlerini gösteriyorsa uygulamaları yeniden başlatın.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'Canlı Kimlik uygulaması kullanılamıyor. Bu Build/bölge özellik değişiklikleri için yeniden başlatma gerekiyor.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'Daha önce uygulanmış Build/bölge kimliğini kapatmak, özgün özellikleri geri yüklemek için yeniden başlatma gerekebilir.',
            'No CleveresTricky logs found.': 'CleveresTricky günlüğü bulunamadı.'
        },
        'zh-CN': {
            'Cron Auto Identity': 'Cron 自动身份',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': '启用 Build Identity 时，每 24 小时获取并应用新的自动身份。',
            'Profile enabled': '配置档案已启用',
            'Profile disabled': '配置档案已禁用',
            'Enabled': '已启用',
            'Disabled': '已禁用',
            'Auto Identity saved. Build Identity remains disabled.': '自动身份已保存，Build Identity 仍保持禁用。',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': '身份已无需重启直接应用。若已打开的应用仍显示旧 Build 值，请重启这些应用。',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': '无法实时应用身份；这些 Build/区域属性更改需要重启。',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': '关闭已应用的 Build/区域身份后，可能需要重启才能恢复原始属性。',
            'No CleveresTricky logs found.': '未找到 CleveresTricky 日志。'
        },
        es: {
            'Cron Auto Identity': 'Identidad automática Cron',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Obtiene y aplica una nueva identidad automática cada 24 horas mientras Build Identity está activado.',
            'Profile enabled': 'Perfil activado',
            'Profile disabled': 'Perfil desactivado',
            'Enabled': 'Activado',
            'Disabled': 'Desactivado',
            'Auto Identity saved. Build Identity remains disabled.': 'Identidad automática guardada. Build Identity permanece desactivado.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'Identidad aplicada sin reiniciar. Reinicia las apps abiertas si aún muestran valores Build anteriores.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'La aplicación en vivo de identidad no está disponible. Estos cambios de propiedades Build/región requieren reinicio.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'Desactivar una identidad Build/región ya aplicada puede requerir reinicio para restaurar las propiedades originales.',
            'No CleveresTricky logs found.': 'No se encontraron registros de CleveresTricky.'
        },
        de: {
            'Cron Auto Identity': 'Cron Auto-Identität',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Ruft alle 24 Stunden eine neue Auto-Identität ab und wendet sie an, solange Build Identity aktiviert ist.',
            'Profile enabled': 'Profil aktiviert',
            'Profile disabled': 'Profil deaktiviert',
            'Enabled': 'Aktiviert',
            'Disabled': 'Deaktiviert',
            'Auto Identity saved. Build Identity remains disabled.': 'Auto-Identität gespeichert. Build Identity bleibt deaktiviert.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'Identität ohne Neustart angewendet. Starte offene Apps neu, falls sie noch alte Build-Werte anzeigen.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'Live-Anwendung der Identität ist nicht verfügbar. Für diese Build-/Regionsänderungen ist ein Neustart erforderlich.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'Das Deaktivieren einer bereits angewendeten Build-/Regionsidentität kann einen Neustart zum Wiederherstellen der Originalwerte erfordern.',
            'No CleveresTricky logs found.': 'Keine CleveresTricky-Protokolle gefunden.'
        },
        ru: {
            'Cron Auto Identity': 'Cron Автоидентичность',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Получает и применяет новую автоидентичность каждые 24 часа, пока включён Build Identity.',
            'Profile enabled': 'Профиль включён',
            'Profile disabled': 'Профиль отключён',
            'Enabled': 'Включено',
            'Disabled': 'Отключено',
            'Auto Identity saved. Build Identity remains disabled.': 'Автоидентичность сохранена. Build Identity остаётся отключённым.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'Идентичность применена без перезагрузки. Перезапустите открытые приложения, если они показывают старые значения Build.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'Живое применение идентичности недоступно. Для этих изменений Build/региона требуется перезагрузка.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'Отключение уже применённой Build/региональной идентичности может потребовать перезагрузки для возврата исходных свойств.',
            'No CleveresTricky logs found.': 'Журналы CleveresTricky не найдены.'
        },
        id: {
            'Cron Auto Identity': 'Cron Identitas Otomatis',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Mengambil dan menerapkan Identitas Otomatis baru setiap 24 jam saat Build Identity aktif.',
            'Profile enabled': 'Profil diaktifkan',
            'Profile disabled': 'Profil dinonaktifkan',
            'Enabled': 'Aktif',
            'Disabled': 'Nonaktif',
            'Auto Identity saved. Build Identity remains disabled.': 'Identitas Otomatis disimpan. Build Identity tetap nonaktif.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'Identitas diterapkan tanpa reboot. Mulai ulang aplikasi yang masih menampilkan nilai Build lama.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'Penerapan Identitas langsung tidak tersedia. Perubahan properti Build/wilayah ini memerlukan reboot.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'Menonaktifkan identitas Build/wilayah yang sudah diterapkan mungkin memerlukan reboot untuk memulihkan properti asli.',
            'No CleveresTricky logs found.': 'Log CleveresTricky tidak ditemukan.'
        },
        hi: {
            'Cron Auto Identity': 'Cron ऑटो पहचान',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'Build Identity चालू रहने पर हर 24 घंटे में नई ऑटो पहचान लाता और लागू करता है।',
            'Profile enabled': 'प्रोफ़ाइल चालू',
            'Profile disabled': 'प्रोफ़ाइल बंद',
            'Enabled': 'चालू',
            'Disabled': 'बंद',
            'Auto Identity saved. Build Identity remains disabled.': 'ऑटो पहचान सहेजी गई। Build Identity बंद ही रहेगा।',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'पहचान बिना रीबूट के लागू हुई। खुले ऐप पुराने Build मान दिखाएँ तो उन्हें पुनः शुरू करें।',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'लाइव पहचान लागू करना उपलब्ध नहीं है। इन Build/क्षेत्र प्रॉपर्टी बदलावों के लिए रीबूट आवश्यक है।',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'पहले से लागू Build/क्षेत्र पहचान बंद करने पर मूल प्रॉपर्टी लौटाने के लिए रीबूट आवश्यक हो सकता है।',
            'No CleveresTricky logs found.': 'CleveresTricky लॉग नहीं मिले।'
        },
        ar: {
            'Cron Auto Identity': 'هوية تلقائية مجدولة',
            'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.': 'يجلب ويطبق هوية تلقائية جديدة كل 24 ساعة ما دامت Build Identity مفعلة.',
            'Profile enabled': 'تم تفعيل الملف الشخصي',
            'Profile disabled': 'تم تعطيل الملف الشخصي',
            'Enabled': 'مفعّل',
            'Disabled': 'معطّل',
            'Auto Identity saved. Build Identity remains disabled.': 'تم حفظ الهوية التلقائية. ستبقى Build Identity معطلة.',
            'Identity applied without reboot. Restart open apps if they still show previous Build values.': 'تم تطبيق الهوية دون إعادة تشغيل. أعد تشغيل التطبيقات المفتوحة إذا استمرت في عرض قيم Build القديمة.',
            'Live Identity apply is unavailable. Reboot is required for these Build/region property changes.': 'التطبيق المباشر للهوية غير متاح. تتطلب تغييرات خصائص Build/المنطقة هذه إعادة تشغيل.',
            'Disabling an already applied Build/region identity may require a reboot to restore original properties.': 'قد يتطلب تعطيل هوية Build/المنطقة المطبقة سابقًا إعادة تشغيل لاستعادة الخصائص الأصلية.',
            'No CleveresTricky logs found.': 'لم يتم العثور على سجلات CleveresTricky.'
        }
    };

    function normalizeLocale(value) {
        if (typeof value !== 'string' || !value) return null;
        const tag = value.toLowerCase().replace(/_/g, '-');
        if (tag === 'zh' || tag.startsWith('zh-hans') || tag.startsWith('zh-cn') || tag.startsWith('zh-sg')) {
            return 'zh-CN';
        }
        for (const loc of supportedLocales) {
            if (loc.toLowerCase() === tag) return loc;
        }
        const base = tag.split('-')[0];
        for (const loc of supportedLocales) {
            if (loc.toLowerCase() === base) return loc;
        }
        return null;
    }

    function extensionLocale() {
        const active = global.CleveresI18n && global.CleveresI18n.locale;
        const activeLocale = normalizeLocale(active);
        if (activeLocale) return activeLocale;
        try {
            const stored = normalizeLocale(global.localStorage && global.localStorage.getItem(languageStorageKey));
            if (stored) return stored;
            const systemLocale = normalizeLocale(global.CleveresSystemLocale);
            if (systemLocale) return systemLocale;
            const cachedSystem = normalizeLocale(global.localStorage && global.localStorage.getItem(systemLocaleStorageKey));
            if (cachedSystem) return cachedSystem;
            const browserLocale = normalizeLocale(global.navigator && global.navigator.language);
            if (browserLocale) return browserLocale;
            return 'en';
        } catch (_) {
            return 'en';
        }
    }

    function extensionText(source) {
        const catalog = EXTENSION_COPY[extensionLocale()];
        return catalog && typeof catalog[source] === 'string' ? catalog[source] : source;
    }

    function abortError() {
        return new DOMException('The request was aborted', 'AbortError');
    }

    function throwIfAborted(signal) {
        if (signal && signal.aborted) throw abortError();
    }

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

    function shellCommand(args, markSuccess = false) {
        if (!args.every(value => typeof value === 'string' && /^[A-Za-z0-9_-]+$/.test(value))) {
            throw new Error('Invalid bridge argument');
        }
        const paths = modulePaths.map(path => `'${path}'`).join(' ');
        const prefix = `CT_BRIDGE=''; for CT_PATH in ${paths}; do [ -x "$CT_PATH" ] && { CT_BRIDGE="$CT_PATH"; break; }; done; [ -n "$CT_BRIDGE" ] || { echo 'Native WebUI bridge is unavailable' >&2; exit 127; };`;
        const invocation = `"$CT_BRIDGE" ${args.map(value => `'${value}'`).join(' ')}`;
        if (!markSuccess) return `${prefix} exec ${invocation}`;
        return `${prefix} ${invocation}; CT_STATUS=$?; [ "$CT_STATUS" -eq 0 ] || exit "$CT_STATUS"; printf '\n${nativeSuccessMarker}\n'`;
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

    function extractMarkedNativeOutput(value, depth = 0) {
        if (value === null || value === undefined || depth > 4) return null;
        if (typeof value === 'string') {
            if (!value.includes(nativeSuccessMarker)) return null;
            return value.split(nativeSuccessMarker).join('').trim();
        }
        if (typeof value !== 'object' || Array.isArray(value)) return null;
        for (const key of ['stdout', 'out', 'stderr', 'err', 'message', 'result', 'data', 'output']) {
            if (key in value) {
                const output = extractMarkedNativeOutput(value[key], depth + 1);
                if (output !== null) return output;
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

    function execNative(args, timeoutMs, expectEnvelope = false, signal = null) {
        if (!nativeApi || typeof nativeApi.exec !== 'function') return Promise.reject(new Error('Open this page from the KernelSU or APatch WebUI button'));
        try { throwIfAborted(signal); } catch (error) { return Promise.reject(error); }
        const boundedTimeout = Math.min(Math.max(Number(timeoutMs) || 60000, 1000), 125000);
        return new Promise((resolve, reject) => {
            const callbackName = `ct_exec_${Date.now()}_${callbackCounter++}`;
            let settled = false;
            let abortHandler = null;
            const cleanup = () => {
                clearTimeout(timer);
                delete global[callbackName];
                if (signal && abortHandler) signal.removeEventListener('abort', abortHandler);
            };
            const timer = setTimeout(() => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(new Error('Native bridge timed out'));
            }, boundedTimeout + 5000);
            global[callbackName] = (...values) => {
                if (settled) return;
                settled = true;
                cleanup();
                if (!expectEnvelope) {
                    for (const value of [values[1], values[0], values[2], ...values.slice(3)]) {
                        const output = extractMarkedNativeOutput(value);
                        if (output !== null) {
                            resolve(output);
                            return;
                        }
                    }
                }
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
            if (signal) {
                abortHandler = () => {
                    if (settled) return;
                    settled = true;
                    cleanup();
                    reject(abortError());
                };
                signal.addEventListener('abort', abortHandler, { once: true });
                if (signal.aborted) {
                    abortHandler();
                    return;
                }
            }
            try {
                nativeApi.exec(shellCommand(args, !expectEnvelope), '{}', callbackName);
            } catch (error) {
                if (settled) return;
                settled = true;
                cleanup();
                reject(error);
            }
        });
    }

    async function openExternalUrl(url) {
        if (url !== communityUrl && url !== keyboxHubUrl) throw new Error('Unsupported external URL');
        const command = `/system/bin/am start --user current -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${url}' -p com.android.chrome >/dev/null 2>&1 || /system/bin/am start --user current -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${url}' >/dev/null 2>&1`;
        await execHostCommand(command, 12000);
        return true;
    }

    async function openCommunity() {
        return openExternalUrl(communityUrl);
    }

    async function openKeyboxHub() {
        return openExternalUrl(keyboxHubUrl);
    }

    function getDebugLogging() {
        return execHostCommand(`[ -f '${debugFlag}' ] && [ ! -L '${debugFlag}' ] && printf on || printf off`, 5000).then(output => output === 'on');
    }

    async function setDebugLogging(enabled) {
        const command = enabled
            ? `umask 077; [ ! -L '${debugFlag}' ] || exit 2; : > '${debugFlag}'; chmod 0600 '${debugFlag}'`
            : `[ ! -L '${debugFlag}' ] || exit 2; rm -f '${debugFlag}'`;
        await execHostCommand(command, 5000);
        return Boolean(enabled);
    }

    async function createStage(kind, timeoutMs, signal = null) {
        const id = await execNative(['stage-create', kind], timeoutMs, false, signal);
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

    async function appendStage(kind, id, bytes, timeoutMs, signal = null) {
        for (let offset = 0; offset < bytes.length; offset += chunkBytes) {
            throwIfAborted(signal);
            const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.length));
            await execNative(['stage-append', kind, id, encodeBytes(chunk)], timeoutMs, false, signal);
        }
    }

    async function stageBlob(kind, blob, timeoutMs, signal = null) {
        const limit = kind === 'export' ? maxResponseBytes : maxUploadBytes;
        if (!['upload', 'export'].includes(kind) || !(blob instanceof Blob) || blob.size <= 0 || blob.size > limit) throw new Error('File size is outside the supported range');
        const id = await createStage(kind, timeoutMs, signal);
        try {
            for (let offset = 0; offset < blob.size; offset += chunkBytes) {
                throwIfAborted(signal);
                const bytes = new Uint8Array(await blob.slice(offset, Math.min(offset + chunkBytes, blob.size)).arrayBuffer());
                await execNative(['stage-append', kind, id, encodeBytes(bytes)], timeoutMs, false, signal);
            }
            return id;
        } catch (error) {
            await dropStage(kind, id);
            throw error;
        }
    }

    async function readDownload(id, size, timeoutMs, signal = null) {
        if (!/^[0-9a-f]{32}$/.test(id) || !Number.isSafeInteger(size) || size < 0 || size > maxResponseBytes) {
            throw new Error('Invalid staged response');
        }
        const output = new Uint8Array(size);
        for (let offset = 0; offset < size; offset += chunkBytes) {
            throwIfAborted(signal);
            const length = Math.min(chunkBytes, size - offset);
            const encoded = await execNative(['stage-read', 'download', id, String(offset), String(length)], timeoutMs, false, signal);
            const chunk = decodeBytes(encoded);
            if (chunk.length !== length) throw new Error('Incomplete staged response');
            output.set(chunk, offset);
        }
        return output;
    }

    async function materializeDownload(reference, size, timeoutMs, signal = null) {
        if (reference.bytes) return reference.bytes;
        if (reference.error) throw reference.error;
        if (reference.promise) return reference.promise;
        const id = reference.id;
        if (!id) throw new Error('Staged response is unavailable');
        reference.promise = (async () => {
            try {
                const bytes = await readDownload(id, size, timeoutMs, signal);
                reference.bytes = bytes;
                return bytes;
            } catch (error) {
                reference.error = error;
                throw error;
            } finally {
                if (reference.id === id) reference.id = null;
                await dropStage('download', id);
            }
        })();
        return reference.promise;
    }

    class NativeResponse {
        constructor(envelope, timeoutMs, signal = null) {
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
            this.downloadRef = this.downloadId ? { id: this.downloadId, promise: null, bytes: null, error: null, shared: false } : null;
            this.size = Number(envelope.size || 0);
            this.timeoutMs = timeoutMs;
            this.signal = signal;
            this.cachedBytes = null;
        }

        async consumeBytes() {
            throwIfAborted(this.signal);
            if (this.bodyUsed) throw new TypeError('Response body has already been consumed');
            this.bodyUsed = true;
            if (this.cachedBytes) return this.cachedBytes;
            if (this.bodyEncoded !== null) {
                this.cachedBytes = decodeBytes(this.bodyEncoded);
                this.bodyEncoded = null;
            } else if (this.downloadRef) {
                const reference = this.downloadRef;
                try {
                    const bytes = await materializeDownload(reference, this.size, this.timeoutMs, this.signal);
                    this.cachedBytes = bytes.slice();
                } finally {
                    this.downloadId = null;
                    this.downloadRef = null;
                }
            } else {
                this.cachedBytes = new Uint8Array(0);
            }
            return this.cachedBytes;
        }

        async bytes() {
            return this.consumeBytes();
        }

        async text() {
            return new TextDecoder('utf-8', { fatal: false }).decode(await this.consumeBytes());
        }

        async json() {
            return JSON.parse(await this.text());
        }

        async blob() {
            return new Blob([await this.consumeBytes()], { type: this.headers.get('content-type') || 'application/octet-stream' });
        }

        async arrayBuffer() {
            const bytes = await this.consumeBytes();
            return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        }

        clone() {
            if (this.bodyUsed) throw new TypeError('Cannot clone a consumed response');
            const copy = Object.create(NativeResponse.prototype);
            Object.assign(copy, this);
            copy.headers = new Headers(this.headers);
            copy.bodyUsed = false;
            copy.cachedBytes = this.cachedBytes ? this.cachedBytes.slice() : null;
            if (copy.downloadRef) copy.downloadRef.shared = true;
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

    async function prepareRequest(url, options, timeoutMs, signal = null) {
        throwIfAborted(signal);
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
                        uploadId = await stageBlob('upload', value, timeoutMs, signal);
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

    async function callRequest(request, timeoutMs, signal = null) {
        throwIfAborted(signal);
        const bytes = new TextEncoder().encode(JSON.stringify(request));
        if (bytes.length > 1024 * 1024) throw new Error('Request is too large');
        let raw;
        if (bytes.length <= chunkBytes) {
            raw = await execNative(['call', encodeBytes(bytes), String(timeoutMs)], timeoutMs, true, signal);
        } else {
            const stageId = await createStage('request', timeoutMs, signal);
            try {
                await appendStage('request', stageId, bytes, timeoutMs, signal);
                raw = await execNative(['call-file', stageId, String(timeoutMs)], timeoutMs, true, signal);
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
        const response = new NativeResponse(envelope, timeoutMs, signal);
        if (hasBody) {
            const decoded = decodeBytes(envelope.body);
            if (decoded.length !== envelope.size) throw new Error('Incomplete inline response');
            response.cachedBytes = decoded;
            response.bodyEncoded = null;
        }
        return response;
    }

    function canonicalProfileValue(value) {
        if (Array.isArray(value)) return value.map(canonicalProfileValue);
        if (!value || typeof value !== 'object') return value;
        const result = {};
        Object.keys(value).sort().forEach(key => { result[key] = canonicalProfileValue(value[key]); });
        return result;
    }

    function profileSignature(profile) {
        if (!profile || typeof profile !== 'object') return '';
        return JSON.stringify(canonicalProfileValue({
            applications: Array.isArray(profile.applications) ? profile.applications.map(String).sort() : [],
            template: profile.template || null,
            keybox: profile.keybox || null,
            privacy: profile.privacy || 'inherit',
            features: profile.features && typeof profile.features === 'object' ? profile.features : {},
            securityPatch: profile.securityPatch && typeof profile.securityPatch === 'object' ? profile.securityPatch : {},
            rkpPassthrough: typeof profile.rkpPassthrough === 'boolean' ? profile.rkpPassthrough : null,
            drmPassthrough: typeof profile.drmPassthrough === 'boolean' ? profile.drmPassthrough : null
        }));
    }

    function rememberPolicyState(state) {
        if (!state || typeof state !== 'object' || !Array.isArray(state.profiles)) return;
        latestPolicyState = state;
        profileEnabledByName.clear();
        profileEnabledBySignature.clear();
        state.profiles.forEach(profile => {
            const enabled = !profile || typeof profile.enabled !== 'boolean' ? true : profile.enabled;
            const name = profile && typeof profile.name === 'string' ? profile.name.trim().toLowerCase() : '';
            if (name) profileEnabledByName.set(name, enabled);
            const signature = profileSignature(profile);
            if (!signature) return;
            if (!profileEnabledBySignature.has(signature)) profileEnabledBySignature.set(signature, enabled);
            else if (profileEnabledBySignature.get(signature) !== enabled) profileEnabledBySignature.set(signature, null);
        });
    }

    function preparePolicySaveOptions(url, options) {
        const parsed = new URL(String(url), 'https://native.cleverestricky.invalid');
        const method = String(options.method || 'GET').toUpperCase();
        if (parsed.pathname !== '/api/policy_state' || method !== 'POST' || !(options.body instanceof URLSearchParams)) {
            return options;
        }
        const encoded = options.body.get('data');
        if (typeof encoded !== 'string' || encoded.length > 1024 * 1024) return options;

        let state;
        try {
            state = JSON.parse(encoded);
        } catch (_) {
            return options;
        }
        if (!state || typeof state !== 'object' || !Array.isArray(state.profiles)) return options;

        state.profiles.forEach(profile => {
            if (!profile || typeof profile !== 'object' || typeof profile.enabled === 'boolean') return;
            const name = typeof profile.name === 'string' ? profile.name.trim().toLowerCase() : '';
            const signature = profileSignature(profile);
            if (name && profileEnabledByName.has(name)) {
                profile.enabled = profileEnabledByName.get(name);
            } else if (signature && typeof profileEnabledBySignature.get(signature) === 'boolean') {
                profile.enabled = profileEnabledBySignature.get(signature);
            } else {
                profile.enabled = true;
            }
        });
        if (state.activeProfile) {
            const activeName = String(state.activeProfile).trim().toLowerCase();
            const active = state.profiles.find(profile => profile && String(profile.name || '').trim().toLowerCase() === activeName);
            if (active && active.enabled === false) state.activeProfile = null;
        }

        const body = new URLSearchParams(options.body.toString());
        body.set('data', JSON.stringify(state));
        return Object.assign({}, options, { body });
    }

    async function rememberPolicyResponse(url, response) {
        if (!response || !response.ok) return;
        const parsed = new URL(String(url), 'https://native.cleverestricky.invalid');
        if (!policyResponsePaths.has(parsed.pathname)) return;
        try {
            const state = await response.clone().json();
            rememberPolicyState(state);
        } catch (_) {
        }
    }

    async function nativeFetch(url, options = {}) {
        const effectiveOptions = preparePolicySaveOptions(url, options);
        const requestedTimeout = Number(effectiveOptions.timeoutMs ?? 60000);
        const timeoutMs = Number.isFinite(requestedTimeout) ? Math.min(Math.max(Math.trunc(requestedTimeout), 1000), 120000) : 60000;
        const signal = effectiveOptions.signal || null;
        throwIfAborted(signal);
        let request;
        try {
            request = await prepareRequest(url, effectiveOptions, timeoutMs, signal);
            throwIfAborted(signal);
            const response = await callRequest(request, timeoutMs, signal);
            await rememberPolicyResponse(url, response);
            return response;
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
        if (response.bodyUsed) throw new TypeError('Response body has already been consumed');
        if (response.downloadRef && response.downloadRef.id && !response.downloadRef.shared && !response.downloadRef.promise && !response.downloadRef.bytes) {
            const reference = response.downloadRef;
            const id = reference.id;
            response.bodyUsed = true;
            response.downloadId = null;
            response.downloadRef = null;
            reference.id = null;
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
        const dashboard = document.getElementById('dashboard');
        if (!dashboard) return;

        const card = document.createElement('section');
        card.id = 'cleveresCommunityCard';
        card.setAttribute('aria-label', 'CleveresTech Telegram community');
        card.style.cssText = 'box-sizing:border-box;width:100%;margin:20px 0 24px;padding:0;text-align:center;';

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
        dashboard.appendChild(card);
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

    function routeExternalLinks() {
        const document = global.document;
        if (!document || !document.documentElement || !document.documentElement.dataset || document.documentElement.dataset.ctExternalLinkRouting) return;
        document.documentElement.dataset.ctExternalLinkRouting = '1';
        document.addEventListener('click', event => {
            const link = event.target && event.target.closest ? event.target.closest('a[href]') : null;
            if (!link || link.href !== keyboxHubUrl) return;
            event.preventDefault();
            event.stopPropagation();
            openKeyboxHub().catch(() => {});
        }, true);
    }

    function relaxNativeFilePicker(input) {
        if (!input || !nativeFilePickerIds.has(input.id)) return;
        input.accept = '*/*';
    }

    function installNativeFilePickerCompatibility() {
        const document = global.document;
        if (!document) return;
        nativeFilePickerIds.forEach(id => relaxNativeFilePicker(document.getElementById(id)));
        if (!document.documentElement || document.documentElement.dataset.ctNativePickerCompatibility) return;
        document.documentElement.dataset.ctNativePickerCompatibility = '1';
        document.addEventListener('click', event => {
            const target = event.target;
            if (target && target.id) relaxNativeFilePicker(target);
        }, true);
    }

    function notifyExtension(message, type = 'normal') {
        if (typeof global.notify === 'function') global.notify(extensionText(message), type);
    }

    async function readPolicyState() {
        if (policyStateRequest) return policyStateRequest;
        const request = (async () => {
            const response = await nativeFetch('/api/policy_state');
            if (!response.ok) throw new Error(await response.text());
            const state = await response.json();
            rememberPolicyState(state);
            return state;
        })();
        policyStateRequest = request;
        try {
            return await request;
        } finally {
            if (policyStateRequest === request) policyStateRequest = null;
        }
    }

    async function performProfileEnabledMutation(profile, enabled) {
        if (!profile || typeof profile !== 'object' || typeof profile.name !== 'string') throw new Error('Invalid profile');
        const nextProfile = JSON.parse(JSON.stringify(profile));
        nextProfile.enabled = Boolean(enabled);
        const body = new URLSearchParams();
        body.set('action', 'edit');
        body.set('data', JSON.stringify({ name: profile.name, profile: nextProfile }));
        const response = await nativeFetch('/api/profile_v2', { method: 'POST', body });
        if (!response.ok) throw new Error(await response.text());
        const state = await response.json();
        rememberPolicyState(state);
        return state;
    }

    function setProfileEnabled(profile, enabled) {
        const operation = profileMutationQueue.catch(() => {}).then(() => performProfileEnabledMutation(profile, enabled));
        profileMutationQueue = operation.catch(() => {});
        return operation;
    }

    function decorateProfileEnablement() {
        const document = global.document;
        const list = document && document.getElementById('ct_profile_list');
        const profiles = latestPolicyState && Array.isArray(latestPolicyState.profiles) ? latestPolicyState.profiles : [];
        if (!list || !profiles.length) return;
        const rows = Array.from(list.querySelectorAll('.ct-profile-item'));
        rows.forEach((row, index) => {
            if (row.dataset.ctProfileEnablement === '1') return;
            const profile = profiles[index];
            if (!profile) return;
            row.dataset.ctProfileEnablement = '1';

            const label = document.createElement('label');
            label.className = 'ct-profile-enabled-control';
            label.style.cssText = 'display:inline-flex;align-items:center;gap:8px;min-height:44px;flex:0 0 auto;';
            const text = document.createElement('span');
            text.dataset.ctExtensionCopy = 'profile-state';
            text.textContent = extensionText(profile.enabled === false ? 'Disabled' : 'Enabled');
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.className = 'ct-switch';
            input.checked = profile.enabled !== false;
            input.setAttribute('aria-label', `${profile.name}: ${extensionText('Enabled')}`);
            input.addEventListener('change', async () => {
                const requested = input.checked;
                input.disabled = true;
                try {
                    await setProfileEnabled(profile, requested);
                    text.textContent = extensionText(requested ? 'Enabled' : 'Disabled');
                    notifyExtension(requested ? 'Profile enabled' : 'Profile disabled');
                    if (typeof global.dispatchEvent === 'function' && typeof global.CustomEvent === 'function') {
                        global.dispatchEvent(new global.CustomEvent('ct-policy-state', { detail: latestPolicyState }));
                    }
                } catch (error) {
                    input.checked = !requested;
                    if (typeof global.notify === 'function') global.notify(error.message || 'Could not update profile', 'error');
                } finally {
                    input.disabled = false;
                }
            });
            label.append(text, input);
            const editButton = row.querySelector('[data-edit-profile]');
            row.insertBefore(label, editButton || null);
        });
    }

    async function ensureProfileEnablement() {
        const document = global.document;
        const list = document && document.getElementById('ct_profile_list');
        if (!list) return false;
        if (!latestPolicyState) {
            try { await readPolicyState(); } catch (_) { return false; }
        }
        decorateProfileEnablement();
        if (!list.dataset.ctProfileEnablementObserver && typeof global.MutationObserver === 'function') {
            list.dataset.ctProfileEnablementObserver = '1';
            new global.MutationObserver(() => decorateProfileEnablement()).observe(list, { childList: true });
        }
        return true;
    }

    function getCronAutoIdentity() {
        return execHostCommand(`[ -f '${cronAutoIdentityFlag}' ] && [ ! -L '${cronAutoIdentityFlag}' ] && printf on || printf off`, 5000)
            .then(value => value === 'on');
    }

    async function setCronAutoIdentity(enabled) {
        const command = enabled
            ? `umask 077; [ -d '${configRoot}' ] && [ ! -L '${configRoot}' ] || exit 2; [ ! -L '${cronAutoIdentityFlag}' ] || exit 2; : > '${cronAutoIdentityFlag}'; chmod 0600 '${cronAutoIdentityFlag}'`
            : `[ ! -L '${cronAutoIdentityFlag}' ] || exit 2; rm -f '${cronAutoIdentityFlag}'`;
        await execHostCommand(command, 5000);
        return Boolean(enabled);
    }
    async function installCronAutoIdentity() {
        const document = global.document;
        const identityPanel = document && document.querySelector('#spoof .panel');
        const notes = identityPanel ? Array.from(identityPanel.querySelectorAll('.scope-note')) : [];
        const autoIdentityNote = notes.length > 1 ? notes[notes.length - 1] : notes[0];
        if (!identityPanel || !autoIdentityNote || document.getElementById('ct_cron_auto_identity_row')) return Boolean(identityPanel);

        const row = document.createElement('div');
        row.id = 'ct_cron_auto_identity_row';
        row.className = 'row';
        row.style.cssText = 'margin:12px 0 0;padding-top:12px;border-top:1px dashed var(--border);';
        const label = document.createElement('label');
        label.htmlFor = 'ct_cron_auto_identity';
        label.style.cssText = 'flex:1;padding-right:10px;';
        const title = document.createElement('strong');
        title.style.color = '#fff';
        title.dataset.ctExtensionSource = 'Cron Auto Identity';
        title.textContent = extensionText('Cron Auto Identity');
        const description = document.createElement('span');
        description.className = 'res-desc';
        description.dataset.ctExtensionSource = 'Fetches and applies a fresh Auto Identity every 24 hours while Build Identity is enabled.';
        description.textContent = extensionText(description.dataset.ctExtensionSource);
        label.append(title, description);

        const toggle = document.createElement('input');
        toggle.id = 'ct_cron_auto_identity';
        toggle.type = 'checkbox';
        toggle.className = 'ct-switch';
        toggle.disabled = true;
        toggle.addEventListener('change', async () => {
            const requested = toggle.checked;
            toggle.disabled = true;
            try {
                await setCronAutoIdentity(requested);
                notifyExtension(requested ? 'Enabled' : 'Disabled');
            } catch (error) {
                toggle.checked = !requested;
                if (typeof global.notify === 'function') global.notify(error.message || 'Could not update Cron Auto Identity', 'error');
            } finally {
                toggle.disabled = false;
            }
        });
        row.append(label, toggle);
        autoIdentityNote.insertAdjacentElement('afterend', row);

        try {
            toggle.checked = await getCronAutoIdentity();
        } catch (_) {
            toggle.checked = false;
        } finally {
            toggle.disabled = false;
        }
        return true;
    }

    async function disableLegacyBuildIdentityMarker() {
        const body = new URLSearchParams();
        body.set('setting', 'spoof_build_identity');
        body.set('value', 'false');
        try {
            const response = await nativeFetch('/api/toggle', { method: 'POST', body });
            if (!response.ok) throw new Error(await response.text());
        } catch (_) {
            await execHostCommand(`[ ! -L '${configRoot}/spoof_build_identity' ] || exit 2; rm -f '${configRoot}/spoof_build_identity'`, 5000);
        }
    }

    const liveIdentityCommand =
        `CONFIG='${configRoot}'; ` +
        `[ -d "$CONFIG" ] && [ ! -L "$CONFIG" ] || { echo unsafe_config >&2; exit 2; }; ` +
        `SCRIPT=''; for ROOT in /data/adb/modules/cleverestricky /data/adb/ksu/modules/cleverestricky /data/adb/ap/modules/cleverestricky; do CANDIDATE="$ROOT/post-fs-data.sh"; [ -f "$CANDIDATE" ] && [ ! -L "$CANDIDATE" ] && { SCRIPT="$CANDIDATE"; break; }; done; ` +
        `[ -n "$SCRIPT" ] || { echo script_unavailable >&2; exit 4; }; command -v resetprop >/dev/null 2>&1 || { echo resetprop_unavailable >&2; exit 5; }; ` +
        `CLEVERES_TRICKY_IDENTITY_ONLY=1 CLEVERES_TRICKY_CONFIG_DIR="$CONFIG" /system/bin/sh "$SCRIPT" >/dev/null 2>&1 || { echo apply_failed >&2; exit 6; }; printf applied`;

    const verifyBuildIdentityCommand =
        `CONFIG='${configRoot}'; VARS="$CONFIG/spoof_build_vars"; ` +
        `[ -f "$VARS" ] && [ ! -L "$VARS" ] || { echo vars_unavailable >&2; exit 7; }; ` +
        `VARS_SIZE=$(wc -c < "$VARS" 2>/dev/null) || exit 7; case "$VARS_SIZE" in ''|*[!0-9]*) exit 7;; esac; [ "$VARS_SIZE" -le 1048576 ] || exit 7; ` +
        `EXPECTED=$(awk -F= '$1=="FINGERPRINT"{value=substr($0,index($0,"=")+1)} END{print value}' "$VARS"); ` +
        `[ -n "$EXPECTED" ] || { echo fingerprint_unavailable >&2; exit 7; }; [ "$(getprop ro.build.fingerprint)" = "$EXPECTED" ] || { echo verification_failed >&2; exit 8; }; printf verified`;

    const verifyRegionIdentityCommand =
        `[ "$(getprop ro.boot.hwc)" = CN ] || { echo region_verification_failed >&2; exit 9; }; printf verified`;

    async function applyIdentityLive(stateOverride = null) {
        try {
            const state = stateOverride && typeof stateOverride === 'object' ? stateOverride : await readPolicyState();
            await execHostCommand(liveIdentityCommand, 15000);
            if (state.features && state.features.buildIdentity) await execHostCommand(verifyBuildIdentityCommand, 5000);
            if (state.features && state.features.regionIdentity) await execHostCommand(verifyRegionIdentityCommand, 5000);
            return { applied: true, rebootRequired: false };
        } catch (error) {
            return { applied: false, rebootRequired: true, error };
        }
    }

    function notifyLiveIdentityResult(result) {
        if (result && result.applied) {
            notifyExtension('Identity applied without reboot. Restart open apps if they still show previous Build values.');
        } else {
            notifyExtension('Live Identity apply is unavailable. Reboot is required for these Build/region property changes.', 'error');
        }
    }

    function continueIdentitySaveWrapper(attempt) {
        const current = global.applySpoofing;
        if (typeof current !== 'function') {
            if (attempt < 100) global.setTimeout(() => continueIdentitySaveWrapper(attempt + 1), 100);
            else identityWrapperInstallStarted = false;
            return;
        }
        if (current.ctLiveIdentityApply) return;
        if (!current.ctSavedBuildIdentity && attempt < 50) {
            global.setTimeout(() => continueIdentitySaveWrapper(attempt + 1), 100);
            return;
        }
        const wrapped = async function () {
            const result = await current.apply(this, arguments);
            try {
                const state = await readPolicyState();
                if (state.features && (state.features.buildIdentity || state.features.regionIdentity)) {
                    notifyLiveIdentityResult(await applyIdentityLive(state));
                }
            } catch (error) {
                if (typeof global.notify === 'function') global.notify(error.message || 'Could not apply Identity live', 'error');
            }
            return result;
        };
        wrapped.ctLiveIdentityApply = true;
        global.applySpoofing = wrapped;
    }

    function installIdentitySaveWrapper() {
        if (identityWrapperInstallStarted || (global.applySpoofing && global.applySpoofing.ctLiveIdentityApply)) return;
        identityWrapperInstallStarted = true;
        continueIdentitySaveWrapper(0);
    }

    function installAutoIdentityOwner() {
        const current = global.applyAutoIdentity;
        if (typeof current !== 'function' || current.ctAutoIdentityOwner) return false;
        const wrapped = async function () {
            const before = await readPolicyState();
            const buildEnabled = Boolean(before.features && before.features.buildIdentity);
            const response = await nativeFetch('/api/auto_identity', { method: 'POST', timeoutMs: 120000 });
            if (!response.ok) throw new Error(await response.text());
            const data = await response.json();

            const select = global.document && global.document.getElementById('templateSelect');
            if (select) select.value = '';
            const model = global.document && global.document.getElementById('pModel');
            const manufacturer = global.document && global.document.getElementById('pManuf');
            const fingerprint = global.document && global.document.getElementById('pFing');
            if (model) model.innerText = String(data.model || 'Pixel Beta') + ' (Auto Identity)';
            if (manufacturer) manufacturer.innerText = 'Google';
            if (fingerprint) fingerprint.innerText = String(data.fingerprint || '');

            if (!buildEnabled) {
                await disableLegacyBuildIdentityMarker();
                notifyExtension('Auto Identity saved. Build Identity remains disabled.');
            } else {
                notifyLiveIdentityResult(await applyIdentityLive(before));
            }
            if (typeof global.loadIdentity === 'function') {
                try { await global.loadIdentity(); } catch (_) {}
            }
            return data;
        };
        wrapped.ctAutoIdentityOwner = true;
        global.applyAutoIdentity = wrapped;
        return true;
    }

    const cleveresLogsCommand =
        `{ logcat -d -t 2000 2>/dev/null | grep -E ' (CleveresTricky|cleverestricky|cleverestrickyd|cleverestricky_backend) *:' || true; ` +
        `if [ -f '${nativeRuntimeLog}' ] && [ ! -L '${nativeRuntimeLog}' ]; then printf '\n--- native runtime ---\n'; tail -n 1000 '${nativeRuntimeLog}' 2>/dev/null; fi; } | tail -n 2500 | tail -c 1048576`;

    async function loadCleveresLogs() {
        return (await execHostCommand(cleveresLogsCommand, 10000)).trim();
    }

    function installLogsOwner() {
        const original = global.fetchLogs;
        if (typeof original !== 'function' || original.ctCleveresLogs) return false;
        const wrapped = async function () {
            const typeNode = global.document && global.document.getElementById('logType');
            const type = typeNode ? typeNode.value : 'cleverestricky';
            if (type !== 'cleverestricky') return original.apply(this, arguments);
            try {
                const logs = await loadCleveresLogs();
                const viewer = global.document.getElementById('logViewer');
                if (viewer) {
                    viewer.value = logs.trim() || extensionText('No CleveresTricky logs found.');
                    viewer.scrollTop = viewer.scrollHeight;
                }
                if (typeof global.notify === 'function') global.notify(extensionText('Refresh Logs'));
            } catch (error) {
                if (typeof global.notify === 'function') global.notify(error.message || 'Failed to load logs', 'error');
            }
        };
        wrapped.ctCleveresLogs = true;
        global.fetchLogs = wrapped;
        return true;
    }

    function refreshExtensionCopy() {
        const document = global.document;
        if (!document) return;
        document.querySelectorAll('[data-ct-extension-source]').forEach(node => {
            node.textContent = extensionText(node.dataset.ctExtensionSource);
        });
        document.querySelectorAll('[data-ct-extension-copy="profile-state"]').forEach(node => {
            const input = node.parentElement && node.parentElement.querySelector('input[type="checkbox"]');
            node.textContent = extensionText(input && input.checked ? 'Enabled' : 'Disabled');
        });
    }

    function installRuntimeEnhancements(attempt = 0) {
        const document = global.document;
        if (!document || !document.body) return;
        ensureProfileEnablement().catch(() => {});
        installCronAutoIdentity().catch(() => {});
        const logsReady = installLogsOwner() || (typeof global.fetchLogs === 'function' && global.fetchLogs.ctCleveresLogs === true);
        installIdentitySaveWrapper();
        const autoIdentityReady = installAutoIdentityOwner() || (typeof global.applyAutoIdentity === 'function' && global.applyAutoIdentity.ctAutoIdentityOwner === true);

        const identityPanel = document.querySelector('#spoof .panel');
        if (identityPanel && !identityPanel.dataset.ctCronObserver && typeof global.MutationObserver === 'function') {
            identityPanel.dataset.ctCronObserver = '1';
            new global.MutationObserver(() => installCronAutoIdentity().catch(() => {})).observe(identityPanel, { childList: true, subtree: true });
        }
        const language = document.getElementById('ct_language_selector');
        if (language && !language.dataset.ctExtensionLanguage) {
            language.dataset.ctExtensionLanguage = '1';
            language.addEventListener('change', () => global.setTimeout(refreshExtensionCopy, 0));
        }
        if (attempt < 100 && (!document.getElementById('ct_profile_list') || !document.getElementById('ct_cron_auto_identity_row') || !logsReady || !autoIdentityReady)) {
            global.setTimeout(() => installRuntimeEnhancements(attempt + 1), 100);
        }
    }

    function scheduleRuntimeEnhancements() {
        const document = global.document;
        if (!document || !document.getElementById('tab_dashboard')) return;
        const start = () => global.setTimeout(() => installRuntimeEnhancements(), 0);
        if (document.readyState === 'complete') start();
        else global.addEventListener('load', start, { once: true });
    }

    function loadUxEnhancements() {
        const document = global.document;
        if (!document || !document.head || !document.createElement || document.getElementById('ct_ux_script')) return;
        const script = document.createElement('script');
        script.id = 'ct_ux_script';
        script.src = 'ux.js?revision=9';
        script.defer = true;
        script.onload = () => global.setTimeout(installNativeFilePickerCompatibility, 0);
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
    routeExternalLinks();
    installNativeFilePickerCompatibility();
    scheduleRuntimeEnhancements();
    global.CleveresBridge = Object.freeze({
        revision: 13,
        fetch: nativeFetch,
        exportBlob,
        exportResponse,
        listPackages,
        openCommunity,
        openKeyboxHub,
        getDebugLogging,
        setDebugLogging,
        setProfileEnabled,
        getCronAutoIdentity,
        setCronAutoIdentity,
        applyIdentityLive,
        loadCleveresLogs,
        translateExtension: extensionText
    });
    loadUxEnhancements();
})(window);
