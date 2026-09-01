<div dir="rtl">

# توثيق CleveresTricky

**اللغة:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | [Español](es.md) | [Deutsch](de.md) | [Русский](ru.md) | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | **العربية**

[README العربية](../../README.ar.md)

> هذا مرجع مترجم لكل ملفات Markdown الموجهة للمستخدم. عند وجود اختلاف تقني تكون الوثائق الإنجليزية والكود المصدري المرجع الأساسي.

<a id="application-rules"></a>
## Application Rules

تسمح بتعيين template أو keybox محلي متحقق أو privacy policy لتطبيق مؤهل. القاعدة الصحيحة تعد target صريحا. `inherit` يحافظ على السياسة العامة، و`isolate` يشتق IMEI/IMSI/ICCID/MEID/phone/serial وattestation identifiers وDRM `deviceUniqueId` pseudonym مستقرة خاصة بالتطبيق، و`redact` يفرغ القيم المدعومة مع الحفاظ على أخطاء صلاحيات Android.

Attestation Identity يحتاج keybox فعالة ومتحققا منها. DRM isolation مستقل عن DRM Keystore Passthrough. Shared UID يحل بشكل حتمي عبر Package Manager ولا يتم الوثوق باسم package داخل الطلب. تنشر الحالة الجديدة atomically ويتم مسح الكاش المرتبط.

<a id="application-scope"></a>
## Application Scope

يحدد أي تطبيقات Android تتلقى توافق الشهادات/Keybox أو الهوية (Identity). تتضمن الوحدة ملفي أهداف ووضعين عامين منفصلين:

- **أهداف Keybox (`target.txt`)**: تحدد الحزم التي تتلقى Keybox المخصص وتصديق TEE عند تعطيل الوضع العام لـ Keybox.
- **أهداف الهوية (`identity_target.txt`)**: تحدد الحزم لخصائص الهوية الخاصة بكل تطبيق (Build، Telephony، Region) عند تعطيل الوضع العام للهوية.
- **الوضع العام لـ Keybox**: يطبق Keybox المخصص على جميع تطبيقات المستخدم دون الحاجة إلى `target.txt`. تبقى معرفات النظام والبنية التحتية محمية.
- **الوضع العام للهوية**: يطبق خصائص Build على مستوى النظام بالكامل. عند تعطيله، تؤثر الهوية فقط على `identity_target.txt` والملفات الشخصية المعينة.
- **وحدة تصحيح الأمان المستقلة**: يمكن إدارة تصحيح الأمان بشكل مستقل عن محرك الهوية من لوحة التحكم.

تشترك الحزم ذات UID المشترك في هوية Binder. يتم رفض التحديثات غير الصالحة بشكل مغلق (fail-closed).

<a id="attestation"></a>
## Attestation

توفر طبقة attestation توافقاً مضبوطاً لسلاسل الشهادات للتطبيقات المحددة مع إبقاء إنشاء المفاتيح الحقيقي في Android والعمليات التشفيرية اللاحقة كما هي.

