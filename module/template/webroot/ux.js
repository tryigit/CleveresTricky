(function (global) {
    'use strict';

    const bridge = global.CleveresBridge;
    if (!bridge || typeof document === 'undefined') return;

    const STORAGE_KEY = 'cleverestricky.language.v1';
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
        tr: {
            'Dashboard': 'Gösterge Paneli', 'Identity': 'Kimlik', 'Apps': 'Uygulamalar', 'Keyboxes': 'Keyboxlar',
            'Info & Resources': 'Bilgi ve Kaynaklar', 'Guide': 'Kılavuz', 'Logs': 'Günlükler', 'Editor': 'Düzenleyici',
            'Donate': 'Bağış', 'Profiles': 'Profiller', 'Security Patch': 'Güvenlik Yaması',
            'Core Protection': 'Temel Koruma', 'Global Mode': 'Global Mod', 'Auto Keybox Check': 'Otomatik Keybox Kontrolü',
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
            'Open Telegram Community': 'Telegram Topluluğunu Aç', 'Join Telegram Community': 'Telegram Topluluğuna Katıl',
            'Save profile': 'Profili kaydet', 'Clone': 'Klonla', 'Delete': 'Sil', 'Profile saved': 'Profil kaydedildi',
            'System / preinstalled packages are included in this search.': 'Sistem / ön yüklü paketler de bu aramaya dahildir.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Yerleşik çeviriler tamamen yereldir ve ağ bağlantısı gerektirmez. Buradan başka bir dil seçmediğiniz sürece varsayılan dil İngilizcedir.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt içindeki paketleri Android gerçek Keystore yolunda tutar. DRM güvenlik seviyesini taklit etmez.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Debug build kurmadan ek çalışma zamanı tanılamalarını açar. Logları topladıktan sonra kapatın.',
            'DRM passthrough enabled': 'DRM geçiş modu etkin', 'DRM passthrough disabled': 'DRM geçiş modu devre dışı',
            'Debug logging enabled': 'Hata ayıklama günlükleri etkin', 'Debug logging disabled': 'Hata ayıklama günlükleri devre dışı',
            'Could not update DRM setting': 'DRM ayarı güncellenemedi', 'Could not update debug logging': 'Hata ayıklama günlüğü ayarı güncellenemedi',
            'All major features and runtime paths in one place.': 'Tüm temel özellikler ve çalışma yolları tek yerde.'
        },
        'zh-CN': {
            'Dashboard': '仪表盘', 'Identity': '身份', 'Apps': '应用', 'Keyboxes': '密钥盒', 'Info & Resources': '信息与资源',
            'Guide': '指南', 'Logs': '日志', 'Editor': '编辑器', 'Donate': '捐赠', 'Profiles': '配置档案',
            'Security Patch': '安全补丁', 'Core Protection': '核心保护', 'Global Mode': '全局模式',
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
            'Open Telegram Community': '打开 Telegram 社区', 'Join Telegram Community': '加入 Telegram 社区',
            'Save profile': '保存配置', 'Clone': '克隆', 'Delete': '删除',
            'System / preinstalled packages are included in this search.': '此搜索也包含系统 / 预装应用。',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': '内置翻译完全在本地运行，不需要网络。除非在这里选择其他语言，否则默认使用英语。',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": '让 drm_packages.txt 中的包继续使用 Android 真实 Keystore 路径，不会伪造 DRM 安全级别。',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': '无需安装调试版本即可启用额外运行时诊断。收集日志后请关闭。',
            'DRM passthrough enabled': 'DRM 直通已启用', 'DRM passthrough disabled': 'DRM 直通已关闭',
            'Debug logging enabled': '调试日志已启用', 'Debug logging disabled': '调试日志已关闭',
            'Could not update DRM setting': '无法更新 DRM 设置', 'Could not update debug logging': '无法更新调试日志设置',
            'All major features and runtime paths in one place.': '所有主要功能和运行路径集中说明。'
        },
        es: {
            'Dashboard': 'Panel', 'Identity': 'Identidad', 'Apps': 'Apps', 'Keyboxes': 'Keyboxes', 'Info & Resources': 'Info y recursos',
            'Guide': 'Guía', 'Logs': 'Registros', 'Editor': 'Editor', 'Donate': 'Donar', 'Profiles': 'Perfiles',
            'Security Patch': 'Parche de seguridad', 'Core Protection': 'Protección principal', 'Global Mode': 'Modo global',
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
            'Open Telegram Community': 'Abrir comunidad de Telegram', 'Join Telegram Community': 'Unirse a la comunidad de Telegram',
            'Save profile': 'Guardar perfil', 'Clone': 'Clonar', 'Delete': 'Eliminar',
            'System / preinstalled packages are included in this search.': 'La búsqueda incluye paquetes del sistema y preinstalados.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Las traducciones integradas son locales y no requieren conexión. El inglés es el idioma predeterminado hasta que elijas otro aquí.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mantiene los paquetes de drm_packages.txt en la ruta Keystore real de Android. No falsifica el nivel de seguridad DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Activa diagnósticos adicionales sin instalar una compilación debug. Desactívalo después de recoger los registros.',
            'DRM passthrough enabled': 'Paso directo DRM activado', 'DRM passthrough disabled': 'Paso directo DRM desactivado',
            'Debug logging enabled': 'Registro de depuración activado', 'Debug logging disabled': 'Registro de depuración desactivado',
            'Could not update DRM setting': 'No se pudo actualizar DRM', 'Could not update debug logging': 'No se pudo actualizar el registro de depuración',
            'All major features and runtime paths in one place.': 'Todas las funciones principales y rutas de ejecución en un solo lugar.'
        },
        de: {
            'Dashboard': 'Übersicht', 'Identity': 'Identität', 'Apps': 'Apps', 'Keyboxes': 'Keyboxen', 'Info & Resources': 'Info & Ressourcen',
            'Guide': 'Anleitung', 'Logs': 'Protokolle', 'Editor': 'Editor', 'Donate': 'Spenden', 'Profiles': 'Profile',
            'Security Patch': 'Sicherheitspatch', 'Core Protection': 'Kernschutz', 'Global Mode': 'Globaler Modus',
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
            'Open Telegram Community': 'Telegram-Community öffnen', 'Join Telegram Community': 'Telegram-Community beitreten',
            'Save profile': 'Profil speichern', 'Clone': 'Klonen', 'Delete': 'Löschen',
            'System / preinstalled packages are included in this search.': 'System- und vorinstallierte Pakete sind in dieser Suche enthalten.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Die integrierten Übersetzungen funktionieren lokal und benötigen kein Netzwerk. Englisch bleibt Standard, bis hier eine andere Sprache gewählt wird.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Pakete aus drm_packages.txt bleiben auf dem echten Android-Keystore-Pfad. Es wird keine DRM-Sicherheitsstufe vorgetäuscht.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Aktiviert zusätzliche Laufzeitdiagnose ohne Debug-Build. Nach dem Sammeln der Logs wieder ausschalten.',
            'DRM passthrough enabled': 'DRM-Durchleitung aktiviert', 'DRM passthrough disabled': 'DRM-Durchleitung deaktiviert',
            'Debug logging enabled': 'Debug-Protokollierung aktiviert', 'Debug logging disabled': 'Debug-Protokollierung deaktiviert',
            'Could not update DRM setting': 'DRM-Einstellung konnte nicht aktualisiert werden', 'Could not update debug logging': 'Debug-Protokollierung konnte nicht aktualisiert werden',
            'All major features and runtime paths in one place.': 'Alle wichtigen Funktionen und Laufzeitpfade an einem Ort.'
        },
        ru: {
            'Dashboard': 'Панель', 'Identity': 'Идентичность', 'Apps': 'Приложения', 'Keyboxes': 'Keybox', 'Info & Resources': 'Инфо и ресурсы',
            'Guide': 'Руководство', 'Logs': 'Логи', 'Editor': 'Редактор', 'Donate': 'Поддержать', 'Profiles': 'Профили',
            'Security Patch': 'Патч безопасности', 'Core Protection': 'Основная защита', 'Global Mode': 'Глобальный режим',
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
            'Open Telegram Community': 'Открыть Telegram-сообщество', 'Join Telegram Community': 'Вступить в Telegram-сообщество',
            'Save profile': 'Сохранить профиль', 'Clone': 'Клонировать', 'Delete': 'Удалить',
            'System / preinstalled packages are included in this search.': 'Поиск включает системные и предустановленные пакеты.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Встроенные переводы работают локально и не требуют сети. Английский используется по умолчанию, пока здесь не выбран другой язык.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Пакеты из drm_packages.txt остаются на настоящем пути Android Keystore. Уровень безопасности DRM не подделывается.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Включает дополнительную диагностику без debug-сборки. После сбора логов отключите её.',
            'DRM passthrough enabled': 'DRM passthrough включён', 'DRM passthrough disabled': 'DRM passthrough выключен',
            'Debug logging enabled': 'Отладочное логирование включено', 'Debug logging disabled': 'Отладочное логирование выключено',
            'Could not update DRM setting': 'Не удалось обновить настройку DRM', 'Could not update debug logging': 'Не удалось обновить отладочное логирование',
            'All major features and runtime paths in one place.': 'Все основные функции и пути выполнения в одном месте.'
        },
        id: {
            'Dashboard': 'Dasbor', 'Identity': 'Identitas', 'Apps': 'Aplikasi', 'Keyboxes': 'Keybox', 'Info & Resources': 'Info & Sumber Daya',
            'Guide': 'Panduan', 'Logs': 'Log', 'Editor': 'Editor', 'Donate': 'Donasi', 'Profiles': 'Profil',
            'Security Patch': 'Patch Keamanan', 'Core Protection': 'Perlindungan Inti', 'Global Mode': 'Mode Global',
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
            'Open Telegram Community': 'Buka Komunitas Telegram', 'Join Telegram Community': 'Gabung Komunitas Telegram',
            'Save profile': 'Simpan profil', 'Clone': 'Klon', 'Delete': 'Hapus', 'Profile saved': 'Profil disimpan',
            'System / preinstalled packages are included in this search.': 'Paket sistem / prainstal juga disertakan dalam pencarian ini.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'Terjemahan bawaan tersedia secara lokal dan tidak memerlukan jaringan. Bahasa Inggris adalah default sampai Anda memilih bahasa lain di sini.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'Mempertahankan paket di drm_packages.txt pada jalur Keystore Android asli. Ini tidak memalsukan tingkat keamanan DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'Aktifkan diagnostik runtime tambahan tanpa memasang build debug. Matikan setelah log selesai dikumpulkan.',
            'DRM passthrough enabled': 'DRM passthrough aktif', 'DRM passthrough disabled': 'DRM passthrough nonaktif',
            'Debug logging enabled': 'Log debug aktif', 'Debug logging disabled': 'Log debug nonaktif',
            'Could not update DRM setting': 'Tidak dapat memperbarui pengaturan DRM', 'Could not update debug logging': 'Tidak dapat memperbarui log debug',
            'All major features and runtime paths in one place.': 'Semua fitur utama dan jalur runtime dijelaskan di satu tempat.'
        },
        hi: {
            'Dashboard': 'डैशबोर्ड', 'Identity': 'पहचान', 'Apps': 'ऐप्स', 'Keyboxes': 'कीबॉक्स', 'Info & Resources': 'जानकारी और संसाधन',
            'Guide': 'मार्गदर्शिका', 'Logs': 'लॉग', 'Editor': 'संपादक', 'Donate': 'दान', 'Profiles': 'प्रोफाइल',
            'Security Patch': 'सुरक्षा पैच', 'Core Protection': 'मुख्य सुरक्षा', 'Global Mode': 'ग्लोबल मोड',
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
            'Open Telegram Community': 'Telegram समुदाय खोलें', 'Join Telegram Community': 'Telegram समुदाय से जुड़ें',
            'Save profile': 'प्रोफाइल सहेजें', 'Clone': 'क्लोन', 'Delete': 'हटाएं', 'Profile saved': 'प्रोफाइल सहेजा गया',
            'System / preinstalled packages are included in this search.': 'इस खोज में सिस्टम / पहले से इंस्टॉल पैकेज भी शामिल हैं।',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'अंतर्निहित अनुवाद स्थानीय हैं और नेटवर्क की आवश्यकता नहीं है। जब तक आप यहां दूसरी भाषा नहीं चुनते, अंग्रेज़ी डिफ़ॉल्ट रहती है।',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'drm_packages.txt में सूचीबद्ध पैकेजों को Android के वास्तविक Keystore पथ पर रखता है। यह DRM सुरक्षा स्तर को नकली नहीं बनाता।',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'डीबग बिल्ड इंस्टॉल किए बिना अतिरिक्त रनटाइम डायग्नोस्टिक्स चालू करें। लॉग लेने के बाद इसे बंद कर दें।',
            'DRM passthrough enabled': 'DRM पासथ्रू चालू है', 'DRM passthrough disabled': 'DRM पासथ्रू बंद है',
            'Debug logging enabled': 'डीबग लॉगिंग चालू है', 'Debug logging disabled': 'डीबग लॉगिंग बंद है',
            'Could not update DRM setting': 'DRM सेटिंग अपडेट नहीं हो सकी', 'Could not update debug logging': 'डीबग लॉगिंग अपडेट नहीं हो सकी',
            'All major features and runtime paths in one place.': 'सभी मुख्य फीचर और रनटाइम पथ एक ही जगह समझाए गए हैं।'
        },
        ar: {
            'Dashboard': 'لوحة التحكم', 'Identity': 'الهوية', 'Apps': 'التطبيقات', 'Keyboxes': 'صناديق المفاتيح', 'Info & Resources': 'المعلومات والموارد',
            'Guide': 'الدليل', 'Logs': 'السجلات', 'Editor': 'المحرر', 'Donate': 'تبرع', 'Profiles': 'الملفات الشخصية',
            'Security Patch': 'تصحيح الأمان', 'Core Protection': 'الحماية الأساسية', 'Global Mode': 'الوضع العام',
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
            'Open Telegram Community': 'فتح مجتمع Telegram', 'Join Telegram Community': 'الانضمام إلى مجتمع Telegram',
            'Save profile': 'حفظ الملف الشخصي', 'Clone': 'نسخ', 'Delete': 'حذف', 'Profile saved': 'تم حفظ الملف الشخصي',
            'System / preinstalled packages are included in this search.': 'تتضمن هذه عملية البحث أيضا حزم النظام والحزم المثبتة مسبقا.',
            'Built-in translations are local and require no network connection. English is the default unless you choose another language here.': 'الترجمات المدمجة محلية ولا تحتاج إلى اتصال بالشبكة. تبقى الإنجليزية هي الافتراضية ما لم تختر لغة أخرى هنا.',
            "Keep packages listed in drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.": 'يبقي الحزم الموجودة في drm_packages.txt على مسار Keystore الحقيقي في Android. لا يقوم بتزييف مستوى أمان DRM.',
            'Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.': 'يفعل تشخيصات تشغيل إضافية دون تثبيت إصدار debug. أوقفه بعد جمع السجلات.',
            'DRM passthrough enabled': 'تم تفعيل تمرير DRM', 'DRM passthrough disabled': 'تم تعطيل تمرير DRM',
            'Debug logging enabled': 'تم تفعيل سجل التصحيح', 'Debug logging disabled': 'تم تعطيل سجل التصحيح',
            'Could not update DRM setting': 'تعذر تحديث إعداد DRM', 'Could not update debug logging': 'تعذر تحديث سجل التصحيح',
            'All major features and runtime paths in one place.': 'شرح جميع الميزات الأساسية ومسارات التشغيل في مكان واحد.'
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
            keyboxHubCopy: 'Get an API key for the recommended remote server here.', getApiKey: 'Get API Key'
        },
        tr: {
            backupPassword: 'Yedekleme Parolası',
            backupHint: 'Zorunlu, en az 12 karakter.',
            backupPlaceholder: 'Güçlü bir yedekleme parolası girin',
            show: 'Göster', hide: 'Gizle',
            exportSettings: 'Şifreli Ayarları Dışa Aktar', importSettings: 'Şifreli Ayarları İçe Aktar', synchronizeRuntime: 'Çalışma Zamanını Eşitle',
            keyboxHubCopy: "Önerilen remote server için API key'i bu adresten alabilirsiniz.", getApiKey: 'API Key Al'
        },
        'zh-CN': {
            backupPassword: '备份密码', backupHint: '必填，至少 12 个字符。', backupPlaceholder: '输入一个高强度备份密码', show: '显示', hide: '隐藏',
            exportSettings: '导出加密设置', importSettings: '导入加密设置', synchronizeRuntime: '同步运行时',
            keyboxHubCopy: '可在此获取推荐远程服务器所需的 API 密钥。', getApiKey: '获取 API 密钥'
        },
        es: {
            backupPassword: 'Contraseña de respaldo', backupHint: 'Obligatoria, mínimo 12 caracteres.', backupPlaceholder: 'Introduce una contraseña de respaldo segura', show: 'Mostrar', hide: 'Ocultar',
            exportSettings: 'Exportar ajustes cifrados', importSettings: 'Importar ajustes cifrados', synchronizeRuntime: 'Sincronizar runtime',
            keyboxHubCopy: 'Obtén aquí una clave API para el servidor remoto recomendado.', getApiKey: 'Obtener clave API'
        },
        de: {
            backupPassword: 'Backup-Passwort', backupHint: 'Erforderlich, mindestens 12 Zeichen.', backupPlaceholder: 'Ein sicheres Backup-Passwort eingeben', show: 'Anzeigen', hide: 'Ausblenden',
            exportSettings: 'Verschlüsselte Einstellungen exportieren', importSettings: 'Verschlüsselte Einstellungen importieren', synchronizeRuntime: 'Laufzeit synchronisieren',
            keyboxHubCopy: 'Hier erhältst du einen API-Schlüssel für den empfohlenen Remote-Server.', getApiKey: 'API-Schlüssel abrufen'
        },
        ru: {
            backupPassword: 'Пароль резервной копии', backupHint: 'Обязательно, не менее 12 символов.', backupPlaceholder: 'Введите надежный пароль резервной копии', show: 'Показать', hide: 'Скрыть',
            exportSettings: 'Экспортировать зашифрованные настройки', importSettings: 'Импортировать зашифрованные настройки', synchronizeRuntime: 'Синхронизировать среду',
            keyboxHubCopy: 'Здесь можно получить API-ключ для рекомендуемого удаленного сервера.', getApiKey: 'Получить API-ключ'
        },
        id: {
            backupPassword: 'Kata Sandi Cadangan', backupHint: 'Wajib, minimal 12 karakter.', backupPlaceholder: 'Masukkan kata sandi cadangan yang kuat', show: 'Tampilkan', hide: 'Sembunyikan',
            exportSettings: 'Ekspor Pengaturan Terenkripsi', importSettings: 'Impor Pengaturan Terenkripsi', synchronizeRuntime: 'Sinkronkan Runtime',
            keyboxHubCopy: 'Dapatkan API key untuk remote server yang direkomendasikan di sini.', getApiKey: 'Dapatkan API Key'
        },
        hi: {
            backupPassword: 'बैकअप पासवर्ड', backupHint: 'आवश्यक, कम से कम 12 अक्षर।', backupPlaceholder: 'एक मजबूत बैकअप पासवर्ड दर्ज करें', show: 'दिखाएं', hide: 'छिपाएं',
            exportSettings: 'एन्क्रिप्टेड सेटिंग्स निर्यात करें', importSettings: 'एन्क्रिप्टेड सेटिंग्स आयात करें', synchronizeRuntime: 'रनटाइम सिंक करें',
            keyboxHubCopy: 'अनुशंसित रिमोट सर्वर के लिए API key यहां प्राप्त करें।', getApiKey: 'API Key प्राप्त करें'
        },
        ar: {
            backupPassword: 'كلمة مرور النسخة الاحتياطية', backupHint: 'مطلوبة، 12 حرفا على الأقل.', backupPlaceholder: 'أدخل كلمة مرور قوية للنسخة الاحتياطية', show: 'إظهار', hide: 'إخفاء',
            exportSettings: 'تصدير الإعدادات المشفرة', importSettings: 'استيراد الإعدادات المشفرة', synchronizeRuntime: 'مزامنة وقت التشغيل',
            keyboxHubCopy: 'احصل هنا على مفتاح API للخادم البعيد الموصى به.', getApiKey: 'الحصول على مفتاح API'
        }
    };

    let locale = readLocale();
    const originalText = new WeakMap();
    const originalAttrs = new WeakMap();
    let compatibilityConfig = null;

    function readLocale() {
        try {
            const saved = global.localStorage && global.localStorage.getItem(STORAGE_KEY);
            if (SUPPORTED.some(([id]) => id === saved)) return saved;
        } catch (_) {}
        return 'en';
    }

    function saveLocale(value) {
        locale = SUPPORTED.some(([id]) => id === value) ? value : 'en';
        try { if (global.localStorage) global.localStorage.setItem(STORAGE_KEY, locale); } catch (_) {}
    }

    function tr(value) {
        if (locale === 'en') return value;
        return (TRANSLATIONS[locale] && TRANSLATIONS[locale][value]) || value;
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
            #ct_debug_panel .row, #ct_drm_dashboard_panel .row { margin-bottom: 0; }
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
            #ct_keyboxhub_hint .ct-keyboxhub-action { display:inline-flex; align-items:center; justify-content:center; min-height:36px; padding:7px 11px; border:1px solid var(--border); border-radius:6px; color:var(--fg); text-decoration:none; font-size:.78em; font-weight:500; white-space:nowrap; background:rgba(255,255,255,.035); }
            #ct_keyboxhub_hint .ct-keyboxhub-action:hover { background:rgba(255,255,255,.07); }
            html[dir="rtl"] body { direction: rtl; }
            html[dir="rtl"] input, html[dir="rtl"] select, html[dir="rtl"] textarea, html[dir="rtl"] pre, html[dir="rtl"] code, html[dir="rtl"] .mono { direction: ltr; text-align: left; }
            html[dir="rtl"] input[type="checkbox"].toggle { direction: ltr; }
            @media (max-width: 520px) {
                .row { gap: 12px; align-items: flex-start; }
                .row > input[type="checkbox"].toggle { margin-top: 2px !important; }
                #ct_language_panel .row, #ct_debug_panel .row, #ct_drm_dashboard_panel .row { align-items:center; }
                #ct_config_management .ct-config-actions { grid-template-columns:1fr; }
                #ct_config_management .ct-config-actions #runtimeSyncBtn { grid-column:auto; }
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
        if (!originalText.has(node)) originalText.set(node, current);
        const original = originalText.get(node) || '';
        const trimmed = original.trim();
        if (!trimmed) return;
        const leading = original.match(/^\s*/)[0];
        const trailing = original.match(/\s*$/)[0];
        node.nodeValue = leading + tr(trimmed) + trailing;
    }

    function translateElement(element) {
        if (!element || element.id === 'ct_full_guide') return;
        const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
        let node;
        while ((node = walker.nextNode())) translateNode(node);
        ['placeholder','title','aria-label'].forEach(name => {
            if (!element.hasAttribute || !element.hasAttribute(name)) return;
            let stored = originalAttrs.get(element);
            if (!stored) { stored = Object.create(null); originalAttrs.set(element, stored); }
            if (!(name in stored)) stored[name] = element.getAttribute(name);
            element.setAttribute(name, tr(stored[name]));
        });
        if (element.querySelectorAll) element.querySelectorAll('[placeholder],[title],[aria-label]').forEach(child => {
            ['placeholder','title','aria-label'].forEach(name => {
                if (!child.hasAttribute(name)) return;
                let stored = originalAttrs.get(child);
                if (!stored) { stored = Object.create(null); originalAttrs.set(child, stored); }
                if (!(name in stored)) stored[name] = child.getAttribute(name);
                child.setAttribute(name, tr(stored[name]));
            });
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
            panel.innerHTML = `<h3>Language</h3><div class="row"><label for="ct_language_selector" style="flex:1">Language</label><select id="ct_language_selector" aria-label="Language">${SUPPORTED.map(([id,name]) => `<option value="${escapeHtml(id)}">${escapeHtml(name)}</option>`).join('')}</select></div><div class="ct-compat-note">Built-in translations are local and require no network connection. English is the default unless you choose another language here.</div>`;
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
        const response = await bridge.fetch('/api/config');
        if (!response.ok) throw new Error(await response.text());
        compatibilityConfig = await response.json();
        return compatibilityConfig;
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
        panel.innerHTML = `<h3>Debug Logging</h3><div class="row"><label for="ct_debug_logging_toggle" style="flex:1;padding-right:14px"><strong style="color:#fff">Debug Logging</strong><span class="res-desc">Enable additional runtime diagnostics without installing a debug build. Turn it off after collecting logs.</span></label><input id="ct_debug_logging_toggle" class="ct-switch" type="checkbox"></div>`;
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
        hideRetiredRkpUi();
        ensureFooterOrder();
        applyTranslations();
    }

    function start() {
        injectStyles();
        installOwnedSurfaceInteractions();
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

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once:true });
    else start();
})(window);
