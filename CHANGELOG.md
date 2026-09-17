# Changelog

## V2.8.3

- **WebUI XML Keybox Paste Fix:** Fixed an input reference error (`filenameInput is not defined`) when saving pasted XML keybox content. Automatically defaults to `keybox.xml` (or `rkp.xml` for RKP keyboxes) when the filename field is left blank.
- **Improved Keybox Upload Compatibility:** Added UTF-8 BOM stripping to prevent XML parse errors on upload. Added automatic Turkish character transliteration (e.g. `ğ` -> `g`) so filenames with localized characters are cleanly accepted and saved. Fixed keybox addition so keyboxes can be added and inspected in the verification view regardless of expiration status, while honoring automatic deletion when auto keybox check is enabled.
- **Remote Server TEE Badges:** Replaced the technical `RSA` certificate badge on remote servers with a unified `TEE` security classification badge for all non-RKP keyboxes.
