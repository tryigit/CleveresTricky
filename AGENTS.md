# CleveresTricky Versioning Rules

When bumping versions for the CleveresTricky module, do NOT touch `update.json`. You must leave `update.json` completely unmodified.
Only update `build.gradle.kts` (e.g., `val verName by extra(...)`) and any other necessary files, but explicitly skip `update.json`.

## Release finalization contract

Treat a version bump and release finalization as separate phases. The version-bump rule above remains strict: do not edit `update.json` while merely changing the version.

When finalizing or re-finalizing a release after the build has produced the actual release artifact:

1. Verify the exact release commit/tag and require every release-selected workflow to pass, including the release/publish job when it applies. Never finalize from an older, queued, partially successful, or different head.
2. Read the live published artifact metadata before editing release metadata. Derive the exact `versionCode`, commit/hash component, ZIP filename, URL, checksum/digest, and tag from the actual build/release; never predict them from commit counts or filenames.
3. Do not list internal-only maintenance such as `AGENTS.md` or other agent/developer documentation-only commits as user-facing release changes unless they materially change shipped user behavior.
4. Update `CHANGELOG.md` with a polished section based on the real diff since the previous released artifact. Group entries by user benefit and include late fixes that landed before the final shipped build.
5. `CHANGELOG.md` is the canonical release log. Do not mirror release changelog entries into `docs/i18n/` or localized README files unless the maintainer explicitly asks for localized release notes.
6. Update `update.json` only after the release asset exists. Its version string, `versionCode`, ZIP URL/filename, commit/hash component, and changelog target must describe that exact live artifact.
7. Update the GitHub Release for the same tag as part of the same finalization. Replace generated or stale `## What's Changed`, contributor-only notes, and stale `Full Changelog` compare ranges with the canonical current-version `CHANGELOG.md` section plus the correct previous-tag-to-current-tag compare link. Include every late release-critical fix that shipped.
8. When rebuilding/reissuing the same version, replace or retire stale release assets/metadata as needed. The tag/release, published ZIP, checksums/digests, `update.json`, and release notes must all describe one coherent shipped build; never leave a mixture of old and new hashes/versionCodes/URLs.
9. Keep changelog/update/release-metadata changes in one coherent commit when practical. Do not bump the version again merely to finalize metadata unless the maintainer explicitly asks for a new version.
10. If the exact release build, tag, publish job, or expected asset is missing or failed, make no speculative release-metadata edits. Report the blocker and wait for a verified artifact.

## Repository-wide engineering contract

CleveresTricky must be treated as one system, not as a collection of unrelated files. A change that looks local may cross Kotlin/Android, module packaging, WebUI, native Binder, Rust backend, cache/serialization, backup/restore, or CI boundaries.

Before editing code, agents must inspect the actual current repository tree and the relevant workflows. Do not assume an older directory layout, an earlier conversation summary, comments, or this file are more authoritative than the checked-out source. If documentation and the current tree disagree, investigate the discrepancy instead of coding from the stale description.

For every non-trivial bug fix or behavior change:

1. Identify the observable failure and the invariant that should hold.
2. Search all references to the affected symbol, format, config key, cache entry, file type, API, or lifecycle object before choosing the fix.
3. Trace both producers and consumers. Include alternate paths such as direct input vs ZIP/archive input, fresh data vs cached data, startup vs runtime refresh, success vs recovery, UI vs service, and Kotlin vs Rust/native boundaries when applicable.
4. Fix the root cause at the narrowest shared invariant. Do not patch only the first caller if sibling paths can violate the same invariant.
5. Add or update a regression test that fails for the original bug and passes for the intended behavior. A bug fix without regression coverage requires a concrete reason in the PR description.
6. Audit neighboring edge cases after the fix: failure, cancellation, retry, restart, concurrent events, stale cache state, malformed input, partial reads/writes, zero/maximum/over-limit sizes, duplicate names, symlinks/path changes, and cleanup on early return.
7. Re-search references after editing to verify no old bypass, duplicate implementation, compatibility accessor, or alternate path still violates the invariant.
8. Inspect the final diff as a whole. Broadly audit; narrowly patch. Do not mix unrelated cleanup into a bug fix merely because the file is already open.

