# Changelog

## V2.5.7

- Restored native activation on KernelSU/mount-namespace devices with fail-closed platform image and ELF identity validation.
- Fixed intermittent Binder descriptor-cache collision and call-site churn that could cause inconsistent native timing overhead.
- Added regression coverage for alternating Binder exchange sites, descriptor collisions, and reused descriptor identities.
- Added stage-specific native activation diagnostics and strengthened TEE/native regression guardrails.
- Completed all built-in WebUI locales with full first-party catalog coverage and localization regression tests.
