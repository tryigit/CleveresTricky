# CleveresTricky Dokümantasyonu

**Dil:** [English](../../README.md) | **Türkçe** | [简体中文](zh-CN.md) | [Español](es.md) | [Deutsch](de.md) | [Русский](ru.md) | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | [العربية](ar.md)

[Ana Türkçe README](../../README.tr.md)

> Bu dosya kullanıcıya dönük CleveresTricky Markdown belgelerinin Türkçe referansıdır. Teknik davranışın kanonik kaynağı İngilizce belgeler ve kaynak koddur.

<a id="application-rules"></a>
## Application Rules

Application Rules, uygun bir uygulamaya cihaz şablonu, doğrulanmış yerel keybox veya gizlilik politikası atar. Geçerli bir kural zaten açık bir hedef olduğu için ayrıca scope girdisi gerekmez. `inherit` global kimlik politikasını korur; `isolate` korunan random seed üzerinden uygulamaya özel kararlı IMEI, IMSI, ICCID, MEID, telefon, serial, desteklenen attestation kimlikleri ve modern DRM `deviceUniqueId` takma kimliği üretir; `redact` desteklenen telephony ve attestation değerlerini boş döndürürken Android izin hatalarını korur.

Attestation kimliği değiştirme etkin ve doğrulanmış keybox gerektirir. DRM identifier isolation, DRM Keystore Passthrough'tan bağımsızdır. Shared UID paketleri tek deterministik bağlam olarak çözülür; gerçek paketler Package Manager üzerinden bulunur ve istek içindeki paket adına güvenilmez. Kurallar sınırlı trie içinde tutulur, geçerli yeni snapshot atomik olarak eskisinin yerini alır ve ilgili cache'ler temizlenir.

<a id="application-scope"></a>
## Application Scope

Application Scope, hangi Android uygulama kullanıcılarının sertifika veya kimlik uyumluluğu alacağını belirler. Targeted mode günlük kullanım için önerilir; `target.txt` içindeki tam paket adları veya sınırlı wildcard kuralları Package Manager üzerinden gerçek caller UID'ye çözülür. Shared UID kullanan paketler Binder açısından aynı kimliği paylaşır.

Global Mode, `target.txt` girdisi olmadan uygun application UID'lerini hedefler; system kimlikleri ve korunan altyapı kapsam dışında kalır. Paket çözümleme bilinmiyorsa fail closed davranılır. Kurallar ve kısa süreli decision cache birlikte değiştirilir; geçersiz güncelleme son geçerli durumu bozmaz.

<a id="attestation"></a>
## Attestation

Attestation katmanı, seçili uygulamalara kontrollü sertifika zinciri uyumluluğu sağlarken gerçek Android anahtar oluşturma ve sonraki kriptografik işlemleri korur.

RKP altyapı çağıranları her zaman Android'in gerçek provisioning yolunda kalır. Hedeflenen uygulama UID'lerinde başarılı `generateKey` yanıtları ile sonraki `getKeyEntry` sertifika okumaları aynı sertifika uyumluluk yolunu kullanır; böylece aynı alias iki farklı attestation leaf göstermez.

Private key işlemi istenen güvenlik seviyesinde Android KeyMint veya StrongBox tarafından yapılmaya devam eder. Etkinleştirmeden önce key/certificate eşleşmesi, algoritma, chain yapısı, geçerlilik, ambiguity ve revocation doğrulanır. Sertifika değiştirme fiziksel hardware root of trust oluşturmaz, bootloader'ı kilitlemez veya remote verdict garanti etmez.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Automatic Keybox Check, keybox ve revocation durumunu sürekli storage taraması yapmadan güncel tutar. Worker kendi kontrolüne bağlıdır ve core Keystore bakımından bağımsız şekilde servis yaşam döngüsüne uyar. File observer normal değişiklikleri izler; observer'ın güvenilir olmadığı dosya sistemlerinde düşük frekanslı fallback kullanılır.

Her yenilemede key/certificate eşleşmesi, chain, algoritma, validity, ambiguity ve revocation yeniden doğrulanır. Revocation verisi yoksa yeni materyal etkinleştirilmez. Cache dosya sayısı ve boyutuyla sınırlıdır; değişmeyen doğrulanmış dosyalar yeniden parse edilmez.