### Mandatory security and resource-boundary reasoning

- Enforce size/count/resource limits before expensive work such as hashing, parsing, decrypting, decompressing, allocating, or reading an entire stream. Metadata checks alone are not sufficient when a file/stream can change during the operation; the operation itself must remain bounded.
- Security-sensitive state must fail closed. If data is signed/encrypted/trusted only under a verification condition, no alternate accessor, cache restore path, archive path, compatibility API, or lazy getter may expose the protected data before that condition is satisfied.
- Secret or sensitive buffers must be wiped on success, rejection, exception, and early-return paths when the surrounding code already guarantees wiping semantics.
- Cancellation and exception paths are first-class behavior. A scheduler, worker, observer, retry loop, or lifecycle owner must not leave stale ownership state, lose pending work, resurrect cancelled work, or overwrite a replacement worker.
- Cache identity must include every property that changes the trust or interpretation of cached data. Never serialize process-local/opaque handles as if they were portable key material.
- File handling must preserve `NOFOLLOW_LINKS`/symlink protections and bounded I/O. Do not replace secure file helpers with convenient unbounded APIs.

### State modeling, snapshots, and cache correctness

- Model non-success states explicitly. `unknown`, `unavailable`, `failed`, `unstable`, `not verified`, `not found`, and `not cacheable` must not be converted into ordinary domain values when those values can later participate in equality, hashing, ordering, caching, deduplication, persistence, serialization, or trust decisions. Prefer an explicit nullable/result/state type and force the caller to skip, retry, invalidate, or fail closed.
- Cache admission and cache identity are separate decisions. If a stable identity or fingerprint cannot be established, the object is ineligible for both cache lookup and cache write. Do not substitute a fallback timestamp, zero ID, empty hash, default version, previous metadata, constant sentinel, or other magic value. Invalidate prior cache state when the current source is no longer admissible.
- Before introducing a default, fallback, sentinel, placeholder, or exception-to-value conversion, perform collision analysis. Consider whether two independent failures, a valid domain value, repeated retries, restarts, or concurrent races can collapse to the same representation and later compare equal. If they can, the representation is unsafe for identity-sensitive logic.
- Treat mutable external inputs as snapshots rather than stable paths or names. A successful pre-check does not prove the object remains the same across validation, fingerprinting, parsing, verification, caching, and use. Consider growth, shrink, replacement, rename, truncation, and ABA-style A-to-B-to-A changes between every separate operation. Bind validation and consumption to the same snapshot when practical; otherwise revalidate, retry, skip, or fail closed without persisting uncertain results.
- Do not overload unrelated metadata or wrapper behavior with hidden control-state semantics. Timestamps, lengths, hashes, versions, enum placeholders, filenames, subclass overrides, and protocol fields should mean what their type/name says. When control flow depends on a second meaning, represent that state explicitly so invalid states cannot accidentally enter normal success paths.
- For caches, registries, deduplication maps, and memoized state, test admission, invalidation, and negative behavior in addition to returned values. Prove that rejected, unstable, or unverified inputs do not create reusable entries; that old entries are evicted when inputs become ineligible; and that independent failure paths cannot collide into stale reuse.
- When a fix adds any fallback or converts an exception/failure into data, trace that value through every consumer: equality checks, map/set keys, hashes, persistence, retries, serialization, security decisions, cleanup, and recovery. A fallback is not local once another layer can observe it.
- Use adversarial event sequences when reviewing stateful code: fail-fail, fail-success-fail, retry after partial work, cancel-restart, concurrent replacement, same-length/same-timestamp replacement, and A-to-B-to-A transitions. Check both the immediate result and the state left behind for the next operation.
- Treat a review finding as evidence of an invariant class, not as a one-line specification. Generalize the failure mode, search neighboring producers/consumers and equivalent representations, then encode the general invariant in tests and design rules. Do not add narrow instructions that merely name the line or bug that was just fixed.

### External contracts and evidence-driven platform work

