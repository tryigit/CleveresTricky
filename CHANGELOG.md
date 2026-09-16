# Changelog

## V2.8.2

- **Remote Server Keybox Breakdown:** Added detailed stats badges for configured remote servers in WebUI, displaying the exact counts of loaded remote keyboxes (including fetched and validated cached keyboxes) broken down by type (`Keybox`, `CBOX`, `RKP`, `RSA`) in a clean, non-disclosing badge layout without exposing private certificate names or identifiers.
- **Dynamic KeyboxHub Recommendation:** Smart detection of KeyboxHub servers (`keybox.tryigit.dev`). The recommendation banner is automatically hidden when KeyboxHub is already added, and dynamically reappears if the server is removed.
- **Enhanced Remote Server Architecture:** Robust server response tracking and state synchronization across live fetch, cached reloads, and server deactivation.
- **WebUI TEE Badge & Layout Refinements:** Restored explicit `TEE` security badge rendering for keyboxes without RKP protection. Improved visual breathing room between security/algorithm badges and certificate/scope metadata rows in stored keyboxes and verification cards.
- **Remote Server Refresh Interval & Status Localization:** Display configured refresh interval badge directly in the remote server header. Added multilingual localization support for remote server `OK` status badges.
