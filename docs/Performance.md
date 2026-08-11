# Performance and Memory

## Runtime lifecycle

Core Keystore interception remains registered while the module service is healthy. The native Binder hook therefore stays available for certificate and TEE compatibility even when Spoof Engine is disabled.

Spoof Engine is the identity resource control. When disabled, optional attestation identity values are not exposed, Telephony Identity is parked when no privacy rule needs it, DRM Identifier Privacy is parked, and optional build and region identity work is skipped. Core certificate handling and boot property protection remain active.

When Spoof Engine is enabled, the DRM privacy controller reconciles modern stable AIDL DRM factories at a bounded interval. It does not busy poll. Lazy or restarted DRM services are rediscovered, while an injector retry for the same process is rate limited.

Automatic Keybox Check has its own control and is independent from Spoof Engine. Disable that worker directly when scheduled revocation work is not wanted.

## Native path

Rust parses Binder streams into a fixed caller owned array. A fixed local buffer and one kernel validated pipe session protect the read path without heap allocation or an unbounded scan. Transaction writeback uses one kernel validated pipe session for all redirected fields instead of opening one pipe for each field. Kernel copies are divided into bounded chunks so a large request cannot wait on pipe capacity.

The Binder descriptor cache uses 64 fixed slots and no heap growth. The Rust hot path validates device plus inode identity before using a cached classification and checks identity again after procfs resolution. The platform weak pointer handoff uses a fixed per thread queue, so transaction bursts cannot grow a dynamic container. A malformed or oversized stream is passed through without unbounded work.

The injector is a short lived Rust process. Rust owns its arguments, logs, file descriptors, buffers, process maps, symbol resolution, ptrace session, register layouts, process memory, socket transfer, loader calls, cleanup, register restoration, and detach state. Temporary target stack writes and the call stack guard use a fixed upper bound. Overlapping ranges are saved once and restored before detach. C plus plus remains only at the injected Android libbinder and LSPlt boundary.

## DRM privacy cost

DRM Identifier Privacy registers only the stable AIDL `IDrmFactory.createDrmPlugin` and `IDrmPlugin.getPropertyByteArray` transaction codes. Requests for licenses, keys, provisioning, sessions, security level, HDCP state, and DRM string properties never enter the replacement path.

The controller caps tracked DRM factory services at 16 and plugin Binder objects at 256. Dead Binder objects are pruned before new registrations are accepted. Reconciliation runs no more often than the normal runtime controller interval when healthy, and native injection attempts for one PID are rate limited.

A pseudonym is derived only when an isolated application reads exactly `deviceUniqueId`. The derivation reuses the already protected application privacy identity and a thread local SHA 256 instance. There is no persistent DRM ID file and no growing per request or per app DRM pseudonym cache. Output is bounded to 8 through 64 bytes, matching only supported original identifier sizes. Temporary copies of the genuine DRM identifier and the pseudonym are cleared after the replacement Parcel has been constructed.

## Service memory

Package, application rule, DRM, RKP, certificate, patch, template, and keybox caches have fixed entry or byte limits. Policy updates replace state and related caches together. File changes use Android FileObserver and therefore do not wake a periodic polling thread during normal operation. Low frequency polling is enabled only as a fallback when FileObserver cannot be started on the target filesystem.

The WebUI resource view reads bounded procfs lines only when opened. Its CPU parser avoids regular expressions and token collections, uses a monotonic sampling interval, and cancels an obsolete request when the user leaves or reopens the view.

Encrypted and backup operations enforce expanded size before retaining input. Sensitive temporary byte arrays are cleared where the managed runtime permits.

## Build choices

Release Rust uses full link time optimization, one code generation unit, size optimization, symbol stripping, and caught panic unwinding at FFI boundaries. The small unwind cost prevents an unexpected Rust panic from terminating a critical injected process.

Native outputs use section collection, hidden visibility, stack protection, immediate symbol binding, read only relocation protection, a non executable stack, and position independent execution.

## Lowest overhead setup

Keep optional Identity Spoof Engine off when identity substitution and DRM identifier privacy are not needed. Disable Telephony Identity and Automatic Keybox Check unless required. Core Keystore and boot protection remain active because they are the baseline module behavior.

[Return to the project overview](../README.md)

## Optional work scheduling

Optional runtime work follows the resolved feature snapshot. Telephony interception is not retained when no global, active, or assigned profile requires telephony or privacy handling. DRM privacy interception follows the same scoped rule. Identity Refresh does not prepare a next boot snapshot while disabled. Region processing is skipped while disabled. Security Patch returns genuine authorization values without dynamic date resolution while disabled.

Configuration uses immutable state replacement, bounded caches, event driven file observation, and targeted cache invalidation. The legacy periodic keybox file poller is no longer needed because keybox updates use the existing observer path. No new polling loop is introduced.
