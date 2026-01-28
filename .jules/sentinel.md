## 2026-01-28 - [Data Leak in XML Parsing Exceptions]
**Vulnerability:** The application was logging the full `Throwable` object when XML parsing failed. In `XmlPullParserException`, the exception message often contains a snippet of the XML content where the error occurred. Since `keybox.xml` contains private keys, this could leak sensitive data to the logs.
**Learning:** Standard exception logging (`Logger.e(msg, t)`) is dangerous when processing sensitive data files with parsers that include content in error messages.
**Prevention:** Catch exceptions during sensitive data parsing and log generic error messages or sanitized exception types (e.g., `t.getClass().getSimpleName()`) instead of the full exception object.