تبقى نداءات بنية RKP دائماً على مسار provisioning الحقيقي في Android. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` الناجحة وقراءات الشهادة اللاحقة عبر `getKeyEntry` مسار توافق واحداً كي لا يعرض alias واحد شهادتي attestation leaf مختلفتين.

تظل عملية المفتاح الخاص من تنفيذ Android KeyMint أو StrongBox. وقبل تفعيل المادة يتم التحقق من تطابق المفتاح والشهادة والخوارزمية والسلسلة والصلاحية والالتباس وحالة revocation. استبدال الشهادة لا ينشئ hardware root of trust ولا يقفل bootloader فعلياً ولا يضمن remote verdict.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

يحافظ على keybox/revocation محدثة دون continuous storage scan. File observer يتعامل مع التغييرات الطبيعية ويستخدم low-frequency fallback عند الحاجة.

كل refresh يعيد فحص key وchain وalgorithm وvalidity وambiguity وrevocation. إذا تعذر تحديد revocation فلا تفعل المواد الجديدة. الكاش محدود بعدد وحجم الملفات.

<a id="backup-restore"></a>
## Backup and Restore

ينقل config وauthorized key material داخل authenticated encrypted archive. Export يحتاج password من 12 حرفا على الأقل ويستخدم allowlist، ويرفض symlink وunknown path والحجم الزائد.

Import يقبل encrypted CTSB فقط ويضع حدودا للرفع وعدد entries/keyboxes والحجم expanded. Traversal وduplicates وdirectories وsymlink destination وsettings/keybox غير الصالحة ترفض قبل الكتابة. Policy v2 تتحقق وتنشر snapshot كاملة.

<a id="boot-properties"></a>
## Boot Properties

Core userspace property view يقلل كشف مؤشرات unlocked/debug/warranty/verified boot/recovery الشائعة. مجموعة properties ثابتة وتطبق قبل Zygote وتبقى فعالة بشكل مستقل عن optional identity.

`boot_props_mode` يتحكم فقط في Build Identity compatibility الاختياري (`auto`, `force`, `disable`) ولا يوقف core protection. لا يعيد قفل bootloader فعليا ولا يصلح verified boot ولا يغير TEE root of trust.

<a id="build-identity"></a>
## Build Identity

يطبق device template كامل على fingerprint وsupported app-visible Build fields. اختياري، يحتاج Spoof Engine وreboot. Arbitrary Android properties ترفض.

Auto Identity يمكنه جلب Pixel beta/canary من Google public metadata وحفظه دون تشغيل engine تلقائيا. Build Identity وSecurity Patch وRegion وTelephony وAttestation Identity مستقلة.

<a id="building"></a>
## Building

يتطلب Java 21 وSDK API 36 وNDK 27.3.13750724 وCMake 3.22.1 وstable Rust وARM64/x86-64 Android targets وCargo NDK وsubmodules. يجب نجاح Kotlin/Android checks وRust fmt/clippy/tests وunit tests.

CI يتحقق من shell وSELinux وtemplate وKotlin/Java/Rust والمعماريتين وrelease/debug ZIP وEncryptor. First-party C ممنوع و`binder_interceptor.cpp` هو استثناء C++ الوحيد. Release عبر `./gradlew zipRelease`.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

مفهوم legacy. WebUI الحالية لا تقدم مفتاحا لإيقاف core Keystore/TEE compatibility. Scope يحدده Global Mode/Application Rules وSpoof Engine يخص الهوية فقط.

يمكن قراءة `tee_broken_mode` للمهاجرة لكنه لا يحدد core targeting. للتشخيص قلل scope أو استخدم passthrough أو أزل key material بشكل مضبوط.

<a id="diagnostics"></a>
## Diagnostics

افحص Dashboard للقيم version وEngine وprofile وkeybox count وtarget size وRKP وDRM وnative features ثم ابحث عن أول error في Logs. إذا لم تعمل WebUI افحص logcat وdaemon و`webroot` وarchitecture-specific `webui_bridge` وحالة manager.

ينسخ Copy Diagnostics في Info & Resources ملخص دعم محدودا بمفاتيح إنجليزية وallowlist ثابتة. يتضمن version وroot environment وnative/interceptor state وإجمالي keybox/rule count وprocess CPU/RSS وfeature flags؛ ولا يتضمن logs أوأسماء package/keybox أوidentity values أوcredentials أوserver configuration أوkey material. راجع الملخص قبل مشاركته لأن feature flags تصف إعدادات الوحدة.

للعزل استخدم Minimal + reboot، تحقق من genuine path ثم فعّل الميزات واحدة واحدة. Effective State يعرض rule/profile وscope وtemplate وkeybox ref وprivacy وfeatures وpatches وRKP/DRM وKeyMint/StrongBox وprovider coexistence وreboot requirement دون private keys.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Passthrough يبقي تطبيقات الوسائط المحددة على genuine Android Keystore certificate path. Identifier Privacy يستبدل فقط supported stable-AIDL `deviceUniqueId` لتطبيق `privacy=isolate` باسم مستعار ثابت خاص بالتطبيق دون استخدام genuine DRM ID في الاشتقاق.

`drm_packages.txt` يدعم exact package وbounded wildcard. عند إنشاء المكون الإضافي، يتم التقاط اسم الحزمة وسياق وقت تشغيل المستخدم (multi-user / work-profile). Hook محدود إلى `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")` ولا يغير HIDL أوsecurity level أوlicenses أوprovisioning أوkeys أوsessions أوHDCP أوstring properties. Unexpected ABI يحافظ على original response fail open.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX يستخدم authenticated AES-256-GCM ويربط metadata بـ ciphertext. Password containers تستخدم bounded key derivation، ومفتاح local protected cache داخل private config.

