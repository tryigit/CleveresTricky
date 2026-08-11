# Profiles

## Purpose

Profiles apply a coherent group of optional settings in one transaction. They remain available as advanced presets while core boot and Keystore protection stay active independently.

## Daily Compatibility

Daily Compatibility uses targeted application scope, keeps optional identity features off, enables keybox monitoring, and preserves genuine RKP and DRM paths. Core certificate and boot protection remain active.

## Default

Default is a conservative optional identity setup. It uses targeted application scope, leaves build identity and telephony off, enables automatic keybox checks, and preserves RKP and DRM passthrough. Core Keystore and TEE compatibility are unchanged.

## Maximum Compatibility

Maximum Compatibility enables Global Mode together with build identity, identity refresh, and telephony handling. It disables RKP and DRM passthrough so the widest configured compatibility scope can be tested.

This profile changes the most optional behavior and should be used for focused testing. It does not alter hardware trust state or guarantee a remote verdict.

## Minimal

Minimal disables optional identity, build identity, telephony, and scheduled keybox checks while preserving genuine RKP and DRM paths. It does not disable the core Keystore interceptor, certificate compatibility, TEE handling, or boot property protection.

Older configurations can still contain the legacy certificate safe mode flag. Core targeting no longer depends on that flag.

## Applying a profile

The service accepts a bounded validated profile request, updates protected configuration flags, reloads policy, and removes the request. Unknown names are rejected.

Profile application does not replace keyboxes, application lists, templates, or user backups. Reboot after a profile changes early boot identity behavior.

[Return to the project overview](../README.md)

## Profiles version two

Built in presets remain available. User defined named profiles can store application assignments, an identity template reference, a validated keybox reference, privacy mode, independent System, Vendor, and Boot patch policies, optional identity feature overrides, and compatible RKP or DRM choices. Private keybox contents are never copied into a profile.

Profile creation, edit, rename, duplicate, delete, assignment, import, export, and activation pass through the same validated policy state. Activation publishes one immutable snapshot only after complete validation. Invalid input does not replace the current snapshot. Exact assignment conflicts are rejected and shared UID resolution is deterministic.

The previous valid policy snapshot is retained as last known good state. A malformed replacement is never partially applied. Existing preset requests and legacy configuration remain supported when no version two state is present.