<a id="backup-restore"></a>
## Backup and Restore

Backup and Restore, yapılandırma ve yetkili key material'i tek authenticated encrypted archive içinde taşır. Export en az 12 karakter parola ister ve yalnız allowlist içindeki bilinen config dosyaları ile normal keybox dosyalarını dahil eder. Symlink, bilinmeyen path, aşırı dosya sayısı veya boyutu reddedilir ve persistent plain archive bırakılmaz.

Import yalnız encrypted CTSB formatını kabul eder; upload, entry count, keybox count, tekil ve toplam expanded size limitleri uygulanır. Traversal, duplicate, directory, symlink destination, malformed text, invalid setting ve invalid keybox staged write öncesi reddedilir. Version two policy state ve profile referansları da doğrulanarak tek snapshot halinde geri yüklenir.

<a id="boot-properties"></a>
## Boot Properties

Boot Properties, uygulamaların Android property üzerinden gördüğü yaygın unlocked/debug/warranty/verified boot/recovery göstergelerini sınırlayan core userspace görünümüdür. Core property seti Zygote öncesi uygulanır ve modül kurulu, early boot aracı kullanılabilir olduğu sürece aktif kalır.

`boot_props_mode` yalnız optional template Build Identity compatibility için `auto`, `force` veya `disable` kabul eder; bu seçeneklerin hiçbiri core boot property protection yolunu kapatmaz. Bu kullanıcı alanı görünümü bootloader'ı fiziksel olarak kilitlemez, verified boot'u onarmaz veya TEE root of trust'ı değiştirmez.

<a id="build-identity"></a>
## Build Identity

Build Identity, tam cihaz şablonunu fingerprint ve desteklenen app-visible Build alanlarına uygular. Optional'dır, Spoof Engine gerektirir ve Android bu değerleri erken yakaladığı için değişiklik sonrası reboot gerekir. Template manufacturer, model, brand, product, device, fingerprint, release, build ID, incremental, type, tags ve security patch bilgisi taşır; arbitrary `ro.*` property kabul edilmez.

Auto Identity, Custom ROM kullanıcıları için Google public metadata üzerinden güncel Pixel beta/canary Build Identity elde edip yerel kaydeder; motoru otomatik açmaz. Identity Refresh yeni snapshot'ı bir sonraki boot için hazırlar. Build Identity, Security Patch, Region, Telephony ve Attestation Identity birbirinden bağımsız çözülür.

<a id="building"></a>
## Building

Build için Java 21, Android SDK API 36, NDK 27.3.13750724, CMake 3.22.1, stable Rust, ARM64/x86 64 Android Rust target'ları, Cargo NDK ve git submodule'ları gerekir. Kotlin/Android lint ile Rust fmt, clippy ve testleri çalıştırılır; modül paketlemesi unit testleri de içerir.

CI shell, SELinux, template structure, Kotlin/Java/Rust testleri, iki mimari, release/debug ZIP ve Encryptor app'i doğrular. First-party C yasaktır; yalnız Android libbinder/LSPlt ABI sınırı olan `binder_interceptor.cpp` first-party C++ istisnasıdır. Release için `./gradlew zipRelease`, debug için `./gradlew zipDebug` kullanılır.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Certificate Safe Mode legacy bir kavramdır. Güncel WebUI, core Keystore ve TEE protection'ı kapatan bir switch sunmaz. Core interception servis sağlıklı olduğu sürece kayıtlı kalır; Global Mode ve Application Rules scope'u belirler, Spoof Engine yalnız kimlik değerlerini kontrol eder.

Eski kurulumlarda `tee_broken_mode` migration için okunabilir ancak core targeting ona bağlı değildir. Sorun izolasyonu için scope daraltılmalı, uygun passthrough kullanılmalı veya test ortamında ilgili key material kontrollü kaldırılmalıdır.

<a id="diagnostics"></a>
## Diagnostics

Önce Dashboard'da version, Spoof Engine, profile, keybox count, target size, RKP, DRM ve native feature state kontrol edilir; Logs ekranında tekrar eden son hata yerine ilk hata aranır. WebUI açılmıyorsa Android logcat, daemon, `webroot`, architecture-specific `webui_bridge` ve module-manager enable durumu kontrol edilir.

