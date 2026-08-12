from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


index_path = ROOT / 'module/template/webroot/index.html'
bridge_path = ROOT / 'module/template/webroot/bridge.js'
policy_path = ROOT / 'module/template/webroot/policy.js'
ux_path = ROOT / 'module/template/webroot/ux.js'
ux_base_path = ROOT / 'module/template/webroot/ux-base.js'
service_test_path = ROOT / 'service/src/test/java/cleveres/tricky/cleverestech/WebServerUXTest.kt'
bridge_base_test_path = ROOT / 'module/webui-tests/bridge-base.test.js'
bridge_test_path = ROOT / 'module/webui-tests/bridge.test.js'

# Remove the retired Identity Controls surface. Feature Center is the single owner of
# feature switches, so the Identity page must not render a second set of controls.
index = index_path.read_text()
identity_start = '        <div class="panel">\n<h3>Identity Controls</h3>'
identity_end = '<div class="scope-note" style="margin:0;">These switches affect identity only. Bootloader/verified-boot property hiding and Keystore/TEE certificate protection are always-on core features.</div>\n        </div>'
start = index.find(identity_start)
if start < 0:
    raise SystemExit('Identity Controls panel start not found')
end = index.find(identity_end, start)
if end < 0:
    raise SystemExit('Identity Controls panel end not found')
index = index[:start] + index[end + len(identity_end):]

# All remaining user-facing static switches use the current component class. This
# includes Remote Servers > Automatic refresh.
if 'class="toggle"' not in index:
    raise SystemExit('Expected legacy static switch markup before modernization')
index = index.replace('class="toggle"', 'class="ct-switch"')
index = replace_once(index, '<script src="bridge.js?revision=6"></script>', '<script src="bridge.js?revision=7"></script>', 'bridge cache revision')
index = replace_once(index, '<script src="policy.js?revision=3"></script>', '<script src="policy.js?revision=4"></script>', 'policy cache revision')
if '<h3>Identity Controls</h3>' in index or 'class="toggle"' in index:
    raise SystemExit('Retired Identity Controls/static toggle markup survived cleanup')
index_path.write_text(index)

# Community belongs to Dashboard, never to document.body. Keeping it inside the page
# makes the existing content safe-area padding protect it from the fixed bottom nav.
bridge = bridge_path.read_text()
bridge = replace_once(
    bridge,
    "        if (!document || !document.body || document.getElementById('cleveresCommunityCard')) return;\n\n        const card = document.createElement('section');",
    "        if (!document || !document.body || document.getElementById('cleveresCommunityCard')) return;\n        const dashboard = document.getElementById('dashboard');\n        if (!dashboard) return;\n\n        const card = document.createElement('section');",
    'community Dashboard owner',
)
bridge = replace_once(
    bridge,
    "        card.style.cssText = 'box-sizing:border-box;max-width:800px;margin:0 auto;padding:0 20px max(28px,env(safe-area-inset-bottom));text-align:center;';",
    "        card.style.cssText = 'box-sizing:border-box;width:100%;margin:20px 0 24px;padding:0;text-align:center;';",
    'community card spacing',
)
bridge = replace_once(bridge, '        document.body.appendChild(card);', '        dashboard.appendChild(card);', 'community append target')
bridge = replace_once(bridge, "        script.src = 'ux.js?revision=4';", "        script.src = 'ux.js?revision=5';", 'UX loader revision')
bridge = replace_once(bridge, '        revision: 6,', '        revision: 7,', 'bridge revision')
bridge_path.write_text(bridge)

ux = ux_path.read_text()
ux = replace_once(ux, "    script.src = 'ux-base.js?revision=4';", "    script.src = 'ux-base.js?revision=5';", 'UX base revision')
ux_path.write_text(ux)

