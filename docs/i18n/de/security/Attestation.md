# Attestation

**Sprache:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | **Deutsch** | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

Die Attestation-Schicht bietet ausgewählten Apps kontrollierte Zertifikatsketten-Kompatibilität, während echte Android-Schlüsselerzeugung und spätere kryptografische Operationen erhalten bleiben.

RKP-Infrastruktur-Caller bleiben immer auf Androids echtem Provisioning-Pfad. Für ausgewählte App-UIDs verwenden erfolgreiche `generateKey`-Antworten und spätere `getKeyEntry`-Zertifikatlesungen denselben Kompatibilitätspfad, damit ein Alias nicht unterschiedliche Attestation-Leaf-Zertifikate zeigt.

Private-Key-Operationen werden weiterhin von Android KeyMint oder StrongBox ausgeführt. Vor Aktivierung werden Schlüssel/Zertifikat-Zuordnung, Algorithmus, Chain, Gültigkeit, Mehrdeutigkeit und Revocation geprüft. Zertifikatsersetzung erzeugt keinen Hardware-Root-of-Trust, sperrt keinen Bootloader physisch und garantiert kein Remote-Verdict.

## Hardware-TEE-Bereitstellung und OnePlus-Wiederherstellungshinweis

CleveresTricky erfordert ein funktionierendes Hardware-KeyMint/TEE und simuliert absichtlich kein gefälschtes Software-TEE oder gefälschtes Attestation. Wenn die Hardware-TEE-Kommunikation fehlschlägt (`SECURE_HW_COMMUNICATION_FAILED` / Fehlercode 10), unterbricht das Modul die Interzeption, um Deadlocks im Framework zu verhindern.

Auf bestimmten entsperrten Geräten kann das Entsperren des Bootloaders die geräteseitige Hardware-Attestation oder RKP-Bereitstellung beschädigen:
- **OnePlus 13 / 15 Entsperrte Geräte-Attestation / TEE-RKP-Wiederherstellung**: Wie in der [Untersuchung von wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) dokumentiert, unterbricht das Entsperren des Bootloaders auf getesteten OnePlus 13- und 15-Geräten die TEE-Attestation und RKP-Bereitstellung.
- **Geräteseitige Bereitstellungsalternative**: Der Artikel berichtet, dass eine `KmInstallKeybox`-Geräte-ID-Bereitstellung echtes TEE-RKP (und auf getesteten Modellen Widevine L1 RKP) wiederherstellen kann, ohne durchgesickerte Attestation-Schlüssel zu injizieren. Dies ist eine legitime geräteseitige TEE-Bereitstellungs-/Wiederherstellungsalternative, keine Keystore-Täuschung oder Emulation.
- **Umfang und Einschränkungen**:
  - Dies stellt den Offline-TEE-Attestation-Schlüssel **nicht** universell wieder her.
  - Es ist streng **geräte- und firmware-spezifisch**. Es sollte nicht pauschal empfohlen werden und gilt nicht für alle OnePlus-Modelle.
  - **Risikowarnung**: Änderungen an geräteseitigem `persist` oder der TEE-Bereitstellung bergen erhebliche Risiken für dauerhafte Beschädigungen des Keystore oder einen Geräte-Brick. Erstellen Sie vor jedem TEE-Wiederherstellungsversuch immer ein vollständiges Backup Ihrer `persist`- und Firmware-Partitionen.
