## 2026-01-28 - [Data Leak in XML Parsing Exceptions]
**Vulnerability:** The application was logging the full `Throwable` object when XML parsing failed. In `XmlPullParserException`, the exception message often contains a snippet of the XML content where the error occurred. Since `keybox.xml` contains private keys, this could leak sensitive data to the logs.
**Learning:** Standard exception logging (`Logger.e(msg, t)`) is dangerous when processing sensitive data files with parsers that include content in error messages.
**Prevention:** Catch exceptions during sensitive data parsing and log generic error messages or sanitized exception types (e.g., `t.getClass().getSimpleName()`) instead of the full exception object.

## 2024-05-23 - [CRITICAL] Unsecured Sensitive Configuration Storage
**Vulnerability:** The configuration directory `/data/adb/tricky_store/` and sensitive files like `keybox.xml` (containing private keys) were created with default permissions (likely 755/644), making them readable by any app on the device.
**Learning:** Default filesystem operations (`mkdir`, `cp`) in Android/Linux usually respect umask (022 for root), resulting in world-readable files. Sensitive data must always have explicit permission hardening.
**Prevention:** Always use `chmod 700` for directories and `chmod 600` for files containing secrets immediately after creation. Enforce this in both installation scripts (`customize.sh`) and runtime initialization (Java/Kotlin using `Os.chmod`).

## 2024-05-24 - [Unintended Network Exposure of Local Service]
**Vulnerability:** The internal configuration web server (`WebServer`) was initialized using `NanoHTTPD(port)`, which defaults to binding on all network interfaces (`0.0.0.0`). This exposed the sensitive configuration API and token auth to the local network (e.g., Wi-Fi).
**Learning:** Embedded web servers often default to promiscuous binding. For local IPC or configuration tools, explicit binding to loopback (`127.0.0.1`) is mandatory.
**Prevention:** Always explicitly specify the hostname/IP when initializing network listeners for local services (e.g., `NanoHTTPD("127.0.0.1", port)`).
