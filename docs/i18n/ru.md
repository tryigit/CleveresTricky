# Документация CleveresTricky

**Язык:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | [Español](es.md) | [Deutsch](de.md) | **Русский** | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | [العربية](ar.md)

[Русская README](../../README.ru.md)

> Это локализованная справка по пользовательским Markdown-документам. При техническом расхождении приоритет у английской документации и исходного кода.

<a id="application-rules"></a>
## Application Rules

Назначает приложению template, проверенный локальный keybox или privacy policy. Валидное правило само является target. `inherit` сохраняет глобальную policy, `isolate` создает стабильные app-scoped IMEI/IMSI/ICCID/MEID/phone/serial/attestation identifiers и DRM `deviceUniqueId` pseudonym, `redact` очищает поддерживаемые значения, сохраняя Android permission failures.

Attestation Identity требует активный verified keybox. DRM isolation независим от DRM Keystore Passthrough. Shared UID разрешается детерминированно через Package Manager, а имя из запроса не считается authority. Новое состояние публикуется атомарно и очищает связанные cache.

<a id="application-scope"></a>
## Application Scope

Определяет UID приложений, которым доступна certificate/identity compatibility. Targeted Mode использует точные package и ограниченные wildcard из `target.txt`, разрешая их через Package Manager к реальному caller. Shared UID означает общую Binder identity.

Global Mode не требует записи target, но исключает system identity и protected infrastructure. Неизвестное разрешение fail closed. Rules и короткий decision cache заменяются вместе.

<a id="attestation"></a>
## Attestation

Слой attestation обеспечивает управляемую совместимость цепочек сертификатов для выбранных приложений, сохраняя настоящую генерацию ключей Android и последующие криптографические операции.

Вызовы инфраструктуры RKP всегда остаются на штатном пути provisioning Android. Для целевых UID приложений успешные ответы `generateKey` и последующее чтение сертификатов через `getKeyEntry` используют единый путь совместимости, чтобы один alias не показывал разные attestation leaf-сертификаты.

Операции с закрытым ключом по-прежнему выполняются Android KeyMint или StrongBox. Перед активацией проверяются соответствие ключа и сертификата, алгоритм, цепочка, срок действия, неоднозначность и revocation. Подмена сертификата не создаёт аппаратный root of trust, не блокирует bootloader физически и не гарантирует удалённый verdict.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Обновляет keybox/revocation без постоянного сканирования. File observer обрабатывает обычные изменения, низкочастотный fallback покрывает несовместимые filesystem. Повторные ошибки не создают overlapping workers.

Refresh повторяет проверку key, chain, algorithm, validity, ambiguity, revocation. При недоступной revocation новый material не активируется. Cache ограничен числом и размером файлов.

<a id="backup-restore"></a>
## Backup and Restore

Переносит config и authorized key material в authenticated encrypted archive. Export требует пароль минимум 12 символов и allowlist файлов, отклоняя symlink/unknown path/excessive size.

Import принимает только encrypted CTSB и ограничивает upload, entries, keyboxes и expanded size. Traversal, duplicates, directories, symlink target, malformed settings и invalid keybox отклоняются до записи. Policy v2 публикуется единым validated snapshot.

<a id="boot-properties"></a>
## Boot Properties

Core userspace property view уменьшает видимость unlocked/debug/warranty/verified-boot/recovery indicators. Фиксированный набор применяется до Zygote и работает независимо от optional identity.

`boot_props_mode` относится только к Build Identity compatibility (`auto`, `force`, `disable`) и не выключает core protection. Он не relock bootloader, не repair verified boot и не меняет TEE root of trust.

<a id="build-identity"></a>
## Build Identity

Применяет полный device template к fingerprint и поддерживаемым app-visible Build fields. Optional, требует Spoof Engine и reboot. Arbitrary Android properties отклоняются.

Auto Identity может получить Pixel beta/canary identity из public Google metadata и сохранить локально без автоматического включения engine. Build Identity, Security Patch, Region, Telephony и Attestation Identity независимы.

