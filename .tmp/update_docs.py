from pathlib import Path

sections = {
    "README.md": """

## Granular optional identity controls

CleveresTricky now resolves optional identity behavior through independent controls for Device and Build Identity, Attestation Identity, Telephony Identity, Region Identity, Identity Refresh, and Security Patch. Security Patch is independent from Device and Build Identity. A configuration can present a different supported patch level without enabling Build Identity, or present a different Build Identity while all patch levels remain genuine.

Core Keystore interception, genuine platform KeyMint and StrongBox private key operations, root of trust handling, Binder safety, and required boot compatibility remain independent from these optional controls. The interface describes captured device state, configured presentation state, and effective application visible state separately. It does not claim to change physical bootloader state, verified boot measurements, firmware, or a remote integrity verdict.

The Security Patch view exposes System, Vendor, and Boot as independent policies. Profiles can assign coherent optional settings to applications, and the Effective State inspector reports the resolver output that runtime decisions use.
""",
    "docs/SpoofEngine.md": """

## Optional feature state

Identity behavior is resolved as independent optional features. Device and Build Identity controls app visible Build fields. Attestation Identity controls supported identity substitutions inside attestation responses. Telephony Identity controls supported telephony identity APIs. Region Identity controls region presentation. Identity Refresh controls next boot identity generation. Security Patch is separate and can be enabled without any Build Identity change.

Disabling optional identity features does not disable core Keystore interception, genuine KeyMint or StrongBox key operations, root of trust handling, boot compatibility, Binder validation, or certificate compatibility foundations. Optional interceptors stay parked when no active or assigned configuration requires them.
""",
    "docs/PatchLevels.md": """

## Independent patch policies

System, Vendor, and Boot patch levels are resolved independently. Each component supports Device, Property, Manual, Automatic, and Omit modes. Device preserves the genuine authorization value. Property reads the matching Android property for that component. Manual accepts a strictly validated calendar date. Automatic evaluates the corresponding captured value first and then the corresponding property when no captured value is available. Omit removes only the selected component.

Automatic mode uses calendar arithmetic. The default age threshold is six months. A stale source resolves to day five of the previous calendar month. A recent captured value remains genuine. January correctly resolves through the previous December and leap year calendar rules are handled by the platform date API. The result is live reload compatible and cached by source date, current month, and threshold.

Captured means the genuine authorization value observed from Android attestation. Configured means the selected policy. Effective means the value that the runtime resolver will expose. An unrelated certificate modification preserves genuine System, Vendor, and Boot authorization tags, including their original software or TEE authorization list location. Malformed authorization layouts fail closed.

Existing security_patch.txt rules remain supported. When no patch override is active, genuine patch values remain untouched.
""",
    "docs/BuildIdentity.md": """

## Feature separation

Device and Build Identity is an optional feature with its own lifecycle. It covers supported Build fields such as manufacturer, brand, model, product, device, fingerprint, build identifier, incremental value, release, type, and tags. Values captured during early boot still require a reboot when changed.

Security Patch is not part of Device and Build Identity. Build Identity can be enabled while System, Vendor, and Boot patch authorizations remain genuine. Security Patch can also be enabled while Build Identity is disabled. Region, Telephony, Attestation Identity, and Identity Refresh are resolved separately.
""",
    "docs/Profiles.md": """

## Profiles version two

Built in presets remain available. User defined named profiles can store application assignments, an identity template reference, a validated keybox reference, privacy mode, independent System, Vendor, and Boot patch policies, optional identity feature overrides, and compatible RKP or DRM choices. Private keybox contents are never copied into a profile.

Profile creation, edit, rename, duplicate, delete, assignment, import, export, and activation pass through the same validated policy state. Activation publishes one immutable snapshot only after complete validation. Invalid input does not replace the current snapshot. Exact assignment conflicts are rejected and shared UID resolution is deterministic.

The previous valid policy snapshot is retained as last known good state. A malformed replacement is never partially applied. Existing preset requests and legacy configuration remain supported when no version two state is present.
""",
    "docs/ApplicationRules.md": """

## Profile resolution

Application assignments in named profiles participate in the same resolver as runtime policy decisions. The most specific matching rule is chosen deterministically. Shared UIDs are evaluated with sorted package names so repeated requests produce the same result. The Effective State inspector reports the matched application rule, matched profile, and resulting scope without interpreting configuration a second time.

Legacy application rules continue to work when no version two profile supplies the relevant configuration. Package input is validated before activation.
""",
    "docs/Diagnostics.md": """

## Runtime and effective state

Diagnostics exposes the state of each optional component and the core Keystore path. Optional components can report disabled, active, reboot required, or waiting for configuration. Disabled feature paths return before feature specific derivation or cache work. Core Keystore status is shown separately because it is independent from optional identity controls.

The Effective State inspector accepts an installed application and reports the matched rule and profile, scope, identity template, keybox reference, privacy policy, optional feature decisions, configured and effective patch values, RKP and DRM state, genuine platform KeyMint and StrongBox operation state, provider coexistence result, and reboot requirement. Private key material is never returned.
""",
    "docs/Performance.md": """

## Optional work scheduling

Optional runtime work follows the resolved feature snapshot. Telephony interception is not retained when no global, active, or assigned profile requires telephony or privacy handling. DRM privacy interception follows the same scoped rule. Identity Refresh does not prepare a next boot snapshot while disabled. Region processing is skipped while disabled. Security Patch returns genuine authorization values without dynamic date resolution while disabled.

Configuration uses immutable state replacement, bounded caches, event driven file observation, and targeted cache invalidation. The legacy periodic keybox file poller is no longer needed because keybox updates use the existing observer path. No new polling loop is introduced.
""",
    "docs/SecurityModel.md": """

## Policy state security

Version two policy state keeps the existing root owned configuration boundary. Reads reject symbolic links and non regular files, enforce bounded sizes and field counts, validate enums, dates, package rules, profile names, template names, and keybox references, and publish validated snapshots atomically through the existing secure file writer. A previous valid snapshot is retained as last known good state.

Certificate reconstruction preserves unrelated valid authorization tags and rejects malformed authorization list layouts. RKP passthrough, DRM passthrough, keybox validation, revocation handling, encrypted backup handling, Binder validation, and genuine hardware private key operations remain protected. Profiles store only safe keybox references. Diagnostics and WebUI responses never expose key material or protected credentials.

Optional presentation state does not change the physical bootloader, verified boot measurements, vbmeta, firmware, or the device hardware root of trust. Core boot and Keystore compatibility remains independent from optional identity features.
""",
}

for filename, addition in sections.items():
    path = Path(filename)
    existing = path.read_text()
    heading = addition.strip().splitlines()[0]
    if heading not in existing:
        path.write_text(existing.rstrip() + addition)

changelog = Path("CHANGELOG.md")
existing = changelog.read_text()
entry = """

## Granular policy state

* Added independent Device and Build, Attestation, Telephony, Region, Identity Refresh, and Security Patch controls.
* Added independent System, Vendor, and Boot patch policies with genuine, property, manual, automatic, and omit resolution.
* Preserved genuine patch authorizations during unrelated certificate modification and retained authorization list placement.
* Added named profiles, effective state inspection, runtime component status, atomic policy activation, and last known good recovery.
* Parked optional runtime work when its resolved feature is disabled and removed redundant keybox polling.
* Extended tests for patch preservation, automatic calendar resolution, feature independence, profile resolution, shared UID behavior, malformed input, recovery, and safe file handling.
"""
if "## Granular policy state" not in existing:
    changelog.write_text(existing.rstrip() + entry)
