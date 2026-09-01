# CleveresTricky दस्तावेज़

**भाषा:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | [Español](es.md) | [Deutsch](de.md) | [Русский](ru.md) | [Bahasa Indonesia](id.md) | **हिन्दी** | [العربية](ar.md)

[हिन्दी README](../../README.hi.md)

> यह user-facing Markdown documentation का localized reference है। किसी technical अंतर पर English canonical docs और source code प्राथमिक हैं।

<a id="application-rules"></a>
## Application Rules

Eligible app को template, verified local keybox या privacy policy assign करता है। Valid rule स्वयं explicit target है। `inherit` global policy रखता है, `isolate` protected random seed से stable app-scoped IMEI/IMSI/ICCID/MEID/phone/serial/attestation identifiers और DRM `deviceUniqueId` pseudonym बनाता है, `redact` supported identifiers blank करता है पर Android permission failures बचाता है।

Attestation Identity को active verified keybox चाहिए। DRM isolation, DRM Keystore Passthrough से independent है। Shared UID Package Manager के real package set से deterministic resolve होता है। State atomic snapshot के रूप में publish होती है और cache invalidate होती है।

<a id="application-scope"></a>
## Application Scope

तय करता है कि कौन से Android app UID प्रमाणपत्र/Keybox या पहचान (Identity) अनुकूलता पाएंगे। मॉड्यूल दो अलग लक्ष्य फ़ाइलें और दो ग्लोबल मोड प्रदान करता है:

- **Keybox लक्ष्य (`target.txt`)**: ग्लोबल Keybox मोड बंद होने पर कस्टम Keybox और TEE अटेस्टेशन प्राप्त करने वाले पैकेज निर्दिष्ट करता है।
- **Identity लक्ष्य (`identity_target.txt`)**: ग्लोबल Identity मोड बंद होने पर प्रति-ऐप पहचान गुण (Build, Telephony, Region) पाने वाले पैकेज निर्दिष्ट करता है।
- **ग्लोबल Keybox मोड**: बिना `target.txt` के सभी उपयोगकर्ता ऐप्स पर कस्टम Keybox लागू करता है। सिस्टम और इंफ्रास्ट्रक्चर UID सुरक्षित रहते हैं।
- **ग्लोबल Identity मोड**: पूरे डिवाइस में सिस्टम स्तर पर Build गुण लागू करता है। बंद होने पर यह केवल `identity_target.txt` और निर्दिष्ट प्रोफाइल पर असर डालता है।
- **स्वतंत्र सुरक्षा पैच मॉड्यूल**: सुरक्षा पैच को डैशबोर्ड से पहचान इंजन से स्वतंत्र रूप से प्रबंधित किया जा सकता है।

Shared UID वाले पैकेज Binder पहचान साझा करते हैं। अमान्य अपडेट fail-closed होते हैं।

<a id="attestation"></a>
## Attestation

Attestation layer चुने हुए apps के लिए नियंत्रित certificate-chain compatibility देता है, जबकि Android की वास्तविक key creation और बाद की cryptographic operations बनी रहती हैं।

RKP infrastructure callers हमेशा Android के genuine provisioning path पर रहते हैं। Target app UID के लिए सफल `generateKey` replies और बाद की `getKeyEntry` certificate reads एक ही compatibility path का उपयोग करती हैं, ताकि एक alias अलग-अलग attestation leaf न दिखाए।

Private-key operation Android KeyMint या StrongBox ही करता है। Material active होने से पहले key/certificate match, algorithm, chain, validity, ambiguity और revocation जाँचे जाते हैं। Certificate substitution hardware root of trust नहीं बनाता, bootloader को physically lock नहीं करता और remote verdict की guarantee नहीं देता।

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Continuous storage scan के बिना keybox/revocation update रखता है। File observer normal changes संभालता है और जरूरत पर low-frequency fallback चलता है।

हर refresh key, chain, algorithm, validity, ambiguity, revocation verify करता है। Revocation unknown हो तो नया material activate नहीं होता। Cache file count/size bounded है।

<a id="backup-restore"></a>
## Backup and Restore

Config और authorized key material को authenticated encrypted archive में transfer करता है। Export कम से कम 12-char password और allowlist files उपयोग करता है; symlink/unknown path/excessive size reject होते हैं।

Import केवल encrypted CTSB स्वीकारता है और upload/entry/keybox/expanded size limits लागू करता है। Traversal, duplicate, directory, symlink target, malformed setting/keybox write से पहले reject होते हैं। Policy v2 full snapshot के रूप में validate होती है।

<a id="boot-properties"></a>
## Boot Properties

Core userspace property view common unlocked/debug/warranty/verified-boot/recovery indicators की exposure कम करता है। Fixed set Zygote से पहले apply होता है और optional identity से independent active रहता है।