- Treat bug reports, issue comments, review suggestions, generated audits, AI analyses, old forks, and historical code as hypotheses, not as proof about the current head. Before changing production code, map every claimed symbol and call path to the current tree and prove that the described failure is reachable now.
- Build an evidence chain for platform-sensitive changes: observable failure -> current producer/transformer/consumer path -> normative external contract -> regression test. If one link is missing, keep investigating instead of filling the gap with a plausible-looking patch.
- For Android framework, Binder, Keystore2, KeyMint, telephony, package management, process identity, or API-compatibility work, consult the relevant API-level AOSP source, AIDL definitions, compatibility/CTS/VTS material, and official Android documentation for the supported API range. Verify names, transaction semantics, UIDs, constants, ownership, and lifecycle behavior against the actual platform version instead of copying magic values or assumptions from a report.
- For cryptography, X.509, ASN.1, DER, key formats, signatures, and protocol encodings, read the normative RFC/standard plus the documentation or source of the exact dependency version used by the repository. Prefer typed, standards-aware encoders/decoders over manual byte surgery. Do not add custom sign padding, INTEGER padding, modulus trimming, or canonicalization when the active typed library already owns that invariant; prove the library contract and lock it with a boundary test instead.
- Distinguish key roles explicitly. A certificate subject key, issuer/signing key, attestation key, generated application key, opaque backend handle, and verification key are not interchangeable merely because they share an algorithm. Trace which key owns each signature and which public key a relying party must verify before modifying a certificate path.
- Binder is reentrant. Do not hold an internal mutex, monitor, map lock, cache lock, or lifecycle lock across an outgoing Binder/framework callback or other external call unless the design has a documented and tested lock-order/reentrancy argument. Copy the minimum state under lock, release it, perform the external call, then reacquire only to publish state if the snapshot is still current.
- Caller identity is a security and stability boundary. Before intercepting system services, reason about application UIDs, shared/system UIDs, isolated/sandbox UIDs, multi-user UID composition, Binder calling identity, and any clear/restore-calling-identity transitions. Prefer platform-defined predicates/constants or a verified invariant over a copied list of privileged UIDs.
- Historical issues can prove that a symptom existed, but not that a proposed root cause is still present. Inspect the fix history/current implementation when relevant. If the current code already enforces the necessary invariant, do not manufacture a second production fix; strengthen regression coverage or documentation only when it adds durable protection.
- When an external library or Android version changes, revalidate the assumptions inherited from its previous contract. A dependency upgrade or new API level can invalidate encoding, threading, transaction-layout, ownership, or compatibility assumptions even when repository code did not change.
- Record false positives as such in review reasoning. Avoid code churn whose only purpose is to make an inaccurate report appear addressed; unnecessary branches, bypasses, duplicated guards, and hand-rolled protocol logic create new failure modes.

## No-shortcut / no-partial-fix policy

Agents must not optimize for the smallest textual patch or for making one failing check disappear. Optimize for a correct invariant with the smallest complete implementation.

Do not:

- stop after the first plausible fix without checking sibling paths and callers;
- weaken, skip, delete, quarantine, or rewrite a valid regression test merely to get CI green;
- relax security thresholds, parser limits, hardening checks, warnings-as-errors, or lint rules to hide a defect;
- change expected values to match broken behavior unless the behavior change is intentional and justified by the project contract;
- swallow exceptions, add broad catch-and-ignore blocks, or convert failures to empty/default success values without proving that is the intended API behavior;
- add duplicate `*-fix`, `*-patch`, compatibility wrappers, second owners, or temporary runtime layers when the existing owner should be corrected;
- leave required tests, cleanup, or cross-path auditing as `TODO` follow-up work when it belongs to the same fix;
- claim a change is safe because it "should compile" or because a similar path passed previously;
- create temporary GitHub Actions workflows or CI jobs whose purpose is to edit/commit/push source code to the PR branch. CI validates code; it must not be used as a branch-mutation workaround. Make source changes through normal commits so the final PR head is authored and reviewable normally;
- use CI results from an older/staging/bot-generated head as evidence that the final head is green. Only checks attached to the exact final commit count.

