# Build Identity

## Purpose

Build Identity applies a complete device template to the fingerprint and supported app visible Build fields. It is optional, requires Spoof Engine, and requires a reboot because Android captures these values before normal applications start.

## Template content

A template contains manufacturer, model, brand, product, device, fingerprint, Android release, build identifier, incremental value, build type, build tags, and security patch information. Built in templates can be extended through a validated local template file.

Selecting a template writes the complete supported identity into `spoof_build_vars`. Arbitrary Android properties are rejected. This keeps the feature bounded to fields the module actually consumes.

## Auto Identity

Auto Identity is an optional helper for Custom ROM users. It reads Google public Android developer and Flash Tool metadata, selects a current Pixel beta device, resolves the matching canary build identity, and saves the resulting build fields locally.

The helper is intended for Play Integrity compatibility testing on Custom ROMs. It does not enable Spoof Engine automatically. Enable the identity engine and reboot only when you want the saved build identity exposed.

Auto Identity updates the build identity snapshot. TEE component patch policy remains controlled separately by `security_patch.txt`.

## Synchronized activation

The early boot script applies the active identity snapshot before Zygote captures Build fields only when Spoof Engine and Build Identity are enabled. The service loads the same snapshot for identity substitution decisions.

When Refresh Identity on Boot is enabled, the running snapshot is never rotated after early boot. The service prepares a separate randomized snapshot with atomic protected storage. The next early boot phase promotes that prepared snapshot before applying identity properties. Build fields and attestation identity values therefore use one synchronized identity for the entire boot.

Manual identity edits discard an older prepared snapshot so a stale random value cannot replace a newer user choice.

## Compatibility policy

Automatic mode detects common overlapping fingerprint providers and leaves optional Build properties untouched. Core boot and Keystore protection continue operating. Force mode is available for users who intentionally want CleveresTricky to own the build identity property layer.

## Limits

Build Identity changes supported userspace views. It does not change the real hardware model, firmware, kernel, verified boot measurement, or hardware trust root.

[Return to the project overview](../README.md)

## Feature separation

Device and Build Identity is an optional feature with its own lifecycle. It covers supported Build fields such as manufacturer, brand, model, product, device, fingerprint, build identifier, incremental value, release, type, and tags. Values captured during early boot still require a reboot when changed.

Security Patch is not part of Device and Build Identity. Build Identity can be enabled while System, Vendor, and Boot patch authorizations remain genuine. Security Patch can also be enabled while Build Identity is disabled. Region, Telephony, Attestation Identity, and Identity Refresh are resolved separately.
