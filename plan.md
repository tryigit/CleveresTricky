1. **Apply iOS UI Design Changes to `index.html`**
   - Apply CSS patches to `module/template/webroot/index.html` using a python script via `run_in_bash_session` to:
     - Update CSS variables in `:root` to strictly match iOS colors (black/white/gray themes).
     - Fix the logo `h1` styling (remove `::before` pseudo-element completely, adjust font, letter-spacing, text-transform).
     - Adjust the `.tabs` bottom bar CSS for mobile to float with padding and margins, fixing the overlapping issue (the "sliding" submenu issue).
2. **Verify changes to `index.html`**
   - Run `cat module/template/webroot/index.html | grep -B 5 -A 10 ":root"` to verify CSS.
   - Run `cat module/template/webroot/index.html | grep -B 5 -A 10 ".tabs {"` to verify bottom tab padding.
3. **Remove the Legacy "Guide" Tab and obsolete language settings label**
   - Use python scripting via `run_in_bash_session` to remove the `<div id="guide">` content (lines 660-692 approx) and `tab_guide` div from `module/template/webroot/index.html`, and delete translation keys from lines 2475 and 2622.
   - Use python scripting via `run_in_bash_session` to replace `<label for="ct_language_selector" style="flex:1">Language</label>` with an empty string in `module/template/webroot/ux.js` to prevent text wrapping.
4. **Verify removal of Guide Tab and obsolete label**
   - Run `cat module/template/webroot/index.html | grep "tab_guide"` to ensure it's empty.
   - Run `cat module/template/webroot/ux.js | grep "Language"` to verify label removal.
5. **Fix WebServer APatch Detection**
   - Use `sed -i 's/\/data\/adb\/apatch/\/data\/adb\/apatch").exists() || File("\/data\/adb\/ap/g' service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt` via `run_in_bash_session` to check `/data/adb/ap` as well.
6. **Verify WebServer APatch Detection fix**
   - Run `cat service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt | grep "/data/adb/ap"` to ensure fix is present.
7. **Remove Unused Legacy JavaScript**
   - Use python scripting via `run_in_bash_session` to delete the function `removeLegacySurfaces()` and its calls from `module/template/webroot/policy.js`.
   - Use python scripting via `run_in_bash_session` to cleanly replace usages of `legacyConfig` with equivalent fallback states or remove it entirely from `module/template/webroot/policy.js`.
8. **Verify Unused Legacy JavaScript removal**
   - Run `cat module/template/webroot/policy.js | grep "removeLegacySurfaces"` and `cat module/template/webroot/policy.js | grep "legacyConfig"` to verify they are gone.
9. **Run Checks and Tests**
   - Execute `./gradlew test ktlintCheck` via `run_in_bash_session` to verify kotlin changes.
   - Execute `node module/template/webroot/canonical-entrypoint.test.js` via `run_in_bash_session` to verify javascript changes.
10. **Pre-commit Steps**
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.
11. **Submit Changes**
   - Use the `submit` tool to finalize the task and present the PR details.