If a build/test failure appears, read the failing job/step output and fix the root cause. Do not repeatedly guess-edit-push. Distinguish source failures from infrastructure failures and record that distinction when relevant.

## Mandatory preflight before PR or direct push

Agents must run the checks that correspond to the changed scope before opening a PR or directly pushing a requested change. Do not intentionally use GitHub CI as the first compile/test attempt when the equivalent check can be run locally or in the available execution environment.

Always:

- inspect `git diff --check` (or the equivalent diff validation) and the complete changed-file list;
- verify there are no temporary patch scripts, generated debug artifacts, local secrets, test fixtures in runtime directories, or accidental workflow files in the final diff;
- verify the branch is based on the intended current `master` and re-check conflicts/stale assumptions if `master` moved during the work.

For Gradle/Kotlin/Java/XML changes, match the Build/Security workflows as applicable:

```bash
./gradlew ktlintCheck --warning-mode=fail --console=plain
./gradlew :service:lintDebug :stub:lintDebug :encryptor-app:lintDebug --warning-mode=fail --console=plain --continue
./gradlew :service:testDebugUnitTest :stub:testDebugUnitTest :encryptor-app:testDebugUnitTest --warning-mode=fail --console=plain --stacktrace --no-build-cache
```

When only a subset is genuinely unaffected, agents may run the workflow-equivalent affected subset, but shared Gradle/build logic or cross-module behavior requires the full set above.

For Rust changes:

```bash
cd rust
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --verbose
```

Also run targeted native/Binder regression tests for the touched invariant instead of relying only on the workspace test sweep.

For WebUI changes:

```bash
for script in module/template/webroot/*.js; do node --check "$script"; done
for test_file in module/webui-tests/*.test.js; do node "$test_file"; done
```

For installer/shell changes, run the matching shellcheck and extraction/security tests from the workflows. For native/module packaging changes, run the module build/hardening path, not only source-level unit tests.

If an environment cannot run a required check, state exactly which check could not be run and why. Do not silently substitute confidence for execution.

## Functional behavior and platform validation contract

Source-shape assertions such as `includes`, regex matches, expected filenames, or source snippets are architecture guards only. They are not sufficient proof that a user-facing feature works. A green syntax check plus source-shape checks must never be treated as equivalent to executing the affected behavior.

For every user-visible feature change, state transition, persistence path, bridge/API mutation, or bug that could make a control appear to work while runtime state is wrong:

1. Add at least one executable behavior test that invokes the real production function or production code path. Source-shape assertions may remain as supplemental architecture checks, but they do not satisfy regression coverage by themselves.
2. For WebUI code, prefer loading the real runtime owner in a controlled Node/VM harness or an equivalent browser test and invoking the affected handlers/helpers. A test that only reads `policy.js`, `ux.js`, or `index.html` as text cannot prove click/save/refresh behavior.
3. For any escaping, sanitization, encoding, quoting, or HTML-construction primitive used with `innerHTML`, template strings, attribute values, labels, or generated markup, execute the primitive against adversarial strings containing at least `&`, `<`, `>`, `"`, and `'`. Assert the exact encoded output and include an attribute-boundary case when the output can enter an attribute. Do not validate escaping solely by checking that a mapping literal exists in source.
4. For WebUI -> bridge/API -> persistence/runtime chains, test the action-level contract end to end with realistic fakes: the intended request is issued; canonical/persisted success is reflected in returned state; stale UI state is not reused after a successful mutation; a later read-only/presentation refresh failure cannot retroactively turn a committed mutation into failure; and a real backend mutation failure still surfaces as failure.
5. Exercise both success and failure paths for critical buttons/actions. A successful backend operation followed by a failed refresh, reload, status fetch, localization pass, or other presentation-only step must be tested separately from a failed backend operation.
6. Runtime owners must be executable in tests deeply enough that replacing a real implementation with a marker, stub, empty shell, or source-pattern decoy fails CI. Representative critical hooks must actually run; file size and regex presence are not acceptable substitutes.
7. When a defect is first discovered only on a physical device, add the lowest-layer deterministic automated regression test that would have caught the same invariant without the device whenever technically possible. Treat the device finding as evidence that automated coverage was incomplete and strengthen the reusable harness so the same failure class is caught without repeating manual reboot validation.
8. Device-bound critical paths that depend on Android framework/native behavior require layered automated validation before merge: host/unit contracts, service/bridge contracts where applicable, and an Android emulator/platform contract on the repository's current platform oracle. The emulator job must assert the actual booted API/release rather than trusting package names. Physical KernelSU/APatch testing is not a routine PR merge requirement unless the maintainer explicitly requests it for a specific change.
9. Automated platform validation must verify observable behavior, not only emulator process health. For a setting/action, verify the mutation, persisted/canonical state, post-action readback, and the closest deterministic runtime/platform effect available in CI. When an invariant cannot be represented directly on the stock emulator because it depends on root-manager or hardware-only facilities, cover the boundary with production-code host tests, protocol/framework contracts, fault injection, and Android instrumentation rather than requiring repetitive manual reboots.
10. If a new regression demonstrates that the current test suite could pass while the shipped feature is non-functional, strengthen the shared test harness or contract so the entire failure class is detected in future changes. Do not add only a one-off assertion naming the exact line that failed.