Unlock يقبل فقط عبر native WebUI ولا يتجاوز keybox verification. يتم إعادة فحص key/certificate/chain/date/algorithm/revocation. Hostile root يمكنه قراءة البيانات بعد unlock.

<a id="identity-refresh"></a>
## Identity Refresh

يجهز validated identity للإقلاع التالي دون تغيير snapshot الحالية. Early boot يتحقق من staged file ثم يقوم atomic promotion كي تستخدم Build Properties والخدمة نفس الحالة.

IMEI/ICCID checksum والأطوال محدودة. Manual edit يحذف stage القديم؛ إيقاف Engine/Refresh قبل boot يمنع unwanted promotion.

<a id="installer"></a>
## Installer

يثبت full KernelSU/APatch module على Android 12-17 ARM64/x86 64. Magisk/recovery يتوقفان قبل partial install.

كل payload لديه SHA 256 وruntime يرفض symlink/non-regular/unexpected files. Internal hash ليس دليلا على الناشر، لذلك release الرسمي ينشر `SHA256SUMS` وGitHub signed build provenance.

<a id="keybox-manager"></a>
## Keybox Manager

يحمل ويتحقق ويختار ويراقب authorized attestation material بصيغ legacy/XML/CBOX. يمكن لـ Application Rule اختيار file محدد، وremote material يبقى untrusted حتى التحقق المحلي.

يجب أن يطابق private key الـ leaf certificate ويتم فحص algorithm وchain وdate وduplicate/ambiguity وrevocation. Unknown revocation لا يفعل المواد الجديدة، وbroken pool يرفض بالكامل.

<a id="native-architecture"></a>
## Native Architecture

Portable native logic في Rust. لا يوجد first-party C؛ `binder_interceptor.cpp` هو استثناء C++ الوحيد بسبب private Android libbinder object ABI. Rust Core يتحقق من Binder layouts/streams وFD وkernel-validated copies.

