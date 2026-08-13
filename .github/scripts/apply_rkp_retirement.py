from pathlib import Path
import re


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} occurrences, found {actual}: {old[:100]!r}")
    p.write_text(text.replace(old, new), encoding="utf-8")


def replace_section(path: str, anchor: str, body: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    marker = f'<a id="{anchor}"></a>'
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"{path}: missing anchor {anchor}")
    next_anchor = text.find('\n<a id="', start + len(marker))
    end = len(text) if next_anchor < 0 else next_anchor + 1
    p.write_text(text[:start] + body.strip() + "\n\n" + text[end:], encoding="utf-8")


index_path = "module/template/webroot/index.html"
replace(
    index_path,
    '<div style="font-size: 0.8em; color: #888; text-transform: uppercase;">RKP Bypass</div>\n    <div id="status_rkp" style="font-weight: bold; color: var(--danger); margin-top: 5px; background: rgba(239, 68, 68, 0.1); padding: 5px; border-radius: 4px;">INACTIVE</div>',
    '<div style="font-size: 0.8em; color: #888; text-transform: uppercase;">RKP Protection</div>\n    <div id="status_rkp" style="font-weight: bold; color: var(--success); margin-top: 5px; background: rgba(74, 222, 128, 0.1); padding: 5px; border-radius: 4px;">ALWAYS ON</div>',
)
replace(
    index_path,
    "const WEB_UI_SETTINGS = ['spoof_enabled', 'spoof_build_identity', 'global_mode', 'auto_keybox_check', 'random_on_boot', 'spoof_region_cn', 'telephony', 'rkp_passthrough', 'drm_passthrough'];",
    "const WEB_UI_SETTINGS = ['spoof_enabled', 'spoof_build_identity', 'global_mode', 'auto_keybox_check', 'random_on_boot', 'spoof_region_cn', 'telephony', 'drm_passthrough'];",
)
replace(
    index_path,
    """        function updateRkpStatus(enabled) {
const status = document.getElementById('status_rkp');
if (!status) return;
status.innerText = enabled ? 'ACTIVE' : 'INACTIVE';
status.style.color = enabled ? 'var(--success)' : 'var(--danger)';
status.style.background = enabled ? 'rgba(74, 222, 128, 0.1)' : 'rgba(239, 68, 68, 0.1)';
        }

""",
    "",
)
replace(index_path, "if (setting === 'rkp_passthrough') updateRkpStatus(Boolean(enabled));\n", "")
replace(
    index_path,
    "const isMaximum = data.spoof_enabled && data.spoof_build_identity && data.global_mode && !data.tee_broken_mode && data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && data.telephony && !data.spoof_region_cn && !data.rkp_passthrough && !data.drm_passthrough;",
    "const isMaximum = data.spoof_enabled && data.spoof_build_identity && data.global_mode && !data.tee_broken_mode && data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && data.telephony && !data.spoof_region_cn && !data.drm_passthrough;",
)
replace(
    index_path,
    "const isDaily = data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;",
    "const isDaily = data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.drm_passthrough;",
)
replace(
    index_path,
    "const isMinimal = !data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && !data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;",
    "const isMinimal = !data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && !data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.drm_passthrough;",
)
replace(
    index_path,
    "const isDefault = data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.rkp_passthrough && data.drm_passthrough;",
    "const isDefault = data.spoof_enabled && !data.spoof_build_identity && !data.global_mode && !data.tee_broken_mode && !data.random_on_boot && !data.hide_sensitive_props && data.auto_keybox_check && !data.telephony && !data.spoof_region_cn && data.drm_passthrough;",
)
replace(
    index_path,
    "    { id: 'rkp_passthrough', name: 'RKP Passthrough', activity: 'Generated-key fast path', scope: 'KeyMint generated-key replies', desc: 'RKP infrastructure UIDs are always protected; this also preserves generated-key responses.' },",
    "    { id: 'rkp_protection', name: 'RKP Protection', status: 'Always on', activity: 'Protected infrastructure + unified key path', scope: 'RKP callers and targeted KeyMint replies', desc: 'RKP infrastructure UIDs always stay on Android. Targeted generateKey and getKeyEntry responses share one certificate-compatibility path to avoid split attestation leaves.' },",
)
replace(
    index_path,
    """    } else {
        statusHtml = '<span style="color:#888;">Info Only</span>';
    }
""",
    """    } else {
        const infoStatus = f.status || 'Info Only';
        const infoColor = f.status === 'Always on' ? 'var(--success)' : '#888';
        statusHtml = '<span style="color:' + infoColor + ';">' + escapeHtml(infoStatus) + '</span>';
    }
""",
)

replace(
    "module/template/webroot/policy.js",
    "  'RKP Passthrough': 'Estimated impact: CPU negligible on protected infrastructure paths; RAM negligible.',",
    "  'RKP Protection': 'Estimated impact: CPU negligible on protected infrastructure paths; RAM negligible.',",
)

