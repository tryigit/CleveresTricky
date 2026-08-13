# Attestation

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