Kontrollü izolasyon için Minimal profile ile reboot edin, genuine path'i doğrulayın, ardından targeted Spoof Engine, tek yetkili key source ve tek rule ekleyin; Build Identity, Telephony, Boot Properties veya broad scope'u birer birer açın. Effective State inspector matched rule/profile, scope, template, keybox ref, privacy, feature decisions, patch values, RKP/DRM, genuine KeyMint/StrongBox durumu, provider coexistence ve reboot gereksinimini gösterir, private key göstermez.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

DRM Keystore Passthrough seçili medya uygulamalarını Android'in gerçek Keystore certificate path'inde tutar. DRM Identifier Privacy ise desteklenen stable AIDL DRM yolunda `privacy=isolate` kullanılan uygulamanın `deviceUniqueId` byte array okumasını, gerçek değeri input olarak kullanmadan, stable app-scoped pseudonym ile değiştirir.

`drm_packages.txt` exact paket ve bounded wildcard kabul eder. Privacy hook yalnız `IDrmFactory`/`IDrmPlugin.getPropertyByteArray("deviceUniqueId")` yolunu hedefler; legacy HIDL veya vendor-specific yolları değiştirmez. Security level, licenses, provisioning, content keys, sessions, HDCP, string properties ve DRM policy değiştirilmez. Beklenen AIDL servis veya transaction yapısı yoksa fail open ile gerçek yanıt korunur.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX keybox materyalini encrypted olarak saklama ve taşıma biçimidir. Authenticated AES-256-GCM kullanır; metadata authentication data ile ciphertext'e bağlanır. Password tabanlı container bounded key derivation kullanır, local protected cache anahtarı private config alanında tutulur.

Unlock yalnız native module-manager WebUI transport üzerinden kabul edilir. Başarılı decrypt, keybox verification'ı atlamaz; private key, certificate, chain, tarih, algoritma ve revocation yine kontrol edilir. Encryption yetkisiz key material'i meşru yapmaz ve root compromise durumunda unlocked veriyi koruyamaz.

<a id="identity-refresh"></a>
## Identity Refresh

Identity Refresh bir sonraki boot için yeni validated app-facing identity hazırlar; mevcut boot içindeki aktif snapshot'ı değiştirmez. Early boot sırasında staged file path, type, size, permission ve controls doğrulanır, ardından atomik olarak promote edilir ve hem Build properties hem service aynı snapshot'ı kullanır.

IMEI/ICCID checksum, numeric length ve serial charset sınırları korunur. Birden fazla template varsa yeni snapshot mümkün olduğunda farklı template seçer. Manual edit eski staged snapshot'ı siler; Spoof Engine veya Identity Refresh kapalıysa istenmeyen promotion yapılmaz.

<a id="installer"></a>
## Installer

Installer, service, native payload, scripts, policy, metadata ve integrity kayıtlarından oluşan tam KernelSU/APatch modülünü kurar. Android 12-17, ARM64 ve x86 64 desteklenir. Magisk ve recovery yolu partial install bırakmadan durdurulur.

Build her payload için SHA 256 kaydı üretir, installer extraction sırasında doğrular ve runtime doğrulaması symlink/non-regular/unexpected payload'ları reddeder. Archive içi hash tek başına üretici kimliği kanıtı değildir; resmi release ayrıca `SHA256SUMS` ve GitHub signed build provenance yayımlar. Resmi ZIP'i indirip digest/provenance kontrolünden sonra KernelSU/APatch ile kurup reboot etmek önerilir.

<a id="keybox-manager"></a>
## Keybox Manager

Keybox Manager authorized attestation key material'i yükler, doğrular, seçer ve izler. Legacy tek file, çoklu XML ve encrypted CBOX desteklenir. Application Rule belirli doğrulanmış file seçebilir; remote source verisi de aynı local validation tamamlanana kadar untrusted kabul edilir.

Her private key leaf certificate ile eşleşmeli; algorithm, chain, dates, duplicate/ambiguity ve revocation kontrol edilir. Revocation state belirlenemeyen yeni materyal aktif olmaz, broken entry bulunan pool komple reddedilir. Gerçek keybox source control'e commit edilmemelidir.