# Stop exposing the retired marker as a live WebUI setting. Keep it in the backup
# allowlist so old encrypted backups remain restorable and round-trip compatible.
replace(
    "service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt",
    '                "telephony",\n                "rkp_passthrough",\n                "drm_passthrough",\n',
    '                "telephony",\n                "drm_passthrough",\n',
)

# Keep the legacy marker readable for migration/backups, but make its no-op status
# explicit and stop built-in profiles from creating/removing it.
replace(
    "service/src/main/java/cleveres/tricky/cleverestech/Config.kt",
    """    /**
     * Keeps generated-key responses on Android's original KeyMint/RKP path.
     * Existing-key certificate substitution remains available through
     * [KeystoreInterceptor] for explicitly selected UIDs.
     */
""",
    """    /**
     * Legacy compatibility marker retained for older backups and configurations.
     * RKP infrastructure protection is always active and generated-key handling
     * no longer branches on this value.
     */
""",
)
replace(
    "service/src/main/java/cleveres/tricky/cleverestech/Config.kt",
    'Logger.i("RKP passthrough is ${if (isRkpPassthroughEnabled) "enabled" else "disabled"}")',
    'Logger.i("Legacy RKP passthrough marker is ${if (isRkpPassthroughEnabled) "present" else "absent"}; RKP protection is always active")',
)
replace(
    "service/src/main/java/cleveres/tricky/cleverestech/Config.kt",
    """                    BootLogic.FILE_SPOOF_CN,
                    RKP_PASSTHROUGH_FILE,
                    DRM_PASSTHROUGH_FILE,
""",
    """                    BootLogic.FILE_SPOOF_CN,
                    DRM_PASSTHROUGH_FILE,
""",
)
replace(
    "service/src/main/java/cleveres/tricky/cleverestech/Config.kt",
    "                SecureFile.touch(File(root, RKP_PASSTHROUGH_FILE), 384)\n",
    "",
    count=3,
)

Path("docs/RkpProtection.md").write_text(
    """# RKP Protection

**Language:** **English** | [Türkçe](i18n/tr.md#rkp-protection) | [简体中文](i18n/zh-CN.md#rkp-protection) | [Español](i18n/es.md#rkp-protection) | [Deutsch](i18n/de.md#rkp-protection) | [Русский](i18n/ru.md#rkp-protection) | [Bahasa Indonesia](i18n/id.md#rkp-protection) | [हिन्दी](i18n/hi.md#rkp-protection) | [العربية](i18n/ar.md#rkp-protection)

## Purpose

Remote Key Provisioning protection keeps Android provisioning infrastructure on the genuine platform path and prevents protected RKP callers from entering certificate-substitution scope.

## Protected callers

Android and Google RKP application packages are always outside substitution scope. Current callers include `com.android.rkpdapp` and `com.google.android.rkpdapp`. Legacy Remote Provisioner callers include `com.android.remoteprovisioner` and `com.google.android.remoteprovisioner`.

System Android user identifiers are excluded before package policy. Unknown package resolution also fails closed. Global Mode therefore cannot turn a Package Manager failure into an RKP infrastructure hook.

## Unified generated-key behavior

RKP infrastructure callers always remain untouched. For targeted application UIDs, successful `generateKey` replies and later `getKeyEntry` certificate reads deliberately use the same certificate-compatibility path. Keeping those paths unified prevents one alias from exposing two different attestation leaf certificates.

The old `rkp_passthrough` switch is retired. Older configurations and backups may still contain the marker for compatibility, but it no longer gates generated-key handling and is not exposed as a runtime toggle.

## Profiles

Built-in profiles no longer change RKP behavior. Daily Compatibility, Default, Minimal, and Maximum Compatibility all retain the same always-on RKP infrastructure protection; profile differences apply only to other optional settings such as scope, identity, keybox monitoring, and DRM passthrough.

## Cache behavior

Protected caller decisions use a short bounded cache. Package changes and policy reloads clear relevant state. This avoids repeated Package Manager work while preventing stale decisions from becoming permanent.

## Limits

CleveresTricky does not simulate an RKP server, manufacture provisioning credentials, replace the remote service, or change the hardware provisioning root. The feature protects the genuine Android flow from accidental interception.

If key creation or provisioning behaves differently, restart the affected application and review the service log. The retired RKP marker is not a troubleshooting control.

[Return to the project overview](../README.md)
""",
    encoding="utf-8",
)

