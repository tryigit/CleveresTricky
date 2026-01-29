## 2026-01-28 - [Data Leak in XML Parsing Exceptions]
**Vulnerability:** The application was logging the full `Throwable` object when XML parsing failed. In `XmlPullParserException`, the exception message often contains a snippet of the XML content where the error occurred. Since `keybox.xml` contains private keys, this could leak sensitive data to the logs.
**Learning:** Standard exception logging (`Logger.e(msg, t)`) is dangerous when processing sensitive data files with parsers that include content in error messages.
**Prevention:** Catch exceptions during sensitive data parsing and log generic error messages or sanitized exception types (e.g., `t.getClass().getSimpleName()`) instead of the full exception object.

## 2024-05-23 - [CRITICAL] Unsecured Sensitive Configuration Storage
**Vulnerability:** The configuration directory `/data/adb/tricky_store/` and sensitive files like `keybox.xml` (containing private keys) were created with default permissions (likely 755/644), making them readable by any app on the device.
**Learning:** Default filesystem operations (`mkdir`, `cp`) in Android/Linux usually respect umask (022 for root), resulting in world-readable files. Sensitive data must always have explicit permission hardening.
**Prevention:** Always use `chmod 700` for directories and `chmod 600` for files containing secrets immediately after creation. Enforce this in both installation scripts (`customize.sh`) and runtime initialization (Java/Kotlin using `Os.chmod`).
