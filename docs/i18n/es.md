# Documentación de CleveresTricky

**Idioma:** [English](../../README.md) | [Türkçe](tr.md) | [简体中文](zh-CN.md) | **Español** | [Deutsch](de.md) | [Русский](ru.md) | [Bahasa Indonesia](id.md) | [हिन्दी](hi.md) | [العربية](ar.md)

[README en español](../../README.es.md)

> Esta referencia localiza toda la documentación Markdown orientada al usuario. Ante una diferencia técnica, prevalecen la documentación inglesa y el código fuente.

<a id="application-rules"></a>
## Application Rules

Application Rules asigna a una aplicación elegible una plantilla, un keybox local verificado o una política de privacidad. Una regla válida ya es un target explícito. `inherit` conserva la política global; `isolate` deriva IMEI, IMSI, ICCID, MEID, teléfono, serial, identificadores de attestation compatibles y un pseudónimo DRM `deviceUniqueId` estable por aplicación; `redact` devuelve vacíos los identificadores compatibles conservando las denegaciones de permisos de Android.

La identidad de attestation requiere un keybox activo y verificado. DRM isolation es independiente de DRM Keystore Passthrough. Los paquetes con shared UID se resuelven como un contexto determinista usando Package Manager, nunca un nombre de paquete proporcionado por la petición. El estado se publica como snapshot inmutable y limpia las cachés relacionadas.

<a id="application-scope"></a>
## Application Scope

Application Scope decide qué aplicaciones Android reciben compatibilidad de certificados/Keybox o de identidad. El módulo cuenta con dos archivos de destino y dos modos globales independientes:

- **Destinos de Keybox (`target.txt`)**: Define los paquetes que reciben Keybox personalizado y atestación TEE cuando el Modo Keybox global está desactivado.
- **Destinos de Identidad (`identity_target.txt`)**: Define los paquetes que reciben propiedades de identidad por app (Build, Telephony, Region) cuando el Modo Identidad global está desactivado.
- **Modo Keybox global**: Aplica Keybox personalizado a todas las aplicaciones sin requerir `target.txt`. Las identidades del sistema e infraestructura permanecen protegidas.
- **Modo Identidad global**: Aplica propiedades de Build a nivel de sistema para todo el dispositivo. Cuando está desactivado, la identidad solo afecta a `identity_target.txt` y perfiles asignados.
- **Módulo independiente de Parche de seguridad**: El parche de seguridad se gestiona independientemente del motor de identidad desde el Panel.

Los paquetes con un UID compartido comparten la misma identidad Binder. Las actualizaciones no válidas fallan de forma cerrada y conservan el último estado válido.

<a id="attestation"></a>
## Attestation

La capa de attestation ofrece compatibilidad controlada de cadenas de certificados para las aplicaciones seleccionadas, manteniendo la creación real de claves de Android y las operaciones criptográficas posteriores.

Los callers de infraestructura RKP permanecen siempre en la ruta genuina de provisioning de Android. Para los UID de aplicaciones objetivo, las respuestas correctas de `generateKey` y las lecturas posteriores de certificados mediante `getKeyEntry` comparten una sola ruta de compatibilidad, evitando que un mismo alias muestre hojas de attestation distintas.

La operación de clave privada sigue realizándose en Android KeyMint o StrongBox. Antes de activar material se validan correspondencia clave/certificado, algoritmo, cadena, vigencia, ambigüedad y revocación. La sustitución de certificados no crea una raíz de confianza hardware, no bloquea físicamente el bootloader ni garantiza un veredicto remoto.

<a id="automatic-keybox-check"></a>
## Automatic Keybox Check

Mantiene keyboxes y estado de revocación actualizados sin escanear continuamente. File observers manejan cambmodern normales y se usa un fallback de baja frecuencia cuando el filesystem lo requiere; errores repetidos no crean workers superpuestos.

Cada refresh repite la validación de clave, cadena, algoritmo, validez, ambigüedad y revocación. Material nuevo no se activa si no puede establecerse la revocación. Las cachés están limitadas por número y tamaño de archivos.

<a id="backup-restore"></a>
## Backup and Restore