Path("docs/Attestation.md").write_text(
    """# Attestation

**Language:** **English** | [Türkçe](i18n/tr.md#attestation) | [简体中文](i18n/zh-CN.md#attestation) | [Español](i18n/es.md#attestation) | [Deutsch](i18n/de.md#attestation) | [Русский](i18n/ru.md#attestation) | [Bahasa Indonesia](i18n/id.md#attestation) | [हिन्दी](i18n/hi.md#attestation) | [العربية](i18n/ar.md#attestation)

## Purpose

The attestation layer provides controlled certificate chain compatibility for selected applications while preserving genuine Android key creation and later cryptographic operations.

## Request handling

The service observes relevant keystore Binder transactions and resolves the real calling Android user identifier. Policy checks then decide whether the caller is targeted, protected, or assigned an application specific configuration.

RKP infrastructure callers always remain on Android's genuine provisioning path. For targeted application UIDs, successful `generateKey` replies and later `getKeyEntry` certificate reads use one certificate-compatibility path so the same alias cannot expose different attestation leaves.

The underlying private key operation is still performed by Android KeyMint or StrongBox when the device and application request that security level. The module does not replace signing, encryption, or key agreement with a software implementation.

## Validation

Before material becomes active, the module checks private key and certificate correspondence, public key algorithm, chain structure, certificate validity, ambiguity, and revocation state. A mixed or invalid pool is rejected rather than partially accepted.

Application rules, keybox selection, patch rules, and identity values are loaded as immutable snapshots. Updates clear the relevant certificate caches so later requests use the new state.

## Limits

Certificate substitution cannot create a hardware root of trust that the device does not possess. It cannot repair firmware, change verified boot measurements, physically relock a bootloader, or guarantee acceptance by a remote service.

Only use key material that you own or are authorized to test. No usable private attestation key is included in the repository or release package.

[Return to the project overview](../README.md)
""",
    encoding="utf-8",
)

Path("docs/Profiles.md").write_text(
    """# Profiles

**Language:** **English** | [Türkçe](i18n/tr.md#profiles) | [简体中文](i18n/zh-CN.md#profiles) | [Español](i18n/es.md#profiles) | [Deutsch](i18n/de.md#profiles) | [Русский](i18n/ru.md#profiles) | [Bahasa Indonesia](i18n/id.md#profiles) | [हिन्दी](i18n/hi.md#profiles) | [العربية](i18n/ar.md#profiles)

## Purpose

Profiles apply a coherent group of optional settings in one transaction. They remain available as advanced presets while core boot, Keystore, and RKP infrastructure protection stay active independently.

## Daily Compatibility

Daily Compatibility uses targeted application scope, keeps optional identity features off, enables keybox monitoring, and preserves the configured DRM passthrough policy. RKP infrastructure protection is always on and is not changed by this profile.

## Default

Default is a conservative optional identity setup. It uses targeted application scope, leaves build identity and telephony off, enables automatic keybox checks, and preserves DRM passthrough. Core Keystore, TEE, and RKP infrastructure protection are unchanged.

## Maximum Compatibility

Maximum Compatibility enables Global Mode together with build identity, identity refresh, and telephony handling. It disables DRM passthrough so the widest configured compatibility scope can be tested. RKP infrastructure callers remain protected on the genuine Android path.

This profile changes the most optional behavior and should be used for focused testing. It does not alter hardware trust state or guarantee a remote verdict.

## Minimal

Minimal disables optional identity, build identity, telephony, and scheduled keybox checks while preserving the genuine DRM passthrough path. It does not disable the core Keystore interceptor, certificate compatibility, TEE handling, boot property protection, or RKP infrastructure protection.

Older configurations can still contain the legacy certificate safe mode flag and the retired `rkp_passthrough` marker. Core targeting and generated-key handling no longer depend on either RKP marker state.

## Applying a profile

The service accepts a bounded validated profile request, updates protected configuration flags, reloads policy, and removes the request. Unknown names are rejected.

Profile application does not replace keyboxes, application lists, templates, or user backups. Reboot after a profile changes early boot identity behavior.

[Return to the project overview](../README.md)

## Profiles version two

Built in presets remain available. User defined named profiles can store application assignments, an identity template reference, a validated keybox reference, privacy mode, independent System, Vendor, and Boot patch policies, optional identity feature overrides, and compatible DRM choices. Private keybox contents are never copied into a profile.

Legacy policy snapshots may still contain an RKP passthrough field for migration compatibility, but the runtime ignores that field and the WebUI does not expose it as a live profile choice.

Profile creation, edit, rename, duplicate, delete, assignment, import, export, and activation pass through the same validated policy state. Activation publishes one immutable snapshot only after complete validation. Invalid input does not replace the current snapshot. Exact assignment conflicts are rejected and shared UID resolution is deterministic.

The previous valid policy snapshot is retained as last known good state. A malformed replacement is never partially applied. Existing preset requests and legacy configuration remain supported when no version two state is present.
""",
    encoding="utf-8",
)