<a id="native-architecture"></a>
## Native Architecture

Projede portable native logic Rust ile yazılır. First-party C yoktur; yalnız private Android libbinder object ABI sınırı nedeniyle `binder_interceptor.cpp` first-party C++ olarak kalır. Rust native core Binder layout/stream validation, FD classification, kernel-validated copies, process/version parsing ve bounded control parsing yapar.

Rust injector argument, logging, path/file validation, SELinux socket context, random abstract socket, descriptor transfer, maps/symbol resolution, ptrace, registers, remote memory, loader call ve cleanup durumunu yönetir. Temporary target stack write'ları bounded journal ile geri yüklenir. C++ istisnası büyütülemez; güvenle Rust'a taşınabilen her parça Rust'a taşınmalıdır.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` System, Vendor ve Boot attestation patch alanları için global ve per-app kurallar sağlar. Calendar değerleri, `today`, `device_default`, `prop` ve `no` desteklenir; version two modelde her component için Device, Property, Manual, Automatic ve Omit modları bağımsız çözülür.

Parsing size/section/package/field/date/value sınırlarıyla tam replacement state üretir; invalid file running policy'yi kısmen değiştirmez. Automatic mode calendar arithmetic kullanır ve stale source için previous calendar month day five yaklaşımını uygular. Patch presentation gerçek security update kurmaz, kernel/vendor firmware düzeltmez ve remote verifier'ın diğer evidence'ını değiştirmez.

<a id="performance"></a>
## Performance and Memory

Core Keystore interception servis sağlıklı olduğu sürece kayıtlı kalır. Spoof Engine kapalıyken optional identity, DRM privacy, build/region work ve gereksiz telephony path'leri park edilir; core certificate ve boot protection aktif kalır. Automatic Keybox Check kendi kontrolüne sahiptir.

Rust Binder parser fixed caller-owned array kullanır; descriptor cache 64 fixed slot'tur ve heap büyümez. DRM controller tracked factory/service sayısını sınırlar ve busy poll yapmaz. Package, rule, DRM, RKP, certificate, patch, template ve keybox cache'leri entry/byte limitlidir. Release Rust LTO, size optimization ve hardened native linking seçenekleri kullanır.

<a id="profiles"></a>
## Profiles

Profiller optional ayar gruplarını tek validated işlemle uygular; core boot, Keystore ve RKP altyapı koruması bunlardan bağımsız olarak aktif kalır.

Daily Compatibility targeted scope ve keybox monitoring kullanır; Default muhafazakâr optional identity düzenidir; Maximum Compatibility Global Mode, build identity, identity refresh ve telephony yollarını açıp DRM passthrough'u kapatır; Minimal optional identity ve scheduled keybox kontrollerini kapatır. Bu profillerin hiçbiri RKP altyapı korumasını değiştirmez.

Eski yapılandırmalar retired `rkp_passthrough` işaretini taşıyabilir; runtime generated-key davranışı artık bu değere bağlı değildir. Version two profilleri app assignment, template, doğrulanmış keybox, privacy, patch ve optional identity/DRM seçimlerini saklayabilir; legacy RKP alanı yalnız migration uyumluluğu için korunabilir ve WebUI'da canlı seçenek değildir.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity, başka aktif modül fingerprint/product property layer'ını yönetiyorsa onu ezmemek için provider detection yapar. KernelSU/APatch enabled module directories içindeki yaygın PIF, auto_pif/autopif ve PlayCurl varyantları aynı normalize politikayla algılanır.

Conflict varsa automatic mode optional Build properties'i değiştirmez fakat attestation, keybox, patch, RKP, DRM ve telephony özellikleri çalışabilir. Force mode bilinçli olarak detection'ı bypass eder; günlük kullanım için automatic önerilir.

<a id="region-properties"></a>
## Region Properties

Region Properties, küçük sabit bir Android property seti üzerinden optional bounded China-region görünümü sağlar. Hardware country, SIM country, operator country, hardware level ve radio compatibility marker gibi değerler code içinde sabittir ve arbitrary user input kabul edilmez.

Spoof Engine açıkken Zygote öncesi uygulanır. Bu kontrol gerçek SIM ülkesini, radio registration, modem firmware, secure hardware sales region veya carrier account'u değiştirmez. Vendor sorunu varsa kapatıp reboot edilmelidir.