## CI failure discipline

A PR is not "green enough" because one workflow passed. Evaluate the exact final head commit across every workflow selected by the repository's change detection.

When CI fails:

1. Open the first actionable failing job and inspect the actual log/report.
2. Reproduce the same command locally when possible.
3. Fix the underlying source/test/config issue, not the CI symptom.
4. Add or strengthen regression coverage when the failure exposed a missing invariant.
5. Re-run the directly affected local checks before pushing another commit.
6. Re-check the entire final workflow set after the new head is created.

Avoid commit churn. Multiple speculative "maybe this fixes CI" commits are a signal to stop and inspect the failing command more deeply.

## WebUI architecture contract

The runtime WebUI file layout is fixed. AI agents and human contributors must extend the existing owners instead of creating extra JavaScript or CSS layers.

Allowed runtime files under `module/template/webroot`:

- `index.html`: static HTML, base/static CSS, and the legacy inline controller that has not yet been extracted. Do not create additional HTML entry points or standalone CSS files.
- `bridge.js`: native KernelSU/APatch bridge, host commands, file transfer, external intents, and minimal WebUI bootstrap only. Do not add policy rendering, localization catalogs, or page-specific UI state here.
- `policy.js`: policy/state API integration and policy-owned dynamic controls/pages only. Do not wrap or replace global navigation functions and do not own general UX/community-link behavior.
- `ux.js`: the single general UX/localization/presentation owner. Locales, guide presentation, community-link behavior, compatibility presentation, and general UX enhancements belong here.
- `LOCALES.md`: localization documentation only; it is not a runtime asset.

Do not add files such as `*-patch.js`, `*-fix.js`, `*-test.js`, `*-overlay.js`, `*-ux.js`, temporary CSS files, experiment bundles, or feature-specific runtime JS/CSS files. Tests must stay outside `module/template/webroot` and must never become runtime assets.

When changing WebUI behavior:

1. Identify the existing owner above and edit that file.
2. Prefer deleting obsolete compatibility layers over adding a new layer.
3. One DOM surface must have one owner. Do not attach competing handlers from multiple files to the same control.
4. Do not monkey-patch `window.switchTab`, `window.toggle`, or another global controller from multiple files. If a compatibility hook is unavoidable, it must have exactly one documented owner.
5. Keep the runtime JS set fixed to `bridge.js`, `policy.js`, and `ux.js`; keep standalone runtime CSS count at zero unless this contract is intentionally redesigned in the same change.
6. Do not reintroduce `ux-base.js`; its contents were consolidated into `ux.js`.

## WebUI source-of-truth and local preview discipline