localized = {
    "docs/i18n/tr.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

Attestation katmanı, seçili uygulamalara kontrollü sertifika zinciri uyumluluğu sağlarken gerçek Android anahtar oluşturma ve sonraki kriptografik işlemleri korur.

RKP altyapı çağıranları her zaman Android'in gerçek provisioning yolunda kalır. Hedeflenen uygulama UID'lerinde başarılı `generateKey` yanıtları ile sonraki `getKeyEntry` sertifika okumaları aynı sertifika uyumluluk yolunu kullanır; böylece aynı alias iki farklı attestation leaf göstermez.

Private key işlemi istenen güvenlik seviyesinde Android KeyMint veya StrongBox tarafından yapılmaya devam eder. Etkinleştirmeden önce key/certificate eşleşmesi, algoritma, chain yapısı, geçerlilik, ambiguity ve revocation doğrulanır. Sertifika değiştirme fiziksel hardware root of trust oluşturmaz, bootloader'ı kilitlemez veya remote verdict garanti etmez.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Profiller optional ayar gruplarını tek validated işlemle uygular; core boot, Keystore ve RKP altyapı koruması bunlardan bağımsız olarak aktif kalır.

Daily Compatibility targeted scope ve keybox monitoring kullanır; Default muhafazakâr optional identity düzenidir; Maximum Compatibility Global Mode, build identity, identity refresh ve telephony yollarını açıp DRM passthrough'u kapatır; Minimal optional identity ve scheduled keybox kontrollerini kapatır. Bu profillerin hiçbiri RKP altyapı korumasını değiştirmez.

Eski yapılandırmalar retired `rkp_passthrough` işaretini taşıyabilir; runtime generated-key davranışı artık bu değere bağlı değildir. Version two profilleri app assignment, template, doğrulanmış keybox, privacy, patch ve optional identity/DRM seçimlerini saklayabilir; legacy RKP alanı yalnız migration uyumluluğu için korunabilir ve WebUI'da canlı seçenek değildir.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning koruması Android provisioning altyapısını gerçek platform yolunda tutar. Android/Google RKP ve eski Remote Provisioner paketleri substitution scope dışında kalır; sistem UID'leri ve çözülemeyen package durumları da fail closed davranır.

RKP altyapı caller'ları hiçbir zaman değiştirilmez. Hedeflenen uygulama UID'lerinde `generateKey` ve sonraki `getKeyEntry` sertifika yanıtları aynı compatibility yolunu kullanır; bu, tek alias'ın iki farklı attestation leaf göstermesini önler.

Eski `rkp_passthrough` switch'i retired durumdadır. Eski config/backup içinde işaret bulunabilir ancak generated-key davranışını artık yönetmez ve WebUI runtime toggle olarak sunmaz. Built-in profiller RKP davranışını değiştirmez; RKP altyapı koruması her zaman aktiftir.

CleveresTricky bir RKP sunucusu simüle etmez, provisioning credential üretmez veya hardware provisioning root'u değiştirmez.''',
    },
    "docs/i18n/zh-CN.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

认证层为选定应用提供受控的证书链兼容，同时保留 Android 真实的密钥创建和后续密码学操作。

RKP 基础设施调用者始终保持在 Android 原生的配置路径上。对于被选中的应用 UID，成功的 `generateKey` 响应与后续 `getKeyEntry` 证书读取使用同一条证书兼容路径，避免同一 alias 暴露不同的认证叶证书。

私钥操作仍由 Android KeyMint 或 StrongBox 在请求的安全级别中完成。材料启用前会验证密钥/证书匹配、算法、链结构、有效期、歧义和吊销状态。证书替换不能创建硬件信任根、重新锁定 bootloader 或保证远端判定。''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Profiles 以一次经过验证的事务应用一组可选设置；核心 boot、Keystore 与 RKP 基础设施保护始终独立保持启用。

Daily Compatibility 使用定向范围和 keybox 监控；Default 是保守的可选身份配置；Maximum Compatibility 启用 Global Mode、build identity、identity refresh 与 telephony，并关闭 DRM passthrough；Minimal 关闭可选身份和计划 keybox 检查。这些预设都不会改变 RKP 基础设施保护。

旧配置可能仍包含已退役的 `rkp_passthrough` 标记，但运行时的 generated-key 行为不再依赖它。Version two profile 可保存应用分配、template、已验证 keybox、privacy、patch 以及可选 identity/DRM 设置；旧 RKP 字段仅用于迁移兼容，不再作为 WebUI 的实时选项。''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning 保护会让 Android provisioning 基础设施保持在真实平台路径上。Android/Google RKP 与旧 Remote Provisioner 包始终排除在证书替换范围之外；系统 UID 与无法解析包名的情况也会 fail closed。

RKP 基础设施调用者从不被修改。对于目标应用 UID，`generateKey` 与后续 `getKeyEntry` 证书响应使用统一的兼容路径，从而避免同一 alias 出现两个不同的 attestation leaf。

旧的 `rkp_passthrough` 开关已经退役。旧配置或备份中可以继续存在该标记，但它不再控制 generated-key 行为，也不会作为 WebUI runtime toggle 暴露。内置 Profiles 不再改变 RKP 行为，RKP 基础设施保护始终开启。