Exporta configuración y material autorizado dentro de un archive autenticado y cifrado. Requiere contraseña de al menos 12 caracteres y usa una allowlist de archivos conocidos; symlinks, rutas desconocidas y límites excesivos se rechazan.

Import acepta solo CTSB cifrado y limita upload, entries, keyboxes, tamaños individuales y total expandido. Traversal, duplicados, directormodern, symlink destinations, texto, settings o keyboxes inválidos se rechazan antes de escribir. El estado de policy v2 también se valida y publica como un snapshot completo.

<a id="boot-properties"></a>
## Boot Properties

Es la vista userspace principal que reduce la exposición de indicadores comunes de unlocked/debug/warranty/verified boot/recovery. El conjunto de properties es fijo y se aplica antes de Zygote mientras el módulo y su early boot están operativos.

`boot_props_mode` solo controla compatibilidad opcional de Build Identity con `auto`, `force` o `disable`; no apaga la protección core. No relockea físicamente bootloader, no repara verified boot ni cambia TEE root of trust.

<a id="build-identity"></a>
## Build Identity

Aplica una plantilla completa a fingerprint y campos Build visibles para apps. Es opcional, requiere Spoof Engine y reboot. La plantilla incluye manufacturer, model, brand, product, device, fingerprint, release, build ID, incremental, type, tags y security patch; properties arbitrarias se rechazan.

Auto Identity puede resolver un Pixel beta/canary desde metadata pública de Google y guardarlo, sin encender el motor automáticamente. Build Identity, Security Patch, Region, Telephony y Attestation Identity se resuelven por separado.

<a id="building"></a>
## Building

Se requieren Java 21, SDK API 36, NDK 27.3.13750724, CMake 3.22.1, Rust estable, targets Android ARM64/x86 64, Cargo NDK y submodules. Deben pasar checks Kotlin/Android, Rust fmt/clippy/tests y unit tests.

CI valida shell, SELinux, template, Kotlin/Java/Rust, ambas arquitecturas, ZIP release/debug y Encryptor. First-party C está prohibido y `binder_interceptor.cpp` es la única frontera C++ permitida. Release se genera con `./gradlew zipRelease`.

<a id="certificate-safe-mode"></a>
## Certificate Safe Mode

Es un concepto heredado. La WebUI actual no ofrece un switch para desactivar la compatibilidad core de Keystore/TEE. Global Mode y Application Rules controlan scope; Spoof Engine controla solo identidad.

`tee_broken_mode` puede leerse para migración en instalaciones antiguas, pero el targeting core no depende de él. Para diagnosticar se recomienda reducir scope, usar passthrough apropiado o retirar material de claves en un entorno controlado.

<a id="diagnostics"></a>
## Diagnostics

Primero revisar version, Spoof Engine, profile, keybox count, target size, RKP, DRM y native feature state en Dashboard, y buscar el primer error en Logs. Si WebUI no inicia, comprobar logcat, daemon, `webroot`, `webui_bridge` por arquitectura y estado del module manager.

Copy Diagnostics en Info & Resources copia un resumen acotado con claves en inglés y una allowlist fija. Incluye version, root environment, native/interceptor state, conteos agregados de keybox/rule, process CPU/RSS y feature flags; excluye logs, nombres de package/keybox, identity values, credentials, server configuration y key material. Revisa el resumen antes de compartirlo porque los feature flags describen la configuración del módulo.

Para aislar, aplicar Minimal y reboot, confirmar genuine path y luego activar gradualmente targeted Spoof Engine, una fuente/regla y funciones opcionales. Effective State inspector muestra regla/perfil, scope, template, keybox ref, privacy, features, patches, RKP/DRM, KeyMint/StrongBox, provider coexistence y reboot requirement, nunca private keys.

<a id="drm-passthrough"></a>
## DRM Keystore Passthrough and Identifier Privacy

Keystore Passthrough mantiene apps multimedia seleccionadas en la ruta genuina de certificados Android. Identifier Privacy sustituye únicamente el `deviceUniqueId` compatible sobre stable AIDL para apps `privacy=isolate`, usando un pseudónimo estable por app que no deriva del ID DRM genuino.

