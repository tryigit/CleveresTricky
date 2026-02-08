## 2025-02-05 - Embedded Web Server Testing
**Learning:** For Android apps with embedded `NanoHTTPD` web servers, you can unit test the HTML generation by instantiating the server class (mocking Android dependencies if needed) and extracting the response body from `serve(session)`. This allows for frontend verification using Playwright on the dumped HTML file without needing an emulator.
**Action:** Use `WebServerHtmlTest` pattern to dump HTML to `/tmp/index.html` for Playwright verification in future tasks.

## 2026-02-06 - Validating Embedded UI with Playwright
**Learning:** When verifying embedded UIs where the HTML is constructed as a raw string in Kotlin/Java, you can extract the HTML string directly (via regex or simple string parsing) to a standalone file. This file can then be served via a simple HTTP server (e.g., `python3 -m http.server`) to allow Playwright verification of structure and accessibility attributes, bypassing the need for a full Android build environment.
**Action:** Use this extraction method for rapid UI verification of embedded servers.

## 2026-02-08 - Mirrored Static Artifacts Drift
**Learning:** When a project maintains a static file (e.g., `index.html`) as a mirror of an embedded string in code (e.g., `WebServer.kt`), they can easily drift out of sync (e.g., missing CSS rules).
**Action:** Always diff the static artifact against the source of truth before editing to detect and resolve drift, ensuring consistent behavior across dev and prod.