CleveresTricky 不模拟 RKP 服务器、不生成 provisioning credential，也不改变硬件 provisioning root。''',
    },
    "docs/i18n/es.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

La capa de attestation ofrece compatibilidad controlada de cadenas de certificados para las aplicaciones seleccionadas, manteniendo la creación real de claves de Android y las operaciones criptográficas posteriores.

Los callers de infraestructura RKP permanecen siempre en la ruta genuina de provisioning de Android. Para los UID de aplicaciones objetivo, las respuestas correctas de `generateKey` y las lecturas posteriores de certificados mediante `getKeyEntry` comparten una sola ruta de compatibilidad, evitando que un mismo alias muestre hojas de attestation distintas.

La operación de clave privada sigue realizándose en Android KeyMint o StrongBox. Antes de activar material se validan correspondencia clave/certificado, algoritmo, cadena, vigencia, ambigüedad y revocación. La sustitución de certificados no crea una raíz de confianza hardware, no bloquea físicamente el bootloader ni garantiza un veredicto remoto.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Los perfiles aplican grupos de ajustes opcionales en una transacción validada; la protección central de boot, Keystore e infraestructura RKP permanece activa de forma independiente.

Daily Compatibility usa alcance dirigido y monitorización de keybox; Default es una configuración conservadora; Maximum Compatibility activa Global Mode, build identity, identity refresh y telephony y desactiva DRM passthrough; Minimal desactiva identity opcional y comprobaciones programadas de keybox. Ninguno de estos presets cambia la protección de infraestructura RKP.

Las configuraciones antiguas pueden conservar el marcador retirado `rkp_passthrough`, pero el runtime ya no basa en él el comportamiento generated-key. Los perfiles version two pueden guardar aplicaciones, template, keybox validado, privacy, patch y opciones identity/DRM; el campo RKP heredado se conserva solo para migración y no es una opción activa del WebUI.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

La protección Remote Key Provisioning mantiene la infraestructura de provisioning de Android en la ruta genuina de la plataforma. Los paquetes RKP de Android/Google y los Remote Provisioner heredados quedan fuera del alcance de sustitución; los UID del sistema y las resoluciones de paquete desconocidas fallan en modo cerrado.

Los callers de infraestructura RKP nunca se modifican. Para UID de aplicaciones objetivo, `generateKey` y las lecturas posteriores `getKeyEntry` usan una ruta unificada de compatibilidad de certificados, evitando dos hojas de attestation distintas para el mismo alias.

El antiguo switch `rkp_passthrough` está retirado. El marcador puede seguir presente en configs o backups antiguos, pero ya no controla generated-key ni aparece como runtime toggle en WebUI. Los perfiles integrados no cambian el comportamiento RKP: su protección de infraestructura está siempre activa.

CleveresTricky no simula un servidor RKP, no fabrica credenciales de provisioning ni cambia la raíz hardware de provisioning.''',
    },
    "docs/i18n/de.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

Die Attestation-Schicht bietet ausgewählten Apps kontrollierte Zertifikatsketten-Kompatibilität, während echte Android-Schlüsselerzeugung und spätere kryptografische Operationen erhalten bleiben.

RKP-Infrastruktur-Caller bleiben immer auf Androids echtem Provisioning-Pfad. Für ausgewählte App-UIDs verwenden erfolgreiche `generateKey`-Antworten und spätere `getKeyEntry`-Zertifikatlesungen denselben Kompatibilitätspfad, damit ein Alias nicht unterschiedliche Attestation-Leaf-Zertifikate zeigt.

Private-Key-Operationen werden weiterhin von Android KeyMint oder StrongBox ausgeführt. Vor Aktivierung werden Schlüssel/Zertifikat-Zuordnung, Algorithmus, Chain, Gültigkeit, Mehrdeutigkeit und Revocation geprüft. Zertifikatsersetzung erzeugt keinen Hardware-Root-of-Trust, sperrt keinen Bootloader physisch und garantiert kein Remote-Verdict.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Profiles wenden optionale Einstellungen in einer validierten Transaktion an; Core-Boot-, Keystore- und RKP-Infrastrukturschutz bleiben unabhängig aktiv.

Daily Compatibility nutzt gezielten Scope und Keybox-Monitoring; Default ist konservativ; Maximum Compatibility aktiviert Global Mode, Build Identity, Identity Refresh und Telephony und deaktiviert DRM Passthrough; Minimal deaktiviert optionale Identity- und geplante Keybox-Arbeit. Keines dieser Presets ändert den RKP-Infrastrukturschutz.

Alte Konfigurationen können den stillgelegten Marker `rkp_passthrough` enthalten, aber Generated-Key-Verhalten hängt nicht mehr davon ab. Version-two-Profile können App-Zuordnung, Template, validierte Keybox, Privacy, Patch und optionale Identity/DRM-Wahlen speichern; das alte RKP-Feld bleibt nur für Migration kompatibel und ist keine Live-WebUI-Option.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Remote-Key-Provisioning-Schutz hält Androids Provisioning-Infrastruktur auf dem echten Plattformpfad. Android/Google-RKP- und alte Remote-Provisioner-Pakete liegen immer außerhalb der Zertifikatsersetzung; System-UIDs und unbekannte Paketauflösung verhalten sich fail closed.

