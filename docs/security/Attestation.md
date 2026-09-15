# Attestation

**Language:** **English** | [Türkçe](../i18n/tr/security/Attestation.md) | [简体中文](../i18n/zh-CN/security/Attestation.md) | [Español](../i18n/es/security/Attestation.md) | [Deutsch](../i18n/de/security/Attestation.md) | [Русский](../i18n/ru/security/Attestation.md) | [Bahasa Indonesia](../i18n/id/security/Attestation.md) | [हिन्दी](../i18n/hi/security/Attestation.md) | [العربية](../i18n/ar/security/Attestation.md)

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

## Hardware TEE Provisioning & Unlocked-Device Recovery Note

CleveresTricky relies on genuine platform KeyMint/TEE operations and intentionally does not simulate or emulate a fake software TEE or fake attestation. When hardware TEE communication fails (e.g. `SECURE_HW_COMMUNICATION_FAILED` / error code -49), the module halts interception to prevent framework deadlock.

On unlocked Android devices, bootloader unlocking can invalidate or break device-side hardware attestation or Remote Key Provisioning (RKP):
- **Device-Side TEE RKP Recovery**: As demonstrated in [wuxianlin's investigation](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) (evaluated on modern devices including OnePlus 13/15), `KmInstallKeybox` device-ID provisioning can restore genuine TEE RKP (and Widevine L1 RKP) without injecting leaked attestation keys. This serves as a legitimate device-side TEE provisioning recovery mechanism across unlocked Android devices, rather than a keystore spoof or software emulation.
- **Scope & Recommendations**:
  - Device-side `KmInstallKeybox` provisioning restores genuine TEE RKP certificate issuance from remote provisioning infrastructure.
  - It does **not** universally restore the legacy offline TEE Attestation Key.
  - **Risk Warning**: Modifying device-side `persist` or hardware TEE provisioning carries risk of permanent keybox corruption or device bricking. Always make a complete backup of your device `persist` and firmware partitions before attempting any TEE provisioning recovery.

[Return to the project overview](../README.md)