The checked-in WebUI source of truth is `module/template/webroot/index.html` plus the three runtime owners `bridge.js`, `policy.js`, and `ux.js`. Do not add or retain `index.php`, `index.htm`, duplicate HTML entrypoints, copied release WebUI directories, or generated WebUI files anywhere in the shipped source tree. Local preview must use the canonical source tree or the real WebServer/package path; opening an extracted file from an old release is not evidence about the current repository. Before a release, the packaging contract and executable runtime-layout test must prove that the archive contains exactly the canonical WebUI files and no legacy entrypoint. Build outputs, extracted release folders, and temporary local preview copies must remain ignored and must never be committed as runtime assets.

## Documentation localization contract

The built in user-facing language set is fixed to English, Türkçe, 简体中文, Español, Deutsch, Русский, Bahasa Indonesia, हिन्दी, and العربية unless the supported WebUI locale set is intentionally changed in the same work.

English is the canonical technical documentation language. User-facing documentation includes `README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `DONATE.md`, `LANGUAGES.md`, `LOG.md`, `THEME.md`, and Markdown documents directly under `docs/`.

- Localized project overviews live in `README.<locale>.md`.
- Localized documentation references live in `docs/i18n/<locale>.md` and use stable English anchor IDs.
- Every canonical user-facing Markdown document must expose the same nine-language navigation where that navigation already exists.
- `CHANGELOG.md` is the release-log exception: keep release entries canonical English only by default; do not duplicate them into `docs/i18n/` or localized README files unless the maintainer explicitly requests localized release notes.
- Except for `CHANGELOG.md` release entries, when a user-visible Markdown document changes materially, update the matching sections in all localized references and update localized README files when the project overview changes.
- Preserve code symbols, API names, config keys, commands, filenames, security behavior, and numeric limits exactly inside translations.
- Do not localize source code, build files, CI configuration, generated files, or internal agent/developer instructions. Those remain English for deterministic tooling and review.

## Native / TEE regression guardrails

Native runtime health, Binder behavior, TEE timing, and attestation state are release-critical. Agents must treat regressions in these areas as blockers rather than compatibility quirks.

- When KernelSU/APatch exploratory or release-certification device tests are explicitly run, require `native_state=active`, `native_alive=true`, and the expected interceptors to activate when their features are enabled. A yellow-to-red runtime-health transition is a blocker for that certification run, but routine PRs rely on the automated host/emulator contract above.
- TEE timing-side-channel checks must stay below the project threshold of `1.1x`. A positive result at or above the threshold must be investigated before merge/release; do not suppress the warning or relax the threshold to make a build pass.
- Treat bootloader / Verified Boot / attestation-state regressions as release blockers. Do not trade attestation correctness for permissive spoofing or compatibility shortcuts.
- Mount-namespace differences may change device/inode identity between processes. Keep canonical platform-location matching plus fail-closed ELF ABI/build-ID validation; never fall back to same-basename-only symbol resolution.
- Binder/native hot paths are performance-sensitive. Avoid new per-call syscalls, unbounded allocations, repeated parsing, or expensive zeroization in hot paths without measurement. Keep CPU, RSS, Binder latency, and TEE latency close to the last known-good baseline.
- Preserve fail-closed Binder FD classification, bounded parser limits, coherent transaction writeback, ptrace signal handling, pointer-log redaction, temporary-buffer wiping, and cleanup behavior.
- Future Android API support must be validated against the actual compiled Binder UAPI/layout. Struct size or API number alone is not sufficient proof of compatibility.
- Changes touching ptrace, Binder, process memory, FD transfer, symbol resolution, TEE/attestation behavior, or boot/Verified Boot state need targeted regression tests.

## WebUI localization release guardrails

- Every first-party user-visible string added to the English catalog must be added to all built-in locales in the same change, including dynamic messages, dialogs, placeholders, errors, progress states, and accessibility labels.
- Non-English locales must not silently fall back to English for first-party UI text, except intentionally untranslated technical identifiers or protocol names.
- Keep all locale catalogs at identical key coverage and retain automated full-catalog localization tests.

## Merge / release verification

- Before merge, require the Build and Security Regression workflows to pass and resolve actionable Codex/review findings, unless the repository owner/maintainer explicitly overrides that requirement in the current instruction. Never infer an override from urgency or confidence.
- When device-bound Android/runtime paths change, the Security Regression workflow's exact-head Android emulator/platform contract is part of the required merge evidence. Do not substitute a manual device-smoke checkbox for a missing or failing automated platform contract.
- When native/module paths are selected, Native Hardening and the module artifact build are part of the required final-head verification unless explicitly overridden by the maintainer.
- For release candidates, verify module archive structure, checksums, native artifact hardening, signed APK verification, and any explicitly requested release-certification device checks.
- Do not update `update.json`, release URLs, hashes, or release metadata until the release artifact actually exists and those values are verified from the published artifact.

## Branch Lifecycle Rules

Feature, fix, experiment, and AI-generated branches are temporary and must not be kept after their work is integrated.

- After a pull request is successfully merged into `master`, delete its source branch immediately.
- Remove stale branches whose changes have already been merged into `master`.
- Do not delete `master` or any branch that still contains unmerged work.
- Keep a merged branch only when there is an explicit, documented reason for it to remain long-lived.

## AI Agent General Bug Prevention Contract

AI agents must optimize for durable correctness, not only for making the current issue disappear.

### Generalize every failure

When a bug, review finding, regression, or unexpected behavior is discovered:

1. Identify the violated invariant, not only the failing line.
2. Determine why the system allowed that invalid state to exist.
3. Search for every equivalent path that can produce, transform, cache, persist, or consume the same type of state.
4. Encode the general rule through implementation, tests, and documentation where appropriate.

Do not add narrow instructions that only describe the exact bug location. A fixed line is temporary; a protected invariant prevents future classes of bugs.

### Root cause over symptom fixes

Agents must not optimize for the smallest textual patch.

Before changing code:

- Understand the lifecycle of the affected data/object/state.
- Trace producers and consumers.
- Check initialization, refresh, retry, cancellation, failure, recovery, and cleanup paths.
- Check alternate implementations and compatibility paths.
- Verify assumptions against the current repository state.

A patch is incomplete if another valid execution path can still violate the same invariant.

### State and failure handling

Treat failure states as real states.

Agents must carefully review:

- partial initialization,
- partial writes,
- interrupted operations,
- stale cache entries,
- invalid persisted state,
- retries,
- concurrent updates,
- lifecycle recreation,
- process restarts,
- rollback paths.

Do not silently convert unknown, failed, unavailable, or unverified states into normal success values.

Avoid unsafe fallbacks such as:

- empty strings,
- zero identifiers,
- default objects,
- fake timestamps,
- placeholder hashes,
- previous known values,

unless collision analysis proves they cannot be confused with valid data.

### Async and concurrency reasoning

For asynchronous or stateful code, review adversarial sequences:

- fail → fail,
- fail → success → fail,
- cancel → restart,
- concurrent replacement,
- duplicate events,
- stale callbacks,
- delayed responses,
- retry after partial completion.

Verify both:

1. the immediate result;
2. the state left behind for the next operation.

A correct output with corrupted future state is still a bug.

### Boundary and security reasoning

Every boundary requires explicit validation.

Review:

- external input,
- files,
- archives,
- IPC/Binder,
- APIs,
- native interfaces,
- serialization,
- encryption/signing,
- caches,
- permissions.

Do not trust:

- filenames,
- timestamps,
- lengths,
- metadata,
- object identity,
- cached assumptions,

as proof that data is still valid.

Validate the object actually consumed, not only the object previously inspected.

### Tests must prove the invariant

A regression test should:

1. Fail before the fix.
2. Pass after the fix.
3. Protect the general behavior, not only the original example.

When fixing a bug, consider adding tests for:

- invalid input,
- repeated execution,
- recovery,
- cancellation,
- concurrency,
- corrupted state,
- boundary values,
- cleanup behavior.

### Final review before completion

Before declaring work complete:

- Inspect the complete diff.
- Remove unrelated changes.
- Search for remaining bypasses.
- Verify no duplicate workaround was introduced.
- Confirm tests cover the changed invariant.
- Confirm the implementation matches the intended architecture.

A successful build does not prove correctness.

A successful fix is one where the same category of failure becomes harder or impossible to reintroduce.