RKP-Infrastruktur-Caller werden nie verändert. Für Ziel-App-UIDs verwenden `generateKey` und spätere `getKeyEntry`-Zertifikatantworten einen einheitlichen Kompatibilitätspfad, damit ein Alias nicht zwei verschiedene Attestation-Leafs zeigt.

Der alte Schalter `rkp_passthrough` ist stillgelegt. Der Marker darf in alten Konfigurationen oder Backups verbleiben, steuert aber Generated-Key-Verhalten nicht mehr und wird nicht als WebUI-Runtime-Toggle angeboten. Eingebaute Profiles ändern RKP nicht; der Infrastrukturschutz ist immer aktiv.

CleveresTricky simuliert keinen RKP-Server, erzeugt keine Provisioning-Credentials und ändert keinen Hardware-Provisioning-Root.''',
    },
    "docs/i18n/ru.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

Слой attestation обеспечивает управляемую совместимость цепочек сертификатов для выбранных приложений, сохраняя настоящую генерацию ключей Android и последующие криптографические операции.

Вызовы инфраструктуры RKP всегда остаются на штатном пути provisioning Android. Для целевых UID приложений успешные ответы `generateKey` и последующее чтение сертификатов через `getKeyEntry` используют единый путь совместимости, чтобы один alias не показывал разные attestation leaf-сертификаты.

Операции с закрытым ключом по-прежнему выполняются Android KeyMint или StrongBox. Перед активацией проверяются соответствие ключа и сертификата, алгоритм, цепочка, срок действия, неоднозначность и revocation. Подмена сертификата не создаёт аппаратный root of trust, не блокирует bootloader физически и не гарантирует удалённый verdict.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Профили применяют наборы необязательных настроек одной проверенной транзакцией; базовая защита boot, Keystore и инфраструктуры RKP остаётся активной независимо.

Daily Compatibility использует targeted scope и мониторинг keybox; Default — консервативный режим; Maximum Compatibility включает Global Mode, build identity, identity refresh и telephony и выключает DRM passthrough; Minimal отключает необязательную identity-логику и плановые проверки keybox. Ни один preset не меняет защиту инфраструктуры RKP.

Старые конфигурации могут содержать выведенный из эксплуатации маркер `rkp_passthrough`, но generated-key поведение больше от него не зависит. Профили version two могут хранить назначения приложений, template, проверенный keybox, privacy, patch и параметры identity/DRM; старое поле RKP сохраняется только для миграционной совместимости и не является активной настройкой WebUI.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Защита Remote Key Provisioning оставляет инфраструктуру provisioning Android на штатном платформенном пути. Пакеты RKP Android/Google и legacy Remote Provisioner всегда исключены из области подмены; системные UID и неизвестное разрешение пакета работают fail closed.

Вызовы инфраструктуры RKP никогда не изменяются. Для целевых UID приложений `generateKey` и последующие ответы `getKeyEntry` используют единый путь совместимости сертификатов, что не позволяет одному alias показывать два разных attestation leaf.

Старый переключатель `rkp_passthrough` выведен из эксплуатации. Маркер может оставаться в старых конфигурациях и backup, но больше не управляет generated-key и не показывается как runtime toggle WebUI. Встроенные Profiles не меняют RKP: защита инфраструктуры всегда включена.

CleveresTricky не эмулирует RKP-сервер, не создаёт provisioning credentials и не меняет аппаратный provisioning root.''',
    },
    "docs/i18n/id.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

Lapisan attestation memberikan kompatibilitas rantai sertifikat terkontrol untuk aplikasi terpilih sambil mempertahankan pembuatan kunci Android asli dan operasi kriptografi berikutnya.

Caller infrastruktur RKP selalu tetap di jalur provisioning Android asli. Untuk UID aplikasi target, respons `generateKey` yang berhasil dan pembacaan sertifikat `getKeyEntry` berikutnya memakai satu jalur kompatibilitas agar satu alias tidak menampilkan attestation leaf yang berbeda.

Operasi private key tetap dilakukan Android KeyMint atau StrongBox. Sebelum material aktif, kecocokan key/certificate, algoritma, chain, masa berlaku, ambiguity, dan revocation diperiksa. Substitusi sertifikat tidak menciptakan hardware root of trust, mengunci bootloader secara fisik, atau menjamin remote verdict.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Profiles menerapkan kelompok pengaturan opsional dalam satu transaksi tervalidasi; perlindungan inti boot, Keystore, dan infrastruktur RKP tetap aktif secara independen.