<a id="remote-sources"></a>
## Remote Sources

Remote Sources, açıkça yapılandırılmış HTTPS endpoint'ten authorized keybox material alır. Host, port, path, timeout, refresh interval, auth type/header ve response size sınırlandırılır; secret status response içinde gösterilmez.

Signed content zorunlu yapılabilir. Signature, XML/CBOX formatı, size, keybox, certificate ve revocation validation tamamlanmadan veri active olmaz. Failed refresh mevcut verified material'i bozuk download ile değiştirmez. Güvendiğiniz veya kontrol ettiğiniz endpoint kullanın.

<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning koruması Android provisioning altyapısını gerçek platform yolunda tutar. Android/Google RKP ve eski Remote Provisioner paketleri substitution scope dışında kalır; sistem UID'leri ve çözülemeyen package durumları da fail closed davranır.

RKP altyapı caller'ları hiçbir zaman değiştirilmez. Hedeflenen uygulama UID'lerinde `generateKey` ve sonraki `getKeyEntry` sertifika yanıtları aynı compatibility yolunu kullanır; bu, tek alias'ın iki farklı attestation leaf göstermesini önler.

Eski `rkp_passthrough` switch'i retired durumdadır. Eski config/backup içinde işaret bulunabilir ancak generated-key davranışını artık yönetmez ve WebUI runtime toggle olarak sunmaz. Built-in profiller RKP davranışını değiştirmez; RKP altyapı koruması her zaman aktiftir.

CleveresTricky bir RKP sunucusu simüle etmez, provisioning credential üretmez veya hardware provisioning root'u değiştirmez.

<a id="security-model"></a>
## Security Model

Root service, işletim sistemi, KernelSU/APatch, installed module files ve explicitly authorized key material local trust boundary'nin trusted parçalarıdır. Applications, Binder content, uploads, remote response, config edit, archive entry, package rule, template, path ve network metadata untrusted input kabul edilir.

Config root gerçek root-owned directory olmalı; sensitive file root-only mode kullanır, symlink reddedilir ve writes atomiktir. Native parser live Binder ABI'yi doğrular, kernel-validated bounded copy sonrası parse eder. Injector yalnız bilinen entry/resume symbol, desteklenen stopped process, executable platform symbol map ve güvenli root-owned library kabul eder. WebUI TCP port açmaz; native queue/bridge fixed API allowlist ve strict bounds kullanır. Hostile root process'e karşı tam güvenlik garanti edilemez.

<a id="spoof-engine"></a>
## Spoof Engine

Spoof Engine optional app-facing identity runtime control'dür. Core Keystore/TEE interception, certificate compatibility, root of trust handling ve boot protection motor kapalıyken de core path olarak devam eder.

Motor açıkken configured attestation identity, Telephony Identity, optional Build Identity, Region Identity ve Identity Refresh kendi dedicated controls ile çalışabilir. Motor kapatılınca identity values kaybolmaz, sadece interception path'lerde sunulmaz. Application cache nedeniyle live değişiklik sonrası app restart, Build Identity değişikliğinde reboot gerekebilir.

<a id="telephony-identity"></a>
## Telephony Identity

Telephony Identity Android telephony Binder API'leri üzerinden selected app'lere dönen IMEI, MEID, IMSI, ICCID ve phone number değerlerini destekler; iki SIM slotu için ayrı değer kullanılabilir. IMEI/ICCID checksum, numeric/hex/phone syntax, slot ve input size validation uygulanır.

Interceptor önce genuine Android response'u alır. Android permission denied/null/error verirse bu karar korunur; modül uygulamaya okuyamadığı identifier için yeni yetki kazandırmaz. Değerler yalnız app-facing'dir ve modem, baseband, EFS, fiziksel SIM veya network operator kimliğini değiştirmez.

<a id="web-interface"></a>
## Web Interface

Runtime WebUI file ownership sabittir: `index.html` static markup/base CSS, `bridge.js` native KernelSU/APatch bridge ve external intents, `policy.js` policy/state API ve policy-owned dynamic UI, `ux.js` general presentation/localization/guide/community UX sahibidir. Standalone runtime CSS veya feature-specific JS bundle eklenmez; testler `webroot` dışında kalır.

