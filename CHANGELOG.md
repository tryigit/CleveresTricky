# Changelog

## V2.8.2

- **Keybox Expiration & Time Accuracy:** Resolved keybox expiration calculation discrepancies between stored inventory and verification results. Both now evaluate the earliest expiring certificate in the chain and present accurate expiration dates with hour/minute timestamps in UTC.
- **Enhanced Mobile Card Layout:** Redesigned keybox inventory and verification cards for mobile devices. Badges now cleanly drop to their own dedicated line beneath filenames, preventing crowding, awkward wrapping, and truncation.
- **Clean Monospaced Certificate Serials:** Streamlined long certificate serial numbers into compact, single-line monospaced text to eliminate multiline wrapping glitches.
- **Universal Long-Press Inspection:** Extended touch long-press and keyboard interactions across the interface. Users can now hold or activate any filename, certificate serial, expiration date, scope, or verification detail to open a detailed modal and copy the exact value with haptic feedback.
- **Comprehensive Localization:** Complete multilingual coverage across all 9 supported languages for all updated card elements and popup strings.