Daily Compatibility memakai targeted scope dan keybox monitoring; Default adalah konfigurasi konservatif; Maximum Compatibility mengaktifkan Global Mode, build identity, identity refresh, dan telephony lalu mematikan DRM passthrough; Minimal mematikan identity opsional dan pemeriksaan keybox terjadwal. Tidak satu pun preset mengubah perlindungan infrastruktur RKP.

Konfigurasi lama dapat tetap memiliki marker `rkp_passthrough` yang sudah retired, tetapi perilaku generated-key tidak lagi bergantung padanya. Profile version two dapat menyimpan assignment aplikasi, template, keybox tervalidasi, privacy, patch, serta pilihan identity/DRM; field RKP lama hanya dipertahankan untuk kompatibilitas migrasi dan bukan opsi WebUI aktif.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Perlindungan Remote Key Provisioning menjaga infrastruktur provisioning Android pada jalur platform asli. Paket RKP Android/Google dan Remote Provisioner legacy selalu di luar scope substitusi; UID sistem dan resolusi package yang tidak diketahui juga fail closed.

Caller infrastruktur RKP tidak pernah dimodifikasi. Untuk UID aplikasi target, `generateKey` dan respons sertifikat `getKeyEntry` berikutnya memakai jalur kompatibilitas terpadu sehingga satu alias tidak menghasilkan dua attestation leaf berbeda.

Switch lama `rkp_passthrough` sudah retired. Marker dapat tetap ada di config atau backup lama, tetapi tidak lagi mengontrol generated-key dan tidak diekspos sebagai runtime toggle WebUI. Built-in Profiles tidak mengubah perilaku RKP; perlindungan infrastrukturnya selalu aktif.

CleveresTricky tidak mensimulasikan server RKP, membuat provisioning credential, atau mengubah hardware provisioning root.''',
    },
    "docs/i18n/hi.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

Attestation layer चुने हुए apps के लिए नियंत्रित certificate-chain compatibility देता है, जबकि Android की वास्तविक key creation और बाद की cryptographic operations बनी रहती हैं।

RKP infrastructure callers हमेशा Android के genuine provisioning path पर रहते हैं। Target app UID के लिए सफल `generateKey` replies और बाद की `getKeyEntry` certificate reads एक ही compatibility path का उपयोग करती हैं, ताकि एक alias अलग-अलग attestation leaf न दिखाए।

Private-key operation Android KeyMint या StrongBox ही करता है। Material active होने से पहले key/certificate match, algorithm, chain, validity, ambiguity और revocation जाँचे जाते हैं। Certificate substitution hardware root of trust नहीं बनाता, bootloader को physically lock नहीं करता और remote verdict की guarantee नहीं देता।''',
        "profiles": '''<a id="profiles"></a>
## Profiles

Profiles optional settings के समूह को एक validated transaction में लागू करते हैं; core boot, Keystore और RKP infrastructure protection स्वतंत्र रूप से active रहती है।

Daily Compatibility targeted scope और keybox monitoring उपयोग करता है; Default conservative setup है; Maximum Compatibility Global Mode, build identity, identity refresh और telephony चालू करके DRM passthrough बंद करता है; Minimal optional identity और scheduled keybox checks बंद करता है। इनमें से कोई preset RKP infrastructure protection नहीं बदलता।

पुरानी configuration में retired `rkp_passthrough` marker रह सकता है, लेकिन generated-key behavior अब उस पर निर्भर नहीं है। Version two profiles app assignment, template, validated keybox, privacy, patch और optional identity/DRM choices रख सकते हैं; legacy RKP field केवल migration compatibility के लिए है और live WebUI option नहीं है।''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

Remote Key Provisioning protection Android provisioning infrastructure को genuine platform path पर रखती है। Android/Google RKP और legacy Remote Provisioner packages substitution scope से बाहर रहते हैं; system UID और unknown package resolution fail closed रहते हैं।

RKP infrastructure callers कभी modify नहीं किए जाते। Target app UID के लिए `generateKey` और बाद के `getKeyEntry` certificate responses unified compatibility path का उपयोग करते हैं, जिससे एक alias दो अलग attestation leaf नहीं दिखाता।

पुराना `rkp_passthrough` switch retired है। Marker पुराने config/backup में रह सकता है, लेकिन अब generated-key behavior को control नहीं करता और WebUI runtime toggle के रूप में expose नहीं होता। Built-in Profiles RKP behavior नहीं बदलते; infrastructure protection हमेशा active है।

CleveresTricky RKP server simulate नहीं करता, provisioning credentials नहीं बनाता और hardware provisioning root नहीं बदलता।''',
    },
    "docs/i18n/ar.md": {
        "attestation": '''<a id="attestation"></a>
## Attestation

توفر طبقة attestation توافقاً مضبوطاً لسلاسل الشهادات للتطبيقات المحددة مع إبقاء إنشاء المفاتيح الحقيقي في Android والعمليات التشفيرية اللاحقة كما هي.

تبقى نداءات بنية RKP دائماً على مسار provisioning الحقيقي في Android. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` الناجحة وقراءات الشهادة اللاحقة عبر `getKeyEntry` مسار توافق واحداً كي لا يعرض alias واحد شهادتي attestation leaf مختلفتين.

تظل عملية المفتاح الخاص من تنفيذ Android KeyMint أو StrongBox. وقبل تفعيل المادة يتم التحقق من تطابق المفتاح والشهادة والخوارزمية والسلسلة والصلاحية والالتباس وحالة revocation. استبدال الشهادة لا ينشئ hardware root of trust ولا يقفل bootloader فعلياً ولا يضمن remote verdict.''',
        "profiles": '''<a id="profiles"></a>
## Profiles

تطبق Profiles مجموعة من الإعدادات الاختيارية في معاملة واحدة متحقق منها؛ وتظل حماية boot وKeystore وبنية RKP الأساسية فعالة بشكل مستقل.

يستخدم Daily Compatibility نطاقاً مستهدفاً ومراقبة keybox؛ وDefault إعداد محافظ؛ ويشغل Maximum Compatibility ‏Global Mode وbuild identity وidentity refresh وtelephony مع تعطيل DRM passthrough؛ بينما يعطل Minimal الهوية الاختيارية وفحوص keybox المجدولة. لا يغير أي preset حماية بنية RKP.

قد تبقى علامة `rkp_passthrough` المتقاعدة في الإعدادات القديمة، لكن سلوك generated-key لم يعد يعتمد عليها. تستطيع Profiles version two حفظ تعيينات التطبيقات وtemplate وkeybox متحقق منه وprivacy وpatch وخيارات identity/DRM؛ أما حقل RKP القديم فيبقى فقط لتوافق الترحيل وليس خياراً حياً في WebUI.''',
        "rkp-protection": '''<a id="rkp-protection"></a>
## RKP Protection

تحافظ حماية Remote Key Provisioning على بنية provisioning في Android ضمن المسار الحقيقي للمنصة. تبقى حزم RKP الخاصة بـ Android/Google وحزم Remote Provisioner القديمة خارج نطاق الاستبدال، كما تفشل UID النظام وحالات تعذر حل الحزمة بوضع fail closed.

لا يتم تعديل نداءات بنية RKP أبداً. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` وقراءات `getKeyEntry` اللاحقة مسار توافق شهادات موحداً لمنع alias واحد من إظهار attestation leaf مختلفتين.

تم تقاعد المفتاح القديم `rkp_passthrough`. يمكن أن تبقى العلامة في config أو backup قديم، لكنها لا تتحكم بعد الآن في generated-key ولا تظهر كـ runtime toggle في WebUI. لا تغير Profiles المدمجة سلوك RKP؛ فحماية البنية فعالة دائماً.

لا يحاكي CleveresTricky خادم RKP ولا ينشئ provisioning credentials ولا يغير hardware provisioning root.''',
    },
}

