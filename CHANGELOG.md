# Changelog

## V2.8.3

- **Remote Server Keybox Breakdown:** Added detailed stats badges for configured remote servers in WebUI, displaying the exact counts of fetched keyboxes broken down by type (`Keybox`, `CBOX`, `RKP`, `RSA`) in a clean, non-disclosing badge layout without exposing private certificate names or identifiers.
- **Dynamic KeyboxHub Recommendation:** Smart detection of KeyboxHub servers (`keybox.tryigit.dev`). The recommendation banner is automatically hidden when KeyboxHub is already added, and dynamically reappears if the server is removed.
- **Enhanced Remote Server Architecture:** Robust server response tracking and state synchronization across live fetch, cached reloads, and server deactivation.