`boot_props_mode` केवल optional Build Identity compatibility (`auto`, `force`, `disable`) नियंत्रित करता है, core protection नहीं। यह bootloader physically relock, verified boot repair या TEE root of trust change नहीं करता।

<a id="build-identity"></a>
## Build Identity

Full device template को fingerprint और supported app-visible Build fields पर लागू करता है। Optional है, Spoof Engine और reboot चाहिए। Arbitrary Android properties reject होती हैं।

Auto Identity public Google metadata से Pixel beta/canary resolve कर local save कर सकता है, engine auto-on नहीं करता। Build Identity, Security Patch, Region, Telephony और Attestation Identity independent हैं।

<a id="building"></a>
## Building

Java 21, SDK API 36, NDK 27.3.13750724, CMake 3.22.1, stable Rust, ARM64/x86-64 Android targets, Cargo NDK और submodules चाहिए। Kotlin/Android checks, Rust fmt/clippy/tests और unit tests pass होने चाहिए।

CI shell, SELinux, template, Kotlin/Java/Rust, दोनों architectures, release/debug ZIP और Encryptor verify करता है। First-party C prohibited है; `binder_interceptor.cpp` केवल C++ ABI exception है। Release: `./gradlew zipRelease`।

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Legacy concept है। Current WebUI core Keystore/TEE compatibility off करने का switch नहीं देता। Scope Global Mode/Application Rules तय करते हैं, Spoof Engine केवल identity।

`tee_broken_mode` migration के लिए पढ़ा जा सकता है पर core targeting पर निर्भर नहीं। Diagnosis में scope कम करें, passthrough उपयोग करें या controlled key material हटाएं।

<a id="diagnostics"></a>
## Diagnostics

Dashboard में version, Engine, profile, keybox count, target size, RKP, DRM, native features देखें और Logs में पहला error खोजें। WebUI न खुले तो logcat, daemon, `webroot`, architecture-specific `webui_bridge` और manager state जांचें।

Info & Resources में Copy Diagnostics fixed allowlist और English keys वाला bounded support snapshot कॉपी करता है। इसमें version, root environment, native/interceptor state, aggregate keybox/rule count, process CPU/RSS और feature flags होते हैं; logs, package/keybox names, identity values, credentials, server configuration या key material नहीं। Feature flags module configuration बताते हैं, इसलिए साझा करने से पहले snapshot जांचें।

Isolation के लिए Minimal + reboot, genuine path verify, फिर features एक-एक करके enable करें। Effective State rule/profile, scope, template, keybox ref, privacy, features, patches, RKP/DRM, KeyMint/StrongBox, provider coexistence और reboot requirement दिखाता है, private keys नहीं।

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Passthrough selected media apps को genuine Android Keystore certificate path पर रखता है। Identifier Privacy केवल supported stable-AIDL `deviceUniqueId` को `privacy=isolate` apps के लिए stable app-scoped pseudonym से बदलता है, genuine DRM ID derivation input नहीं है।

`drm_packages.txt` exact package/bounded wildcard support करता है। Plugin निर्माण के समय पैकेज नाम और रनटाइम यूज़र कॉन्टेक्स्ट (multi-user / work-profile) कैप्चर किया जाता है। Hook `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")` तक सीमित है; HIDL, security level, license, provisioning, keys, sessions, HDCP, string property नहीं बदलते। Unexpected ABI fail open रहता है।

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX authenticated AES-256-GCM उपयोग करता है और metadata को ciphertext से bind करता है। Password containers bounded key derivation उपयोग करते हैं; local protected cache key private config में रहती है।

Unlock केवल native WebUI से होता है और keybox verification bypass नहीं करता। Key/certificate/chain/date/algorithm/revocation फिर जांचे जाते हैं। Hostile root unlocked data पढ़ सकता है।

<a id="identity-refresh"></a>
## Identity Refresh

Next boot के लिए validated identity तैयार करता है, current snapshot नहीं बदलता। Early boot staged file validate और atomic promote करता है ताकि Build Properties और service एक ही state उपयोग करें।

IMEI/ICCID checksum और lengths bounded हैं। Manual edit old stage हटाता है; boot से पहले Engine/Refresh off unwanted promotion रोकता है।

<a id="installer"></a>
## Installer

Android 12-17 ARM64/x86-64 पर full KernelSU/APatch module install करता है। Magisk/recovery partial install से पहले stop होते हैं।

हर payload SHA 256 से verified है; runtime symlink/non-regular/unexpected files reject करता है। Internal hash publisher proof नहीं है, इसलिए official Release में `SHA256SUMS` और GitHub signed build provenance है।

<a id="keybox-manager"></a>
## Keybox Manager

