# Security Policy

CleveresTricky handles Android system integration, Binder-facing code, attestation and KeyMint-related compatibility paths, encrypted storage, keybox data, native components, and a local WebUI. Security reports are therefore treated as a higher priority than ordinary bug reports.

## Supported versions

Security fixes are targeted at the current `V2.6.x` release and development line. Users should upgrade to the newest release available from the official repository before reporting an issue that may already be fixed. Older release lines are not guaranteed to receive security fixes.

| Version line | Security support |
|---|---|
| `V2.6.x` | Supported; security fixes are evaluated against the current release and `master`. |
| `master` | Supported as the active development branch, subject to change before release. |
| Older versions | Best effort only; upgrade first whenever possible. |

## Reporting a vulnerability

Please report suspected vulnerabilities privately through GitHub's **Security and quality → Reporting → Report a vulnerability** flow when private vulnerability reporting is available for this repository. This creates a confidential report for the maintainers and is preferred over a public issue. See GitHub's [private vulnerability reporting guidance](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability).

Do **not** disclose exploit details, credentials, keyboxes, attestation material, access tokens, private keys, device identifiers, or sensitive logs in a public issue, pull request, commit, chat message, or release comment. If the private reporting option is unavailable, open a minimal public issue asking for a private security contact without including the vulnerability details or proof of concept.

A useful confidential report should include the affected release or commit, the affected component and entry point, the Android version and device context when relevant, exact reproduction steps, the security impact and attacker assumptions, a minimal proof of concept that contains no real secrets, and any suggested mitigation. Please redact or replace all production key material, keyboxes, tokens, user data, and device-specific identifiers before submission.

## What to expect

The maintainers will validate the report against the current source and supported Android scope, determine whether the behavior is reachable in a shipped configuration, and coordinate a fix or mitigation when appropriate. CodeQL, dependency, fuzzing, or static-analysis findings are reviewed against the actual data flow; a warning is not closed by weakening production security controls or by changing secure code solely to silence a report.

Please allow the maintainers time to investigate before public disclosure. Coordinate any public advisory, release note, or proof-of-concept publication with the maintainers so that users have a reasonable opportunity to update.

## Security boundaries and project expectations

CleveresTricky follows a fail-closed model. Binder parcels, XML, ZIP/CBOX data, HTTP responses, file paths, process identifiers, WebUI input, and native interfaces must be treated as untrusted. Size, count, allocation, parsing, archive, and cryptographic operations must remain explicitly bounded. Sensitive buffers should be cleared on success, failure, and early-return paths where the surrounding implementation provides wiping semantics.

The project supports Android 12 through 17 and KernelSU/APatch integration as documented by the repository. Userspace compatibility behavior must not be described as manufacturing hardware-backed integrity. Hardware-backed attestation, TEE, StrongBox, KeyMint, Verified Boot, and RKP claims must be verified against the actual platform behavior.

Never commit private keys, keyboxes, access tokens, device secrets, generated release APKs, or module ZIPs. Release files and update metadata must be derived from the exact verified build artifact; an existing release must not be silently replaced with unrelated hashes or binaries.

## Security-related contributions

Security fixes should include a focused regression test for the violated invariant, cover failure and malformed-input paths, and preserve the repository's bounds, fail-closed behavior, and warning-as-error checks. Pull requests should describe the security impact, affected trust boundary, test evidence, and any remaining platform-specific limitations without publishing sensitive material.

## References

- [GitHub: Adding a security policy to your repository](https://docs.github.com/code-security/getting-started/adding-a-security-policy-to-your-repository)
- [GitHub: Privately reporting a security vulnerability](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
