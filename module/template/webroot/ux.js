(function (global) {
    'use strict';

    const bridge = global.CleveresBridge;
    if (!bridge || typeof document === 'undefined') return;

    const STORAGE_KEY = 'cleverestricky.language.v1';
    const SYSTEM_LOCALE_KEY = 'cleverestricky.system_locale.v1';
    // To add a locale: append [locale, displayName] here, add TRANSLATIONS[locale],
    // add GUIDE[locale] when a localized guide is available, then run module/webui-tests.
    const SUPPORTED = [
        ['en', 'English'],
        ['tr', 'Türkçe'],
        ['zh-CN', '简体中文'],
        ['es', 'Español'],
        ['de', 'Deutsch'],
        ['ru', 'Русский'],
        ['id', 'Bahasa Indonesia'],
        ['hi', 'हिन्दी'],
        ['ar', 'العربية']
    ];

    const TRANSLATIONS = {
        en: {
            'noServers': 'No servers configured. Add one below to fetch keyboxes automatically.',
            'refresh': 'Refresh',
            'remove': 'Remove',
        },
        tr: {
            'Identity Controls': 'Kimlik Denetimleri', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'Yalnızca ihtiyacınız olan kimlik yollarını etkinleştirin. Devre dışı yollar isteğe bağlı yakalayıcıları başlatmaz.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'Kimlik şu anda devre dışı. Aşağıdan yalnızca ihtiyacınız olan kimlik yollarını etkinleştirin.', 'Random': 'Rastgele', 'Identity value randomized': 'Kimlik değeri rastgeleleştirildi',
            'Visible SIM count': 'Görünür SIM sayısı', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'Seçili uygulamalara döndürülen etkin abonelik listesini sınırlar. Var olmayan SIM oluşturmaz.', 'Randomize Telephony': 'Telefon kimliğini rastgeleleştir',
            "Camera visibility": "Kamera görünürlüğü", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "Seçili uygulamalar için kamera keşfini filtreler. Devre dışıyken cameraserver yakalayıcısı başlatılmaz.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "Bu yalnızca keşfedilebilir gerçek kamera kimliklerini azaltır; kamera oluşturmaz veya doğrudan erişimi engellemez.", "Hardware visibility": "Donanım görünürlüğü", "Visible camera count": "Görünür kamera sayısı", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "Seçili uygulamalar için keşfedilebilir kamera kimliklerini sınırlar. Var olmayan kamera oluşturmaz.",
            'Dashboard': 'Gösterge Paneli', 'Identity': 'Kimlik', 'Apps': 'Uygulamalar', 'Keyboxes': 'Keyboxlar',
            'Info & Resources': 'Bilgi ve Kaynaklar', 'Guide': 'Kılavuz', 'Logs': 'Günlükler', 'Editor': 'Düzenleyici',
            'Donate': 'Bağış', 'Profiles': 'Profiller', 'Security Patch': 'Güvenlik Yaması',
            'Core Protection': 'Temel Koruma', 'Global Mode': 'Global Mod', 'Global Keybox': 'Global Keybox', 'Global Identity': 'Global Kimlik', 'Auto Keybox Check': 'Otomatik Keybox Kontrolü',
            'DRM Passthrough': 'DRM Geçiş Modu', 'Configuration Management': 'Yapılandırma Yönetimi',
            'Reload Config': 'Yapılandırmayı Yenile', 'Identity Manager': 'Kimlik Yöneticisi', 'No attestation template': 'Attestation şablonu yok',
            'Randomize All Identifiers': 'Tüm Kimlikleri Rastgeleleştir', 'Auto Identity (Pixel Beta)': 'Otomatik Kimlik (Pixel Beta)',
            'Apply Identity': 'Kimliği Uygula', 'Application Privacy Shield': 'Uygulama Gizlilik Kalkanı', 'New Rule': 'Yeni Kural',
            'Package Name': 'Paket Adı', 'Attestation Identity Profile': 'Attestation Kimlik Profili', 'Custom Keybox': 'Özel Keybox',
            'Privacy Policy': 'Gizlilik Politikası', 'Add Rule': 'Kural Ekle', 'Active Rules': 'Etkin Kurallar',
            'Runtime Health': 'Çalışma Durumu', 'Resource Monitor': 'Kaynak İzleyici', 'Module Logs': 'Modül Günlükleri',
            'Refresh Logs': 'Günlükleri Yenile', 'Download Logs': 'Günlükleri İndir', 'Copy Logs': 'Günlükleri Kopyala',
            'Language': 'Dil', 'Debug Logging': 'Hata Ayıklama Günlükleri', 'Effective State': 'Etkin Durum',
            'Resolved Configuration': 'Çözümlenen Yapılandırma', 'Inspect': 'İncele', 'Select an app.': 'Bir uygulama seçin.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'Kimlik şu anda devre dışı. Gösterge Paneli üzerinden etkinleştirebilirsiniz.',
            'CleveresTech Telegram community': 'CleveresTech Telegram topluluğu', 'CleveresTech Community': 'CleveresTech Topluluğu', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'Yardımlaşma, test, tartışma ve CleveresTricky geliştirme için Telegram grubumuza katılın.',
            'Upload Keybox or CBOX file': 'Keybox veya CBOX dosyası yükle', 'Open Telegram Community': 'Telegram Topluluğunu Aç', 'Join Telegram Community': 'Telegram Topluluğuna Katıl',
            'Save profile': 'Profili kaydet', 'Clone': 'Klonla', 'Delete': 'Sil', 'Profile saved': 'Profil kaydedildi',
            'System / preinstalled packages are included in this search.': 'Sistem / ön yüklü paketler de bu aramaya dahildir.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Yerleşik çeviriler tamamen yereldir ve ağ bağlantısı gerektirmez. Buradan başka bir dil seçmediğiniz sürece varsayılan dil İngilizcedir.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt içindeki paketleri Android gerçek Keystore yolunda tutar. DRM güvenlik seviyesini taklit etmez.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Debug build kurmadan ek çalışma zamanı tanılamalarını açar. Logları topladıktan sonra kapatın.',
            'DRM passthrough enabled': 'DRM geçiş modu etkin', 'DRM passthrough disabled': 'DRM geçiş modu devre dışı',
            'Debug logging enabled': 'Hata ayıklama günlükleri etkin', 'Debug logging disabled': 'Hata ayıklama günlükleri devre dışı',
            'Could not update DRM setting': 'DRM ayarı güncellenemedi', 'Could not update debug logging': 'Hata ayıklama günlüğü ayarı güncellenemedi',
            'Identity: Disabled': 'Kimlik: Devre Dışı',
            'Identity: Global Mode Active': 'Kimlik: Global Mod Etkin',
            'Identity: App-Scoped Mode Active': 'Kimlik: Uygulama Bazlı Mod Etkin',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'Kimlik motoru şu anda Gösterge Panelinde devre dışı. Hiçbir sistem özelliği veya kimlik değişikliği etkin değil.',
            'Identity system properties are applied system-wide to all applications.': 'Kimlik sistem özellikleri tüm uygulamalara sistem genelinde uygulanır.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'Kimlik özellikleri yalnızca identity_target.txt içindeki uygulamalara ve yapılandırılmış profillere uygulanır.',
            'Security Patch: Enabled': 'Güvenlik Yaması: Etkin',
            'Security Patch: Disabled': 'Güvenlik Yaması: Devre Dışı',
            'Security patch levels are actively managed according to the component modes below.': 'Güvenlik yaması seviyeleri aşağıdaki bileşen modlarına göre aktif olarak yönetilmektedir.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'Güvenlik yaması taklidi şu anda devre dışı. Özel yama seviyeleri uygulamak için Gösterge Panelinden AÇIN.',
            'All major features and runtime paths in one place.': 'Tüm temel özellikler ve çalışma yolları tek yerde.'
        },
        'zh-CN': {
            'Identity Controls': '身份控制', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': '只启用你需要的身份路径。禁用的路径不会启动可选拦截器。', 'Identity is currently disabled. Enable only the identity paths you need below.': '身份功能当前已关闭。请在下方仅启用你需要的身份路径。', 'Random': '随机', 'Identity value randomized': '身份值已随机化',
            'Visible SIM count': '可见 SIM 数量', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': '限制向所选应用返回的活动订阅列表。不会创建实际不存在的 SIM。', 'Randomize Telephony': '随机化电话身份',
            "Camera visibility": "相机可见性", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "过滤所选应用的相机发现。关闭时不会启动 cameraserver 拦截器。", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "它只减少可发现的真实相机 ID；不会创建相机，也不会阻止直接访问。", "Hardware visibility": "硬件可见性", "Visible camera count": "可见相机数量", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "限制所选应用可发现的相机 ID。不会创建实际不存在的相机。",
            'Dashboard': '仪表盘', 'Identity': '身份', 'Apps': '应用', 'Keyboxes': '密钥盒', 'Info & Resources': '信息与资源',
            'Guide': '指南', 'Logs': '日志', 'Editor': '编辑器', 'Donate': '捐赠', 'Profiles': '配置档案',
            'Security Patch': '安全补丁', 'Core Protection': '核心保护', 'Global Mode': '全局模式', 'Global Keybox': '全局密钥盒', 'Global Identity': '全局身份',
            'Auto Keybox Check': '自动密钥盒检查', 'DRM Passthrough': 'DRM 直通', 'Configuration Management': '配置管理',
            'Reload Config': '重新加载配置', 'Identity Manager': '身份管理器', 'No attestation template': '无认证模板',
            'Randomize All Identifiers': '随机化所有标识符', 'Auto Identity (Pixel Beta)': '自动身份（Pixel Beta）',
            'Apply Identity': '应用身份', 'Application Privacy Shield': '应用隐私保护', 'New Rule': '新规则',
            'Package Name': '包名', 'Attestation Identity Profile': '认证身份配置', 'Custom Keybox': '自定义密钥盒',
            'Privacy Policy': '隐私策略', 'Add Rule': '添加规则', 'Active Rules': '活动规则', 'Runtime Health': '运行状态',
            'Resource Monitor': '资源监视器', 'Module Logs': '模块日志', 'Refresh Logs': '刷新日志',
            'Download Logs': '下载日志', 'Copy Logs': '复制日志', 'Language': '语言', 'Debug Logging': '调试日志',
            'Effective State': '有效状态', 'Resolved Configuration': '解析后的配置', 'Inspect': '检查', 'Select an app.': '请选择应用。',
            'Identity is currently disabled. You can enable it from Dashboard.': '身份功能当前已关闭。可在仪表盘中启用。',
            'CleveresTech Telegram community': 'CleveresTech Telegram 社区', 'CleveresTech Community': 'CleveresTech 社区', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': '加入我们的 Telegram 群组，互相帮助、测试、讨论并参与 CleveresTricky 的开发。',
            'Upload Keybox or CBOX file': '上传 Keybox 或 CBOX 文件', 'Open Telegram Community': '打开 Telegram 社区', 'Join Telegram Community': '加入 Telegram 社区',
            'Save profile': '保存配置', 'Clone': '克隆', 'Delete': '删除',
            'System / preinstalled packages are included in this search.': '此搜索也包含系统 / 预装应用。',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': '内置翻译完全在本地运行，不需要网络。除非在这里选择其他语言，否则默认使用英语。',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": '让 drm_packages.txt 中的包继续使用 Android 真实 Keystore 路径，不会伪造 DRM 安全级别。',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': '无需安装调试版本即可启用额外运行时诊断。收集日志后请关闭。',
            'DRM passthrough enabled': 'DRM 直通已启用', 'DRM passthrough disabled': 'DRM 直通已关闭',
            'Debug logging enabled': '调试日志已启用', 'Debug logging disabled': '调试日志已关闭',
            'Could not update DRM setting': '无法更新 DRM 设置', 'Could not update debug logging': '无法更新调试日志设置',
            'Identity: Disabled': '身份：已禁用',
            'Identity: Global Mode Active': '身份：全局模式已激活',
            'Identity: App-Scoped Mode Active': '身份：应用级模式已激活',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': '身份引擎当前在仪表盘中已禁用。没有系统属性或身份修改处于激活状态。',
            'Identity system properties are applied system-wide to all applications.': '身份系统属性将应用于系统范围内的所有应用程序。',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': '身份属性仅适用于 identity_target.txt 中的应用程序和已配置的配置文件。',
            'Security Patch: Enabled': '安全补丁：已启用',
            'Security Patch: Disabled': '安全补丁：已禁用',
            'Security patch levels are actively managed according to the component modes below.': '安全补丁级别将根据以下组件模式进行积极管理。',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': '安全补丁伪装当前已禁用。从仪表盘将其开启以应用自定义补丁级别。',
            'All major features and runtime paths in one place.': '所有主要功能和运行路径集中说明。',
            'noServers': '未配置服务器。在下方添加一个以自动获取 keybox。',
            'refresh': '刷新',
            'remove': '移除'
        },
        es: {
            'Identity Controls': 'Controles de identidad', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'Activa solo las rutas de identidad que necesites. Las rutas desactivadas no inician interceptores opcionales.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'La identidad está desactivada. Activa abajo solo las rutas que necesites.', 'Random': 'Aleatorio', 'Identity value randomized': 'Valor de identidad aleatorizado',
            'Visible SIM count': 'Cantidad de SIM visibles', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'Limita la lista de suscripciones activas devuelta a las apps seleccionadas. Nunca crea SIM inexistentes.', 'Randomize Telephony': 'Aleatorizar telefonía',
            "Camera visibility": "Visibilidad de cámara", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "Filtra el descubrimiento de cámaras para las apps seleccionadas. Desactivado no inicia el interceptor de cameraserver.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "Solo reduce IDs de cámaras reales detectables; no crea cámaras ni bloquea el acceso directo.", "Hardware visibility": "Visibilidad de hardware", "Visible camera count": "Cantidad de cámaras visibles", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "Limita los IDs de cámara detectables para las apps seleccionadas. Nunca crea cámaras inexistentes.",
            'Dashboard': 'Panel', 'Identity': 'Identidad', 'Apps': 'Aplicaciones', 'Keyboxes': 'Keyboxes', 'Info & Resources': 'Info y recursos',
            'Guide': 'Guía', 'Logs': 'Registros', 'Editor': 'Editor', 'Donate': 'Donar', 'Profiles': 'Perfiles',
            'Security Patch': 'Parche de seguridad', 'Core Protection': 'Protección principal', 'Global Mode': 'Modo global', 'Global Keybox': 'Keybox global', 'Global Identity': 'Identidad global',
            'Auto Keybox Check': 'Comprobación automática de keybox', 'DRM Passthrough': 'Paso directo DRM',
            'Configuration Management': 'Gestión de configuración', 'Reload Config': 'Recargar configuración',
            'Identity Manager': 'Gestor de identidad', 'No attestation template': 'Sin plantilla de atestación',
            'Randomize All Identifiers': 'Aleatorizar identificadores', 'Auto Identity (Pixel Beta)': 'Identidad automática (Pixel Beta)',
            'Apply Identity': 'Aplicar identidad', 'Application Privacy Shield': 'Protección de privacidad por app', 'New Rule': 'Nueva regla',
            'Package Name': 'Nombre del paquete', 'Attestation Identity Profile': 'Perfil de identidad de atestación',
            'Custom Keybox': 'Keybox personalizado', 'Privacy Policy': 'Política de privacidad', 'Add Rule': 'Añadir regla',
            'Active Rules': 'Reglas activas', 'Runtime Health': 'Estado de ejecución', 'Resource Monitor': 'Monitor de recursos',
            'Module Logs': 'Registros del módulo', 'Refresh Logs': 'Actualizar registros', 'Download Logs': 'Descargar registros',
            'Copy Logs': 'Copiar registros', 'Language': 'Idioma', 'Debug Logging': 'Registro de depuración',
            'Effective State': 'Estado efectivo', 'Resolved Configuration': 'Configuración resuelta', 'Inspect': 'Inspeccionar',
            'Select an app.': 'Selecciona una app.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'La identidad está desactivada. Puedes activarla desde el Panel.',
            'CleveresTech Telegram community': 'comunidad de Telegram de CleveresTech', 'CleveresTech Community': 'Comunidad de CleveresTech', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'Únete a nuestro grupo de Telegram para obtener ayuda mutua, realizar pruebas, debatir y desarrollar CleveresTricky.',
            'Upload Keybox or CBOX file': 'Subir archivo Keybox o CBOX', 'Open Telegram Community': 'Abrir comunidad de Telegram', 'Join Telegram Community': 'Unirse a la comunidad de Telegram',
            'Save profile': 'Guardar perfil', 'Clone': 'Clonar', 'Delete': 'Eliminar',
            'System / preinstalled packages are included in this search.': 'La búsqueda incluye paquetes del sistema y preinstalados.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Las traducciones integradas son locales y no requieren conexión. El inglés es el idioma predeterminado hasta que elijas otro aquí.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mantiene los paquetes de drm_packages.txt en la ruta Keystore real de Android. No falsifica el nivel de seguridad DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Activa diagnósticos adicionales sin instalar una compilación debug. Desactívalo después de recoger los registros.',
            'DRM passthrough enabled': 'Paso directo DRM activado', 'DRM passthrough disabled': 'Paso directo DRM desactivado',
            'Debug logging enabled': 'Registro de depuración activado', 'Debug logging disabled': 'Registro de depuración desactivado',
            'Could not update DRM setting': 'No se pudo actualizar DRM', 'Could not update debug logging': 'No se pudo actualizar el registro de depuración',
            'Identity: Disabled': 'Identidad: Desactivada',
            'Identity: Global Mode Active': 'Identidad: Modo global activo',
            'Identity: App-Scoped Mode Active': 'Identidad: Modo por aplicación activo',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'El motor de identidad está desactivado en el Panel. No hay propiedades del sistema ni modificaciones de identidad activas.',
            'Identity system properties are applied system-wide to all applications.': 'Las propiedades de identidad del sistema se aplican a nivel de todo el sistema para todas las aplicaciones.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'Las propiedades de identidad se aplican solo a las aplicaciones en identity_target.txt y a los perfiles configurados.',
            'Security Patch: Enabled': 'Parche de seguridad: Activado',
            'Security Patch: Disabled': 'Parche de seguridad: Desactivado',
            'Security patch levels are actively managed according to the component modes below.': 'Los niveles de parches de seguridad se gestionan activamente según los modos de componentes siguientes.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'La suplantación de parches de seguridad está desactivada. Actívala desde el Panel para aplicar niveles de parche personalizados.',
            'All major features and runtime paths in one place.': 'Todas las funciones principales y rutas de ejecución en un solo lugar.',
            'noServers': 'No hay servidores configurados. Añada uno abajo para obtener keyboxes automáticamente.',
            'refresh': 'Actualizar',
            'remove': 'Eliminar'
        },
        de: {
            'Identity Controls': 'Identitätssteuerung', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'Aktiviere nur die benötigten Identitätspfade. Deaktivierte Pfade starten keine optionalen Interzeptoren.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'Identität ist derzeit deaktiviert. Aktiviere unten nur die benötigten Identitätspfade.', 'Random': 'Zufällig', 'Identity value randomized': 'Identitätswert randomisiert',
            'Visible SIM count': 'Sichtbare SIM-Anzahl', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'Begrenzt die Liste aktiver Abonnements für ausgewählte Apps. Es werden keine nicht vorhandenen SIMs erzeugt.', 'Randomize Telephony': 'Telefonie randomisieren',
            "Camera visibility": "Kamerasichtbarkeit", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "Filtert die Kameraerkennung für ausgewählte Apps. Deaktiviert wird kein cameraserver-Interceptor gestartet.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "Dies reduziert nur erkennbare reale Kamera-IDs; es erzeugt keine Kameras und blockiert keinen direkten Zugriff.", "Hardware visibility": "Hardwaresichtbarkeit", "Visible camera count": "Sichtbare Kameraanzahl", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "Begrenzt erkennbare Kamera-IDs für ausgewählte Apps. Es werden keine nicht vorhandenen Kameras erzeugt.",
            'Dashboard': 'Übersicht', 'Identity': 'Identität', 'Apps': 'Anwendungen', 'Keyboxes': 'Keyboxen', 'Info & Resources': 'Info & Ressourcen',
            'Guide': 'Anleitung', 'Logs': 'Protokolle', 'Editor': 'Editor', 'Donate': 'Spenden', 'Profiles': 'Profile',
            'Security Patch': 'Sicherheitspatch', 'Core Protection': 'Kernschutz', 'Global Mode': 'Globaler Modus', 'Global Keybox': 'Globaler Keybox-Modus', 'Global Identity': 'Globale Identität',
            'Auto Keybox Check': 'Automatische Keybox-Prüfung', 'DRM Passthrough': 'DRM-Durchleitung',
            'Configuration Management': 'Konfigurationsverwaltung', 'Reload Config': 'Konfiguration neu laden',
            'Identity Manager': 'Identitätsverwaltung', 'No attestation template': 'Keine Attestierungs-Vorlage',
            'Randomize All Identifiers': 'Alle Kennungen randomisieren', 'Auto Identity (Pixel Beta)': 'Auto-Identität (Pixel Beta)',
            'Apply Identity': 'Identität anwenden', 'Application Privacy Shield': 'App-Datenschutz', 'New Rule': 'Neue Regel',
            'Package Name': 'Paketname', 'Attestation Identity Profile': 'Attestierungsprofil', 'Custom Keybox': 'Eigene Keybox',
            'Privacy Policy': 'Datenschutzrichtlinie', 'Add Rule': 'Regel hinzufügen', 'Active Rules': 'Aktive Regeln',
            'Runtime Health': 'Laufzeitstatus', 'Resource Monitor': 'Ressourcenmonitor', 'Module Logs': 'Modulprotokolle',
            'Refresh Logs': 'Protokolle aktualisieren', 'Download Logs': 'Protokolle herunterladen', 'Copy Logs': 'Protokolle kopieren',
            'Language': 'Sprache', 'Debug Logging': 'Debug-Protokollierung', 'Effective State': 'Effektiver Zustand',
            'Resolved Configuration': 'Aufgelöste Konfiguration', 'Inspect': 'Prüfen', 'Select an app.': 'App auswählen.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'Identität ist derzeit deaktiviert. Sie kann in der Übersicht aktiviert werden.',
            'CleveresTech Telegram community': 'CleveresTech-Telegram-Community', 'CleveresTech Community': 'CleveresTech-Community', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'Tritt unserer Telegram-Gruppe bei, um gegenseitige Hilfe, Tests, Diskussionen und die Entwicklung von CleveresTricky zu unterstützen.',
            'Upload Keybox or CBOX file': 'Keybox- oder CBOX-Datei hochladen', 'Open Telegram Community': 'Telegram-Community öffnen', 'Join Telegram Community': 'Telegram-Community beitreten',
            'Save profile': 'Profil speichern', 'Clone': 'Klonen', 'Delete': 'Löschen',
            'System / preinstalled packages are included in this search.': 'System- und vorinstallierte Pakete sind in dieser Suche enthalten.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Die integrierten Übersetzungen funktionieren lokal und benötigen kein Netzwerk. Englisch bleibt Standard, bis hier eine andere Sprache gewählt wird.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Pakete aus drm_packages.txt bleiben auf dem echten Android-Keystore-Pfad. Es wird keine DRM-Sicherheitsstufe vorgetäuscht.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Aktiviert zusätzliche Laufzeitdiagnose ohne Debug-Build. Nach dem Sammeln der Logs wieder ausschalten.',
            'DRM passthrough enabled': 'DRM-Durchleitung aktiviert', 'DRM passthrough disabled': 'DRM-Durchleitung deaktiviert',
            'Debug logging enabled': 'Debug-Protokollierung aktiviert', 'Debug logging disabled': 'Debug-Protokollierung deaktiviert',
            'Could not update DRM setting': 'DRM-Einstellung konnte nicht aktualisiert werden', 'Could not update debug logging': 'Debug-Protokollierung konnte nicht aktualisiert werden',
            'Identity: Disabled': 'Identität: Deaktiviert',
            'Identity: Global Mode Active': 'Identität: Globaler Modus aktiv',
            'Identity: App-Scoped Mode Active': 'Identität: App-basierter Modus aktiv',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'Die Identitäts-Engine ist im Dashboard derzeit deaktiviert. Es sind keine Systemeigenschaften oder Identitätsänderungen aktiv.',
            'Identity system properties are applied system-wide to all applications.': 'Identitäts-Systemeigenschaften werden systemweit auf alle Anwendungen angewendet.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'Identitätseigenschaften gelten nur für Anwendungen in identity_target.txt und konfigurierte Profile.',
            'Security Patch: Enabled': 'Sicherheitspatch: Aktiviert',
            'Security Patch: Disabled': 'Sicherheitspatch: Deaktiviert',
            'Security patch levels are actively managed according to the component modes below.': 'Sicherheitspatch-Ebenen werden entsprechend den folgenden Komponentenmodi aktiv verwaltet.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'Das Spoofing von Sicherheitspatches ist derzeit deaktiviert. Schalten Sie es im Dashboard EIN, um benutzerdefinierte Patch-Ebenen anzuwenden.',
            'All major features and runtime paths in one place.': 'Alle wichtigen Funktionen und Laufzeitpfade an einem Ort.',
            'noServers': 'Keine Server konfiguriert. Fügen Sie unten einen hinzu, um Keyboxen automatisch abzurufen.',
            'refresh': 'Aktualisieren',
            'remove': 'Entfernen'
        },
        ru: {
            'Identity Controls': 'Управление идентичностью', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'Включайте только нужные пути идентичности. Отключённые пути не запускают необязательные перехватчики.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'Идентичность сейчас отключена. Ниже включите только нужные пути.', 'Random': 'Случайно', 'Identity value randomized': 'Значение идентичности рандомизировано',
            'Visible SIM count': 'Количество видимых SIM', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'Ограничивает список активных подписок для выбранных приложений. Не создаёт несуществующие SIM.', 'Randomize Telephony': 'Рандомизировать телефонию',
            "Camera visibility": "Видимость камер", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "Фильтрует обнаружение камер для выбранных приложений. В выключенном состоянии перехватчик cameraserver не запускается.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "Только уменьшает набор обнаруживаемых реальных ID камер; не создаёт камеры и не блокирует прямой доступ.", "Hardware visibility": "Видимость оборудования", "Visible camera count": "Количество видимых камер", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "Ограничивает обнаруживаемые ID камер для выбранных приложений. Не создаёт несуществующие камеры.",
            'Dashboard': 'Панель', 'Identity': 'Идентичность', 'Apps': 'Приложения', 'Keyboxes': 'Keybox', 'Info & Resources': 'Инфо и ресурсы',
            'Guide': 'Руководство', 'Logs': 'Логи', 'Editor': 'Редактор', 'Donate': 'Поддержать', 'Profiles': 'Профили',
            'Security Patch': 'Патч безопасности', 'Core Protection': 'Основная защита', 'Global Mode': 'Глобальный режим', 'Global Keybox': 'Глобальный Keybox', 'Global Identity': 'Глобальная идентификация',
            'Auto Keybox Check': 'Автопроверка keybox', 'DRM Passthrough': 'DRM passthrough', 'Configuration Management': 'Управление конфигурацией',
            'Reload Config': 'Перезагрузить конфигурацию', 'Identity Manager': 'Менеджер идентичности', 'No attestation template': 'Без шаблона аттестации',
            'Randomize All Identifiers': 'Случайные идентификаторы', 'Auto Identity (Pixel Beta)': 'Авто-идентичность (Pixel Beta)',
            'Apply Identity': 'Применить идентичность', 'Application Privacy Shield': 'Защита приложений', 'New Rule': 'Новое правило',
            'Package Name': 'Имя пакета', 'Attestation Identity Profile': 'Профиль аттестации', 'Custom Keybox': 'Свой keybox',
            'Privacy Policy': 'Политика приватности', 'Add Rule': 'Добавить правило', 'Active Rules': 'Активные правила',
            'Runtime Health': 'Состояние среды', 'Resource Monitor': 'Монитор ресурсов', 'Module Logs': 'Логи модуля',
            'Refresh Logs': 'Обновить логи', 'Download Logs': 'Скачать логи', 'Copy Logs': 'Копировать логи',
            'Language': 'Язык', 'Debug Logging': 'Отладочное логирование', 'Effective State': 'Эффективное состояние',
            'Resolved Configuration': 'Итоговая конфигурация', 'Inspect': 'Проверить', 'Select an app.': 'Выберите приложение.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'Идентичность отключена. Её можно включить на Панели.',
            'CleveresTech Telegram community': 'Telegram-сообщество CleveresTech', 'CleveresTech Community': 'Сообщество CleveresTech', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'Присоединяйтесь к нашей группе в Telegram для взаимопомощи, тестирования, обсуждения и развития CleveresTricky.',
            'Upload Keybox or CBOX file': 'Загрузить файл Keybox или CBOX', 'Open Telegram Community': 'Открыть Telegram-сообщество', 'Join Telegram Community': 'Вступить в Telegram-сообщество',
            'Save profile': 'Сохранить профиль', 'Clone': 'Клонировать', 'Delete': 'Удалить',
            'System / preinstalled packages are included in this search.': 'Поиск включает системные и предустановленные пакеты.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Встроенные переводы работают локально и не требуют сети. Английский используется по умолчанию, пока здесь не выбран другой язык.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Пакеты из drm_packages.txt остаются на настоящем пути Android Keystore. Уровень безопасности DRM не подделывается.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Включает дополнительную диагностику без debug-сборки. После сбора логов отключите её.',
            'DRM passthrough enabled': 'DRM passthrough включён', 'DRM passthrough disabled': 'DRM passthrough выключен',
            'Debug logging enabled': 'Отладочное логирование включено', 'Debug logging disabled': 'Отладочное логирование выключено',
            'Could not update DRM setting': 'Не удалось обновить настройку DRM', 'Could not update debug logging': 'Не удалось обновить отладочное логирование',
            'Identity: Disabled': 'Идентичность: Отключена',
            'Identity: Global Mode Active': 'Идентичность: Глобальный режим активен',
            'Identity: App-Scoped Mode Active': 'Идентичность: Режим для приложений активен',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'Модуль идентификации в настоящее время отключен на панели управления. Никакие системные свойства или изменения идентификации не активны.',
            'Identity system properties are applied system-wide to all applications.': 'Системные свойства идентификации применяются на уровне всей системы ко всем приложениям.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'Свойства идентификации применяются только к приложениям из identity_target.txt и настроенным профилям.',
            'Security Patch: Enabled': 'Патч безопасности: Включен',
            'Security Patch: Disabled': 'Патч безопасности: Отключен',
            'Security patch levels are actively managed according to the component modes below.': 'Уровни патчей безопасности активно управляются в соответствии с приведенными ниже режимами компонентов.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'Подмена патча безопасности в настоящее время отключена. Включите ее на панели управления, чтобы применить пользовательские уровни патчей.',
            'All major features and runtime paths in one place.': 'Все основные функции и пути выполнения в одном месте.',
            'noServers': 'Серверы не настроены. Добавьте один ниже, чтобы получать keybox автоматически.',
            'refresh': 'Обновить',
            'remove': 'Удалить'
        },
        id: {
            'Identity Controls': 'Kontrol Identitas', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'Aktifkan hanya jalur identitas yang diperlukan. Jalur yang dinonaktifkan tidak menjalankan interceptor opsional.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'Identitas saat ini dinonaktifkan. Aktifkan hanya jalur yang diperlukan di bawah.', 'Random': 'Acak', 'Identity value randomized': 'Nilai identitas diacak',
            'Visible SIM count': 'Jumlah SIM terlihat', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'Membatasi daftar langganan aktif yang dikembalikan ke aplikasi terpilih. Tidak membuat SIM yang sebenarnya tidak ada.', 'Randomize Telephony': 'Acak identitas telepon',
            "Camera visibility": "Visibilitas kamera", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "Memfilter penemuan kamera untuk aplikasi terpilih. Saat nonaktif, interceptor cameraserver tidak dimulai.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "Ini hanya mengurangi ID kamera nyata yang dapat ditemukan; tidak membuat kamera atau memblokir akses langsung.", "Hardware visibility": "Visibilitas perangkat keras", "Visible camera count": "Jumlah kamera terlihat", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "Membatasi ID kamera yang dapat ditemukan untuk aplikasi terpilih. Tidak membuat kamera yang sebenarnya tidak ada.",
            'Dashboard': 'Dasbor', 'Identity': 'Identitas', 'Apps': 'Aplikasi', 'Keyboxes': 'Keybox', 'Info & Resources': 'Info & Sumber Daya',
            'Guide': 'Panduan', 'Logs': 'Log', 'Editor': 'Editor', 'Donate': 'Donasi', 'Profiles': 'Profil',
            'Security Patch': 'Patch Keamanan', 'Core Protection': 'Perlindungan Inti', 'Global Mode': 'Mode Global', 'Global Keybox': 'Keybox Global', 'Global Identity': 'Identitas Global',
            'Auto Keybox Check': 'Pemeriksaan Keybox Otomatis', 'DRM Passthrough': 'DRM Passthrough',
            'Configuration Management': 'Manajemen Konfigurasi', 'Reload Config': 'Muat Ulang Konfigurasi',
            'Identity Manager': 'Pengelola Identitas', 'No attestation template': 'Tidak ada templat attestasi',
            'Randomize All Identifiers': 'Acak Semua Identitas', 'Auto Identity (Pixel Beta)': 'Identitas Otomatis (Pixel Beta)',
            'Apply Identity': 'Terapkan Identitas', 'Application Privacy Shield': 'Perlindungan Privasi Aplikasi', 'New Rule': 'Aturan Baru',
            'Package Name': 'Nama Paket', 'Attestation Identity Profile': 'Profil Identitas Attestasi', 'Custom Keybox': 'Keybox Kustom',
            'Privacy Policy': 'Kebijakan Privasi', 'Add Rule': 'Tambah Aturan', 'Active Rules': 'Aturan Aktif',
            'Runtime Health': 'Kesehatan Runtime', 'Resource Monitor': 'Monitor Sumber Daya', 'Module Logs': 'Log Modul',
            'Refresh Logs': 'Segarkan Log', 'Download Logs': 'Unduh Log', 'Copy Logs': 'Salin Log',
            'Language': 'Bahasa', 'Debug Logging': 'Log Debug', 'Effective State': 'Status Efektif',
            'Resolved Configuration': 'Konfigurasi Terselesaikan', 'Inspect': 'Periksa', 'Select an app.': 'Pilih aplikasi.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'Identitas saat ini dinonaktifkan. Anda dapat mengaktifkannya dari Dasbor.',
            'CleveresTech Telegram community': 'komunitas Telegram CleveresTech', 'CleveresTech Community': 'Komunitas CleveresTech', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'Bergabunglah dengan grup Telegram kami untuk saling membantu, menguji, berdiskusi, dan mengembangkan CleveresTricky.',
            'Upload Keybox or CBOX file': 'Unggah file Keybox atau CBOX', 'Open Telegram Community': 'Buka Komunitas Telegram', 'Join Telegram Community': 'Gabung Komunitas Telegram',
            'Save profile': 'Simpan profil', 'Clone': 'Klon', 'Delete': 'Hapus', 'Profile saved': 'Profil disimpan',
            'System / preinstalled packages are included in this search.': 'Paket sistem / prainstal juga disertakan dalam pencarian ini.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Terjemahan bawaan tersedia secara lokal dan tidak memerlukan jaringan. Bahasa Inggris adalah default sampai Anda memilih bahasa lain di sini.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mempertahankan paket di drm_packages.txt pada jalur Keystore Android asli. Ini tidak memalsukan tingkat keamanan DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Aktifkan diagnostik runtime tambahan tanpa memasang build debug. Matikan setelah log selesai dikumpulkan.',
            'DRM passthrough enabled': 'DRM passthrough aktif', 'DRM passthrough disabled': 'DRM passthrough nonaktif',
            'Debug logging enabled': 'Log debug aktif', 'Debug logging disabled': 'Log debug nonaktif',
            'Could not update DRM setting': 'Tidak dapat memperbarui pengaturan DRM', 'Could not update debug logging': 'Tidak dapat memperbarui log debug',
            'Identity: Disabled': 'Identitas: Dinonaktifkan',
            'Identity: Global Mode Active': 'Identitas: Mode Global Aktif',
            'Identity: App-Scoped Mode Active': 'Identitas: Mode Khusus Aplikasi Aktif',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'Mesin identitas saat ini dinonaktifkan di Dasbor. Tidak ada properti sistem atau modifikasi identitas yang aktif.',
            'Identity system properties are applied system-wide to all applications.': 'Properti sistem identitas diterapkan di seluruh sistem untuk semua aplikasi.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'Properti identitas hanya berlaku untuk aplikasi di identity_target.txt dan profil yang dikonfigurasi.',
            'Security Patch: Enabled': 'Patch Keamanan: Diaktifkan',
            'Security Patch: Disabled': 'Patch Keamanan: Dinonaktifkan',
            'Security patch levels are actively managed according to the component modes below.': 'Tingkat patch keamanan dikelola secara aktif sesuai dengan mode komponen di bawah ini.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'Spoofing patch keamanan saat ini dinonaktifkan. Aktifkan dari Dasbor untuk menerapkan tingkat patch khusus.',
            'All major features and runtime paths in one place.': 'Semua fitur utama dan jalur runtime dijelaskan di satu tempat.',
            'noServers': 'Tidak ada server yang dikonfigurasi. Tambahkan satu di bawah untuk mengambil keybox secara otomatis.',
            'refresh': 'Segarkan',
            'remove': 'Hapus'
        },
        hi: {
            'Identity Controls': 'पहचान नियंत्रण', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'केवल आवश्यक पहचान पथ सक्षम करें। अक्षम पथ वैकल्पिक इंटरसेप्टर शुरू नहीं करते।', 'Identity is currently disabled. Enable only the identity paths you need below.': 'पहचान अभी अक्षम है। नीचे केवल आवश्यक पहचान पथ सक्षम करें।', 'Random': 'रैंडम', 'Identity value randomized': 'पहचान मान रैंडम किया गया',
            'Visible SIM count': 'दिखाई देने वाली SIM संख्या', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'चुने गए ऐप्स को लौटाई जाने वाली सक्रिय सदस्यता सूची सीमित करता है। मौजूद न होने वाली SIM नहीं बनाता।', 'Randomize Telephony': 'टेलीफोनी रैंडमाइज़ करें',
            "Camera visibility": "कैमरा दृश्यता", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "चुने गए ऐप्स के लिए कैमरा खोज को फ़िल्टर करता है। बंद होने पर cameraserver इंटरसेप्टर शुरू नहीं होता।", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "यह केवल खोजे जा सकने वाले वास्तविक कैमरा ID कम करता है; कैमरे बनाता या सीधी पहुँच रोकता नहीं है।", "Hardware visibility": "हार्डवेयर दृश्यता", "Visible camera count": "दिखाई देने वाले कैमरों की संख्या", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "चुने गए ऐप्स के लिए खोजे जा सकने वाले कैमरा ID सीमित करता है। मौजूद न होने वाले कैमरे नहीं बनाता।",
            'Dashboard': 'डैशबोर्ड', 'Identity': 'पहचान', 'Apps': 'ऐप्स', 'Keyboxes': 'कीबॉक्स', 'Info & Resources': 'जानकारी और संसाधन',
            'Guide': 'मार्गदर्शिका', 'Logs': 'लॉग', 'Editor': 'संपादक', 'Donate': 'दान', 'Profiles': 'प्रोफाइल',
            'Security Patch': 'सुरक्षा पैच', 'Core Protection': 'मुख्य सुरक्षा', 'Global Mode': 'ग्लोबल मोड', 'Global Keybox': 'ग्लोबल कीबॉक्स', 'Global Identity': 'ग्लोबल पहचान',
            'Auto Keybox Check': 'स्वचालित कीबॉक्स जांच', 'DRM Passthrough': 'DRM पासथ्रू',
            'Configuration Management': 'कॉन्फ़िगरेशन प्रबंधन', 'Reload Config': 'कॉन्फ़िगरेशन पुनः लोड करें',
            'Identity Manager': 'पहचान प्रबंधक', 'No attestation template': 'कोई अटेस्टेशन टेम्पलेट नहीं',
            'Randomize All Identifiers': 'सभी पहचानकर्ता रैंडम करें', 'Auto Identity (Pixel Beta)': 'ऑटो पहचान (Pixel Beta)',
            'Apply Identity': 'पहचान लागू करें', 'Application Privacy Shield': 'ऐप गोपनीयता सुरक्षा', 'New Rule': 'नया नियम',
            'Package Name': 'पैकेज नाम', 'Attestation Identity Profile': 'अटेस्टेशन पहचान प्रोफाइल', 'Custom Keybox': 'कस्टम कीबॉक्स',
            'Privacy Policy': 'गोपनीयता नीति', 'Add Rule': 'नियम जोड़ें', 'Active Rules': 'सक्रिय नियम',
            'Runtime Health': 'रनटाइम स्थिति', 'Resource Monitor': 'संसाधन मॉनिटर', 'Module Logs': 'मॉड्यूल लॉग',
            'Refresh Logs': 'लॉग रीफ्रेश करें', 'Download Logs': 'लॉग डाउनलोड करें', 'Copy Logs': 'लॉग कॉपी करें',
            'Language': 'भाषा', 'Debug Logging': 'डीबग लॉगिंग', 'Effective State': 'प्रभावी स्थिति',
            'Resolved Configuration': 'निर्धारित कॉन्फ़िगरेशन', 'Inspect': 'जांचें', 'Select an app.': 'एक ऐप चुनें।',
            'Identity is currently disabled. You can enable it from Dashboard.': 'पहचान अभी बंद है। आप इसे डैशबोर्ड से चालू कर सकते हैं।',
            'CleveresTech Telegram community': 'CleveresTech Telegram समुदाय', 'CleveresTech Community': 'CleveresTech समुदाय', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'आपसी सहायता, परीक्षण, चर्चा और CleveresTricky के विकास के लिए हमारे Telegram समूह से जुड़ें।',
            'Upload Keybox or CBOX file': 'Keybox या CBOX फ़ाइल अपलोड करें', 'Open Telegram Community': 'Telegram समुदाय खोलें', 'Join Telegram Community': 'Telegram समुदाय से जुड़ें',
            'Save profile': 'प्रोफाइल सहेजें', 'Clone': 'क्लोन', 'Delete': 'हटाएं', 'Profile saved': 'प्रोफाइल सहेजा गया',
            'System / preinstalled packages are included in this search.': 'इस खोज में सिस्टम / पहले से इंस्टॉल पैकेज भी शामिल हैं।',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'अंतर्निहित अनुवाद स्थानीय हैं और नेटवर्क की आवश्यकता नहीं है। जब तक आप यहां दूसरी भाषा नहीं चुनते, अंग्रेज़ी डिफ़ॉल्ट रहती है।',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt में सूचीबद्ध पैकेजों को Android के वास्तविक Keystore पथ पर रखता है। यह DRM सुरक्षा स्तर को नकली नहीं बनाता।',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'डीबग बिल्ड इंस्टॉल किए बिना अतिरिक्त रनटाइम डायग्नोस्टिक्स चालू करें। लॉग लेने के बाद इसे बंद कर दें।',
            'DRM passthrough enabled': 'DRM पासथ्रू चालू है', 'DRM passthrough disabled': 'DRM पासथ्रू बंद है',
            'Debug logging enabled': 'डीबग लॉगिंग चालू है', 'Debug logging disabled': 'डीबग लॉगिंग बंद है',
            'Could not update DRM setting': 'DRM सेटिंग अपडेट नहीं हो सकी', 'Could not update debug logging': 'डीबग लॉगिंग अपडेट नहीं हो सकी',
            'Identity: Disabled': 'पहचान: अक्षम',
            'Identity: Global Mode Active': 'पहचान: ग्लोबल मोड सक्रिय',
            'Identity: App-Scoped Mode Active': 'पहचान: ऐप-स्तरीय मोड सक्रिय',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'पहचान इंजन वर्तमान में डैशबोर्ड पर अक्षम है। कोई सिस्टम गुण या पहचान संशोधन सक्रिय नहीं हैं।',
            'Identity system properties are applied system-wide to all applications.': 'पहचान सिस्टम गुण सभी एप्लिकेशन पर सिस्टम-व्यापी रूप से लागू होते हैं।',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'पहचान गुण केवल identity_target.txt और कॉन्फ़िगर किए गए प्रोफाइल में मौजूद एप्लिकेशन पर लागू होते हैं।',
            'Security Patch: Enabled': 'सुरक्षा पैच: सक्षम',
            'Security Patch: Disabled': 'सुरक्षा पैच: अक्षम',
            'Security patch levels are actively managed according to the component modes below.': 'सुरक्षा पैच स्तर नीचे दिए गए घटक मोड के अनुसार सक्रिय रूप से प्रबंधित किए जाते हैं।',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'सुरक्षा पैच स्पूफिंग वर्तमान में अक्षम है। कस्टम पैच स्तर लागू करने के लिए इसे डैशबोर्ड से चालू करें।',
            'All major features and runtime paths in one place.': 'सभी मुख्य फीचर और रनटाइम पथ एक ही जगह समझाए गए हैं।',
            'noServers': 'कोई सर्वर कॉन्फ़िगर नहीं किया गया है। स्वचालित रूप से कीबॉक्स प्राप्त करने के लिए नीचे एक जोड़ें।',
            'refresh': 'रीफ़्रेश करें',
            'remove': 'हटाएं'
        },
        ar: {
            'Identity Controls': 'عناصر التحكم بالهوية', 'Enable only the identity paths you need. Disabled paths do not start optional interceptors.': 'فعّل فقط مسارات الهوية التي تحتاجها. المسارات المعطلة لا تشغّل المعترضات الاختيارية.', 'Identity is currently disabled. Enable only the identity paths you need below.': 'الهوية معطلة حاليًا. فعّل أدناه فقط مسارات الهوية التي تحتاجها.', 'Random': 'عشوائي', 'Identity value randomized': 'تم توليد قيمة هوية عشوائية',
            'Visible SIM count': 'عدد شرائح SIM الظاهرة', 'Limits the active subscription list returned to selected apps. It never creates SIMs that are not present.': 'يحد من قائمة الاشتراكات النشطة المعادة للتطبيقات المحددة، ولا ينشئ شرائح SIM غير موجودة.', 'Randomize Telephony': 'توليد هوية الهاتف عشوائياً',
            "Camera visibility": "إظهار الكاميرا", "Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.": "يرشح اكتشاف الكاميرات للتطبيقات المحددة. عند تعطيله لا يبدأ معترض cameraserver.", "This only reduces discoverable real camera IDs; it does not create cameras or block direct access.": "يقلل فقط معرفات الكاميرات الحقيقية القابلة للاكتشاف؛ ولا ينشئ كاميرات أو يمنع الوصول المباشر.", "Hardware visibility": "إظهار العتاد", "Visible camera count": "عدد الكاميرات الظاهرة", "Limits discoverable camera IDs for selected apps. It never creates cameras that are not present.": "يحد من معرفات الكاميرات القابلة للاكتشاف للتطبيقات المحددة، ولا ينشئ كاميرات غير موجودة.",
            'Dashboard': 'لوحة التحكم', 'Identity': 'الهوية', 'Apps': 'التطبيقات', 'Keyboxes': 'صناديق المفاتيح', 'Info & Resources': 'المعلومات والموارد',
            'Guide': 'الدليل', 'Logs': 'السجلات', 'Editor': 'المحرر', 'Donate': 'تبرع', 'Profiles': 'الملفات الشخصية',
            'Security Patch': 'تصحيح الأمان', 'Core Protection': 'الحماية الأساسية', 'Global Mode': 'الوضع العام', 'Global Keybox': 'Keybox العام', 'Global Identity': 'الهوية العامة',
            'Auto Keybox Check': 'فحص صندوق المفاتيح تلقائيا', 'DRM Passthrough': 'تمرير DRM',
            'Configuration Management': 'إدارة الإعدادات', 'Reload Config': 'إعادة تحميل الإعدادات',
            'Identity Manager': 'مدير الهوية', 'No attestation template': 'لا يوجد قالب تصديق',
            'Randomize All Identifiers': 'عشوائية جميع المعرفات', 'Auto Identity (Pixel Beta)': 'الهوية التلقائية (Pixel Beta)',
            'Apply Identity': 'تطبيق الهوية', 'Application Privacy Shield': 'حماية خصوصية التطبيقات', 'New Rule': 'قاعدة جديدة',
            'Package Name': 'اسم الحزمة', 'Attestation Identity Profile': 'ملف هوية التصديق', 'Custom Keybox': 'صندوق مفاتيح مخصص',
            'Privacy Policy': 'سياسة الخصوصية', 'Add Rule': 'إضافة قاعدة', 'Active Rules': 'القواعد النشطة',
            'Runtime Health': 'حالة التشغيل', 'Resource Monitor': 'مراقب الموارد', 'Module Logs': 'سجلات الوحدة',
            'Refresh Logs': 'تحديث السجلات', 'Download Logs': 'تنزيل السجلات', 'Copy Logs': 'نسخ السجلات',
            'Language': 'اللغة', 'Debug Logging': 'سجل التصحيح', 'Effective State': 'الحالة الفعلية',
            'Resolved Configuration': 'الإعدادات المحسوبة', 'Inspect': 'فحص', 'Select an app.': 'اختر تطبيقا.',
            'Identity is currently disabled. You can enable it from Dashboard.': 'الهوية معطلة حاليا. يمكنك تفعيلها من لوحة التحكم.',
            'CleveresTech Telegram community': 'مجتمع CleveresTech على Telegram', 'CleveresTech Community': 'مجتمع CleveresTech', 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.': 'انضم إلى مجموعة Telegram الخاصة بنا للمساعدة المتبادلة والاختبار والنقاش وتطوير CleveresTricky.',
            'Upload Keybox or CBOX file': 'تحميل ملف Keybox أو CBOX', 'Open Telegram Community': 'فتح مجتمع Telegram', 'Join Telegram Community': 'الانضمام إلى مجتمع Telegram',
            'Save profile': 'حفظ الملف الشخصي', 'Clone': 'نسخ', 'Delete': 'حذف', 'Profile saved': 'تم حفظ الملف الشخصي',
            'System / preinstalled packages are included in this search.': 'تتضمن هذه عملية البحث أيضا حزم النظام والحزم المثبتة مسبقا.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'الترجمات المدمجة محلية ولا تحتاج إلى اتصال بالشبكة. تبقى الإنجليزية هي الافتراضية ما لم تختر لغة أخرى هنا.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'يبقي الحزم الموجودة في drm_packages.txt على مسار Keystore الحقيقي في Android. لا يقوم بتزييف مستوى أمان DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'يفعل تشخيصات تشغيل إضافية دون تثبيت إصدار debug. أوقفه بعد جمع السجلات.',
            'DRM passthrough enabled': 'تم تفعيل تمرير DRM', 'DRM passthrough disabled': 'تم تعطيل تمرير DRM',
            'Debug logging enabled': 'تم تفعيل سجل التصحيح', 'Debug logging disabled': 'تم تعطيل سجل التصحيح',
            'Could not update DRM setting': 'تعذر تحديث إعداد DRM', 'Could not update debug logging': 'تعذر تحديث سجل التصحيح',
            'Identity: Disabled': 'الهوية: معطلة',
            'Identity: Global Mode Active': 'الهوية: الوضع العام نشط',
            'Identity: App-Scoped Mode Active': 'الهوية: وضع التطبيقات نشط',
            'Identity engine is currently disabled on Dashboard. No system properties or identity modifications are active.': 'محرك الهوية معطل حاليًا في لوحة التحكم. لا توجد خصائص نظام أو تعديلات هوية نشطة.',
            'Identity system properties are applied system-wide to all applications.': 'يتم تطبيق خصائص نظام الهوية على مستوى النظام لجميع التطبيقات.',
            'Identity properties apply only to applications in identity_target.txt and configured profiles.': 'تنطبق خصائص الهوية فقط على التطبيقات الموجودة في identity_target.txt والملفات الشخصية المكونة.',
            'Security Patch: Enabled': 'تصحيح الأمان: ممكّن',
            'Security Patch: Disabled': 'تصحيح الأمان: معطل',
            'Security patch levels are actively managed according to the component modes below.': 'تتم إدارة مستويات تصحيح الأمان بشكل نشط وفقًا لأوضاع المكونات أدناه.',
            'Security patch spoofing is currently disabled. Toggle it ON from the Dashboard to apply custom patch levels.': 'محاكاة تصحيح الأمان معطلة حاليًا. قم بتفعيلها من لوحة التحكم لتطبيق مستويات تصحيح مخصصة.',
            'All major features and runtime paths in one place.': 'شرح جميع الميزات الأساسية ومسارات التشغيل في مكان واحد.',
            'noServers': 'لم يتم تكوين أي خوادم. أضف واحداً أدناه لجلب keyboxes تلقائياً.',
            'refresh': 'تحديث',
            'remove': 'إزالة'
        }
    };

    // Turkish defines the canonical complete first-party WebUI key set. The
    // aligned catalog rows below provide the same static and runtime-generated
    // coverage for every other built-in locale.
    Object.assign(TRANSLATIONS.tr, {
        'noServers': 'Yapılandırılmış sunucu yok. Keybox\'ları otomatik olarak almak için aşağıdan bir tane ekleyin.',
        'refresh': 'Yenile',
        'remove': 'Kaldır',
        'Notification': 'Bildirim',
        'Close notification': 'Bildirimi kapat',
        'Always active.': 'Her zaman etkin.',
        'Bootloader/verified-boot property compatibility and Keystore/TEE certificate protection are core module behavior. They have no on/off switch and continue working when Identity Engine is disabled. Hardware bootloader and root-of-trust state are not physically changed.': 'Bootloader/doğrulanmış önyükleme özellik uyumluluğu ve Keystore/TEE sertifika koruması modülün temel davranışıdır. Açma/kapama anahtarları yoktur ve Kimlik Motoru devre dışıyken de çalışırlar. Donanımsal bootloader ve güven kökü durumu fiziksel olarak değiştirilmez.',
        'Identity Engine': 'Kimlik Motoru',
        'Identity Spoof Engine': 'Kimlik Değiştirme Motoru',
        'OFF': 'KAPALI',
        'ON': 'AÇIK',
        'ACTIVE': 'ETKİN',
        'INACTIVE': 'DEVRE DIŞI',
        'ALWAYS ON': 'HER ZAMAN AÇIK',
        'DRM Fix': 'DRM Düzeltmesi',
        'Select the attestation identity used for configured target applications.': 'Yapılandırılmış hedef uygulamalarda kullanılacak attestation kimliğini seçin.',
        'Device': 'Cihaz',
        'Manufacturer': 'Üretici',
        'Template fingerprint': 'Şablon parmak izi',
        'Copy template fingerprint': 'Şablon parmak izini kopyala',
        'Copy Template Fingerprint': 'Şablon Parmak İzini Kopyala',
        'Copy': 'Kopyala',
        'Applying a template persists its fingerprint and build fields. Build Identity at Boot requires Identity Engine and a reboot. Android ID remains Android\'s per-app SSAID, and the actual kernel uname remains unchanged.': 'Bir şablon uygulandığında parmak izi ve build alanları kalıcı olur. Önyüklemede Build Kimliği için Kimlik Motoru ve yeniden başlatma gerekir. Android ID, Android\'in uygulamaya özel SSAID değeri olarak kalır; gerçek kernel uname değeri değişmez.',
        'Auto Identity:': 'Otomatik Kimlik:',
        "for Play Integrity it pulls a current Pixel beta/canary ROM identity from Google's public metadata. Recommended only if you use a Custom ROM. The result is saved locally; enable Identity Engine and reboot to expose build fields.": 'Play Integrity için Google\'ın herkese açık metadatasından güncel bir Pixel beta/canary ROM kimliği alır. Yalnızca Custom ROM kullanıyorsanız önerilir. Sonuç yerel olarak kaydedilir; build alanlarını sunmak için Kimlik Motorunu açıp cihazı yeniden başlatın.',
        'Attestation and Telephony Identifiers': 'Attestation ve Telefon Kimlikleri',
        'These overrides are visible only to selected apps after Android grants the original API request. They do not change modem, SIM, EFS, baseband, or mobile-network identity.': 'Bu geçersiz kılmalar yalnızca Android özgün API isteğine izin verdikten sonra seçili uygulamalara görünür. Modem, SIM, EFS, baseband veya mobil ağ kimliğini değiştirmez.',
        'Subscriber identity': 'Abone kimliği',
        'SIM card identity': 'SIM kart kimliği',
        'Phone number': 'Telefon numarası',
        'Phone number 2': 'Telefon numarası 2',
        'SIM 2 (optional)': 'SIM 2 (isteğe bağlı)',
        'Serial': 'Seri numarası',
        'Device serial': 'Cihaz seri numarası',
        'Randomize All': 'Tümünü Rastgeleleştir',
        'Clear All': 'Tümünü Temizle',
        'Confirm Clear': 'Temizlemeyi Onayla',
        'Apply an identity policy per package while preserving Android\'s original permission result. Isolation creates stable package-specific telephony identifiers and supported attestation identifiers when a verified keybox is active; redaction replaces the same supported identifiers with blank values. Without an active keybox the attestation chain remains unchanged. Shared-UID packages receive one consistent policy. These controls do not claim to block sensors, clipboard, location, VPN detection, or arbitrary app-process checks.': 'Android\'in özgün izin sonucunu koruyarak her paket için bir kimlik politikası uygular. Doğrulanmış bir keybox etkinken izolasyon, pakete özel kararlı telefon ve desteklenen attestation kimlikleri üretir; redaksiyon aynı desteklenen kimlikleri boş değerlerle değiştirir. Etkin keybox yoksa attestation zinciri değişmeden kalır. Ortak UID kullanan paketlere tek ve tutarlı politika uygulanır. Bu kontroller sensörleri, panoyu, konumu, VPN algılamasını veya uygulama içi rastgele kontrolleri engellediğini iddia etmez.',
        'Type to search packages...': 'Paket aramak için yazın...',
        'No identity override': 'Kimlik geçersiz kılması yok',
        'Use global identity': 'Global kimliği kullan',
        'Stable isolated identity': 'Kararlı izole kimlik',
        'Blank supported identifiers': 'Desteklenen kimlikleri boşalt',
        'Package': 'Paket',
        'Profile': 'Profil',
        'Keybox': 'Keybox',
        'Privacy': 'Gizlilik',
        'Save Configuration': 'Yapılandırmayı Kaydet',
        'Filter active rules by package name...': 'Etkin kuralları paket adına göre filtrele...',
        'Filter rules': 'Kuralları filtrele',
        'Clear filter': 'Filtreyi temizle',
        'Encrypted Keyboxes Detected': 'Şifreli Keyboxlar Algılandı',
        'Remote Servers': 'Uzak Sunucular',
        '+ Add Server': '+ Sunucu Ekle',
        'Name': 'Ad',
        'URL (HTTPS)': 'URL (HTTPS)',
        'No Auth': 'Kimlik Doğrulama Yok',
        'Bearer Token': 'Bearer Token',
        'Basic Auth': 'Temel Kimlik Doğrulama',
        'API Key': 'API Anahtarı',
        'Username': 'Kullanıcı adı',
        'Password': 'Parola',
        'Header Name (e.g. X-API-Key)': 'Header Adı (örn. X-API-Key)',
        'Priority': 'Öncelik',
        'Refresh interval (hours)': 'Yenileme aralığı (saat)',
        'Automatic refresh': 'Otomatik yenileme',
        'CBOX content password (optional)': 'CBOX içerik parolası (isteğe bağlı)',
        'CBOX signature public key (optional)': 'CBOX imza açık anahtarı (isteğe bağlı)',
        'Save Server': 'Sunucuyu Kaydet',
        'Cancel': 'İptal',
        'Upload Keybox / CBOX': 'Keybox / CBOX Yükle',
        'Keybox File': 'Keybox Dosyası',
        'Upload Keybox File': 'Keybox Dosyası Yükle',
        '[ Drag & Drop ]': '[ Sürükle ve Bırak ]',
        'Or click to select .xml or .cbox': 'Ya da .xml veya .cbox seçmek için dokunun',
        'Manual Paste (XML)': 'Elle Yapıştır (XML)',
        'Paste Keybox XML Content Here': 'Keybox XML İçeriğini Buraya Yapıştırın',
        'Keybox XML Content': 'Keybox XML İçeriği',
        'Save Pasted XML': 'Yapıştırılan XML\'i Kaydet',
        'Stored Keyboxes': 'Kayıtlı Keyboxlar',
        'Filter keyboxes by name...': 'Keyboxları ada göre filtrele...',
        'Filter keyboxes': 'Keyboxları filtrele',
        'Verification': 'Doğrulama',
        'Check All': 'Tümünü Kontrol Et',
        'Checking module state...': 'Modül durumu kontrol ediliyor...',
        'CHECKING': 'KONTROL EDİLİYOR',
        'Loading resource usage...': 'Kaynak kullanımı yükleniyor...',
        'Feature': 'Özellik',
        'Status': 'Durum',
        'Runtime path': 'Çalışma yolu',
        'Scope': 'Kapsam',
        'Measured daemon CPU and resident memory are shown above. Runtime rows describe configuration and execution scope. Hardware bootloader and root-of-trust warnings can remain visible because this page reports module state, not a physically relocked device.': 'Ölçülen daemon CPU ve yerleşik bellek kullanımı yukarıda gösterilir. Çalışma satırları yapılandırmayı ve yürütme kapsamını açıklar. Bu sayfa fiziksel olarak yeniden kilitlenmiş bir cihazı değil modül durumunu bildirdiği için donanımsal bootloader ve güven kökü uyarıları görünmeye devam edebilir.',
        'Environment': 'Ortam',
        'Process CPU': 'İşlem CPU',
        'Process RSS': 'İşlem RSS',
        'READY': 'HAZIR',
        'REGISTERING': 'KAYDEDİLİYOR',
        'STARTING': 'BAŞLATILIYOR',
        'NATIVE FAILED': 'NATIVE BAŞARISIZ',
        'NATIVE OFFLINE': 'NATIVE ÇEVRİMDIŞI',
        'NO KEYS': 'ANAHTAR YOK',
        'UNAVAILABLE': 'KULLANILAMIYOR',
        'The last native activation attempt failed before the Keystore interceptor became operational.': 'Son native etkinleştirme denemesi Keystore interceptor çalışır duruma gelmeden başarısız oldu.',
        'A native target accepted activation, but the Keystore Binder interceptor is not registered yet.': 'Native hedef etkinleştirmeyi kabul etti ancak Keystore Binder interceptor henüz kaydedilmedi.',
        'Native activation is in progress and the Keystore interceptor is not registered yet.': 'Native etkinleştirme sürüyor ve Keystore interceptor henüz kaydedilmedi.',
        'No operational Keystore interceptor is registered and no matching live native activation is available.': 'Çalışır durumda bir Keystore interceptor kaydı veya eşleşen canlı native etkinleştirme yok.',
        'The native runtime is active, but no verified keybox is currently active.': 'Native çalışma zamanı etkin ancak şu anda doğrulanmış etkin bir keybox yok.',
        'Core boot/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine.': 'Temel boot/TEE uyumluluğu Kimlik Motorundan bağımsız olarak etkin kalır; donanımsal bootloader ve güven kökü durumu gerçek kalır.',
        'Runtime resource data could not be read. Open Logs and check the first CleveresTricky error.': 'Çalışma zamanı kaynak verisi okunamadı. Günlükleri açıp ilk CleveresTricky hatasını kontrol edin.',
        'Resource data unavailable.': 'Kaynak verisi kullanılamıyor.',
        'Resource monitor unavailable. Check module logs.': 'Kaynak izleyici kullanılamıyor. Modül günlüklerini kontrol edin.',
        'Keystore Runtime': 'Keystore Çalışma Zamanı',
        'Telephony Runtime': 'Telefon Çalışma Zamanı',
        'Automatic Keybox Check': 'Otomatik Keybox Kontrolü',
        'Identity Refresh on Boot': 'Önyüklemede Kimlik Yenileme',
        'Telephony Interception': 'Telefon Interception',
        'RKP Protection': 'RKP Koruması',
        'DRM App Passthrough': 'DRM Uygulama Geçişi',
        'Template Build Identity': 'Şablon Build Kimliği',
        'Region Property View': 'Bölge Özelliği Görünümü',
        'Keybox Storage': 'Keybox Depolama',
        'App Rules': 'Uygulama Kuralları',
        'Enabled': 'Etkin',
        'Disabled': 'Devre dışı',
        'Always on': 'Her zaman açık',
        'Info Only': 'Yalnızca bilgi',
        'View recent logs from the module. You can also download them for sharing.': 'Modülün son günlüklerini görüntüleyin. Paylaşmak için indirebilirsiniz.',
        'Select Log Type': 'Günlük Türünü Seç',
        'CleveresTricky Logs': 'CleveresTricky Günlükleri',
        'Errors Only': 'Yalnızca Hatalar',
        'Full System (Recent)': 'Tüm Sistem (Son)',
        'Logs Copied': 'Günlükler Kopyalandı',
        'Select file to edit': 'Düzenlenecek dosyayı seç',
        'Revert Changes': 'Değişiklikleri Geri Al',
        'Revert': 'Geri Al',
        'Save': 'Kaydet',
        'File Content': 'Dosya İçeriği',
        'Support the Development': 'Geliştirmeyi Destekleyin',
        'If you find this project helpful, consider supporting the development. Your contributions help maintain the project and develop new features.': 'Bu projeyi yararlı buluyorsanız geliştirmeyi desteklemeyi düşünebilirsiniz. Katkılarınız projenin bakımına ve yeni özelliklerin geliştirilmesine yardımcı olur.',
        'Crypto Addresses': 'Kripto Adresleri',
        'Asset': 'Varlık',
        'Network': 'Ağ',
        'Address': 'Adres',
        'Platforms': 'Platformlar',
        'Binance User ID': 'Binance Kullanıcı Kimliği',
        'Thank you for your support!': 'Desteğiniz için teşekkürler!',
        'Feature Center': 'Özellik Merkezi',
        'Main controls are here. Parent features reveal only the settings that belong to them.': 'Ana kontroller burada bulunur. Üst özellikler yalnızca kendilerine ait ayarları gösterir.',
        'What does this do?': 'Bu ne yapar?',
        'Global Mode is the module-wide application scope switch.': 'Global Mod, modül genelindeki uygulama kapsamı anahtarıdır.',
        'Applies target rules globally when no narrower application rule wins. Fresh installs default to ON.': 'Daha dar bir uygulama kuralı eşleşmediğinde hedef kuralları global olarak uygular. Yeni kurulumlarda varsayılan olarak açıktır.',
        'Optional identity substitution. Turn it on first, then choose only the child identity paths you want.': 'İsteğe bağlı kimlik değişimi. Önce açın, ardından yalnızca istediğiniz alt kimlik yollarını seçin.',
        'Identity is optional. Core Keystore/TEE protection is independent from this switch.': 'Kimlik isteğe bağlıdır. Temel Keystore/TEE koruması bu anahtardan bağımsızdır.',
        'Build identity': 'Build kimliği',
        'Attestation identity': 'Attestation kimliği',
        'Telephony identity': 'Telefon kimliği',
        'Region identity': 'Bölge kimliği',
        'Identity refresh': 'Kimlik yenileme',
        'Boot fingerprint, model and build fields. Requires a reboot when early-boot properties change.': 'Önyükleme parmak izi, model ve build alanları. Erken önyükleme özellikleri değiştiğinde yeniden başlatma gerekir.',
        'Uses the configured attestation identity only for selected targets; genuine hardware key operations remain on Android.': 'Yapılandırılmış attestation kimliğini yalnızca seçili hedeflerde kullanır; gerçek donanım anahtarı işlemleri Android üzerinde kalır.',
        'Controls optional IMEI/IMSI/ICCID/phone presentation for selected apps.': 'Seçili uygulamalar için isteğe bağlı IMEI/IMSI/ICCID/telefon sunumunu yönetir.',
        'Controls optional region/hardware-region presentation. Some values require a reboot.': 'İsteğe bağlı bölge/donanım bölgesi sunumunu yönetir. Bazı değerler yeniden başlatma gerektirir.',
        'Prepares a new identity for the next boot only while this option is enabled.': 'Yalnızca bu seçenek açıkken bir sonraki önyükleme için yeni kimlik hazırlar.',
        'Independent attestation patch policy. Default is off unless stale-ROM policy enables it.': 'Bağımsız attestation yama politikası. Eski ROM politikası açmadıkça varsayılan olarak kapalıdır.',
        'Security Patch is independent from Identity.': 'Güvenlik Yaması, Kimlik özelliğinden bağımsızdır.',
        'Auto Security Patch': 'Otomatik Güvenlik Yaması',
        'Use automatic mode for stale captured patch values.': 'Eski yakalanmış yama değerleri için otomatik modu kullanır.',
        'Advanced Security Patch': 'Gelişmiş Güvenlik Yaması',
        'Checks configured keyboxes against the module revocation source when enabled.': 'Açıkken yapılandırılmış keyboxları modülün iptal kaynağına göre kontrol eder.',
        'Optional network-backed keybox hygiene; manual management remains available.': 'İsteğe bağlı ağ destekli keybox denetimi; elle yönetim kullanılabilir kalır.',
        'DRM Identifier Privacy': 'DRM Kimlik Gizliliği',
        'Configure Profiles': 'Profilleri Yapılandır',
        'Keybox / TEE path': 'Keybox / TEE yolu',
        'Keyboxes are selected per profile or from the stored pool. Stored XML/CBOX sources are reloaded without requiring an environment reset.': 'Keyboxlar profile göre veya kayıtlı havuzdan seçilir. Kayıtlı XML/CBOX kaynakları ortam sıfırlaması gerektirmeden yeniden yüklenir.',
        'The core Keystore hook remains separate from Identity. Certificate chains are cached to avoid repeated expensive work.': 'Temel Keystore hook, Kimlik özelliğinden ayrı kalır. Pahalı işlemlerin tekrarlanmasını önlemek için sertifika zincirleri önbelleğe alınır.',
        'Open keyboxes': 'Keyboxları Aç',
        'Keybox Status': 'Keybox Durumu',
        'Loading keybox state...': 'Keybox durumu yükleniyor...',
        'Use Profiles for app assignments, identity template, custom keybox, DRM identifier privacy, per-feature overrides and per-app Security Patch rules in one place.': 'Uygulama atamalarını, kimlik şablonunu, özel keyboxı, DRM kimlik gizliliğini, özellik geçersiz kılmalarını ve uygulamaya özel Güvenlik Yaması kurallarını tek yerde yönetmek için Profilleri kullanın.',
        'Open Profiles': 'Profilleri Aç',
        'Independent from Identity. Child controls appear only while this feature is enabled.': 'Kimlik özelliğinden bağımsızdır. Alt kontroller yalnızca bu özellik etkinken görünür.',
        'Use automatic mode for System, Vendor and Boot.': 'System, Vendor ve Boot için otomatik modu kullanır.',
        'Stale ROM threshold (months)': 'Eski ROM eşiği (ay)',
        'Save Security Patch': 'Güvenlik Yamasını Kaydet',
        'Resolve for an app': 'Bir uygulama için çözümle',
        'Shows captured, configured and effective values from the runtime resolver.': 'Çalışma zamanı çözümleyicisindeki yakalanmış, yapılandırılmış ve etkin değerleri gösterir.',
        'Resolve': 'Çözümle',
        'System': 'Sistem',
        'Vendor': 'Üretici',
        'Boot': 'Önyükleme',
        'Device default': 'Cihaz varsayılanı',
        'ROM property': 'ROM özelliği',
        'Manual date': 'Elle tarih',
        'Automatic': 'Otomatik',
        'Omit': 'Atla',
        'Inherit': 'Devral',
        'Inherit / none': 'Devral / yok',
        'Captured:': 'Yakalanan:',
        'Configured:': 'Yapılandırılan:',
        'Effective:': 'Etkin:',
        'App-centric configuration. Assign installed apps or wildcards, then choose privacy, identity, keybox and feature overrides.': 'Uygulama merkezli yapılandırma. Yüklü uygulamaları veya wildcard kurallarını atayın; ardından gizlilik, kimlik, keybox ve özellik geçersiz kılmalarını seçin.',
        'New profile': 'Yeni profil',
        'Export': 'Dışa Aktar',
        'Import': 'İçe Aktar',
        'Profile Editor': 'Profil Düzenleyici',
        'DRM / privacy mode': 'DRM / gizlilik modu',
        'Isolate - app-scoped pseudonymous DRM ID': 'İzole et - uygulamaya özel takma DRM kimliği',
        'Redact': 'Gizle',
        'Add installed app': 'Yüklü uygulama ekle',
        'Add app': 'Uygulama ekle',
        'Assignments (one package or wildcard per line)': 'Atamalar (satır başına bir paket veya wildcard)',
        'Identity template': 'Kimlik şablonu',
        'Feature overrides': 'Özellik geçersiz kılmaları',
        'Security Patch override': 'Güvenlik Yaması geçersiz kılması',
        'No app assignments': 'Uygulama ataması yok',
        'Edit': 'Düzenle',
        'No custom profiles yet.': 'Henüz özel profil yok.',
        'Inspect the exact resolver output for an installed application without exposing private key material.': 'Özel anahtar materyalini açığa çıkarmadan yüklü bir uygulamanın kesin çözümleyici çıktısını inceleyin.',
        'Matched profile': 'Eşleşen profil',
        'Matched rule': 'Eşleşen kural',
        'DRM privacy': 'DRM gizliliği',
        'Keystore core': 'Keystore çekirdeği',
        'Reboot required': 'Yeniden başlatma gerekli',
        'Identity enabled': 'Kimlik etkinleştirildi',
        'Identity disabled': 'Kimlik devre dışı bırakıldı',
        'Security Patch enabled': 'Güvenlik Yaması etkinleştirildi',
        'Security Patch disabled': 'Güvenlik Yaması devre dışı bırakıldı',
        'Auto Security Patch enabled': 'Otomatik Güvenlik Yaması etkinleştirildi',
        'Auto Security Patch disabled': 'Otomatik Güvenlik Yaması devre dışı bırakıldı',
        'Security Patch policy saved': 'Güvenlik Yaması politikası kaydedildi',
        'Profile cloned': 'Profil klonlandı',
        'Profile deleted': 'Profil silindi',
        'Profile name already exists': 'Profil adı zaten var',
        'Profile name is invalid': 'Profil adı geçersiz',
        'Profile policy copied/exported': 'Profil politikası kopyalandı/dışa aktarıldı',
        'Profile policy imported': 'Profil politikası içe aktarıldı',
        'Could not save policy': 'Politika kaydedilemedi',
        'Could not update setting': 'Ayar güncellenemedi',
        'Could not resolve patch state': 'Yama durumu çözümlenemedi',
        'Could not inspect effective state': 'Etkin durum incelenemedi',
        'The operation failed. Open Logs for details.': 'İşlem başarısız oldu. Ayrıntılar için Günlükleri açın.',
        'RESOLVING PIXEL IDENTITY...': 'PIXEL KİMLİĞİ ÇÖZÜMLENİYOR...',
        'Resolving Pixel identity...': 'Pixel kimliği çözümleniyor...',
        'Auto Identity source is temporarily unavailable. Try again later or choose a local template.': 'Otomatik Kimlik kaynağı geçici olarak kullanılamıyor. Daha sonra tekrar deneyin veya yerel bir şablon seçin.',
        'Auto Identity failed': 'Otomatik Kimlik başarısız oldu',
        'Estimated impact: CPU low per matching identity/attestation call; RAM low and bounded.': 'Tahmini etki: eşleşen kimlik/attestation çağrısı başına düşük CPU; düşük ve sınırlı RAM.',
        'Estimated impact: CPU very low while idle and low per matching Binder call; RAM low.': 'Tahmini etki: boşta çok düşük, eşleşen Binder çağrısı başına düşük CPU; düşük RAM.',
        'Estimated impact: CPU low only on matching calls; RAM low.': 'Tahmini etki: yalnızca eşleşen çağrılarda düşük CPU; düşük RAM.',
        'Estimated impact: CPU very low per UID decision; RAM low with a bounded UID cache.': 'Tahmini etki: UID kararı başına çok düşük CPU; sınırlı UID önbelleğiyle düşük RAM.',
        'Estimated impact: CPU/network low during scheduled verification; RAM low and temporary.': 'Tahmini etki: zamanlanmış doğrulamada düşük CPU/ağ; düşük ve geçici RAM.',
        'Estimated impact: CPU low at boot only; RAM negligible after initialization.': 'Tahmini etki: yalnızca önyüklemede düşük CPU; başlatma sonrasında ihmal edilebilir RAM.',
        'Estimated impact: CPU low per matching Binder call; RAM low.': 'Tahmini etki: eşleşen Binder çağrısı başına düşük CPU; düşük RAM.',
        'Estimated impact: CPU negligible on protected infrastructure paths; RAM negligible.': 'Tahmini etki: korunan altyapı yollarında ve RAM kullanımında ihmal edilebilir.',
        'Estimated impact: CPU low per matching package lookup; RAM low and bounded.': 'Tahmini etki: eşleşen paket sorgusu başına düşük CPU; düşük ve sınırlı RAM.',
        'Estimated impact: CPU low at boot only; RAM negligible after properties are prepared.': 'Tahmini etki: yalnızca önyüklemede düşük CPU; özellikler hazırlandıktan sonra ihmal edilebilir RAM.',
        'Estimated impact: CPU low at boot only; RAM negligible.': 'Tahmini etki: yalnızca önyüklemede düşük CPU; ihmal edilebilir RAM.',
        'Estimated impact: CPU low during refresh/verification; RAM moderate and bounded by active certificate chains.': 'Tahmini etki: yenileme/doğrulamada düşük CPU; etkin sertifika zincirleriyle sınırlı orta düzey RAM.',
        'Estimated impact: CPU very low with cached lookups; RAM low and proportional to configured rules.': 'Tahmini etki: önbellekli sorgularda çok düşük CPU; yapılandırılmış kurallarla orantılı düşük RAM.',
        'Identity overrides enabled': 'Kimlik geçersiz kılmaları etkin',
        'Identity overrides off': 'Kimlik geçersiz kılmaları kapalı',
        'Attestation, telephony and build identity only': 'Yalnızca attestation, telefon ve build kimliği',
        'Does not disable the core Keystore/TEE or boot protection paths.': 'Temel Keystore/TEE veya önyükleme koruma yollarını devre dışı bırakmaz.',
        'Registered and Binder alive': 'Kayıtlı ve Binder çalışıyor',
        'Not operational': 'Çalışmıyor',
        'Keystore2 Binder lifecycle': 'Keystore2 Binder yaşam döngüsü',
        'Reports the daemon registration state rather than inferring readiness from configuration.': 'Hazır olma durumunu yapılandırmadan tahmin etmek yerine daemon kayıt durumunu bildirir.',
        'Enabled but not operational': 'Etkin ancak çalışmıyor',
        'Phone subscription Binder lifecycle': 'Telefon aboneliği Binder yaşam döngüsü',
        'Identity-only Binder path; it is parked while Identity Engine is off.': 'Yalnızca kimlik için kullanılan Binder yolu; Kimlik Motoru kapalıyken park edilir.',
        'UID decision only': 'Yalnızca UID kararı',
        'Resolved application UIDs': 'Çözümlenmiş uygulama UID\'leri',
        'Targets every eligible app while protecting system and RKP infrastructure UIDs.': 'Sistem ve RKP altyapı UID\'lerini korurken uygun tüm uygulamaları hedefler.',
        'Scheduled background check': 'Zamanlanmış arka plan kontrolü',
        'Worker stopped': 'Worker durduruldu',
        'Authorized key material': 'Yetkili anahtar materyali',
        'Core keybox maintenance; independent from Identity Engine.': 'Temel keybox bakımı; Kimlik Motorundan bağımsızdır.',
        'Boot only': 'Yalnızca önyüklemede',
        'Persisted identity fields': 'Kalıcı kimlik alanları',
        'Refreshes configured attestation and app-visible telephony identifiers.': 'Yapılandırılmış attestation ve uygulamaya görünen telefon kimliklerini yeniler.',
        'Matching Binder calls only': 'Yalnızca eşleşen Binder çağrıları',
        'Permission-approved app APIs': 'İzin verilmiş uygulama API\'leri',
        'Overrides configured dual-SIM values without changing modem or carrier identity.': 'Modem veya operatör kimliğini değiştirmeden yapılandırılmış çift SIM değerlerini geçersiz kılar.',
        'Protected infrastructure + unified key path': 'Korunan altyapı + birleştirilmiş anahtar yolu',
        'RKP callers and targeted KeyMint replies': 'RKP çağıranlar ve hedeflenmiş KeyMint yanıtları',
        'RKP infrastructure UIDs always stay on Android. Targeted generateKey and getKeyEntry responses share one certificate-compatibility path to avoid split attestation leaves.': 'RKP altyapı UID\'leri her zaman Android yolunda kalır. Hedeflenmiş generateKey ve getKeyEntry yanıtları, ayrık attestation leaf oluşmasını önlemek için tek sertifika uyumluluk yolunu paylaşır.',
        'Bounded UID lookup': 'Sınırlı UID sorgusu',
        'drm_packages.txt rules': 'drm_packages.txt kuralları',
        'Leaves configured playback and DRM packages on the original keystore path.': 'Yapılandırılmış oynatma ve DRM paketlerini özgün Keystore yolunda bırakır.',
        'Fingerprint and Build fields': 'Parmak izi ve Build alanları',
        'Persists the selected template before Zygote; requires reboot.': 'Seçilen şablonu Zygote öncesinde kalıcılaştırır; yeniden başlatma gerekir.',
        'Bounded userspace region properties': 'Sınırlı userspace bölge özellikleri',
        'Applies the optional CN region view before Zygote; requires reboot.': 'İsteğe bağlı CN bölge görünümünü Zygote öncesinde uygular; yeniden başlatma gerekir.',
        'Validated bounded cache': 'Doğrulanmış sınırlı önbellek',
        'Uses root-only storage and fails closed when revocation data is unavailable.': 'Yalnızca root erişimli depolama kullanır ve iptal verisi yoksa fail-closed davranır.',
        'Cached package lookup': 'Önbellekli paket sorgusu',
        'Selects target-specific identity and keybox policy.': 'Hedefe özel kimlik ve keybox politikasını seçer.',
        "Profile privacy Isolate replaces only DRM deviceUniqueId with a stable app-scoped pseudonymous ID. Licenses, provisioning and security level stay on Android's genuine DRM path.": 'Profil gizliliğindeki İzole Et seçeneği yalnızca DRM deviceUniqueId değerini uygulamaya özel kararlı bir takma kimlikle değiştirir. Lisanslar, provisioning ve güvenlik seviyesi Android\'in gerçek DRM yolunda kalır.',
        'Profile privacy': 'Profil gizliliğindeki',
        'Isolate': 'İzole Et',
        'replaces only DRM': 'seçeneği yalnızca DRM',
        "with a stable app-scoped pseudonymous ID. Licenses, provisioning and security level stay on Android's genuine DRM path.": 'değerini uygulamaya özel kararlı bir takma kimlikle değiştirir. Lisanslar, provisioning ve güvenlik seviyesi Android\'in gerçek DRM yolunda kalır.',
        "Keeps packages from drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt içindeki paketleri Android\'in gerçek Keystore yolunda tutar. DRM güvenlik seviyesini taklit etmez.',
        "DRM App Passthrough keeps configured packages on Android's genuine Keystore path. It does not fake a DRM security level; use Profiles > Privacy > Isolate for app-scoped DRM device identifiers.": 'DRM App Passthrough yapılandırılmış paketleri Android\'in gerçek Keystore yolunda tutar. DRM güvenlik seviyesini taklit etmez; uygulamaya özel DRM cihaz kimlikleri için Profiller > Gizlilik > İzole Et seçeneğini kullanın.',
        'Security Patch is independent from Identity. It controls system, vendor, and boot security patch levels; use Device Default to keep captured values, Automatic for calendar-based policy, or Manual for an explicit date.': 'Güvenlik Yaması Kimlikten bağımsızdır. Sistem, vendor ve boot güvenlik yaması seviyelerini yönetir; yakalanan değerleri korumak için Device Default, takvim tabanlı politika için Automatic, açık bir tarih için Manual modunu kullanın.',
        'Use Profiles > Privacy > Isolate for apps that should not share the genuine DRM device identifier.': 'Gerçek DRM cihaz kimliğini paylaşmaması gereken uygulamalar için Profiller > Gizlilik > İzole Et seçeneğini kullanın.',
        'Override the Security Patch feature only for apps assigned to this profile.': 'Güvenlik Yaması özelliğini yalnızca bu profile atanmış uygulamalar için geçersiz kılar.',
        'Show': 'Göster', 'Hide': 'Gizle', 'SIM 1': 'SIM 1',
        '14 hexadecimal characters': '14 onaltılık karakter',
        'Loading...': 'Yükleniyor...', 'Saving...': 'Kaydediliyor...', 'Fetching...': 'Alınıyor...',
        'Generating...': 'Üretiliyor...', 'Synchronizing...': 'Eşitleniyor...', 'Exporting...': 'Dışa aktarılıyor...',
        'Refreshing...': 'Yenileniyor...', 'Verifying...': 'Doğrulanıyor...', 'Downloading...': 'İndiriliyor...',
        'Updating...': 'Güncelleniyor...', 'Removing...': 'Kaldırılıyor...', 'Deleting...': 'Siliniyor...',
        'Uploading...': 'Yükleniyor...', 'Applying...': 'Uygulanıyor...', 'Unlocking...': 'Kilidi açılıyor...',
        'Resetting...': 'Sıfırlanıyor...', 'Restoring...': 'Geri yükleniyor...', 'Creating encrypted backup...': 'Şifreli yedek oluşturuluyor...',
        'Saved': 'Kaydedildi', 'Success': 'Başarılı', 'Deleted': 'Silindi', 'Refreshed': 'Yenilendi',
        'Reloaded': 'Yeniden yüklendi', 'Unlocked!': 'Kilit açıldı!', 'Language Loaded': 'Dil yüklendi',
        'Logs refreshed': 'Günlükler yenilendi', 'No logs to download': 'İndirilecek günlük yok',
        'Password required': 'Parola gerekli', 'Server Added': 'Sunucu eklendi', 'Server Removed': 'Sunucu kaldırıldı',
        'Uploaded Successfully': 'Başarıyla yüklendi', 'Saved Successfully': 'Başarıyla kaydedildi',
        'Please paste XML content first': 'Önce XML içeriğini yapıştırın',
        'Installed package list is unavailable': 'Yüklü paket listesi kullanılamıyor',
        'Could not load boot property policy': 'Önyükleme özelliği politikası yüklenemedi',
        'Saving boot property policy...': 'Önyükleme özelliği politikası kaydediliyor...',
        'Boot property policy saved': 'Önyükleme özelliği politikası kaydedildi',
        'Invalid setting': 'Geçersiz ayar', 'Setting control is unavailable': 'Ayar kontrolü kullanılamıyor',
        'Setting Updated': 'Ayar güncellendi', 'Identity Generated': 'Kimlik üretildi',
        'Verification Failed': 'Doğrulama başarısız', 'No keyboxes to verify': 'Doğrulanacak keybox yok',
        'Verification Complete': 'Doğrulama tamamlandı', 'Configuration Saved': 'Yapılandırma kaydedildi',
        'Package required': 'Paket gerekli', 'Invalid package': 'Geçersiz paket',
        'Select a profile, keybox, or privacy policy': 'Bir profil, keybox veya gizlilik politikası seçin',
        'Rule Updated': 'Kural güncellendi', 'Rule Added': 'Kural eklendi',
        'Saving App Config...': 'Uygulama yapılandırması kaydediliyor...', 'App Config Saved': 'Uygulama yapılandırması kaydedildi',
        'Please select a profile first': 'Önce bir profil seçin',
        'Runtime settings synchronized': 'Çalışma zamanı ayarları eşitlendi',
        'Environment Reset - New identity generated': 'Ortam sıfırlandı - Yeni kimlik üretildi',
        'Backup password must be at least 12 characters': 'Yedekleme parolası en az 12 karakter olmalıdır',
        'Only encrypted .ctsb backups are accepted': 'Yalnızca şifreli .ctsb yedekleri kabul edilir',
        'Enter the backup password (at least 12 characters)': 'Yedekleme parolasını girin (en az 12 karakter)',
        'You have unsaved changes. Click tab again to discard.': 'Kaydedilmemiş değişiklikler var. Vazgeçmek için sekmeye yeniden dokunun.',
        'You have unsaved changes. Select file again to discard.': 'Kaydedilmemiş değişiklikler var. Vazgeçmek için dosyayı yeniden seçin.',
        'Failed to load file': 'Dosya yüklenemedi', 'Error loading file': 'Dosya yüklenirken hata oluştu',
        'File Saved': 'Dosya kaydedildi', 'Changes reverted': 'Değişiklikler geri alındı',
        'Copy failed. Check permissions.': 'Kopyalama başarısız. İzinleri kontrol edin.',
        'Network error: Failed to reach the server. Is the module running?': 'Ağ hatası: Sunucuya ulaşılamadı. Modül çalışıyor mu?',
        'Select a non-empty XML or CBOX file up to 10 MB': 'En fazla 10 MB boyutunda, boş olmayan bir XML veya CBOX dosyası seçin',
        'Could not import profile policy': 'Profil politikası içe aktarılamadı',
        'Policy file is too large': 'Politika dosyası çok büyük'
    });

    Object.assign(TRANSLATIONS.tr, {
        'Restore Defaults': 'Varsayılanlara Dön',
        'Restore module settings to defaults?': 'Modül ayarları varsayılanlara döndürülsün mü?',
        'Restores module settings using the built-in default profile. Stored keyboxes and encrypted backups are not deleted.': 'Yerleşik varsayılan profil ile modül ayarlarını geri yükler. Kayıtlı keyboxlar ve şifreli yedekler silinmez.',
        'Default settings restored': 'Varsayılan ayarlar geri yüklendi',
        'Could not restore defaults': 'Varsayılanlar geri yüklenemedi'
    });

    Object.assign(TRANSLATIONS.tr, {
        'Custom Templates': 'Özel Şablonlar',
        'Create a reusable device identity template. The form stays collapsed until you open it.': 'Yeniden kullanılabilir bir cihaz kimliği şablonu oluşturun. Form siz açana kadar kapalı kalır.',
        'Template ID': 'Şablon Kimliği', 'Manufacturer': 'Üretici', 'Model': 'Model', 'Fingerprint': 'Parmak izi',
        'Brand': 'Marka', 'Product': 'Ürün', 'Device': 'Cihaz', 'Android release': 'Android sürümü',
        'Build ID': 'Derleme Kimliği', 'Incremental': 'Artımlı derleme', 'Build type': 'Derleme türü', 'Build tags': 'Derleme etiketleri',
        'Security patch': 'Güvenlik yaması', 'Save custom template': 'Özel şablonu kaydet',
        'Custom template saved': 'Özel şablon kaydedildi', 'Template ID is invalid': 'Şablon kimliği geçersiz',
        'Built-in template IDs cannot be replaced': 'Yerleşik şablon kimlikleri değiştirilemez',
        'All template fields are required': 'Tüm şablon alanları zorunludur',
        'Security patch must be YYYY-MM-DD': 'Güvenlik yaması YYYY-AA-GG biçiminde olmalıdır',
        'Template catalog is unavailable': 'Şablon kataloğu kullanılamıyor',
        'Could not save custom template': 'Özel şablon kaydedilemedi'
    });

    Object.assign(TRANSLATIONS.tr, {
        'Kernel Identity': 'Çekirdek Kimliği', 'Hook kernel name': 'Çekirdek adını hookla', 'GKI preset': 'GKI ön ayarı',
        'uname release': 'uname sürümü', 'uname version': 'uname derleme bilgisi', 'Save kernel identity': 'Çekirdek kimliğini kaydet',
        'Custom': 'Özel', 'Kernel identity applied': 'Çekirdek kimliği uygulandı',
        'Kernel identity saved for next native activation': 'Çekirdek kimliği sonraki yerel etkinleştirme için kaydedildi',
        'Could not load kernel identity': 'Çekirdek kimliği yüklenemedi',
        'Optionally overrides uname release/version inside the injected Keystore runtime. Official GKI presets use published base kernel versions and remain editable.': 'Enjekte edilen Keystore çalışma zamanında uname sürüm/derleme bilgisini isteğe bağlı olarak değiştirir. Resmî GKI ön ayarları yayımlanan temel çekirdek sürümlerini kullanır ve düzenlenebilir.',
        'Disabled by default. Core Binder protection is independent from this option.': 'Varsayılan olarak kapalıdır. Temel Binder koruması bu seçenekten bağımsızdır.'
    });

    // Complete catalogs share one source key per row to keep all built-in
    // locales aligned without adding locale-specific runtime assets. Columns:
    // source, zh-CN, es, de, ru, id, hi, ar.
    const COMPLETE_LOCALE_IDS = ['zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar'];
    const COMPLETE_CATALOG_ROWS = [
        ["Profile saved", "配置档案已保存", "Perfil guardado", "Profil gespeichert", "Профиль сохранён", "Profil disimpan", "प्रोफ़ाइल सहेजी गई", "تم حفظ الملف الشخصي"],
        ["Notification", "通知", "Notificación", "Benachrichtigung", "Уведомление", "Notifikasi", "सूचना", "إشعار"],
        ["Close notification", "关闭通知", "Cerrar notificación", "Benachrichtigung schließen", "Закрыть уведомление", "Tutup notifikasi", "सूचना बंद करें", "إغلاق الإشعار"],
        ["Always active.", "始终启用。", "Siempre activo.", "Immer aktiv.", "Всегда активно.", "Selalu aktif.", "हमेशा सक्रिय।", "نشط دائما."],
        ["Bootloader/verified-boot property compatibility and Keystore/TEE certificate protection are core module behavior. They have no on/off switch and continue working when Identity Engine is disabled. Hardware bootloader and root-of-trust state are not physically changed.", "Bootloader/验证启动属性兼容性和 Keystore/TEE 证书保护是模块的核心行为。它们没有开关，即使身份引擎关闭也会继续工作。硬件 Bootloader 和信任根状态不会被物理更改。", "La compatibilidad de propiedades del bootloader/arranque verificado y la protección de certificados Keystore/TEE son funciones esenciales del módulo. No tienen interruptor y siguen funcionando con el Motor de identidad desactivado. El bootloader físico y el estado de la raíz de confianza no se modifican.", "Die Kompatibilität von Bootloader-/Verified-Boot-Eigenschaften und der Keystore-/TEE-Zertifikatschutz gehören zum Kernverhalten des Moduls. Sie besitzen keinen Schalter und arbeiten auch bei deaktivierter Identitäts-Engine weiter. Der Hardware-Bootloader und der Root-of-Trust-Status werden nicht physisch verändert.", "Совместимость свойств загрузчика/verified boot и защита сертификатов Keystore/TEE — базовые функции модуля. У них нет переключателя, и они продолжают работать при отключённом движке идентичности. Аппаратный загрузчик и состояние корня доверия физически не изменяются.", "Kompatibilitas properti bootloader/verified boot dan perlindungan sertifikat Keystore/TEE adalah fungsi inti modul. Keduanya tidak memiliki sakelar dan tetap bekerja saat Mesin Identitas dimatikan. Bootloader perangkat keras dan status root-of-trust tidak diubah secara fisik.", "Bootloader/verified-boot प्रॉपर्टी संगतता और Keystore/TEE सर्टिफिकेट सुरक्षा मॉड्यूल का मुख्य व्यवहार है। इनके लिए कोई ऑन/ऑफ स्विच नहीं है और Identity Engine बंद होने पर भी ये काम करते हैं। हार्डवेयर bootloader और root-of-trust की वास्तविक स्थिति नहीं बदली जाती।", "توافق خصائص bootloader/verified boot وحماية شهادات Keystore/TEE من وظائف الوحدة الأساسية. لا يوجد لهما مفتاح تشغيل أو إيقاف، ويستمران في العمل عند تعطيل محرك الهوية. لا تتغير حالة محمّل الإقلاع المادي أو جذر الثقة فعليا."],
        ["Identity Engine", "身份引擎", "Motor de identidad", "Identitäts-Engine", "Движок идентичности", "Mesin Identitas", "पहचान इंजन", "محرك الهوية"],
        ["Identity Spoof Engine", "身份替换引擎", "Motor de sustitución de identidad", "Engine für Identitätsersetzung", "Движок подмены идентичности", "Mesin Penggantian Identitas", "पहचान प्रतिस्थापन इंजन", "محرك استبدال الهوية"],
        ["OFF", "关闭", "DESACTIVADO", "AUS", "ВЫКЛ.", "MATI", "बंद", "متوقف"],
        ["ON", "开启", "ACTIVADO", "EIN", "ВКЛ.", "AKTIF", "चालू", "مفعّل"],
        ["ACTIVE", "活动", "ACTIVO", "AKTIV", "АКТИВНО", "AKTIF", "सक्रिय", "نشط"],
        ["INACTIVE", "未启用", "INACTIVO", "INAKTIV", "НЕАКТИВНО", "NONAKTIF", "निष्क्रिय", "غير نشط"],
        ["ALWAYS ON", "始终开启", "SIEMPRE ACTIVO", "IMMER EIN", "ВСЕГДА ВКЛ.", "SELALU AKTIF", "हमेशा चालू", "مفعّل دائما"],
        ["DRM Fix", "DRM 修复", "Corrección DRM", "DRM-Korrektur", "Исправление DRM", "Perbaikan DRM", "DRM सुधार", "إصلاح DRM"],
        ["Select the attestation identity used for configured target applications.", "选择为已配置目标应用使用的认证身份。", "Selecciona la identidad de atestación que usarán las aplicaciones objetivo configuradas.", "Wähle die Attestierungsidentität für konfigurierte Ziel-Apps aus.", "Выберите идентичность аттестации для настроенных целевых приложений.", "Pilih identitas atestasi yang digunakan untuk aplikasi target yang dikonfigurasi.", "कॉन्फ़िगर किए गए लक्ष्य ऐप्स के लिए उपयोग होने वाली attestation पहचान चुनें।", "اختر هوية التصديق المستخدمة للتطبيقات المستهدفة التي تم إعدادها."],
        ["Device", "设备", "Dispositivo", "Gerät", "Устройство", "Perangkat", "डिवाइस", "الجهاز"],
        ["Manufacturer", "制造商", "Fabricante", "Hersteller", "Производитель", "Produsen", "निर्माता", "الشركة المصنعة"],
        ["Template fingerprint", "模板指纹", "Huella de la plantilla", "Vorlagen-Fingerprint", "Отпечаток шаблона", "Sidik jari templat", "टेम्पलेट फ़िंगरप्रिंट", "بصمة القالب"],
        ["Copy template fingerprint", "复制模板指纹", "Copiar huella de la plantilla", "Vorlagen-Fingerprint kopieren", "Копировать отпечаток шаблона", "Salin sidik jari templat", "टेम्पलेट फ़िंगरप्रिंट कॉपी करें", "نسخ بصمة القالب"],
        ["Copy Template Fingerprint", "复制模板指纹", "Copiar huella de la plantilla", "Vorlagen-Fingerprint kopieren", "Копировать отпечаток шаблона", "Salin Sidik Jari Templat", "टेम्पलेट फ़िंगरप्रिंट कॉपी करें", "نسخ بصمة القالب"],
        ["Copy", "复制", "Copiar", "Kopieren", "Копировать", "Salin", "कॉपी करें", "نسخ"],
        ["Applying a template persists its fingerprint and build fields. Build Identity at Boot requires Identity Engine and a reboot. Android ID remains Android's per-app SSAID, and the actual kernel uname remains unchanged.", "应用模板会保存其指纹和构建字段。开机应用构建身份需要启用身份引擎并重启。Android ID 仍是 Android 为每个应用分配的 SSAID，实际内核 uname 不变。", "Aplicar una plantilla guarda su huella y los campos de compilación. La identidad de compilación al arrancar requiere el Motor de identidad y reiniciar. Android ID sigue siendo el SSAID por aplicación de Android y el uname real del kernel no cambia.", "Beim Anwenden einer Vorlage werden Fingerprint und Build-Felder gespeichert. Die Build-Identität beim Start erfordert die Identitäts-Engine und einen Neustart. Die Android-ID bleibt Androids App-spezifische SSAID; der tatsächliche Kernel-uname bleibt unverändert.", "Применение шаблона сохраняет его отпечаток и поля сборки. Для идентичности сборки при загрузке нужны движок идентичности и перезагрузка. Android ID остаётся отдельным SSAID Android для каждого приложения, а реальный uname ядра не меняется.", "Menerapkan templat menyimpan sidik jari dan bidang build. Identitas Build saat boot memerlukan Mesin Identitas dan reboot. Android ID tetap berupa SSAID per aplikasi milik Android, dan uname kernel yang sebenarnya tidak berubah.", "टेम्पलेट लागू करने पर उसका फ़िंगरप्रिंट और build फ़ील्ड सहेजे जाते हैं। बूट पर Build Identity के लिए Identity Engine और रीबूट आवश्यक है। Android ID, Android का प्रति-ऐप SSAID ही रहता है और वास्तविक kernel uname नहीं बदलता।", "يؤدي تطبيق القالب إلى حفظ بصمته وحقول build. تتطلب هوية build عند الإقلاع محرك الهوية وإعادة التشغيل. يبقى Android ID هو SSAID الخاص بكل تطبيق، ولا يتغير uname الفعلي للنواة."],
        ["Auto Identity:", "自动身份：", "Identidad automática:", "Automatische Identität:", "Автоидентичность:", "Identitas Otomatis:", "स्वचालित पहचान:", "الهوية التلقائية:"],
        ["for Play Integrity it pulls a current Pixel beta/canary ROM identity from Google's public metadata. Recommended only if you use a Custom ROM. The result is saved locally; enable Identity Engine and reboot to expose build fields.", "它会从 Google 的公开元数据获取当前 Pixel beta/canary ROM 身份供 Play Integrity 使用。仅建议自定义 ROM 用户启用。结果保存在本地；启用身份引擎并重启后才会公开构建字段。", "para Play Integrity obtiene una identidad ROM Pixel beta/canary actual de los metadatos públicos de Google. Solo se recomienda si usas una ROM personalizada. El resultado se guarda localmente; activa el Motor de identidad y reinicia para exponer los campos de compilación.", "für Play Integrity wird eine aktuelle Pixel-Beta-/Canary-ROM-Identität aus öffentlichen Google-Metadaten geladen. Nur für Custom-ROMs empfohlen. Das Ergebnis wird lokal gespeichert; aktiviere die Identitäts-Engine und starte neu, um Build-Felder bereitzustellen.", "для Play Integrity загружается актуальная идентичность Pixel beta/canary ROM из открытых метаданных Google. Рекомендуется только для Custom ROM. Результат сохраняется локально; включите движок идентичности и перезагрузитесь, чтобы предоставить поля сборки.", "untuk Play Integrity, identitas ROM Pixel beta/canary terbaru diambil dari metadata publik Google. Hanya disarankan jika Anda memakai Custom ROM. Hasil disimpan lokal; aktifkan Mesin Identitas dan reboot agar bidang build tersedia.", "Play Integrity के लिए यह Google के सार्वजनिक metadata से वर्तमान Pixel beta/canary ROM पहचान लेता है। केवल Custom ROM उपयोगकर्ताओं के लिए सुझाया गया है। परिणाम स्थानीय रूप से सहेजा जाता है; build फ़ील्ड दिखाने के लिए Identity Engine चालू करके रीबूट करें।", "بالنسبة إلى Play Integrity، يجلب هوية ROM حديثة من Pixel beta/canary من بيانات Google العامة. يوصى به فقط عند استخدام Custom ROM. تحفظ النتيجة محليا؛ فعّل محرك الهوية وأعد التشغيل لإظهار حقول build."],
        ["Attestation and Telephony Identifiers", "认证和电话标识符", "Identificadores de atestación y telefonía", "Attestierungs- und Telefonie-Kennungen", "Идентификаторы аттестации и телефонии", "Pengenal Atestasi dan Telepon", "Attestation और टेलीफ़ोनी पहचानकर्ता", "معرّفات التصديق والهاتف"],
        ["These overrides are visible only to selected apps after Android grants the original API request. They do not change modem, SIM, EFS, baseband, or mobile-network identity.", "只有在 Android 允许原始 API 请求后，选定应用才能看到这些替换值。它们不会更改调制解调器、SIM、EFS、基带或移动网络身份。", "Estas sustituciones solo son visibles para las apps seleccionadas después de que Android autorice la solicitud API original. No cambian el módem, la SIM, EFS, la banda base ni la identidad de la red móvil.", "Diese Überschreibungen sind nur für ausgewählte Apps sichtbar, nachdem Android die ursprüngliche API-Anfrage erlaubt hat. Modem, SIM, EFS, Baseband und Mobilfunkidentität werden nicht verändert.", "Эти переопределения видны только выбранным приложениям после разрешения исходного API-запроса Android. Они не изменяют модем, SIM, EFS, baseband или идентичность мобильной сети.", "Override ini hanya terlihat oleh aplikasi terpilih setelah Android mengizinkan permintaan API asli. Ini tidak mengubah modem, SIM, EFS, baseband, atau identitas jaringan seluler.", "Android द्वारा मूल API अनुरोध की अनुमति मिलने के बाद ही ये override चुने गए ऐप्स को दिखते हैं। ये modem, SIM, EFS, baseband या मोबाइल नेटवर्क पहचान को नहीं बदलते।", "لا تظهر هذه التجاوزات إلا للتطبيقات المحددة بعد أن يسمح Android بطلب API الأصلي. وهي لا تغيّر المودم أو SIM أو EFS أو baseband أو هوية شبكة الهاتف."],
        ["Subscriber identity", "用户身份", "Identidad del abonado", "Teilnehmerkennung", "Идентификатор абонента", "Identitas pelanggan", "सब्सक्राइबर पहचान", "هوية المشترك"],
        ["SIM card identity", "SIM 卡身份", "Identidad de la tarjeta SIM", "SIM-Kartenkennung", "Идентификатор SIM-карты", "Identitas kartu SIM", "SIM कार्ड पहचान", "هوية بطاقة SIM"],
        ["Phone number", "电话号码", "Número de teléfono", "Telefonnummer", "Номер телефона", "Nomor telepon", "फ़ोन नंबर", "رقم الهاتف"],
        ["Phone number 2", "电话号码 2", "Número de teléfono 2", "Telefonnummer 2", "Номер телефона 2", "Nomor telepon 2", "फ़ोन नंबर 2", "رقم الهاتف 2"],
        ["SIM 2 (optional)", "SIM 2（可选）", "SIM 2 (opcional)", "SIM 2 (optional)", "SIM 2 (необязательно)", "SIM 2 (opsional)", "SIM 2 (वैकल्पिक)", "SIM 2 (اختياري)"],
        ["Serial", "序列号", "Serie", "Seriennummer", "Серийный номер", "Serial", "सीरियल", "الرقم التسلسلي"],
        ["Device serial", "设备序列号", "Serie del dispositivo", "Geräteseriennummer", "Серийный номер устройства", "Serial perangkat", "डिवाइस सीरियल", "الرقم التسلسلي للجهاز"],
        ["Randomize All", "全部随机化", "Aleatorizar todo", "Alles randomisieren", "Случайные значения для всех", "Acak Semua", "सभी को रैंडम करें", "تعيين الكل عشوائيا"],
        ["Clear All", "全部清除", "Borrar todo", "Alles löschen", "Очистить всё", "Hapus Semua", "सभी साफ़ करें", "مسح الكل"],
        ["Confirm Clear", "确认清除", "Confirmar borrado", "Löschen bestätigen", "Подтвердить очистку", "Konfirmasi Penghapusan", "साफ़ करने की पुष्टि करें", "تأكيد المسح"],
        ["Apply an identity policy per package while preserving Android's original permission result. Isolation creates stable package-specific telephony identifiers and supported attestation identifiers when a verified keybox is active; redaction replaces the same supported identifiers with blank values. Without an active keybox the attestation chain remains unchanged. Shared-UID packages receive one consistent policy. These controls do not claim to block sensors, clipboard, location, VPN detection, or arbitrary app-process checks.", "在保留 Android 原始权限结果的同时，为每个包应用身份策略。启用已验证的密钥盒时，隔离会创建稳定的包专属电话标识符和受支持的认证标识符；遮蔽会将这些标识符替换为空值。没有活动密钥盒时，认证链保持不变。共享 UID 的包使用同一策略。这些控制不声称能阻止传感器、剪贴板、位置、VPN 检测或任意应用进程检查。", "Aplica una política de identidad por paquete conservando el resultado de permisos original de Android. Con una keybox verificada activa, el aislamiento crea identificadores estables de telefonía por paquete y los identificadores de atestación compatibles; la redacción los sustituye por valores vacíos. Sin keybox activa, la cadena de atestación no cambia. Los paquetes con UID compartido reciben una política coherente. Estos controles no pretenden bloquear sensores, portapapeles, ubicación, detección de VPN ni comprobaciones arbitrarias del proceso de una app.", "Wendet pro Paket eine Identitätsrichtlinie an und bewahrt dabei Androids ursprüngliches Berechtigungsergebnis. Bei aktiver verifizierter Keybox erzeugt die Isolation stabile paketbezogene Telefonie- und unterstützte Attestierungskennungen; Redaction ersetzt dieselben Kennungen durch leere Werte. Ohne aktive Keybox bleibt die Attestierungskette unverändert. Pakete mit gemeinsamer UID erhalten eine einheitliche Richtlinie. Diese Steuerung beansprucht nicht, Sensoren, Zwischenablage, Standort, VPN-Erkennung oder beliebige Prüfungen im App-Prozess zu blockieren.", "Применяет политику идентичности для каждого пакета, сохраняя исходный результат разрешений Android. При активном проверенном keybox изоляция создаёт стабильные телефонные и поддерживаемые аттестационные идентификаторы для пакета, а редактирование заменяет их пустыми значениями. Без активного keybox цепочка аттестации не меняется. Пакеты с общим UID получают единую политику. Эти настройки не заявляют блокировку датчиков, буфера обмена, местоположения, обнаружения VPN или произвольных проверок внутри процесса приложения.", "Terapkan kebijakan identitas per paket sambil mempertahankan hasil izin asli Android. Saat keybox terverifikasi aktif, isolasi membuat pengenal telepon khusus paket yang stabil dan pengenal atestasi yang didukung; redaksi menggantinya dengan nilai kosong. Tanpa keybox aktif, rantai atestasi tidak berubah. Paket dengan UID bersama menerima satu kebijakan yang konsisten. Kontrol ini tidak mengklaim memblokir sensor, clipboard, lokasi, deteksi VPN, atau pemeriksaan arbitrer di proses aplikasi.", "Android के मूल permission परिणाम को बनाए रखते हुए हर package पर पहचान नीति लागू करें। सत्यापित keybox सक्रिय होने पर isolation स्थिर package-विशिष्ट टेलीफ़ोनी और समर्थित attestation पहचानकर्ता बनाता है; redaction उन्हीं पहचानकर्ताओं को खाली मानों से बदलता है। सक्रिय keybox के बिना attestation chain नहीं बदलती। Shared-UID packages को एक समान नीति मिलती है। ये नियंत्रण sensors, clipboard, location, VPN detection या app-process की मनमानी जाँच को रोकने का दावा नहीं करते।", "طبّق سياسة هوية لكل حزمة مع الحفاظ على نتيجة أذونات Android الأصلية. عند وجود keybox موثّق ونشط، ينشئ العزل معرّفات هاتف وتصديق مدعومة وثابتة خاصة بالحزمة؛ ويستبدل الحجب المعرّفات نفسها بقيم فارغة. من دون keybox نشط تبقى سلسلة التصديق كما هي. تحصل حزم UID المشترك على سياسة واحدة متسقة. لا تدّعي هذه الخيارات حجب المستشعرات أو الحافظة أو الموقع أو كشف VPN أو فحوصات عملية التطبيق المختلفة."],
        ["Type to search packages...", "输入以搜索包…", "Escribe para buscar paquetes...", "Zum Suchen von Paketen tippen…", "Введите текст для поиска пакетов…", "Ketik untuk mencari paket...", "पैकेज खोजने के लिए टाइप करें...", "اكتب للبحث عن الحزم..."],
        ["No identity override", "不替换身份", "Sin sustitución de identidad", "Keine Identitätsüberschreibung", "Без переопределения идентичности", "Tanpa override identitas", "कोई पहचान override नहीं", "من دون تجاوز للهوية"],
        ["Use global identity", "使用全局身份", "Usar identidad global", "Globale Identität verwenden", "Использовать глобальную идентичность", "Gunakan identitas global", "ग्लोबल पहचान उपयोग करें", "استخدام الهوية العامة"],
        ["Stable isolated identity", "稳定的隔离身份", "Identidad aislada estable", "Stabile isolierte Identität", "Стабильная изолированная идентичность", "Identitas terisolasi stabil", "स्थिर पृथक पहचान", "هوية معزولة ثابتة"],
        ["Blank supported identifiers", "清空受支持的标识符", "Vaciar identificadores compatibles", "Unterstützte Kennungen leeren", "Очистить поддерживаемые идентификаторы", "Kosongkan pengenal yang didukung", "समर्थित पहचानकर्ता खाली करें", "إفراغ المعرّفات المدعومة"],
        ["Package", "包", "Paquete", "Paket", "Пакет", "Paket", "पैकेज", "الحزمة"],
        ["Profile", "配置档案", "Perfil", "Profil", "Профиль", "Profil", "प्रोफ़ाइल", "الملف الشخصي"],
        ["Keybox", "密钥盒", "Keybox", "Keybox", "Keybox", "Keybox", "Keybox", "Keybox"],
        ["Privacy", "隐私", "Privacidad", "Datenschutz", "Приватность", "Privasi", "गोपनीयता", "الخصوصية"],
        ["Save Configuration", "保存配置", "Guardar configuración", "Konfiguration speichern", "Сохранить конфигурацию", "Simpan Konfigurasi", "कॉन्फ़िगरेशन सहेजें", "حفظ الإعداد"],
        ["Filter active rules by package name...", "按包名筛选活动规则…", "Filtrar reglas activas por nombre de paquete...", "Aktive Regeln nach Paketname filtern…", "Фильтр активных правил по имени пакета…", "Filter aturan aktif berdasarkan nama paket...", "पैकेज नाम से सक्रिय नियम फ़िल्टर करें...", "تصفية القواعد النشطة حسب اسم الحزمة..."],
        ["Filter rules", "筛选规则", "Filtrar reglas", "Regeln filtern", "Фильтр правил", "Filter aturan", "नियम फ़िल्टर करें", "تصفية القواعد"],
        ["Clear filter", "清除筛选", "Borrar filtro", "Filter löschen", "Очистить фильтр", "Hapus filter", "फ़िल्टर साफ़ करें", "مسح التصفية"],
        ["Encrypted Keyboxes Detected", "检测到加密密钥盒", "Keyboxes cifradas detectadas", "Verschlüsselte Keyboxen erkannt", "Обнаружены зашифрованные keybox", "Keybox Terenkripsi Terdeteksi", "एन्क्रिप्टेड Keybox मिले", "تم اكتشاف Keybox مشفّرة"],
        ["Remote Servers", "远程服务器", "Servidores remotos", "Remote-Server", "Удалённые серверы", "Server Jarak Jauh", "रिमोट सर्वर", "الخوادم البعيدة"],
        ["+ Add Server", "+ 添加服务器", "+ Añadir servidor", "+ Server hinzufügen", "+ Добавить сервер", "+ Tambah Server", "+ सर्वर जोड़ें", "+ إضافة خادم"],
        ["Name", "名称", "Nombre", "Name", "Имя", "Nama", "नाम", "الاسم"],
        ["URL (HTTPS)", "URL（HTTPS）", "URL (HTTPS)", "URL (HTTPS)", "URL (HTTPS)", "URL (HTTPS)", "URL (HTTPS)", "URL (HTTPS)"],
        ["No Auth", "无认证", "Sin autenticación", "Keine Authentifizierung", "Без аутентификации", "Tanpa Autentikasi", "कोई प्रमाणीकरण नहीं", "من دون مصادقة"],
        ["Bearer Token", "Bearer 令牌", "Token Bearer", "Bearer-Token", "Bearer-токен", "Token Bearer", "Bearer टोकन", "رمز Bearer"],
        ["Basic Auth", "基本认证", "Autenticación básica", "Basisauthentifizierung", "Базовая аутентификация", "Autentikasi Dasar", "बेसिक प्रमाणीकरण", "المصادقة الأساسية"],
        ["API Key", "API 密钥", "Clave API", "API-Schlüssel", "API-ключ", "Kunci API", "API कुंजी", "مفتاح API"],
        ["Username", "用户名", "Usuario", "Benutzername", "Имя пользователя", "Nama pengguna", "उपयोगकर्ता नाम", "اسم المستخدم"],
        ["Password", "密码", "Contraseña", "Passwort", "Пароль", "Kata sandi", "पासवर्ड", "كلمة المرور"],
        ["Header Name (e.g. X-API-Key)", "请求头名称（例如 X-API-Key）", "Nombre de cabecera (p. ej. X-API-Key)", "Header-Name (z. B. X-API-Key)", "Имя заголовка (например X-API-Key)", "Nama header (mis. X-API-Key)", "हेडर नाम (जैसे X-API-Key)", "اسم الترويسة (مثل X-API-Key)"],
        ["Priority", "优先级", "Prioridad", "Priorität", "Приоритет", "Prioritas", "प्राथमिकता", "الأولوية"],
        ["Refresh interval (hours)", "刷新间隔（小时）", "Intervalo de actualización (horas)", "Aktualisierungsintervall (Stunden)", "Интервал обновления (часы)", "Interval penyegaran (jam)", "रीफ़्रेश अंतराल (घंटे)", "فاصل التحديث (ساعات)"],
        ["Automatic refresh", "自动刷新", "Actualización automática", "Automatische Aktualisierung", "Автоматическое обновление", "Penyegaran otomatis", "स्वचालित रीफ़्रेश", "تحديث تلقائي"],
        ["CBOX content password (optional)", "CBOX 内容密码（可选）", "Contraseña del contenido CBOX (opcional)", "CBOX-Inhaltspasswort (optional)", "Пароль содержимого CBOX (необязательно)", "Kata sandi konten CBOX (opsional)", "CBOX सामग्री पासवर्ड (वैकल्पिक)", "كلمة مرور محتوى CBOX (اختياري)"],
        ["CBOX signature public key (optional)", "CBOX 签名公钥（可选）", "Clave pública de firma CBOX (opcional)", "Öffentlicher CBOX-Signaturschlüssel (optional)", "Открытый ключ подписи CBOX (необязательно)", "Kunci publik tanda tangan CBOX (opsional)", "CBOX हस्ताक्षर सार्वजनिक कुंजी (वैकल्पिक)", "المفتاح العام لتوقيع CBOX (اختياري)"],
        ["Save Server", "保存服务器", "Guardar servidor", "Server speichern", "Сохранить сервер", "Simpan Server", "सर्वर सहेजें", "حفظ الخادم"],
        ["Cancel", "取消", "Cancelar", "Abbrechen", "Отмена", "Batal", "रद्द करें", "إلغاء"],
        ["Upload Keybox / CBOX", "上传密钥盒 / CBOX", "Subir Keybox / CBOX", "Keybox / CBOX hochladen", "Загрузить Keybox / CBOX", "Unggah Keybox / CBOX", "Keybox / CBOX अपलोड करें", "رفع Keybox / CBOX"],
        ["Keybox File", "密钥盒文件", "Archivo Keybox", "Keybox-Datei", "Файл Keybox", "File Keybox", "Keybox फ़ाइल", "ملف Keybox"],
        ["Upload Keybox File", "上传密钥盒文件", "Subir archivo Keybox", "Keybox-Datei hochladen", "Загрузить файл Keybox", "Unggah File Keybox", "Keybox फ़ाइल अपलोड करें", "رفع ملف Keybox"],
        ["[ Drag & Drop ]", "[ 拖放 ]", "[ Arrastrar y soltar ]", "[ Ziehen und ablegen ]", "[ Перетащите файл ]", "[ Seret & Lepas ]", "[ खींचें और छोड़ें ]", "[ سحب وإفلات ]"],
        ["Or click to select .xml or .cbox", "或点击选择 .xml 或 .cbox", "O haz clic para elegir .xml o .cbox", "Oder klicken, um .xml oder .cbox auszuwählen", "Или нажмите, чтобы выбрать .xml или .cbox", "Atau klik untuk memilih .xml atau .cbox", "या .xml अथवा .cbox चुनने के लिए क्लिक करें", "أو انقر لاختيار .xml أو .cbox"],
        ["Manual Paste (XML)", "手动粘贴（XML）", "Pegado manual (XML)", "Manuell einfügen (XML)", "Вставить вручную (XML)", "Tempel Manual (XML)", "मैन्युअल पेस्ट (XML)", "لصق يدوي (XML)"],
        ["Paste Keybox XML Content Here", "在此粘贴密钥盒 XML 内容", "Pega aquí el contenido XML de la Keybox", "Keybox-XML-Inhalt hier einfügen", "Вставьте сюда XML-содержимое Keybox", "Tempel Konten XML Keybox di Sini", "Keybox XML सामग्री यहाँ पेस्ट करें", "الصق محتوى XML الخاص بـ Keybox هنا"],
        ["Keybox XML Content", "密钥盒 XML 内容", "Contenido XML de la Keybox", "Keybox-XML-Inhalt", "XML-содержимое Keybox", "Konten XML Keybox", "Keybox XML सामग्री", "محتوى XML الخاص بـ Keybox"],
        ["Save Pasted XML", "保存粘贴的 XML", "Guardar XML pegado", "Eingefügtes XML speichern", "Сохранить вставленный XML", "Simpan XML yang Ditempel", "पेस्ट किया XML सहेजें", "حفظ XML الملصق"],
        ["Stored Keyboxes", "已存储的密钥盒", "Keyboxes guardadas", "Gespeicherte Keyboxen", "Сохранённые keybox", "Keybox Tersimpan", "सहेजे गए Keybox", "Keybox المحفوظة"],
        ["Filter keyboxes by name...", "按名称筛选密钥盒…", "Filtrar keyboxes por nombre...", "Keyboxen nach Name filtern…", "Фильтр keybox по имени…", "Filter keybox berdasarkan nama...", "नाम से keybox फ़िल्टर करें...", "تصفية Keybox حسب الاسم..."],
        ["Filter keyboxes", "筛选密钥盒", "Filtrar keyboxes", "Keyboxen filtern", "Фильтр keybox", "Filter keybox", "Keybox फ़िल्टर करें", "تصفية Keybox"],
        ["Verification", "验证", "Verificación", "Überprüfung", "Проверка", "Verifikasi", "सत्यापन", "التحقق"],
        ["Check All", "全部检查", "Comprobar todo", "Alle prüfen", "Проверить всё", "Periksa Semua", "सभी जाँचें", "فحص الكل"],
        ["Checking module state...", "正在检查模块状态…", "Comprobando el estado del módulo...", "Modulstatus wird geprüft…", "Проверка состояния модуля…", "Memeriksa status modul...", "मॉड्यूल स्थिति जाँची जा रही है...", "جار فحص حالة الوحدة..."],
        ["CHECKING", "检查中", "COMPROBANDO", "PRÜFUNG", "ПРОВЕРКА", "MEMERIKSA", "जाँच जारी", "جار الفحص"],
        ["Loading resource usage...", "正在加载资源使用情况…", "Cargando uso de recursos...", "Ressourcennutzung wird geladen…", "Загрузка данных об использовании ресурсов…", "Memuat penggunaan sumber daya...", "संसाधन उपयोग लोड हो रहा है...", "جار تحميل استخدام الموارد..."],
        ["Feature", "功能", "Función", "Funktion", "Функция", "Fitur", "विशेषता", "الميزة"],
        ["Status", "状态", "Estado", "Status", "Состояние", "Status", "स्थिति", "الحالة"],
        ["Runtime path", "运行路径", "Ruta de ejecución", "Laufzeitpfad", "Путь выполнения", "Jalur runtime", "रनटाइम पथ", "مسار وقت التشغيل"],
        ["Scope", "范围", "Ámbito", "Geltungsbereich", "Область", "Cakupan", "दायरा", "النطاق"],
        ["Measured daemon CPU and resident memory are shown above. Runtime rows describe configuration and execution scope. Hardware bootloader and root-of-trust warnings can remain visible because this page reports module state, not a physically relocked device.", "上方显示守护进程实测 CPU 和驻留内存。运行时行描述配置和执行范围。此页面报告的是模块状态而非物理重新锁定的设备，因此硬件 Bootloader 和信任根警告可能仍会显示。", "Arriba se muestran la CPU medida del daemon y su memoria residente. Las filas de ejecución describen la configuración y su alcance. Las advertencias del bootloader físico y de la raíz de confianza pueden seguir visibles porque esta página informa del estado del módulo, no de un dispositivo físicamente vuelto a bloquear.", "Oben werden die gemessene Daemon-CPU und der residente Speicher angezeigt. Die Laufzeitzeilen beschreiben Konfiguration und Ausführungsbereich. Warnungen zu Hardware-Bootloader und Root of Trust können sichtbar bleiben, da diese Seite den Modulstatus und kein physisch wieder gesperrtes Gerät meldet.", "Выше показаны измеренная загрузка CPU демона и резидентная память. Строки среды описывают конфигурацию и область выполнения. Предупреждения об аппаратном загрузчике и корне доверия могут оставаться видимыми, поскольку страница сообщает о состоянии модуля, а не о физически повторно заблокированном устройстве.", "CPU daemon yang terukur dan memori residen ditampilkan di atas. Baris runtime menjelaskan konfigurasi dan cakupan eksekusi. Peringatan bootloader perangkat keras dan root-of-trust dapat tetap terlihat karena halaman ini melaporkan status modul, bukan perangkat yang dikunci ulang secara fisik.", "मापी गई daemon CPU और resident memory ऊपर दिखाई गई है। रनटाइम पंक्तियाँ कॉन्फ़िगरेशन और निष्पादन दायरा बताती हैं। हार्डवेयर bootloader और root-of-trust चेतावनियाँ दिख सकती हैं क्योंकि यह पेज मॉड्यूल स्थिति बताता है, भौतिक रूप से दोबारा लॉक किया डिवाइस नहीं।", "يظهر أعلاه استهلاك CPU المقاس للعملية وذاكرتها المقيمة. تصف صفوف وقت التشغيل الإعداد ونطاق التنفيذ. قد تبقى تحذيرات bootloader المادي وجذر الثقة ظاهرة لأن هذه الصفحة تعرض حالة الوحدة، لا جهازا أعيد قفله فعليا."],
        ["Environment", "环境", "Entorno", "Umgebung", "Среда", "Lingkungan", "परिवेश", "البيئة"],
        ["Process CPU", "进程 CPU", "CPU del proceso", "Prozess-CPU", "CPU процесса", "CPU Proses", "प्रोसेस CPU", "CPU للعملية"],
        ["Process RSS", "进程 RSS", "RSS del proceso", "Prozess-RSS", "RSS процесса", "RSS Proses", "प्रोसेस RSS", "RSS للعملية"],
        ["READY", "就绪", "LISTO", "BEREIT", "ГОТОВО", "SIAP", "तैयार", "جاهز"],
        ["REGISTERING", "注册中", "REGISTRANDO", "REGISTRIERUNG", "РЕГИСТРАЦИЯ", "MENDAFTARKAN", "पंजीकरण जारी", "جار التسجيل"],
        ["STARTING", "启动中", "INICIANDO", "STARTET", "ЗАПУСК", "MEMULAI", "शुरू हो रहा है", "جار البدء"],
        ["NATIVE FAILED", "原生层失败", "FALLO NATIVO", "NATIVE FEHLGESCHLAGEN", "СБОЙ NATIVE", "NATIVE GAGAL", "NATIVE विफल", "فشل NATIVE"],
        ["NATIVE OFFLINE", "原生层离线", "NATIVO DESCONECTADO", "NATIVE NICHT VERFÜGBAR", "NATIVE НЕДОСТУПЕН", "NATIVE TIDAK TERHUBUNG", "NATIVE ऑफ़लाइन", "NATIVE غير متصل"],
        ["NO KEYS", "无密钥", "SIN CLAVES", "KEINE SCHLÜSSEL", "НЕТ КЛЮЧЕЙ", "TANPA KUNCI", "कोई कुंजी नहीं", "لا توجد مفاتيح"],
        ["UNAVAILABLE", "不可用", "NO DISPONIBLE", "NICHT VERFÜGBAR", "НЕДОСТУПНО", "TIDAK TERSEDIA", "अनुपलब्ध", "غير متاح"],
        ["The last native activation attempt failed before the Keystore interceptor became operational.", "上一次原生层激活在 Keystore 拦截器开始工作前失败。", "El último intento de activación nativa falló antes de que el interceptor de Keystore estuviera operativo.", "Der letzte native Aktivierungsversuch schlug fehl, bevor der Keystore-Interceptor betriebsbereit war.", "Последняя попытка активации native завершилась ошибкой до запуска перехватчика Keystore.", "Upaya aktivasi native terakhir gagal sebelum interceptor Keystore beroperasi.", "पिछला native activation प्रयास Keystore interceptor के चालू होने से पहले विफल हुआ।", "فشلت آخر محاولة لتفعيل native قبل أن يصبح معترض Keystore جاهزا للعمل."],
        ["A native target accepted activation, but the Keystore Binder interceptor is not registered yet.", "原生目标已接受激活，但 Keystore Binder 拦截器尚未注册。", "Un objetivo nativo aceptó la activación, pero el interceptor Binder de Keystore aún no está registrado.", "Ein natives Ziel hat die Aktivierung angenommen, der Keystore-Binder-Interceptor ist jedoch noch nicht registriert.", "Целевой native-процесс принял активацию, но Binder-перехватчик Keystore ещё не зарегистрирован.", "Target native menerima aktivasi, tetapi interceptor Binder Keystore belum terdaftar.", "Native लक्ष्य ने activation स्वीकार कर लिया है, लेकिन Keystore Binder interceptor अभी पंजीकृत नहीं है।", "قبل هدف native التفعيل، لكن معترض Binder الخاص بـ Keystore لم يسجل بعد."],
        ["Native activation is in progress and the Keystore interceptor is not registered yet.", "原生层正在激活，Keystore 拦截器尚未注册。", "La activación nativa está en curso y el interceptor de Keystore aún no está registrado.", "Die native Aktivierung läuft; der Keystore-Interceptor ist noch nicht registriert.", "Активация native выполняется, перехватчик Keystore ещё не зарегистрирован.", "Aktivasi native sedang berlangsung dan interceptor Keystore belum terdaftar.", "Native activation जारी है और Keystore interceptor अभी पंजीकृत नहीं है।", "تفعيل native جار، ولم يسجل معترض Keystore بعد."],
        ["No operational Keystore interceptor is registered and no matching live native activation is available.", "没有已注册且可工作的 Keystore 拦截器，也没有匹配的实时原生层激活。", "No hay ningún interceptor de Keystore operativo registrado ni una activación nativa activa que coincida.", "Es ist weder ein betriebsbereiter Keystore-Interceptor registriert noch eine passende aktive native Aktivierung verfügbar.", "Рабочий перехватчик Keystore не зарегистрирован, подходящей активной native-активации нет.", "Tidak ada interceptor Keystore operasional yang terdaftar dan tidak ada aktivasi native aktif yang cocok.", "कोई कार्यरत Keystore interceptor पंजीकृत नहीं है और कोई मेल खाता सक्रिय native activation उपलब्ध नहीं है।", "لا يوجد معترض Keystore عامل ومسجل، ولا يتوفر تفعيل native حي مطابق."],
        ["The native runtime is active, but no verified keybox is currently active.", "原生运行时已激活，但当前没有已验证的活动密钥盒。", "El runtime nativo está activo, pero no hay ninguna keybox verificada activa.", "Die native Laufzeit ist aktiv, derzeit ist jedoch keine verifizierte Keybox aktiv.", "Среда native активна, но сейчас нет активного проверенного keybox.", "Runtime native aktif, tetapi saat ini tidak ada keybox terverifikasi yang aktif.", "Native runtime सक्रिय है, लेकिन अभी कोई सत्यापित keybox सक्रिय नहीं है।", "وقت تشغيل native نشط، لكن لا يوجد حاليا keybox موثّق ونشط."],
        ["Core boot/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine.", "核心启动/TEE 兼容性独立于身份引擎保持启用；硬件 Bootloader 和信任根状态仍为真实状态。", "La compatibilidad principal de arranque/TEE permanece activa independientemente del Motor de identidad; el bootloader físico y el estado de la raíz de confianza siguen siendo reales.", "Die zentrale Boot-/TEE-Kompatibilität bleibt unabhängig von der Identitäts-Engine aktiv; Hardware-Bootloader und Root-of-Trust-Status bleiben echt.", "Базовая совместимость boot/TEE работает независимо от движка идентичности; аппаратный загрузчик и состояние корня доверия остаются подлинными.", "Kompatibilitas inti boot/TEE tetap aktif secara independen dari Mesin Identitas; bootloader perangkat keras dan status root-of-trust tetap asli.", "मुख्य boot/TEE संगतता Identity Engine से स्वतंत्र रूप से सक्रिय रहती है; हार्डवेयर bootloader और root-of-trust की स्थिति वास्तविक रहती है।", "يبقى توافق boot/TEE الأساسي نشطا بشكل مستقل عن محرك الهوية؛ وتبقى حالة bootloader المادي وجذر الثقة حقيقية."],
        ["Runtime resource data could not be read. Open Logs and check the first CleveresTricky error.", "无法读取运行时资源数据。请打开日志并查看第一条 CleveresTricky 错误。", "No se pudieron leer los datos de recursos del runtime. Abre Registros y revisa el primer error de CleveresTricky.", "Die Laufzeit-Ressourcendaten konnten nicht gelesen werden. Öffne die Protokolle und prüfe den ersten CleveresTricky-Fehler.", "Не удалось прочитать данные ресурсов среды. Откройте логи и проверьте первую ошибку CleveresTricky.", "Data sumber daya runtime tidak dapat dibaca. Buka Log dan periksa kesalahan CleveresTricky pertama.", "रनटाइम संसाधन डेटा पढ़ा नहीं जा सका। लॉग खोलें और पहली CleveresTricky त्रुटि देखें।", "تعذر قراءة بيانات موارد وقت التشغيل. افتح السجلات وتحقق من أول خطأ لـ CleveresTricky."],
        ["Resource data unavailable.", "资源数据不可用。", "Datos de recursos no disponibles.", "Ressourcendaten nicht verfügbar.", "Данные ресурсов недоступны.", "Data sumber daya tidak tersedia.", "संसाधन डेटा उपलब्ध नहीं है।", "بيانات الموارد غير متاحة."],
        ["Resource monitor unavailable. Check module logs.", "资源监视器不可用。请检查模块日志。", "El monitor de recursos no está disponible. Revisa los registros del módulo.", "Ressourcenmonitor nicht verfügbar. Prüfe die Modulprotokolle.", "Монитор ресурсов недоступен. Проверьте логи модуля.", "Monitor sumber daya tidak tersedia. Periksa log modul.", "संसाधन मॉनिटर उपलब्ध नहीं है। मॉड्यूल लॉग देखें।", "مراقب الموارد غير متاح. تحقق من سجلات الوحدة."],
        ["Keystore Runtime", "Keystore 运行时", "Runtime de Keystore", "Keystore-Laufzeit", "Среда Keystore", "Runtime Keystore", "Keystore रनटाइम", "وقت تشغيل Keystore"],
        ["Telephony Runtime", "电话运行时", "Runtime de telefonía", "Telefonie-Laufzeit", "Среда телефонии", "Runtime Telepon", "टेलीफ़ोनी रनटाइम", "وقت تشغيل الهاتف"],
        ["Automatic Keybox Check", "自动密钥盒检查", "Comprobación automática de Keybox", "Automatische Keybox-Prüfung", "Автоматическая проверка keybox", "Pemeriksaan Keybox Otomatis", "स्वचालित Keybox जाँच", "فحص Keybox تلقائيا"],
        ["Identity Refresh on Boot", "开机刷新身份", "Actualizar identidad al arrancar", "Identität beim Start aktualisieren", "Обновление идентичности при загрузке", "Segarkan Identitas saat Boot", "बूट पर पहचान रीफ़्रेश", "تحديث الهوية عند الإقلاع"],
        ["Telephony Interception", "电话拦截", "Intercepción de telefonía", "Telefonie-Interception", "Перехват телефонии", "Intersepsi Telepon", "टेलीफ़ोनी इंटरसेप्शन", "اعتراض الهاتف"],
        ["RKP Protection", "RKP 保护", "Protección RKP", "RKP-Schutz", "Защита RKP", "Perlindungan RKP", "RKP सुरक्षा", "حماية RKP"],
        ["DRM App Passthrough", "DRM 应用直通", "Paso directo para apps DRM", "DRM-App-Durchleitung", "Прямой путь DRM-приложений", "Passthrough Aplikasi DRM", "DRM ऐप पासथ्रू", "تمرير تطبيقات DRM"],
        ["Template Build Identity", "模板构建身份", "Identidad de compilación de plantilla", "Vorlagen-Build-Identität", "Идентичность сборки из шаблона", "Identitas Build Templat", "टेम्पलेट Build पहचान", "هوية build للقالب"],
        ["Region Property View", "区域属性视图", "Vista de propiedades regionales", "Ansicht der Regionseigenschaften", "Представление свойств региона", "Tampilan Properti Wilayah", "क्षेत्र प्रॉपर्टी दृश्य", "عرض خصائص المنطقة"],
        ["Keybox Storage", "密钥盒存储", "Almacenamiento de Keybox", "Keybox-Speicher", "Хранилище keybox", "Penyimpanan Keybox", "Keybox संग्रहण", "تخزين Keybox"],
        ["App Rules", "应用规则", "Reglas de apps", "App-Regeln", "Правила приложений", "Aturan Aplikasi", "ऐप नियम", "قواعد التطبيقات"],
        ["Enabled", "已启用", "Activado", "Aktiviert", "Включено", "Diaktifkan", "सक्षम", "مفعّل"],
        ["Disabled", "已禁用", "Desactivado", "Deaktiviert", "Отключено", "Dinonaktifkan", "अक्षम", "معطّل"],
        ["Always on", "始终开启", "Siempre activo", "Immer aktiv", "Всегда включено", "Selalu aktif", "हमेशा चालू", "مفعّل دائما"],
        ["Info Only", "仅供参考", "Solo información", "Nur Information", "Только информация", "Hanya Informasi", "केवल जानकारी", "للمعلومات فقط"],
        ["View recent logs from the module. You can also download them for sharing.", "查看模块的最新日志，也可以下载后分享。", "Consulta los registros recientes del módulo. También puedes descargarlos para compartirlos.", "Zeige aktuelle Modulprotokolle an. Du kannst sie auch zum Teilen herunterladen.", "Просмотрите последние логи модуля. Их также можно скачать для передачи.", "Lihat log terbaru dari modul. Anda juga dapat mengunduhnya untuk dibagikan.", "मॉड्यूल के हाल के लॉग देखें। साझा करने के लिए उन्हें डाउनलोड भी कर सकते हैं।", "اعرض السجلات الحديثة للوحدة. ويمكنك تنزيلها لمشاركتها."],
        ["Select Log Type", "选择日志类型", "Seleccionar tipo de registro", "Protokolltyp auswählen", "Выберите тип логов", "Pilih Jenis Log", "लॉग प्रकार चुनें", "اختر نوع السجل"],
        ["CleveresTricky Logs", "CleveresTricky 日志", "Registros de CleveresTricky", "CleveresTricky-Protokolle", "Логи CleveresTricky", "Log CleveresTricky", "CleveresTricky लॉग", "سجلات CleveresTricky"],
        ["Errors Only", "仅错误", "Solo errores", "Nur Fehler", "Только ошибки", "Hanya Kesalahan", "केवल त्रुटियाँ", "الأخطاء فقط"],
        ["Full System (Recent)", "完整系统（最近）", "Sistema completo (reciente)", "Gesamtes System (aktuell)", "Вся система (последние)", "Sistem Lengkap (Terbaru)", "पूरा सिस्टम (हाल का)", "النظام الكامل (الحديث)"],
        ["Logs Copied", "日志已复制", "Registros copiados", "Protokolle kopiert", "Логи скопированы", "Log Disalin", "लॉग कॉपी हुए", "تم نسخ السجلات"],
        ["Select file to edit", "选择要编辑的文件", "Selecciona un archivo para editar", "Datei zum Bearbeiten auswählen", "Выберите файл для редактирования", "Pilih file untuk diedit", "संपादित करने के लिए फ़ाइल चुनें", "اختر ملفا لتحريره"],
        ["Revert Changes", "撤销更改", "Revertir cambios", "Änderungen zurücksetzen", "Отменить изменения", "Batalkan Perubahan", "परिवर्तन वापस लें", "التراجع عن التغييرات"],
        ["Revert", "撤销", "Revertir", "Zurücksetzen", "Отменить", "Batalkan", "वापस लें", "تراجع"],
        ["Save", "保存", "Guardar", "Speichern", "Сохранить", "Simpan", "सहेजें", "حفظ"],
        ["File Content", "文件内容", "Contenido del archivo", "Dateiinhalt", "Содержимое файла", "Isi File", "फ़ाइल सामग्री", "محتوى الملف"],
        ["Support the Development", "支持开发", "Apoya el desarrollo", "Entwicklung unterstützen", "Поддержать разработку", "Dukung Pengembangan", "विकास में सहयोग करें", "ادعم التطوير"],
        ["If you find this project helpful, consider supporting the development. Your contributions help maintain the project and develop new features.", "如果这个项目对你有帮助，请考虑支持开发。你的贡献有助于维护项目并开发新功能。", "Si este proyecto te resulta útil, considera apoyar su desarrollo. Tus aportaciones ayudan a mantenerlo y a crear nuevas funciones.", "Wenn dieses Projekt hilfreich ist, erwäge die Entwicklung zu unterstützen. Deine Beiträge helfen bei der Pflege und Entwicklung neuer Funktionen.", "Если проект вам полезен, поддержите его разработку. Ваш вклад помогает сопровождать проект и создавать новые функции.", "Jika proyek ini bermanfaat, pertimbangkan untuk mendukung pengembangannya. Kontribusi Anda membantu pemeliharaan proyek dan pengembangan fitur baru.", "यदि यह प्रोजेक्ट उपयोगी है तो विकास में सहयोग करने पर विचार करें। आपका योगदान प्रोजेक्ट के रखरखाव और नई सुविधाओं के विकास में मदद करता है।", "إذا كان هذا المشروع مفيدا لك، ففكر في دعم تطويره. تساعد مساهماتك في صيانة المشروع وتطوير ميزات جديدة."],
        ["Crypto Addresses", "加密货币地址", "Direcciones de criptomonedas", "Krypto-Adressen", "Криптоадреса", "Alamat Kripto", "क्रिप्टो पते", "عناوين العملات الرقمية"],
        ["Asset", "资产", "Activo", "Vermögenswert", "Актив", "Aset", "परिसंपत्ति", "الأصل"],
        ["Network", "网络", "Red", "Netzwerk", "Сеть", "Jaringan", "नेटवर्क", "الشبكة"],
        ["Address", "地址", "Dirección", "Adresse", "Адрес", "Alamat", "पता", "العنوان"],
        ["Platforms", "平台", "Plataformas", "Plattformen", "Платформы", "Platform", "प्लेटफ़ॉर्म", "المنصات"],
        ["Binance User ID", "Binance 用户 ID", "ID de usuario de Binance", "Binance-Benutzer-ID", "ID пользователя Binance", "ID Pengguna Binance", "Binance उपयोगकर्ता ID", "معرّف مستخدم Binance"],
        ["Thank you for your support!", "感谢你的支持！", "¡Gracias por tu apoyo!", "Vielen Dank für deine Unterstützung!", "Спасибо за поддержку!", "Terima kasih atas dukungan Anda!", "आपके सहयोग के लिए धन्यवाद!", "شكرا لدعمك!"],
        ["Feature Center", "功能中心", "Centro de funciones", "Funktionszentrale", "Центр функций", "Pusat Fitur", "सुविधा केंद्र", "مركز الميزات"],
        ["Main controls are here. Parent features reveal only the settings that belong to them.", "主要控制项位于此处。父功能只会显示属于它的设置。", "Aquí están los controles principales. Las funciones superiores solo muestran sus ajustes correspondientes.", "Hier befinden sich die Hauptsteuerungen. Übergeordnete Funktionen zeigen nur die zugehörigen Einstellungen.", "Здесь находятся основные элементы управления. Родительские функции показывают только относящиеся к ним настройки.", "Kontrol utama ada di sini. Fitur induk hanya menampilkan pengaturan yang terkait.", "मुख्य नियंत्रण यहाँ हैं। मूल सुविधाएँ केवल उनसे संबंधित सेटिंग दिखाती हैं।", "توجد عناصر التحكم الرئيسية هنا. لا تظهر الميزات الرئيسية إلا الإعدادات التابعة لها."],
        ["What does this do?", "这是做什么的？", "¿Qué hace esto?", "Was bewirkt das?", "Что это делает?", "Apa fungsi ini?", "यह क्या करता है?", "ماذا يفعل هذا؟"],
        ["Global Mode is the module-wide application scope switch.", "全局模式是控制整个模块应用范围的开关。", "El Modo global controla el ámbito de aplicación de todo el módulo.", "Der globale Modus steuert den App-Geltungsbereich des gesamten Moduls.", "Глобальный режим управляет областью приложений для всего модуля.", "Mode Global adalah sakelar cakupan aplikasi untuk seluruh modul.", "ग्लोबल मोड पूरे मॉड्यूल के ऐप दायरे का स्विच है।", "الوضع العام هو مفتاح نطاق التطبيقات على مستوى الوحدة."],
        ["Applies target rules globally when no narrower application rule wins. Fresh installs default to ON.", "当没有更具体的应用规则匹配时，全局应用目标规则。全新安装默认开启。", "Aplica las reglas objetivo globalmente cuando no prevalece una regla de aplicación más específica. En instalaciones nuevas está activado por defecto.", "Wendet Zielregeln global an, wenn keine engere App-Regel greift. Bei Neuinstallationen standardmäßig EIN.", "Применяет целевые правила глобально, если не сработало более узкое правило приложения. В новой установке включено по умолчанию.", "Menerapkan aturan target secara global saat tidak ada aturan aplikasi yang lebih spesifik. Instalasi baru default AKTIF.", "जब कोई अधिक विशिष्ट ऐप नियम लागू न हो तो लक्ष्य नियम ग्लोबल रूप से लागू करता है। नई स्थापना में यह डिफ़ॉल्ट रूप से चालू है।", "يطبّق القواعد المستهدفة عاما عندما لا تنطبق قاعدة تطبيق أكثر تحديدا. يكون مفعّلا افتراضيا في التثبيت الجديد."],
        ["Optional identity substitution. Turn it on first, then choose only the child identity paths you want.", "可选的身份替换。请先开启，再只选择需要的子身份路径。", "Sustitución de identidad opcional. Actívala primero y elige solo las rutas de identidad secundarias que necesites.", "Optionale Identitätsersetzung. Zuerst aktivieren und dann nur die gewünschten untergeordneten Identitätspfade auswählen.", "Необязательная подмена идентичности. Сначала включите её, затем выберите только нужные дочерние пути.", "Penggantian identitas opsional. Aktifkan terlebih dahulu, lalu pilih hanya jalur identitas turunan yang diperlukan.", "वैकल्पिक पहचान प्रतिस्थापन। पहले इसे चालू करें, फिर केवल आवश्यक उप-पथ चुनें।", "استبدال اختياري للهوية. فعّله أولا، ثم اختر فقط مسارات الهوية الفرعية المطلوبة."],
        ["Identity is optional. Core Keystore/TEE protection is independent from this switch.", "身份功能是可选的。核心 Keystore/TEE 保护不依赖此开关。", "La identidad es opcional. La protección principal Keystore/TEE es independiente de este interruptor.", "Identität ist optional. Der zentrale Keystore-/TEE-Schutz arbeitet unabhängig von diesem Schalter.", "Идентичность необязательна. Базовая защита Keystore/TEE не зависит от этого переключателя.", "Identitas bersifat opsional. Perlindungan inti Keystore/TEE tidak bergantung pada sakelar ini.", "पहचान वैकल्पिक है। मुख्य Keystore/TEE सुरक्षा इस स्विच से स्वतंत्र है।", "الهوية اختيارية. حماية Keystore/TEE الأساسية مستقلة عن هذا المفتاح."],
        ["Build identity", "构建身份", "Identidad de compilación", "Build-Identität", "Идентичность сборки", "Identitas build", "Build पहचान", "هوية build"],
        ["Attestation identity", "认证身份", "Identidad de atestación", "Attestierungsidentität", "Идентичность аттестации", "Identitas atestasi", "Attestation पहचान", "هوية التصديق"],
        ["Telephony identity", "电话身份", "Identidad de telefonía", "Telefonie-Identität", "Телефонная идентичность", "Identitas telepon", "टेलीफ़ोनी पहचान", "هوية الهاتف"],
        ["Region identity", "区域身份", "Identidad regional", "Regionsidentität", "Региональная идентичность", "Identitas wilayah", "क्षेत्र पहचान", "هوية المنطقة"],
        ["Identity refresh", "身份刷新", "Actualización de identidad", "Identitätsaktualisierung", "Обновление идентичности", "Penyegaran identitas", "पहचान रीफ़्रेश", "تحديث الهوية"],
        ["Boot fingerprint, model and build fields. Requires a reboot when early-boot properties change.", "启动指纹、型号和构建字段。更改早期启动属性后需要重启。", "Huella de arranque, modelo y campos de compilación. Requiere reiniciar cuando cambian propiedades de arranque temprano.", "Boot-Fingerprint, Modell und Build-Felder. Bei Änderungen früher Boot-Eigenschaften ist ein Neustart erforderlich.", "Отпечаток загрузки, модель и поля сборки. При изменении ранних загрузочных свойств требуется перезагрузка.", "Sidik jari boot, model, dan bidang build. Memerlukan reboot saat properti boot awal berubah.", "बूट फ़िंगरप्रिंट, मॉडल और build फ़ील्ड। शुरुआती बूट प्रॉपर्टी बदलने पर रीबूट आवश्यक है।", "بصمة الإقلاع والطراز وحقول build. تتطلب إعادة التشغيل عند تغيير خصائص الإقلاع المبكر."],
        ["Uses the configured attestation identity only for selected targets; genuine hardware key operations remain on Android.", "仅为选定目标使用已配置的认证身份；真实硬件密钥操作仍由 Android 执行。", "Usa la identidad de atestación configurada solo para objetivos seleccionados; las operaciones reales de claves de hardware permanecen en Android.", "Verwendet die konfigurierte Attestierungsidentität nur für ausgewählte Ziele; echte Hardware-Schlüsseloperationen verbleiben bei Android.", "Использует настроенную идентичность аттестации только для выбранных целей; реальные операции аппаратных ключей остаются в Android.", "Menggunakan identitas atestasi yang dikonfigurasi hanya untuk target terpilih; operasi kunci perangkat keras asli tetap di Android.", "कॉन्फ़िगर की गई attestation पहचान केवल चुने लक्ष्यों के लिए उपयोग होती है; वास्तविक हार्डवेयर कुंजी कार्य Android पर ही रहते हैं।", "يستخدم هوية التصديق المعدّة للأهداف المحددة فقط؛ وتبقى عمليات مفاتيح العتاد الحقيقية على Android."],
        ["Controls optional IMEI/IMSI/ICCID/phone presentation for selected apps.", "控制选定应用可见的可选 IMEI/IMSI/ICCID/电话号码。", "Controla la presentación opcional de IMEI/IMSI/ICCID/teléfono para las apps seleccionadas.", "Steuert die optionale Anzeige von IMEI/IMSI/ICCID/Telefonnummer für ausgewählte Apps.", "Управляет необязательным отображением IMEI/IMSI/ICCID/телефона выбранным приложениям.", "Mengontrol penyajian IMEI/IMSI/ICCID/telepon opsional untuk aplikasi terpilih.", "चुने ऐप्स के लिए वैकल्पिक IMEI/IMSI/ICCID/फ़ोन प्रस्तुति नियंत्रित करता है।", "يتحكم في عرض IMEI/IMSI/ICCID/الهاتف الاختياري للتطبيقات المحددة."],
        ["Controls optional region/hardware-region presentation. Some values require a reboot.", "控制可选的区域/硬件区域显示。部分值需要重启。", "Controla la presentación opcional de región/región de hardware. Algunos valores requieren reiniciar.", "Steuert die optionale Anzeige von Region/Hardware-Region. Einige Werte erfordern einen Neustart.", "Управляет необязательным представлением региона/аппаратного региона. Для некоторых значений нужна перезагрузка.", "Mengontrol penyajian wilayah/wilayah-perangkat-keras opsional. Beberapa nilai memerlukan reboot.", "वैकल्पिक क्षेत्र/हार्डवेयर-क्षेत्र प्रस्तुति नियंत्रित करता है। कुछ मानों के लिए रीबूट आवश्यक है।", "يتحكم في عرض المنطقة/منطقة العتاد الاختياري. تتطلب بعض القيم إعادة التشغيل."],
        ["Prepares a new identity for the next boot only while this option is enabled.", "仅在启用此选项时为下次启动准备新身份。", "Prepara una identidad nueva para el siguiente arranque solo mientras esta opción esté activada.", "Bereitet nur bei aktivierter Option eine neue Identität für den nächsten Start vor.", "Готовит новую идентичность к следующей загрузке только пока этот параметр включён.", "Menyiapkan identitas baru untuk boot berikutnya hanya saat opsi ini aktif.", "यह विकल्प चालू रहने पर ही अगले बूट के लिए नई पहचान तैयार करता है।", "يجهز هوية جديدة للإقلاع التالي فقط أثناء تفعيل هذا الخيار."],
        ["Independent attestation patch policy. Default is off unless stale-ROM policy enables it.", "独立的认证补丁策略。除非旧 ROM 策略启用，否则默认关闭。", "Política independiente de parche de atestación. Está desactivada por defecto salvo que la política de ROM obsoleta la active.", "Unabhängige Attestierungs-Patchrichtlinie. Standardmäßig aus, sofern die Stale-ROM-Richtlinie sie nicht aktiviert.", "Независимая политика патча аттестации. По умолчанию отключена, если её не включает политика устаревшей ROM.", "Kebijakan patch atestasi independen. Default mati kecuali kebijakan ROM usang mengaktifkannya.", "स्वतंत्र attestation patch नीति। stale-ROM नीति चालू न करे तो डिफ़ॉल्ट रूप से बंद।", "سياسة مستقلة لتصحيح التصديق. تكون متوقفة افتراضيا ما لم تفعّلها سياسة ROM القديمة."],
        ["Security Patch is independent from Identity. It controls system, vendor, and boot security patch levels; use Device Default to keep captured values, Automatic for calendar-based policy, or Manual for an explicit date.", "安全补丁独立于身份功能。它控制系统、供应商和启动安全补丁级别；使用设备默认值保留已捕获值，自动模式使用日历策略，手动模式使用明确日期。", "El Parche de seguridad es independiente de Identidad. Controla los niveles de parche de seguridad del sistema, vendor y arranque; usa Predeterminado del dispositivo para conservar valores capturados, Automático para la política del calendario o Manual para una fecha explícita.", "Der Sicherheitspatch ist unabhängig von der Identität. Er steuert die Sicherheitspatch-Stufen von System, Vendor und Boot; Gerätestandard bewahrt erfasste Werte, Automatisch nutzt die Kalenderlogik und Manuell ein festes Datum.", "Security Patch не зависит от Identity. Он управляет уровнями патча безопасности System, Vendor и Boot; Device Default сохраняет полученные значения, Automatic использует календарную политику, а Manual — указанную дату.", "Patch Keamanan terpisah dari Identitas. Fitur ini mengatur tingkat patch keamanan System, Vendor, dan Boot; gunakan Default perangkat untuk mempertahankan nilai yang ditangkap, Otomatis untuk kebijakan kalender, atau Manual untuk tanggal tertentu.", "सुरक्षा पैच पहचान से स्वतंत्र है। यह System, Vendor और Boot के सुरक्षा पैच स्तर नियंत्रित करता है; कैप्चर किए मान रखने के लिए Device Default, कैलेंडर नीति के लिए Automatic या निश्चित तारीख के लिए Manual उपयोग करें।", "تصحيح الأمان مستقل عن الهوية. يتحكم في مستويات تصحيح أمان System وVendor وBoot؛ استخدم Device Default للحفاظ على القيم الملتقطة، وAutomatic لسياسة التقويم، أو Manual لتاريخ صريح."],
        ["Security Patch is independent from Identity.", "安全补丁独立于身份功能。", "El Parche de seguridad es independiente de Identidad.", "Der Sicherheitspatch ist unabhängig von der Identität.", "Патч безопасности не зависит от идентичности.", "Patch Keamanan independen dari Identitas.", "सुरक्षा पैच पहचान से स्वतंत्र है।", "تصحيح الأمان مستقل عن الهوية."],
        ["Auto Security Patch", "自动安全补丁", "Parche de seguridad automático", "Automatischer Sicherheitspatch", "Автоматический патч безопасности", "Patch Keamanan Otomatis", "स्वचालित सुरक्षा पैच", "تصحيح الأمان التلقائي"],
        ["Use automatic mode for stale captured patch values.", "对过期的已捕获补丁值使用自动模式。", "Usa el modo automático para valores de parche capturados que estén obsoletos.", "Für veraltete erfasste Patchwerte den automatischen Modus verwenden.", "Используйте автоматический режим для устаревших сохранённых значений патча.", "Gunakan mode otomatis untuk nilai patch tertangkap yang usang.", "पुराने कैप्चर किए patch मानों के लिए स्वचालित मोड उपयोग करें।", "استخدم الوضع التلقائي لقيم التصحيح الملتقطة القديمة."],
        ["Advanced Security Patch", "高级安全补丁", "Parche de seguridad avanzado", "Erweiterter Sicherheitspatch", "Расширенный патч безопасности", "Patch Keamanan Lanjutan", "उन्नत सुरक्षा पैच", "تصحيح الأمان المتقدم"],
        ["Checks configured keyboxes against the module revocation source when enabled.", "启用后会根据模块撤销源检查已配置的密钥盒。", "Cuando está activado, comprueba las keyboxes configuradas con la fuente de revocación del módulo.", "Prüft konfigurierte Keyboxen bei Aktivierung gegen die Widerrufsquelle des Moduls.", "При включении проверяет настроенные keybox по источнику отзыва модуля.", "Saat aktif, memeriksa keybox yang dikonfigurasi terhadap sumber pencabutan modul.", "चालू होने पर कॉन्फ़िगर keybox को मॉड्यूल के revocation स्रोत से जाँचता है।", "عند التفعيل يفحص Keybox المعدّة مقابل مصدر الإلغاء الخاص بالوحدة."],
        ["Optional network-backed keybox hygiene; manual management remains available.", "可选的联网密钥盒健康检查；仍可手动管理。", "Mantenimiento opcional de keyboxes mediante red; la gestión manual sigue disponible.", "Optionale netzwerkgestützte Keybox-Pflege; manuelle Verwaltung bleibt verfügbar.", "Необязательная сетевая проверка keybox; ручное управление остаётся доступным.", "Pemeliharaan keybox berbasis jaringan bersifat opsional; pengelolaan manual tetap tersedia.", "वैकल्पिक नेटवर्क-आधारित keybox स्वच्छता; मैन्युअल प्रबंधन उपलब्ध रहता है।", "صيانة اختيارية لـ Keybox عبر الشبكة؛ وتبقى الإدارة اليدوية متاحة."],
        ["DRM App Passthrough keeps configured packages on Android's genuine Keystore path. It does not fake a DRM security level; use Profiles > Privacy > Isolate for app-scoped DRM device identifiers.", "DRM 直通会让已配置的包继续使用 Android 的真实 Keystore 路径。它不会伪造 DRM 安全级别；如需应用级 DRM 设备标识符，请使用 配置档案 > 隐私 > 隔离。", "DRM Passthrough mantiene los paquetes configurados en la ruta Keystore genuina de Android. No falsifica el nivel de seguridad DRM; usa Perfiles > Privacidad > Aislar para los identificadores DRM por aplicación.", "DRM-Durchleitung belässt konfigurierte Pakete auf Androids echtem Keystore-Pfad. Sie täuscht keine DRM-Sicherheitsstufe vor; für App-spezifische DRM-Gerätekennungen Profile > Datenschutz > Isolieren verwenden.", "DRM Passthrough оставляет настроенные пакеты на настоящем пути Keystore Android. Он не подделывает уровень безопасности DRM; для идентификаторов DRM, отдельных для приложений, используйте Профили > Приватность > Изолировать.", "DRM Passthrough mempertahankan paket yang dikonfigurasi pada jalur Keystore asli Android. Fitur ini tidak memalsukan tingkat keamanan DRM; gunakan Profil > Privasi > Isolasi untuk pengenal perangkat DRM per aplikasi.", "DRM Passthrough कॉन्फ़िगर किए गए पैकेजों को Android के वास्तविक Keystore पथ पर रखता है। यह DRM सुरक्षा स्तर को नकली नहीं बनाता; ऐप-विशिष्ट DRM device identifier के लिए Profiles > Privacy > Isolate उपयोग करें।", "يبقي DRM Passthrough الحزم المعدة على مسار Keystore الحقيقي في Android. ولا يزيّف مستوى أمان DRM؛ استخدم الملفات الشخصية > الخصوصية > عزل لمعرّفات أجهزة DRM الخاصة بكل تطبيق."],
        ["DRM Identifier Privacy", "DRM 标识符隐私", "Privacidad del identificador DRM", "Datenschutz für DRM-Kennung", "Приватность DRM-идентификатора", "Privasi Pengenal DRM", "DRM पहचानकर्ता गोपनीयता", "خصوصية معرّف DRM"],
        ["Configure Profiles", "配置档案", "Configurar perfiles", "Profile konfigurieren", "Настроить профили", "Konfigurasi Profil", "प्रोफ़ाइल कॉन्फ़िगर करें", "إعداد الملفات الشخصية"],
        ["Keybox / TEE path", "Keybox / TEE 路径", "Ruta Keybox / TEE", "Keybox-/TEE-Pfad", "Путь Keybox / TEE", "Jalur Keybox / TEE", "Keybox / TEE पथ", "مسار Keybox / TEE"],
        ["Keyboxes are selected per profile or from the stored pool. Stored XML/CBOX sources are reloaded without requiring an environment reset.", "密钥盒按配置档案选择，也可从已存储池中选择。已存储的 XML/CBOX 源可重新加载，无需重置环境。", "Las keyboxes se eligen por perfil o desde el conjunto guardado. Las fuentes XML/CBOX guardadas se recargan sin restablecer el entorno.", "Keyboxen werden pro Profil oder aus dem gespeicherten Pool gewählt. Gespeicherte XML-/CBOX-Quellen werden ohne Umgebungsreset neu geladen.", "Keybox выбираются для профиля или из сохранённого пула. Сохранённые источники XML/CBOX перезагружаются без сброса среды.", "Keybox dipilih per profil atau dari kumpulan tersimpan. Sumber XML/CBOX tersimpan dimuat ulang tanpa perlu mereset lingkungan.", "Keybox प्रोफ़ाइल के अनुसार या सहेजे पूल से चुने जाते हैं। सहेजे XML/CBOX स्रोत environment reset के बिना फिर लोड होते हैं।", "يتم اختيار Keybox لكل ملف شخصي أو من المخزون المحفوظ. يعاد تحميل مصادر XML/CBOX المحفوظة دون الحاجة إلى إعادة ضبط البيئة."],
        ["The core Keystore hook remains separate from Identity. Certificate chains are cached to avoid repeated expensive work.", "核心 Keystore Hook 与身份功能保持独立。证书链会被缓存，以避免重复的高开销工作。", "El hook principal de Keystore sigue separado de Identidad. Las cadenas de certificados se almacenan en caché para evitar trabajo costoso repetido.", "Der zentrale Keystore-Hook bleibt von der Identität getrennt. Zertifikatsketten werden zwischengespeichert, um wiederholte teure Arbeit zu vermeiden.", "Базовый hook Keystore остаётся отдельным от идентичности. Цепочки сертификатов кешируются, чтобы не повторять дорогую работу.", "Hook inti Keystore tetap terpisah dari Identitas. Rantai sertifikat di-cache untuk menghindari pekerjaan mahal berulang.", "मुख्य Keystore hook पहचान से अलग रहता है। बार-बार महंगा कार्य रोकने के लिए सर्टिफिकेट chain cache होती हैं।", "يبقى hook الأساسي لـ Keystore منفصلا عن الهوية. تخزن سلاسل الشهادات مؤقتا لتجنب تكرار العمل المكلف."],
        ["Open keyboxes", "打开密钥盒", "Abrir keyboxes", "Keyboxen öffnen", "Открыть keybox", "Buka keybox", "Keybox खोलें", "فتح Keybox"],
        ["Keybox Status", "密钥盒状态", "Estado de Keybox", "Keybox-Status", "Состояние keybox", "Status Keybox", "Keybox स्थिति", "حالة Keybox"],
        ["Loading keybox state...", "正在加载密钥盒状态…", "Cargando estado de keybox...", "Keybox-Status wird geladen…", "Загрузка состояния keybox…", "Memuat status keybox...", "Keybox स्थिति लोड हो रही है...", "جار تحميل حالة Keybox..."],
        ["Use Profiles for app assignments, identity template, custom keybox, DRM identifier privacy, per-feature overrides and per-app Security Patch rules in one place.", "使用配置档案可在一处管理应用分配、身份模板、自定义密钥盒、DRM 标识符隐私、每功能覆盖和每应用安全补丁规则。", "Usa Perfiles para reunir asignaciones de apps, plantilla de identidad, keybox personalizada, privacidad del identificador DRM, sustituciones por función y reglas de Parche de seguridad por app.", "Profile bündeln App-Zuweisungen, Identitätsvorlage, eigene Keybox, DRM-Kennungsdatenschutz, Funktionsüberschreibungen und App-spezifische Sicherheitspatch-Regeln.", "Используйте профили, чтобы в одном месте настроить приложения, шаблон идентичности, свой keybox, приватность DRM, переопределения функций и патч безопасности для приложений.", "Gunakan Profil untuk penugasan aplikasi, templat identitas, keybox kustom, privasi pengenal DRM, override per fitur, dan aturan Patch Keamanan per aplikasi di satu tempat.", "ऐप assignment, identity template, custom keybox, DRM पहचान गोपनीयता, प्रति-सुविधा override और प्रति-ऐप सुरक्षा पैच नियम एक जगह रखने के लिए Profiles उपयोग करें।", "استخدم الملفات الشخصية لجمع تعيينات التطبيقات وقالب الهوية وKeybox مخصص وخصوصية معرّف DRM وتجاوزات الميزات وقواعد تصحيح الأمان لكل تطبيق في مكان واحد."],
        ["Open Profiles", "打开配置档案", "Abrir perfiles", "Profile öffnen", "Открыть профили", "Buka Profil", "प्रोफ़ाइल खोलें", "فتح الملفات الشخصية"],
        ["Independent from Identity. Child controls appear only while this feature is enabled.", "独立于身份功能。仅在启用此功能时显示子控制项。", "Independiente de Identidad. Los controles secundarios solo aparecen mientras la función está activada.", "Unabhängig von der Identität. Untergeordnete Steuerungen erscheinen nur bei aktivierter Funktion.", "Не зависит от идентичности. Дочерние настройки видны только при включённой функции.", "Independen dari Identitas. Kontrol turunan hanya muncul saat fitur ini aktif.", "पहचान से स्वतंत्र। उप-नियंत्रण केवल सुविधा चालू होने पर दिखते हैं।", "مستقل عن الهوية. لا تظهر عناصر التحكم الفرعية إلا أثناء تفعيل هذه الميزة."],
        ["Use automatic mode for System, Vendor and Boot.", "对系统、供应商和启动使用自动模式。", "Usa el modo automático para Sistema, Vendor y Arranque.", "Automatischen Modus für System, Vendor und Boot verwenden.", "Используйте автоматический режим для System, Vendor и Boot.", "Gunakan mode otomatis untuk System, Vendor, dan Boot.", "System, Vendor और Boot के लिए स्वचालित मोड उपयोग करें।", "استخدم الوضع التلقائي لـ System وVendor وBoot."],
        ["Stale ROM threshold (months)", "旧 ROM 阈值（月）", "Umbral de ROM obsoleta (meses)", "Schwelle für veraltete ROM (Monate)", "Порог устаревшей ROM (месяцы)", "Ambang ROM usang (bulan)", "पुराने ROM की सीमा (महीने)", "حد ROM القديمة (أشهر)"],
        ["Save Security Patch", "保存安全补丁", "Guardar Parche de seguridad", "Sicherheitspatch speichern", "Сохранить патч безопасности", "Simpan Patch Keamanan", "सुरक्षा पैच सहेजें", "حفظ تصحيح الأمان"],
        ["Resolve for an app", "为应用解析", "Resolver para una app", "Für eine App auflösen", "Определить для приложения", "Resolusi untuk aplikasi", "ऐप के लिए हल करें", "حل لتطبيق"],
        ["Shows captured, configured and effective values from the runtime resolver.", "显示运行时解析器捕获、配置和最终生效的值。", "Muestra los valores capturados, configurados y efectivos del resolvedor de runtime.", "Zeigt erfasste, konfigurierte und effektive Werte des Laufzeit-Resolvers.", "Показывает сохранённые, настроенные и фактические значения из resolver среды.", "Menampilkan nilai tertangkap, terkonfigurasi, dan efektif dari resolver runtime.", "रनटाइम resolver से कैप्चर, कॉन्फ़िगर और प्रभावी मान दिखाता है।", "يعرض القيم الملتقطة والمعدّة والفعلية من محلل وقت التشغيل."],
        ["Resolve", "解析", "Resolver", "Auflösen", "Определить", "Resolusi", "हल करें", "حل"],
        ["System", "系统", "Sistema", "System", "System", "System", "System", "System"],
        ["Vendor", "供应商", "Vendor", "Vendor", "Vendor", "Vendor", "Vendor", "Vendor"],
        ["Boot", "启动", "Arranque", "Boot", "Boot", "Boot", "Boot", "Boot"],
        ["Device default", "设备默认值", "Predeterminado del dispositivo", "Gerätestandard", "По умолчанию устройства", "Default perangkat", "डिवाइस डिफ़ॉल्ट", "افتراضي الجهاز"],
        ["ROM property", "ROM 属性", "Propiedad de ROM", "ROM-Eigenschaft", "Свойство ROM", "Properti ROM", "ROM प्रॉपर्टी", "خاصية ROM"],
        ["Manual date", "手动日期", "Fecha manual", "Manuelles Datum", "Дата вручную", "Tanggal manual", "मैन्युअल तारीख", "تاريخ يدوي"],
        ["Automatic", "自动", "Automático", "Automatisch", "Автоматически", "Otomatis", "स्वचालित", "تلقائي"],
        ["Omit", "省略", "Omitir", "Auslassen", "Не указывать", "Abaikan", "छोड़ें", "تجاهل"],
        ["Inherit", "继承", "Heredar", "Übernehmen", "Наследовать", "Warisi", "विरासत", "وراثة"],
        ["Inherit / none", "继承 / 无", "Heredar / ninguno", "Übernehmen / keine", "Наследовать / нет", "Warisi / tidak ada", "विरासत / कोई नहीं", "وراثة / لا شيء"],
        ["Captured:", "已捕获：", "Capturado:", "Erfasst:", "Сохранено:", "Tertangkap:", "कैप्चर किया:", "الملتقط:"],
        ["Configured:", "已配置：", "Configurado:", "Konfiguriert:", "Настроено:", "Dikonfigurasi:", "कॉन्फ़िगर किया:", "المعدّ:"],
        ["Effective:", "生效值：", "Efectivo:", "Effektiv:", "Фактически:", "Efektif:", "प्रभावी:", "الفعلي:"],
        ["App-centric configuration. Assign installed apps or wildcards, then choose privacy, identity, keybox and feature overrides.", "以应用为中心的配置。分配已安装应用或通配符，然后选择隐私、身份、密钥盒和功能覆盖。", "Configuración centrada en apps. Asigna apps instaladas o comodines y elige privacidad, identidad, keybox y sustituciones de funciones.", "App-zentrierte Konfiguration. Installierte Apps oder Platzhalter zuweisen und dann Datenschutz, Identität, Keybox und Funktionsüberschreibungen wählen.", "Настройка вокруг приложений. Назначьте установленные приложения или шаблоны, затем выберите приватность, идентичность, keybox и переопределения функций.", "Konfigurasi berpusat pada aplikasi. Tetapkan aplikasi terinstal atau wildcard, lalu pilih privasi, identitas, keybox, dan override fitur.", "ऐप-केंद्रित कॉन्फ़िगरेशन। इंस्टॉल ऐप या wildcard assign करें, फिर privacy, identity, keybox और feature override चुनें।", "إعداد يركز على التطبيقات. عيّن التطبيقات المثبتة أو wildcard، ثم اختر الخصوصية والهوية وKeybox وتجاوزات الميزات."],
        ["New profile", "新建配置档案", "Nuevo perfil", "Neues Profil", "Новый профиль", "Profil baru", "नई प्रोफ़ाइल", "ملف شخصي جديد"],
        ["Export", "导出", "Exportar", "Exportieren", "Экспорт", "Ekspor", "निर्यात", "تصدير"],
        ["Import", "导入", "Importar", "Importieren", "Импорт", "Impor", "आयात", "استيراد"],
        ["Profile Editor", "配置档案编辑器", "Editor de perfiles", "Profil-Editor", "Редактор профилей", "Editor Profil", "प्रोफ़ाइल संपादक", "محرر الملف الشخصي"],
        ["DRM / privacy mode", "DRM / 隐私模式", "Modo DRM / privacidad", "DRM-/Datenschutzmodus", "Режим DRM / приватности", "Mode DRM / privasi", "DRM / गोपनीयता मोड", "وضع DRM / الخصوصية"],
        ["Isolate - app-scoped pseudonymous DRM ID", "隔离 — 应用专属的匿名 DRM ID", "Aislar: ID DRM seudónimo por app", "Isolieren – App-bezogene pseudonyme DRM-ID", "Изоляция — псевдонимный DRM ID для приложения", "Isolasi - ID DRM pseudonim khusus aplikasi", "पृथक करें - ऐप-दायरे की छद्मनाम DRM ID", "عزل - معرّف DRM مستعار خاص بالتطبيق"],
        ["Redact", "遮蔽", "Redactar", "Schwärzen", "Скрыть", "Redaksi", "रिक्त करें", "حجب"],
        ["Add installed app", "添加已安装应用", "Añadir app instalada", "Installierte App hinzufügen", "Добавить установленное приложение", "Tambah aplikasi terinstal", "इंस्टॉल ऐप जोड़ें", "إضافة تطبيق مثبت"],
        ["Add app", "添加应用", "Añadir app", "App hinzufügen", "Добавить приложение", "Tambah aplikasi", "ऐप जोड़ें", "إضافة تطبيق"],
        ["Assignments (one package or wildcard per line)", "分配（每行一个包或通配符）", "Asignaciones (un paquete o comodín por línea)", "Zuweisungen (ein Paket oder Platzhalter pro Zeile)", "Назначения (один пакет или шаблон в строке)", "Penugasan (satu paket atau wildcard per baris)", "असाइनमेंट (हर पंक्ति में एक पैकेज या wildcard)", "التعيينات (حزمة أو wildcard واحد في كل سطر)"],
        ["Identity template", "身份模板", "Plantilla de identidad", "Identitätsvorlage", "Шаблон идентичности", "Templat identitas", "पहचान टेम्पलेट", "قالب الهوية"],
        ["Feature overrides", "功能覆盖", "Sustituciones de funciones", "Funktionsüberschreibungen", "Переопределения функций", "Override fitur", "सुविधा override", "تجاوزات الميزات"],
        ["Security Patch override", "安全补丁覆盖", "Sustitución de Parche de seguridad", "Sicherheitspatch-Überschreibung", "Переопределение патча безопасности", "Override Patch Keamanan", "सुरक्षा पैच override", "تجاوز تصحيح الأمان"],
        ["No app assignments", "未分配应用", "Sin apps asignadas", "Keine App-Zuweisungen", "Нет назначенных приложений", "Tidak ada penugasan aplikasi", "कोई ऐप assignment नहीं", "لا توجد تعيينات تطبيقات"],
        ["Edit", "编辑", "Editar", "Bearbeiten", "Изменить", "Edit", "संपादित करें", "تحرير"],
        ["No custom profiles yet.", "尚无自定义配置档案。", "Aún no hay perfiles personalizados.", "Noch keine eigenen Profile.", "Пользовательских профилей пока нет.", "Belum ada profil kustom.", "अभी कोई कस्टम प्रोफ़ाइल नहीं है।", "لا توجد ملفات شخصية مخصصة بعد."],
        ["Inspect the exact resolver output for an installed application without exposing private key material.", "检查已安装应用的精确解析结果，同时不公开私钥材料。", "Inspecciona la salida exacta del resolvedor para una app instalada sin exponer material de clave privada.", "Prüft die genaue Resolver-Ausgabe für eine installierte App, ohne privates Schlüsselmaterial offenzulegen.", "Проверьте точный результат resolver для установленного приложения без раскрытия материала закрытого ключа.", "Periksa output resolver yang tepat untuk aplikasi terinstal tanpa mengekspos material kunci privat.", "निजी कुंजी सामग्री उजागर किए बिना इंस्टॉल ऐप का सटीक resolver आउटपुट देखें।", "افحص ناتج المحلل الدقيق لتطبيق مثبت دون كشف مواد المفتاح الخاص."],
        ["Matched profile", "匹配的配置档案", "Perfil coincidente", "Passendes Profil", "Совпавший профиль", "Profil yang cocok", "मेल खाती प्रोफ़ाइल", "الملف الشخصي المطابق"],
        ["Matched rule", "匹配的规则", "Regla coincidente", "Passende Regel", "Совпавшее правило", "Aturan yang cocok", "मेल खाता नियम", "القاعدة المطابقة"],
        ["DRM privacy", "DRM 隐私", "Privacidad DRM", "DRM-Datenschutz", "Приватность DRM", "Privasi DRM", "DRM गोपनीयता", "خصوصية DRM"],
        ["Keystore core", "Keystore 核心", "Núcleo Keystore", "Keystore-Kern", "Ядро Keystore", "Inti Keystore", "Keystore कोर", "نواة Keystore"],
        ["Reboot required", "需要重启", "Reinicio necesario", "Neustart erforderlich", "Требуется перезагрузка", "Perlu reboot", "रीबूट आवश्यक", "إعادة التشغيل مطلوبة"],
        ["Identity enabled", "身份已启用", "Identidad activada", "Identität aktiviert", "Идентичность включена", "Identitas aktif", "पहचान सक्षम", "الهوية مفعّلة"],
        ["Identity disabled", "身份已禁用", "Identidad desactivada", "Identität deaktiviert", "Идентичность отключена", "Identitas nonaktif", "पहचान अक्षम", "الهوية معطّلة"],
        ["Security Patch enabled", "安全补丁已启用", "Parche de seguridad activado", "Sicherheitspatch aktiviert", "Патч безопасности включён", "Patch Keamanan aktif", "सुरक्षा पैच सक्षम", "تصحيح الأمان مفعّل"],
        ["Security Patch disabled", "安全补丁已禁用", "Parche de seguridad desactivado", "Sicherheitspatch deaktiviert", "Патч безопасности отключён", "Patch Keamanan nonaktif", "सुरक्षा पैच अक्षम", "تصحيح الأمان معطّل"],
        ["Auto Security Patch enabled", "自动安全补丁已启用", "Parche de seguridad automático activado", "Automatischer Sicherheitspatch aktiviert", "Автоматический патч безопасности включён", "Patch Keamanan Otomatis aktif", "स्वचालित सुरक्षा पैच सक्षम", "تصحيح الأمان التلقائي مفعّل"],
        ["Auto Security Patch disabled", "自动安全补丁已禁用", "Parche de seguridad automático desactivado", "Automatischer Sicherheitspatch deaktiviert", "Автоматический патч безопасности отключён", "Patch Keamanan Otomatis nonaktif", "स्वचालित सुरक्षा पैच अक्षम", "تصحيح الأمان التلقائي معطّل"],
        ["Security Patch policy saved", "安全补丁策略已保存", "Política de Parche de seguridad guardada", "Sicherheitspatch-Richtlinie gespeichert", "Политика патча безопасности сохранена", "Kebijakan Patch Keamanan disimpan", "सुरक्षा पैच नीति सहेजी गई", "تم حفظ سياسة تصحيح الأمان"],
        ["Profile cloned", "配置档案已克隆", "Perfil clonado", "Profil geklont", "Профиль клонирован", "Profil dikloning", "प्रोफ़ाइल क्लोन हुई", "تم استنساخ الملف الشخصي"],
        ["Profile deleted", "配置档案已删除", "Perfil eliminado", "Profil gelöscht", "Профиль удалён", "Profil dihapus", "प्रोफ़ाइल हटाई गई", "تم حذف الملف الشخصي"],
        ["Profile name already exists", "配置档案名称已存在", "El nombre del perfil ya existe", "Profilname existiert bereits", "Имя профиля уже существует", "Nama profil sudah ada", "प्रोफ़ाइल नाम पहले से मौजूद है", "اسم الملف الشخصي موجود بالفعل"],
        ["Profile name is invalid", "配置档案名称无效", "El nombre del perfil no es válido", "Profilname ist ungültig", "Недопустимое имя профиля", "Nama profil tidak valid", "प्रोफ़ाइल नाम अमान्य है", "اسم الملف الشخصي غير صالح"],
        ["Profile policy copied/exported", "配置档案策略已复制/导出", "Política de perfil copiada/exportada", "Profilrichtlinie kopiert/exportiert", "Политика профиля скопирована/экспортирована", "Kebijakan profil disalin/diekspor", "प्रोफ़ाइल नीति कॉपी/निर्यात हुई", "تم نسخ/تصدير سياسة الملف الشخصي"],
        ["Profile policy imported", "配置档案策略已导入", "Política de perfil importada", "Profilrichtlinie importiert", "Политика профиля импортирована", "Kebijakan profil diimpor", "प्रोफ़ाइल नीति आयात हुई", "تم استيراد سياسة الملف الشخصي"],
        ["Could not save policy", "无法保存策略", "No se pudo guardar la política", "Richtlinie konnte nicht gespeichert werden", "Не удалось сохранить политику", "Kebijakan tidak dapat disimpan", "नीति सहेजी नहीं जा सकी", "تعذر حفظ السياسة"],
        ["Could not update setting", "无法更新设置", "No se pudo actualizar el ajuste", "Einstellung konnte nicht aktualisiert werden", "Не удалось обновить настройку", "Pengaturan tidak dapat diperbarui", "सेटिंग अपडेट नहीं हो सकी", "تعذر تحديث الإعداد"],
        ["Could not resolve patch state", "无法解析补丁状态", "No se pudo resolver el estado del parche", "Patchstatus konnte nicht aufgelöst werden", "Не удалось определить состояние патча", "Status patch tidak dapat diresolusi", "पैच स्थिति हल नहीं हो सकी", "تعذر حل حالة التصحيح"],
        ["Could not inspect effective state", "无法检查生效状态", "No se pudo inspeccionar el estado efectivo", "Effektiver Zustand konnte nicht geprüft werden", "Не удалось проверить фактическое состояние", "Status efektif tidak dapat diperiksa", "प्रभावी स्थिति देखी नहीं जा सकी", "تعذر فحص الحالة الفعلية"],
        ["The operation failed. Open Logs for details.", "操作失败。打开日志查看详情。", "La operación falló. Abre Registros para ver los detalles.", "Der Vorgang ist fehlgeschlagen. Details stehen in den Protokollen.", "Операция завершилась ошибкой. Подробности в логах.", "Operasi gagal. Buka Log untuk detail.", "कार्य विफल हुआ। विवरण के लिए लॉग खोलें।", "فشلت العملية. افتح السجلات للتفاصيل."],
        ["RESOLVING PIXEL IDENTITY...", "正在解析 PIXEL 身份…", "RESOLVIENDO IDENTIDAD PIXEL...", "PIXEL-IDENTITÄT WIRD AUFGELÖST…", "ОПРЕДЕЛЕНИЕ ИДЕНТИЧНОСТИ PIXEL…", "MENGAMBIL IDENTITAS PIXEL...", "PIXEL पहचान हल हो रही है...", "جار حل هوية PIXEL..."],
        ["Resolving Pixel identity...", "正在解析 Pixel 身份…", "Resolviendo identidad Pixel...", "Pixel-Identität wird aufgelöst…", "Определение идентичности Pixel…", "Mengambil identitas Pixel...", "Pixel पहचान हल हो रही है...", "جار حل هوية Pixel..."],
        ["Auto Identity source is temporarily unavailable. Try again later or choose a local template.", "自动身份源暂时不可用。请稍后重试或选择本地模板。", "La fuente de Identidad automática no está disponible temporalmente. Inténtalo más tarde o elige una plantilla local.", "Die Quelle für automatische Identität ist vorübergehend nicht verfügbar. Später erneut versuchen oder eine lokale Vorlage wählen.", "Источник автоидентичности временно недоступен. Повторите позже или выберите локальный шаблон.", "Sumber Identitas Otomatis sementara tidak tersedia. Coba lagi nanti atau pilih templat lokal.", "स्वचालित पहचान स्रोत अस्थायी रूप से उपलब्ध नहीं है। बाद में फिर प्रयास करें या स्थानीय टेम्पलेट चुनें।", "مصدر الهوية التلقائية غير متاح مؤقتا. حاول لاحقا أو اختر قالبا محليا."],
        ["Auto Identity failed", "自动身份失败", "Falló la Identidad automática", "Automatische Identität fehlgeschlagen", "Ошибка автоидентичности", "Identitas Otomatis gagal", "स्वचालित पहचान विफल", "فشلت الهوية التلقائية"],
        ["Estimated impact: CPU low per matching identity/attestation call; RAM low and bounded.", "预计影响：每次匹配的身份/认证调用占用少量 CPU；RAM 占用低且有上限。", "Impacto estimado: CPU bajo por llamada de identidad/atestación coincidente; RAM baja y acotada.", "Geschätzte Auswirkung: geringe CPU pro passendem Identitäts-/Attestierungsaufruf; geringer und begrenzter RAM.", "Оценка: низкая нагрузка CPU на подходящий вызов идентичности/аттестации; мало RAM с ограничением.", "Dampak perkiraan: CPU rendah per panggilan identitas/atestasi yang cocok; RAM rendah dan terbatas.", "अनुमानित प्रभाव: मेल खाती identity/attestation call पर कम CPU; RAM कम और सीमित।", "الأثر المتوقع: CPU منخفض لكل استدعاء هوية/تصديق مطابق؛ وRAM منخفضة ومحدودة."],
        ["Estimated impact: CPU very low while idle and low per matching Binder call; RAM low.", "预计影响：空闲时 CPU 极低，每次匹配的 Binder 调用占用低；RAM 占用低。", "Impacto estimado: CPU muy baja en reposo y baja por llamada Binder coincidente; RAM baja.", "Geschätzte Auswirkung: im Leerlauf sehr geringe CPU und gering pro passendem Binder-Aufruf; geringer RAM.", "Оценка: очень низкая нагрузка CPU в простое и низкая на подходящий Binder-вызов; мало RAM.", "Dampak perkiraan: CPU sangat rendah saat idle dan rendah per panggilan Binder yang cocok; RAM rendah.", "अनुमानित प्रभाव: idle में CPU बहुत कम और मेल खाती Binder call पर कम; RAM कम।", "الأثر المتوقع: CPU منخفض جدا عند الخمول ومنخفض لكل استدعاء Binder مطابق؛ وRAM منخفضة."],
        ["Estimated impact: CPU low only on matching calls; RAM low.", "预计影响：仅匹配调用时 CPU 占用低；RAM 占用低。", "Impacto estimado: CPU baja solo en llamadas coincidentes; RAM baja.", "Geschätzte Auswirkung: geringe CPU nur bei passenden Aufrufen; geringer RAM.", "Оценка: низкая нагрузка CPU только на подходящих вызовах; мало RAM.", "Dampak perkiraan: CPU rendah hanya pada panggilan yang cocok; RAM rendah.", "अनुमानित प्रभाव: केवल मेल खाती call पर कम CPU; RAM कम।", "الأثر المتوقع: CPU منخفض فقط في الاستدعاءات المطابقة؛ وRAM منخفضة."],
        ["Estimated impact: CPU very low per UID decision; RAM low with a bounded UID cache.", "预计影响：每次 UID 决策 CPU 极低；使用有界 UID 缓存，RAM 占用低。", "Impacto estimado: CPU muy baja por decisión de UID; RAM baja con caché de UID acotada.", "Geschätzte Auswirkung: sehr geringe CPU pro UID-Entscheidung; geringer RAM durch begrenzten UID-Cache.", "Оценка: очень низкая нагрузка CPU на решение UID; мало RAM благодаря ограниченному UID-кешу.", "Dampak perkiraan: CPU sangat rendah per keputusan UID; RAM rendah dengan cache UID terbatas.", "अनुमानित प्रभाव: हर UID निर्णय पर CPU बहुत कम; सीमित UID cache के साथ RAM कम।", "الأثر المتوقع: CPU منخفض جدا لكل قرار UID؛ وRAM منخفضة مع cache UID محدودة."],
        ["Estimated impact: CPU/network low during scheduled verification; RAM low and temporary.", "预计影响：计划验证期间 CPU/网络占用低；RAM 占用低且为临时使用。", "Impacto estimado: CPU/red bajas durante la verificación programada; RAM baja y temporal.", "Geschätzte Auswirkung: geringe CPU-/Netzlast während geplanter Prüfung; geringer temporärer RAM.", "Оценка: низкая нагрузка CPU/сети при плановой проверке; временно мало RAM.", "Dampak perkiraan: CPU/jaringan rendah selama verifikasi terjadwal; RAM rendah dan sementara.", "अनुमानित प्रभाव: निर्धारित सत्यापन में CPU/नेटवर्क कम; RAM कम और अस्थायी।", "الأثر المتوقع: CPU/شبكة منخفضان أثناء التحقق المجدول؛ وRAM منخفضة ومؤقتة."],
        ["Estimated impact: CPU low at boot only; RAM negligible after initialization.", "预计影响：仅启动时 CPU 占用低；初始化后 RAM 占用可忽略。", "Impacto estimado: CPU baja solo al arrancar; RAM insignificante tras iniciar.", "Geschätzte Auswirkung: geringe CPU nur beim Start; RAM nach Initialisierung vernachlässigbar.", "Оценка: низкая нагрузка CPU только при загрузке; после инициализации RAM почти не используется.", "Dampak perkiraan: CPU rendah hanya saat boot; RAM dapat diabaikan setelah inisialisasi.", "अनुमानित प्रभाव: केवल बूट पर CPU कम; initialization के बाद RAM नगण्य।", "الأثر المتوقع: CPU منخفض عند الإقلاع فقط؛ وRAM ضئيلة بعد التهيئة."],
        ["Estimated impact: CPU low per matching Binder call; RAM low.", "预计影响：每次匹配的 Binder 调用 CPU 占用低；RAM 占用低。", "Impacto estimado: CPU baja por llamada Binder coincidente; RAM baja.", "Geschätzte Auswirkung: geringe CPU pro passendem Binder-Aufruf; geringer RAM.", "Оценка: низкая нагрузка CPU на подходящий Binder-вызов; мало RAM.", "Dampak perkiraan: CPU rendah per panggilan Binder yang cocok; RAM rendah.", "अनुमानित प्रभाव: मेल खाती Binder call पर कम CPU; RAM कम।", "الأثر المتوقع: CPU منخفض لكل استدعاء Binder مطابق؛ وRAM منخفضة."],
        ["Estimated impact: CPU negligible on protected infrastructure paths; RAM negligible.", "预计影响：受保护基础设施路径上的 CPU 和 RAM 占用均可忽略。", "Impacto estimado: CPU insignificante en rutas de infraestructura protegidas; RAM insignificante.", "Geschätzte Auswirkung: CPU auf geschützten Infrastrukturpfaden und RAM vernachlässigbar.", "Оценка: нагрузка CPU на защищённых инфраструктурных путях и RAM пренебрежимо малы.", "Dampak perkiraan: CPU dapat diabaikan pada jalur infrastruktur terlindungi; RAM dapat diabaikan.", "अनुमानित प्रभाव: संरक्षित infrastructure पथ पर CPU नगण्य; RAM नगण्य।", "الأثر المتوقع: CPU ضئيل في مسارات البنية المحمية؛ وRAM ضئيلة."],
        ["Estimated impact: CPU low per matching package lookup; RAM low and bounded.", "预计影响：每次匹配的包查询 CPU 占用低；RAM 占用低且有上限。", "Impacto estimado: CPU baja por búsqueda de paquete coincidente; RAM baja y acotada.", "Geschätzte Auswirkung: geringe CPU pro passender Paketsuche; geringer und begrenzter RAM.", "Оценка: низкая нагрузка CPU на подходящий поиск пакета; мало RAM с ограничением.", "Dampak perkiraan: CPU rendah per pencarian paket yang cocok; RAM rendah dan terbatas.", "अनुमानित प्रभाव: मेल खाती package lookup पर कम CPU; RAM कम और सीमित।", "الأثر المتوقع: CPU منخفض لكل بحث حزمة مطابق؛ وRAM منخفضة ومحدودة."],
        ["Estimated impact: CPU low at boot only; RAM negligible after properties are prepared.", "预计影响：仅启动时 CPU 占用低；属性准备完成后 RAM 占用可忽略。", "Impacto estimado: CPU baja solo al arrancar; RAM insignificante tras preparar las propiedades.", "Geschätzte Auswirkung: geringe CPU nur beim Start; RAM nach Vorbereitung der Eigenschaften vernachlässigbar.", "Оценка: низкая нагрузка CPU только при загрузке; после подготовки свойств RAM почти не используется.", "Dampak perkiraan: CPU rendah hanya saat boot; RAM dapat diabaikan setelah properti disiapkan.", "अनुमानित प्रभाव: केवल बूट पर CPU कम; प्रॉपर्टी तैयार होने के बाद RAM नगण्य।", "الأثر المتوقع: CPU منخفض عند الإقلاع فقط؛ وRAM ضئيلة بعد تجهيز الخصائص."],
        ["Estimated impact: CPU low at boot only; RAM negligible.", "预计影响：仅启动时 CPU 占用低；RAM 占用可忽略。", "Impacto estimado: CPU baja solo al arrancar; RAM insignificante.", "Geschätzte Auswirkung: geringe CPU nur beim Start; RAM vernachlässigbar.", "Оценка: низкая нагрузка CPU только при загрузке; RAM почти не используется.", "Dampak perkiraan: CPU rendah hanya saat boot; RAM dapat diabaikan.", "अनुमानित प्रभाव: केवल बूट पर CPU कम; RAM नगण्य।", "الأثر المتوقع: CPU منخفض عند الإقلاع فقط؛ وRAM ضئيلة."],
        ["Estimated impact: CPU low during refresh/verification; RAM moderate and bounded by active certificate chains.", "预计影响：刷新/验证期间 CPU 占用低；RAM 中等，并受活动证书链数量限制。", "Impacto estimado: CPU baja durante actualización/verificación; RAM moderada y limitada por las cadenas activas.", "Geschätzte Auswirkung: geringe CPU bei Aktualisierung/Prüfung; moderater, durch aktive Zertifikatsketten begrenzter RAM.", "Оценка: низкая нагрузка CPU при обновлении/проверке; умеренный RAM, ограниченный активными цепочками сертификатов.", "Dampak perkiraan: CPU rendah selama penyegaran/verifikasi; RAM sedang dan dibatasi rantai sertifikat aktif.", "अनुमानित प्रभाव: रीफ़्रेश/सत्यापन में CPU कम; RAM मध्यम और सक्रिय सर्टिफिकेट chain तक सीमित।", "الأثر المتوقع: CPU منخفض أثناء التحديث/التحقق؛ وRAM متوسطة ومحدودة بسلاسل الشهادات النشطة."],
        ["Estimated impact: CPU very low with cached lookups; RAM low and proportional to configured rules.", "预计影响：缓存查询时 CPU 极低；RAM 占用低并与已配置规则数量成比例。", "Impacto estimado: CPU muy baja con búsquedas en caché; RAM baja y proporcional a las reglas configuradas.", "Geschätzte Auswirkung: sehr geringe CPU durch zwischengespeicherte Suchen; geringer RAM proportional zu den Regeln.", "Оценка: очень низкая нагрузка CPU при кешированном поиске; мало RAM пропорционально числу правил.", "Dampak perkiraan: CPU sangat rendah dengan pencarian cache; RAM rendah dan sebanding dengan aturan yang dikonfigurasi.", "अनुमानित प्रभाव: cached lookup के साथ CPU बहुत कम; RAM कम और कॉन्फ़िगर नियमों के अनुपात में।", "الأثر المتوقع: CPU منخفض جدا مع البحث المخزن مؤقتا؛ وRAM منخفضة وتتناسب مع القواعد المعدّة."],
        ["Identity overrides enabled", "身份覆盖已启用", "Sustituciones de identidad activadas", "Identitätsüberschreibungen aktiviert", "Переопределения идентичности включены", "Override identitas aktif", "पहचान override सक्षम", "تجاوزات الهوية مفعّلة"],
        ["Identity overrides off", "身份覆盖已关闭", "Sustituciones de identidad desactivadas", "Identitätsüberschreibungen aus", "Переопределения идентичности отключены", "Override identitas mati", "पहचान override बंद", "تجاوزات الهوية متوقفة"],
        ["Attestation, telephony and build identity only", "仅认证、电话和构建身份", "Solo identidad de atestación, telefonía y compilación", "Nur Attestierungs-, Telefonie- und Build-Identität", "Только аттестационная, телефонная идентичность и поля сборки", "Hanya identitas atestasi, telepon, dan build", "केवल attestation, टेलीफ़ोनी और build पहचान", "هوية التصديق والهاتف وbuild فقط"],
        ["Does not disable the core Keystore/TEE or boot protection paths.", "不会禁用核心 Keystore/TEE 或启动保护路径。", "No desactiva las rutas principales de protección Keystore/TEE ni de arranque.", "Deaktiviert weder den zentralen Keystore-/TEE- noch den Boot-Schutzpfad.", "Не отключает базовые пути защиты Keystore/TEE или загрузки.", "Tidak menonaktifkan jalur perlindungan inti Keystore/TEE atau boot.", "मुख्य Keystore/TEE या boot सुरक्षा पथ को अक्षम नहीं करता।", "لا يعطّل مسارات حماية Keystore/TEE أو الإقلاع الأساسية."],
        ["Registered and Binder alive", "已注册且 Binder 活动", "Registrado y Binder activo", "Registriert und Binder aktiv", "Зарегистрировано, Binder активен", "Terdaftar dan Binder aktif", "पंजीकृत और Binder सक्रिय", "مسجل وBinder نشط"],
        ["Not operational", "未运行", "No operativo", "Nicht betriebsbereit", "Не работает", "Tidak operasional", "कार्यरत नहीं", "غير عامل"],
        ["Keystore2 Binder lifecycle", "Keystore2 Binder 生命周期", "Ciclo de vida Binder de Keystore2", "Keystore2-Binder-Lebenszyklus", "Жизненный цикл Binder Keystore2", "Siklus Binder Keystore2", "Keystore2 Binder जीवनचक्र", "دورة حياة Binder لـ Keystore2"],
        ["Reports the daemon registration state rather than inferring readiness from configuration.", "报告守护进程注册状态，而不是根据配置推断就绪状态。", "Informa del estado de registro del daemon en lugar de deducirlo de la configuración.", "Meldet den Registrierungsstatus des Daemons, statt Bereitschaft aus der Konfiguration abzuleiten.", "Сообщает состояние регистрации демона, а не предполагает готовность по конфигурации.", "Melaporkan status pendaftaran daemon, bukan menyimpulkan kesiapan dari konfigurasi.", "कॉन्फ़िगरेशन से readiness मानने के बजाय daemon registration स्थिति बताता है।", "يعرض حالة تسجيل العملية بدلا من استنتاج الجاهزية من الإعداد."],
        ["Enabled but not operational", "已启用但未运行", "Activado pero no operativo", "Aktiviert, aber nicht betriebsbereit", "Включено, но не работает", "Aktif tetapi tidak operasional", "सक्षम लेकिन कार्यरत नहीं", "مفعّل لكنه غير عامل"],
        ["Phone subscription Binder lifecycle", "电话订阅 Binder 生命周期", "Ciclo de vida Binder de suscripción telefónica", "Binder-Lebenszyklus des Telefonabonnements", "Жизненный цикл Binder телефонной подписки", "Siklus Binder langganan telepon", "फ़ोन सदस्यता Binder जीवनचक्र", "دورة حياة Binder لاشتراك الهاتف"],
        ["Identity-only Binder path; it is parked while Identity Engine is off.", "仅身份功能使用的 Binder 路径；身份引擎关闭时会暂停。", "Ruta Binder exclusiva de Identidad; queda en espera mientras el Motor de identidad está desactivado.", "Binder-Pfad nur für Identität; bei ausgeschalteter Identitäts-Engine wird er geparkt.", "Binder-путь только для идентичности; при отключённом движке он остановлен.", "Jalur Binder khusus Identitas; dinonaktifkan sementara Mesin Identitas mati.", "केवल पहचान का Binder पथ; Identity Engine बंद होने पर निष्क्रिय रहता है।", "مسار Binder خاص بالهوية؛ يتوقف عندما يكون محرك الهوية معطلا."],
        ["UID decision only", "仅 UID 决策", "Solo decisión de UID", "Nur UID-Entscheidung", "Только решение UID", "Hanya keputusan UID", "केवल UID निर्णय", "قرار UID فقط"],
        ["Resolved application UIDs", "已解析的应用 UID", "UID de aplicaciones resueltos", "Aufgelöste App-UIDs", "Определённые UID приложений", "UID aplikasi yang diresolusi", "हल किए ऐप UID", "UID التطبيقات المحلولة"],
        ["Targets every eligible app while protecting system and RKP infrastructure UIDs.", "以所有符合条件的应用为目标，同时保护系统和 RKP 基础设施 UID。", "Abarca todas las apps aptas mientras protege los UID del sistema y de la infraestructura RKP.", "Erfasst jede geeignete App und schützt System- sowie RKP-Infrastruktur-UIDs.", "Охватывает все подходящие приложения, защищая UID системы и инфраструктуры RKP.", "Menargetkan setiap aplikasi yang memenuhi syarat sambil melindungi UID sistem dan infrastruktur RKP.", "सभी योग्य ऐप्स को लक्ष्य बनाता है और system व RKP infrastructure UID सुरक्षित रखता है।", "يستهدف كل تطبيق مؤهل مع حماية UID النظام وبنية RKP."],
        ["Scheduled background check", "计划后台检查", "Comprobación programada en segundo plano", "Geplante Hintergrundprüfung", "Плановая фоновая проверка", "Pemeriksaan latar terjadwal", "निर्धारित बैकग्राउंड जाँच", "فحص خلفي مجدول"],
        ["Worker stopped", "工作线程已停止", "Worker detenido", "Worker gestoppt", "Обработчик остановлен", "Worker berhenti", "Worker रुका", "العامل متوقف"],
        ["Authorized key material", "已授权的密钥材料", "Material de claves autorizado", "Autorisiertes Schlüsselmaterial", "Разрешённый ключевой материал", "Material kunci resmi", "अधिकृत कुंजी सामग्री", "مواد مفاتيح معتمدة"],
        ["Core keybox maintenance; independent from Identity Engine.", "核心密钥盒维护；独立于身份引擎。", "Mantenimiento principal de keybox; independiente del Motor de identidad.", "Zentrale Keybox-Pflege; unabhängig von der Identitäts-Engine.", "Базовое обслуживание keybox; не зависит от движка идентичности.", "Pemeliharaan inti keybox; independen dari Mesin Identitas.", "मुख्य keybox रखरखाव; Identity Engine से स्वतंत्र।", "صيانة Keybox الأساسية؛ مستقلة عن محرك الهوية."],
        ["Boot only", "仅启动时", "Solo al arrancar", "Nur beim Start", "Только при загрузке", "Hanya saat boot", "केवल बूट", "عند الإقلاع فقط"],
        ["Persisted identity fields", "持久化的身份字段", "Campos de identidad persistidos", "Gespeicherte Identitätsfelder", "Сохранённые поля идентичности", "Bidang identitas tersimpan", "सहेजे पहचान फ़ील्ड", "حقول الهوية المحفوظة"],
        ["Refreshes configured attestation and app-visible telephony identifiers.", "刷新已配置的认证和应用可见电话标识符。", "Actualiza los identificadores configurados de atestación y telefonía visibles para apps.", "Aktualisiert konfigurierte Attestierungs- und App-sichtbare Telefonie-Kennungen.", "Обновляет настроенные аттестационные и видимые приложениям телефонные идентификаторы.", "Menyegarkan pengenal atestasi dan telepon yang terlihat aplikasi.", "कॉन्फ़िगर attestation और ऐप को दिखने वाले टेलीफ़ोनी पहचानकर्ता रीफ़्रेश करता है।", "يحدّث معرّفات التصديق والهاتف المعدّة والظاهرة للتطبيقات."],
        ["Matching Binder calls only", "仅匹配的 Binder 调用", "Solo llamadas Binder coincidentes", "Nur passende Binder-Aufrufe", "Только подходящие Binder-вызовы", "Hanya panggilan Binder yang cocok", "केवल मेल खाती Binder call", "استدعاءات Binder المطابقة فقط"],
        ["Permission-approved app APIs", "权限允许的应用 API", "API de apps autorizadas por permisos", "Durch Berechtigungen erlaubte App-APIs", "API приложений с разрешением", "API aplikasi yang diizinkan", "अनुमति-स्वीकृत ऐप API", "واجهات تطبيق سمحت بها الأذونات"],
        ["Overrides configured dual-SIM values without changing modem or carrier identity.", "覆盖已配置的双 SIM 值，但不更改调制解调器或运营商身份。", "Sustituye los valores de doble SIM configurados sin cambiar la identidad del módem ni del operador.", "Überschreibt konfigurierte Dual-SIM-Werte, ohne Modem- oder Netzbetreiberidentität zu ändern.", "Переопределяет настроенные значения двух SIM, не меняя идентичность модема или оператора.", "Mengganti nilai dual-SIM yang dikonfigurasi tanpa mengubah identitas modem atau operator.", "कॉन्फ़िगर dual-SIM मान override करता है, modem या carrier पहचान नहीं बदलता।", "يتجاوز قيم SIM المزدوجة المعدّة من دون تغيير هوية المودم أو شركة الاتصالات."],
        ["Protected infrastructure + unified key path", "受保护的基础设施 + 统一密钥路径", "Infraestructura protegida + ruta de claves unificada", "Geschützte Infrastruktur + einheitlicher Schlüsselpfad", "Защищённая инфраструктура + единый путь ключей", "Infrastruktur terlindungi + jalur kunci terpadu", "संरक्षित infrastructure + एकीकृत key पथ", "بنية محمية + مسار مفاتيح موحد"],
        ["RKP callers and targeted KeyMint replies", "RKP 调用方和目标 KeyMint 响应", "Llamadores RKP y respuestas KeyMint objetivo", "RKP-Aufrufer und gezielte KeyMint-Antworten", "Вызовы RKP и целевые ответы KeyMint", "Pemanggil RKP dan respons KeyMint tertarget", "RKP caller और लक्षित KeyMint उत्तर", "متصلو RKP وردود KeyMint المستهدفة"],
        ["RKP infrastructure UIDs always stay on Android. Targeted generateKey and getKeyEntry responses share one certificate-compatibility path to avoid split attestation leaves.", "RKP 基础设施 UID 始终留在 Android 原始路径。目标 generateKey 和 getKeyEntry 响应共享同一证书兼容路径，以避免认证叶证书分裂。", "Los UID de infraestructura RKP permanecen siempre en Android. Las respuestas objetivo de generateKey y getKeyEntry comparten una ruta de compatibilidad de certificados para evitar hojas de atestación divididas.", "RKP-Infrastruktur-UIDs bleiben immer bei Android. Gezielte generateKey- und getKeyEntry-Antworten nutzen einen gemeinsamen Zertifikats-Kompatibilitätspfad, damit Attestierungsblätter nicht auseinanderlaufen.", "UID инфраструктуры RKP всегда остаются на Android. Целевые ответы generateKey и getKeyEntry используют единый путь совместимости сертификатов, чтобы не разделять листья аттестации.", "UID infrastruktur RKP selalu tetap di Android. Respons generateKey dan getKeyEntry tertarget berbagi satu jalur kompatibilitas sertifikat agar leaf atestasi tidak terpisah.", "RKP infrastructure UID हमेशा Android पर रहते हैं। लक्षित generateKey और getKeyEntry उत्तर एक certificate-compatibility पथ साझा करते हैं ताकि attestation leaf अलग न हों।", "تبقى UID بنية RKP دائما على Android. تشترك ردود generateKey وgetKeyEntry المستهدفة في مسار واحد لتوافق الشهادات لتجنب انقسام أوراق التصديق."],
        ["Bounded UID lookup", "有界 UID 查询", "Búsqueda de UID acotada", "Begrenzte UID-Suche", "Ограниченный поиск UID", "Pencarian UID terbatas", "सीमित UID lookup", "بحث UID محدود"],
        ["drm_packages.txt rules", "drm_packages.txt 规则", "Reglas de drm_packages.txt", "drm_packages.txt-Regeln", "Правила drm_packages.txt", "Aturan drm_packages.txt", "drm_packages.txt नियम", "قواعد drm_packages.txt"],
        ["Leaves configured playback and DRM packages on the original keystore path.", "使已配置的播放和 DRM 包保留在原始 Keystore 路径。", "Mantiene los paquetes de reproducción y DRM configurados en la ruta Keystore original.", "Belässt konfigurierte Wiedergabe- und DRM-Pakete auf dem ursprünglichen Keystore-Pfad.", "Оставляет настроенные пакеты воспроизведения и DRM на исходном пути Keystore.", "Membiarkan paket pemutaran dan DRM yang dikonfigurasi pada jalur Keystore asli.", "कॉन्फ़िगर playback और DRM packages को मूल Keystore पथ पर रखता है।", "يبقي حزم التشغيل وDRM المعدّة على مسار Keystore الأصلي."],
        ["Fingerprint and Build fields", "指纹和构建字段", "Huella y campos de compilación", "Fingerprint- und Build-Felder", "Отпечаток и поля сборки", "Sidik jari dan bidang Build", "फ़िंगरप्रिंट और Build फ़ील्ड", "البصمة وحقول Build"],
        ["Persists the selected template before Zygote; requires reboot.", "在 Zygote 前持久化所选模板；需要重启。", "Guarda la plantilla elegida antes de Zygote; requiere reiniciar.", "Speichert die gewählte Vorlage vor Zygote; Neustart erforderlich.", "Сохраняет выбранный шаблон до Zygote; требуется перезагрузка.", "Menyimpan templat terpilih sebelum Zygote; perlu reboot.", "चुना टेम्पलेट Zygote से पहले सहेजता है; रीबूट आवश्यक।", "يحفظ القالب المحدد قبل Zygote؛ ويتطلب إعادة التشغيل."],
        ["Bounded userspace region properties", "有界用户空间区域属性", "Propiedades regionales acotadas en espacio de usuario", "Begrenzte Userspace-Regionseigenschaften", "Ограниченные региональные свойства userspace", "Properti wilayah userspace terbatas", "सीमित userspace क्षेत्र प्रॉपर्टी", "خصائص منطقة محدودة في userspace"],
        ["Applies the optional CN region view before Zygote; requires reboot.", "在 Zygote 前应用可选的中国区域视图；需要重启。", "Aplica la vista regional CN opcional antes de Zygote; requiere reiniciar.", "Wendet die optionale CN-Regionsansicht vor Zygote an; Neustart erforderlich.", "Применяет необязательное представление региона CN до Zygote; требуется перезагрузка.", "Menerapkan tampilan wilayah CN opsional sebelum Zygote; perlu reboot.", "वैकल्पिक CN क्षेत्र दृश्य Zygote से पहले लागू करता है; रीबूट आवश्यक।", "يطبق عرض منطقة CN الاختياري قبل Zygote؛ ويتطلب إعادة التشغيل."],
        ["Validated bounded cache", "已验证的有界缓存", "Caché validada y acotada", "Validierter begrenzter Cache", "Проверенный ограниченный кеш", "Cache tervalidasi dan terbatas", "सत्यापित सीमित cache", "cache موثقة ومحدودة"],
        ["Uses root-only storage and fails closed when revocation data is unavailable.", "使用仅 root 可访问的存储，撤销数据不可用时会安全失败。", "Usa almacenamiento exclusivo de root y falla de forma cerrada si no hay datos de revocación.", "Verwendet nur für Root zugänglichen Speicher und schlägt bei fehlenden Widerrufsdaten sicher geschlossen fehl.", "Использует хранилище только для root и безопасно отказывает при недоступных данных отзыва.", "Menggunakan penyimpanan khusus root dan gagal tertutup saat data pencabutan tidak tersedia.", "केवल root storage उपयोग करता है और revocation डेटा न मिलने पर fail-closed रहता है।", "يستخدم تخزينا خاصا بـ root ويفشل مغلقا عند غياب بيانات الإلغاء."],
        ["Cached package lookup", "缓存的包查询", "Búsqueda de paquetes en caché", "Zwischengespeicherte Paketsuche", "Кешированный поиск пакетов", "Pencarian paket dengan cache", "कैश की गई पैकेज खोज", "بحث حزم مخزن مؤقتا"],
        ["Selects target-specific identity and keybox policy.", "选择目标专属的身份和密钥盒策略。", "Selecciona la política de identidad y keybox específica del objetivo.", "Wählt Ziel-spezifische Identitäts- und Keybox-Richtlinien.", "Выбирает политику идентичности и keybox для цели.", "Memilih kebijakan identitas dan keybox khusus target.", "लक्ष्य-विशिष्ट पहचान और keybox नीति चुनता है।", "يختار سياسة الهوية وKeybox الخاصة بالهدف."],
        ["Profile privacy Isolate replaces only DRM deviceUniqueId with a stable app-scoped pseudonymous ID. Licenses, provisioning and security level stay on Android's genuine DRM path.", "配置档案隐私中的隔离仅将 DRM deviceUniqueId 替换为稳定的应用专属匿名 ID。许可证、配置和安全级别仍走 Android 真实 DRM 路径。", "Aislar en la privacidad del perfil solo sustituye DRM deviceUniqueId por un ID seudónimo estable por app. Las licencias, el aprovisionamiento y el nivel de seguridad permanecen en la ruta DRM real de Android.", "Profil-Datenschutz „Isolieren“ ersetzt nur DRM deviceUniqueId durch eine stabile App-bezogene pseudonyme ID. Lizenzen, Provisionierung und Sicherheitsstufe bleiben auf Androids echtem DRM-Pfad.", "Изоляция приватности профиля заменяет только DRM deviceUniqueId стабильным псевдонимным ID приложения. Лицензии, provisioning и уровень безопасности остаются на настоящем DRM-пути Android.", "Isolasi privasi profil hanya mengganti DRM deviceUniqueId dengan ID pseudonim stabil khusus aplikasi. Lisensi, provisioning, dan tingkat keamanan tetap pada jalur DRM asli Android.", "Profile privacy Isolate केवल DRM deviceUniqueId को स्थिर ऐप-दायरे की छद्मनाम ID से बदलता है। लाइसेंस, provisioning और security level Android के वास्तविक DRM पथ पर रहते हैं।", "يستبدل عزل خصوصية الملف الشخصي DRM deviceUniqueId فقط بمعرّف مستعار ثابت خاص بالتطبيق. تبقى التراخيص وprovisioning ومستوى الأمان على مسار DRM الحقيقي في Android."],
        ["Profile privacy", "配置档案隐私", "Privacidad del perfil", "Profil-Datenschutz", "Приватность профиля", "Privasi profil", "प्रोफ़ाइल गोपनीयता", "خصوصية الملف الشخصي"],
        ["Isolate", "隔离", "Aislar", "Isolieren", "Изолировать", "Isolasi", "पृथक करें", "عزل"],
        ["replaces only DRM", "仅替换 DRM", "solo sustituye DRM", "ersetzt nur DRM", "заменяет только DRM", "hanya mengganti DRM", "केवल DRM बदलता है", "يستبدل DRM فقط"],
        ["with a stable app-scoped pseudonymous ID. Licenses, provisioning and security level stay on Android's genuine DRM path.", "为稳定的应用专属匿名 ID。许可证、配置和安全级别仍走 Android 真实 DRM 路径。", "por un ID seudónimo estable por app. Las licencias, el aprovisionamiento y el nivel de seguridad permanecen en la ruta DRM real de Android.", "durch eine stabile App-bezogene pseudonyme ID. Lizenzen, Provisionierung und Sicherheitsstufe bleiben auf Androids echtem DRM-Pfad.", "стабильным псевдонимным ID приложения. Лицензии, provisioning и уровень безопасности остаются на настоящем DRM-пути Android.", "dengan ID pseudonim stabil khusus aplikasi. Lisensi, provisioning, dan tingkat keamanan tetap pada jalur DRM asli Android.", "को स्थिर ऐप-दायरे की छद्मनाम ID से। लाइसेंस, provisioning और security level Android के वास्तविक DRM पथ पर रहते हैं।", "بمعرّف مستعار ثابت خاص بالتطبيق. تبقى التراخيص وprovisioning ومستوى الأمان على مسار DRM الحقيقي في Android."],
        ["Keeps packages from drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.", "使 drm_packages.txt 中的包保留在 Android 真实 Keystore 路径上。不会伪造 DRM 安全级别。", "Mantiene los paquetes de drm_packages.txt en la ruta Keystore real de Android. No falsifica un nivel de seguridad DRM.", "Belässt Pakete aus drm_packages.txt auf Androids echtem Keystore-Pfad. Es wird keine DRM-Sicherheitsstufe vorgetäuscht.", "Оставляет пакеты из drm_packages.txt на настоящем пути Keystore Android. Уровень безопасности DRM не подделывается.", "Mempertahankan paket dari drm_packages.txt pada jalur Keystore asli Android. Ini tidak memalsukan tingkat keamanan DRM.", "drm_packages.txt के पैकेज Android के वास्तविक Keystore पथ पर रखता है। यह DRM security level को नकली नहीं बनाता।", "يبقي حزم drm_packages.txt على مسار Keystore الحقيقي في Android. ولا يزيّف مستوى أمان DRM."],
        ["Use Profiles > Privacy > Isolate for apps that should not share the genuine DRM device identifier.", "对于不应共享真实 DRM 设备标识符的应用，请使用配置档案 > 隐私 > 隔离。", "Usa Perfiles > Privacidad > Aislar para las apps que no deban compartir el identificador DRM real del dispositivo.", "Für Apps, die die echte DRM-Gerätekennung nicht teilen sollen, Profile > Datenschutz > Isolieren verwenden.", "Для приложений, которым не следует использовать общий настоящий DRM-идентификатор, выберите Профили > Приватность > Изолировать.", "Gunakan Profil > Privasi > Isolasi untuk aplikasi yang tidak boleh berbagi pengenal perangkat DRM asli.", "जो ऐप वास्तविक DRM device identifier साझा न करें उनके लिए Profiles > Privacy > Isolate उपयोग करें।", "استخدم الملفات الشخصية > الخصوصية > عزل للتطبيقات التي يجب ألا تشارك معرّف جهاز DRM الحقيقي."],
        ["Override the Security Patch feature only for apps assigned to this profile.", "仅为分配到此配置档案的应用覆盖安全补丁功能。", "Sustituye la función Parche de seguridad solo para las apps asignadas a este perfil.", "Überschreibt die Sicherheitspatch-Funktion nur für diesem Profil zugewiesene Apps.", "Переопределяет патч безопасности только для приложений этого профиля.", "Override fitur Patch Keamanan hanya untuk aplikasi yang ditetapkan ke profil ini.", "सुरक्षा पैच सुविधा केवल इस प्रोफ़ाइल को assign ऐप्स के लिए override करें।", "تجاوز ميزة تصحيح الأمان فقط للتطبيقات المعيّنة لهذا الملف الشخصي."],
        ["Show", "显示", "Mostrar", "Anzeigen", "Показать", "Tampilkan", "दिखाएँ", "إظهار"],
        ["Hide", "隐藏", "Ocultar", "Ausblenden", "Скрыть", "Sembunyikan", "छिपाएँ", "إخفاء"],
        ["SIM 1", "SIM 1", "SIM 1", "SIM 1", "SIM 1", "SIM 1", "SIM 1", "SIM 1"],
        ["14 hexadecimal characters", "14 个十六进制字符", "14 caracteres hexadecimales", "14 Hexadezimalzeichen", "14 шестнадцатеричных символов", "14 karakter heksadesimal", "14 हेक्साडेसिमल अक्षर", "14 محرفا سداسي عشري"],
        ["Loading...", "正在加载…", "Cargando...", "Wird geladen…", "Загрузка…", "Memuat...", "लोड हो रहा है...", "جار التحميل..."],
        ["Saving...", "正在保存…", "Guardando...", "Wird gespeichert…", "Сохранение…", "Menyimpan...", "सहेजा जा रहा है...", "جار الحفظ..."],
        ["Fetching...", "正在获取…", "Obteniendo...", "Wird abgerufen…", "Получение…", "Mengambil...", "प्राप्त किया जा रहा है...", "جار الجلب..."],
        ["Generating...", "正在生成…", "Generando...", "Wird erzeugt…", "Создание…", "Menghasilkan...", "बनाया जा रहा है...", "جار الإنشاء..."],
        ["Synchronizing...", "正在同步…", "Sincronizando...", "Wird synchronisiert…", "Синхронизация…", "Menyinkronkan...", "सिंक हो रहा है...", "جار المزامنة..."],
        ["Exporting...", "正在导出…", "Exportando...", "Wird exportiert…", "Экспорт…", "Mengekspor...", "निर्यात हो रहा है...", "جار التصدير..."],
        ["Refreshing...", "正在刷新…", "Actualizando...", "Wird aktualisiert…", "Обновление…", "Menyegarkan...", "रीफ़्रेश हो रहा है...", "جار التحديث..."],
        ["Verifying...", "正在验证…", "Verificando...", "Wird geprüft…", "Проверка…", "Memverifikasi...", "सत्यापन हो रहा है...", "جار التحقق..."],
        ["Downloading...", "正在下载…", "Descargando...", "Wird heruntergeladen…", "Скачивание…", "Mengunduh...", "डाउनलोड हो रहा है...", "جار التنزيل..."],
        ["Updating...", "正在更新…", "Actualizando...", "Wird aktualisiert…", "Обновление…", "Memperbarui...", "अपडेट हो रहा है...", "جار التحديث..."],
        ["Removing...", "正在移除…", "Eliminando...", "Wird entfernt…", "Удаление…", "Menghapus...", "हटाया जा रहा है...", "جار الإزالة..."],
        ["Deleting...", "正在删除…", "Borrando...", "Wird gelöscht…", "Удаление…", "Menghapus...", "डिलीट हो रहा है...", "جار الحذف..."],
        ["Uploading...", "正在上传…", "Subiendo...", "Wird hochgeladen…", "Загрузка на сервер…", "Mengunggah...", "अपलोड हो रहा है...", "جار الرفع..."],
        ["Applying...", "正在应用…", "Aplicando...", "Wird angewendet…", "Применение…", "Menerapkan...", "लागू हो रहा है...", "جار التطبيق..."],
        ["Unlocking...", "正在解锁…", "Desbloqueando...", "Wird entsperrt…", "Разблокировка…", "Membuka kunci...", "अनलॉक हो रहा है...", "جار إلغاء القفل..."],
        ["Resetting...", "正在重置…", "Restableciendo...", "Wird zurückgesetzt…", "Сброс…", "Mereset...", "रीसेट हो रहा है...", "جار إعادة الضبط..."],
        ["Restoring...", "正在恢复…", "Restaurando...", "Wird wiederhergestellt…", "Восстановление…", "Memulihkan...", "पुनर्स्थापित हो रहा है...", "جار الاستعادة..."],
        ["Creating encrypted backup...", "正在创建加密备份…", "Creando copia cifrada...", "Verschlüsseltes Backup wird erstellt…", "Создание зашифрованной резервной копии…", "Membuat cadangan terenkripsi...", "एन्क्रिप्टेड बैकअप बनाया जा रहा है...", "جار إنشاء نسخة احتياطية مشفرة..."],
        ["Saved", "已保存", "Guardado", "Gespeichert", "Сохранено", "Tersimpan", "सहेजा गया", "تم الحفظ"],
        ["Success", "成功", "Correcto", "Erfolgreich", "Успешно", "Berhasil", "सफल", "نجاح"],
        ["Deleted", "已删除", "Eliminado", "Gelöscht", "Удалено", "Dihapus", "हटाया गया", "تم الحذف"],
        ["Refreshed", "已刷新", "Actualizado", "Aktualisiert", "Обновлено", "Disegarkan", "रीफ़्रेश हुआ", "تم التحديث"],
        ["Reloaded", "已重新加载", "Recargado", "Neu geladen", "Перезагружено", "Dimuat ulang", "फिर लोड हुआ", "تمت إعادة التحميل"],
        ["Unlocked!", "已解锁！", "¡Desbloqueado!", "Entsperrt!", "Разблокировано!", "Terbuka!", "अनलॉक हुआ!", "تم إلغاء القفل!"],
        ["Language Loaded", "语言已加载", "Idioma cargado", "Sprache geladen", "Язык загружен", "Bahasa dimuat", "भाषा लोड हुई", "تم تحميل اللغة"],
        ["Logs refreshed", "日志已刷新", "Registros actualizados", "Protokolle aktualisiert", "Логи обновлены", "Log disegarkan", "लॉग रीफ़्रेश हुए", "تم تحديث السجلات"],
        ["No logs to download", "没有可下载的日志", "No hay registros para descargar", "Keine Protokolle zum Herunterladen", "Нет логов для скачивания", "Tidak ada log untuk diunduh", "डाउनलोड के लिए कोई लॉग नहीं", "لا توجد سجلات للتنزيل"],
        ["Password required", "需要密码", "Contraseña obligatoria", "Passwort erforderlich", "Требуется пароль", "Kata sandi diperlukan", "पासवर्ड आवश्यक", "كلمة المرور مطلوبة"],
        ["Server Added", "服务器已添加", "Servidor añadido", "Server hinzugefügt", "Сервер добавлен", "Server Ditambahkan", "सर्वर जोड़ा गया", "تمت إضافة الخادم"],
        ["Server Removed", "服务器已移除", "Servidor eliminado", "Server entfernt", "Сервер удалён", "Server Dihapus", "सर्वर हटाया गया", "تمت إزالة الخادم"],
        ["Uploaded Successfully", "上传成功", "Subido correctamente", "Erfolgreich hochgeladen", "Успешно загружено", "Berhasil Diunggah", "सफलतापूर्वक अपलोड हुआ", "تم الرفع بنجاح"],
        ["Saved Successfully", "保存成功", "Guardado correctamente", "Erfolgreich gespeichert", "Успешно сохранено", "Berhasil Disimpan", "सफलतापूर्वक सहेजा गया", "تم الحفظ بنجاح"],
        ["Please paste XML content first", "请先粘贴 XML 内容", "Pega primero el contenido XML", "Bitte zuerst XML-Inhalt einfügen", "Сначала вставьте XML-содержимое", "Tempel konten XML terlebih dahulu", "पहले XML सामग्री पेस्ट करें", "الصق محتوى XML أولا"],
        ["Installed package list is unavailable", "已安装包列表不可用", "La lista de paquetes instalados no está disponible", "Liste installierter Pakete nicht verfügbar", "Список установленных пакетов недоступен", "Daftar paket terinstal tidak tersedia", "इंस्टॉल पैकेज सूची उपलब्ध नहीं है", "قائمة الحزم المثبتة غير متاحة"],
        ["Could not load boot property policy", "无法加载启动属性策略", "No se pudo cargar la política de propiedades de arranque", "Boot-Eigenschaften-Richtlinie konnte nicht geladen werden", "Не удалось загрузить политику свойств загрузки", "Kebijakan properti boot tidak dapat dimuat", "बूट प्रॉपर्टी नीति लोड नहीं हो सकी", "تعذر تحميل سياسة خصائص الإقلاع"],
        ["Saving boot property policy...", "正在保存启动属性策略…", "Guardando política de propiedades de arranque...", "Boot-Eigenschaften-Richtlinie wird gespeichert…", "Сохранение политики свойств загрузки…", "Menyimpan kebijakan properti boot...", "बूट प्रॉपर्टी नीति सहेजी जा रही है...", "جار حفظ سياسة خصائص الإقلاع..."],
        ["Boot property policy saved", "启动属性策略已保存", "Política de propiedades de arranque guardada", "Boot-Eigenschaften-Richtlinie gespeichert", "Политика свойств загрузки сохранена", "Kebijakan properti boot disimpan", "बूट प्रॉपर्टी नीति सहेजी गई", "تم حفظ سياسة خصائص الإقلاع"],
        ["Invalid setting", "设置无效", "Ajuste no válido", "Ungültige Einstellung", "Недопустимая настройка", "Pengaturan tidak valid", "अमान्य सेटिंग", "إعداد غير صالح"],
        ["Setting control is unavailable", "设置控制不可用", "El control del ajuste no está disponible", "Einstellungssteuerung nicht verfügbar", "Управление настройкой недоступно", "Kontrol pengaturan tidak tersedia", "सेटिंग नियंत्रण उपलब्ध नहीं है", "عنصر التحكم بالإعداد غير متاح"],
        ["Setting Updated", "设置已更新", "Ajuste actualizado", "Einstellung aktualisiert", "Настройка обновлена", "Pengaturan Diperbarui", "सेटिंग अपडेट हुई", "تم تحديث الإعداد"],
        ["Identity Generated", "身份已生成", "Identidad generada", "Identität erzeugt", "Идентичность создана", "Identitas Dihasilkan", "पहचान बनाई गई", "تم إنشاء الهوية"],
        ["Verification Failed", "验证失败", "Falló la verificación", "Überprüfung fehlgeschlagen", "Проверка не пройдена", "Verifikasi Gagal", "सत्यापन विफल", "فشل التحقق"],
        ["No keyboxes to verify", "没有可验证的密钥盒", "No hay keyboxes para verificar", "Keine Keyboxen zum Prüfen", "Нет keybox для проверки", "Tidak ada keybox untuk diverifikasi", "सत्यापन के लिए कोई keybox नहीं", "لا توجد Keybox للتحقق"],
        ["Verification Complete", "验证完成", "Verificación completada", "Überprüfung abgeschlossen", "Проверка завершена", "Verifikasi Selesai", "सत्यापन पूरा", "اكتمل التحقق"],
        ["Configuration Saved", "配置已保存", "Configuración guardada", "Konfiguration gespeichert", "Конфигурация сохранена", "Konfigurasi Disimpan", "कॉन्फ़िगरेशन सहेजा गया", "تم حفظ الإعداد"],
        ["Package required", "需要包名", "Paquete obligatorio", "Paket erforderlich", "Требуется пакет", "Paket diperlukan", "पैकेज आवश्यक", "الحزمة مطلوبة"],
        ["Invalid package", "包无效", "Paquete no válido", "Ungültiges Paket", "Недопустимый пакет", "Paket tidak valid", "अमान्य पैकेज", "حزمة غير صالحة"],
        ["Select a profile, keybox, or privacy policy", "请选择配置档案、密钥盒或隐私策略", "Selecciona un perfil, una keybox o una política de privacidad", "Profil, Keybox oder Datenschutzrichtlinie auswählen", "Выберите профиль, keybox или политику приватности", "Pilih profil, keybox, atau kebijakan privasi", "प्रोफ़ाइल, keybox या गोपनीयता नीति चुनें", "اختر ملفا شخصيا أو Keybox أو سياسة خصوصية"],
        ["Rule Updated", "规则已更新", "Regla actualizada", "Regel aktualisiert", "Правило обновлено", "Aturan Diperbarui", "नियम अपडेट हुआ", "تم تحديث القاعدة"],
        ["Rule Added", "规则已添加", "Regla añadida", "Regel hinzugefügt", "Правило добавлено", "Aturan Ditambahkan", "नियम जोड़ा गया", "تمت إضافة القاعدة"],
        ["Saving App Config...", "正在保存应用配置…", "Guardando configuración de la app...", "App-Konfiguration wird gespeichert…", "Сохранение конфигурации приложения…", "Menyimpan Konfigurasi Aplikasi...", "ऐप कॉन्फ़िगरेशन सहेजा जा रहा है...", "جار حفظ إعداد التطبيق..."],
        ["App Config Saved", "应用配置已保存", "Configuración de la app guardada", "App-Konfiguration gespeichert", "Конфигурация приложения сохранена", "Konfigurasi Aplikasi Disimpan", "ऐप कॉन्फ़िगरेशन सहेजा गया", "تم حفظ إعداد التطبيق"],
        ["Please select a profile first", "请先选择配置档案", "Selecciona primero un perfil", "Bitte zuerst ein Profil auswählen", "Сначала выберите профиль", "Pilih profil terlebih dahulu", "पहले प्रोफ़ाइल चुनें", "اختر ملفا شخصيا أولا"],
        ["Runtime settings synchronized", "运行时设置已同步", "Ajustes de runtime sincronizados", "Laufzeiteinstellungen synchronisiert", "Настройки среды синхронизированы", "Pengaturan runtime disinkronkan", "रनटाइम सेटिंग सिंक हुई", "تمت مزامنة إعدادات وقت التشغيل"],
        ["Environment Reset - New identity generated", "环境已重置 — 已生成新身份", "Entorno restablecido: se generó una identidad nueva", "Umgebung zurückgesetzt – neue Identität erzeugt", "Среда сброшена — создана новая идентичность", "Lingkungan Direset - Identitas baru dihasilkan", "परिवेश रीसेट - नई पहचान बनाई गई", "أعيد ضبط البيئة - تم إنشاء هوية جديدة"],
        ["Backup password must be at least 12 characters", "备份密码必须至少包含 12 个字符", "La contraseña de respaldo debe tener al menos 12 caracteres", "Backup-Passwort muss mindestens 12 Zeichen lang sein", "Пароль резервной копии должен содержать не менее 12 символов", "Kata sandi cadangan minimal 12 karakter", "बैकअप पासवर्ड कम से कम 12 अक्षर का होना चाहिए", "يجب ألا تقل كلمة مرور النسخة الاحتياطية عن 12 حرفا"],
        ["Only encrypted .ctsb backups are accepted", "仅接受加密的 .ctsb 备份", "Solo se aceptan copias .ctsb cifradas", "Nur verschlüsselte .ctsb-Backups werden akzeptiert", "Принимаются только зашифрованные резервные копии .ctsb", "Hanya cadangan .ctsb terenkripsi yang diterima", "केवल एन्क्रिप्टेड .ctsb बैकअप स्वीकार किए जाते हैं", "لا تقبل إلا نسخ .ctsb الاحتياطية المشفرة"],
        ["Enter the backup password (at least 12 characters)", "输入备份密码（至少 12 个字符）", "Introduce la contraseña de respaldo (mínimo 12 caracteres)", "Backup-Passwort eingeben (mindestens 12 Zeichen)", "Введите пароль резервной копии (не менее 12 символов)", "Masukkan kata sandi cadangan (minimal 12 karakter)", "बैकअप पासवर्ड दर्ज करें (कम से कम 12 अक्षर)", "أدخل كلمة مرور النسخة الاحتياطية (12 حرفا على الأقل)"],
        ["You have unsaved changes. Click tab again to discard.", "你有未保存的更改。再次点击标签页即可放弃。", "Tienes cambios sin guardar. Pulsa de nuevo la pestaña para descartarlos.", "Es gibt ungespeicherte Änderungen. Tab erneut anklicken, um sie zu verwerfen.", "Есть несохранённые изменения. Снова нажмите вкладку, чтобы отменить их.", "Ada perubahan yang belum disimpan. Klik tab lagi untuk membuangnya.", "आपके परिवर्तन सहेजे नहीं गए हैं। छोड़ने के लिए टैब फिर क्लिक करें।", "لديك تغييرات غير محفوظة. انقر علامة التبويب مرة أخرى لتجاهلها."],
        ["You have unsaved changes. Select file again to discard.", "你有未保存的更改。再次选择文件即可放弃。", "Tienes cambios sin guardar. Selecciona de nuevo el archivo para descartarlos.", "Es gibt ungespeicherte Änderungen. Datei erneut auswählen, um sie zu verwerfen.", "Есть несохранённые изменения. Снова выберите файл, чтобы отменить их.", "Ada perubahan yang belum disimpan. Pilih file lagi untuk membuangnya.", "आपके परिवर्तन सहेजे नहीं गए हैं। छोड़ने के लिए फ़ाइल फिर चुनें।", "لديك تغييرات غير محفوظة. اختر الملف مرة أخرى لتجاهلها."],
        ["Failed to load file", "文件加载失败", "No se pudo cargar el archivo", "Datei konnte nicht geladen werden", "Не удалось загрузить файл", "Gagal memuat file", "फ़ाइल लोड नहीं हुई", "فشل تحميل الملف"],
        ["Error loading file", "加载文件时出错", "Error al cargar el archivo", "Fehler beim Laden der Datei", "Ошибка загрузки файла", "Kesalahan saat memuat file", "फ़ाइल लोड करने में त्रुटि", "خطأ في تحميل الملف"],
        ["File Saved", "文件已保存", "Archivo guardado", "Datei gespeichert", "Файл сохранён", "File Disimpan", "फ़ाइल सहेजी गई", "تم حفظ الملف"],
        ["Changes reverted", "更改已撤销", "Cambios revertidos", "Änderungen zurückgesetzt", "Изменения отменены", "Perubahan dibatalkan", "परिवर्तन वापस लिए गए", "تم التراجع عن التغييرات"],
        ["Copy failed. Check permissions.", "复制失败。请检查权限。", "Falló la copia. Comprueba los permisos.", "Kopieren fehlgeschlagen. Berechtigungen prüfen.", "Не удалось скопировать. Проверьте разрешения.", "Penyalinan gagal. Periksa izin.", "कॉपी विफल। अनुमतियाँ जाँचें।", "فشل النسخ. تحقق من الأذونات."],
        ["Network error: Failed to reach the server. Is the module running?", "网络错误：无法连接服务器。模块是否正在运行？", "Error de red: no se pudo contactar con el servidor. ¿Está ejecutándose el módulo?", "Netzwerkfehler: Server nicht erreichbar. Läuft das Modul?", "Ошибка сети: сервер недоступен. Модуль запущен?", "Kesalahan jaringan: Gagal menjangkau server. Apakah modul berjalan?", "नेटवर्क त्रुटि: सर्वर तक नहीं पहुँचा जा सका। क्या मॉड्यूल चल रहा है?", "خطأ في الشبكة: تعذر الوصول إلى الخادم. هل الوحدة قيد التشغيل؟"],
        ["Select a non-empty XML or CBOX file up to 10 MB", "请选择最大 10 MB 的非空 XML 或 CBOX 文件", "Selecciona un archivo XML o CBOX no vacío de hasta 10 MB", "Nicht leere XML- oder CBOX-Datei bis 10 MB auswählen", "Выберите непустой файл XML или CBOX размером до 10 МБ", "Pilih file XML atau CBOX yang tidak kosong hingga 10 MB", "10 MB तक की गैर-खाली XML या CBOX फ़ाइल चुनें", "اختر ملف XML أو CBOX غير فارغ حتى 10 MB"],
        ["Could not import profile policy", "无法导入配置档案策略", "No se pudo importar la política del perfil", "Profilrichtlinie konnte nicht importiert werden", "Не удалось импортировать политику профиля", "Kebijakan profil tidak dapat diimpor", "प्रोफ़ाइल नीति आयात नहीं हो सकी", "تعذر استيراد سياسة الملف الشخصي"],
        ["Policy file is too large", "策略文件过大", "El archivo de política es demasiado grande", "Richtliniendatei ist zu groß", "Файл политики слишком велик", "File kebijakan terlalu besar", "नीति फ़ाइल बहुत बड़ी है", "ملف السياسة كبير جدا"],
        ["Restore Defaults", "恢复默认设置", "Restaurar valores predeterminados", "Standardeinstellungen wiederherstellen", "Восстановить настройки по умолчанию", "Pulihkan Default", "डिफ़ॉल्ट बहाल करें", "استعادة الإعدادات الافتراضية"],
        ["Restore module settings to defaults?", "将模块设置恢复为默认值？", "¿Restaurar la configuración del módulo a los valores predeterminados?", "Moduleinstellungen auf Standardwerte zurücksetzen?", "Восстановить настройки модуля по умолчанию?", "Pulihkan pengaturan modul ke default?", "मॉड्यूल सेटिंग्स को डिफ़ॉल्ट पर बहाल करें?", "هل تريد استعادة إعدادات الوحدة إلى القيم الافتراضية؟"],
        ["Restores module settings using the built-in default profile. Stored keyboxes and encrypted backups are not deleted.", "使用内置默认配置恢复模块设置。已保存的密钥盒和加密备份不会被删除。", "Restaura la configuración del módulo con el perfil predeterminado integrado. No elimina keyboxes guardados ni copias cifradas.", "Stellt Moduleinstellungen mit dem integrierten Standardprofil wieder her. Gespeicherte Keyboxen und verschlüsselte Backups werden nicht gelöscht.", "Восстанавливает настройки модуля встроенным профилем по умолчанию. Сохранённые keybox и зашифрованные резервные копии не удаляются.", "Memulihkan pengaturan modul memakai profil default bawaan. Keybox tersimpan dan cadangan terenkripsi tidak dihapus.", "अंतर्निहित डिफ़ॉल्ट प्रोफ़ाइल से मॉड्यूल सेटिंग्स बहाल करता है। सहेजे गए keybox और एन्क्रिप्टेड बैकअप हटाए नहीं जाते।", "يستعيد إعدادات الوحدة باستخدام الملف الافتراضي المدمج. لا يتم حذف صناديق المفاتيح المحفوظة أو النسخ الاحتياطية المشفرة."],
        ["Default settings restored", "已恢复默认设置", "Valores predeterminados restaurados", "Standardeinstellungen wiederhergestellt", "Настройки по умолчанию восстановлены", "Pengaturan default dipulihkan", "डिफ़ॉल्ट सेटिंग्स बहाल की गईं", "تمت استعادة الإعدادات الافتراضية"],
        ["Could not restore defaults", "无法恢复默认设置", "No se pudieron restaurar los valores predeterminados", "Standardeinstellungen konnten nicht wiederhergestellt werden", "Не удалось восстановить настройки по умолчанию", "Tidak dapat memulihkan default", "डिफ़ॉल्ट बहाल नहीं किए जा सके", "تعذر استعادة الإعدادات الافتراضية"],
        ["Custom Templates", "自定义模板", "Plantillas personalizadas", "Benutzerdefinierte Vorlagen", "Пользовательские шаблоны", "Template Kustom", "कस्टम टेम्पलेट", "قوالب مخصصة"],
        ["Create a reusable device identity template. The form stays collapsed until you open it.", "创建可重复使用的设备身份模板。表单在打开前保持折叠。", "Crea una plantilla reutilizable de identidad del dispositivo. El formulario permanece contraído hasta que lo abras.", "Erstellt eine wiederverwendbare Geräteidentitätsvorlage. Das Formular bleibt bis zum Öffnen eingeklappt.", "Создайте многоразовый шаблон идентичности устройства. Форма остаётся свёрнутой, пока вы её не откроете.", "Buat template identitas perangkat yang dapat digunakan kembali. Form tetap tertutup sampai dibuka.", "दोबारा उपयोग योग्य डिवाइस पहचान टेम्पलेट बनाएँ। खोलने तक फ़ॉर्म बंद रहता है।", "أنشئ قالب هوية جهاز قابل لإعادة الاستخدام. يبقى النموذج مطويا حتى تفتحه."],
        ["Template ID", "模板 ID", "ID de plantilla", "Vorlagen-ID", "ID шаблона", "ID Template", "टेम्पलेट आईडी", "معرف القالب"],
        ["Manufacturer", "制造商", "Fabricante", "Hersteller", "Производитель", "Produsen", "निर्माता", "الشركة المصنعة"],
        ["Model", "型号", "Modelo", "Modell", "Модель", "Model", "मॉडल", "الطراز"],
        ["Fingerprint", "指纹", "Huella digital", "Fingerprint", "Отпечаток", "Fingerprint", "फिंगरप्रिंट", "البصمة"],
        ["Brand", "品牌", "Marca", "Marke", "Бренд", "Merek", "ब्रांड", "العلامة التجارية"],
        ["Product", "产品", "Producto", "Produkt", "Продукт", "Produk", "उत्पाद", "المنتج"],
        ["Device", "设备", "Dispositivo", "Gerät", "Устройство", "Perangkat", "डिवाइस", "الجهاز"],
        ["Android release", "Android 版本", "Versión de Android", "Android-Version", "Версия Android", "Rilis Android", "Android रिलीज़", "إصدار Android"],
        ["Build ID", "构建 ID", "ID de compilación", "Build-ID", "ID сборки", "ID Build", "बिल्ड आईडी", "معرف البناء"],
        ["Incremental", "增量版本", "Incremental", "Inkrementell", "Инкремент", "Inkremental", "इन्क्रिमेंटल", "تزايدي"],
        ["Build type", "构建类型", "Tipo de compilación", "Build-Typ", "Тип сборки", "Tipe build", "बिल्ड प्रकार", "نوع البناء"],
        ["Build tags", "构建标签", "Etiquetas de compilación", "Build-Tags", "Теги сборки", "Tag build", "बिल्ड टैग", "وسوم البناء"],
        ["Security patch", "安全补丁", "Parche de seguridad", "Sicherheitspatch", "Патч безопасности", "Patch keamanan", "सुरक्षा पैच", "تصحيح الأمان"],
        ["Save custom template", "保存自定义模板", "Guardar plantilla personalizada", "Benutzerdefinierte Vorlage speichern", "Сохранить пользовательский шаблон", "Simpan template kustom", "कस्टम टेम्पलेट सहेजें", "حفظ القالب المخصص"],
        ["Custom template saved", "自定义模板已保存", "Plantilla personalizada guardada", "Benutzerdefinierte Vorlage gespeichert", "Пользовательский шаблон сохранён", "Template kustom disimpan", "कस्टम टेम्पलेट सहेजा गया", "تم حفظ القالب المخصص"],
        ["Template ID is invalid", "模板 ID 无效", "El ID de plantilla no es válido", "Vorlagen-ID ist ungültig", "Недопустимый ID шаблона", "ID template tidak valid", "टेम्पलेट आईडी अमान्य है", "معرف القالب غير صالح"],
        ["Built-in template IDs cannot be replaced", "不能替换内置模板 ID", "No se pueden reemplazar los ID de plantillas integradas", "Integrierte Vorlagen-IDs können nicht ersetzt werden", "Встроенные ID шаблонов нельзя заменять", "ID template bawaan tidak dapat diganti", "अंतर्निहित टेम्पलेट आईडी बदली नहीं जा सकती", "لا يمكن استبدال معرفات القوالب المدمجة"],
        ["All template fields are required", "所有模板字段均为必填", "Todos los campos de la plantilla son obligatorios", "Alle Vorlagenfelder sind erforderlich", "Все поля шаблона обязательны", "Semua kolom template wajib diisi", "सभी टेम्पलेट फ़ील्ड आवश्यक हैं", "جميع حقول القالب مطلوبة"],
        ["Security patch must be YYYY-MM-DD", "安全补丁必须为 YYYY-MM-DD", "El parche de seguridad debe tener formato AAAA-MM-DD", "Sicherheitspatch muss JJJJ-MM-TT sein", "Патч безопасности должен быть YYYY-MM-DD", "Patch keamanan harus YYYY-MM-DD", "सुरक्षा पैच YYYY-MM-DD होना चाहिए", "يجب أن يكون تصحيح الأمان بالصيغة YYYY-MM-DD"],
        ["Template catalog is unavailable", "模板目录不可用", "El catálogo de plantillas no está disponible", "Vorlagenkatalog ist nicht verfügbar", "Каталог шаблонов недоступен", "Katalog template tidak tersedia", "टेम्पलेट कैटलॉग उपलब्ध नहीं है", "كتالوج القوالب غير متاح"],
        ["Could not save custom template", "无法保存自定义模板", "No se pudo guardar la plantilla personalizada", "Benutzerdefinierte Vorlage konnte nicht gespeichert werden", "Не удалось сохранить пользовательский шаблон", "Tidak dapat menyimpan template kustom", "कस्टम टेम्पलेट सहेजा नहीं जा सका", "تعذر حفظ القالب المخصص"],
        ["Kernel Identity", "内核身份", "Identidad del kernel", "Kernel-Identität", "Идентичность ядра", "Identitas Kernel", "कर्नेल पहचान", "هوية النواة"],
        ["Hook kernel name", "Hook 内核名称", "Interceptar nombre del kernel", "Kernel-Namen hooken", "Перехватывать имя ядра", "Hook nama kernel", "कर्नेल नाम हुक करें", "اعتراض اسم النواة"],
        ["GKI preset", "GKI 预设", "Preajuste GKI", "GKI-Voreinstellung", "Профиль GKI", "Preset GKI", "GKI प्रीसेट", "إعداد GKI مسبق"],
        ["uname release", "uname release", "release de uname", "uname release", "uname release", "uname release", "uname release", "uname release"],
        ["uname version", "uname version", "versión de uname", "uname version", "uname version", "uname version", "uname version", "uname version"],
        ["Save kernel identity", "保存内核身份", "Guardar identidad del kernel", "Kernel-Identität speichern", "Сохранить идентичность ядра", "Simpan identitas kernel", "कर्नेल पहचान सहेजें", "حفظ هوية النواة"],
        ["Kernel identity applied", "内核身份已应用", "Identidad del kernel aplicada", "Kernel-Identität angewendet", "Идентичность ядра применена", "Identitas kernel diterapkan", "कर्नेल पहचान लागू की गई", "تم تطبيق هوية النواة"],
        ["Kernel identity saved for next native activation", "内核身份已保存，将在下次原生激活时应用", "Identidad del kernel guardada para la próxima activación nativa", "Kernel-Identität für die nächste native Aktivierung gespeichert", "Идентичность ядра сохранена для следующей нативной активации", "Identitas kernel disimpan untuk aktivasi native berikutnya", "कर्नेल पहचान अगली नेटिव सक्रियता के लिए सहेजी गई", "تم حفظ هوية النواة للتفعيل الأصلي التالي"],
        ["Could not load kernel identity", "无法加载内核身份", "No se pudo cargar la identidad del kernel", "Kernel-Identität konnte nicht geladen werden", "Не удалось загрузить идентичность ядра", "Tidak dapat memuat identitas kernel", "कर्नेल पहचान लोड नहीं हो सकी", "تعذر تحميل هوية النواة"],
        ["Disabled by default. Core Binder protection is independent from this option.", "默认关闭。核心 Binder 保护独立于此选项。", "Desactivado de forma predeterminada. La protección Binder principal es independiente de esta opción.", "Standardmäßig deaktiviert. Der Binder-Kernschutz ist von dieser Option unabhängig.", "По умолчанию отключено. Основная защита Binder не зависит от этой опции.", "Dinonaktifkan secara default. Perlindungan inti Binder tidak bergantung pada opsi ini.", "डिफ़ॉल्ट रूप से बंद। मुख्य Binder सुरक्षा इस विकल्प से स्वतंत्र है।", "معطل افتراضيا. حماية Binder الأساسية مستقلة عن هذا الخيار."],
        ["Optionally overrides uname release/version inside the injected Keystore runtime. Official GKI presets use published base kernel versions and remain editable.", "可选地覆盖注入 Keystore 运行时中的 uname release/version。官方 GKI 预设使用已发布的基础内核版本并可编辑。", "Sustituye opcionalmente release/version de uname dentro del entorno Keystore inyectado. Los preajustes GKI oficiales usan versiones base publicadas y siguen siendo editables.", "Überschreibt optional uname release/version in der injizierten Keystore-Laufzeit. Offizielle GKI-Voreinstellungen verwenden veröffentlichte Basis-Kernelversionen und bleiben editierbar.", "При необходимости заменяет uname release/version во внедрённой среде Keystore. Официальные профили GKI используют опубликованные базовые версии ядра и остаются редактируемыми.", "Secara opsional mengganti uname release/version di runtime Keystore yang diinjeksi. Preset GKI resmi memakai versi kernel dasar yang dipublikasikan dan tetap dapat diedit.", "इंजेक्ट किए गए Keystore रनटाइम में uname release/version को वैकल्पिक रूप से बदलता है। आधिकारिक GKI प्रीसेट प्रकाशित बेस कर्नेल संस्करणों का उपयोग करते हैं और संपादन योग्य रहते हैं।", "يستبدل اختياريا uname release/version داخل بيئة Keystore المحقونة. تستخدم إعدادات GKI الرسمية إصدارات النواة الأساسية المنشورة وتظل قابلة للتحرير."],
        ["Custom", "自定义", "Personalizado", "Benutzerdefiniert", "Пользовательский", "Kustom", "कस्टम", "مخصص"],
        ["No servers configured. Add one below to fetch keyboxes automatically.", "未配置服务器。在下方添加一个以自动获取 keybox。", "No hay servidores configurados. Añada uno abajo para obtener keyboxes automáticamente.", "Keine Server konfiguriert. Fügen Sie unten einen hinzu, um Keyboxen automatisch abzurufen.", "Серверы не настроены. Добавьте один ниже, чтобы получать keybox автоматически.", "Tidak ada server yang dikonfigurasi. Tambahkan satu di bawah untuk mengambil keybox secara otomatis.", "कोई सर्वर कॉन्फ़िगर नहीं किया गया है। स्वचालित रूप से कीबॉक्स प्राप्त करने के लिए नीचे एक जोड़ें।", "لم يتم تكوين أي خوادم. أضف واحداً أدناه لجلب keyboxes تلقائياً."],
    ];

    for (const row of COMPLETE_CATALOG_ROWS) {
        COMPLETE_LOCALE_IDS.forEach((localeId, index) => {
            TRANSLATIONS[localeId][row[0]] = row[index + 1];
        });
    }

    const DYNAMIC_COPY = {
        tr: {
            targeted: 'Native çalışma zamanı {value} doğrulanmış keybox ile etkin. Hedefli mod açık olduğundan kapsamı uygulama kuralları belirler. Temel boot/TEE uyumluluğu Kimlik Motorundan bağımsız olarak etkin kalır; donanımsal bootloader ve güven kökü durumu gerçek kalır.',
            global: 'Native çalışma zamanı {value} doğrulanmış keybox ile etkin. Global uygulama kapsamı açık. Temel boot/TEE uyumluluğu Kimlik Motorundan bağımsız olarak etkin kalır; donanımsal bootloader ve güven kökü durumu gerçek kalır.',
            activeKeyboxes: '{value} etkin keybox', keysLoaded: '{value} anahtar yüklendi', configurationBytes: '{value} B yapılandırma',
            toggle: '{value} ayarını değiştir', remove: '{value} öğesini kaldır', current: '{value} (geçerli)', deleteProfile: '“{value}” profili silinsin mi?',
            identityReady: 'Kimlik hazır: {value}', updated: '{value} güncellendi', manualDate: '{value} elle tarihi YYYY-AA-GG biçiminde olmalıdır',
            logsSaved: 'Günlükler şuraya kaydedildi: {value}', templateSaved: 'Dil şablonu şuraya kaydedildi: {value}', error: 'Hata: {value}', failed: 'Başarısız: {value}',
            saveFailed: 'Kaydetme başarısız: {value}', downloadFailed: 'İndirme başarısız: {value}', loadLogsFailed: 'Günlükler yüklenemedi: {value}',
            templatesFailed: 'Şablonlar yüklenirken hata oluştu: {value}', identityFailed: 'Kimlik üretilirken hata oluştu: {value}', appConfigFailed: 'Uygulama yapılandırması kaydedilirken hata oluştu: {value}',
            profileApplied: '{value} profili uygulandı', policyUnavailable: 'Politika kontrolleri kullanılamıyor: {value}', backupSaved: 'Şifreli yedek şuraya kaydedildi: {value}'
        },
        'zh-CN': {
            targeted: '原生运行时已激活，包含 {value} 个已验证密钥盒。目标模式已开启，因此由应用规则决定范围。核心启动/TEE 兼容性独立于身份引擎保持启用；硬件 Bootloader 和信任根状态仍为真实状态。',
            global: '原生运行时已激活，包含 {value} 个已验证密钥盒。全局应用范围已开启。核心启动/TEE 兼容性独立于身份引擎保持启用；硬件 Bootloader 和信任根状态仍为真实状态。',
            activeKeyboxes: '{value} 个活动密钥盒', keysLoaded: '已加载 {value} 个密钥', configurationBytes: '{value} B 配置',
            toggle: '切换{value}', remove: '移除{value}', current: '{value}（当前）', deleteProfile: '删除配置档案“{value}”？',
            identityReady: '身份就绪：{value}', updated: '{value}已更新', manualDate: '{value}的手动日期必须为 YYYY-MM-DD',
            logsSaved: '日志已保存到：{value}', templateSaved: '语言模板已保存到：{value}', error: '错误：{value}', failed: '失败：{value}',
            saveFailed: '保存失败：{value}', downloadFailed: '下载失败：{value}', loadLogsFailed: '日志加载失败：{value}', templatesFailed: '加载模板时出错：{value}',
            identityFailed: '生成身份时出错：{value}', appConfigFailed: '保存应用配置时出错：{value}', profileApplied: '已应用配置档案 {value}',
            policyUnavailable: '策略控制不可用：{value}', backupSaved: '加密备份已保存到：{value}'
        },
        es: {
            targeted: 'El runtime nativo está activo con {value} keyboxes verificadas. El modo dirigido está activado, así que las reglas de apps determinan el ámbito. La compatibilidad principal de arranque/TEE permanece activa independientemente del Motor de identidad; el bootloader físico y la raíz de confianza siguen siendo reales.',
            global: 'El runtime nativo está activo con {value} keyboxes verificadas. El ámbito global de aplicaciones está activado. La compatibilidad principal de arranque/TEE permanece activa independientemente del Motor de identidad; el bootloader físico y la raíz de confianza siguen siendo reales.',
            activeKeyboxes: '{value} keyboxes activas', keysLoaded: '{value} claves cargadas', configurationBytes: '{value} B de configuración',
            toggle: 'Cambiar {value}', remove: 'Eliminar {value}', current: '{value} (actual)', deleteProfile: '¿Eliminar el perfil «{value}»?',
            identityReady: 'Identidad lista: {value}', updated: '{value} actualizado', manualDate: 'La fecha manual de {value} debe tener formato YYYY-MM-DD',
            logsSaved: 'Registros guardados en: {value}', templateSaved: 'Plantilla de idioma guardada en: {value}', error: 'Error: {value}', failed: 'Fallo: {value}',
            saveFailed: 'Error al guardar: {value}', downloadFailed: 'Error de descarga: {value}', loadLogsFailed: 'No se pudieron cargar los registros: {value}', templatesFailed: 'Error al cargar plantillas: {value}',
            identityFailed: 'Error al generar la identidad: {value}', appConfigFailed: 'Error al guardar la configuración de la app: {value}', profileApplied: 'Perfil {value} aplicado',
            policyUnavailable: 'Controles de política no disponibles: {value}', backupSaved: 'Copia cifrada guardada en: {value}'
        },
        de: {
            targeted: 'Die native Laufzeit ist mit {value} verifizierten Keyboxen aktiv. Der Zielmodus ist aktiviert; App-Regeln bestimmen den Bereich. Die zentrale Boot-/TEE-Kompatibilität bleibt unabhängig von der Identitäts-Engine aktiv; Hardware-Bootloader und Root of Trust bleiben echt.',
            global: 'Die native Laufzeit ist mit {value} verifizierten Keyboxen aktiv. Der globale App-Bereich ist aktiviert. Die zentrale Boot-/TEE-Kompatibilität bleibt unabhängig von der Identitäts-Engine aktiv; Hardware-Bootloader und Root of Trust bleiben echt.',
            activeKeyboxes: '{value} aktive Keyboxen', keysLoaded: '{value} Schlüssel geladen', configurationBytes: '{value} B Konfiguration',
            toggle: '{value} umschalten', remove: '{value} entfernen', current: '{value} (aktuell)', deleteProfile: 'Profil „{value}“ löschen?',
            identityReady: 'Identität bereit: {value}', updated: '{value} aktualisiert', manualDate: 'Das manuelle Datum für {value} muss YYYY-MM-DD entsprechen',
            logsSaved: 'Protokolle gespeichert unter: {value}', templateSaved: 'Sprachvorlage gespeichert unter: {value}', error: 'Fehler: {value}', failed: 'Fehlgeschlagen: {value}',
            saveFailed: 'Speichern fehlgeschlagen: {value}', downloadFailed: 'Download fehlgeschlagen: {value}', loadLogsFailed: 'Protokolle konnten nicht geladen werden: {value}', templatesFailed: 'Fehler beim Laden der Vorlagen: {value}',
            identityFailed: 'Fehler beim Erzeugen der Identität: {value}', appConfigFailed: 'Fehler beim Speichern der App-Konfiguration: {value}', profileApplied: 'Profil {value} angewendet',
            policyUnavailable: 'Richtliniensteuerung nicht verfügbar: {value}', backupSaved: 'Verschlüsseltes Backup gespeichert unter: {value}'
        },
        ru: {
            targeted: 'Среда native активна с {value} проверенными keybox. Целевой режим включён, поэтому область определяют правила приложений. Базовая совместимость boot/TEE работает независимо от движка идентичности; аппаратный загрузчик и корень доверия остаются подлинными.',
            global: 'Среда native активна с {value} проверенными keybox. Глобальная область приложений включена. Базовая совместимость boot/TEE работает независимо от движка идентичности; аппаратный загрузчик и корень доверия остаются подлинными.',
            activeKeyboxes: 'Активных keybox: {value}', keysLoaded: 'Загружено ключей: {value}', configurationBytes: 'Конфигурация: {value} Б',
            toggle: 'Переключить {value}', remove: 'Удалить {value}', current: '{value} (текущее)', deleteProfile: 'Удалить профиль «{value}»?',
            identityReady: 'Идентичность готова: {value}', updated: '{value} обновлено', manualDate: 'Дата вручную для {value} должна быть в формате YYYY-MM-DD',
            logsSaved: 'Логи сохранены в: {value}', templateSaved: 'Языковой шаблон сохранён в: {value}', error: 'Ошибка: {value}', failed: 'Сбой: {value}',
            saveFailed: 'Ошибка сохранения: {value}', downloadFailed: 'Ошибка скачивания: {value}', loadLogsFailed: 'Не удалось загрузить логи: {value}', templatesFailed: 'Ошибка загрузки шаблонов: {value}',
            identityFailed: 'Ошибка создания идентичности: {value}', appConfigFailed: 'Ошибка сохранения конфигурации приложения: {value}', profileApplied: 'Профиль {value} применён',
            policyUnavailable: 'Элементы политики недоступны: {value}', backupSaved: 'Зашифрованная резервная копия сохранена в: {value}'
        },
        id: {
            targeted: 'Runtime native aktif dengan {value} keybox terverifikasi. Mode tertarget aktif, sehingga aturan aplikasi menentukan cakupan. Kompatibilitas inti boot/TEE tetap aktif secara independen dari Mesin Identitas; bootloader perangkat keras dan root-of-trust tetap asli.',
            global: 'Runtime native aktif dengan {value} keybox terverifikasi. Cakupan aplikasi global aktif. Kompatibilitas inti boot/TEE tetap aktif secara independen dari Mesin Identitas; bootloader perangkat keras dan root-of-trust tetap asli.',
            activeKeyboxes: '{value} keybox aktif', keysLoaded: '{value} kunci dimuat', configurationBytes: 'Konfigurasi {value} B',
            toggle: 'Ubah {value}', remove: 'Hapus {value}', current: '{value} (saat ini)', deleteProfile: 'Hapus profil “{value}”?',
            identityReady: 'Identitas siap: {value}', updated: '{value} diperbarui', manualDate: 'Tanggal manual {value} harus berformat YYYY-MM-DD',
            logsSaved: 'Log disimpan ke: {value}', templateSaved: 'Templat bahasa disimpan ke: {value}', error: 'Kesalahan: {value}', failed: 'Gagal: {value}',
            saveFailed: 'Gagal menyimpan: {value}', downloadFailed: 'Gagal mengunduh: {value}', loadLogsFailed: 'Gagal memuat log: {value}', templatesFailed: 'Kesalahan saat memuat templat: {value}',
            identityFailed: 'Kesalahan saat menghasilkan identitas: {value}', appConfigFailed: 'Kesalahan saat menyimpan konfigurasi aplikasi: {value}', profileApplied: 'Profil {value} diterapkan',
            policyUnavailable: 'Kontrol kebijakan tidak tersedia: {value}', backupSaved: 'Cadangan terenkripsi disimpan ke: {value}'
        },
        hi: {
            targeted: 'Native runtime {value} सत्यापित keybox के साथ सक्रिय है। लक्षित मोड चालू है, इसलिए ऐप नियम दायरा तय करते हैं। मुख्य boot/TEE संगतता Identity Engine से स्वतंत्र रहती है; हार्डवेयर bootloader और root-of-trust वास्तविक रहते हैं।',
            global: 'Native runtime {value} सत्यापित keybox के साथ सक्रिय है। ग्लोबल ऐप दायरा चालू है। मुख्य boot/TEE संगतता Identity Engine से स्वतंत्र रहती है; हार्डवेयर bootloader और root-of-trust वास्तविक रहते हैं।',
            activeKeyboxes: '{value} सक्रिय keybox', keysLoaded: '{value} कुंजियाँ लोड हुईं', configurationBytes: '{value} B कॉन्फ़िगरेशन',
            toggle: '{value} बदलें', remove: '{value} हटाएँ', current: '{value} (वर्तमान)', deleteProfile: 'प्रोफ़ाइल “{value}” हटाएँ?',
            identityReady: 'पहचान तैयार: {value}', updated: '{value} अपडेट हुआ', manualDate: '{value} की मैन्युअल तारीख YYYY-MM-DD होनी चाहिए',
            logsSaved: 'लॉग यहाँ सहेजे गए: {value}', templateSaved: 'भाषा टेम्पलेट यहाँ सहेजा गया: {value}', error: 'त्रुटि: {value}', failed: 'विफल: {value}',
            saveFailed: 'सहेजना विफल: {value}', downloadFailed: 'डाउनलोड विफल: {value}', loadLogsFailed: 'लॉग लोड नहीं हुए: {value}', templatesFailed: 'टेम्पलेट लोड करने में त्रुटि: {value}',
            identityFailed: 'पहचान बनाने में त्रुटि: {value}', appConfigFailed: 'ऐप कॉन्फ़िगरेशन सहेजने में त्रुटि: {value}', profileApplied: 'प्रोफ़ाइल {value} लागू हुई',
            policyUnavailable: 'नीति नियंत्रण उपलब्ध नहीं: {value}', backupSaved: 'एन्क्रिप्टेड बैकअप यहाँ सहेजा गया: {value}'
        },
        ar: {
            targeted: 'وقت تشغيل native نشط مع {value} من Keybox الموثقة. الوضع المستهدف مفعّل، لذلك تحدد قواعد التطبيقات النطاق. يبقى توافق boot/TEE الأساسي مستقلا عن محرك الهوية؛ وتبقى حالة bootloader المادي وجذر الثقة حقيقية.',
            global: 'وقت تشغيل native نشط مع {value} من Keybox الموثقة. نطاق التطبيقات العام مفعّل. يبقى توافق boot/TEE الأساسي مستقلا عن محرك الهوية؛ وتبقى حالة bootloader المادي وجذر الثقة حقيقية.',
            activeKeyboxes: '{value} Keybox نشطة', keysLoaded: 'تم تحميل {value} مفاتيح', configurationBytes: 'إعداد بحجم {value} B',
            toggle: 'تبديل {value}', remove: 'إزالة {value}', current: '{value} (الحالي)', deleteProfile: 'حذف الملف الشخصي «{value}»؟',
            identityReady: 'الهوية جاهزة: {value}', updated: 'تم تحديث {value}', manualDate: 'يجب أن يكون التاريخ اليدوي لـ {value} بالصيغة YYYY-MM-DD',
            logsSaved: 'حفظت السجلات في: {value}', templateSaved: 'حفظ قالب اللغة في: {value}', error: 'خطأ: {value}', failed: 'فشل: {value}',
            saveFailed: 'فشل الحفظ: {value}', downloadFailed: 'فشل التنزيل: {value}', loadLogsFailed: 'تعذر تحميل السجلات: {value}', templatesFailed: 'خطأ في تحميل القوالب: {value}',
            identityFailed: 'خطأ في إنشاء الهوية: {value}', appConfigFailed: 'خطأ في حفظ إعداد التطبيق: {value}', profileApplied: 'تم تطبيق الملف الشخصي {value}',
            policyUnavailable: 'عناصر السياسة غير متاحة: {value}', backupSaved: 'حفظت النسخة الاحتياطية المشفرة في: {value}'
        }
    };

    const GUIDE = {
        en: [
            ['Dashboard and core protection', 'Dashboard is the single source of truth for Global Mode, DRM compatibility, Identity, Security Patch and keybox health. Core Keystore/TEE and verified-boot compatibility stay independent from optional Identity controls.'],
            ['Identity and Auto Identity', 'Identity changes only configured app-visible build, attestation and telephony fields. Auto Identity first tries bounded Google Pixel metadata and falls back to a local verified Pixel template when the remote source is unavailable.'],
            ['KeyMint and TEE', 'KeyMint keeps private-key operations on the genuine platform security level. CleveresTricky only rewrites the returned attestation certificate chain for eligible application UIDs; hardware root-of-trust state is not physically changed.'],
            ['Keyboxes', 'Use only authorized XML or CBOX material. Keyboxes are validated before activation, mirrored into the managed keybox directory, checked against revocation information and selected per profile when configured.'],
            ['Profiles', 'Profiles group app assignments, an identity template, a keybox, privacy behavior and feature overrides. Exact package rules take priority over wildcard rules.'],
            ['Apps and Effective State', 'Use Apps to add or remove package rules. Effective State is embedded below the app controls so you can inspect the exact resolved profile, keybox, KeyMint and patch policy for one package.'],
            ['Security Patch', 'Security Patch is independent from Identity. Device-default preserves captured values, automatic resolves calendar-based policy, and manual mode accepts an explicit date.'],
            ['DRM', 'DRM Passthrough keeps configured playback or DRM packages on the genuine Keystore path. Per-app identifier isolation affects only supported privacy identifiers and does not fake a DRM security level.'],
            ['RKP', 'RKP is no longer a user-facing switch. Remote-provisioning infrastructure UIDs are always protected, while generated-key and stored-key certificate paths stay coherent.'],
            ['Runtime health and upgrades', 'Runtime Health reports the live interceptor and verified-keybox state. Upgrade migration repairs stale profile references, retires obsolete switches and keeps a recoverable copy instead of requiring a full settings reset.'],
            ['Logs and debug mode', 'Normal releases keep extra debug logging off. Enable Debug Logging temporarily from Logs when diagnosing a problem, reproduce it, collect the logs, then turn it off again.'],
            ['Performance', 'Global Mode uses bounded caches and avoids permanent WebUI polling. Certificate rewrite caches are deliberately small, and timing-sensitive work is kept off repeated reads whenever possible.']
        ],
        tr: [
            ['Gösterge Paneli ve temel koruma', 'Gösterge Paneli; Global Mod, DRM uyumluluğu, Kimlik, Güvenlik Yaması ve keybox sağlığı için tek doğruluk kaynağıdır. Temel Keystore/TEE ve verified-boot uyumluluğu isteğe bağlı Kimlik kontrollerinden bağımsızdır.'],
            ['Kimlik ve Otomatik Kimlik', 'Kimlik yalnızca yapılandırılmış uygulamalara görünen build, attestation ve telefon alanlarını değiştirir. Otomatik Kimlik önce süre sınırlandırılmış Google Pixel verisini dener; kaynak yoksa yerel doğrulanmış Pixel şablonuna döner.'],
            ['KeyMint ve TEE', 'KeyMint özel anahtar işlemlerini gerçek platform güvenlik seviyesinde tutar. CleveresTricky yalnızca uygun uygulama UIDlerine dönen attestation sertifika zincirini yeniden yazar; donanımsal root-of-trust fiziksel olarak değişmez.'],
            ['Keyboxlar', 'Yalnızca yetkili XML veya CBOX materyali kullanın. Keyboxlar etkinleşmeden önce doğrulanır, yönetilen dizine eşlenir, iptal bilgisine göre kontrol edilir ve gerektiğinde profile göre seçilir.'],
            ['Profiller', 'Profiller uygulama atamalarını, kimlik şablonunu, keyboxı, gizlilik davranışını ve özellik geçersiz kılmalarını bir arada tutar. Tam paket kuralları wildcard kurallarından önceliklidir.'],
            ['Uygulamalar ve Etkin Durum', 'Paket kurallarını Uygulamalar bölümünden yönetin. Etkin Durum aynı sayfada çözülmüş profil, keybox, KeyMint ve yama politikasını gösterir.'],
            ['Güvenlik Yaması', 'Güvenlik Yaması Kimlikten bağımsızdır. Device-default yakalanan değerleri korur, otomatik mod takvime göre çözer, manuel mod açık bir tarih kabul eder.'],
            ['DRM', 'DRM Geçiş Modu yapılandırılmış oynatma/DRM paketlerini gerçek Keystore yolunda bırakır. Uygulama bazlı kimlik izolasyonu yalnızca desteklenen gizlilik kimliklerini etkiler; DRM güvenlik seviyesini taklit etmez.'],
            ['RKP', 'RKP artık kullanıcıya açık bir anahtar değildir. Remote provisioning altyapı UIDleri daima korunur ve üretilen/saklanan anahtar sertifika yolları tutarlı kalır.'],
            ['Çalışma durumu ve yükseltmeler', 'Çalışma Durumu canlı interceptor ve doğrulanmış keybox durumunu gösterir. Yükseltme geçişi eski profil referanslarını onarır, kullanımdan kalkmış anahtarları temizler ve tam sıfırlama yerine kurtarılabilir ayarları korur.'],
            ['Günlükler ve debug modu', 'Normal sürümde ekstra debug logları kapalıdır. Sorun incelerken Logs bölümünden geçici olarak açın, sorunu tekrar üretin, logları alın ve sonra kapatın.'],
            ['Performans', 'Global Mod sınırlı önbellekler kullanır ve kalıcı WebUI polling yapmaz. Sertifika yeniden-yazım cachei küçük tutulur; timing hassas işler tekrarlanan okumaların dışına alınır.']
        ],
        'zh-CN': [
            ['仪表盘与核心保护', '仪表盘统一管理全局模式、DRM 兼容、身份、安全补丁与密钥盒状态。核心 Keystore/TEE 与 verified-boot 兼容逻辑独立于可选身份功能。'],
            ['身份与自动身份', '身份功能仅修改配置应用可见的 build、认证和电话字段。自动身份优先使用有严格超时的 Google Pixel 元数据，远端不可用时回退到本地已验证 Pixel 模板。'],
            ['KeyMint 与 TEE', 'KeyMint 的私钥运算仍在真实平台安全级别执行。CleveresTricky 只针对符合条件的应用 UID 重写返回的认证证书链，不会物理改变硬件 root-of-trust。'],
            ['密钥盒', '仅使用获得授权的 XML 或 CBOX。启用前会验证密钥盒，将其同步到受管目录，执行吊销检查，并可按配置档案选择。'],
            ['配置档案', '配置档案可组合应用分配、身份模板、密钥盒、隐私行为与功能覆盖。精确包名优先于通配规则。'],
            ['应用与有效状态', '在“应用”中管理包规则。有效状态已放在同一页面，可查看某个包最终解析到的配置档案、密钥盒、KeyMint 与补丁策略。'],
            ['安全补丁', '安全补丁独立于身份功能。设备默认值保留捕获值；自动模式按日期策略解析；手动模式使用明确日期。'],
            ['DRM', 'DRM 直通让指定播放/DRM 包继续使用真实 Keystore 路径。应用级标识符隔离只影响支持的隐私标识符，不伪造 DRM 安全级别。'],
            ['RKP', 'RKP 不再提供用户开关。远程配置基础设施 UID 始终受保护，生成密钥与已存密钥的证书路径保持一致。'],
            ['运行状态与升级', '运行状态显示实时拦截器与已验证密钥盒状态。升级迁移会修复过期引用、移除废弃开关并尽量保留可恢复配置。'],
            ['日志与调试模式', '正式版本默认关闭额外调试日志。排查时可在“日志”中临时开启，复现问题并收集日志后再关闭。'],
            ['性能', '全局模式使用有界缓存，不进行永久 WebUI 轮询。证书重写缓存保持较小，并尽量减少计时敏感的重复工作。']
        ],
        es: [
            ['Panel y protección principal', 'El Panel centraliza Modo global, compatibilidad DRM, Identidad, Parche de seguridad y salud de keyboxes. Keystore/TEE y verified boot siguen independientes de Identidad.'],
            ['Identidad e Identidad automática', 'Solo se modifican campos visibles para las apps configuradas. Auto Identity usa primero metadatos Pixel con límites estrictos y recurre a una plantilla Pixel local verificada si la fuente remota falla.'],
            ['KeyMint y TEE', 'Las operaciones de clave privada permanecen en el nivel de seguridad real de la plataforma. Solo se reescribe la cadena de certificados de atestación devuelta a UIDs de apps elegibles.'],
            ['Keyboxes', 'Usa únicamente XML o CBOX autorizados. Se validan antes de activarse, se sincronizan con el directorio administrado y pueden seleccionarse por perfil.'],
            ['Perfiles', 'Los perfiles agrupan apps, plantilla de identidad, keybox, privacidad y ajustes de funciones. Las reglas exactas tienen prioridad sobre comodines.'],
            ['Apps y Estado efectivo', 'Gestiona reglas desde Apps. El Estado efectivo está integrado allí para mostrar perfil, keybox, KeyMint y política de parches resueltos.'],
            ['Parche de seguridad', 'Es independiente de Identidad. El valor del dispositivo conserva lo capturado, Automático resuelve por calendario y Manual acepta una fecha explícita.'],
            ['DRM', 'DRM Passthrough mantiene los paquetes configurados en la ruta Keystore genuina. El aislamiento de identificadores no falsifica el nivel de seguridad DRM.'],
            ['RKP', 'RKP ya no es un interruptor de usuario. Los UIDs de aprovisionamiento remoto siempre están protegidos y las rutas de certificados permanecen coherentes.'],
            ['Estado y actualizaciones', 'Runtime Health muestra el estado real. La migración corrige referencias obsoletas y retira opciones antiguas sin exigir un reinicio total de ajustes.'],
            ['Logs y depuración', 'Las versiones normales desactivan el log extra. Actívalo temporalmente desde Logs para diagnosticar y vuelve a desactivarlo al terminar.'],
            ['Rendimiento', 'Modo global usa cachés acotadas y evita sondeo WebUI permanente. La caché de certificados se mantiene pequeña para controlar RAM.']
        ],
        de: [
            ['Übersicht und Kernschutz', 'Die Übersicht ist die zentrale Quelle für Globalen Modus, DRM, Identität, Sicherheitspatches und Keybox-Status. Keystore/TEE bleibt von optionalen Identitätsfunktionen unabhängig.'],
            ['Identität und Auto-Identität', 'Nur für konfigurierte Apps sichtbare Build-, Attestierungs- und Telefoniefelder werden geändert. Bei Ausfall der begrenzten Pixel-Onlinequelle wird eine lokale geprüfte Pixel-Vorlage verwendet.'],
            ['KeyMint und TEE', 'Private Schlüsseloperationen bleiben auf der echten Plattform-Sicherheitsstufe. Nur die zurückgegebene Attestierungs-Zertifikatskette für geeignete App-UIDs wird angepasst.'],
            ['Keyboxen', 'Nur autorisiertes XML/CBOX verwenden. Keyboxen werden vor Aktivierung geprüft, in das verwaltete Verzeichnis gespiegelt und können pro Profil gewählt werden.'],
            ['Profile', 'Profile bündeln App-Zuordnung, Identitätsvorlage, Keybox, Datenschutz und Funktions-Overrides. Exakte Paketregeln haben Vorrang vor Wildcards.'],
            ['Apps und effektiver Zustand', 'Paketregeln werden unter Apps verwaltet. Der effektive Zustand zeigt dort das tatsächlich aufgelöste Profil, Keybox, KeyMint und Patch-Regeln.'],
            ['Sicherheitspatch', 'Unabhängig von Identität. Device Default bewahrt erfasste Werte, Automatisch löst kalenderbasiert auf, Manuell nutzt ein festes Datum.'],
            ['DRM', 'DRM-Durchleitung hält konfigurierte Wiedergabe-/DRM-Pakete auf dem echten Keystore-Pfad. Die Kennungsisolierung fälscht keine DRM-Sicherheitsstufe.'],
            ['RKP', 'RKP ist kein Benutzer-Schalter mehr. Remote-Provisioning-UIDs bleiben immer geschützt und Zertifikatspfade konsistent.'],
            ['Laufzeit und Upgrades', 'Runtime Health zeigt den Live-Zustand. Die Migration repariert veraltete Referenzen und entfernt alte Schalter, ohne pauschal alle Einstellungen zu löschen.'],
            ['Logs und Debug', 'Zusätzliche Debug-Logs sind in normalen Releases aus. Nur zur Diagnose unter Logs temporär einschalten und danach wieder deaktivieren.'],
            ['Leistung', 'Globaler Modus nutzt begrenzte Caches ohne dauerhaftes WebUI-Polling. Der Zertifikat-Cache bleibt bewusst klein.']
        ],
        ru: [
            ['Панель и основная защита', 'Панель является единым источником настроек Global Mode, DRM, Identity, Security Patch и состояния keybox. Основной Keystore/TEE работает независимо от Identity.'],
            ['Identity и Auto Identity', 'Меняются только видимые настроенным приложениям поля build, аттестации и телефонии. При недоступности ограниченного по времени источника Pixel используется локальный проверенный шаблон Pixel.'],
            ['KeyMint и TEE', 'Операции закрытого ключа остаются на реальном уровне безопасности платформы. Для подходящих UID приложений меняется только возвращаемая цепочка сертификатов аттестации.'],
            ['Keybox', 'Используйте только разрешённые XML/CBOX. Они проверяются до активации, синхронизируются с управляемым каталогом и могут назначаться профилям.'],
            ['Профили', 'Профиль объединяет приложения, шаблон Identity, keybox, приватность и переопределения функций. Точные правила пакетов важнее wildcard.'],
            ['Приложения и эффективное состояние', 'Правила пакетов управляются в Apps. Там же показывается итоговый профиль, keybox, KeyMint и политика патчей.'],
            ['Security Patch', 'Не зависит от Identity. Device Default сохраняет полученные значения, Automatic выбирает по календарю, Manual использует указанную дату.'],
            ['DRM', 'DRM Passthrough оставляет указанные DRM/медиа-пакеты на настоящем пути Keystore. Изоляция идентификаторов не подделывает уровень безопасности DRM.'],
            ['RKP', 'RKP больше не имеет пользовательского переключателя. UID инфраструктуры remote provisioning всегда защищены, а пути сертификатов согласованы.'],
            ['Состояние и обновления', 'Runtime Health показывает реальное состояние. Миграция исправляет устаревшие ссылки и удаляет старые переключатели без полного сброса настроек.'],
            ['Логи и debug', 'Дополнительные debug-логи в обычном релизе выключены. Включайте их временно в Logs только для диагностики.'],
            ['Производительность', 'Global Mode использует ограниченные кеши и не запускает постоянный WebUI polling. Кеш переписанных сертификатов намеренно небольшой.']
        ],
        id: [
            ['Dasbor dan perlindungan inti', 'Dasbor menjadi sumber utama untuk Mode Global, kompatibilitas DRM, Identitas, Patch Keamanan, dan kesehatan keybox. Keystore/TEE inti tetap independen dari kontrol Identitas opsional.'],
            ['Identitas dan Identitas Otomatis', 'Identitas hanya mengubah field build, attestasi, dan telepon yang terlihat oleh aplikasi yang dikonfigurasi. Auto Identity mencoba metadata Pixel dengan batas waktu lalu memakai templat Pixel lokal terverifikasi jika sumber jarak jauh gagal.'],
            ['KeyMint dan TEE', 'Operasi kunci privat tetap berjalan pada tingkat keamanan platform yang asli. CleveresTricky hanya menulis ulang rantai sertifikat attestasi yang dikembalikan untuk UID aplikasi yang memenuhi syarat.'],
            ['Keybox', 'Gunakan hanya XML atau CBOX yang berwenang. Keybox diverifikasi sebelum aktif, disalin ke direktori terkelola, diperiksa terhadap informasi pencabutan, dan dapat dipilih per profil.'],
            ['Profil', 'Profil menggabungkan penugasan aplikasi, templat identitas, keybox, perilaku privasi, dan override fitur. Aturan nama paket yang tepat lebih diprioritaskan daripada wildcard.'],
            ['Aplikasi dan Status Efektif', 'Kelola aturan paket dari Aplikasi. Status Efektif di halaman yang sama menunjukkan profil, keybox, KeyMint, dan kebijakan patch yang benar-benar digunakan.'],
            ['Patch Keamanan', 'Patch Keamanan terpisah dari Identitas. Device Default mempertahankan nilai yang ditangkap, mode otomatis memakai kebijakan kalender, dan mode manual menerima tanggal tertentu.'],
            ['DRM', 'DRM Passthrough menjaga paket media/DRM yang dipilih pada jalur Keystore asli. Isolasi identifier per aplikasi tidak memalsukan tingkat keamanan DRM.'],
            ['RKP', 'RKP tidak lagi menjadi sakelar pengguna. UID infrastruktur remote provisioning selalu dilindungi dan jalur sertifikat tetap konsisten.'],
            ['Kesehatan runtime dan upgrade', 'Runtime Health menampilkan keadaan interceptor dan keybox terverifikasi secara langsung. Migrasi upgrade memperbaiki referensi lama dan mempertahankan salinan yang dapat dipulihkan.'],
            ['Log dan mode debug', 'Rilis normal mematikan log debug tambahan. Aktifkan sementara dari Log saat mendiagnosis, kumpulkan log, lalu matikan kembali.'],
            ['Performa', 'Mode Global memakai cache terbatas dan tidak melakukan polling WebUI permanen. Cache penulisan ulang sertifikat sengaja dibuat kecil untuk mengontrol RAM.']
        ],
        hi: [
            ['डैशबोर्ड और मुख्य सुरक्षा', 'डैशबोर्ड ग्लोबल मोड, DRM संगतता, पहचान, सुरक्षा पैच और कीबॉक्स स्वास्थ्य के लिए मुख्य नियंत्रण स्थान है। मुख्य Keystore/TEE वैकल्पिक पहचान नियंत्रणों से स्वतंत्र रहता है।'],
            ['पहचान और ऑटो पहचान', 'पहचान केवल कॉन्फ़िगर किए गए ऐप्स को दिखने वाले build, अटेस्टेशन और टेलीफोनी फील्ड बदलती है। Auto Identity सीमित समय वाले Pixel मेटाडेटा को आजमाता है और विफल होने पर सत्यापित स्थानीय Pixel टेम्पलेट का उपयोग करता है।'],
            ['KeyMint और TEE', 'निजी कुंजी के ऑपरेशन वास्तविक प्लेटफॉर्म सुरक्षा स्तर पर ही रहते हैं। CleveresTricky केवल योग्य ऐप UID को लौटाई गई अटेस्टेशन सर्टिफिकेट चेन को फिर से लिखता है।'],
            ['कीबॉक्स', 'केवल अधिकृत XML या CBOX सामग्री का उपयोग करें। सक्रिय करने से पहले कीबॉक्स सत्यापित होते हैं, प्रबंधित डायरेक्टरी में रखे जाते हैं और रिवोकेशन जानकारी से जांचे जाते हैं।'],
            ['प्रोफाइल', 'प्रोफाइल ऐप असाइनमेंट, पहचान टेम्पलेट, कीबॉक्स, गोपनीयता व्यवहार और फीचर ओवरराइड को एक साथ रखते हैं। सटीक पैकेज नियम wildcard से पहले लागू होते हैं।'],
            ['ऐप्स और प्रभावी स्थिति', 'ऐप्स में पैकेज नियम जोड़ें या हटाएं। प्रभावी स्थिति उसी पेज पर वास्तविक प्रोफाइल, कीबॉक्स, KeyMint और पैच नीति दिखाती है।'],
            ['सुरक्षा पैच', 'सुरक्षा पैच पहचान से स्वतंत्र है। Device Default कैप्चर किए गए मान रखता है, Automatic कैलेंडर नीति से तय करता है और Manual निश्चित तारीख स्वीकार करता है।'],
            ['DRM', 'DRM Passthrough चुने हुए मीडिया/DRM पैकेजों को वास्तविक Keystore पथ पर रखता है। प्रति-ऐप पहचान अलगाव DRM सुरक्षा स्तर को नकली नहीं बनाता।'],
            ['RKP', 'RKP अब उपयोगकर्ता स्विच नहीं है। remote provisioning इंफ्रास्ट्रक्चर UID हमेशा सुरक्षित रहते हैं और सर्टिफिकेट पथ संगत रहते हैं।'],
            ['रनटाइम स्थिति और अपग्रेड', 'Runtime Health लाइव interceptor और सत्यापित कीबॉक्स स्थिति दिखाता है। अपग्रेड माइग्रेशन पुराने संदर्भ ठीक करता है और पूर्ण रीसेट के बजाय पुनर्प्राप्ति योग्य कॉपी रखता है।'],
            ['लॉग और डीबग मोड', 'सामान्य रिलीज में अतिरिक्त डीबग लॉग बंद रहते हैं। जांच के समय Logs से थोड़ी देर के लिए चालू करें, समस्या दोहराएं, लॉग लें और फिर बंद करें।'],
            ['प्रदर्शन', 'Global Mode सीमित cache का उपयोग करता है और स्थायी WebUI polling नहीं करता। RAM नियंत्रित रखने के लिए certificate rewrite cache छोटा रखा जाता है।']
        ],
        ar: [
            ['لوحة التحكم والحماية الأساسية', 'لوحة التحكم هي المصدر الرئيسي لإعدادات الوضع العام وDRM والهوية وتصحيح الأمان وحالة صناديق المفاتيح. تبقى وظائف Keystore وTEE الأساسية مستقلة عن خيارات الهوية الإضافية.'],
            ['الهوية والهوية التلقائية', 'تغير الهوية فقط حقول build والتصديق والهاتف المرئية للتطبيقات التي تم إعدادها. تحاول الهوية التلقائية بيانات Pixel بمهلة محدودة ثم تستخدم قالب Pixel محليا موثقا إذا تعذر المصدر البعيد.'],
            ['KeyMint وTEE', 'تبقى عمليات المفتاح الخاص على مستوى الأمان الحقيقي للمنصة. يعيد CleveresTricky كتابة سلسلة شهادات التصديق التي تعاد إلى معرفات UID المؤهلة فقط.'],
            ['صناديق المفاتيح', 'استخدم فقط مواد XML أو CBOX المصرح بها. يتم التحقق منها قبل التفعيل ونسخها إلى الدليل المدار وفحصها مقابل معلومات الإبطال ويمكن اختيارها حسب الملف الشخصي.'],
            ['الملفات الشخصية', 'يجمع الملف الشخصي تعيينات التطبيقات وقالب الهوية وصندوق المفاتيح وسلوك الخصوصية وتجاوزات الميزات. قواعد اسم الحزمة الدقيقة لها أولوية على wildcard.'],
            ['التطبيقات والحالة الفعلية', 'أدر قواعد الحزم من صفحة التطبيقات. تعرض الحالة الفعلية في الصفحة نفسها الملف الشخصي وصندوق المفاتيح وKeyMint وسياسة التصحيح المستخدمة فعليا.'],
            ['تصحيح الأمان', 'تصحيح الأمان مستقل عن الهوية. يحافظ Device Default على القيم الملتقطة ويعتمد Automatic سياسة التقويم ويقبل Manual تاريخا صريحا.'],
            ['DRM', 'يبقي DRM Passthrough حزم الوسائط وDRM المحددة على مسار Keystore الحقيقي. لا يؤدي عزل المعرفات لكل تطبيق إلى تزييف مستوى أمان DRM.'],
            ['RKP', 'لم يعد RKP مفتاحا للمستخدم. تظل معرفات UID الخاصة ببنية remote provisioning محمية دائما وتبقى مسارات الشهادات متناسقة.'],
            ['حالة التشغيل والترقيات', 'يعرض Runtime Health حالة interceptor وصندوق المفاتيح الموثق مباشرة. تصلح عملية الترقية المراجع القديمة وتحافظ على نسخة قابلة للاسترداد بدلا من طلب إعادة ضبط كاملة.'],
            ['السجلات ووضع التصحيح', 'تبقي الإصدارات العادية سجلات التصحيح الإضافية متوقفة. فعلها مؤقتا من السجلات أثناء التشخيص ثم أوقفها بعد جمع السجلات.'],
            ['الأداء', 'يستخدم الوضع العام ذاكرات cache محدودة ولا ينفذ polling دائما للواجهة. يتم إبقاء cache إعادة كتابة الشهادات صغيرة للتحكم في استهلاك RAM.']
        ]
    };

    const OWNED_COPY = {
        en: {
            backupPassword: 'Backup Password',
            backupHint: 'Required, at least 12 characters.',
            backupPlaceholder: 'Enter a strong backup password',
            show: 'Show', hide: 'Hide',
            exportSettings: 'Export Encrypted Settings', importSettings: 'Import Encrypted Settings', synchronizeRuntime: 'Synchronize Runtime',
            keyboxHubCopy: 'Get an API key for the recommended remote server here.', getApiKey: 'Get API Key',
            diagnosticsTitle: 'Support Diagnostics', diagnosticsHint: 'Copy a bounded support snapshot without logs, package names, keybox names, identity values, credentials, or key material.',
            copyDiagnostics: 'Copy Diagnostics', diagnosticsCollecting: 'Collecting...', diagnosticsCopied: 'Diagnostics copied', diagnosticsFailed: 'Unable to collect diagnostics'
        },
        tr: {
            backupPassword: 'Yedekleme Parolası',
            backupHint: 'Zorunlu, en az 12 karakter.',
            backupPlaceholder: 'Güçlü bir yedekleme parolası girin',
            show: 'Göster', hide: 'Gizle',
            exportSettings: 'Şifreli Ayarları Dışa Aktar', importSettings: 'Şifreli Ayarları İçe Aktar', synchronizeRuntime: 'Çalışma Zamanını Eşitle',
            keyboxHubCopy: "Önerilen remote server için API key'i bu adresten alabilirsiniz.", getApiKey: 'API Key Al',
            diagnosticsTitle: 'Destek Tanılaması', diagnosticsHint: 'Log, paket adı, keybox adı, kimlik değeri, kimlik bilgisi veya anahtar materyali içermeyen sınırlı bir destek özeti kopyalayın.',
            copyDiagnostics: 'Tanılamayı Kopyala', diagnosticsCollecting: 'Toplanıyor...', diagnosticsCopied: 'Tanılama kopyalandı', diagnosticsFailed: 'Tanılama toplanamadı'
        },
        'zh-CN': {
            backupPassword: '备份密码', backupHint: '必填，至少 12 个字符。', backupPlaceholder: '输入一个高强度备份密码', show: '显示', hide: '隐藏',
            exportSettings: '导出加密设置', importSettings: '导入加密设置', synchronizeRuntime: '同步运行时',
            keyboxHubCopy: '可在此获取推荐远程服务器所需的 API 密钥。', getApiKey: '获取 API 密钥',
            diagnosticsTitle: '支持诊断', diagnosticsHint: '复制有界支持摘要，不包含日志、包名、密钥盒名称、身份值、凭据或密钥材料。',
            copyDiagnostics: '复制诊断', diagnosticsCollecting: '正在收集…', diagnosticsCopied: '诊断已复制', diagnosticsFailed: '无法收集诊断'
        },
        es: {
            backupPassword: 'Contraseña de respaldo', backupHint: 'Obligatoria, mínimo 12 caracteres.', backupPlaceholder: 'Introduce una contraseña de respaldo segura', show: 'Mostrar', hide: 'Ocultar',
            exportSettings: 'Exportar ajustes cifrados', importSettings: 'Importar ajustes cifrados', synchronizeRuntime: 'Sincronizar runtime',
            keyboxHubCopy: 'Obtén aquí una clave API para el servidor remoto recomendado.', getApiKey: 'Obtener clave API',
            diagnosticsTitle: 'Diagnóstico de soporte', diagnosticsHint: 'Copia un resumen acotado sin registros, nombres de paquetes o keyboxes, valores de identidad, credenciales ni material de claves.',
            copyDiagnostics: 'Copiar diagnóstico', diagnosticsCollecting: 'Recopilando...', diagnosticsCopied: 'Diagnóstico copiado', diagnosticsFailed: 'No se pudo recopilar el diagnóstico'
        },
        de: {
            backupPassword: 'Backup-Passwort', backupHint: 'Erforderlich, mindestens 12 Zeichen.', backupPlaceholder: 'Ein sicheres Backup-Passwort eingeben', show: 'Anzeigen', hide: 'Ausblenden',
            exportSettings: 'Verschlüsselte Einstellungen exportieren', importSettings: 'Verschlüsselte Einstellungen importieren', synchronizeRuntime: 'Laufzeit synchronisieren',
            keyboxHubCopy: 'Hier erhältst du einen API-Schlüssel für den empfohlenen Remote-Server.', getApiKey: 'API-Schlüssel abrufen',
            diagnosticsTitle: 'Support-Diagnose', diagnosticsHint: 'Kopiert eine begrenzte Support-Zusammenfassung ohne Logs, Paket- oder Keybox-Namen, Identitätswerte, Zugangsdaten oder Schlüsselmaterial.',
            copyDiagnostics: 'Diagnose kopieren', diagnosticsCollecting: 'Wird erfasst...', diagnosticsCopied: 'Diagnose kopiert', diagnosticsFailed: 'Diagnose konnte nicht erfasst werden'
        },
        ru: {
            backupPassword: 'Пароль резервной копии', backupHint: 'Обязательно, не менее 12 символов.', backupPlaceholder: 'Введите надежный пароль резервной копии', show: 'Показать', hide: 'Скрыть',
            exportSettings: 'Экспортировать зашифрованные настройки', importSettings: 'Импортировать зашифрованные настройки', synchronizeRuntime: 'Синхронизировать среду',
            keyboxHubCopy: 'Здесь можно получить API-ключ для рекомендуемого удаленного сервера.', getApiKey: 'Получить API-ключ',
            diagnosticsTitle: 'Диагностика поддержки', diagnosticsHint: 'Копирует ограниченную сводку без логов, имен пакетов и keybox, значений идентичности, учетных данных или ключевого материала.',
            copyDiagnostics: 'Копировать диагностику', diagnosticsCollecting: 'Сбор данных...', diagnosticsCopied: 'Диагностика скопирована', diagnosticsFailed: 'Не удалось собрать диагностику'
        },
        id: {
            backupPassword: 'Kata Sandi Cadangan', backupHint: 'Wajib, minimal 12 karakter.', backupPlaceholder: 'Masukkan kata sandi cadangan yang kuat', show: 'Tampilkan', hide: 'Sembunyikan',
            exportSettings: 'Ekspor Pengaturan Terenkripsi', importSettings: 'Impor Pengaturan Terenkripsi', synchronizeRuntime: 'Sinkronkan Runtime',
            keyboxHubCopy: 'Dapatkan API key untuk remote server yang direkomendasikan di sini.', getApiKey: 'Dapatkan API Key',
            diagnosticsTitle: 'Diagnostik Dukungan', diagnosticsHint: 'Salin ringkasan dukungan terbatas tanpa log, nama paket atau keybox, nilai identitas, kredensial, atau material kunci.',
            copyDiagnostics: 'Salin Diagnostik', diagnosticsCollecting: 'Mengumpulkan...', diagnosticsCopied: 'Diagnostik disalin', diagnosticsFailed: 'Diagnostik tidak dapat dikumpulkan'
        },
        hi: {
            backupPassword: 'बैकअप पासवर्ड', backupHint: 'आवश्यक, कम से कम 12 अक्षर।', backupPlaceholder: 'एक मजबूत बैकअप पासवर्ड दर्ज करें', show: 'दिखाएं', hide: 'छिपाएं',
            exportSettings: 'एन्क्रिप्टेड सेटिंग्स निर्यात करें', importSettings: 'एन्क्रिप्टेड सेटिंग्स आयात करें', synchronizeRuntime: 'रनटाइम सिंक करें',
            keyboxHubCopy: 'अनुशंसित रिमोट सर्वर के लिए API key यहां प्राप्त करें।', getApiKey: 'API Key प्राप्त करें',
            diagnosticsTitle: 'सहायता निदान', diagnosticsHint: 'Logs, package या keybox नाम, identity values, credentials या key material के बिना सीमित सहायता सारांश कॉपी करें।',
            copyDiagnostics: 'निदान कॉपी करें', diagnosticsCollecting: 'एकत्र किया जा रहा है...', diagnosticsCopied: 'निदान कॉपी हुआ', diagnosticsFailed: 'निदान एकत्र नहीं हो सका'
        },
        ar: {
            backupPassword: 'كلمة مرور النسخة الاحتياطية', backupHint: 'مطلوبة، 12 حرفا على الأقل.', backupPlaceholder: 'أدخل كلمة مرور قوية للنسخة الاحتياطية', show: 'إظهار', hide: 'إخفاء',
            exportSettings: 'تصدير الإعدادات المشفرة', importSettings: 'استيراد الإعدادات المشفرة', synchronizeRuntime: 'مزامنة وقت التشغيل',
            keyboxHubCopy: 'احصل هنا على مفتاح API للخادم البعيد الموصى به.', getApiKey: 'الحصول على مفتاح API',
            diagnosticsTitle: 'تشخيص الدعم', diagnosticsHint: 'انسخ ملخص دعم محدودا دون سجلات أو أسماء حزم أو keybox أو قيم هوية أو بيانات اعتماد أو مواد مفاتيح.',
            copyDiagnostics: 'نسخ التشخيص', diagnosticsCollecting: 'جار الجمع...', diagnosticsCopied: 'تم نسخ التشخيص', diagnosticsFailed: 'تعذر جمع التشخيص'
        }
    };

    let compatibilityConfig = null;
    let compatibilityConfigController = null;
    let locale = readLocale();
    const originalText = new WeakMap();
    const originalAttrs = new WeakMap();
    let translationObserver = null;

    function normalizeSupportedLocale(value) {
        if (typeof value !== 'string' || !value) return null;
        if (value.startsWith('zh-Hans-')) {
            const mapped = 'zh-' + value.split('-')[2];
            if (SUPPORTED.some(([id]) => id === mapped)) return mapped;
        }
        if (SUPPORTED.some(([id]) => id === value)) return value;
        const baseLang = value.split('-')[0];
        if (SUPPORTED.some(([id]) => id === baseLang)) return baseLang;
        return null;
    }

    function readSavedLocale() {
        try {
            return normalizeSupportedLocale(global.localStorage && global.localStorage.getItem(STORAGE_KEY));
        } catch (_) {
            return null;
        }
    }

    function readSystemLocaleHint() {
        const runtimeLocale = normalizeSupportedLocale(global.CleveresSystemLocale);
        if (runtimeLocale) return runtimeLocale;
        const configLocale = normalizeSupportedLocale(compatibilityConfig && compatibilityConfig.system_locale);
        if (configLocale) return configLocale;
        try {
            return normalizeSupportedLocale(global.localStorage && global.localStorage.getItem(SYSTEM_LOCALE_KEY));
        } catch (_) {
            return null;
        }
    }

    function persistSystemLocaleHint(value) {
        const normalized = normalizeSupportedLocale(value);
        if (!normalized) return null;
        global.CleveresSystemLocale = normalized;
        try {
            if (global.localStorage) global.localStorage.setItem(SYSTEM_LOCALE_KEY, normalized);
        } catch (_) {}
        return normalized;
    }

    function readBrowserLocale() {
        try {
            return normalizeSupportedLocale(global.navigator && global.navigator.language);
        } catch (_) {
            return null;
        }
    }

    function readLocale() {
        return readSavedLocale() || readSystemLocaleHint() || readBrowserLocale() || 'en';
    }

    function saveLocale(value) {
        locale = SUPPORTED.some(([id]) => id === value) ? value : 'en';
        try { if (global.localStorage) global.localStorage.setItem(STORAGE_KEY, locale); } catch (_) {}
    }

    function tr(value) {
        if (locale === 'en') return value;
        const translated = TRANSLATIONS[locale] && TRANSLATIONS[locale][value];
        if (translated) return translated;
        return translateDynamicCopy(value);
    }

    function translateDynamicCopy(value) {
        if (locale === 'en' || typeof value !== 'string') return value;
        const dynamic = DYNAMIC_COPY[locale];
        if (!dynamic) return value;
        const render = (key, captured, localizeCaptured = false) => {
            const template = dynamic[key];
            if (!template) return value;
            const replacement = localizeCaptured
                ? ((TRANSLATIONS[locale] && TRANSLATIONS[locale][captured]) || captured)
                : captured;
            return template.replace('{value}', replacement);
        };
        const runtimeSuffix = ' Core boot/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine.';
        if (value.endsWith(runtimeSuffix)) {
            const prefix = value.slice(0, -runtimeSuffix.length);
            const translatedPrefix = TRANSLATIONS[locale] && TRANSLATIONS[locale][prefix];
            const translatedSuffix = TRANSLATIONS[locale] && TRANSLATIONS[locale][runtimeSuffix.trim()];
            if (translatedPrefix && translatedSuffix) return `${translatedPrefix} ${translatedSuffix}`;
        }
        let match = value.match(/^Native runtime is active with (\d+) verified keybox(?:es)?\. Targeted mode is enabled, so app rules determine scope\. Core boot\/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine\.$/);
        if (match) return render('targeted', match[1]);
        match = value.match(/^Native runtime is active with (\d+) verified keybox(?:es)?\. Global application scope is enabled\. Core boot\/TEE compatibility remains active independently of Identity Engine; hardware bootloader and root-of-trust state remain genuine\.$/);
        if (match) return render('global', match[1]);
        match = value.match(/^(\d+) active keyboxes$/);
        if (match) return render('activeKeyboxes', match[1]);
        match = value.match(/^(\d+) Keys Loaded$/);
        if (match) return render('keysLoaded', match[1]);
        match = value.match(/^(\d+) B configuration$/);
        if (match) return render('configurationBytes', match[1]);
        match = value.match(/^Toggle (.+)$/);
        if (match) return render('toggle', match[1], true);
        match = value.match(/^Remove (.+)$/);
        if (match) return render('remove', match[1], true);
        match = value.match(/^(.+) \(current\)$/);
        if (match) return render('current', match[1], true);
        match = value.match(/^Delete profile "(.+)"\?$/);
        if (match) return render('deleteProfile', match[1]);
        match = value.match(/^Identity ready: (.+)$/);
        if (match) return render('identityReady', match[1]);
        match = value.match(/^(.+) updated$/);
        if (match) return render('updated', match[1], true);
        match = value.match(/^(.+) manual date must be YYYY-MM-DD$/);
        if (match) return render('manualDate', match[1], true);
        match = value.match(/^Logs saved to (.+)$/);
        if (match) return render('logsSaved', match[1]);
        match = value.match(/^Language template saved to (.+)$/);
        if (match) return render('templateSaved', match[1]);
        match = value.match(/^Error: (.+)$/);
        if (match) return render('error', match[1]);
        match = value.match(/^Failed: (.+)$/);
        if (match) return render('failed', match[1]);
        match = value.match(/^Save Failed: (.+)$/);
        if (match) return render('saveFailed', match[1]);
        match = value.match(/^Download failed: (.+)$/);
        if (match) return render('downloadFailed', match[1]);
        match = value.match(/^Failed to load logs: (.+)$/);
        if (match) return render('loadLogsFailed', match[1]);
        match = value.match(/^Error loading templates: (.+)$/);
        if (match) return render('templatesFailed', match[1]);
        match = value.match(/^Error generating identity: (.+)$/);
        if (match) return render('identityFailed', match[1]);
        match = value.match(/^Error saving app config: (.+)$/);
        if (match) return render('appConfigFailed', match[1]);
        match = value.match(/^Profile (.+) Applied$/);
        if (match) return render('profileApplied', match[1]);
        match = value.match(/^Policy controls unavailable: (.+)$/);
        if (match) return render('policyUnavailable', match[1]);
        match = value.match(/^Encrypted backup saved to (.+)$/);
        if (match) return render('backupSaved', match[1]);
        return value;
    }

    function ownedCopy(key) {
        const catalog = OWNED_COPY[locale] || OWNED_COPY.en;
        return catalog[key] || OWNED_COPY.en[key] || key;
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value).replace(/[&<>\"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[char]));
    }

    function injectStyles() {
        if (document.getElementById('ct_ux_styles')) return;
        const style = document.createElement('style');
        style.id = 'ct_ux_styles';
        style.textContent = `
            :root { --ct-soft-border: color-mix(in srgb, var(--border) 62%, transparent); }
            .panel { border-color: var(--ct-soft-border) !important; box-shadow: 0 2px 10px rgba(0,0,0,.12) !important; }
            .row, .row > *, .panel, .panel > *, .ct-feature-card, .ct-feature-card > *, td, td > *, .tag { min-width: 0; }
            .row label, .res-desc, .scope-note, .tag, td, th, code, #runtimeHealthText, .ct-inline-note { overflow-wrap: anywhere; word-break: break-word; }
            input[type="checkbox"].toggle {
                appearance: none !important; -webkit-appearance: none !important; width: 48px !important; height: 28px !important;
                box-sizing: border-box !important; flex: 0 0 48px !important; margin: 0 !important; padding: 0 !important;
                border: 1px solid #4b4b4f !important; border-radius: 999px !important; background: #25262a !important;
                background-clip: border-box !important; position: relative !important; transition: background .18s ease,border-color .18s ease !important;
                box-shadow: inset 0 1px 2px rgba(0,0,0,.35) !important;
            }
            input[type="checkbox"].toggle::after {
                content: '' !important; position: absolute !important; width: 22px !important; height: 22px !important;
                top: 2px !important; left: 2px !important; border-radius: 50% !important; background: #f5f5f5 !important;
                box-shadow: 0 1px 3px rgba(0,0,0,.45) !important; transform: translateX(0) !important; transition: transform .18s ease !important;
            }
            input[type="checkbox"].toggle:checked { background: var(--accent) !important; border-color: var(--accent) !important; }
            input[type="checkbox"].toggle:checked::after { transform: translateX(20px) !important; background: #0b0b0c !important; }
            input[type="checkbox"].toggle:focus-visible { outline: 2px solid var(--accent) !important; outline-offset: 3px !important; }
            #apps .search-container, #apps .grid-2 > div { min-width: 0; }
            .autocomplete-items { z-index: 1200 !important; max-height: min(42dvh,320px) !important; overflow-x: hidden !important; }
            .autocomplete-items div { white-space: normal !important; overflow-wrap: anywhere !important; line-height: 1.25 !important; }
            #ct_package_search_note { margin-top: -3px; margin-bottom: 12px; }
            #ct_language_panel select { max-width: 260px; }
            #ct_debug_panel .row { display: flex !important; flex-direction: row !important; align-items: center !important; justify-content: space-between !important; margin-bottom: 0 !important; }
            #ct_debug_panel .row > label { flex: 1 1 auto !important; min-width: 0 !important; padding-right: 14px !important; }
            #ct_debug_panel .row > input[type="checkbox"] { flex: 0 0 48px !important; width: 48px !important; min-width: 48px !important; max-width: 48px !important; height: 28px !important; min-height: 28px !important; max-height: 28px !important; margin: 0 !important; }
            #ct_diagnostics_panel .row, #ct_drm_dashboard_panel .row { margin-bottom: 0; }
            #ct_diagnostics_copy { display: inline-flex !important; align-items: center !important; justify-content: center !important; text-align: center !important; white-space: nowrap !important; box-sizing: border-box !important; }
            #storedKeyboxesList .ct-keybox-item, #storedKeyboxesList .row { display: flex !important; flex-direction: row !important; flex-wrap: nowrap !important; align-items: center !important; justify-content: space-between !important; gap: 12px !important; width: 100% !important; box-sizing: border-box !important; }
            #storedKeyboxesList .ct-keybox-item > input[type="checkbox"], #storedKeyboxesList .row > input[type="checkbox"] { flex: 0 0 20px !important; width: 20px !important; height: 20px !important; margin: 0 !important; }
            #storedKeyboxesList .ct-keybox-item > div, #storedKeyboxesList .row > div { flex: 1 1 auto !important; min-width: 0 !important; }
            #storedKeyboxesList .ct-keybox-item > button, #storedKeyboxesList .row > button { flex: 0 0 auto !important; width: auto !important; min-width: 0 !important; max-width: max-content !important; margin: 0 !important; white-space: nowrap !important; }
            #ct_effective_apps_host > .panel { margin-top: 0; }
            #ct_effective_apps_host { margin-top: 20px; }
            #cleveresCommunityCard { box-sizing: border-box; margin: 20px 0 24px !important; width: 100%; }
            #dashboard { padding-bottom: max(116px, calc(84px + env(safe-area-inset-bottom))) !important; }
            #ct_full_guide .ct-guide-section { padding: 14px 0; border-bottom: 1px solid var(--ct-soft-border); }
            #ct_full_guide .ct-guide-section:last-child { border-bottom: 0; padding-bottom: 0; }
            #ct_full_guide h4 { margin: 0 0 6px; color: var(--accent); font-size: 1em; }
            #ct_full_guide p { margin: 0; color: #aaa; line-height: 1.55; }
            .ct-compat-note { color:#999; font-size:.82em; line-height:1.45; margin-top:8px; }
            #ct_config_management .ct-config-password-label { display:block; margin-bottom:8px; }
            #ct_config_management .ct-config-field-note { margin:7px 2px 0; color:#888; font-size:.8em; line-height:1.4; }
            #ct_config_management .ct-config-actions { gap:10px; margin-top:14px; }
            #ct_config_management .ct-config-actions > button { width:100%; margin:0 !important; }
            #ct_config_management .ct-config-actions #runtimeSyncBtn { grid-column:1 / -1; }
            #ct_keyboxhub_hint { display:grid; grid-template-columns:minmax(0,1fr) auto; align-items:center; gap:12px; margin-top:12px; padding:10px 12px; border:1px solid var(--ct-soft-border); border-radius:8px; background:rgba(255,255,255,.025); }
            #ct_keyboxhub_hint strong { display:block; color:var(--fg); font-size:.86em; font-weight:600; margin-bottom:2px; }
            #ct_keyboxhub_hint p { margin:0; color:#888; font-size:.78em; line-height:1.4; }
            #ct_keyboxhub_hint .ct-keyboxhub-action { display:inline-flex; align-items:center; justify-content:center; min-height:44px; padding:7px 11px; border:1px solid var(--border); border-radius:6px; color:var(--fg); text-decoration:none; font-size:.78em; font-weight:500; white-space:nowrap; background:rgba(255,255,255,.035); }
            #ct_keyboxhub_hint .ct-keyboxhub-action:hover { background:rgba(255,255,255,.07); }
            html[dir="rtl"] body { direction: rtl; }
            html[dir="rtl"] input, html[dir="rtl"] select, html[dir="rtl"] textarea, html[dir="rtl"] pre, html[dir="rtl"] code, html[dir="rtl"] .mono { direction: ltr; text-align: left; }
            html[dir="rtl"] select { background-position: left 14px center !important; padding-left: 40px !important; padding-right: 14px !important; }
            html[dir="rtl"] input[type="checkbox"].toggle { direction: ltr; }
            @media (max-width: 520px) {
                .ct-verify-header, .ct-diagnostics-header { flex-direction: column !important; align-items: stretch !important; gap: 10px !important; margin-bottom: 10px !important; }
                .ct-verify-header button, .ct-diagnostics-header button, #ct_diagnostics_copy { width: 100% !important; padding: 12px 20px !important; text-align: center !important; justify-content: center !important; margin: 0 !important; }
                .row { gap: 12px; align-items: flex-start; }
                .row:has(input[type="checkbox"]) { flex-direction: row !important; align-items: center !important; justify-content: space-between !important; }
                .row > input[type="checkbox"].toggle, .row > input[type="checkbox"].ct-switch { margin-top: 0 !important; }
                #ct_language_panel .row, #ct_diagnostics_panel .row, #ct_drm_dashboard_panel .row { flex-direction: column; align-items: stretch; gap: 12px; }
                #ct_language_panel select { width: 100% !important; max-width: 100% !important; }
                #ct_diagnostics_hint { width: 100% !important; padding-right: 0 !important; margin: 0 !important; }
                #ct_drm_dashboard_panel .row > * { width: 100%; padding-right: 0 !important; margin: 0 !important; }
                #ct_config_management .ct-config-actions { grid-template-columns:1fr; }
                #ct_config_management .ct-config-actions #runtimeSyncBtn { grid-column:auto; }
                .ct-verify-controls { display: flex; gap: 8px; align-items: stretch; flex-wrap: wrap; margin: 8px 0 12px; }
                .ct-verify-controls input { width: 100%; min-width: 0; }
                .ct-verify-controls button { flex: 1 1 calc(50% - 4px); min-width: 0; }
            }
            @media (max-width: 390px) {
                #ct_keyboxhub_hint { grid-template-columns:1fr; }
                #ct_keyboxhub_hint .ct-keyboxhub-action { justify-self:start; }
            }
        `;
        document.head.appendChild(style);
    }

    function canonicalizePolicyText() {
        document.querySelectorAll('h1,h2,h3,h4,label,button,.tab,.scope-note,.res-desc,p,small,option').forEach(element => {
            if (element.childElementCount > 0) return;
            const text = (element.textContent || '').trim();
            if (/^Profiles\s+v2$/i.test(text) || /^Profiles\s+V2$/i.test(text) || /^Advanced App Profiles$/i.test(text)) element.textContent = 'Profiles';
            else if (/Use Profiles\s+v2/i.test(text)) element.textContent = text.replace(/Profiles\s+v2/ig, 'Profiles');
        });
        const banner = document.getElementById('ct_identity_disabled_banner');
        if (banner) {
            banner.childNodes.forEach(node => {
                if (node.nodeType === 3 && /Identity şu anda|Identity is currently/i.test(node.nodeValue || '')) {
                    node.nodeValue = 'Identity is currently disabled. You can enable it from Dashboard. ';
                }
            });
            const button = banner.querySelector('button');
            if (button) button.textContent = 'Dashboard';
        }
    }

    function translateNode(node) {
        if (node.nodeType !== 3) return;
        const current = node.nodeValue || '';
        let record = originalText.get(node);
        if (!record) {
            record = { source: current, rendered: null };
            originalText.set(node, record);
        } else if (current !== record.source && current !== record.rendered) {
            record.source = current;
        }
        const original = record.source || '';
        const trimmed = original.trim();
        if (!trimmed) return;
        const leading = original.match(/^\s*/)[0];
        const trailing = original.match(/\s*$/)[0];
        const rendered = leading + tr(trimmed) + trailing;
        record.rendered = rendered;
        if (current !== rendered) node.nodeValue = rendered;
    }

    function translateElement(element) {
        if (!element || element.id === 'ct_full_guide') return;
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) translateNode(node);
        ['placeholder','title','aria-label','data-label'].forEach(name => {
            if (!element.hasAttribute || !element.hasAttribute(name)) return;
            let stored = originalAttrs.get(element);
            if (!stored) { stored = Object.create(null); originalAttrs.set(element, stored); }
            const current = element.getAttribute(name);
            if (!(name in stored)) stored[name] = { source: current, rendered: null };
            const record = stored[name];
            if (current !== record.source && current !== record.rendered) record.source = current;
            const rendered = tr(record.source);
            record.rendered = rendered;
            if (current !== rendered) element.setAttribute(name, rendered);
        });
        if (element.querySelectorAll) element.querySelectorAll('[placeholder],[title],[aria-label],[data-label]').forEach(child => {
            ['placeholder','title','aria-label','data-label'].forEach(name => {
                if (!child.hasAttribute(name)) return;
                let stored = originalAttrs.get(child);
                if (!stored) { stored = Object.create(null); originalAttrs.set(child, stored); }
                const current = child.getAttribute(name);
                if (!(name in stored)) stored[name] = { source: current, rendered: null };
                const record = stored[name];
                if (current !== record.source && current !== record.rendered) record.source = current;
                const rendered = tr(record.source);
                record.rendered = rendered;
                if (current !== rendered) child.setAttribute(name, rendered);
            });
        });
    }

    function installTranslationObserver() {
        if (translationObserver || typeof global.MutationObserver !== 'function' || !document.body) return;
        translationObserver = new global.MutationObserver(mutations => {
            mutations.forEach(mutation => {
                if (mutation.type === 'characterData') {
                    translateNode(mutation.target);
                    return;
                }
                if (mutation.type === 'attributes') {
                    translateElement(mutation.target);
                    return;
                }
                mutation.addedNodes.forEach(node => {
                    if (node.nodeType === 3) translateNode(node);
                    else if (node.nodeType === 1) translateElement(node);
                });
            });
        });
        translationObserver.observe(document.body, {
            subtree: true,
            childList: true,
            characterData: true,
            attributes: true,
            attributeFilter: ['placeholder','title','aria-label','data-label']
        });
    }

    function localizeOwnedSurfaces() {
        const config = document.getElementById('ct_config_management');
        if (config) {
            const input = document.getElementById('backupPw');
            const label = config.querySelector('label[for="backupPw"]');
            const note = document.getElementById('ct_backup_password_note');
            const exportButton = config.querySelector('button[onclick*="backupConfig"]');
            const importButton = config.querySelector('button[onclick*="restoreInput"]');
            const syncButton = document.getElementById('runtimeSyncBtn');
            if (label) label.textContent = ownedCopy('backupPassword');
            if (note) note.textContent = ownedCopy('backupHint');
            if (input) input.placeholder = ownedCopy('backupPlaceholder');
            if (exportButton && !exportButton.disabled) exportButton.textContent = ownedCopy('exportSettings');
            if (importButton && !importButton.disabled) importButton.textContent = ownedCopy('importSettings');
            if (syncButton && !syncButton.disabled) syncButton.textContent = ownedCopy('synchronizeRuntime');
            const toggle = input && input.closest('.pwd-wrapper') ? input.closest('.pwd-wrapper').querySelector('.pwd-toggle') : null;
            if (toggle) toggle.textContent = ownedCopy(input.type === 'text' ? 'hide' : 'show');
        }
        const hubCopy = document.getElementById('ct_keyboxhub_copy');
        const hubAction = document.getElementById('ct_keyboxhub_action');
        if (hubCopy) hubCopy.textContent = ownedCopy('keyboxHubCopy');
        if (hubAction) hubAction.textContent = ownedCopy('getApiKey');
        const diagnostics = document.getElementById('ct_diagnostics_panel');
        if (diagnostics) {
            const title = diagnostics.querySelector('h3');
            const hint = document.getElementById('ct_diagnostics_hint');
            const button = document.getElementById('ct_diagnostics_copy');
            if (title) title.textContent = ownedCopy('diagnosticsTitle');
            if (hint) hint.textContent = ownedCopy('diagnosticsHint');
            if (button && !button.disabled) button.textContent = ownedCopy('copyDiagnostics');
            if (button) button.setAttribute('aria-label', ownedCopy('copyDiagnostics'));
        }
    }

    function applyTranslations() {
        document.documentElement.lang = locale;
        document.documentElement.dir = locale === 'ar' ? 'rtl' : 'ltr';
        canonicalizePolicyText();
        translateElement(document.body);
        renderGuide();
        const selector = document.getElementById('ct_language_selector');
        if (selector) selector.value = locale;
        localizeOwnedSurfaces();
    }

    function installLanguagePanel() {
        const dashboard = document.getElementById('dashboard');
        if (!dashboard) return;
        let panel = document.getElementById('ct_language_panel');
        if (!panel) {
            panel = document.createElement('div');
            panel.id = 'ct_language_panel';
            panel.className = 'panel';
            panel.innerHTML = `<h3>Language</h3><div class="row"><select id="ct_language_selector" aria-label="Language" style="width:100%;">${SUPPORTED.map(([id,name]) => `<option value="${escapeHtml(id)}">${escapeHtml(name)}</option>`).join('')}</select></div><div class="ct-compat-note">Built-in translations are local and require no network connection. English is the default unless you choose another language here.</div>`;
            panel.querySelector('select').addEventListener('change', event => {
                saveLocale(event.target.value);
                applyTranslations();
                ensureFooterOrder();
            });
        }
        const featureCenter = document.getElementById('ct_dashboard_controls');
        const configPanel = document.getElementById('backupPw')?.closest('.panel');
        if (featureCenter && featureCenter.parentElement === dashboard) {
            if (featureCenter.nextElementSibling !== panel) dashboard.insertBefore(panel, featureCenter.nextSibling);
        } else if (configPanel && configPanel.parentElement === dashboard) {
            if (configPanel.nextElementSibling !== panel) dashboard.insertBefore(panel, configPanel.nextSibling);
        } else if (panel.parentElement !== dashboard) {
            dashboard.appendChild(panel);
        }
        panel.querySelector('select').value = locale;
    }

    function installConfigurationManagement() {
        const input = document.getElementById('backupPw');
        if (!input) return;
        const panel = input.closest('.panel');
        if (!panel) return;
        panel.id = 'ct_config_management';
        const label = panel.querySelector('label[for="backupPw"]');
        if (label) {
            label.classList.add('ct-config-password-label');
            if (!label.dataset.ctConfigCanonical) {
                label.dataset.ctConfigCanonical = '1';
                label.textContent = 'Backup Password';
            }
        }
        const passwordWrapper = input.closest('.pwd-wrapper');
        if (passwordWrapper && !document.getElementById('ct_backup_password_note')) {
            const note = document.createElement('div');
            note.id = 'ct_backup_password_note';
            note.className = 'ct-config-field-note';
            note.textContent = 'Required, at least 12 characters.';
            passwordWrapper.insertAdjacentElement('afterend', note);
        }
        const actions = panel.querySelector('.grid-2');
        const syncButton = document.getElementById('runtimeSyncBtn');
        if (actions) {
            actions.classList.add('ct-config-actions');
            if (syncButton && syncButton.parentElement !== actions) {
                const oldWrapper = syncButton.parentElement;
                actions.appendChild(syncButton);
                if (oldWrapper && oldWrapper !== panel && oldWrapper.children.length === 0) oldWrapper.remove();
            }
        }
    }

    function installKeyboxHubHint() {
        const serverList = document.getElementById('serverList');
        if (!serverList) return;
        const panel = serverList.closest('.panel');
        if (!panel) return;
        let hint = document.getElementById('ct_keyboxhub_hint');
        if (!hint) {
            hint = document.createElement('div');
            hint.id = 'ct_keyboxhub_hint';
            hint.innerHTML = '<div><strong>KeyboxHub</strong><p id="ct_keyboxhub_copy">Get an API key for the recommended remote server here.</p></div><a id="ct_keyboxhub_action" class="ct-keyboxhub-action" href="https://keybox.tryigit.dev/" target="_blank" rel="noopener noreferrer">Get API Key</a>';
            const addButton = Array.from(panel.querySelectorAll('button')).find(button => /addServerForm/.test(button.getAttribute('onclick') || ''));
            if (addButton) addButton.insertAdjacentElement('afterend', hint);
            else panel.appendChild(hint);
        }
    }

    function installOwnedSurfaceInteractions() {
        if (document.documentElement.dataset.ctOwnedSurfaceInteractions) return;
        document.documentElement.dataset.ctOwnedSurfaceInteractions = '1';
        document.addEventListener('click', event => {
            const target = event.target && event.target.closest ? event.target.closest('#ct_config_management .pwd-toggle') : null;
            if (!target) return;
            queueMicrotask(localizeOwnedSurfaces);
        });
    }

    function ensureFooterOrder() {
        const dashboard = document.getElementById('dashboard');
        if (!dashboard) return;
        const language = document.getElementById('ct_language_panel');
        const card = document.getElementById('cleveresCommunityCard');
        if (language && language.parentElement !== dashboard) dashboard.appendChild(language);
        if (card) {
            if (card.parentElement !== dashboard || card.nextElementSibling) dashboard.appendChild(card);
            card.setAttribute('aria-label', tr('CleveresTech Telegram community'));
            const title = card.querySelector('strong');
            if (title) title.textContent = tr('CleveresTech Community');
            const copy = card.querySelector('p');
            if (copy) copy.textContent = tr('Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.');
            const link = card.querySelector('a');
            if (link) {
                link.href = 'https://t.me/cleverestech';
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                link.textContent = tr('Open Telegram Community');
                if (!link.dataset.ctNativeOpen) {
                    link.dataset.ctNativeOpen = '1';
                    link.addEventListener('click', async event => {
                        event.preventDefault();
                        try {
                            await bridge.openCommunity();
                        } catch (_) {
                            try { global.open('https://t.me/cleverestech', '_blank', 'noopener,noreferrer'); } catch (_) {}
                        }
                    });
                }
            }
        }
    }

    function moveEffectiveStateIntoApps() {
        const apps = document.getElementById('apps');
        const effective = document.getElementById('effective');
        const effectiveTab = document.getElementById('tab_effective');
        if (effectiveTab) effectiveTab.hidden = true;
        if (!apps || !effective) return;
        let host = document.getElementById('ct_effective_apps_host');
        if (!host) {
            host = document.createElement('div');
            host.id = 'ct_effective_apps_host';
            apps.appendChild(host);
        }
        while (effective.firstChild) host.appendChild(effective.firstChild);
        effective.hidden = true;
    }

    function installPackageSearchNote() {
        const input = document.getElementById('appPkg');
        if (!input || document.getElementById('ct_package_search_note')) return;
        input.setAttribute('type','search');
        const note = document.createElement('div');
        note.id = 'ct_package_search_note';
        note.className = 'scope-note';
        note.textContent = 'System / preinstalled packages are included in this search.';
        const wrapper = input.closest('.search-container');
        if (wrapper && wrapper.parentElement) wrapper.parentElement.insertBefore(note, wrapper.nextSibling);
    }

    async function requestConfig() {
        if (compatibilityConfigController) compatibilityConfigController.abort();
        const controller = new AbortController();
        compatibilityConfigController = controller;
        try {
            const response = await bridge.fetch('/api/config', { signal: controller.signal });
            if (!response.ok) throw new Error(await response.text());
            const data = await response.json();
            if (controller.signal.aborted) return compatibilityConfig;
            compatibilityConfig = data;
            if (typeof persistSystemLocaleHint === 'function') persistSystemLocaleHint(data && data.system_locale);
            return compatibilityConfig;
        } catch (error) {
            if (controller.signal.aborted || (error && error.name === 'AbortError')) return compatibilityConfig;
            throw error;
        } finally {
            if (compatibilityConfigController === controller) compatibilityConfigController = null;
        }
    }

    function syncCompatibilityToggles(data) {
        if (!data) return;
        const drm = Boolean(data.drm_passthrough);
        ['drm_passthrough','res_toggle_drm_passthrough','ct_drm_passthrough_toggle'].forEach(id => {
            const checkbox = document.getElementById(id);
            if (checkbox) checkbox.checked = drm;
        });
        ['rkp_passthrough','res_toggle_rkp_passthrough'].forEach(id => {
            const checkbox = document.getElementById(id);
            if (checkbox) checkbox.checked = false;
        });
    }

    async function setSetting(name, enabled) {
        const body = new URLSearchParams();
        body.set('setting', name);
        body.set('enabled', enabled ? 'true' : 'false');
        const response = await bridge.fetch('/api/toggle', { method:'POST', body });
        if (!response.ok) throw new Error(await response.text());
        const data = await requestConfig();
        syncCompatibilityToggles(data);
    }

    function hideRetiredRkpUi() {
        document.querySelectorAll('#rkp_passthrough,#res_toggle_rkp_passthrough,#status_rkp').forEach(element => {
            const row = element.closest('.row');
            const card = element.parentElement && element.parentElement.parentElement;
            if (row) row.hidden = true;
            else if (card && /RKP/i.test(card.textContent || '')) card.hidden = true;
            else element.hidden = true;
        });
        document.querySelectorAll('#resourceBody tr').forEach(row => {
            if (/RKP Passthrough/i.test(row.textContent || '')) row.hidden = true;
        });
    }

    async function retireRkpSetting() {
        try {
            const data = compatibilityConfig || await requestConfig();
            if (data.rkp_passthrough) await setSetting('rkp_passthrough', false);
        } catch (_) {}
        hideRetiredRkpUi();
    }

    function installDrmPanel() {
        // DRM controls live exclusively in Feature Center. Remove stale standalone copies
        // from cached/older WebUI layouts instead of creating another toggle surface.
        const legacy = document.getElementById('ct_drm_dashboard_panel');
        if (legacy) legacy.remove();
    }

    function installDebugPanel() {
        const log = document.getElementById('log');
        if (!log || document.getElementById('ct_debug_panel')) return;
        const panel = document.createElement('div');
        panel.id = 'ct_debug_panel';
        panel.className = 'panel';
        panel.innerHTML = `<h3>Debug Logging</h3><div class="row" style="display:flex;align-items:center;justify-content:space-between;"><label for="ct_debug_logging_toggle" style="flex:1;min-width:0;padding-right:14px"><strong style="color:#fff">Debug Logging</strong><span class="res-desc">Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.</span></label><input id="ct_debug_logging_toggle" class="toggle" type="checkbox" style="flex:0 0 48px;width:48px;height:28px;"></div>`;
        log.insertBefore(panel, log.firstChild);
        const checkbox = panel.querySelector('input');
        bridge.getDebugLogging().then(enabled => { checkbox.checked = Boolean(enabled); }).catch(()=>{});
        checkbox.addEventListener('change', async () => {
            checkbox.disabled = true;
            try {
                await bridge.setDebugLogging(checkbox.checked);
                if (typeof global.notify === 'function') global.notify(tr(checkbox.checked ? 'Debug logging enabled' : 'Debug logging disabled'));
            } catch (error) {
                checkbox.checked = !checkbox.checked;
                if (typeof global.notify === 'function') global.notify(error.message || tr('Could not update debug logging'),'error');
            } finally {
                checkbox.disabled = false;
            }
        });
    }

    const DIAGNOSTIC_FIELDS = Object.freeze([
        'version_name', 'version_code', 'environment', 'native_state', 'native_alive', 'native_failure',
        'keystore_interceptor', 'telephony_interceptor', 'keybox_count', 'app_config_bytes',
        'process_cpu_percent', 'process_rss_kb', 'identity_engine', 'global_mode',
        'automatic_keybox_check', 'identity_refresh_on_boot', 'telephony',
        'drm_passthrough', 'build_identity', 'region_property_view'
    ]);

    function sanitizeDiagnosticValue(value) {
        if (typeof value === 'boolean') return value ? 'true' : 'false';
        if (typeof value === 'number' && Number.isFinite(value)) return String(value);
        if (typeof value !== 'string') return 'unknown';
        const sanitized = value.replace(/[\u0000-\u001f\u007f=]/g, ' ').trim().slice(0, 96);
        return sanitized || 'unknown';
    }

    function formatDiagnosticsSnapshot(data) {
        const source = data && typeof data === 'object' && !Array.isArray(data) ? data : {};
        const runtime = source.native_runtime && typeof source.native_runtime === 'object' && !Array.isArray(source.native_runtime)
            ? source.native_runtime : {};
        const values = {
            version_name: source.version_name,
            version_code: source.version_code,
            environment: source.environment,
            native_state: runtime.state,
            native_alive: runtime.alive,
            native_failure: runtime.failure,
            keystore_interceptor: source.keystore_interceptor_running,
            telephony_interceptor: source.telephony_interceptor_running,
            keybox_count: source.keybox_count,
            app_config_bytes: source.app_config_size,
            process_cpu_percent: source.real_cpu,
            process_rss_kb: source.real_ram_kb,
            identity_engine: source.spoof_enabled,
            global_mode: source.global_mode,
            automatic_keybox_check: source.auto_keybox_check,
            identity_refresh_on_boot: source.random_on_boot,
            telephony: source.telephony,
            drm_passthrough: source.drm_passthrough,
            build_identity: source.spoof_build_identity,
            region_property_view: source.spoof_region_cn
        };
        return ['CleveresTricky diagnostics', 'schema=2']
            .concat(DIAGNOSTIC_FIELDS.map(field => `${field}=${sanitizeDiagnosticValue(values[field])}`))
            .join('\n');
    }

    async function copyDiagnosticsSnapshot(button) {
        let handedToClipboard = false;
        button.disabled = true;
        button.textContent = ownedCopy('diagnosticsCollecting');
        try {
            const response = await bridge.fetch('/api/resource_usage');
            if (!response.ok) throw new Error('Resource snapshot unavailable');
            const snapshot = formatDiagnosticsSnapshot(await response.json());
            button.disabled = false;
            button.textContent = ownedCopy('copyDiagnostics');
            if (typeof global.copyToClipboard !== 'function') throw new Error('Clipboard unavailable');
            await global.copyToClipboard(snapshot, ownedCopy('diagnosticsCopied'), button);
            handedToClipboard = true;
        } catch (_) {
            if (typeof global.notify === 'function') global.notify(ownedCopy('diagnosticsFailed'), 'error');
        } finally {
            button.disabled = false;
            if (!handedToClipboard) button.textContent = ownedCopy('copyDiagnostics');
        }
    }

    function installDiagnosticsPanel() {
        const info = document.getElementById('info');
        if (!info || document.getElementById('ct_diagnostics_panel')) return;
        const panel = document.createElement('div');
        panel.id = 'ct_diagnostics_panel';
        panel.className = 'panel';
        panel.innerHTML = '<h3>Support Diagnostics</h3><div class="row ct-diagnostics-header"><div id="ct_diagnostics_hint" class="res-desc" style="flex:1;padding-right:14px">Copy a bounded support snapshot without logs, package names, keybox names, identity values, credentials, or key material.</div><button id="ct_diagnostics_copy" type="button" aria-label="Copy Diagnostics" style="text-align:center;justify-content:center;white-space:nowrap;">Copy Diagnostics</button></div>';
        info.appendChild(panel);
        panel.querySelector('button').addEventListener('click', event => copyDiagnosticsSnapshot(event.currentTarget));
    }

    function renderGuide() {
        const guide = document.getElementById('guide');
        if (!guide) return;
        if (guide.dataset.ctGuideLocale === locale && guide.querySelector('#ct_full_guide')) return;
        const sections = GUIDE[locale] || GUIDE.en;
        guide.dataset.ctGuideLocale = locale;
        guide.innerHTML = `<div class="panel" id="ct_full_guide"><h3>${escapeHtml(tr('Guide'))}</h3><div class="scope-note">${escapeHtml(tr('All major features and runtime paths in one place.'))}</div>${sections.map(([title,body]) => `<section class="ct-guide-section"><h4>${escapeHtml(title)}</h4><p>${escapeHtml(body)}</p></section>`).join('')}</div>`;
    }

    function wrapLegacyToggle() {
        const original = global.toggle;
        if (typeof original !== 'function' || original.ctUxWrapped) return;
        const wrapped = function(setting, checkbox) {
            if (setting === 'rkp_passthrough') {
                if (checkbox) checkbox.checked = false;
                retireRkpSetting();
                return Promise.resolve();
            }
            const result = original.apply(this, arguments);
            if (setting === 'drm_passthrough') Promise.resolve(result).finally(() => requestConfig().then(syncCompatibilityToggles).catch(()=>{}));
            return result;
        };
        wrapped.ctUxWrapped = true;
        global.toggle = wrapped;
    }

    function wrapTabSwitch() {
        const original = global.switchTab;
        if (typeof original !== 'function' || original.ctUxWrapped) return;
        const wrapped = function(name) {
            const result = original.apply(this, arguments);
            queueMicrotask(() => {
                applyEnhancements();
                if (name === 'info') requestConfig().then(syncCompatibilityToggles).catch(()=>{});
            });
            return result;
        };
        wrapped.ctUxWrapped = true;
        global.switchTab = wrapped;
    }

    function applyEnhancements() {
        canonicalizePolicyText();
        installLanguagePanel();
        installConfigurationManagement();
        installKeyboxHubHint();
        moveEffectiveStateIntoApps();
        installPackageSearchNote();
        installDrmPanel();
        installDebugPanel();
        installDiagnosticsPanel();
        hideRetiredRkpUi();
        ensureFooterOrder();
        applyTranslations();
    }

    async function start() {
        if (!readSavedLocale()) {
            try { await requestConfig(); } catch (_) {}
            locale = readLocale();
        }
        injectStyles();
        installOwnedSurfaceInteractions();
        installTranslationObserver();
        applyEnhancements();
        wrapLegacyToggle();
        wrapTabSwitch();
        retireRkpSetting();
        [200,700,1500,3000,6000].forEach(delay => global.setTimeout(() => {
            applyEnhancements();
            wrapLegacyToggle();
            wrapTabSwitch();
        }, delay));
    }

    global.CleveresI18n = Object.freeze({
        translate: value => tr(String(value == null ? '' : value)),
        apply: applyTranslations,
        get locale() { return locale; }
    });

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once:true });
    else start();
})(window);

// ZIP keybox import UX; single-owner compatibility hook for the legacy inline upload controller.
(function (global) {
    'use strict';

    const MAX_SUPPORTED_FILES = 64;
    const MAX_ARCHIVE_ENTRIES = 256;
    const MAX_XML_BYTES = 10 * 1024 * 1024;
    const MAX_CBOX_BYTES = MAX_XML_BYTES + 36;
    const MAX_COMPRESSED_FILE_BYTES = MAX_CBOX_BYTES + 64 * 1024;
    const MAX_NAME_BYTES = 4096;
    const MAX_CENTRAL_DIRECTORY_BYTES = 64 * 1024 * 1024;
    const INSTALL_RETRY_MS = 50;
    const MAX_INSTALL_ATTEMPTS = 100;
    const STORAGE_KEY = 'cleverestricky.language.v1';
    const SUPPORTED_LOCALES = new Set(['en', 'tr', 'zh-CN', 'es', 'de', 'ru', 'id', 'hi', 'ar']);

    const COPY = {
        en: {
            prompt: 'Or click to select .xml, .cbox or .zip',
            ready: 'ZIP ready: {name}',
            summary: '{count} supported XML/CBOX files · {size}',
            confirm: 'I understand that every supported XML/CBOX file in this ZIP will be imported individually.',
            import: 'Import ZIP files',
            importing: 'Importing ZIP files… {done}/{total}',
            success: 'ZIP import complete: {ok}/{total} files imported.',
            partial: 'ZIP import finished: {ok} imported, {failed} failed.',
            select: 'Select a non-empty XML, CBOX or ZIP file.',
            entryLimit: 'ZIP contains too many entries.',
            fileCountLimit: 'ZIP contains more than 64 supported XML/CBOX files.',
            fileLimit: 'A supported XML/CBOX file is empty or larger than 10 MiB.',
            noSupported: 'ZIP does not contain supported .xml or .cbox files.',
            encrypted: 'Encrypted ZIP entries are not supported.',
            compression: 'ZIP uses an unsupported compression method.',
            malformed: 'ZIP is malformed or uses unsupported ZIP64/multi-disk features.',
            decompressor: 'This Android WebView cannot decompress standard ZIP files. Update Android System WebView and try again.',
            changed: 'ZIP selection changed. Review the files and confirm again.',
            busy: 'A ZIP import is already running.'
        },
        tr: {
            prompt: '.xml, .cbox veya .zip seçmek için dokunun',
            ready: 'ZIP hazır: {name}',
            summary: '{count} desteklenen XML/CBOX dosyası · {size}',
            confirm: 'Bu ZIP içindeki desteklenen tüm XML/CBOX dosyalarının tek tek içe aktarılacağını anlıyorum.',
            import: 'ZIP dosyalarını içe aktar',
            importing: 'ZIP dosyaları içe aktarılıyor… {done}/{total}',
            success: 'ZIP içe aktarma tamamlandı: {ok}/{total} dosya eklendi.',
            partial: 'ZIP içe aktarma bitti: {ok} eklendi, {failed} başarısız.',
            select: 'Boş olmayan bir XML, CBOX veya ZIP dosyası seçin.',
            entryLimit: 'ZIP çok fazla girdi içeriyor.',
            fileCountLimit: 'ZIP 64 adetten fazla desteklenen XML/CBOX dosyası içeriyor.',
            fileLimit: 'Desteklenen bir XML/CBOX dosyası boş veya 10 MiB sınırını aşıyor.',
            noSupported: 'ZIP desteklenen .xml veya .cbox dosyası içermiyor.',
            encrypted: 'Şifreli ZIP girdileri desteklenmiyor.',
            compression: 'ZIP desteklenmeyen bir sıkıştırma yöntemi kullanıyor.',
            malformed: 'ZIP bozuk veya desteklenmeyen ZIP64/çoklu disk özellikleri kullanıyor.',
            decompressor: 'Bu Android WebView standart ZIP dosyalarını açamıyor. Android System WebView uygulamasını güncelleyip yeniden deneyin.',
            changed: 'ZIP seçimi değişti. Dosyaları yeniden kontrol edip onaylayın.',
            busy: 'Bir ZIP içe aktarma işlemi zaten çalışıyor.'
        },
        'zh-CN': {
            prompt: '点击选择 .xml、.cbox 或 .zip', ready: 'ZIP 已就绪：{name}', summary: '{count} 个支持的 XML/CBOX 文件 · {size}',
            confirm: '我了解此 ZIP 中所有受支持的 XML/CBOX 文件都会逐个导入。', import: '导入 ZIP 文件', importing: '正在导入 ZIP 文件… {done}/{total}',
            success: 'ZIP 导入完成：已导入 {ok}/{total} 个文件。', partial: 'ZIP 导入结束：成功 {ok} 个，失败 {failed} 个。',
            select: '请选择非空的 XML、CBOX 或 ZIP 文件。', entryLimit: 'ZIP 中的条目过多。', fileCountLimit: 'ZIP 中受支持的 XML/CBOX 文件超过 64 个。',
            fileLimit: '受支持的 XML/CBOX 文件为空或超过 10 MiB。', noSupported: 'ZIP 中没有受支持的 .xml 或 .cbox 文件。', encrypted: '不支持加密的 ZIP 条目。',
            compression: 'ZIP 使用了不支持的压缩方式。', malformed: 'ZIP 已损坏，或使用了不支持的 ZIP64/多磁盘功能。',
            decompressor: '此 Android WebView 无法解压标准 ZIP。请更新 Android System WebView 后重试。', changed: 'ZIP 选择已更改。请重新检查文件并确认。', busy: '已有 ZIP 导入任务正在运行。'
        },
        es: {
            prompt: 'Toca para elegir .xml, .cbox o .zip', ready: 'ZIP listo: {name}', summary: '{count} archivos XML/CBOX compatibles · {size}',
            confirm: 'Entiendo que todos los archivos XML/CBOX compatibles de este ZIP se importarán individualmente.', import: 'Importar archivos ZIP', importing: 'Importando archivos ZIP… {done}/{total}',
            success: 'Importación ZIP completada: {ok}/{total} archivos importados.', partial: 'Importación ZIP finalizada: {ok} importados, {failed} fallidos.',
            select: 'Selecciona un archivo XML, CBOX o ZIP no vacío.', entryLimit: 'El ZIP contiene demasiadas entradas.', fileCountLimit: 'El ZIP contiene más de 64 archivos XML/CBOX compatibles.',
            fileLimit: 'Un XML/CBOX compatible está vacío o supera 10 MiB.', noSupported: 'El ZIP no contiene archivos .xml o .cbox compatibles.', encrypted: 'No se admiten entradas ZIP cifradas.',
            compression: 'El ZIP usa un método de compresión no compatible.', malformed: 'El ZIP está dañado o usa ZIP64/múltiples discos no compatibles.',
            decompressor: 'Este Android WebView no puede descomprimir ZIP estándar. Actualiza Android System WebView y vuelve a intentarlo.', changed: 'La selección ZIP cambió. Revisa los archivos y confirma de nuevo.', busy: 'Ya hay una importación ZIP en curso.'
        },
        de: {
            prompt: 'Tippen, um .xml, .cbox oder .zip auszuwählen', ready: 'ZIP bereit: {name}', summary: '{count} unterstützte XML/CBOX-Dateien · {size}',
            confirm: 'Ich verstehe, dass alle unterstützten XML/CBOX-Dateien in diesem ZIP einzeln importiert werden.', import: 'ZIP-Dateien importieren', importing: 'ZIP-Dateien werden importiert… {done}/{total}',
            success: 'ZIP-Import abgeschlossen: {ok}/{total} Dateien importiert.', partial: 'ZIP-Import beendet: {ok} importiert, {failed} fehlgeschlagen.',
            select: 'Wähle eine nicht leere XML-, CBOX- oder ZIP-Datei.', entryLimit: 'Das ZIP enthält zu viele Einträge.', fileCountLimit: 'Das ZIP enthält mehr als 64 unterstützte XML/CBOX-Dateien.',
            fileLimit: 'Eine unterstützte XML/CBOX-Datei ist leer oder größer als 10 MiB.', noSupported: 'Das ZIP enthält keine unterstützten .xml- oder .cbox-Dateien.', encrypted: 'Verschlüsselte ZIP-Einträge werden nicht unterstützt.',
            compression: 'Das ZIP verwendet eine nicht unterstützte Komprimierung.', malformed: 'Das ZIP ist beschädigt oder verwendet nicht unterstützte ZIP64-/Multi-Disk-Funktionen.',
            decompressor: 'Dieses Android WebView kann Standard-ZIP-Dateien nicht entpacken. Aktualisiere Android System WebView und versuche es erneut.', changed: 'Die ZIP-Auswahl wurde geändert. Prüfe die Dateien erneut und bestätige noch einmal.', busy: 'Ein ZIP-Import läuft bereits.'
        },
        ru: {
            prompt: 'Нажмите, чтобы выбрать .xml, .cbox или .zip', ready: 'ZIP готов: {name}', summary: '{count} поддерживаемых XML/CBOX · {size}',
            confirm: 'Я понимаю, что все поддерживаемые XML/CBOX-файлы из этого ZIP будут импортированы по отдельности.', import: 'Импортировать файлы ZIP', importing: 'Импорт файлов ZIP… {done}/{total}',
            success: 'Импорт ZIP завершён: импортировано {ok}/{total}.', partial: 'Импорт ZIP завершён: {ok} успешно, {failed} с ошибкой.',
            select: 'Выберите непустой XML, CBOX или ZIP.', entryLimit: 'В ZIP слишком много записей.', fileCountLimit: 'В ZIP больше 64 поддерживаемых XML/CBOX-файлов.',
            fileLimit: 'Поддерживаемый XML/CBOX пуст или больше 10 MiB.', noSupported: 'В ZIP нет поддерживаемых .xml или .cbox.', encrypted: 'Зашифрованные записи ZIP не поддерживаются.',
            compression: 'ZIP использует неподдерживаемый метод сжатия.', malformed: 'ZIP повреждён или использует неподдерживаемые ZIP64/многодисковые функции.',
            decompressor: 'Этот Android WebView не может распаковать стандартный ZIP. Обновите Android System WebView и повторите попытку.', changed: 'Выбран другой ZIP. Снова проверьте файлы и подтвердите импорт.', busy: 'Импорт ZIP уже выполняется.'
        },
        id: {
            prompt: 'Ketuk untuk memilih .xml, .cbox, atau .zip', ready: 'ZIP siap: {name}', summary: '{count} file XML/CBOX yang didukung · {size}',
            confirm: 'Saya memahami bahwa semua file XML/CBOX yang didukung di ZIP ini akan diimpor satu per satu.', import: 'Impor file ZIP', importing: 'Mengimpor file ZIP… {done}/{total}',
            success: 'Impor ZIP selesai: {ok}/{total} file diimpor.', partial: 'Impor ZIP selesai: {ok} berhasil, {failed} gagal.',
            select: 'Pilih file XML, CBOX, atau ZIP yang tidak kosong.', entryLimit: 'ZIP berisi terlalu banyak entri.', fileCountLimit: 'ZIP berisi lebih dari 64 file XML/CBOX yang didukung.',
            fileLimit: 'File XML/CBOX yang didukung kosong atau lebih dari 10 MiB.', noSupported: 'ZIP tidak berisi file .xml atau .cbox yang didukung.', encrypted: 'Entri ZIP terenkripsi tidak didukung.',
            compression: 'ZIP memakai metode kompresi yang tidak didukung.', malformed: 'ZIP rusak atau memakai fitur ZIP64/multi-disk yang tidak didukung.',
            decompressor: 'Android WebView ini tidak dapat mengekstrak ZIP standar. Perbarui Android System WebView lalu coba lagi.', changed: 'Pilihan ZIP berubah. Tinjau file dan konfirmasi lagi.', busy: 'Impor ZIP sedang berjalan.'
        },
        hi: {
            prompt: '.xml, .cbox या .zip चुनने के लिए टैप करें', ready: 'ZIP तैयार: {name}', summary: '{count} समर्थित XML/CBOX फ़ाइलें · {size}',
            confirm: 'मैं समझता/समझती हूँ कि इस ZIP की हर समर्थित XML/CBOX फ़ाइल अलग-अलग आयात होगी।', import: 'ZIP फ़ाइलें आयात करें', importing: 'ZIP फ़ाइलें आयात हो रही हैं… {done}/{total}',
            success: 'ZIP आयात पूरा: {ok}/{total} फ़ाइलें आयात हुईं।', partial: 'ZIP आयात समाप्त: {ok} आयात हुईं, {failed} विफल।',
            select: 'कोई खाली न होने वाली XML, CBOX या ZIP फ़ाइल चुनें।', entryLimit: 'ZIP में बहुत अधिक प्रविष्टियाँ हैं।', fileCountLimit: 'ZIP में 64 से अधिक समर्थित XML/CBOX फ़ाइलें हैं।',
            fileLimit: 'कोई समर्थित XML/CBOX फ़ाइल खाली है या 10 MiB से बड़ी है।', noSupported: 'ZIP में समर्थित .xml या .cbox फ़ाइल नहीं है।', encrypted: 'एन्क्रिप्टेड ZIP प्रविष्टियाँ समर्थित नहीं हैं।',
            compression: 'ZIP असमर्थित संपीड़न विधि उपयोग करता है।', malformed: 'ZIP खराब है या असमर्थित ZIP64/मल्टी-डिस्क सुविधा उपयोग करता है।',
            decompressor: 'यह Android WebView सामान्य ZIP फ़ाइल नहीं खोल सकता। Android System WebView अपडेट करके फिर कोशिश करें।', changed: 'ZIP चयन बदल गया। फ़ाइलों की दोबारा जाँच करके फिर पुष्टि करें।', busy: 'ZIP आयात पहले से चल रहा है।'
        },
        ar: {
            prompt: 'اضغط لاختيار .xml أو .cbox أو .zip', ready: 'ZIP جاهز: {name}', summary: '{count} من ملفات XML/CBOX المدعومة · {size}',
            confirm: 'أفهم أن كل ملفات XML/CBOX المدعومة داخل ZIP ستُستورد واحداً تلو الآخر.', import: 'استيراد ملفات ZIP', importing: 'جار استيراد ملفات ZIP… {done}/{total}',
            success: 'اكتمل استيراد ZIP: تم استيراد {ok}/{total} ملفاً.', partial: 'انتهى استيراد ZIP: نجح {ok} وفشل {failed}.',
            select: 'اختر ملف XML أو CBOX أو ZIP غير فارغ.', entryLimit: 'يحتوي ZIP على عدد كبير جداً من العناصر.', fileCountLimit: 'يحتوي ZIP على أكثر من 64 ملف XML/CBOX مدعوماً.',
            fileLimit: 'أحد ملفات XML/CBOX المدعومة فارغ أو أكبر من 10 MiB.', noSupported: 'لا يحتوي ZIP على ملفات .xml أو .cbox مدعومة.', encrypted: 'عناصر ZIP المشفرة غير مدعومة.',
            compression: 'يستخدم ZIP طريقة ضغط غير مدعومة.', malformed: 'ملف ZIP تالف أو يستخدم ZIP64/أقراصاً متعددة غير مدعومة.',
            decompressor: 'لا يستطيع Android WebView هذا فك ZIP القياسي. حدّث Android System WebView ثم أعد المحاولة.', changed: 'تغير ملف ZIP المحدد. راجع الملفات وأكّد مرة أخرى.', busy: 'هناك عملية استيراد ZIP قيد التشغيل بالفعل.'
        }
    };

    class ZipImportError extends Error {
        constructor(code) {
            super(code);
            this.name = 'ZipImportError';
            this.code = code;
        }
    }

    function fail(code) {
        throw new ZipImportError(code);
    }

    function normalizeSupportedLocale(value) {
        if (typeof value !== 'string' || !value) return null;
        if (value.startsWith('zh-Hans-')) {
            const mapped = 'zh-' + value.split('-')[2];
            if (SUPPORTED_LOCALES.has(mapped)) return mapped;
        }
        if (SUPPORTED_LOCALES.has(value)) return value;
        const baseLang = value.split('-')[0];
        if (SUPPORTED_LOCALES.has(baseLang)) return baseLang;
        return null;
    }

    function readLocale() {
        try {
            const value = normalizeSupportedLocale(global.localStorage && global.localStorage.getItem(STORAGE_KEY));
            if (value) return value;
        } catch (_) {}

        const runtimeLocale = normalizeSupportedLocale(global.CleveresSystemLocale);
        if (runtimeLocale) return runtimeLocale;
        try {
            const cachedSystem = normalizeSupportedLocale(global.localStorage && global.localStorage.getItem(SYSTEM_LOCALE_KEY));
            if (cachedSystem) return cachedSystem;
        } catch (_) {}

        try {
            const navLang = normalizeSupportedLocale(global.navigator && global.navigator.language);
            if (navLang) return navLang;
        } catch (_) {}

        return 'en';
    }

    function text(key, values) {
        const locale = readLocale();
        let value = (COPY[locale] && COPY[locale][key]) || COPY.en[key] || key;
        Object.entries(values || {}).forEach(([name, replacement]) => {
            value = value.split('{' + name + '}').join(String(replacement));
        });
        return value;
    }

    function formatBytes(size) {
        if (size < 1024) return size + ' B';
        if (size < 1024 * 1024) return Math.ceil(size / 1024) + ' KiB';
        if (size < 1024 * 1024 * 1024) return (size / (1024 * 1024)).toFixed(size % (1024 * 1024) === 0 ? 0 : 1) + ' MiB';
        return (size / (1024 * 1024 * 1024)).toFixed(1) + ' GiB';
    }

    function u16(view, offset) {
        if (offset < 0 || offset + 2 > view.byteLength) fail('malformed');
        return view.getUint16(offset, true);
    }

    function u32(view, offset) {
        if (offset < 0 || offset + 4 > view.byteLength) fail('malformed');
        return view.getUint32(offset, true);
    }

    function decodeName(bytes, utf8) {
        if (bytes.length === 0 || bytes.length > MAX_NAME_BYTES) fail('malformed');
        let value;
        try {
            value = utf8
                ? new TextDecoder('utf-8', { fatal: true }).decode(bytes)
                : Array.from(bytes, byte => String.fromCharCode(byte)).join('');
        } catch (_) {
            fail('malformed');
        }
        if (!value || value.indexOf('\u0000') >= 0 || /[\u0000-\u001f\u007f]/.test(value)) fail('malformed');
        return value;
    }

    function isSupportedName(name) {
        const lower = name.toLowerCase();
        return lower.endsWith('.xml') || lower.endsWith('.cbox');
    }

    function fileLimitForName(name) {
        return name.toLowerCase().endsWith('.cbox') ? MAX_CBOX_BYTES : MAX_XML_BYTES;
    }

    function safeBasename(name) {
        let base = name.replace(/\\/g, '/').split('/').pop() || 'keybox.xml';
        base = base.replace(/[\u0000-\u001f\u007f]/g, '_').trim();
        if (!base) base = 'keybox.xml';
        if (base.length > 120) {
            const dot = base.lastIndexOf('.');
            const ext = dot >= 0 ? base.slice(dot).slice(0, 8) : '';
            base = base.slice(0, Math.max(1, 120 - ext.length)) + ext;
        }
        return base;
    }

    function allocateUploadNames(entries) {
        const used = new Set();
        return entries.map(entry => {
            const base = safeBasename(entry.name);
            const dot = base.lastIndexOf('.');
            const stem = dot > 0 ? base.slice(0, dot) : base;
            const ext = dot > 0 ? base.slice(dot) : '';
            let candidate = base;
            let suffix = 2;
            while (used.has(candidate.toLowerCase())) {
                const suffixText = '-' + suffix++;
                candidate = stem.slice(0, Math.max(1, 120 - ext.length - suffixText.length)) + suffixText + ext;
            }
            used.add(candidate.toLowerCase());
            return Object.assign({}, entry, { uploadName: candidate });
        });
    }

    async function readRange(blob, start, length) {
        if (!(blob instanceof Blob) || !Number.isSafeInteger(start) || !Number.isSafeInteger(length) || start < 0 || length < 0 || start > blob.size || length > blob.size - start) fail('malformed');
        return new Uint8Array(await blob.slice(start, start + length).arrayBuffer());
    }

    async function findEocd(file) {
        if (!(file instanceof Blob) || !Number.isSafeInteger(file.size) || file.size < 22) fail('malformed');
        const tailStart = Math.max(0, file.size - 65557);
        const tail = await readRange(file, tailStart, file.size - tailStart);
        try {
            const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
            for (let offset = tail.length - 22; offset >= 0; offset--) {
                if (u32(view, offset) !== 0x06054b50) continue;
                const commentLength = u16(view, offset + 20);
                if (offset + 22 + commentLength !== tail.length) continue;
                return {
                    absoluteOffset: tailStart + offset,
                    disk: u16(view, offset + 4),
                    centralDisk: u16(view, offset + 6),
                    diskEntries: u16(view, offset + 8),
                    totalEntries: u16(view, offset + 10),
                    centralSize: u32(view, offset + 12),
                    centralOffset: u32(view, offset + 16)
                };
            }
            fail('malformed');
        } finally {
            tail.fill(0);
        }
    }

    async function parseZipFile(file) {
        if (!(file instanceof Blob) || file.size <= 0) fail('select');
        const eocd = await findEocd(file);
        if (eocd.disk !== 0 || eocd.centralDisk !== 0 || eocd.diskEntries !== eocd.totalEntries || eocd.totalEntries === 0xffff || eocd.centralSize === 0xffffffff || eocd.centralOffset === 0xffffffff) fail('malformed');
        if (eocd.totalEntries > MAX_ARCHIVE_ENTRIES) fail('entryLimit');
        if (eocd.centralSize > MAX_CENTRAL_DIRECTORY_BYTES || eocd.centralOffset > eocd.absoluteOffset || eocd.centralSize > eocd.absoluteOffset - eocd.centralOffset) fail('malformed');

        const central = await readRange(file, eocd.centralOffset, eocd.centralSize);
        try {
            const view = new DataView(central.buffer, central.byteOffset, central.byteLength);
            const entries = [];
            let cursor = 0;
            for (let index = 0; index < eocd.totalEntries; index++) {
                if (cursor + 46 > central.length || u32(view, cursor) !== 0x02014b50) fail('malformed');
                const flags = u16(view, cursor + 8);
                const method = u16(view, cursor + 10);
                const crc = u32(view, cursor + 16);
                const compressedSize = u32(view, cursor + 20);
                const uncompressedSize = u32(view, cursor + 24);
                const nameLength = u16(view, cursor + 28);
                const extraLength = u16(view, cursor + 30);
                const commentLength = u16(view, cursor + 32);
                const diskStart = u16(view, cursor + 34);
                const localOffset = u32(view, cursor + 42);
                const end = cursor + 46 + nameLength + extraLength + commentLength;
                if (end > central.length || diskStart !== 0 || compressedSize === 0xffffffff || uncompressedSize === 0xffffffff || localOffset === 0xffffffff) fail('malformed');
                if ((flags & 0x0041) !== 0) fail('encrypted');
                if (method !== 0 && method !== 8) fail('compression');
                const name = decodeName(central.subarray(cursor + 46, cursor + 46 + nameLength), (flags & 0x0800) !== 0);
                if (!name.endsWith('/') && isSupportedName(name)) {
                    if (uncompressedSize <= 0 || uncompressedSize > fileLimitForName(name) || compressedSize <= 0 || compressedSize > MAX_COMPRESSED_FILE_BYTES) fail('fileLimit');
                    entries.push({ name, flags, method, crc, compressedSize, uncompressedSize, localOffset });
                    if (entries.length > MAX_SUPPORTED_FILES) fail('fileCountLimit');
                }
                cursor = end;
            }
            if (cursor !== central.length) fail('malformed');
            if (entries.length === 0) fail('noSupported');
            const named = allocateUploadNames(entries);
            const totalBytes = named.reduce((sum, entry) => sum + entry.uncompressedSize, 0);
            return { entries: named, centralOffset: eocd.centralOffset, totalBytes };
        } finally {
            central.fill(0);
        }
    }

    let crcTable = null;
    function crc32(bytes) {
        if (!crcTable) {
            crcTable = new Uint32Array(256);
            for (let n = 0; n < 256; n++) {
                let value = n;
                for (let k = 0; k < 8; k++) value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
                crcTable[n] = value >>> 0;
            }
        }
        let crc = 0xffffffff;
        for (let index = 0; index < bytes.length; index++) crc = crcTable[(crc ^ bytes[index]) & 0xff] ^ (crc >>> 8);
        return (crc ^ 0xffffffff) >>> 0;
    }

    async function inflateRawBounded(compressed, expectedSize) {
        if (typeof global.DecompressionStream !== 'function') fail('decompressor');
        let stream;
        try {
            stream = new Blob([compressed]).stream().pipeThrough(new global.DecompressionStream('deflate-raw'));
        } catch (_) {
            fail('decompressor');
        }
        const reader = stream.getReader();
        const chunks = [];
        let total = 0;
        try {
            while (true) {
                const result = await reader.read();
                if (result.done) break;
                const chunk = result.value instanceof Uint8Array ? result.value : new Uint8Array(result.value);
                if (chunk.length > expectedSize - total) fail('fileLimit');
                chunks.push(chunk);
                total += chunk.length;
            }
        } catch (error) {
            try { await reader.cancel(); } catch (_) {}
            chunks.forEach(chunk => chunk.fill(0));
            if (error instanceof ZipImportError) throw error;
            fail('malformed');
        }
        if (total !== expectedSize) {
            chunks.forEach(chunk => chunk.fill(0));
            fail('malformed');
        }
        const output = new Uint8Array(total);
        let offset = 0;
        chunks.forEach(chunk => {
            output.set(chunk, offset);
            offset += chunk.length;
            chunk.fill(0);
        });
        return output;
    }

    async function extractEntry(file, entry, centralOffset) {
        const header = await readRange(file, entry.localOffset, 30);
        let compressed = null;
        try {
            const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
            if (u32(view, 0) !== 0x04034b50) fail('malformed');
            const flags = u16(view, 6);
            const method = u16(view, 8);
            const nameLength = u16(view, 26);
            const extraLength = u16(view, 28);
            if ((flags & 0x0041) !== 0) fail('encrypted');
            if (method !== entry.method || (flags & 0x0809) !== (entry.flags & 0x0809) || nameLength <= 0 || nameLength > MAX_NAME_BYTES) fail('malformed');
            const dataOffset = entry.localOffset + 30 + nameLength + extraLength;
            if (!Number.isSafeInteger(dataOffset) || dataOffset > centralOffset || entry.compressedSize > centralOffset - dataOffset) fail('malformed');
            const localNameBytes = await readRange(file, entry.localOffset + 30, nameLength);
            try {
                if (decodeName(localNameBytes, (flags & 0x0800) !== 0) !== entry.name) fail('malformed');
            } finally {
                localNameBytes.fill(0);
            }
            compressed = await readRange(file, dataOffset, entry.compressedSize);
            let output;
            if (entry.method === 0) {
                if (entry.compressedSize !== entry.uncompressedSize) fail('malformed');
                output = compressed;
                compressed = null;
            } else {
                output = await inflateRawBounded(compressed, entry.uncompressedSize);
            }
            if (output.length !== entry.uncompressedSize || crc32(output) !== entry.crc) {
                output.fill(0);
                fail('malformed');
            }
            return output;
        } finally {
            header.fill(0);
            if (compressed) compressed.fill(0);
        }
    }

    function notify(message, type) {
        if (typeof global.notify === 'function') global.notify(message, type);
    }

    function errorMessage(error) {
        if (error instanceof ZipImportError) return text(error.code);
        return text('malformed');
    }

    let pendingZip = null;
    let busy = false;
    let ui = null;
    let originalLoadFileContent = null;
    let originalResetDropZone = null;
    let installAttempts = 0;
    let installRetryTimer = null;

    function clearPending() {
        pendingZip = null;
        if (!ui) return;
        ui.confirm.checked = false;
        ui.button.disabled = true;
        ui.panel.hidden = true;
        ui.summary.textContent = '';
    }

    function updatePrompt() {
        if (!ui) return;
        const content = document.getElementById('dropZoneContent');
        if (content && !pendingZip && !busy) {
            content.innerHTML = '';
            const drag = document.createElement('div');
            drag.style.cssText = 'font-size:1.5em;margin-bottom:10px;color:#888;';
            drag.textContent = '[ Drag & Drop ]';
            const prompt = document.createElement('div');
            prompt.style.cssText = 'font-size:0.9em;color:#888;';
            prompt.textContent = text('prompt');
            content.appendChild(drag);
            content.appendChild(prompt);
        }
        ui.confirmLabel.textContent = text('confirm');
        ui.button.textContent = text('import');
        if (pendingZip) ui.summary.textContent = text('summary', { count: pendingZip.count, size: formatBytes(pendingZip.totalBytes) });
    }

    async function prepareZip(file) {
        if (busy) {
            notify(text('busy'), 'error');
            return;
        }
        clearPending();
        try {
            const parsed = await parseZipFile(file);
            pendingZip = {
                file,
                count: parsed.entries.length,
                totalBytes: parsed.totalBytes,
                identity: file.name + ':' + file.size + ':' + file.lastModified
            };
            ui.panel.hidden = false;
            ui.confirm.checked = false;
            ui.button.disabled = true;
            const content = document.getElementById('dropZoneContent');
            if (content) {
                content.innerHTML = '';
                const ready = document.createElement('div');
                ready.style.cssText = 'font-size:1.05em;color:var(--accent);font-weight:600;overflow-wrap:anywhere;';
                ready.textContent = text('ready', { name: file.name });
                content.appendChild(ready);
            }
            updatePrompt();
        } catch (error) {
            clearPending();
            notify(errorMessage(error), 'error');
            updatePrompt();
        }
    }

    async function uploadEntry(entry, bytes) {
        if (typeof global.fetchAuth !== 'function') throw new Error('upload unavailable');
        const formData = new FormData();
        const type = entry.uploadName.toLowerCase().endsWith('.cbox') ? 'application/octet-stream' : 'application/xml';
        const file = new File([bytes], entry.uploadName, { type });
        formData.append('file', file);
        formData.append('filename', entry.uploadName);
        const response = await global.fetchAuth('/api/upload_keybox', { method: 'POST', body: formData, timeoutMs: 120000 });
        if (response.ok) {
            let storedName = entry.uploadName;
            try {
                const body = await response.clone().json();
                if (typeof body.filename === 'string' && body.filename) storedName = body.filename;
            } catch (_) {}
            return { ok: true, name: storedName };
        }
        let detail = '';
        try { detail = (await response.text()).replace(/[\r\n]+/g, ' ').trim().slice(0, 160); } catch (_) {}
        return { ok: false, name: entry.uploadName, detail };
    }

    async function importPendingZip() {
        if (busy || !pendingZip) return;
        if (!ui.confirm.checked) {
            ui.button.disabled = true;
            return;
        }
        const selection = pendingZip;
        busy = true;
        ui.button.disabled = true;
        const picker = document.getElementById('kbFilePicker');
        if (picker) picker.disabled = true;
        const results = [];
        try {
            const parsed = await parseZipFile(selection.file);
            const identity = selection.file.name + ':' + selection.file.size + ':' + selection.file.lastModified;
            if (identity !== selection.identity || parsed.entries.length !== selection.count || parsed.totalBytes !== selection.totalBytes) fail('changed');
            for (let index = 0; index < parsed.entries.length; index++) {
                const entry = parsed.entries[index];
                const progress = text('importing', { done: index, total: parsed.entries.length });
                ui.summary.textContent = progress;
                notify(progress, 'working');
                let bytes = null;
                try {
                    bytes = await extractEntry(selection.file, entry, parsed.centralOffset);
                    results.push(await uploadEntry(entry, bytes));
                } catch (error) {
                    results.push({ ok: false, name: entry.uploadName, detail: errorMessage(error) });
                } finally {
                    if (bytes) bytes.fill(0);
                }
                ui.summary.textContent = text('importing', { done: index + 1, total: parsed.entries.length });
            }
            const ok = results.filter(result => result.ok).length;
            const failed = results.length - ok;
            const message = failed === 0
                ? text('success', { ok, total: results.length })
                : text('partial', { ok, failed });
            const failures = results.filter(result => !result.ok).slice(0, 4).map(result => result.name + (result.detail ? ': ' + result.detail : '')).join(' | ');
            notify(failures ? message + ' ' + failures : message, failed === 0 ? 'normal' : 'error');
            if (typeof global.loadKeyInfo === 'function') global.loadKeyInfo();
            if (typeof global.loadKeyboxes === 'function') global.loadKeyboxes();
        } catch (error) {
            notify(errorMessage(error), 'error');
        } finally {
            busy = false;
            if (picker) picker.disabled = false;
            clearPending();
            if (typeof originalResetDropZone === 'function') originalResetDropZone();
            updatePrompt();
        }
    }

    function createUi(dropZone) {
        const panel = document.createElement('div');
        panel.id = 'ct_zip_confirmation';
        panel.hidden = true;
        panel.style.cssText = 'grid-column:1 / -1;border:1px solid var(--border);border-radius:8px;padding:12px;margin:0 0 10px 0;';
        const summary = document.createElement('div');
        summary.id = 'ct_zip_summary';
        summary.style.cssText = 'font-size:0.85em;color:#aaa;margin-bottom:10px;overflow-wrap:anywhere;';
        const row = document.createElement('label');
        row.style.cssText = 'display:flex;gap:10px;align-items:flex-start;min-height:44px;line-height:1.45;cursor:pointer;margin-bottom:10px;';
        const confirm = document.createElement('input');
        confirm.type = 'checkbox';
        confirm.id = 'ct_zip_confirm';
        confirm.style.cssText = 'width:20px;height:20px;min-width:20px;margin-top:1px;';
        const confirmLabel = document.createElement('span');
        confirmLabel.id = 'ct_zip_confirm_label';
        row.appendChild(confirm);
        row.appendChild(confirmLabel);
        const button = document.createElement('button');
        button.id = 'ct_zip_import_btn';
        button.className = 'primary';
        button.type = 'button';
        button.disabled = true;
        button.style.width = '100%';
        panel.appendChild(summary);
        panel.appendChild(row);
        panel.appendChild(button);
        dropZone.parentNode.appendChild(panel);
        confirm.addEventListener('change', function () { button.disabled = !confirm.checked || busy || !pendingZip; });
        button.addEventListener('click', function (event) { event.preventDefault(); event.stopPropagation(); importPendingZip(); });
        panel.addEventListener('click', function (event) { event.stopPropagation(); });
        return { panel, summary, confirm, confirmLabel, button };
    }

    function scheduleInstallRetry() {
        if (installRetryTimer !== null || installAttempts >= MAX_INSTALL_ATTEMPTS) return;
        installAttempts += 1;
        installRetryTimer = global.setTimeout(() => {
            installRetryTimer = null;
            install();
        }, INSTALL_RETRY_MS);
    }

    function install() {
        if (ui || typeof document === 'undefined') return;
        const picker = document.getElementById('kbFilePicker');
        const dropZone = document.getElementById('dropZone');
        if (!picker || !dropZone || typeof global.loadFileContent !== 'function') {
            scheduleInstallRetry();
            return;
        }
        installAttempts = 0;
        originalLoadFileContent = global.loadFileContent;
        originalResetDropZone = typeof global.resetDropZone === 'function' ? global.resetDropZone : null;
        picker.accept = '.xml,.cbox,.zip';
        ui = createUi(dropZone);

        global.loadFileContent = async function (input) {
            const file = input instanceof File ? input : (input && input.files ? input.files[0] : null);
            if (!file) return originalLoadFileContent(input);
            if (busy) {
                notify(text('busy'), 'error');
                return;
            }
            if (file.name.toLowerCase().endsWith('.zip')) {
                await prepareZip(file);
                return;
            }
            clearPending();
            updatePrompt();
            return originalLoadFileContent(input);
        };

        if (originalResetDropZone) {
            global.resetDropZone = function () {
                const result = originalResetDropZone.apply(this, arguments);
                if (!busy) clearPending();
                updatePrompt();
                return result;
            };
        }

        const selector = document.getElementById('ct_language_selector');
        if (selector) selector.addEventListener('change', function () { global.setTimeout(updatePrompt, 0); });
        updatePrompt();
    }

    global.CleveresZipImport = Object.freeze({
        limits: Object.freeze({ MAX_SUPPORTED_FILES, MAX_ARCHIVE_ENTRIES, MAX_XML_BYTES, MAX_CBOX_BYTES }),
        parseZipFile,
        extractEntry,
        allocateUploadNames,
        translations: COPY
    });

    if (typeof document !== 'undefined') {
        if (document.readyState === 'complete') global.setTimeout(install, 0);
        else global.addEventListener('load', install, { once: true });
    }
})(window);


// Source-aware Stored Keyboxes and Verification UX. Runtime ownership stays in ux.js.
(function (global) {
    'use strict';
    if (typeof document === 'undefined') return;

    const PAGE_SIZE = 5;
    const INSTALL_RETRY_MS = 50;
    const MAX_INSTALL_ATTEMPTS = 100;
    const COPY = {
        en: {
            selected: 'selected', deleteSelected: 'Delete selected', previous: 'Previous', next: 'Next', page: 'Page {page} / {pages}',
            root: 'Module root', managed: 'Managed folder', cert: 'Certificate #3 serial', certMissing: 'Certificate #3 serial unavailable',
            deleteConfirm: 'Delete this stored keybox?', bulkConfirm: 'Delete {count} selected keyboxes?', bulkDone: 'Deleted {count} keyboxes',
            keyboxesLoaded: '{count} Keyboxes Loaded', selectFiltered: 'Select filtered', clearFiltered: 'Clear filtered selection',
            search: 'Search', clear: 'Clear', verifySearchPlaceholder: 'Search verification results...', verifying: 'Verifying...',
            noVerify: 'No keyboxes to verify', noVerifyMatch: 'No verification results match your search.', loading: 'Loading...',
            noStored: 'No keyboxes stored.', noStoredMatch: 'No keyboxes match your filter.', delete: 'Delete'
        },
        tr: {
            selected: 'seçili', deleteSelected: 'Seçilileri sil', previous: 'Önceki', next: 'Sonraki', page: 'Sayfa {page} / {pages}',
            root: 'Modül kökü', managed: 'Yönetilen klasör', cert: '3. sertifika seri no', certMissing: '3. sertifika seri no yok',
            deleteConfirm: 'Bu kayıtlı keybox silinsin mi?', bulkConfirm: 'Seçili {count} keybox silinsin mi?', bulkDone: '{count} keybox silindi',
            keyboxesLoaded: '{count} Keybox Yüklendi', selectFiltered: 'Filtrelenenleri seç', clearFiltered: 'Filtre seçimini temizle',
            search: 'Ara', clear: 'Temizle', verifySearchPlaceholder: 'Doğrulama sonuçlarında ara...', verifying: 'Doğrulanıyor...',
            noVerify: 'Doğrulanacak keybox yok', noVerifyMatch: 'Aramanızla eşleşen doğrulama sonucu yok.', loading: 'Yükleniyor...',
            noStored: 'Kayıtlı keybox yok.', noStoredMatch: 'Filtrenizle eşleşen keybox yok.', delete: 'Sil'
        },
        'zh-CN': {
            selected: '已选择', deleteSelected: '删除所选', previous: '上一页', next: '下一页', page: '第 {page} / {pages} 页',
            root: '模块根目录', managed: '受管目录', cert: '第 3 个证书序列号', certMissing: '无第 3 个证书序列号',
            deleteConfirm: '删除此已存储密钥盒？', bulkConfirm: '删除选中的 {count} 个密钥盒？', bulkDone: '已删除 {count} 个密钥盒',
            keyboxesLoaded: '已加载 {count} 个 Keybox', selectFiltered: '选择筛选结果', clearFiltered: '清除筛选选择',
            search: '搜索', clear: '清除', verifySearchPlaceholder: '搜索验证结果...', verifying: '正在验证...',
            noVerify: '没有可验证的 Keybox', noVerifyMatch: '没有符合搜索条件的验证结果。', loading: '正在加载...',
            noStored: '没有已存储的 Keybox。', noStoredMatch: '没有符合筛选条件的 Keybox。', delete: '删除'
        },
        es: {
            selected: 'seleccionados', deleteSelected: 'Eliminar seleccionados', previous: 'Anterior', next: 'Siguiente', page: 'Página {page} / {pages}',
            root: 'Raíz del módulo', managed: 'Carpeta administrada', cert: 'Serie del certificado n.º 3', certMissing: 'Serie del certificado n.º 3 no disponible',
            deleteConfirm: '¿Eliminar esta keybox guardada?', bulkConfirm: '¿Eliminar {count} keyboxes seleccionadas?', bulkDone: 'Se eliminaron {count} keyboxes',
            keyboxesLoaded: '{count} Keyboxes cargadas', selectFiltered: 'Seleccionar filtradas', clearFiltered: 'Limpiar selección filtrada',
            search: 'Buscar', clear: 'Limpiar', verifySearchPlaceholder: 'Buscar resultados de verificación...', verifying: 'Verificando...',
            noVerify: 'No hay keyboxes para verificar', noVerifyMatch: 'Ningún resultado de verificación coincide con la búsqueda.', loading: 'Cargando...',
            noStored: 'No hay keyboxes guardadas.', noStoredMatch: 'Ninguna keybox coincide con el filtro.', delete: 'Eliminar'
        },
        de: {
            selected: 'ausgewählt', deleteSelected: 'Auswahl löschen', previous: 'Zurück', next: 'Weiter', page: 'Seite {page} / {pages}',
            root: 'Modulstamm', managed: 'Verwalteter Ordner', cert: 'Seriennummer Zertifikat Nr. 3', certMissing: 'Seriennummer Zertifikat Nr. 3 nicht verfügbar',
            deleteConfirm: 'Diese gespeicherte Keybox löschen?', bulkConfirm: '{count} ausgewählte Keyboxen löschen?', bulkDone: '{count} Keyboxen gelöscht',
            keyboxesLoaded: '{count} Keyboxen geladen', selectFiltered: 'Gefilterte auswählen', clearFiltered: 'Gefilterte Auswahl löschen',
            search: 'Suchen', clear: 'Leeren', verifySearchPlaceholder: 'Prüfergebnisse durchsuchen...', verifying: 'Prüfung läuft...',
            noVerify: 'Keine Keyboxen zum Prüfen', noVerifyMatch: 'Keine Prüfergebnisse entsprechen der Suche.', loading: 'Wird geladen...',
            noStored: 'Keine Keyboxen gespeichert.', noStoredMatch: 'Keine Keybox entspricht dem Filter.', delete: 'Löschen'
        },
        ru: {
            selected: 'выбрано', deleteSelected: 'Удалить выбранные', previous: 'Назад', next: 'Далее', page: 'Страница {page} / {pages}',
            root: 'Корень модуля', managed: 'Управляемая папка', cert: 'Серийный номер сертификата №3', certMissing: 'Серийный номер сертификата №3 недоступен',
            deleteConfirm: 'Удалить этот сохраненный keybox?', bulkConfirm: 'Удалить выбранные keybox: {count}?', bulkDone: 'Удалено keybox: {count}',
            keyboxesLoaded: 'Загружено Keybox: {count}', selectFiltered: 'Выбрать отфильтрованные', clearFiltered: 'Очистить выбор фильтра',
            search: 'Поиск', clear: 'Очистить', verifySearchPlaceholder: 'Поиск по результатам проверки...', verifying: 'Проверка...',
            noVerify: 'Нет keybox для проверки', noVerifyMatch: 'Нет результатов проверки, соответствующих поиску.', loading: 'Загрузка...',
            noStored: 'Нет сохраненных keybox.', noStoredMatch: 'Нет keybox, соответствующих фильтру.', delete: 'Удалить'
        },
        id: {
            selected: 'dipilih', deleteSelected: 'Hapus pilihan', previous: 'Sebelumnya', next: 'Berikutnya', page: 'Halaman {page} / {pages}',
            root: 'Root modul', managed: 'Folder terkelola', cert: 'Serial sertifikat #3', certMissing: 'Serial sertifikat #3 tidak tersedia',
            deleteConfirm: 'Hapus keybox tersimpan ini?', bulkConfirm: 'Hapus {count} keybox terpilih?', bulkDone: '{count} keybox dihapus',
            keyboxesLoaded: '{count} Keybox dimuat', selectFiltered: 'Pilih yang difilter', clearFiltered: 'Hapus pilihan filter',
            search: 'Cari', clear: 'Bersihkan', verifySearchPlaceholder: 'Cari hasil verifikasi...', verifying: 'Memverifikasi...',
            noVerify: 'Tidak ada keybox untuk diverifikasi', noVerifyMatch: 'Tidak ada hasil verifikasi yang cocok dengan pencarian.', loading: 'Memuat...',
            noStored: 'Tidak ada keybox tersimpan.', noStoredMatch: 'Tidak ada keybox yang cocok dengan filter.', delete: 'Hapus'
        },
        hi: {
            selected: 'चयनित', deleteSelected: 'चयनित हटाएँ', previous: 'पिछला', next: 'अगला', page: 'पृष्ठ {page} / {pages}',
            root: 'मॉड्यूल रूट', managed: 'प्रबंधित फ़ोल्डर', cert: 'सर्टिफिकेट #3 सीरियल', certMissing: 'सर्टिफिकेट #3 सीरियल उपलब्ध नहीं',
            deleteConfirm: 'यह सहेजा Keybox हटाएँ?', bulkConfirm: 'चयनित {count} Keybox हटाएँ?', bulkDone: '{count} Keybox हटाए गए',
            keyboxesLoaded: '{count} Keybox लोड हुए', selectFiltered: 'फ़िल्टर किए चुनें', clearFiltered: 'फ़िल्टर चयन साफ़ करें',
            search: 'खोजें', clear: 'साफ़ करें', verifySearchPlaceholder: 'सत्यापन परिणाम खोजें...', verifying: 'सत्यापन हो रहा है...',
            noVerify: 'सत्यापित करने के लिए Keybox नहीं', noVerifyMatch: 'खोज से मेल खाता सत्यापन परिणाम नहीं है।', loading: 'लोड हो रहा है...',
            noStored: 'कोई सहेजा Keybox नहीं।', noStoredMatch: 'फ़िल्टर से मेल खाता Keybox नहीं है।', delete: 'हटाएँ'
        },
        ar: {
            selected: 'محدد', deleteSelected: 'حذف المحدد', previous: 'السابق', next: 'التالي', page: 'الصفحة {page} / {pages}',
            root: 'جذر الوحدة', managed: 'المجلد المدار', cert: 'الرقم التسلسلي للشهادة 3', certMissing: 'الرقم التسلسلي للشهادة 3 غير متاح',
            deleteConfirm: 'حذف Keybox المحفوظ هذا؟', bulkConfirm: 'حذف {count} من Keybox المحددة؟', bulkDone: 'تم حذف {count} من Keybox',
            keyboxesLoaded: 'تم تحميل {count} Keybox', selectFiltered: 'تحديد النتائج المفلترة', clearFiltered: 'مسح تحديد الفلتر',
            search: 'بحث', clear: 'مسح', verifySearchPlaceholder: 'البحث في نتائج التحقق...', verifying: 'جارٍ التحقق...',
            noVerify: 'لا توجد Keybox للتحقق', noVerifyMatch: 'لا توجد نتائج تحقق تطابق البحث.', loading: 'جارٍ التحميل...',
            noStored: 'لا توجد Keybox محفوظة.', noStoredMatch: 'لا توجد Keybox تطابق الفلتر.', delete: 'حذف'
        }
    };

    let inventory = [];
    let selected = new Set();
    let page = 1;
    let installed = false;
    let loading = false;
    let originalLoad = null;
    let verificationItems = [];
    let verificationPage = 1;
    let verificationQuery = '';
    let inventoryController = null;
    let verificationController = null;
    const deletingIds = new Set();
    let bulkDeleteBusy = false;
    let keyboxMutationQueue = Promise.resolve();
    let installAttempts = 0;
    let installRetryTimer = null;

    function locale() {
        try {
            const value = global.CleveresI18n && global.CleveresI18n.locale;
            return COPY[value] ? value : 'en';
        } catch (_) {
            return 'en';
        }
    }

    function t(key, values) {
        let value = (COPY[locale()] || COPY.en)[key] || COPY.en[key] || key;
        Object.entries(values || {}).forEach(([name, replacement]) => {
            value = value.split('{' + name + '}').join(String(replacement));
        });
        return value;
    }

    function statusLabel() {
        const node = document.getElementById('keyboxStatus');
        if (!node) return;
        const match = (node.textContent || '').match(/(\d+)/);
        if (match) {
    const value = t('keyboxesLoaded', { count: match[1] });
    if (node.textContent !== value) node.textContent = value;
}
    }

    function filtered() {
        const filter = (document.getElementById('keyboxFilter')?.value || '').trim().toLowerCase();
        return filter
            ? inventory.filter(item => (String(item.filename || '') + ' ' + String(item.certificate_serial || '')).toLowerCase().includes(filter))
            : inventory.slice();
    }

    function toggleFilteredSelection() {
        const items = filtered();
        if (!items.length) return;
        const allSelected = items.every(item => selected.has(item.id));
        items.forEach(item => {
            if (allSelected) selected.delete(item.id);
            else selected.add(item.id);
        });
        render();
    }

    function ensureControls() {
        const list = document.getElementById('storedKeyboxesList');
        if (!list || document.getElementById('ct_keybox_bulk')) return;
        list.style.maxHeight = 'none';
        list.style.overflowY = 'visible';

        const toolbar = document.createElement('div');
        toolbar.id = 'ct_keybox_bulk';
        toolbar.style.cssText = 'display:flex;gap:8px;align-items:center;justify-content:space-between;margin:8px 0;flex-wrap:wrap;flex-direction:column;align-items:stretch;';

        if (typeof window.matchMedia === 'function') {
            const mq = window.matchMedia('(min-width: 600px)');
            const updateLayout = (e) => { toolbar.style.flexDirection = e.matches ? 'row' : 'column'; toolbar.style.alignItems = e.matches ? 'center' : 'stretch'; };
            mq.addListener(updateLayout);
            updateLayout(mq);
        }


        const left = document.createElement('div');
        left.style.cssText = 'display:flex;gap:8px;align-items:center;flex-wrap:wrap;';
        const count = document.createElement('span');
        count.id = 'ct_keybox_selected_count';
        count.style.cssText = 'font-size:.82em;color:#888;';
        const selectFilteredButton = document.createElement('button');
        selectFilteredButton.id = 'ct_keybox_select_filtered';
        selectFilteredButton.type = 'button';
        selectFilteredButton.style.cssText = 'padding:8px 12px;font-size:.82em;';
        selectFilteredButton.addEventListener('click', toggleFilteredSelection);
        left.append(count, selectFilteredButton);

        const deleteButton = document.createElement('button');
        deleteButton.id = 'ct_keybox_delete_selected';
        deleteButton.type = 'button';
        deleteButton.className = 'danger';
        deleteButton.style.cssText = 'padding:8px 12px;font-size:.82em;';
        deleteButton.addEventListener('click', bulkDelete);
        toolbar.append(left, deleteButton);
        list.parentNode.insertBefore(toolbar, list);

        const pager = document.createElement('div');
        pager.id = 'ct_keybox_pager';
        pager.style.cssText = 'display:flex;gap:8px;align-items:center;justify-content:center;margin-top:10px;';
        list.insertAdjacentElement('afterend', pager);
    }

    function updateControls(pages) {
        ensureControls();
        const count = document.getElementById('ct_keybox_selected_count');
        const deleteButton = document.getElementById('ct_keybox_delete_selected');
        const selectFilteredButton = document.getElementById('ct_keybox_select_filtered');
        const visible = filtered();
        const allVisibleSelected = visible.length > 0 && visible.every(item => selected.has(item.id));

        if (count) count.textContent = selected.size + ' ' + t('selected');
        if (deleteButton) {
            deleteButton.textContent = t('deleteSelected');
            deleteButton.disabled = bulkDeleteBusy || selected.size === 0;
        }
        if (selectFilteredButton) {
            selectFilteredButton.textContent = allVisibleSelected ? t('clearFiltered') : t('selectFiltered');
            selectFilteredButton.disabled = visible.length === 0;
        }

        const pager = document.getElementById('ct_keybox_pager');
        if (!pager) return;
        pager.innerHTML = '';
        const prev = document.createElement('button');
        prev.type = 'button';
        prev.textContent = t('previous');
        prev.disabled = page <= 1;
        prev.addEventListener('click', () => { page--; render(); });
        const label = document.createElement('span');
        label.style.cssText = 'font-size:.82em;color:#888;';
        label.textContent = t('page', { page, pages });
        const next = document.createElement('button');
        next.type = 'button';
        next.textContent = t('next');
        next.disabled = page >= pages;
        next.addEventListener('click', () => { page++; render(); });
        pager.append(prev, label, next);
    }

    function render() {
        const list = document.getElementById('storedKeyboxesList');
        if (!list) return;
        ensureControls();
        const clear = document.getElementById('clearKeyboxFilterBtn');
        const filter = document.getElementById('keyboxFilter');
        if (clear) clear.style.display = filter && filter.value ? 'flex' : 'none';

        const items = filtered();
        const pages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
        page = Math.min(Math.max(1, page), pages);
        list.innerHTML = '';

        if (loading) {
            const node = document.createElement('div');
            node.style.cssText = 'padding:10px;text-align:center;color:#888';
            node.textContent = t('loading');
            list.appendChild(node);
            updateControls(pages);
            return;
        }
        if (items.length === 0) {
            const node = document.createElement('div');
            node.style.cssText = 'padding:10px;text-align:center;color:#666';
            node.textContent = inventory.length ? t('noStoredMatch') : t('noStored');
            list.appendChild(node);
            updateControls(pages);
            return;
        }

        items.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE).forEach(item => {
            const row = document.createElement('div');
            row.className = 'ct-keybox-item row';
            row.style.cssText = 'padding:10px;border-bottom:1px solid var(--border);display:flex;flex-direction:row;align-items:center;justify-content:space-between;gap:12px;width:100%;box-sizing:border-box;flex-wrap:nowrap;';
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = selected.has(item.id);
            box.setAttribute('aria-label', 'Select ' + item.filename);
            box.style.cssText = 'flex:0 0 20px;width:20px;height:20px;margin:0;cursor:pointer;';
            box.addEventListener('change', () => {
                if (box.checked) selected.add(item.id);
                else selected.delete(item.id);
                updateControls(pages);
            });

            const body = document.createElement('div');
            body.style.cssText = 'flex:1 1 auto;min-width:0;line-height:1.4;';
            const name = document.createElement('div');
            name.style.cssText = 'overflow-wrap:anywhere;word-break:break-word;font-weight:500;';
            name.textContent = String(item.filename || '');
            const meta = document.createElement('div');
            meta.style.cssText = 'font-size:.78em;color:#888;margin-top:3px;overflow-wrap:anywhere;word-break:break-word;';
            const scope = item.scope === 'root' ? t('root') : t('managed');
            const cert = item.certificate_serial ? t('cert') + ': ' + item.certificate_serial : t('certMissing');
            meta.textContent = scope + ' | ' + cert;
            body.append(name, meta);

            const remove = document.createElement('button');
            remove.type = 'button';
            remove.className = 'danger';
            remove.style.cssText = 'padding:8px 12px;font-size:.82em;flex:0 0 auto;width:auto;margin:0;white-space:nowrap;';
            remove.textContent = t('delete');
            remove.addEventListener('click', () => deleteOne(item));
            row.append(box, body, remove);
            list.appendChild(row);
        });
        updateControls(pages);
    }

    function normalizeKeyboxScope(value) {
        if (value === 'root') return 'root';
        if (value === 'keyboxes' || value === 'managed') return 'keyboxes';
        return '';
    }

    async function refreshInventory(options = {}) {
        if (typeof global.fetchAuth !== 'function' || (options.signal && options.signal.aborted)) return;
        const previousController = inventoryController;
        if (previousController) previousController.abort();
        const controller = new AbortController();
        inventoryController = controller;
        const externalSignal = options.signal;
        const abortFromExternal = () => controller.abort();
        if (externalSignal) externalSignal.addEventListener('abort', abortFromExternal, { once: true });
        const requestOptions = Object.assign({}, options, { signal: controller.signal });
        loading = true;
        render();
        try {
            const response = await global.fetchAuth('/api/keybox_inventory', requestOptions);
            if (!response.ok) throw new Error(await response.text());
            const data = await response.json();
            if (controller.signal.aborted) return;
            inventory = Array.isArray(data)
                ? data.slice(0, 4096).map(item => ({
                    id: String(item?.id ?? '').slice(0, 128),
                    filename: String(item?.filename ?? '').slice(0, 256),
                    scope: item?.scope === 'root' || item?.scope === 'keyboxes' || item?.scope === 'managed' ? item.scope : '',
                    certificate_serial: String(item?.certificate_serial ?? '').slice(0, 256)
                })).filter(item => item.id && item.filename && item.scope)
                : [];
            const ids = new Set(inventory.map(item => item.id));
            selected = new Set(Array.from(selected).filter(id => ids.has(id)));
        } catch (error) {
            if (controller.signal.aborted || (error && error.name === 'AbortError')) return;
            if (typeof global.notify === 'function') global.notify('Error: ' + error.message, 'error');
        } finally {
            if (externalSignal) externalSignal.removeEventListener('abort', abortFromExternal);
            if (inventoryController !== controller) return;
            inventoryController = null;
            if (controller.signal.aborted) return;
            loading = false;
            render();
            statusLabel();
        }
    }

    function enqueueKeyboxMutation(task) {
        const operation = keyboxMutationQueue.catch(() => {}).then(task);
        keyboxMutationQueue = operation.catch(() => {});
        return operation;
    }

    async function deleteOne(item) {
        if (!item || !item.id || deletingIds.has(item.id)) return;
        deletingIds.add(item.id);
        return enqueueKeyboxMutation(async () => {
            try {
                if (typeof global.confirm === 'function' && !global.confirm(t('deleteConfirm'))) return;
                const scope = normalizeKeyboxScope(item.scope);
                if (!scope) return;
                const body = new URLSearchParams();
                body.set('filename', item.filename);
                body.set('scope', scope);
                const response = await global.fetchAuth('/api/delete_keybox', { method: 'POST', body });
                if (!response.ok) {
                    if (typeof global.notify === 'function') global.notify('Error: ' + await response.text(), 'error');
                    return;
                }
                selected.delete(item.id);
                await reloadAll();
            } catch (error) {
                if (typeof global.notify === 'function') global.notify('Error: ' + (error.message || error), 'error');
            } finally {
                deletingIds.delete(item.id);
                render();
            }
        });
    }

    async function bulkDelete() {
        if (bulkDeleteBusy) return;
        bulkDeleteBusy = true;
        return enqueueKeyboxMutation(async () => {
            try {
                const items = inventory
                    .filter(item => selected.has(item.id))
                    .map(item => ({ item, scope: normalizeKeyboxScope(item.scope) }))
                    .filter(entry => entry.scope);
                if (!items.length) return;
                if (typeof global.confirm === 'function' && !global.confirm(t('bulkConfirm', { count: items.length }))) return;
                const body = new URLSearchParams();
                body.set('items', JSON.stringify(items.map(({ item, scope }) => ({ filename: item.filename, scope }))));
                const response = await global.fetchAuth('/api/delete_keyboxes', { method: 'POST', body });
                let payload = null;
                try { payload = await response.clone().json(); } catch (_) {}
                if (!response.ok && !payload) {
                    if (typeof global.notify === 'function') global.notify('Error: ' + await response.text(), 'error');
                    return;
                }
                if (typeof global.notify === 'function') {
                    global.notify(t('bulkDone', { count: payload?.deleted ?? items.length }), payload?.failed ? 'error' : 'normal');
                }
                selected.clear();
                await reloadAll();
            } catch (error) {
                if (typeof global.notify === 'function') global.notify('Error: ' + (error.message || error), 'error');
            } finally {
                bulkDeleteBusy = false;
                render();
            }
        });
    }

    async function reloadAll() {
        if (typeof originalLoad === 'function') await originalLoad();
        await refreshInventory();
        if (typeof global.loadKeyInfo === 'function') global.loadKeyInfo();
    }

    function ensureVerificationControls() {
        const result = document.getElementById('verifyResult');
        if (!result || document.getElementById('ct_verify_controls')) return;

        const controls = document.createElement('div');
        controls.id = 'ct_verify_controls';
        controls.style.cssText = 'display:flex;gap:8px;align-items:center;margin:10px 0;flex-wrap:wrap;';
        controls.className = 'ct-verify-controls';
        const input = document.createElement('input');
        input.id = 'ct_verify_filter';
        input.type = 'search';
        input.placeholder = t('verifySearchPlaceholder');
        input.setAttribute('aria-label', t('verifySearchPlaceholder'));
        input.spellcheck = false;
        input.autocomplete = 'off';
        input.style.cssText = 'flex:1;min-width:180px;';

        const searchButton = document.createElement('button');
        searchButton.id = 'ct_verify_search';
        searchButton.type = 'button';
        searchButton.textContent = t('search');
        const applySearch = () => {
            verificationQuery = input.value.trim().toLowerCase();
            verificationPage = 1;
            renderVerification();
        };
        searchButton.addEventListener('click', applySearch);
        input.addEventListener('input', applySearch);
        input.addEventListener('search', applySearch);
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                applySearch();
            }
        });

        const clearButton = document.createElement('button');
        clearButton.id = 'ct_verify_clear';
        clearButton.type = 'button';
        clearButton.textContent = t('clear');
        clearButton.addEventListener('click', () => {
            if (input.value || verificationQuery) {
                input.value = '';
                verificationQuery = '';
                verificationPage = 1;
                renderVerification();
            } else if (verificationItems.length > 0) {
                verificationItems = [];
                verificationQuery = '';
                verificationPage = 1;
                input.value = '';
                renderVerification();
            }
        });
        controls.append(input, searchButton, clearButton);
        result.parentNode.insertBefore(controls, result);

        const pager = document.createElement('div');
        pager.id = 'ct_verify_pager';
        pager.style.cssText = 'display:flex;gap:8px;align-items:center;justify-content:center;margin-top:10px;';
        result.insertAdjacentElement('afterend', pager);
    }

    function filteredVerification() {
        if (!verificationQuery) return verificationItems.slice();
        return verificationItems.filter(item => {
            const haystack = [item.filename, item.status, item.certificate_serial, item.details]
                .map(value => String(value || ''))
                .join(' ')
                .toLowerCase();
            return haystack.includes(verificationQuery);
        });
    }

    function updateVerificationPager(pages) {
        ensureVerificationControls();
        const pager = document.getElementById('ct_verify_pager');
        if (!pager) return;
        if (pages <= 1) {
            pager.style.display = 'none';
            pager.innerHTML = '';
            return;
        }
        pager.style.display = 'flex';
        pager.innerHTML = '';
        const prev = document.createElement('button');
        prev.type = 'button';
        prev.textContent = t('previous');
        prev.disabled = verificationPage <= 1;
        prev.addEventListener('click', () => { verificationPage--; renderVerification(); });
        const label = document.createElement('span');
        label.style.cssText = 'font-size:.82em;color:#888;';
        label.textContent = t('page', { page: verificationPage, pages });
        const next = document.createElement('button');
        next.type = 'button';
        next.textContent = t('next');
        next.disabled = verificationPage >= pages;
        next.addEventListener('click', () => { verificationPage++; renderVerification(); });
        pager.append(prev, label, next);
    }

    function renderVerification() {
        const result = document.getElementById('verifyResult');
        if (!result) return;
        ensureVerificationControls();
        const input = document.getElementById('ct_verify_filter');
        if (input) input.placeholder = t('verifySearchPlaceholder');
        const searchButton = document.getElementById('ct_verify_search');
        if (searchButton) searchButton.textContent = t('search');
        const clearButton = document.getElementById('ct_verify_clear');
        if (clearButton) clearButton.textContent = t('clear');

        const items = filteredVerification();
        const pages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
        verificationPage = Math.min(Math.max(1, verificationPage), pages);
        result.innerHTML = '';

        if (verificationItems.length === 0) {
            result.textContent = t('noVerify');
            updateVerificationPager(pages);
            return;
        }
        if (items.length === 0) {
            result.textContent = t('noVerifyMatch');
            updateVerificationPager(pages);
            return;
        }

        items.slice((verificationPage - 1) * PAGE_SIZE, verificationPage * PAGE_SIZE).forEach((item, index, array) => {
            const row = document.createElement('div');
            row.style.cssText = 'padding:8px 0;overflow-wrap:anywhere' + (index !== array.length - 1 ? ';border-bottom:1px solid var(--border)' : '');
            const title = document.createElement('div');
            title.style.fontWeight = '600';
            title.textContent = String(item.filename || '') + ' - ' + String(item.status || '');
            const meta = document.createElement('div');
            meta.style.cssText = 'font-size:.8em;color:#888;margin-top:2px';
            meta.textContent = item.certificate_serial ? t('cert') + ': ' + item.certificate_serial : t('certMissing');
            const details = document.createElement('div');
            details.style.cssText = 'font-size:.8em;color:#aaa;margin-top:2px';
            details.textContent = String(item.details || '');
            row.append(title, meta, details);
            result.appendChild(row);
        });
        updateVerificationPager(pages);
    }

    async function verify() {
        if (verificationController) verificationController.abort();
        const controller = new AbortController();
        verificationController = controller;
        const result = document.getElementById('verifyResult');
        ensureVerificationControls();
        if (result) result.textContent = t('verifying');
        try {
            const response = await global.fetchAuth('/api/verify_keyboxes', { method: 'POST', signal: controller.signal });
            if (!response.ok) throw new Error(await response.text());
            const data = await response.json();
            if (controller.signal.aborted) return;
            verificationItems = Array.isArray(data)
                ? data.slice(0, 4096).map(item => ({
                    filename: String(item?.filename ?? '').slice(0, 256),
                    status: String(item?.status ?? 'UNKNOWN').slice(0, 128),
                    certificate_serial: String(item?.certificate_serial ?? '').slice(0, 256),
                    details: String(item?.details ?? '').slice(0, 2048)
                })).filter(item => item.filename)
                : [];
            verificationPage = 1;
            verificationQuery = '';
            const input = document.getElementById('ct_verify_filter');
            if (input) input.value = '';
            renderVerification();
        } catch (error) {
            if (controller.signal.aborted || (error && error.name === 'AbortError')) return;
            throw error;
        } finally {
            if (verificationController === controller) verificationController = null;
        }
    }

    function cancelVerification() {
        if (verificationController) verificationController.abort();
    }

    function scheduleInstallRetry() {
        if (installRetryTimer !== null || installAttempts >= MAX_INSTALL_ATTEMPTS) return;
        installAttempts += 1;
        installRetryTimer = global.setTimeout(() => {
            installRetryTimer = null;
            install();
        }, INSTALL_RETRY_MS);
    }

    function install() {
        if (installed) return;
        if (!document.getElementById('storedKeyboxesList') || typeof global.loadKeyboxes !== 'function') {
            scheduleInstallRetry();
            return;
        }
        installAttempts = 0;
        installed = true;
        originalLoad = global.loadKeyboxes;
        global.renderKeyboxes = render;
        global.loadKeyboxes = async function (options = {}) {
            const value = await originalLoad.apply(this, arguments);
            if (options.signal && options.signal.aborted) return value;
            await refreshInventory(options);
            return value;
        };
        global.verifyKeyboxes = verify;
        global.cancelKeyboxVerification = cancelVerification;
        const filter = document.getElementById('keyboxFilter');
        if (filter) filter.addEventListener('input', () => { page = 1; render(); });
        ensureControls();
        ensureVerificationControls();
        refreshInventory();
        statusLabel();
        const status = document.getElementById('keyboxStatus');
        if (status && typeof global.MutationObserver === 'function') {
            new global.MutationObserver(statusLabel).observe(status, { childList: true, characterData: true, subtree: true });
        }
    }

    if (document.readyState === 'complete') global.setTimeout(install, 0);
    else if (typeof global.addEventListener === 'function') global.addEventListener('load', install, { once: true });
})(window);