Rust Injector يدير files وSELinux socket وFD transfer وmaps/symbols وptrace وregisters وremote memory وloader وcleanup. Temporary stack writes تستعاد من bounded journal. لا يجب توسيع استثناء C++.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` يوفر System/Vendor/Boot global/per-app rules. يدعم dates و`today` و`device_default` و`prop` و`no`؛ policy v2 تدعم Device وProperty وManual وAutomatic وOmit بشكل مستقل.

Parsing محدود وinvalid input لا يطبق partial state. Automatic يستخدم calendar arithmetic. الميزة لا تثبت security update حقيقية ولا تغير firmware ولا تضمن remote verdict.

<a id="performance"></a>
## Performance and Memory

Core Keystore interception يبقى فعالا؛ إيقاف Spoof Engine يوقف optional identity/DRM/build/region/telephony work. Automatic Keybox Check له control مستقل.

Binder parser يستخدم fixed arrays وdescriptor cache من 64 slot. Controller/cache محدودة وتتجنب busy poll. Rust release يستخدم LTO وsize optimization وhardened linking.

<a id="profiles"></a>
## Profiles

تطبق Profiles مجموعة من الإعدادات الاختيارية في معاملة واحدة متحقق منها؛ وتظل حماية boot وKeystore وبنية RKP الأساسية فعالة بشكل مستقل.

يستخدم Daily Compatibility نطاقاً مستهدفاً ومراقبة keybox؛ وDefault إعداد محافظ؛ ويشغل Maximum Compatibility ‏Global Mode وbuild identity وidentity refresh وtelephony مع تعطيل DRM passthrough؛ بينما يعطل Minimal الهوية الاختيارية وفحوص keybox المجدولة. لا يغير أي preset حماية بنية RKP.

قد تبقى علامة `rkp_passthrough` المتقاعدة في الإعدادات القديمة، لكن سلوك generated-key لم يعد يعتمد عليها. تستطيع Profiles version two حفظ تعيينات التطبيقات وtemplate وkeybox متحقق منه وprivacy وpatch وخيارات identity/DRM؛ أما حقل RKP القديم فيبقى فقط لتوافق الترحيل وليس خياراً حياً في WebUI.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity يكتشف fingerprint/property providers أخرى مثل PIF و`autopif`/`auto_pif` وPlayCurl ولا يكتب فوقها.

عند conflict تبقى optional Build properties كما هي ويمكن لبقية الميزات العمل. Force يتجاوز detection عمدا؛ Automatic موصى به.

<a id="region-properties"></a>
## Region Properties

يوفر optional fixed China-region view عبر hardware/SIM/operator country وhardware level وradio marker. Arbitrary properties غير مقبولة.

يطبق قبل Zygote مع Spoof Engine. لا يغير real SIM country أوradio registration أوmodem firmware أوsecure sales region أوcarrier account.

<a id="remote-sources"></a>
## Remote Sources

يجلب authorized keybox من HTTPS صريح فقط. Host/port/path/timeout/refresh/auth/header/size محدودة، وsecrets لا تظهر في status.

يمكن فرض signature. لا تفعل البيانات قبل signature وXML/CBOX وsize وkeybox وcertificate وrevocation validation. Failed refresh لا يستبدل verified material.

<a id="rkp-protection"></a>
## RKP Protection

تحافظ حماية Remote Key Provisioning على بنية provisioning في Android ضمن المسار الحقيقي للمنصة. تبقى حزم RKP الخاصة بـ Android/Google وحزم Remote Provisioner القديمة خارج نطاق الاستبدال، كما تفشل UID النظام وحالات تعذر حل الحزمة بوضع fail closed.

لا يتم تعديل نداءات بنية RKP أبداً. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` وقراءات `getKeyEntry` اللاحقة مسار توافق شهادات موحداً لمنع alias واحد من إظهار attestation leaf مختلفتين.

تم تقاعد المفتاح القديم `rkp_passthrough`. يمكن أن تبقى العلامة في config أو backup قديم، لكنها لا تتحكم بعد الآن في generated-key ولا تظهر كـ runtime toggle في WebUI. لا تغير Profiles المدمجة سلوك RKP؛ فحماية البنية فعالة دائماً.

لا يحاكي CleveresTricky خادم RKP ولا ينشئ provisioning credentials ولا يغير hardware provisioning root.

<a id="security-model"></a>
## Security Model

Root service وOS وKernelSU/APatch وmodule files وauthorized key material trusted. Apps وBinder وuploads وremote responses وconfig وarchives وrules وtemplates وpaths وnetwork metadata untrusted.

Config root-owned وsensitive root-only وsymlink مرفوض وwrites atomic. Binder ABI وkernel-validated copies تتحقق. Injector يقيد symbol/process/library وWebUI لا يفتح TCP ويستخدم strict native bridge. Hostile root خارج دفاع كامل.

<a id="spoof-engine"></a>
## Spoof Engine

Optional app-facing identity controller. Core Keystore/TEE وcertificate compatibility وroot of trust وboot protection تبقى حتى عند إيقافه.

عند التشغيل تعمل optional Attestation/Telephony/Build/Region/Refresh حسب controls الخاصة بها. الإيقاف لا يحذف saved values. App cache قد يحتاج restart وBuild Identity يحتاج reboot.

<a id="telephony-identity"></a>
## Telephony Identity

يمكنه عرض IMEI وMEID وIMSI وICCID وphone لشريحتي SIM عبر supported Binder APIs. Checksum وlength وsyntax وslot وinput size تتحقق.

يتم أخذ genuine Android response أولا؛ permission denial/error/null تحفظ. لا يتغير modem أوbaseband أوEFS أوphysical SIM أوcarrier identity.

