# Spoof Engine

## Purpose

Spoof Engine is the identity runtime control. It enables optional application facing identity overrides without controlling the core boot and Keystore protection paths.

## Runtime behavior

Core Keystore and TEE interception remains active while the module service is healthy. Certificate compatibility, root of trust handling, patch policy, and the native Keystore Binder registration do not stop when Spoof Engine is disabled.

When Spoof Engine is enabled, configured attestation identity fields can be substituted for targeted applications. Telephony interception can run when its dedicated control is also enabled. Optional build identity, region identity, and identity refresh behavior also require Spoof Engine.

When Spoof Engine is disabled, identity values remain saved but are not exposed through the identity interception paths. Telephony interception is parked when it is no longer needed. Core Keystore interception remains registered.

## State changes

The WebUI writes the identity state as a protected flag in the configuration directory. The service observes the change and wakes its runtime controller immediately. Reinstallation is not required.

Applications can cache identity values they obtained earlier. Restart an affected application after changing the identity engine state. Reboot when a change involves fingerprint or other build identity values because Android captures those values early.

## Safety behavior

Global Mode controls application scope independently from Spoof Engine. Fresh installations enable Global Mode by default while identity spoofing remains off.

Tamper detection still forces a safe service state. In that state the WebUI can present the warning, but native interception does not start.

[Return to the project overview](../README.md)

## Optional feature state

Identity behavior is resolved as independent optional features. Device and Build Identity controls app visible Build fields. Attestation Identity controls supported identity substitutions inside attestation responses. Telephony Identity controls supported telephony identity APIs. Region Identity controls region presentation. Identity Refresh controls next boot identity generation. Security Patch is separate and can be enabled without any Build Identity change.

Disabling optional identity features does not disable core Keystore interception, genuine KeyMint or StrongBox key operations, root of trust handling, boot compatibility, Binder validation, or certificate compatibility foundations. Optional interceptors stay parked when no active or assigned configuration requires them.