`drm_packages.txt` permite paquetes exactos y wildcards acotados. Al crear el plugin se captura el paquete y el contexto de usuario (multi-user / work-profile). El hook de privacidad se limita a `IDrmFactory` y `IDrmPlugin.getPropertyByteArray("deviceUniqueId")`; no modifica HIDL legado, security level, licenses, provisioning, content keys, sessions, HDCP ni string properties. Si la forma esperada no existe, fail open y conserva la respuesta original.

<a id="encrypted-storage"></a>
## Encrypted Storage

CBOX usa AES-256-GCM autenticado para guardar y transferir keyboxes. Metadata queda ligada al ciphertext y los containers con contraseña usan derivación acotada; la clave del cache local protegido vive en configuración privada.

Unlock solo se acepta por el transporte nativo de WebUI. Descifrar no evita la verificación de keybox: se repiten checks de private key, certificate, chain, date, algorithm y revocation. Un proceso root hostil puede leer datos una vez desbloqueados.

<a id="identity-refresh"></a>
## Identity Refresh

Prepara una identidad validada para el siguiente boot sin cambiar el snapshot activo. Early boot valida path, tipo, tamaño, permisos y controles del staged file, lo promueve atómicamente y Build properties y service cargan el mismo snapshot.

IMEI/ICCID conservan checksums válidos y longitudes/caracteres están acotados. Una edición manual elimina un snapshot staged viejo; desactivar Spoof Engine o Refresh antes del boot impide una promoción no deseada.

<a id="installer"></a>
## Installer

Instala el módulo completo KernelSU/APatch con service, native payload, scripts, policy, metadata e integrity records. Soporta Android 12-17, ARM64 y x86 64; Magisk/recovery se detienen antes de dejar instalación parcial.

Cada payload tiene SHA 256 y la verificación runtime rechaza symlinks, entradas no regulares o inesperadas. Los hashes internos no prueban quién creó el ZIP, por lo que releases oficiales publican `SHA256SUMS` y GitHub signed build provenance.

<a id="keybox-manager"></a>
## Keybox Manager

Carga, verifica, selecciona y monitoriza material autorizado de attestation en formato legacy, XML múltiple y CBOX. Application Rules puede seleccionar un archivo específico; los remote sources siguen siendo untrusted hasta pasar la misma verificación local.

Cada private key debe corresponder al leaf certificate. Se comprueban algoritmo, chain, fechas, duplicados/ambigüedad y revocation. Si la revocación no puede establecerse, material nuevo no se activa; un pool con una entrada rota se rechaza completo.

<a id="native-architecture"></a>
## Native Architecture

Toda lógica native portable se escribe en Rust. No existe first-party C; `binder_interceptor.cpp` es la única excepción C++ por depender del object ABI privado de Android libbinder. Rust core valida Binder layouts/streams, clasifica FDs y realiza copias kernel-validated acotadas.

El injector Rust administra argumentos, ficheros, SELinux socket, FD transfer, maps/symbols, ptrace, registros, memoria remota, loader y cleanup. Las escrituras temporales de stack se restauran con journal acotado. La excepción C++ no debe crecer.

<a id="patch-levels"></a>
## Patch Levels

`security_patch.txt` define reglas System/Vendor/Boot globales y por aplicación. Acepta valores de calendario, `today`, `device_default`, `prop`, `no`; policy v2 ofrece Device, Property, Manual, Automatic y Omit de forma independiente.

Parsing está limitado y una entrada inválida no aplica estado parcial. Automatic usa aritmética de calendario. Esto solo modifica campos soportados de certificados; no instala actualizaciones, no cambia kernel/vendor firmware ni garantiza verdict remoto.

<a id="performance"></a>
## Performance and Memory

Core Keystore interception permanece registrado mientras el servicio esté sano. Al apagar Spoof Engine se aparcan trabajos opcionales de identity/DRM/build/region/telephony mientras certificate y boot protection siguen activos. Automatic Keybox Check tiene control propio.

Binder parser usa arrays fijos y descriptor cache de 64 slots. DRM controller y caches de package/rule/certificate/keybox tienen límites estrictos y evitan busy polling. Rust release usa LTO, optimización de tamaño y linking endurecido.

<a id="profiles"></a>
## Profiles

Los perfiles aplican grupos de ajustes opcionales en una transacción validada; la protección central de boot, Keystore e infraestructura RKP permanece activa de forma independiente.