<a id="web-interface"></a>
## Web Interface

Fixed ownership: `index.html` markup/base CSS، `bridge.js` native bridge/intents، `policy.js` policy/state UI، `ux.js` presentation/localization/guide/community. لا standalone runtime CSS ولا feature-specific JS bundles.

Mobile bottom navigation وtouch controls وresponsive panels وpassword visibility وprogress وaccessibility. لا TCP listener؛ native manager API وbounded Rust bridge وroot-only queues وstrict validation.

<a id="changelog"></a>
## CHANGELOG

V2.6.2 يعزز refresh وrecovery وpublication لـ keybox/CBOX عبر snapshots مستقرة ومتحقق منها ونشر atomically، ويقلل السباقات بين readers وquarantine وbackend recovery ويحسن server restart وcache invalidation وauth validation. يربط Auto Identity الآن Pixel security patch بالصف الصحيح في bulletin؛ كما أصبحت WebUI أكثر صلابة في abort/response/export. أصبح Backup/restore أكثر transactionality، وأضيفت bounds وحماية symlink أشد لقراءات bugreport وruntime files، وتم تحديث توافق Rust X.509 وRust 1.98 CI.

V2.5.3 أضاف granular identity/security patch controls وprofiles وEffective State؛ عزز Attestation/KeyMint/StrongBox/DRM privacy/upgrades/Android 17؛ وحد WebUI وtranslations؛ أضاف KeyboxHub external-browser helper؛ وحسن diagnostics وcache/timing وdependency security وregression وartifact validation.

<a id="contributing"></a>
## Contributing

يجب الحفاظ على fail-closed model وAndroid 12-17 وKernelSU/APatch وعدم تقديم claims غير قابلة للتحقق عن hardware integrity. شغل Kotlin/Android/Rust checks؛ portable native additions في Rust، first-party C ممنوع و`binder_interceptor.cpp` استثناء C++ الوحيد.

Binder/XML/ZIP/CBOX/HTTP/path/PID untrusted وتحتاج bounds/failure tests. لا ترفع private keys/keyboxes/tokens/secrets/generated APK/ZIP. حدث docs عند تغيير user-visible behavior.

<a id="donate"></a>
## Development Support

يمكن دعم المشروع عبر الخيارات في `DONATE.md`: USDT TRC20 وXMR وUSDT/USDC ERC20/BEP20 وBinance User ID وPayPal وBuyMeACoffee وموقع المطور. تحقق من العناوين الحالية في الملف الإنجليزي قبل إرسال الأموال.

<a id="languages"></a>
## Language Support

WebUI يتضمن English وTürkçe و简体中文 وEspañol وDeutsch وРусский وBahasa Indonesia وहिन्दी والعربية. Runtime catalogs تبقى فقط في `ux.js` ولا يتم إنشاء locale-specific JS/CSS. User docs توفر README وهذا المرجع بنفس اللغات التسع.

أي تغيير user-facing Markdown يجب أن يحدث English canonical والأقسام المترجمة المرتبطة.

<a id="logging"></a>
## Logging and Diagnostics

Diagnostics تكتب إلى Android logcat ولا يوجد plaintext log مستقل. الأمر: `adb logcat -s cleverestricky CleveresTricky`. علامات service/bridge/Binder/TEE مفيدة لبدء التشغيل.

`TAMPER DETECTED` وBinder ABI failure وrejected keybox وinjector timeout تحتاج فحصا. راجع filenames/packages/properties/PIDs الحساسة قبل نشر log.

<a id="theme"></a>
## UI Theme

تصميم minimal monochrome hybrid بين Nothing OS وModern: خلفية charcoal ونص light gray وaccent فضي وpanels داكنة وsuccess أخضر وdanger أحمر. System sans وmonospace للبيانات التقنية وDynamic Island وأزرار rounded وModern toggles وmobile-first layout.

Touch targets تقريبا 44px أو أكثر، vertical flow مفضل والواجهة محسنة للاستخدام على الهاتف داخل KernelSU/APatch.

</div>