<a id="building"></a>
## Building

Нужны Java 21, SDK API 36, NDK 27.3.13750724, CMake 3.22.1, stable Rust, ARM64/x86-64 Android targets, Cargo NDK, submodules. Требуются Kotlin/Android checks, Rust fmt/clippy/tests и unit tests.

CI проверяет shell, SELinux, template, Kotlin/Java/Rust, обе архитектуры, release/debug ZIP и Encryptor. First-party C запрещен; `binder_interceptor.cpp` единственная first-party C++ ABI boundary. Release: `./gradlew zipRelease`.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Legacy concept. Текущая WebUI не дает выключить core Keystore/TEE compatibility. Scope задают Global Mode/Application Rules, Spoof Engine управляет только identity.

`tee_broken_mode` может читаться для migration, но core targeting от него не зависит. Для диагностики следует уменьшать scope, использовать passthrough или контролируемо убирать key material.

<a id="diagnostics"></a>
## Diagnostics

Сначала проверить Dashboard: version, Engine, profile, keybox count, target size, RKP, DRM, native features; затем найти первый error в Logs. При недоступной WebUI проверить logcat, daemon, `webroot`, architecture-specific `webui_bridge` и manager state.

Для изоляции использовать Minimal + reboot, проверить genuine path и добавлять функции по одной. Effective State показывает rule/profile, scope, template, keybox ref, privacy, features, patches, RKP/DRM, KeyMint/StrongBox, provider coexistence и reboot requirement без private keys.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Passthrough держит выбранные media apps на genuine Android Keystore certificate path. Identifier Privacy заменяет только supported stable-AIDL `deviceUniqueId` для `privacy=isolate` стабильным app-scoped pseudonym без использования genuine DRM ID в derivation.

`drm_packages.txt` поддерживает exact packages и bounded wildcard. Privacy hook ограничен `IDrmFactory` / `IDrmPlugin.getPropertyByteArray("deviceUniqueId")`; HIDL, security level, licenses, provisioning, keys, sessions, HDCP и string properties не меняются. Неожиданный ABI сохраняет original response fail open.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX использует authenticated AES-256-GCM и связывает metadata с ciphertext. Password containers используют bounded key derivation, локальный protected-cache key хранится в private config.

Unlock доступен только через native WebUI и не обходит keybox verification. Key/certificate/chain/date/algorithm/revocation проверяются снова. Hostile root может прочитать уже unlocked data.

<a id="identity-refresh"></a>
## Identity Refresh

Готовит validated identity на следующий boot без изменения текущего snapshot. Early boot проверяет staged file и атомарно promotes его, после чего Build Properties и service используют одно состояние.

IMEI/ICCID checksum и длины ограничены. Manual edit удаляет старый staged snapshot; выключение Engine/Refresh до boot предотвращает нежелательную promotion.

<a id="installer"></a>
## Installer

Ставит полный KernelSU/APatch module на Android 12-17 ARM64/x86 64. Magisk/recovery отклоняются до partial install.

Каждый payload имеет SHA 256; runtime отклоняет symlink/non-regular/unexpected files. Internal hashes не доказывают автора, поэтому официальный Release публикует `SHA256SUMS` и GitHub signed build provenance.

<a id="keybox-manager"></a>
## Keybox Manager

Загружает, проверяет, выбирает и мониторит authorized attestation material в legacy/XML/CBOX. Application Rules могут ссылаться на конкретный verified file; remote material untrusted до такой же локальной проверки.

Private key должен совпадать с leaf certificate; проверяются algorithm, chain, date, duplicate/ambiguity и revocation. Неопределенная revocation не активирует новый material, broken pool отклоняется полностью.

<a id="native-architecture"></a>
## Native Architecture

Portable native logic реализуется на Rust. First-party C отсутствует; `binder_interceptor.cpp` единственная C++ exception для private Android libbinder object ABI. Rust Core валидирует Binder layouts/streams, FD и kernel-validated copies.

