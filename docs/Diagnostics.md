# Diagnostics

## Purpose

Diagnostics provides a controlled way to isolate installation, configuration, keybox, identity, RKP, DRM, and native runtime problems.

## First checks

Open Dashboard and confirm the module version, Spoof Engine state, active profile, keybox count, target rule size, RKP state, DRM state, and native feature state. Then open Logs and look for the first failure rather than the last repeated retry.

Use Android logcat with the `CleveresTricky` tag when the WebUI cannot start. Confirm that the daemon remains running, the `webroot` files are present, the architecture specific `webui_bridge` is executable, and the module manager did not disable the module after boot.

## Controlled isolation

1. Apply Minimal and reboot.

2. Confirm that the affected application behaves on the genuine path.

3. Enable Spoof Engine with targeted scope only.

4. Add one authorized key source and one application rule.

5. Enable Build Identity, Telephony, Boot Properties, or broad scope one at a time.

6. Restart the application after each live change and reboot after each early boot change.

This sequence prevents several independent changes from hiding the actual cause.

## Common conditions

An inactive keybox usually means parsing, key correspondence, chain, date, ambiguity, or revocation validation failed. A missing Build change can mean reboot is pending or another provider owns the property layer in automatic mode. A telephony value can remain genuine when Android denied the original permission or the request used an unsupported API or slot.

RKP or protected playback differences should be checked with their passthrough controls enabled. A native injection failure should be checked for the supported API level, architecture payload, root ownership, file mode, SELinux policy, target process name, and current process identifier.

## Recovery

Disable Spoof Engine before boot to prevent native injection and early property changes. If the WebUI is unavailable, disable the module from KernelSU or APatch and reboot. Preserve logs and a protected configuration backup before resetting data.

[Return to the project overview](../README.md)

## Runtime and effective state

Diagnostics exposes the state of each optional component and the core Keystore path. Optional components can report disabled, active, reboot required, or waiting for configuration. Disabled feature paths return before feature specific derivation or cache work. Core Keystore status is shown separately because it is independent from optional identity controls.

The Effective State inspector accepts an installed application and reports the matched rule and profile, scope, identity template, keybox reference, privacy policy, optional feature decisions, configured and effective patch values, RKP and DRM state, genuine platform KeyMint and StrongBox operation state, provider coexistence result, and reboot requirement. Private key material is never returned.
