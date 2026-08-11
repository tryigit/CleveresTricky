# Security Model

## Trust boundaries

CleveresTricky runs with root service access and injects a bounded native library into selected Android system processes. Root, the operating system, KernelSU or APatch, the installed module files, and explicitly authorized key material are therefore trusted parts of the local environment.

Applications, Binder request content, uploaded files, remote source responses, configuration edits, archive entries, package rules, template data, file paths, and network metadata are treated as untrusted input.

## File protection

The configuration root is required to be a real directory owned by root. Sensitive files use root only modes. Reads and writes reject symbolic links where a path can affect protected material. Atomic writes prevent readers from observing partial configuration.

Keybox, backup, template, rule, and identity input has fixed count and size limits. Archive extraction uses an allowlist and validates all staged entries before committing them.

## Runtime protection

Caller policy uses the Android user identifier observed through Binder and packages resolved by Package Manager. System identifiers and RKP infrastructure are protected. Unknown package resolution fails closed.

The native parser validates the live Binder ABI before hooks are installed. Binder response bytes pass through a bounded kernel validated copy before Rust parses them. Rust bounds every stream, layout, ancillary message, process name, path, argument, and remote memory plan. Raced, unreadable, unexpected, or malformed input passes through or stops activation without guessing.

The injector accepts only the known `entry` and `resume` symbols, supported stopped process names, executable platform symbol mappings, a root owned regular library that is not writable by group or other users, and an open file descriptor transferred through a random local socket. Its bounded stack journal restores explicit data plus a call stack guard before registers and detach.

The injected Binder control object can be discovered only through a driver reported root caller. Every registration, removal, park, and clear command checks the Binder calling user again. Disabling the engine clears registered callbacks before the hook enters its atomic paused path.

## Web protection

The WebUI is packaged as a native KernelSU and APatch `webroot` and does not listen on a TCP port. The module manager command API invokes a Rust bridge with fixed arguments. Root only queue directories, random request identifiers, atomic publication, strict file checks, bounded staging, timeouts, path and method allowlists, service side validation, and stale cleanup protect privileged operations. The page applies a restrictive content security policy.

## Limits

A hostile root process can modify the runtime and read unlocked secrets. Userspace code cannot physically relock a bootloader, repair verified boot, alter hardware fuses, change TEE measurements, rewrite the modem, or create a hardware trust root.

Remote services can change policy independently. The module cannot guarantee acceptance outside the device.

[Return to the project overview](../README.md)

## Policy state security

Version two policy state keeps the existing root owned configuration boundary. Reads reject symbolic links and non regular files, enforce bounded sizes and field counts, validate enums, dates, package rules, profile names, template names, and keybox references, and publish validated snapshots atomically through the existing secure file writer. A previous valid snapshot is retained as last known good state.

Certificate reconstruction preserves unrelated valid authorization tags and rejects malformed authorization list layouts. RKP passthrough, DRM passthrough, keybox validation, revocation handling, encrypted backup handling, Binder validation, and genuine hardware private key operations remain protected. Profiles store only safe keybox references. Diagnostics and WebUI responses never expose key material or protected credentials.

Optional presentation state does not change the physical bootloader, verified boot measurements, vbmeta, firmware, or the device hardware root of trust. Core boot and Keystore compatibility remains independent from optional identity features.