Rust Injector управляет files, SELinux socket, FD transfer, maps/symbols, ptrace, registers, remote memory, loader и cleanup. Temporary stack writes восстанавливаются bounded journal. C++ exception не должна расширяться.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` задает System/Vendor/Boot global/per-app rules. Поддерживаются date, `today`, `device_default`, `prop`, `no`; policy v2 имеет независимые Device, Property, Manual, Automatic, Omit.

Parsing bounded и invalid input не публикует partial state. Automatic использует calendar arithmetic. Функция не устанавливает реальные security updates, не меняет kernel/vendor firmware и не гарантирует verdict.

<a id="performance"></a>
## Performance and Memory

Core Keystore interception остается активным; выключенный Spoof Engine паркует optional identity/DRM/build/region/telephony work. Automatic Keybox Check управляется отдельно.

Binder parser использует fixed arrays и 64-slot descriptor cache. Controller/cache имеют лимиты и избегают busy polling. Release Rust использует LTO, size optimization и hardened linking.

<a id="profiles"></a>
## Profiles

Профили применяют наборы необязательных настроек одной проверенной транзакцией; базовая защита boot, Keystore и инфраструктуры RKP остаётся активной независимо.

Daily Compatibility использует targeted scope и мониторинг keybox; Default: консервативный режим; Maximum Compatibility включает Global Mode, build identity, identity refresh и telephony и выключает DRM passthrough; Minimal отключает необязательную identity-логику и плановые проверки keybox. Ни один preset не меняет защиту инфраструктуры RKP.

Старые конфигурации могут содержать выведенный из эксплуатации маркер `rkp_passthrough`, но generated-key поведение больше от него не зависит. Профили version two могут хранить назначения приложений, template, проверенный keybox, privacy, patch и параметры identity/DRM; старое поле RKP сохраняется только для миграционной совместимости и не является активной настройкой WebUI.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity обнаруживает другие fingerprint/property providers, включая PIF, `autopif`/`auto_pif`, PlayCurl, и не перезаписывает их.

При конфликте optional Build properties untouched, остальные функции могут работать. Force намеренно bypass detection; Automatic рекомендуется.

<a id="region-properties"></a>
## Region Properties

Предоставляет небольшой фиксированный China-region view через hardware/SIM/operator country, hardware level и radio marker. Arbitrary properties не принимаются.

Применяется до Zygote при включенном Spoof Engine. Не меняет реальную SIM country, radio registration, modem firmware, secure sales region или carrier account.

<a id="remote-sources"></a>
## Remote Sources

Получает authorized keybox только с явно настроенного HTTPS. Host/port/path/timeout/refresh/auth/header/size bounded, secrets не возвращаются в status.

Можно требовать signature. До signature, XML/CBOX, size, keybox, certificate и revocation validation ничего не активируется. Failed refresh не заменяет verified material.

<a id="rkp-protection"></a>
## RKP Protection

Защита Remote Key Provisioning оставляет инфраструктуру provisioning Android на штатном платформенном пути. Пакеты RKP Android/Google и legacy Remote Provisioner всегда исключены из области подмены; системные UID и неизвестное разрешение пакета работают fail closed.

Вызовы инфраструктуры RKP никогда не изменяются. Для целевых UID приложений `generateKey` и последующие ответы `getKeyEntry` используют единый путь совместимости сертификатов, что не позволяет одному alias показывать два разных attestation leaf.

Старый переключатель `rkp_passthrough` выведен из эксплуатации. Маркер может оставаться в старых конфигурациях и backup, но больше не управляет generated-key и не показывается как runtime toggle WebUI. Встроенные Profiles не меняют RKP: защита инфраструктуры всегда включена.

CleveresTricky не эмулирует RKP-сервер, не создаёт provisioning credentials и не меняет аппаратный provisioning root.

<a id="security-model"></a>
## Security Model

Root service, OS, KernelSU/APatch, module files и authorized key material trusted. Apps, Binder, uploads, remote responses, config, archives, rules, templates, paths, network metadata untrusted.

Config root-owned, sensitive root-only, symlink rejected, writes atomic. Binder ABI и kernel-validated copies проверяются. Injector ограничивает symbol/process/library, WebUI не открывает TCP и использует strict native bridge. Hostile root вне полной защиты.

<a id="spoof-engine"></a>
## Spoof Engine

Optional app-facing identity controller. Core Keystore/TEE, certificate compatibility, root of trust и boot protection продолжаются даже когда он выключен.

При включении работают optional Attestation/Telephony/Build/Region/Refresh по своим controls. Выключение не удаляет saved values. App cache может потребовать restart, Build Identity reboot.

<a id="telephony-identity"></a>
## Telephony Identity

Может представлять IMEI, MEID, IMSI, ICCID и phone для двух SIM через supported Binder APIs. Checksums, length, syntax, slot, input size валидируются.

Сначала получается genuine Android response; permission denial/error/null сохраняются. Modem, baseband, EFS, physical SIM и carrier identity не меняются.

<a id="web-interface"></a>
## Web Interface

Fixed ownership: `index.html` markup/base CSS, `bridge.js` native bridge/intents, `policy.js` policy/state UI, `ux.js` presentation/localization/guide/community. Нет standalone runtime CSS или feature JS bundles.

Mobile bottom navigation, touch controls, responsive panels, password visibility, progress, accessibility. Нет TCP listener: native module-manager API, bounded Rust bridge, root-only queue и strict validation.

<a id="changelog"></a>
## CHANGELOG

V2.5.3: granular identity/security patch controls, profiles и Effective State; hardening Attestation/KeyMint/StrongBox/DRM privacy/upgrades/Android 17; consolidated WebUI and translations; KeyboxHub external browser helper; улучшения diagnostics, cache/timing, dependency security, regression и artifact validation.

<a id="contributing"></a>
## Contributing

Сохранять fail-closed model, Android 12-17, KernelSU/APatch и не делать unverifiable hardware claims. Нужны Kotlin/Android/Rust checks; portable native на Rust, first-party C запрещен, `binder_interceptor.cpp` единственная C++ exception.

Binder/XML/ZIP/CBOX/HTTP/path/PID untrusted и требуют bounds/failure tests. Не коммитить private keys/keyboxes/tokens/secrets/generated APK/ZIP. User-visible changes требуют docs update.

<a id="donate"></a>
## Development Support

Поддержать проект можно способами из канонического `DONATE.md`: USDT TRC20, XMR, USDT/USDC ERC20/BEP20, Binance User ID, PayPal, BuyMeACoffee и сайт автора. Перед переводом проверяйте актуальные адреса в английском оригинале.

<a id="languages"></a>
## Language Support

WebUI встроенно поддерживает English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी, العربية. Runtime catalogs находятся только в `ux.js`; locale-specific JS/CSS не создаются. README и эта user-doc reference доступны в тех же девяти языках.

Изменения user-facing Markdown должны синхронно отражаться в English canonical и переводах.

<a id="logging"></a>
## Logging and Diagnostics

Diagnostics пишутся в Android logcat, отдельного plaintext log нет. Команда: `adb logcat -s cleverestricky CleveresTricky`. Startup markers service/bridge/Binder/TEE помогают диагностике.

`TAMPER DETECTED`, Binder ABI failure, rejected keybox, injector timeout требуют анализа. Перед публикацией проверять file names, packages, properties и PIDs.

<a id="theme"></a>
## UI Theme

Минималистичный monochrome Nothing OS/iOS hybrid: charcoal background, light gray text, silver accent, dark panels, green success, red danger. System sans, monospace для technical data, Dynamic Island, rounded buttons, iOS toggles, mobile-first layout.

Touch target около 44px+, вертикальный flow приоритетен и UI оптимизирован для телефона внутри KernelSU/APatch.
