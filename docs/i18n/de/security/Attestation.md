# Attestation

**Sprache:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | **Deutsch** | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Die Attestation-Schicht bietet ausgewählten Apps kontrollierte Zertifikatsketten-Kompatibilität, während echte Android-Schlüsselerzeugung und spätere kryptografische Operationen erhalten bleiben.

RKP-Infrastruktur-Caller bleiben immer auf Androids echtem Provisioning-Pfad. Für ausgewählte App-UIDs verwenden erfolgreiche `generateKey`-Antworten und spätere `getKeyEntry`-Zertifikatlesungen denselben Kompatibilitätspfad, damit ein Alias nicht unterschiedliche Attestation-Leaf-Zertifikate zeigt.

Private-Key-Operationen werden weiterhin von Android KeyMint oder StrongBox ausgeführt. Vor Aktivierung werden Schlüssel/Zertifikat-Zuordnung, Algorithmus, Chain, Gültigkeit, Mehrdeutigkeit und Revocation geprüft. Zertifikatsersetzung erzeugt keinen Hardware-Root-of-Trust, sperrt keinen Bootloader physisch und garantiert kein Remote-Verdict.

## Hardware-TEE-Bereitstellung und Wiederherstellungshinweis für entsperrte Geräte

CleveresTricky erfordert ein funktionierendes Hardware-KeyMint/TEE und simuliert absichtlich kein gefälschtes Software-TEE oder gefälschtes Attestation. Wenn die Hardware-TEE-Kommunikation fehlschlägt (`SECURE_HW_COMMUNICATION_FAILED` / Fehlercode -49), unterbricht das Modul die Interzeption, um Deadlocks im Framework zu verhindern.

Auf entsperrten Android-Geräten kann das Entsperren des Bootloaders die geräteseitige Hardware-Attestation oder Remote Key Provisioning (RKP) beeinträchtigen:
- **Geräteseitige TEE-RKP-Wiederherstellung**: Wie in der [Untersuchung von wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) gezeigt (evaluiert auf modernen Geräten einschließlich OnePlus 13/15), kann eine `KmInstallKeybox`-Geräte-ID-Bereitstellung echtes TEE-RKP (und Widevine L1 RKP) wiederherstellen, ohne durchgesickerte Schlüssel zu injizieren. Dies dient als legitimer geräteseitiger Mechanismus auf entsperrten Android-Geräten anstelle von Keystore-Täuschung oder Emulation.
- **Umfang und Empfehlungen**:
  - Die `KmInstallKeybox`-Bereitstellung stellt die Ausstellung echter TEE-RKP-Zertifikate von Remote-Infrastrukturen wieder her.
  - Sie stellt den klassischen Offline-TEE-Attestation-Schlüssel nicht universell wieder her.
  - **Risikowarnung**: Änderungen an geräteseitigem `persist` oder der Hardware-TEE-Bereitstellung bergen erhebliche Risiken. Erstellen Sie vor jedem TEE-Wiederherstellungsversuch immer ein vollständiges Backup Ihrer `persist`- und Firmware-Partitionen.