Mobilde tab menu bottom safe-area ile kullanılır; touch-sized controls, responsive panels, password visibility, progress state ve accessible tab state bulunur. WebUI TCP port dinlemez; module manager native API üzerinden bounded Rust bridge kullanır. Request ID randomness, root-only queue, atomic publication, strict file/path/method/size/time bounds ve service-side validation privileged işlemleri korur.

<a id="changelog"></a>
## CHANGELOG

V2.5.3; granular identity ve security patch controls, named profiles ve Effective State görünümü ekledi; attestation/KeyMint/StrongBox, DRM identifier privacy, upgrade ve Android 17 uyumluluğunu güçlendirdi. WebUI ownership sabit dosya setinde birleştirildi, yerleşik local translations ve Configuration Management UX iyileştirildi, KeyboxHub remote helper external browser routing ile eklendi. Runtime diagnostics, cache/timing, dependency security, regression coverage ve artifact validation iyileştirildi.

<a id="contributing"></a>
## Contributing

Değişiklikler fail-closed security model'i, Android 12-17 ve KernelSU/APatch kapsamını korumalıdır. Doğrulanamayan hardware-backed integrity iddiaları yapılmamalıdır. İlgili Kotlin/Android ve Rust checks çalıştırılmalı; portable native additions Rust olmalı, first-party C yasak, `binder_interceptor.cpp` tek first-party C++ istisnasıdır.

Binder parcels, XML, ZIP/CBOX, HTTP, paths ve process IDs untrusted kabul edilmelidir. Bounds explicit tutulmalı, malformed input ve failure paths için regression test eklenmeli, private key/keybox/token/device secret/generated APK/ZIP commit edilmemeli ve user-visible değişiklikte README/CHANGELOG güncellenmelidir.

<a id="donate"></a>
## Development Support

Projeyi faydalı buluyorsanız geliştirmeyi destekleyebilirsiniz. Resmi bağış seçenekleri root `DONATE.md` dosyasında yer alan USDT TRC20, XMR Monero, USDT/USDC ERC20/BEP20 adresleri ile Binance User ID, PayPal, BuyMeACoffee ve proje sahibinin bağış sayfasıdır. Adresleri kullanmadan önce kanonik İngilizce `DONATE.md` içinden güncel değeri doğrulayın.

<a id="languages"></a>
## Language Support

WebUI'nin dokuz yerleşik dili English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी ve العربية'dır. Runtime localization yalnız `ux.js` içinde tutulur; locale-specific JS/CSS runtime asset oluşturulmaz. Kullanıcı dokümantasyonu aynı dokuz dilde README ve bu merkezi referans üzerinden sunulur.

Yeni veya değiştirilen user-facing Markdown içerik, İngilizce canonical dokümanla birlikte ilgili localized reference bölümlerinde güncel tutulmalıdır. Teknik çelişkide English canonical davranış ve kaynak kod önceliklidir.

<a id="logging"></a>
## Logging and Diagnostics

CleveresTricky ayrı plain log file tutmaz; tanılama Android logcat'e yazılır. Temel komut `adb logcat -s cleverestricky CleveresTricky` şeklindedir. `Welcome to Service!`, web server/bridge başlangıç mesajları, Binder interceptor ve TEE kayıt mesajları faydalı startup marker'lardır.

`TAMPER DETECTED`, Binder ABI validation failure, rejected keybox veya injector timeout action gerektirir. Log yayımlamadan önce inceleyin; credential/token bilerek yazılmasa da file name, package, device property ve PID hassas olabilir.

<a id="theme"></a>
## UI Theme

WebUI minimalist monochrome Nothing OS / iOS hybrid tasarım kullanır. Arka plan koyu charcoal, foreground light gray, accent silver, panel dark gray, success emerald ve danger red'dir. Typography system sans-serif, teknik veri monospace; Dynamic Island bildirimleri, rounded buttons, iOS-style toggles ve responsive mobile-first layout kullanılır.

Touch target'lar en az yaklaşık 44 px olmalı, vertical flow horizontal karmaşıklığa tercih edilmeli ve tasarım KernelSU/APatch içindeki telefon kullanımına optimize edilmelidir.