Daily Compatibility usa alcance dirigido y monitorización de keybox; Default es una configuración conservadora; Maximum Compatibility activa Global Mode, build identity, identity refresh y telephony y desactiva DRM passthrough; Minimal desactiva identity opcional y comprobaciones programadas de keybox. Ninguno de estos presets cambia la protección de infraestructura RKP.

Las configuraciones antiguas pueden conservar el marcador retirado `rkp_passthrough`, pero el runtime ya no basa en él el comportamiento generated-key. Los perfiles version two pueden guardar aplicaciones, template, keybox validado, privacy, patch y opciones identity/DRM; el campo RKP heredado se conserva solo para migración y no es una opción activa del WebUI.

<a id="provider-coexistence"></a>
## Provider Coexistence

Automatic Build Identity detecta otros providers de fingerprint/property, incluyendo variantes PIF, `autopif`/`auto_pif` y PlayCurl, y evita sobrescribirlos.

Con conflicto, las Build properties opcionales quedan intactas mientras otras funciones pueden seguir activas. Force salta la detección de forma intencional; automatic es lo recomendado.

<a id="region-properties"></a>
## Region Properties

Proporciona una vista regional China opcional mediante un conjunto pequeño de properties fijas para hardware/SIM/operator country, hardware level y radio marker. No acepta properties arbitrarias.

Se aplica antes de Zygote con Spoof Engine. No modifica país real de SIM, registro de radio, modem firmware, región segura de venta ni cuenta del operador.

<a id="remote-sources"></a>
## Remote Sources

Obtiene keybox autorizado solo desde endpoint HTTPS explícito. Host, port, path, timeout, refresh, auth/header y response size están limitados; secrets no se muestran en status.

Se puede exigir firma. Ningún dato se activa antes de pasar signature, XML/CBOX, size, keybox, certificate y revocation validation. Un refresh fallido no reemplaza material previamente verificado.

<a id="rkp-protection"></a>
## RKP Protection

La protección Remote Key Provisioning mantiene la infraestructura de provisioning de Android en la ruta genuina de la plataforma. Los paquetes RKP de Android/Google y los Remote Provisioner heredados quedan fuera del alcance de sustitución; los UID del sistema y las resoluciones de paquete desconocidas fallan en modo cerrado.

Los callers de infraestructura RKP nunca se modifican. Para UID de aplicaciones objetivo, `generateKey` y las lecturas posteriores `getKeyEntry` usan una ruta unificada de compatibilidad de certificados, evitando dos hojas de attestation distintas para el mismo alias.

El antiguo switch `rkp_passthrough` está retirado. El marcador puede seguir presente en configs o backups antiguos, pero ya no controla generated-key ni aparece como runtime toggle en WebUI. Los perfiles integrados no cambian el comportamiento RKP: su protección de infraestructura está siempre activa.

CleveresTricky no simula un servidor RKP, no fabrica credenciales de provisioning ni cambia la raíz hardware de provisioning.

<a id="security-model"></a>
## Security Model

Root service, OS, KernelSU/APatch, module files y key material autorizado forman el trust boundary local. Apps, Binder input, uploads, remote responses, config, archives, rules, templates, paths y network metadata se tratan como untrusted.

Config debe ser root-owned, sensible root-only, sin symlinks y con writes atómicos. Binder ABI se valida antes de parsear copies kernel-validated. Injector restringe symbols/process/library y WebUI no abre TCP port, usando un bridge nativo con allowlists y bounds. Un root hostil queda fuera de una defensa total.

<a id="spoof-engine"></a>
## Spoof Engine

Es el control de identidad opcional para apps. Core Keystore/TEE, certificate compatibility, root of trust y boot protection continúan incluso cuando está apagado.

Al activarlo pueden funcionar attestation identity, Telephony, Build Identity, Region e Identity Refresh según sus controles. Al apagarlo los valores guardados no se borran, solo dejan de presentarse. Apps pueden cachear valores y Build Identity requiere reboot.

<a id="telephony-identity"></a>
## Telephony Identity

Puede sustituir IMEI, MEID, IMSI, ICCID y teléfono en APIs Binder compatibles, con valores distintos para dos SIM. Se validan checksums, longitudes, sintaxis, slot y tamaño.