# Policy owns modern feature switches. Remove stale legacy Identity rows when a cached
# older index is encountered, and move keybox count out of Dashboard into Keyboxes.
policy = policy_path.read_text()
policy = replace_once(
    policy,
    "  ['spoof_enabled','global_mode','rkp_passthrough','drm_passthrough'].forEach(id => {",
    "  ['spoof_enabled','spoof_build_identity','random_on_boot','spoof_region_cn','telephony','global_mode','rkp_passthrough','drm_passthrough'].forEach(id => {",
    'legacy switch cleanup list',
)
policy = replace_once(
    policy,
    "  const stale = document.getElementById('ct_resources_controls');\n  if (stale) stale.remove();\n}",
    "  const spoof = document.getElementById('spoof');\n  if (spoof) {\n    [...spoof.querySelectorAll('.panel')].forEach(panel => {\n      const title = (panel.querySelector('h3')?.textContent || '').trim();\n      if (/^Identity Controls$/i.test(title)) panel.remove();\n    });\n  }\n  const stale = document.getElementById('ct_resources_controls');\n  if (stale) stale.remove();\n}",
    'cached Identity Controls cleanup',
)
policy = replace_once(
    policy,
    "    panel.innerHTML = '<h3>Feature Center</h3><div class=\"scope-note\">Main controls are here. Parent features reveal only the settings that belong to them.</div><div id=\"keyboxStatus\" class=\"ct-keybox-summary\">Loading keybox state...</div><div class=\"ct-control-host\"></div>';",
    "    panel.innerHTML = '<h3>Feature Center</h3><div class=\"scope-note\">Main controls are here. Parent features reveal only the settings that belong to them.</div><div class=\"ct-control-host\"></div>';",
    'Dashboard keybox summary removal',
)
policy = replace_once(
    policy,
    "  } else if (!document.getElementById('keyboxStatus')) {\n    const status = document.createElement('div');\n    status.id = 'keyboxStatus';\n    status.className = 'ct-keybox-summary';\n    status.textContent = 'Loading keybox state...';\n    document.getElementById('ct_dashboard_controls').querySelector('.ct-control-host')?.before(status);\n  }\n}",
    "  }\n  const keysPage = document.getElementById('keys');\n  if (keysPage && !document.getElementById('keyboxStatus')) {\n    const statusPanel = document.createElement('div');\n    statusPanel.id = 'ct_keybox_status_panel';\n    statusPanel.className = 'panel';\n    statusPanel.innerHTML = '<h3>Keybox Status</h3><div id=\"keyboxStatus\" class=\"ct-keybox-summary\" aria-live=\"polite\">Loading keybox state...</div>';\n    keysPage.prepend(statusPanel);\n  }\n}",
    'Keyboxes status relocation',
)
policy_path.write_text(policy)

# Localization remains local-only. Put its selector directly after Feature Center so it
# is discoverable, document the extension contract, and use the modern switch for the
# dynamically-created Debug Logging control.
ux_base = ux_base_path.read_text()
ux_base = replace_once(
    ux_base,
    '    const SUPPORTED = [',
    '    // To add a locale: append [locale, displayName] here, add TRANSLATIONS[locale],\n    // add GUIDE[locale] when a localized guide is available, then run module/webui-tests.\n    const SUPPORTED = [',
    'locale contributor contract',
)
ux_base = replace_once(
    ux_base,
    '#cleveresCommunityCard { box-sizing: border-box; margin: 20px 0 max(18px, env(safe-area-inset-bottom)) !important; width: 100%; }',
    '#cleveresCommunityCard { box-sizing: border-box; margin: 20px 0 24px !important; width: 100%; }',
    'community presentation spacing',
)
ux_base = replace_once(
    ux_base,
    '<input id="ct_debug_logging_toggle" class="toggle" type="checkbox">',
    '<input id="ct_debug_logging_toggle" class="ct-switch" type="checkbox">',
    'Debug Logging modern switch',
)
ux_base = replace_once(
    ux_base,
    "        const configPanel = document.getElementById('backupPw')?.closest('.panel');\n        if (configPanel && configPanel.parentElement === dashboard) {\n            if (configPanel.nextElementSibling !== panel) dashboard.insertBefore(panel, configPanel.nextSibling);\n        } else if (panel.parentElement !== dashboard) {\n            dashboard.appendChild(panel);\n        }",
    "        const featureCenter = document.getElementById('ct_dashboard_controls');\n        const configPanel = document.getElementById('backupPw')?.closest('.panel');\n        if (featureCenter && featureCenter.parentElement === dashboard) {\n            if (featureCenter.nextElementSibling !== panel) dashboard.insertBefore(panel, featureCenter.nextSibling);\n        } else if (configPanel && configPanel.parentElement === dashboard) {\n            if (configPanel.nextElementSibling !== panel) dashboard.insertBefore(panel, configPanel.nextSibling);\n        } else if (panel.parentElement !== dashboard) {\n            dashboard.appendChild(panel);\n        }",
    'language selector placement',
)
ux_base_path.write_text(ux_base)

