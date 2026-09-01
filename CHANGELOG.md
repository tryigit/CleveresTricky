# Changelog

## V2.7.2

- **Auto Identity & App Rules:** Added per-app Auto Identity (Pixel Beta) support to App Rules (`app_config`), allowing automatic daily identity rotation to be configured individually per target application. Enforced strict Global Identity Mode property isolation so system properties are never modified device-wide when Global Mode is disabled.
- **WebUI & Mobile Usability:** Improved readability with a 16px base font and responsive mobile layouts, refined touch targets, fixed translation fallbacks (`noServers` / server lists), and added automatic system and browser locale detection across all supported languages.
- **Multi-User & DRM Privacy:** Enhanced DRM privacy handling for secondary Android users and work profiles, binding DRM plugin registrations accurately to the calling application UID.
- **Keybox & Attestation Stability:** Fixed large decimal serial number parsing in `KeyboxVerifier` to prevent false certificate revocation warnings, and added safety confirmation warnings before manual action execution.
- **Performance:** Optimized identity lookup performance using sorted binary search and eliminated redundant memory allocations in file filtering and background polling loops.
