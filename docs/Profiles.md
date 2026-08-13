# Profiles

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

Older configurations can still contain the legacy certificate safe mode flag and the retired `rkp_passthrough` marker. Core targeting no longer depends on certificate safe mode, and generated-key handling no longer depends on the retired RKP marker.

## Applying a profile

The service accepts a bounded validated profile request, updates protected configuration flags, reloads policy, and removes the request. Unknown names are rejected.

Profile application does not replace keyboxes, application lists, templates, or user backups. Reboot after a profile changes early boot identity behavior.

[Return to the project overview](../README.md)

## Profiles version two

Built in presets remain available. User defined named profiles can store application assignments, an identity template reference, a validated keybox reference, privacy mode, independent System, Vendor, and Boot patch policies, optional identity feature overrides, and compatible DRM choices. Private keybox contents are never copied into a profile.

Legacy policy snapshots may still contain an RKP passthrough field for migration compatibility, but the runtime ignores that field and the WebUI does not expose it as a live profile choice.

Profile creation, edit, rename, duplicate, delete, assignment, import, export, and activation pass through the same validated policy state. Activation publishes one immutable snapshot only after complete validation. Invalid input does not replace the current snapshot. Exact assignment conflicts are rejected and shared UID resolution is deterministic.

The previous valid policy snapshot is retained as last known good state. A malformed replacement is never partially applied. Existing preset requests and legacy configuration remain supported when no version two state is present.