Primero se obtiene la respuesta genuina Android. Denegación de permisos, error o null se conservan, por lo que no se otorga acceso extra. Solo cambia la vista de apps, no modem, baseband, EFS, SIM física ni operador.

<a id="web-interface"></a>
## Web Interface

Ownership runtime fijo: `index.html` markup/CSS base, `bridge.js` native bridge e intents, `policy.js` policy/state y UI propia, `ux.js` presentation/localization/guide/community. No hay CSS runtime separado ni bundles JS por feature.

En móvil hay navegación inferior, controles táctiles, paneles responsive, password visibility, progress y tabs accesibles. WebUI no escucha TCP: usa API nativa del module manager, bridge Rust acotado, queues root-only y validación estricta de path/method/size/time/input.

<a id="changelog"></a>
## CHANGELOG

V2.6.2 refuerza refresh, recovery y publicación de keybox/CBOX con snapshots estables verificados y publicación atómica, reduciendo carreras entre lectores, quarantine y recuperación del backend; también mejora reinicmodern del servidor, invalidación de caché y validación de autenticación. Auto Identity enlaza ahora el security patch de Pixel con la fila correcta del boletín; WebUI maneja mejor aborts, respuestas y exports. Backup/restore es más transaccional, bugreport y lecturas de archivos tienen límites y protección de symlink más estrictos, y se actualizó la compatibilidad Rust X.509/Rust 1.98.

V2.5.3 añadió controles granulares de identity/security patch, profiles y Effective State; endureció attestation, KeyMint/StrongBox, privacidad DRM, upgrades y Android 17; consolidó WebUI, restauró traducciones y mejoró Configuration Management; añadió KeyboxHub con apertura en navegador externo; y reforzó diagnostics, caches, dependency security, regression y validación de artefactos.

<a id="contributing"></a>
## Contributing

Los cambmodern deben mantener el modelo fail closed, Android 12-17 y KernelSU/APatch, sin afirmar integridad hardware no verificable. Deben pasar checks Kotlin/Android/Rust; native portable nuevo va en Rust, C first-party está prohibido y `binder_interceptor.cpp` es la única excepción C++.

Binder/XML/ZIP/CBOX/HTTP/path/PID son untrusted y requieren límites explícitos y regression tests de fallos. No se deben commitear claves privadas, keyboxes, tokens, secretos, APKs o ZIPs generados. Cambmodern visibles requieren actualizar docs.

<a id="donate"></a>
## Development Support

Puedes apoyar el desarrollo usando las opciones oficiales del `DONATE.md`: USDT TRC20, XMR Monero, USDT/USDC ERC20/BEP20, Binance User ID, PayPal, BuyMeACoffee y la web del autor. Verifica siempre direcciones actuales en el archivo inglés canónico antes de enviar fondos.

<a id="languages"></a>
## Language Support

WebUI incluye English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी y العربية. Los catálogos runtime pertenecen únicamente a `ux.js`; no se crean JS/CSS por idioma. La documentación de usuario ofrece README y esta referencia en las mismas nueve lenguas.

Cuando cambia documentación user-facing debe actualizarse el canonical inglés y las secciones localizadas correspondientes. En conflicto técnico manda el inglés y el código.

<a id="logging"></a>
## Logging and Diagnostics

CleveresTricky escribe diagnostics en Android logcat y no guarda un log plaintext separado. Comando principal: `adb logcat -s cleverestricky CleveresTricky`. Los markers de service, bridge, Binder interceptor y TEE ayudan a revisar startup.

`TAMPER DETECTED`, fallo Binder ABI, keybox rechazado o injector timeout requieren atención. Revisa logs antes de publicarlos porque nombres de archivo, packages, properties y PID pueden ser sensibles.

<a id="theme"></a>
## UI Theme

La WebUI usa un diseño monocromo minimalista híbrido Nothing OS/Modern: fondo charcoal, texto gris claro, accent plateado, panel oscuro, success verde y danger rojo. Usa system sans-serif, datos técnicos monospace, Dynamic Island, botones redondeados, toggles Modern y layout mobile-first.

Los touch targets deben rondar al menos 44px, se prioriza el flujo vertical y la experiencia está optimizada para uso desde KernelSU/APatch en teléfono.