Authorized attestation material को legacy/XML/CBOX रूप में load, verify, select, monitor करता है। Application Rule specific file चुन सकता है; remote data local verification तक untrusted है।

Private key leaf certificate से match होना चाहिए; algorithm, chain, date, duplicate/ambiguity, revocation check होती है। Unknown revocation नया material activate नहीं करती, broken pool पूरा reject होता है।

<a id="native-architecture"></a>
## Native Architecture

Portable native logic Rust में है। First-party C नहीं है; private Android libbinder object ABI के कारण `binder_interceptor.cpp` एकमात्र C++ exception है। Rust Core Binder layouts/streams, FD और kernel-validated copies validate करता है।

Rust Injector files, SELinux socket, FD transfer, maps/symbols, ptrace, registers, remote memory, loader, cleanup संभालता है। Temporary stack writes bounded journal से restore होते हैं। C++ exception नहीं बढ़नी चाहिए।

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` System/Vendor/Boot global/per-app rules देता है। Date, `today`, `device_default`, `prop`, `no` और policy v2 में Device, Property, Manual, Automatic, Omit supported हैं।

Parsing bounded है और invalid input partial state नहीं लगाता। Automatic calendar arithmetic उपयोग करता है। Feature real security update install या remote verdict guarantee नहीं करता।

<a id="performance"></a>
## Performance and Memory

Core Keystore interception active रहता है; Spoof Engine off optional identity/DRM/build/region/telephony work park करता है। Automatic Keybox Check का अलग control है।

Binder parser fixed arrays और 64-slot descriptor cache उपयोग करता है। Controller/cache bounded और non-busy-poll हैं। Rust release LTO, size optimization, hardened linking उपयोग करता है।

<a id="profiles"></a>
## Profiles

Profiles optional settings के समूह को एक validated transaction में लागू करते हैं; core boot, Keystore और RKP infrastructure protection स्वतंत्र रूप से active रहती है।

Daily Compatibility targeted scope और keybox monitoring उपयोग करता है; Default conservative setup है; Maximum Compatibility Global Mode, build identity, identity refresh और telephony चालू करके DRM passthrough बंद करता है; Minimal optional identity और scheduled keybox checks बंद करता है। इनमें से कोई preset RKP infrastructure protection नहीं बदलता।

पुरानी configuration में retired `rkp_passthrough` marker रह सकता है, लेकिन generated-key behavior अब उस पर निर्भर नहीं है। Version two profiles app assignment, template, validated keybox, privacy, patch और optional identity/DRM choices रख सकते हैं; legacy RKP field केवल migration compatibility के लिए है और live WebUI option नहीं है।

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity PIF, `autopif`/`auto_pif`, PlayCurl जैसे दूसरे fingerprint/property provider detect कर overwrite नहीं करता।

Conflict पर optional Build properties genuine रहती हैं, अन्य features चल सकते हैं। Force detection bypass करता है; Automatic recommended है।

<a id="region-properties"></a>
## Region Properties

Fixed hardware/SIM/operator country, hardware level और radio marker से optional China-region view देता है। Arbitrary properties नहीं।

Spoof Engine के साथ Zygote से पहले apply। Real SIM country, radio registration, modem firmware, secure sales region, carrier account नहीं बदलते।

<a id="remote-sources"></a>
## Remote Sources

Explicit HTTPS से authorized keybox लाता है। Host/port/path/timeout/refresh/auth/header/size bounded हैं और secrets status में नहीं।

Signature optional-required हो सकती है। Signature, XML/CBOX, size, keybox, certificate, revocation validation से पहले data active नहीं। Failed refresh verified material नहीं बदलता।

<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning protection Android provisioning infrastructure को genuine platform path पर रखती है। Android/Google RKP और legacy Remote Provisioner packages substitution scope से बाहर रहते हैं; system UID और unknown package resolution fail closed रहते हैं।

RKP infrastructure callers कभी modify नहीं किए जाते। Target app UID के लिए `generateKey` और बाद के `getKeyEntry` certificate responses unified compatibility path का उपयोग करते हैं, जिससे एक alias दो अलग attestation leaf नहीं दिखाता।

पुराना `rkp_passthrough` switch retired है। Marker पुराने config/backup में रह सकता है, लेकिन अब generated-key behavior को control नहीं करता और WebUI runtime toggle के रूप में expose नहीं होता। Built-in Profiles RKP behavior नहीं बदलते; infrastructure protection हमेशा active है।

CleveresTricky RKP server simulate नहीं करता, provisioning credentials नहीं बनाता और hardware provisioning root नहीं बदलता।

<a id="security-model"></a>
## Security Model

Root service, OS, KernelSU/APatch, module files और authorized key material trusted हैं। Apps, Binder, upload, remote response, config, archive, rule, template, path, network metadata untrusted हैं।

Config root-owned, sensitive root-only, symlink rejected, writes atomic। Binder ABI/kernel-validated copies verify होती हैं। Injector symbol/process/library restrict करता है, WebUI TCP नहीं खोलता और strict native bridge उपयोग करता है। Hostile root पूर्ण defense से बाहर है।

<a id="spoof-engine"></a>
## Spoof Engine

Optional app-facing identity controller। Core Keystore/TEE, certificate compatibility, root of trust, boot protection off होने पर भी चलते हैं।

On होने पर optional Attestation/Telephony/Build/Region/Refresh controls अनुसार चल सकते हैं। Off saved values नहीं हटाता। App cache restart और Build Identity reboot मांग सकता है।

<a id="telephony-identity"></a>
## Telephony Identity

Supported Binder APIs में दो SIM slots के लिए IMEI, MEID, IMSI, ICCID, phone प्रस्तुत कर सकता है। Checksum, length, syntax, slot, input size validate होते हैं।

पहले genuine Android response लिया जाता है; permission denial/error/null preserve होते हैं। Modem, baseband, EFS, physical SIM, carrier identity नहीं बदलते।

<a id="web-interface"></a>
## Web Interface

Fixed ownership: `index.html` markup/base CSS, `bridge.js` native bridge/intents, `policy.js` policy/state UI, `ux.js` presentation/localization/guide/community। Standalone runtime CSS या feature JS bundles नहीं।

Mobile bottom navigation, touch controls, responsive panels, password visibility, progress, accessibility हैं। TCP listener नहीं; native manager API, bounded Rust bridge, root-only queues और strict validation।

<a id="changelog"></a>
## CHANGELOG

V2.6.2 keybox/CBOX refresh, recovery और publication को stable verified snapshots और atomic publication से मजबूत करता है, readers, quarantine और backend recovery के बीच races कम करता है और server restart, cache invalidation तथा auth validation बेहतर बनाता है। Auto Identity अब Pixel security patch को सही bulletin row से bind करता है; WebUI abort/response/export flows अधिक robust हैं। Backup/restore अधिक transactional है, bugreport और runtime file reads पर कड़े bounds/symlink protections हैं, और Rust X.509 व Rust 1.98 CI compatibility अपडेट हुई है।

V2.5.3 में granular identity/security patch controls, profiles, Effective State; Attestation/KeyMint/StrongBox/DRM privacy/upgrades/Android 17 hardening; consolidated WebUI/translations; KeyboxHub external-browser helper; diagnostics, cache/timing, dependency security, regression और artifact validation improvements आए।

<a id="contributing"></a>
## Contributing

Fail-closed model, Android 12-17, KernelSU/APatch scope बनाए रखें और unverifiable hardware claims न करें। Kotlin/Android/Rust checks चलाएँ; portable native additions Rust में, first-party C prohibited, `binder_interceptor.cpp` एकमात्र C++ exception।

Binder/XML/ZIP/CBOX/HTTP/path/PID untrusted हैं और bounds/failure tests चाहिए। Private keys/keyboxes/tokens/secrets/generated APK/ZIP commit न करें। User-visible changes पर docs update करें।

<a id="donate"></a>
## Development Support

Official `DONATE.md` में USDT TRC20, XMR, USDT/USDC ERC20/BEP20, Binance User ID, PayPal, BuyMeACoffee और developer website विकल्प हैं। Funds भेजने से पहले English canonical file में current address verify करें।

<a id="languages"></a>
## Language Support

WebUI में English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी, العربية built-in हैं। Runtime catalogs केवल `ux.js` में रहते हैं, locale-specific JS/CSS नहीं। User docs वही नौ भाषाओं में README और इस reference के रूप में उपलब्ध हैं।

User-facing Markdown change के साथ English canonical और relevant localized section sync रखें।

<a id="logging"></a>
## Logging and Diagnostics

Diagnostics Android logcat में जाते हैं, separate plaintext log नहीं। Command: `adb logcat -s cleverestricky CleveresTricky`। Service/bridge/Binder/TEE startup markers उपयोगी हैं।

`TAMPER DETECTED`, Binder ABI failure, rejected keybox, injector timeout जांचें। Publish से पहले filename/package/property/PID sensitivity review करें।

<a id="theme"></a>
## UI Theme

Minimal monochrome Nothing OS/Modern hybrid: charcoal background, light gray text, silver accent, dark panels, green success, red danger। System sans, technical monospace, Dynamic Island, rounded buttons, Modern toggles, mobile-first layout।

Touch targets लगभग 44px+, vertical flow preferred और KernelSU/APatch phone use के लिए optimized।