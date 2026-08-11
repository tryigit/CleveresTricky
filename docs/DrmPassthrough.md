# DRM Keystore Passthrough

## Purpose

DRM Keystore Passthrough keeps selected media applications on Android's genuine Keystore certificate path. It prevents CleveresTricky attestation compatibility handling from leaking into applications where modified certificate chains can interfere with protected playback.

This feature keeps DRM-sensitive packages on Android's genuine keystore certificate path, and the runtime now also attaches a native Binder interception path for the platform DRM service so security-level replies can be normalized only for configured non-passthrough callers.

## Package policy

The `drm_packages.txt` file accepts exact package names and bounded wildcard rules. The service resolves the calling Android user identifier and evaluates the package set before global or targeted certificate substitution decisions.

When passthrough is enabled and a caller matches the DRM list, certificate substitution is skipped. Other CleveresTricky controls remain available for applications outside that list.

Runtime changes to DRM passthrough, the DRM package list, target scope, global mode, or TEE broken mode invalidate previously substituted attestation chain cache entries after the new policy is loaded. This prevents a package that has moved onto the genuine path from receiving a chain cached under an older policy.

## Defaults

Daily Compatibility, Default, and Minimal enable DRM Keystore Passthrough. Maximum Compatibility disables it for controlled tests that intentionally use the widest scope.

The package list has no effect while the dedicated passthrough control is disabled. Changes reload without restarting the service.

## Safety

Package count, file size, line length, and wildcard form are bounded. Invalid input leaves the previous valid policy active. Unknown package resolution does not become a broad substitution decision.

The DRM subsystem itself remains owned by Android and the device DRM implementation. CleveresTricky only intercepts bounded Binder reply fields and still does not replace license exchange, provisioning, content keys, or OEM DRM HAL ownership.

## Limits

This feature does not implement DRM, create licenses, or repair a device whose DRM provisioning, OEMCrypto implementation, vendor DRM HAL, or hardware backed DRM state is independently broken.

If protected playback behaves differently, enable DRM Keystore Passthrough, confirm the package is listed, restart the application, and review the log for the caller and policy decision.

[Return to the project overview](../README.md)
