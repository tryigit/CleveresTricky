(function (global) {
    'use strict';

    const bridge = global.CleveresBridge;
    if (!bridge || typeof document === 'undefined') return;

    const translations = {
        tr: {
            'Backup Password (required, at least 12 characters)': 'Yedekleme Parolası (zorunlu, en az 12 karakter)',
            'Enter a strong backup password': 'Güçlü bir yedekleme parolası girin',
            'Show': 'Göster', 'Hide': 'Gizle', 'Export Encrypted Settings': 'Şifreli Ayarları Dışa Aktar',
            'Import Encrypted Settings': 'Şifreli Ayarları İçe Aktar', 'Synchronize Runtime': 'Çalışma Zamanını Senkronize Et',
            'Synchronizing...': 'Senkronize ediliyor...', 'Runtime settings synchronized': 'Çalışma zamanı ayarları senkronize edildi',
            'Runtime synchronization failed': 'Çalışma zamanı senkronizasyonu başarısız', 'Language & Localization': 'Dil ve Yerelleştirme',
            'DRM App Passthrough': 'DRM Uygulama Geçişi', 'DRM Identifier Privacy': 'DRM Tanımlayıcı Gizliliği',
            'Configure app profiles': 'Uygulama profillerini yapılandır', 'Type at least 2 characters to search installed packages.': 'Yüklü paketlerde aramak için en az 2 karakter yazın.',
            'No package matches.': 'Eşleşen paket yok.', 'Estimated impact:': 'Tahmini etki:',
            'CPU low when active; RAM low and bounded.': 'Etkin olduğunda CPU düşük; RAM düşük ve sınırlı.',
            'CPU low per matching call; RAM low and bounded.': 'Eşleşen çağrı başına CPU düşük; RAM düşük ve sınırlı.',
            'Idle cost negligible; brief CPU/network work during checks.': 'Boştaki maliyet ihmal edilebilir; kontrollerde kısa CPU/ağ yükü oluşur.',
            'Boot-only work; no steady-state CPU cost.': 'Yalnızca açılışta çalışır; sürekli CPU maliyeti yoktur.',
            'RAM scales with active verified chains but remains bounded.': 'RAM etkin doğrulanmış zincirlerle artar ancak sınırlı kalır.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'Paket araması önbelleğe alınır ve daha temiz bir seçici için sonuçlar sınırlandırılır.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": "drm_packages.txt içindeki paketleri Android'in gerçek Keystore yolunda tutar. DRM güvenlik seviyesini taklit etmez.",
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Profil gizliliğinde Isolate, uygulamaya özel kararlı bir DRM tanımlayıcısı kullanır; lisanslar, provisioning ve güvenlik seviyesi gerçek DRM yolunda kalır.'
        },
        'zh-CN': {
            'Backup Password (required, at least 12 characters)': '备份密码（必填，至少 12 个字符）', 'Enter a strong backup password': '输入强备份密码',
            'Show': '显示', 'Hide': '隐藏', 'Export Encrypted Settings': '导出加密设置', 'Import Encrypted Settings': '导入加密设置',
            'Synchronize Runtime': '同步运行时', 'Synchronizing...': '正在同步...', 'Runtime settings synchronized': '运行时设置已同步',
            'Runtime synchronization failed': '运行时同步失败', 'Language & Localization': '语言与本地化', 'DRM App Passthrough': 'DRM 应用直通',
            'DRM Identifier Privacy': 'DRM 标识符隐私', 'Configure app profiles': '配置应用档案', 'Type at least 2 characters to search installed packages.': '输入至少 2 个字符以搜索已安装的包。',
            'No package matches.': '没有匹配的包。', 'Estimated impact:': '预计影响：', 'CPU low when active; RAM low and bounded.': '启用时 CPU 占用低；RAM 占用低且有界。',
            'CPU low per matching call; RAM low and bounded.': '每次匹配调用的 CPU 开销低；RAM 占用低且有界。', 'Idle cost negligible; brief CPU/network work during checks.': '空闲开销可忽略；检查时会有短暂的 CPU/网络活动。',
            'Boot-only work; no steady-state CPU cost.': '仅在启动时工作；无持续 CPU 开销。', 'RAM scales with active verified chains but remains bounded.': 'RAM 会随活动验证链增加，但保持有界。',
            'Package lookup is cached and results are capped for a cleaner picker.': '包查询会缓存，并限制结果数量以保持选择器简洁。',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": '让 drm_packages.txt 中的应用继续使用 Android 的真实 Keystore 路径，不伪造 DRM 安全级别。',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Isolate 隐私模式使用稳定的应用级 DRM 标识符，同时许可证、配置和安全级别仍使用真实 DRM 路径。'
        },
        es: {
            'Backup Password (required, at least 12 characters)': 'Contraseña de copia (obligatoria, mínimo 12 caracteres)', 'Enter a strong backup password': 'Introduce una contraseña de copia segura',
            'Show': 'Mostrar', 'Hide': 'Ocultar', 'Export Encrypted Settings': 'Exportar ajustes cifrados', 'Import Encrypted Settings': 'Importar ajustes cifrados',
            'Synchronize Runtime': 'Sincronizar runtime', 'Synchronizing...': 'Sincronizando...', 'Runtime settings synchronized': 'Ajustes de runtime sincronizados',
            'Runtime synchronization failed': 'Falló la sincronización del runtime', 'Language & Localization': 'Idioma y localización', 'DRM App Passthrough': 'Paso directo de apps DRM',
            'DRM Identifier Privacy': 'Privacidad de identificadores DRM', 'Configure app profiles': 'Configurar perfiles de apps', 'Type at least 2 characters to search installed packages.': 'Escribe al menos 2 caracteres para buscar paquetes instalados.',
            'No package matches.': 'No hay paquetes coincidentes.', 'Estimated impact:': 'Impacto estimado:', 'CPU low when active; RAM low and bounded.': 'CPU baja cuando está activo; RAM baja y limitada.',
            'CPU low per matching call; RAM low and bounded.': 'CPU baja por llamada coincidente; RAM baja y limitada.', 'Idle cost negligible; brief CPU/network work during checks.': 'Coste en reposo despreciable; breves ráfagas de CPU/red durante las comprobaciones.',
            'Boot-only work; no steady-state CPU cost.': 'Trabajo solo al arrancar; sin coste de CPU sostenido.', 'RAM scales with active verified chains but remains bounded.': 'La RAM escala con las cadenas verificadas activas, pero permanece limitada.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'La búsqueda de paquetes usa caché y limita los resultados para un selector más limpio.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mantiene los paquetes de drm_packages.txt en la ruta Keystore real de Android. No simula un nivel de seguridad DRM.',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'La privacidad Isolate usa un identificador DRM estable por app mientras licencias, aprovisionamiento y nivel de seguridad siguen en la ruta DRM real.'
        },
        de: {
            'Backup Password (required, at least 12 characters)': 'Backup-Passwort (erforderlich, mindestens 12 Zeichen)', 'Enter a strong backup password': 'Ein starkes Backup-Passwort eingeben',
            'Show': 'Anzeigen', 'Hide': 'Ausblenden', 'Export Encrypted Settings': 'Verschlüsselte Einstellungen exportieren', 'Import Encrypted Settings': 'Verschlüsselte Einstellungen importieren',
            'Synchronize Runtime': 'Laufzeit synchronisieren', 'Synchronizing...': 'Synchronisiere...', 'Runtime settings synchronized': 'Laufzeiteinstellungen synchronisiert',
            'Runtime synchronization failed': 'Laufzeitsynchronisierung fehlgeschlagen', 'Language & Localization': 'Sprache und Lokalisierung', 'DRM App Passthrough': 'DRM-App-Durchleitung',
            'DRM Identifier Privacy': 'DRM-Kennungsdatenschutz', 'Configure app profiles': 'App-Profile konfigurieren', 'Type at least 2 characters to search installed packages.': 'Mindestens 2 Zeichen eingeben, um installierte Pakete zu suchen.',
            'No package matches.': 'Kein passendes Paket.', 'Estimated impact:': 'Geschätzte Auswirkung:', 'CPU low when active; RAM low and bounded.': 'CPU bei Aktivität niedrig; RAM niedrig und begrenzt.',
            'CPU low per matching call; RAM low and bounded.': 'CPU pro passendem Aufruf niedrig; RAM niedrig und begrenzt.', 'Idle cost negligible; brief CPU/network work during checks.': 'Leerlaufkosten vernachlässigbar; kurze CPU/Netzwerk-Arbeit bei Prüfungen.',
            'Boot-only work; no steady-state CPU cost.': 'Nur beim Start aktiv; keine dauerhafte CPU-Last.', 'RAM scales with active verified chains but remains bounded.': 'RAM steigt mit aktiven verifizierten Ketten, bleibt aber begrenzt.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'Paketsuche wird zwischengespeichert und für einen übersichtlichen Picker begrenzt.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Belässt Pakete aus drm_packages.txt auf dem echten Android-Keystore-Pfad. Es wird keine DRM-Sicherheitsstufe vorgetäuscht.',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Isolate verwendet eine stabile app-bezogene DRM-Kennung, während Lizenzen, Provisionierung und Sicherheitsstufe auf dem echten DRM-Pfad bleiben.'
        },
        ru: {
            'Backup Password (required, at least 12 characters)': 'Пароль резервной копии (обязательно, минимум 12 символов)', 'Enter a strong backup password': 'Введите надежный пароль резервной копии',
            'Show': 'Показать', 'Hide': 'Скрыть', 'Export Encrypted Settings': 'Экспортировать зашифрованные настройки', 'Import Encrypted Settings': 'Импортировать зашифрованные настройки',
            'Synchronize Runtime': 'Синхронизировать runtime', 'Synchronizing...': 'Синхронизация...', 'Runtime settings synchronized': 'Настройки runtime синхронизированы',
            'Runtime synchronization failed': 'Не удалось синхронизировать runtime', 'Language & Localization': 'Язык и локализация', 'DRM App Passthrough': 'DRM passthrough для приложений',
            'DRM Identifier Privacy': 'Приватность DRM-идентификатора', 'Configure app profiles': 'Настроить профили приложений', 'Type at least 2 characters to search installed packages.': 'Введите минимум 2 символа для поиска установленных пакетов.',
            'No package matches.': 'Совпадающих пакетов нет.', 'Estimated impact:': 'Оценка нагрузки:', 'CPU low when active; RAM low and bounded.': 'CPU низкий при активности; RAM низкая и ограниченная.',
            'CPU low per matching call; RAM low and bounded.': 'CPU низкий на совпадающий вызов; RAM низкая и ограниченная.', 'Idle cost negligible; brief CPU/network work during checks.': 'В простое затраты пренебрежимо малы; во время проверок краткая нагрузка CPU/сети.',
            'Boot-only work; no steady-state CPU cost.': 'Работа только при загрузке; постоянной нагрузки CPU нет.', 'RAM scales with active verified chains but remains bounded.': 'RAM растет с числом активных проверенных цепочек, но остается ограниченной.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'Поиск пакетов кэшируется, а результаты ограничены для более аккуратного выбора.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Оставляет пакеты из drm_packages.txt на настоящем пути Android Keystore и не подменяет уровень безопасности DRM.',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Режим Isolate использует стабильный DRM-идентификатор для приложения, а лицензии, provisioning и уровень безопасности остаются на настоящем DRM-пути.'
        },
        id: {
            'Backup Password (required, at least 12 characters)': 'Kata Sandi Cadangan (wajib, minimal 12 karakter)', 'Enter a strong backup password': 'Masukkan kata sandi cadangan yang kuat',
            'Show': 'Tampilkan', 'Hide': 'Sembunyikan', 'Export Encrypted Settings': 'Ekspor Pengaturan Terenkripsi', 'Import Encrypted Settings': 'Impor Pengaturan Terenkripsi',
            'Synchronize Runtime': 'Sinkronkan Runtime', 'Synchronizing...': 'Menyinkronkan...', 'Runtime settings synchronized': 'Pengaturan runtime tersinkron',
            'Runtime synchronization failed': 'Sinkronisasi runtime gagal', 'Language & Localization': 'Bahasa dan Lokalisasi', 'DRM App Passthrough': 'Passthrough Aplikasi DRM',
            'DRM Identifier Privacy': 'Privasi Pengenal DRM', 'Configure app profiles': 'Konfigurasikan profil aplikasi', 'Type at least 2 characters to search installed packages.': 'Ketik minimal 2 karakter untuk mencari paket terpasang.',
            'No package matches.': 'Tidak ada paket yang cocok.', 'Estimated impact:': 'Perkiraan dampak:', 'CPU low when active; RAM low and bounded.': 'CPU rendah saat aktif; RAM rendah dan terbatas.',
            'CPU low per matching call; RAM low and bounded.': 'CPU rendah per panggilan yang cocok; RAM rendah dan terbatas.', 'Idle cost negligible; brief CPU/network work during checks.': 'Biaya idle dapat diabaikan; ada kerja CPU/jaringan singkat saat pemeriksaan.',
            'Boot-only work; no steady-state CPU cost.': 'Hanya bekerja saat boot; tanpa biaya CPU terus-menerus.', 'RAM scales with active verified chains but remains bounded.': 'RAM bertambah sesuai rantai terverifikasi aktif namun tetap terbatas.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'Pencarian paket dicache dan hasil dibatasi agar pemilih lebih bersih.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mempertahankan paket di drm_packages.txt pada jalur Keystore Android asli dan tidak memalsukan level keamanan DRM.',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Privasi Isolate memakai pengenal DRM stabil per aplikasi sementara lisensi, provisioning, dan level keamanan tetap pada jalur DRM asli.'
        },
        hi: {
            'Backup Password (required, at least 12 characters)': 'बैकअप पासवर्ड (आवश्यक, कम से कम 12 अक्षर)', 'Enter a strong backup password': 'मजबूत बैकअप पासवर्ड दर्ज करें',
            'Show': 'दिखाएं', 'Hide': 'छिपाएं', 'Export Encrypted Settings': 'एन्क्रिप्टेड सेटिंग्स निर्यात करें', 'Import Encrypted Settings': 'एन्क्रिप्टेड सेटिंग्स आयात करें',
            'Synchronize Runtime': 'रनटाइम सिंक करें', 'Synchronizing...': 'सिंक हो रहा है...', 'Runtime settings synchronized': 'रनटाइम सेटिंग्स सिंक हो गईं',
            'Runtime synchronization failed': 'रनटाइम सिंक विफल', 'Language & Localization': 'भाषा और स्थानीयकरण', 'DRM App Passthrough': 'DRM ऐप पासथ्रू',
            'DRM Identifier Privacy': 'DRM पहचान गोपनीयता', 'Configure app profiles': 'ऐप प्रोफाइल कॉन्फ़िगर करें', 'Type at least 2 characters to search installed packages.': 'इंस्टॉल पैकेज खोजने के लिए कम से कम 2 अक्षर लिखें।',
            'No package matches.': 'कोई पैकेज मेल नहीं खाता।', 'Estimated impact:': 'अनुमानित प्रभाव:', 'CPU low when active; RAM low and bounded.': 'सक्रिय होने पर CPU कम; RAM कम और सीमित।',
            'CPU low per matching call; RAM low and bounded.': 'हर मिलती कॉल पर CPU कम; RAM कम और सीमित।', 'Idle cost negligible; brief CPU/network work during checks.': 'निष्क्रिय लागत नगण्य; जांच के दौरान थोड़ी CPU/नेटवर्क गतिविधि।',
            'Boot-only work; no steady-state CPU cost.': 'केवल बूट पर काम; लगातार CPU लागत नहीं।', 'RAM scales with active verified chains but remains bounded.': 'RAM सक्रिय सत्यापित चेन के साथ बढ़ती है लेकिन सीमित रहती है।',
            'Package lookup is cached and results are capped for a cleaner picker.': 'पैकेज खोज कैश होती है और साफ चयन के लिए परिणाम सीमित रहते हैं।',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt में सूचीबद्ध पैकेज Android के वास्तविक Keystore पथ पर रहते हैं और DRM सुरक्षा स्तर नकली नहीं होता।',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'Isolate गोपनीयता स्थिर ऐप-स्कोप DRM पहचान का उपयोग करती है, जबकि लाइसेंस, provisioning और सुरक्षा स्तर वास्तविक DRM पथ पर रहते हैं।'
        },
        ar: {
            'Backup Password (required, at least 12 characters)': 'كلمة مرور النسخة الاحتياطية (مطلوبة، 12 حرفا على الأقل)', 'Enter a strong backup password': 'أدخل كلمة مرور قوية للنسخة الاحتياطية',
            'Show': 'إظهار', 'Hide': 'إخفاء', 'Export Encrypted Settings': 'تصدير الإعدادات المشفرة', 'Import Encrypted Settings': 'استيراد الإعدادات المشفرة',
            'Synchronize Runtime': 'مزامنة وقت التشغيل', 'Synchronizing...': 'جار المزامنة...', 'Runtime settings synchronized': 'تمت مزامنة إعدادات وقت التشغيل',
            'Runtime synchronization failed': 'فشلت مزامنة وقت التشغيل', 'Language & Localization': 'اللغة والتوطين', 'DRM App Passthrough': 'تمرير تطبيقات DRM',
            'DRM Identifier Privacy': 'خصوصية معرف DRM', 'Configure app profiles': 'تهيئة ملفات تعريف التطبيقات', 'Type at least 2 characters to search installed packages.': 'اكتب حرفين على الأقل للبحث في الحزم المثبتة.',
            'No package matches.': 'لا توجد حزمة مطابقة.', 'Estimated impact:': 'الأثر التقديري:', 'CPU low when active; RAM low and bounded.': 'استهلاك CPU منخفض عند النشاط؛ وRAM منخفضة ومحدودة.',
            'CPU low per matching call; RAM low and bounded.': 'استهلاك CPU منخفض لكل استدعاء مطابق؛ وRAM منخفضة ومحدودة.', 'Idle cost negligible; brief CPU/network work during checks.': 'تكلفة الخمول ضئيلة؛ مع عمل قصير للمعالج والشبكة أثناء الفحص.',
            'Boot-only work; no steady-state CPU cost.': 'يعمل عند الإقلاع فقط؛ بلا تكلفة CPU مستمرة.', 'RAM scales with active verified chains but remains bounded.': 'تزداد RAM مع السلاسل الموثقة النشطة لكنها تظل محدودة.',
            'Package lookup is cached and results are capped for a cleaner picker.': 'يتم تخزين بحث الحزم مؤقتا وتحديد النتائج لجعل الاختيار أنظف.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'يبقي حزم drm_packages.txt على مسار Keystore الحقيقي في Android ولا يزيف مستوى أمان DRM.',
            'Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.': 'يستخدم وضع Isolate معرف DRM ثابتا خاصا بالتطبيق، بينما تبقى التراخيص والتهيئة ومستوى الأمان على مسار DRM الحقيقي.'
        }
    };

    const originalText = new WeakMap();
    const originalAttributes = new WeakMap();
    let configCache = null;
    let packagesCache = null;
    let featureObserver = null;
    let pickerObserver = null;
    let languageObserver = null;

    function locale() {
        const raw = String(document.documentElement.lang || localStorage.getItem('ct_language') || 'en');
        return translations[raw] ? raw : 'en';
    }

    function tr(text) {
        const map = translations[locale()];
        return map && map[text] ? map[text] : text;
    }

    function translateExtensions(root) {
        const target = root || document.body;
        if (!target) return;
        const walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) {
            const value = node.nodeValue || '';
            if (!originalText.has(node)) originalText.set(node, value);
            const canonical = originalText.get(node);
            const leading = canonical.match(/^\s*/)[0];
            const trailing = canonical.match(/\s*$/)[0];
            const trimmed = canonical.trim();
            if (trimmed && (translations.tr[trimmed] || translations['zh-CN'][trimmed])) {
                node.nodeValue = leading + tr(trimmed) + trailing;
            }
        }
        target.querySelectorAll('input[placeholder],button[aria-label],input[aria-label]').forEach(element => {
            let attrs = originalAttributes.get(element);
            if (!attrs) {
                attrs = {};
                ['placeholder','aria-label'].forEach(name => {
                    if (element.hasAttribute(name)) attrs[name] = element.getAttribute(name);
                });
                originalAttributes.set(element, attrs);
            }
            Object.entries(attrs).forEach(([name, canonical]) => element.setAttribute(name, tr(canonical)));
        });
    }

    function injectStyles() {
        if (document.getElementById('ctRuntimeSyncPatchStyle')) return;
        const style = document.createElement('style');
        style.id = 'ctRuntimeSyncPatchStyle';
        style.textContent = `
            .island{min-width:min(86vw,280px)!important;max-width:min(92vw,520px)!important;padding:11px 18px!important;opacity:0;transform:translate3d(-50%,-9px,0) scale(.985);transition:opacity 170ms ease,transform 220ms cubic-bezier(.22,.8,.25,1)!important;will-change:transform,opacity;contain:layout paint}
            .island.show{opacity:1;transform:translate3d(-50%,0,0) scale(1)}
            .ct-picker-wrap{position:relative;flex:1 1 240px;min-width:0}
            .ct-app-suggestions{position:absolute;z-index:1800;top:calc(100% + 6px);left:0;right:0;max-height:min(42dvh,320px);overflow:auto;background:#171719;border:1px solid var(--border);border-radius:12px;box-shadow:0 16px 34px rgba(0,0,0,.42);padding:6px}
            .ct-app-suggestions[hidden]{display:none!important}
            .ct-app-suggestion{display:block;width:100%;text-align:left;background:transparent;border:0;border-radius:8px;padding:10px 11px;color:#eee;font:inherit;overflow-wrap:anywhere}
            .ct-app-suggestion:hover,.ct-app-suggestion.active{background:#2a2a2e}
            .ct-impact-note{display:block;margin-top:5px;color:#7f8794;font-size:.78em;line-height:1.35}
            #ct_drm_feature_children{margin-top:12px;padding-top:10px;border-top:1px solid var(--border)}
            #ct_language_panel{scroll-margin-bottom:90px}
        `;
        document.head.appendChild(style);
    }

    async function requestConfig() {
        const response = await bridge.fetch('/api/config');
        if (!response.ok) throw new Error(await response.text());
        configCache = await response.json();
        return configCache;
    }

    function configurationPanel() {
        const password = document.getElementById('backupPw');
        return password && password.closest('.panel');
    }

    function moveLanguagePanel() {
        const panel = document.getElementById('ct_language_panel');
        const config = configurationPanel();
        if (!panel || !config || !config.parentElement) return;
        const title = panel.querySelector('h3');
        if (title) title.textContent = tr('Language & Localization');
        if (config.nextElementSibling !== panel) config.parentElement.insertBefore(panel, config.nextSibling);
    }

    function reorderTabs() {
        const tabs = document.querySelector('.tabs');
        const dashboard = document.getElementById('tab_dashboard');
        const keyboxes = document.getElementById('tab_keys');
        if (tabs && dashboard && keyboxes && dashboard.nextElementSibling !== keyboxes) tabs.insertBefore(keyboxes, dashboard.nextSibling);
        const effective = document.getElementById('tab_effective');
        if (effective) effective.remove();
    }

    function moveEffectiveIntoApps() {
        const effective = document.getElementById('effective');
        const apps = document.getElementById('apps');
        if (!effective || !apps) return;
        let host = document.getElementById('ct_effective_apps_host');
        if (!host) {
            host = document.createElement('div');
            host.id = 'ct_effective_apps_host';
            apps.appendChild(host);
        }
        while (effective.firstChild) host.appendChild(effective.firstChild);
        effective.remove();
        const tab = document.getElementById('tab_effective');
        if (tab) tab.remove();
    }

    function removeQuickControls() {
        const panel = document.getElementById('ct_resources_controls');
        if (panel) panel.remove();
    }

    function hideRetiredRkp() {
        document.querySelectorAll('#rkp_passthrough,#res_toggle_rkp_passthrough,#status_rkp').forEach(element => {
            const row = element.closest('.row') || element.closest('tr');
            if (row) row.hidden = true;
            else element.hidden = true;
        });
        document.querySelectorAll('#resourceBody tr').forEach(row => {
            if (/RKP Passthrough/i.test(row.textContent || '')) row.hidden = true;
        });
    }

    function updateDrmState(data) {
        const enabled = Boolean(data && data.drm_passthrough);
        const checkbox = document.getElementById('ct_dash_drm_passthrough');
        const children = document.getElementById('ct_drm_feature_children');
        if (checkbox) checkbox.checked = enabled;
        if (children) children.hidden = !enabled;
    }

    async function setSetting(setting, value) {
        const body = new URLSearchParams();
        body.set('setting', setting);
        body.set('value', value ? 'true' : 'false');
        const response = await bridge.fetch('/api/toggle', {method:'POST', body});
        if (!response.ok) throw new Error(await response.text());
        return requestConfig();
    }

    function syncVisibleToggles(data) {
        if (!data) return;
        const ids = {
            global_mode: ['global_mode','res_toggle_global_mode','ct_dash_global','ct_res_global'],
            auto_keybox_check: ['auto_keybox_check','res_toggle_auto_keybox_check','ct_dash_keybox','ct_res_keybox'],
            drm_passthrough: ['drm_passthrough','res_toggle_drm_passthrough','ct_drm_passthrough_toggle','ct_dash_drm_passthrough'],
            spoof_enabled: ['spoof_enabled','res_toggle_spoof_enabled'],
            spoof_build_identity: ['spoof_build_identity','res_toggle_spoof_build_identity'],
            random_on_boot: ['random_on_boot','res_toggle_random_on_boot'],
            telephony: ['telephony','res_toggle_telephony'],
            spoof_region_cn: ['spoof_region_cn','res_toggle_spoof_region_cn']
        };
        Object.entries(ids).forEach(([setting, candidates]) => {
            if (typeof data[setting] !== 'boolean') return;
            candidates.forEach(id => {
                const checkbox = document.getElementById(id);
                if (checkbox && checkbox.type === 'checkbox') checkbox.checked = data[setting];
            });
        });
        updateDrmState(data);
    }

    function customizeDrmFeature() {
        const legacy = document.getElementById('ct_drm_dashboard_panel');
        if (legacy) legacy.remove();
        const host = document.querySelector('#ct_dashboard_controls .ct-control-host');
        if (!host) return;
        const cards = [...host.querySelectorAll('.ct-feature-card')];
        let card = cards.find(item => item.dataset.ctDrmFeature === '1');
        if (!card) card = cards.find(item => /DRM Identifier Privacy/i.test(item.textContent || ''));
        if (!card) return;
        if (card.dataset.ctDrmFeature !== '1') {
            card.dataset.ctDrmFeature = '1';
            card.innerHTML = `<div class="row"><label for="ct_dash_drm_passthrough" style="flex:1;min-width:0;padding-right:12px"><strong>DRM App Passthrough</strong><span class="res-desc">Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.</span></label><input id="ct_dash_drm_passthrough" type="checkbox" class="toggle"></div><div id="ct_drm_feature_children" hidden><strong>DRM Identifier Privacy</strong><p>Profile privacy Isolate uses a stable app-scoped DRM identifier while licenses, provisioning and the security level remain on the genuine DRM path.</p><button type="button" id="ct_drm_profiles" style="width:100%;margin-top:10px">Configure app profiles</button></div>`;
            card.querySelector('#ct_dash_drm_passthrough').addEventListener('change', async event => {
                const checkbox = event.currentTarget;
                const wanted = checkbox.checked;
                checkbox.disabled = true;
                updateDrmState({drm_passthrough:wanted});
                try {
                    const data = await setSetting('drm_passthrough', wanted);
                    syncVisibleToggles(data);
                } catch (error) {
                    checkbox.checked = !wanted;
                    updateDrmState({drm_passthrough:!wanted});
                    if (typeof global.notify === 'function') global.notify(error.message || 'Could not update DRM setting', 'error');
                } finally {
                    checkbox.disabled = false;
                }
            });
            card.querySelector('#ct_drm_profiles').addEventListener('click', () => {
                if (typeof global.switchTab === 'function') global.switchTab('profiles');
            });
        }
        updateDrmState(configCache || {});
        translateExtensions(card);
    }

    function observeFeatureCenter() {
        const host = document.querySelector('#ct_dashboard_controls .ct-control-host');
        if (!host || featureObserver || typeof MutationObserver !== 'function') return;
        featureObserver = new MutationObserver(() => queueMicrotask(() => {
            customizeDrmFeature();
            removeQuickControls();
            requestConfig().then(syncVisibleToggles).catch(() => {});
        }));
        featureObserver.observe(host, {childList:true});
    }

    async function synchronizeRuntimeSettings() {
        const current = await requestConfig();
        for (const setting of ['global_mode','auto_keybox_check','drm_passthrough','spoof_enabled','spoof_build_identity','random_on_boot','spoof_region_cn','telephony']) {
            if (typeof current[setting] !== 'boolean') continue;
            const body = new URLSearchParams();
            body.set('setting', setting);
            body.set('value', current[setting] ? 'true' : 'false');
            const response = await bridge.fetch('/api/toggle', {method:'POST', body});
            if (!response.ok) throw new Error(await response.text());
        }
        const reload = await bridge.fetch('/api/reload', {method:'POST'});
        if (!reload.ok) throw new Error(await reload.text());
        const refreshed = await requestConfig();
        syncVisibleToggles(refreshed);
        return refreshed;
    }

    function installRuntimeSync() {
        const panel = configurationPanel();
        if (!panel) return;
        let button = document.getElementById('ct_runtime_sync');
        if (!button) {
            button = panel.querySelector('[onclick*="resetEnvironment"]');
            if (!button) return;
            button.id = 'ct_runtime_sync';
            button.removeAttribute('onclick');
            button.classList.remove('danger');
            button.classList.add('primary');
            button.textContent = 'Synchronize Runtime';
            button.addEventListener('click', async () => {
                if (button.disabled) return;
                button.disabled = true;
                button.setAttribute('aria-busy','true');
                button.textContent = tr('Synchronizing...');
                try {
                    await synchronizeRuntimeSettings();
                    if (typeof global.notify === 'function') global.notify(tr('Runtime settings synchronized'));
                } catch (error) {
                    if (typeof global.notify === 'function') global.notify(error.message || tr('Runtime synchronization failed'), 'error');
                } finally {
                    button.disabled = false;
                    button.removeAttribute('aria-busy');
                    button.textContent = tr('Synchronize Runtime');
                }
            });
        }
    }

    async function getPackages() {
        if (packagesCache) return packagesCache;
        const response = await bridge.fetch('/api/packages');
        if (!response.ok) throw new Error(await response.text());
        const data = await response.json();
        packagesCache = Array.isArray(data) ? data.map(String).filter(Boolean).sort() : [];
        return packagesCache;
    }

    function rankPackages(all, query) {
        const needle = query.toLowerCase();
        const scored = [];
        for (const value of all) {
            const lower = value.toLowerCase();
            let score = -1;
            if (lower === needle) score = 0;
            else if (lower.startsWith(needle)) score = 1;
            else if (lower.split('.').some(segment => segment.startsWith(needle))) score = 2;
            else if (lower.includes(needle)) score = 3;
            if (score >= 0) scored.push([score, value.length, value]);
        }
        scored.sort((a,b) => a[0] - b[0] || a[1] - b[1] || a[2].localeCompare(b[2]));
        return scored.slice(0,24).map(item => item[2]);
    }

    function installPicker(input) {
        if (!input || input.dataset.ctPicker === '1') return;
        input.dataset.ctPicker = '1';
        input.removeAttribute('list');
        const parent = input.parentElement;
        if (!parent) return;
        let wrap = parent;
        if (!parent.classList.contains('ct-picker-wrap')) {
            wrap = document.createElement('div');
            wrap.className = 'ct-picker-wrap';
            parent.insertBefore(wrap, input);
            wrap.appendChild(input);
        }
        const list = document.createElement('div');
        list.className = 'ct-app-suggestions';
        list.hidden = true;
        wrap.appendChild(list);
        let active = -1;

        const close = () => { list.hidden = true; list.replaceChildren(); active = -1; };
        const choose = value => {
            input.value = value;
            input.dispatchEvent(new Event('input', {bubbles:true}));
            input.dispatchEvent(new Event('change', {bubbles:true}));
            close();
            input.focus();
        };
        const render = async () => {
            const query = input.value.trim();
            if (query.length < 2) { close(); return; }
            try {
                const matches = rankPackages(await getPackages(), query);
                list.replaceChildren();
                active = -1;
                if (!matches.length) {
                    const empty = document.createElement('div');
                    empty.className = 'ct-app-suggestion';
                    empty.textContent = tr('No package matches.');
                    list.appendChild(empty);
                } else {
                    matches.forEach(value => {
                        const button = document.createElement('button');
                        button.type = 'button';
                        button.className = 'ct-app-suggestion';
                        button.textContent = value;
                        button.addEventListener('mousedown', event => event.preventDefault());
                        button.addEventListener('click', () => choose(value));
                        list.appendChild(button);
                    });
                }
                list.hidden = false;
            } catch (_) { close(); }
        };
        input.addEventListener('input', render);
        input.addEventListener('focus', render);
        input.addEventListener('keydown', event => {
            const buttons = [...list.querySelectorAll('button')];
            if (list.hidden || !buttons.length) return;
            if (event.key === 'Escape') { event.preventDefault(); close(); return; }
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                event.preventDefault();
                if (active >= 0) buttons[active].classList.remove('active');
                active = event.key === 'ArrowDown' ? (active + 1) % buttons.length : (active - 1 + buttons.length) % buttons.length;
                buttons[active].classList.add('active');
                buttons[active].scrollIntoView({block:'nearest'});
            } else if (event.key === 'Enter' && active >= 0) {
                event.preventDefault();
                choose(buttons[active].textContent);
            }
        });
        input.addEventListener('blur', () => global.setTimeout(close, 120));
    }

    function observePackagePickers() {
        if (pickerObserver || typeof MutationObserver !== 'function') return;
        const roots = ['profiles','patch','apps'].map(id => document.getElementById(id)).filter(Boolean);
        if (!roots.length) return;
        pickerObserver = new MutationObserver(() => queueMicrotask(installPackagePickers));
        roots.forEach(root => pickerObserver.observe(root, {childList:true, subtree:true}));
    }

    function installPackagePickers() {
        ['ct_profile_app_picker','ct_patch_package','ct_effective_package'].forEach(id => installPicker(document.getElementById(id)));
        const profile = document.getElementById('ct_profile_app_picker');
        if (profile && !document.getElementById('ct_picker_hint')) {
            const hint = document.createElement('div');
            hint.id = 'ct_picker_hint';
            hint.className = 'ct-inline-note';
            hint.textContent = tr('Type at least 2 characters to search installed packages.');
            const toolbar = profile.closest('.ct-toolbar');
            if (toolbar && toolbar.parentElement) toolbar.parentElement.insertBefore(hint, toolbar.nextSibling);
        }
        const legacyNote = document.querySelector('#apps .ct-inline-note');
        if (legacyNote && /System \/ preinstalled packages/i.test(legacyNote.textContent || '')) {
            legacyNote.textContent = tr('Package lookup is cached and results are capped for a cleaner picker.');
        }
    }

    function estimatedImpact(name) {
        if (/Keybox Storage/i.test(name)) return 'RAM scales with active verified chains but remains bounded.';
        if (/Automatic Keybox|Auto Keybox/i.test(name)) return 'Idle cost negligible; brief CPU/network work during checks.';
        if (/Identity Refresh|Template Build|Region Property/i.test(name)) return 'Boot-only work; no steady-state CPU cost.';
        if (/Keystore Runtime|Telephony|DRM App|App Rules/i.test(name)) return 'CPU low per matching call; RAM low and bounded.';
        return 'CPU low when active; RAM low and bounded.';
    }

    function annotateResourceImpact() {
        document.querySelectorAll('#resourceBody tr').forEach(row => {
            if (/RKP Passthrough/i.test(row.textContent || '')) { row.hidden = true; return; }
            const cell = row.querySelector('td');
            const desc = cell && cell.querySelector('.res-desc');
            if (!desc || desc.querySelector('.ct-impact-note')) return;
            const name = (cell.querySelector('div > div')?.textContent || cell.textContent || '').trim();
            const note = document.createElement('span');
            note.className = 'ct-impact-note';
            const prefix = document.createElement('span');
            prefix.textContent = tr('Estimated impact:');
            const value = document.createElement('span');
            value.textContent = tr(estimatedImpact(name));
            note.append(prefix, document.createTextNode(' '), value);
            desc.appendChild(note);
        });
    }

    function wrapRuntimeFunctions() {
        const originalToggle = global.toggle;
        if (typeof originalToggle === 'function' && !originalToggle.ctRuntimeSyncPatch) {
            const wrapped = function() {
                const result = originalToggle.apply(this, arguments);
                Promise.resolve(result).finally(() => requestConfig().then(syncVisibleToggles).catch(() => {}));
                return result;
            };
            wrapped.ctRuntimeSyncPatch = true;
            global.toggle = wrapped;
        }
        const originalResource = global.loadResourceUsage;
        if (typeof originalResource === 'function' && !originalResource.ctRuntimeSyncPatch) {
            const wrapped = async function() {
                const result = await originalResource.apply(this, arguments);
                queueMicrotask(() => {
                    removeQuickControls();
                    annotateResourceImpact();
                    hideRetiredRkp();
                    translateExtensions(document.getElementById('info'));
                    requestConfig().then(syncVisibleToggles).catch(() => {});
                });
                return result;
            };
            wrapped.ctRuntimeSyncPatch = true;
            global.loadResourceUsage = wrapped;
        }
        const originalSwitch = global.switchTab;
        if (typeof originalSwitch === 'function' && !originalSwitch.ctRuntimeSyncPatch) {
            const wrapped = function(name) {
                const result = originalSwitch.apply(this, arguments);
                queueMicrotask(apply);
                return result;
            };
            wrapped.ctRuntimeSyncPatch = true;
            global.switchTab = wrapped;
        }
        const originalKeys = global.handleTabNavigation;
        if (typeof originalKeys === 'function' && !originalKeys.ctRuntimeSyncPatch) {
            const wrapped = function(event, id) {
                if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return originalKeys.apply(this, arguments);
                event.preventDefault();
                const tabs = [...document.querySelectorAll('.tabs .tab')].filter(tab => !tab.hidden && tab.style.display !== 'none' && tab.id !== 'tab_effective');
                if (!tabs.length) return;
                let index = tabs.findIndex(tab => tab.id === 'tab_' + id);
                if (index < 0) index = tabs.findIndex(tab => tab.classList.contains('active'));
                index = event.key === 'ArrowRight' ? (index + 1) % tabs.length : (index - 1 + tabs.length) % tabs.length;
                const next = tabs[index];
                global.switchTab(next.id.replace(/^tab_/,''));
                next.focus();
            };
            wrapped.ctRuntimeSyncPatch = true;
            global.handleTabNavigation = wrapped;
        }
    }

    function observeLanguage() {
        if (languageObserver || typeof MutationObserver !== 'function') return;
        languageObserver = new MutationObserver(() => queueMicrotask(() => {
            moveLanguagePanel();
            translateExtensions();
            annotateResourceImpact();
        }));
        languageObserver.observe(document.documentElement, {attributes:true, attributeFilter:['lang','dir']});
    }

    function apply() {
        injectStyles();
        reorderTabs();
        moveEffectiveIntoApps();
        moveLanguagePanel();
        removeQuickControls();
        hideRetiredRkp();
        customizeDrmFeature();
        observeFeatureCenter();
        installRuntimeSync();
        installPackagePickers();
        observePackagePickers();
        annotateResourceImpact();
        wrapRuntimeFunctions();
        observeLanguage();
        translateExtensions();
        if (configCache) syncVisibleToggles(configCache);
        else requestConfig().then(syncVisibleToggles).catch(() => {});
    }

    function start() {
        apply();
        [200,700,1500,3000,6000].forEach(delay => global.setTimeout(apply, delay));
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, {once:true});
    else start();
})(window);