for path, sections in localized.items():
    for anchor, body in sections.items():
        replace_section(path, anchor, body)

Path("module/webui-tests/rkp-retirement.test.js").write_text(
    r'''const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../template/webroot/index.html'), 'utf8');

test('retired RKP passthrough is presented as always-on protection', () => {
  assert.match(html, /RKP Protection/);
  assert.match(html, /ALWAYS ON/);
  assert.doesNotMatch(html, /RKP Bypass/);

  const settings = html.match(/const WEB_UI_SETTINGS = \[([^\]]+)\]/)?.[1] || '';
  assert.doesNotMatch(settings, /rkp_passthrough/);
  assert.doesNotMatch(html, /updateRkpStatus/);
  assert.doesNotMatch(html, /data\.rkp_passthrough/);
  assert.match(html, /id: 'rkp_protection'.*status: 'Always on'/s);
});
''',
    encoding="utf-8",
)

# Final invariants.
index = Path(index_path).read_text(encoding="utf-8")
settings = re.search(r"const WEB_UI_SETTINGS = \[([^\]]+)\]", index).group(1)
assert "rkp_passthrough" not in settings
assert "data.rkp_passthrough" not in index
assert "updateRkpStatus" not in index
assert "RKP Bypass" not in index
assert "RKP Protection" in index and "ALWAYS ON" in index

server = Path("service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt").read_text(encoding="utf-8")
ui_settings = server.split("private val WEB_UI_SETTINGS =", 1)[1].split("private val EDITABLE_CONFIG_FILES", 1)[0]
backups = server.split("private val BACKUP_CONFIG_FILES =", 1)[1].split("private val APP_RULE_FIELDS", 1)[0]
assert "rkp_passthrough" not in ui_settings
assert "rkp_passthrough" in backups

config = Path("service/src/main/java/cleveres/tricky/cleverestech/Config.kt").read_text(encoding="utf-8")
assert config.count("SecureFile.touch(File(root, RKP_PASSTHROUGH_FILE), 384)") == 0
assert "Legacy RKP passthrough marker is" in config
assert "generated-key handling" in config
