# DRM Keystore Passthrough and Identifier Privacy

**Language:** **English** | [Türkçe](i18n/tr.md#drm-passthrough) | [简体中文](i18n/zh-CN.md#drm-passthrough) | [Español](i18n/es.md#drm-passthrough) | [Deutsch](i18n/de.md#drm-passthrough) | [Русский](i18n/ru.md#drm-passthrough) | [Bahasa Indonesia](i18n/id.md#drm-passthrough) | [हिन्दी](i18n/hi.md#drm-passthrough) | [العربية](i18n/ar.md#drm-passthrough)

## Purpose

CleveresTricky has two deliberately separate DRM related behaviors.

DRM Keystore Passthrough keeps selected media applications on Android's genuine Keystore certificate path. It prevents CleveresTricky attestation compatibility handling from leaking into applications where modified certificate chains can interfere with protected playback.

DRM Identifier Privacy is narrower and identity focused. On the supported modern stable AIDL DRM HAL path, an application configured with `privacy=isolate` receives a stable application scoped pseudonym when it reads the byte array property `deviceUniqueId`. The genuine DRM device identifier is not used as input to that pseudonym, is not logged, and is not stored in a CleveresTricky DRM ID cache.

## Why the privacy hook exists

Android defines `deviceUniqueId` as a DRM byte array property established during provisioning that can uniquely identify a device. A media or streaming application can therefore treat that value as another durable device identifier. The privacy hook prevents this specific supported property from exposing the genuine value to an isolated application while keeping the rest of the DRM transaction on the original platform path.

The pseudonym is derived from the same protected random privacy seed used by Application Rules. It is stable for the same isolated application identity, differs across unrelated application identities, and preserves the original DRM identifier length within a bounded supported range. Stable pseudonyms are used instead of generating a new value on every read because changing the identity during normal playback can create avoidable compatibility problems.

## Keystore passthrough package policy

The `drm_packages.txt` file accepts exact package names and bounded wildcard rules. The service resolves the calling Android user identifier and evaluates the package set before global or targeted Keystore certificate substitution decisions.

When passthrough is enabled and a caller matches the DRM list, certificate substitution is skipped. Runtime changes to DRM passthrough, the DRM package list, target scope, global mode, or legacy TEE safe mode state invalidate previously substituted attestation chain cache entries after the new policy is loaded.

The default list includes common media applications such as Netflix, Amazon Prime Video, Disney+, Max, and YouTube. Keeping one of those packages on the genuine Keystore path does not disable DRM Identifier Privacy. The two controls are intentionally independent: a package can use genuine Keystore certificates while `privacy=isolate` pseudonymizes only its supported DRM `deviceUniqueId` read.

## DRM privacy activation

DRM Identifier Privacy requires Identity Spoof Engine to be enabled and the application's Application Rule privacy mode to be `isolate`. `inherit` leaves the DRM identifier unchanged. The DRM privacy hook does not use the Keystore `drm_passthrough` targeting decision.

The runtime discovers stable AIDL `android.hardware.drm.IDrmFactory/*` services, attaches the existing bounded native Binder hook to the vendor DRM service process, observes newly created `IDrmPlugin` Binder objects, and filters only `getPropertyByteArray` transactions. The originating application package name and runtime user context are captured during plugin creation so that requests are correctly isolated even when calls originate from media daemons, secondary users, or work profiles. A request is modified only when its property name is exactly `deviceUniqueId` and the real application has an explicit isolate policy.

Legacy HIDL DRM implementations and vendor specific paths that do not use the supported stable AIDL interface remain untouched. If the expected AIDL service, transaction shape, process information, or response format is unavailable, the hook fails open and Android's original response is preserved.

## Security boundary

The privacy hook does **not** change `getPropertyString`, reported security level, session security level, HDCP state, provisioning, license requests or responses, content keys, secure stops, offline licenses, encryption or decryption operations, or vendor DRM policy. It does not transform L2 or L3 into L1 and does not claim to upgrade a device's hardware backed DRM capability.

A general DRM protection bypass would require substantially broader and continuously maintained vendor specific work. That is not the current purpose of this feature. Bootloader unlock also does not automatically imply that every DRM implementation has stopped working. Actual behavior depends on the device, vendor DRM stack, provisioning state, security level, firmware, and service policy. CleveresTricky therefore treats DRM bypass as a separate problem rather than a primary module objective.

## Resource and failure behavior

Factory discovery and reconciliation are bounded. The implementation caps tracked factory services and plugin Binder objects, retries native injection at a bounded interval, and periodically rescans so restarted or lazy DRM HAL services can be reattached. It does not busy poll.

Only a matching `deviceUniqueId` read for an isolated application performs pseudonym derivation. The code reuses the existing application privacy seed and a thread local SHA 256 digest, does not create an unbounded per request cache, and zeroes temporary copies of the genuine and pseudonymous DRM identifier after constructing the replacement Binder reply.

## Limits

This feature cannot repair broken DRM provisioning, OEMCrypto, vendor HAL defects, revoked credentials, or hardware trust state. It cannot guarantee that an application has no other fingerprinting or account level identifiers. It only prevents the genuine `deviceUniqueId` value from being returned through the supported modern AIDL property path when isolation is explicitly enabled.

If protected playback behaves differently, keep DRM Keystore Passthrough enabled for the affected package, disable or change its privacy rule, restart the application, and review the CleveresTricky log for DRM privacy registration or fail open messages.

[Return to the project overview](../README.md)