# Service contract now forbids the duplicate Identity surface and old switch class.
service_test = service_test_path.read_text()
service_test = replace_once(service_test, '        val legacySettings =', '        val retiredIdentitySettings =', 'service retired settings name')
service_test = replace_once(service_test, '        val monitoredSettings = legacySettings + featureCenterSettings', '        val monitoredSettings = retiredIdentitySettings + featureCenterSettings', 'service monitored settings')
service_test = replace_once(service_test, '        assertTrue(html.contains("<script src=\\"bridge.js?revision=6\\"></script>"))', '        assertTrue(html.contains("<script src=\\"bridge.js?revision=7\\"></script>"))', 'service bridge cache contract')
service_test = replace_once(service_test, '        assertTrue(html.contains("<script src=\\"policy.js?revision=3\\"></script>"))', '        assertTrue(html.contains("<script src=\\"policy.js?revision=4\\"></script>"))', 'service policy cache contract')
service_test = replace_once(
    service_test,
    '''        legacySettings.forEach { setting ->
            assertTrue("Missing synchronized legacy control for $setting", html.contains("data-setting=\\"$setting\\""))
            assertTrue("Missing source-aware toggle for $setting", html.contains("toggle('$setting', this)"))
        }
''',
    '''        retiredIdentitySettings.forEach { setting ->
            assertFalse("Retired Identity toggle must not be rendered for $setting", html.contains("data-setting=\\"$setting\\""))
            assertFalse("Retired source-aware toggle must not be rendered for $setting", html.contains("toggle('$setting', this)"))
        }
        assertFalse("Retired Identity Controls panel must not be rendered", html.contains("<h3>Identity Controls</h3>"))
        assertFalse("Legacy toggle class must not be rendered", html.contains("class=\\"toggle\\""))
        assertTrue("Remote Server automatic refresh must use the modern switch", html.contains("class=\\"ct-switch\\" id=\\"srvAutoRefresh\\""))
''',
    'service legacy switch assertions',
)
service_test_path.write_text(service_test)

# Bridge compatibility fixture now models a real Dashboard and rejects body-global card
# placement, so this overlap regression cannot silently return.
bridge_base = bridge_base_test_path.read_text()
bridge_base = replace_once(
    bridge_base,
    "    const body = createElement('body');\n    return {",
    "    const body = createElement('body');\n    const dashboard = createElement('div');\n    dashboard.id = 'dashboard';\n    body.appendChild(dashboard);\n    return {",
    'bridge test Dashboard fixture',
)
bridge_base = replace_once(
    bridge_base,
    "    assert.strictEqual(communityDocument.body.children.at(-1), communityCard, 'Community card must stay at the bottom');",
    "    const communityDashboard = communityDocument.getElementById('dashboard');\n    assert.strictEqual(communityDashboard.children.at(-1), communityCard, 'Community card must stay at the bottom of Dashboard');\n    assert.ok(!communityDocument.body.children.includes(communityCard), 'Community card must never be a global body-level widget');",
    'community ownership contract',
)
bridge_base = replace_once(bridge_base, 'assert.match(uxSource, /ux-base\\.js\\?revision=4/);', 'assert.match(uxSource, /ux-base\\.js\\?revision=5/);', 'UX base test revision')
bridge_base = replace_once(bridge_base, 'assert.ok(indexSource.includes(\'<script src="bridge.js?revision=6"></script>\'));', 'assert.ok(indexSource.includes(\'<script src="bridge.js?revision=7"></script>\'));', 'bridge index cache test')
bridge_base = replace_once(bridge_base, 'assert.match(bridgeSource, /ux\\.js\\?revision=4/);', 'assert.match(bridgeSource, /ux\\.js\\?revision=5/);', 'bridge UX loader test')
bridge_base = replace_once(
    bridge_base,
    '    assert.match(uxBaseSource, /ct_language_panel/);',
    '    assert.match(uxBaseSource, /ct_language_panel/);\n    assert.match(uxBaseSource, /To add a locale:/);\n    assert.match(uxBaseSource, /const featureCenter = document\\.getElementById\\(\'ct_dashboard_controls\'\\)/);',
    'language extension tests',
)
bridge_base_test_path.write_text(bridge_base)

