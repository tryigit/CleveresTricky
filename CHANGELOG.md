# Changelog

## V2.7.4

- **Key Attestation & Security:**
  - Fixed hardware attestation failures in modern banking, security, and verification apps by correctly preserving multi-level app attestation key chains.
  - Seamless StrongBox support: apps requesting StrongBox now fall back to standard TEE automatically when no StrongBox keybox is loaded, preventing app freezes and duplicate key errors.
  - Smarter keybox selection: automatically matches and prioritizes StrongBox and TEE keyboxes so the correct certificates are always used.
- **WebUI & User Experience:**
  - Added clear, mobile-friendly **StrongBox** and **TEE** badges next to keyboxes in the Keybox Hub, making it easy to identify keybox capabilities at a glance.
  - Localized remote server status messages for all supported interface languages.
- **Module Installation & Compatibility:**
  - Automatically detects and removes conflicting or outdated Play Integrity Fix modules during installation to prevent conflicts and ensure a clean setup.
- **Performance & Reliability:**
  - Faster keystore response times with zero delay when applications check certificates.
  - Improved background stability to ensure smooth operation without false alarms.
