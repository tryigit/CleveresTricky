from pathlib import Path

policy = Path("service/src/main/java/cleveres/tricky/cleverestech/PolicyState.kt")
text = policy.read_text()
old = """    fun validateStateJson(text: String, validateReferences: Boolean): Result<Unit> =\n        runCatching { parseStateJson(text, validateReferences = validateReferences) }.map { Unit }\n"""
new = """    fun validateStateJson(text: String, validateReferences: Boolean): Result<Unit> =\n        runCatching { parseStateJson(text, validateReferences = validateReferences) }.fold(\n            onSuccess = { Result.success(Unit) },\n            onFailure = { Result.failure(it) },\n        )\n"""
if old not in text:
    raise SystemExit("PolicyState validation block not found")
policy.write_text(text.replace(old, new, 1))

web = Path("module/template/webroot/policy.js")
text = web.read_text()
old = """    const profileFeatureKeys = featureKeys.map(item => item[0]).concat(['securityPatch']);\n    let policyState = null;\n"""
new = """    const securityPatchDescription = 'Controls system, vendor and boot patch authorization resolution independently. Disabled preserves captured genuine patch authorizations.';\n    const profileFeatureKeys = featureKeys.map(item => item[0]).concat(['securityPatch']);\n    let infoCardSequence = 0;\n    let policyState = null;\n"""
if old not in text:
    raise SystemExit("policy.js metadata insertion point not found")
text = text.replace(old, new, 1)
old = """        patchPage.innerHTML = `\n            <div class=\"panel\">\n                <h3>Security Patch</h3>\n                <div class=\"row\"><label for=\"policy_securityPatch\"><strong style=\"color:#fff;\">Security Patch Override</strong><span class=\"res-desc\">Independent from Device / Build Identity. Disabled preserves genuine attestation patch authorizations.</span></label><input type=\"checkbox\" class=\"toggle\" id=\"policy_securityPatch\"></div>\n"""
new = """        patchPage.innerHTML = `\n            <div class=\"panel\">\n                <h3>Security Patch</h3>\n                <div class=\"row\"><label for=\"policy_securityPatch\"><strong style=\"color:#fff;\">Security Patch Override</strong><span class=\"res-desc\">Independent from Device / Build Identity. Disabled preserves genuine attestation patch authorizations.</span></label><input type=\"checkbox\" class=\"toggle\" id=\"policy_securityPatch\"></div>\n"""
if old not in text:
    raise SystemExit("policy.js security patch block not found")
text = text.replace(old, new, 1)
old = """        const oldControls = document.getElementById('spoof_enabled');\n"""
new = """        const securityPatchToggle = document.getElementById('policy_securityPatch');\n        securityPatchToggle.parentNode.insertBefore(makeFeatureInfo('Security Patch Override', securityPatchDescription), securityPatchToggle);\n        const oldControls = document.getElementById('spoof_enabled');\n"""
if old not in text:
    raise SystemExit("policy.js security patch info insertion point not found")
text = text.replace(old, new, 1)
old = """    function renderFeatureControls() {\n"""
new = """    function closeFeatureInfoCards(except) {\n        document.querySelectorAll('.policy-info-card').forEach(card => {\n            if (card === except) return;\n            card.hidden = true;\n            const button = card.parentElement.querySelector('.policy-info-button');\n            if (button) button.setAttribute('aria-expanded', 'false');\n        });\n    }\n\n    function makeFeatureInfo(title, description) {\n        const wrap = document.createElement('span');\n        wrap.style.position = 'relative';\n        wrap.style.flex = '0 0 auto';\n        const button = document.createElement('button');\n        button.type = 'button';\n        button.className = 'policy-info-button';\n        button.textContent = 'i';\n        button.setAttribute('aria-label', `${title} information`);\n        button.setAttribute('aria-expanded', 'false');\n        button.style.width = '30px';\n        button.style.height = '30px';\n        button.style.padding = '0';\n        button.style.borderRadius = '50%';\n        button.style.fontWeight = '700';\n        const card = document.createElement('span');\n        card.className = 'policy-info-card';\n        card.id = `policy_info_${++infoCardSequence}`;\n        card.hidden = true;\n        card.setAttribute('role', 'note');\n        card.style.position = 'absolute';\n        card.style.right = '0';\n        card.style.top = '36px';\n        card.style.width = 'min(300px, calc(100vw - 48px))';\n        card.style.padding = '12px';\n        card.style.border = '1px solid rgba(255,255,255,.16)';\n        card.style.borderRadius = '10px';\n        card.style.background = '#1d1f24';\n        card.style.boxShadow = '0 8px 28px rgba(0,0,0,.35)';\n        card.style.zIndex = '40';\n        const heading = document.createElement('strong');\n        heading.style.display = 'block';\n        heading.style.marginBottom = '6px';\n        heading.textContent = title;\n        const body = document.createElement('span');\n        body.className = 'res-desc';\n        body.style.display = 'block';\n        body.textContent = description;\n        card.append(heading, body);\n        button.setAttribute('aria-controls', card.id);\n        button.onclick = event => {\n            event.preventDefault();\n            event.stopPropagation();\n            const opening = card.hidden;\n            closeFeatureInfoCards(card);\n            card.hidden = !opening;\n            button.setAttribute('aria-expanded', String(opening));\n        };\n        card.onclick = event => event.stopPropagation();\n        wrap.append(button, card);\n        return wrap;\n    }\n\n    function renderFeatureControls() {\n"""
if old not in text:
    raise SystemExit("policy.js feature helper insertion point not found")
text = text.replace(old, new, 1)
old = """            row.append(label, input);\n            container.appendChild(row);\n"""
new = """            row.append(label, makeFeatureInfo(title, description), input);\n            container.appendChild(row);\n"""
if old not in text:
    raise SystemExit("policy.js feature row not found")
text = text.replace(old, new, 1)
old = """        profileFeatureKeys.forEach(key => {\n            const row = document.createElement('div');\n            row.className = 'row';\n            const label = document.createElement('label');\n            label.textContent = key.replace(/([A-Z])/g, ' $1');\n            row.append(label, profileFeatureSelect(key, profile && profile.features ? profile.features[key] : undefined));\n            features.appendChild(row);\n        });\n"""
new = """        profileFeatureKeys.forEach(key => {\n            const row = document.createElement('div');\n            row.className = 'row';\n            const metadata = key === 'securityPatch' ? ['securityPatch', 'Security Patch Override', securityPatchDescription] : featureKeys.find(item => item[0] === key);\n            const label = document.createElement('label');\n            label.textContent = metadata[1];\n            const info = makeFeatureInfo(metadata[1], `${metadata[2]} Inherit follows the base policy.`);\n            row.append(label, info, profileFeatureSelect(key, profile && profile.features ? profile.features[key] : undefined));\n            features.appendChild(row);\n        });\n"""
if old not in text:
    raise SystemExit("policy.js profile feature row not found")
text = text.replace(old, new, 1)
old = """        document.getElementById('policy_profile_import_file').onchange = guard(async () => {\n"""
new = """        document.addEventListener('click', () => closeFeatureInfoCards(null));\n        document.addEventListener('keydown', event => {\n            if (event.key === 'Escape') closeFeatureInfoCards(null);\n        });\n        document.getElementById('policy_profile_import_file').onchange = guard(async () => {\n"""
if old not in text:
    raise SystemExit("policy.js info close listener insertion point not found")
web.write_text(text.replace(old, new, 1))