bridge_test = bridge_test_path.read_text()
bridge_test = replace_once(bridge_test, 'assert.match(loaderSource, /ux-base\\.js\\?revision=4/);', 'assert.match(loaderSource, /ux-base\\.js\\?revision=5/);', 'loader revision test')
bridge_test = replace_once(bridge_test, 'assert.match(indexSource, /policy\\.js\\?revision=3/);', 'assert.match(indexSource, /policy\\.js\\?revision=4/);', 'policy revision test')
bridge_test = replace_once(bridge_test, 'assert.match(indexSource, /bridge\\.js\\?revision=6/);', 'assert.match(indexSource, /bridge\\.js\\?revision=7/);', 'bridge revision test')
bridge_test = replace_once(
    bridge_test,
    "assert.ok(!policySource.includes('ct_community_slot'), 'Policy must not structurally relocate the community card');",
    "assert.ok(!policySource.includes('ct_community_slot'), 'Policy must not create a duplicate community slot');\nassert.ok(!indexSource.includes('<h3>Identity Controls</h3>'), 'Retired Identity Controls panel must stay removed');\nassert.ok(!indexSource.includes('class=\\\"toggle\\\"'), 'Legacy toggle class must stay removed from static WebUI markup');\nassert.match(indexSource, /class=\\\"ct-switch\\\" id=\\\"srvAutoRefresh\\\"/);\nassert.match(policySource, /ct_keybox_status_panel/);",
    'WebUI ownership regression tests',
)
bridge_test_path.write_text(bridge_test)

locales_doc = ROOT / 'module/template/webroot/LOCALES.md'
locales_doc.write_text('''# WebUI locales

CleveresTricky WebUI translations are local-only and live in `ux-base.js`; switching language never requires network access.

## Add a language

1. Add `[locale, displayName]` to `SUPPORTED` in `ux-base.js`.
2. Add a `TRANSLATIONS[locale]` catalog for visible UI strings. English remains the fallback for missing strings.
3. Add `GUIDE[locale]` when a localized guide is available; otherwise the English guide is used.
4. Keep RTL locales covered by the `html[dir="rtl"]` rules and extend the direction rule if another RTL locale is added.
5. Run `node --check module/template/webroot/ux-base.js` and `node module/webui-tests/bridge.test.js` before opening a PR.

The language selector is rendered on Dashboard immediately after Feature Center, and the chosen locale is persisted in `localStorage` under `cleverestricky.language.v1`.
''')

# Source-level invariants before the executable tests run.
if 'document.body.appendChild(card);' in bridge or 'dashboard.appendChild(card);' not in bridge:
    raise SystemExit('Community card ownership invariant failed')
if '<h3>Identity Controls</h3>' in index or 'class="toggle"' in index:
    raise SystemExit('Legacy static control invariant failed')
if 'class="ct-switch" id="srvAutoRefresh"' not in index:
    raise SystemExit('Remote Servers modern switch invariant failed')
if 'ct_keybox_status_panel' not in policy:
    raise SystemExit('Keybox status relocation invariant failed')
if '<div id="keyboxStatus" class="ct-keybox-summary">Loading keybox state...</div><div class="ct-control-host">' in policy:
    raise SystemExit('Dashboard still owns keybox status')
if "const featureCenter = document.getElementById('ct_dashboard_controls');" not in ux_base:
    raise SystemExit('Language selector placement invariant failed')
if 'class="toggle" type="checkbox"' in ux_base:
    raise SystemExit('Dynamically-created legacy toggle survived cleanup')

print('WebUI cleanup patch applied with all source invariants satisfied')
