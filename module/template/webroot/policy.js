(function (global) {
    'use strict';

    const bridge = global.CleveresBridge;
    if (!bridge) return;

    const featureKeys = [
        ['buildIdentity', 'Device / Build Identity', 'Requires reboot for early-boot Build fields.'],
        ['attestationIdentity', 'Attestation Identity', 'Controls optional attestation identity substitution only.'],
        ['telephonyIdentity', 'Telephony Identity', 'Starts telephony identity work only when needed.'],
        ['regionIdentity', 'Region Identity', 'Controls optional region and hardware-region presentation.'],
        ['identityRefresh', 'Identity Refresh', 'Generates next-boot identity changes only while enabled.']
    ];
    const patchModes = [
        ['device_default', 'Device'],
        ['prop', 'Property'],
        ['manual', 'Manual'],
        ['automatic', 'Automatic'],
        ['no', 'Omit']
    ];
    const securityPatchDescription = 'Controls system, vendor and boot patch authorization resolution independently. Disabled preserves captured genuine patch authorizations.';
    const profileFeatureKeys = featureKeys.map(item => item[0]).concat(['securityPatch']);
    let infoCardSequence = 0;
    let policyState = null;
    let packages = [];
    let selectedProfile = null;

    async function request(path, options) {
        const response = await bridge.fetch(path, options || {});
        if (!response.ok) throw new Error((await response.text()) || `Request failed with ${response.status}`);
        const type = response.headers.get('content-type') || '';
        return type.includes('application/json') ? response.json() : response.text();
    }

    function notifyUser(message, type) {
        if (typeof global.notify === 'function') global.notify(message, type || 'normal');
    }

    function makeTab(id, title, afterId) {
        if (document.getElementById(`tab_${id}`)) return;
        const tabs = document.querySelector('.tabs');
        const after = document.getElementById(`tab_${afterId}`);
        const tab = document.createElement('div');
        tab.className = 'tab';
        tab.id = `tab_${id}`;
        tab.setAttribute('role', 'tab');
        tab.setAttribute('tabindex', '-1');
        tab.setAttribute('aria-selected', 'false');
        tab.setAttribute('aria-controls', id);
        tab.textContent = title;
        tab.onclick = () => global.switchTab(id);
        tab.onkeydown = event => global.handleTabNavigation(event, id);
        if (after && after.nextSibling) tabs.insertBefore(tab, after.nextSibling);
        else tabs.appendChild(tab);
    }

    function makePage(id, afterId) {
        if (document.getElementById(id)) return document.getElementById(id);
        const page = document.createElement('div');
        page.id = id;
        page.className = 'content';
        page.setAttribute('role', 'tabpanel');
        page.setAttribute('aria-labelledby', `tab_${id}`);
        const after = document.getElementById(afterId);
        if (after && after.nextSibling) after.parentNode.insertBefore(page, after.nextSibling);
        else document.body.appendChild(page);
        return page;
    }

    function staticMarkup() {
        makeTab('patch', 'Security Patch', 'spoof');
        makeTab('profiles', 'Profiles', 'patch');
        makeTab('effective', 'Effective State', 'profiles');
        const patchPage = makePage('patch', 'spoof');
        const profilesPage = makePage('profiles', 'patch');
        const effectivePage = makePage('effective', 'profiles');
        patchPage.innerHTML = `
            <div class="panel">
                <h3>Security Patch</h3>
                <div class="row"><label for="policy_securityPatch"><strong style="color:#fff;">Security Patch Override</strong><span class="res-desc">Independent from Device / Build Identity. Disabled preserves genuine attestation patch authorizations.</span></label><input type="checkbox" class="toggle" id="policy_securityPatch"></div>
                <div style="margin-bottom:16px;"><label for="policy_patch_threshold">Automatic age threshold in months</label><input type="number" id="policy_patch_threshold" min="1" max="24" inputmode="numeric"></div>
                <div id="policy_patch_components"></div>
                <button class="primary" id="policy_patch_save" style="width:100%;">Save Security Patch Policy</button>
            </div>
            <div class="panel">
                <h3>Captured / Configured / Effective</h3>
                <div class="scope-note">Captured is the genuine authorization value observed for a request. Configured is the selected policy. Effective is the value CleveresTricky resolves for the selected application.</div>
                <label for="policy_patch_package">Application</label>
                <input type="search" id="policy_patch_package" list="policy_package_list" placeholder="com.example.app" autocomplete="off" spellcheck="false">
                <button id="policy_patch_inspect" style="width:100%; margin-top:10px;">Resolve Patch State</button>
                <div id="policy_patch_resolution" style="margin-top:14px;"></div>
            </div>`;
        profilesPage.innerHTML = `
            <div class="panel">
                <h3>Profiles v2</h3>
                <div class="scope-note">Profiles store configuration and validated references only. Private keybox material is never copied into a profile.</div>
                <div style="display:flex; gap:10px; flex-wrap:wrap; margin-bottom:14px;"><button id="policy_profile_new">New Profile</button><button id="policy_profile_export">Export</button><button id="policy_profile_import">Import</button><input type="file" id="policy_profile_import_file" accept="application/json,.json" style="display:none"></div>
                <div id="policy_profile_list"></div>
            </div>
            <div class="panel" id="policy_profile_editor_panel" style="display:none;">
                <h3>Profile Editor</h3>
                <div class="grid-2"><div><label for="policy_profile_name">Name</label><input type="text" id="policy_profile_name" maxlength="64"></div><div><label for="policy_profile_privacy">Privacy</label><select id="policy_profile_privacy"><option value="inherit">Inherit</option><option value="isolate">Isolate</option><option value="redact">Redact</option></select></div></div>
                <div style="margin-top:12px;"><label for="policy_profile_apps">Application assignments</label><textarea id="policy_profile_apps" rows="3" maxlength="16384" placeholder="One package or wildcard per line"></textarea></div>
                <div class="grid-2"><div><label for="policy_profile_template">Identity template</label><input type="text" id="policy_profile_template" maxlength="64" placeholder="Optional"></div><div><label for="policy_profile_keybox">Keybox reference</label><input type="text" id="policy_profile_keybox" maxlength="128" placeholder="Optional .xml or .cbox reference"></div></div>
                <div class="section-header">Optional feature overrides</div><div id="policy_profile_features"></div>
                <div class="section-header">Security Patch overrides</div><div id="policy_profile_patches"></div>
                <div class="grid-2"><div><label for="policy_profile_rkp">RKP</label><select id="policy_profile_rkp"><option value="inherit">Inherit</option><option value="true">Genuine passthrough</option><option value="false">Compatibility path</option></select></div><div><label for="policy_profile_drm">DRM</label><select id="policy_profile_drm"><option value="inherit">Inherit</option><option value="true">Genuine passthrough</option><option value="false">Configured path</option></select></div></div>
                <div style="display:flex; gap:10px; margin-top:16px; flex-wrap:wrap;"><button class="primary" id="policy_profile_save" style="flex:1;">Save Profile</button><button id="policy_profile_clone" style="flex:1;">Clone</button><button class="danger" id="policy_profile_delete" style="flex:1;">Delete</button></div>
            </div>`;
        effectivePage.innerHTML = `
            <div class="panel">
                <h3>Effective State / Resolution Inspector</h3>
                <div class="scope-note">This view is produced by the same resolver used for runtime policy decisions and never exposes private key material.</div>
                <label for="policy_effective_package">Installed application</label>
                <input type="search" id="policy_effective_package" list="policy_package_list" placeholder="com.example.app" autocomplete="off" spellcheck="false">
                <button id="policy_effective_load" class="primary" style="width:100%; margin-top:10px;">Inspect Effective State</button>
            </div>
            <div class="panel"><h3>Resolved Configuration</h3><div id="policy_effective_result" class="scope-note">Select an application.</div></div>`;
        if (!document.getElementById('policy_package_list')) {
            const list = document.createElement('datalist');
            list.id = 'policy_package_list';
            document.body.appendChild(list);
        }
        const securityPatchToggle = document.getElementById('policy_securityPatch');
        securityPatchToggle.parentNode.insertBefore(makeFeatureInfo('Security Patch Override', securityPatchDescription), securityPatchToggle);
        const oldControls = document.getElementById('spoof_enabled');
        if (oldControls) oldControls.closest('.panel').style.display = 'none';
        const identity = document.getElementById('spoof');
        const featurePanel = document.createElement('div');
        featurePanel.className = 'panel';
        featurePanel.id = 'policy_feature_panel';
        featurePanel.innerHTML = '<h3>Optional Feature Controls</h3><div class="scope-note">Enable only the optional identity paths you need. Core Keystore, genuine hardware key operations, root-of-trust handling and boot compatibility remain independent.</div><div id="policy_feature_controls"></div><button class="primary" id="policy_feature_save" style="width:100%;">Save Optional Features</button>';
        identity.appendChild(featurePanel);
        const dashboard = document.getElementById('dashboard');
        const runtimePanel = document.createElement('div');
        runtimePanel.className = 'panel';
        runtimePanel.id = 'policy_runtime_panel';
        runtimePanel.innerHTML = '<h3>Runtime Components</h3><div id="policy_runtime_state"></div>';
        dashboard.appendChild(runtimePanel);
    }

    function closeFeatureInfoCards(except) {
        document.querySelectorAll('.policy-info-card').forEach(card => {
            if (card === except) return;
            card.hidden = true;
            const button = card.parentElement.querySelector('.policy-info-button');
            if (button) button.setAttribute('aria-expanded', 'false');
        });
    }

    function makeFeatureInfo(title, description) {
        const wrap = document.createElement('span');
        wrap.style.position = 'relative';
        wrap.style.flex = '0 0 auto';
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'policy-info-button';
        button.textContent = 'i';
        button.setAttribute('aria-label', `${title} information`);
        button.setAttribute('aria-expanded', 'false');
        button.style.width = '30px';
        button.style.height = '30px';
        button.style.padding = '0';
        button.style.borderRadius = '50%';
        button.style.fontWeight = '700';
        const card = document.createElement('span');
        card.className = 'policy-info-card';
        card.id = `policy_info_${++infoCardSequence}`;
        card.hidden = true;
        card.setAttribute('role', 'note');
        card.style.position = 'absolute';
        card.style.right = '0';
        card.style.top = '36px';
        card.style.width = 'min(300px, calc(100vw - 48px))';
        card.style.padding = '12px';
        card.style.border = '1px solid rgba(255,255,255,.16)';
        card.style.borderRadius = '10px';
        card.style.background = '#1d1f24';
        card.style.boxShadow = '0 8px 28px rgba(0,0,0,.35)';
        card.style.zIndex = '40';
        const heading = document.createElement('strong');
        heading.style.display = 'block';
        heading.style.marginBottom = '6px';
        heading.textContent = title;
        const body = document.createElement('span');
        body.className = 'res-desc';
        body.style.display = 'block';
        body.textContent = description;
        card.append(heading, body);
        button.setAttribute('aria-controls', card.id);
        button.onclick = event => {
            event.preventDefault();
            event.stopPropagation();
            const opening = card.hidden;
            closeFeatureInfoCards(card);
            card.hidden = !opening;
            button.setAttribute('aria-expanded', String(opening));
        };
        card.onclick = event => event.stopPropagation();
        wrap.append(button, card);
        return wrap;
    }

    function renderFeatureControls() {
        const container = document.getElementById('policy_feature_controls');
        container.replaceChildren();
        featureKeys.forEach(([key, title, description]) => {
            const row = document.createElement('div');
            row.className = 'row';
            const label = document.createElement('label');
            label.htmlFor = `policy_feature_${key}`;
            const strong = document.createElement('strong');
            strong.style.color = '#fff';
            strong.textContent = title;
            const desc = document.createElement('span');
            desc.className = 'res-desc';
            desc.textContent = description;
            label.append(strong, desc);
            const input = document.createElement('input');
            input.type = 'checkbox';
            input.className = 'toggle';
            input.id = `policy_feature_${key}`;
            input.checked = Boolean(policyState.features[key]);
            row.append(label, makeFeatureInfo(title, description), input);
            container.appendChild(row);
        });
    }

    function componentEditor(component, title, policy, prefix) {
        const wrap = document.createElement('div');
        wrap.className = 'panel';
        wrap.style.padding = '14px';
        const heading = document.createElement('div');
        heading.className = 'section-header';
        heading.textContent = title;
        const select = document.createElement('select');
        select.id = `${prefix}_${component}_mode`;
        patchModes.forEach(([value, label]) => {
            const option = document.createElement('option');
            option.value = value;
            option.textContent = label;
            select.appendChild(option);
        });
        select.value = policy && policy.mode ? policy.mode : 'device_default';
        const manual = document.createElement('input');
        manual.type = 'text';
        manual.id = `${prefix}_${component}_manual`;
        manual.placeholder = 'YYYY-MM-DD';
        manual.maxLength = 10;
        manual.inputMode = 'numeric';
        manual.value = policy && policy.value ? policy.value : '';
        manual.style.marginTop = '8px';
        const sync = () => { manual.style.display = select.value === 'manual' ? 'block' : 'none'; };
        select.onchange = sync;
        sync();
        wrap.append(heading, select, manual);
        return wrap;
    }

    function renderPatchControls() {
        document.getElementById('policy_securityPatch').checked = Boolean(policyState.features.securityPatch);
        document.getElementById('policy_patch_threshold').value = String(policyState.securityPatch.automaticThresholdMonths || 6);
        const container = document.getElementById('policy_patch_components');
        container.replaceChildren();
        [['system', 'System'], ['vendor', 'Vendor'], ['boot', 'Boot']].forEach(([key, title]) => {
            container.appendChild(componentEditor(key, title, policyState.securityPatch[key], 'policy_patch'));
        });
    }

    function renderRuntime() {
        const container = document.getElementById('policy_runtime_state');
        container.replaceChildren();
        Object.entries(policyState.runtime || {}).forEach(([key, value]) => {
            if (key === 'generation') return;
            const row = document.createElement('div');
            row.className = 'row';
            const name = document.createElement('span');
            name.textContent = key.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase());
            const state = document.createElement('span');
            state.className = 'tag';
            state.textContent = String(value).replace(/_/g, ' ');
            row.append(name, state);
            container.appendChild(row);
        });
    }

    function readPatchPolicy(prefix, component, allowInherit) {
        const select = document.getElementById(`${prefix}_${component}_mode`);
        if (!select || (allowInherit && select.value === 'inherit')) return null;
        const result = { mode: select.value };
        if (select.value === 'manual') {
            const value = document.getElementById(`${prefix}_${component}_manual`).value.trim();
            if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error(`${component} manual patch must use YYYY-MM-DD`);
            result.value = value;
        }
        return result;
    }

    function stateForSave() {
        const next = JSON.parse(JSON.stringify(policyState));
        delete next.generation;
        delete next.source;
        delete next.recovery;
        delete next.builtInProfiles;
        delete next.runtime;
        return next;
    }

    async function saveFeatures() {
        const next = stateForSave();
        featureKeys.forEach(([key]) => { next.features[key] = document.getElementById(`policy_feature_${key}`).checked; });
        await saveState(next);
    }

    async function savePatch() {
        const next = stateForSave();
        next.features.securityPatch = document.getElementById('policy_securityPatch').checked;
        next.securityPatch.automaticThresholdMonths = Number(document.getElementById('policy_patch_threshold').value);
        ['system', 'vendor', 'boot'].forEach(component => { next.securityPatch[component] = readPatchPolicy('policy_patch', component, false); });
        await saveState(next);
    }

    async function saveState(next) {
        const body = new URLSearchParams();
        body.set('data', JSON.stringify(next));
        policyState = await request('/api/policy_state', { method: 'POST', body });
        renderAll();
        notifyUser('Policy state saved');
    }

    function profileFeatureSelect(key, value) {
        const select = document.createElement('select');
        select.id = `policy_profile_feature_${key}`;
        [['inherit', 'Inherit'], ['true', 'Enabled'], ['false', 'Disabled']].forEach(([entry, label]) => {
            const option = document.createElement('option');
            option.value = entry;
            option.textContent = label;
            select.appendChild(option);
        });
        select.value = value === true ? 'true' : value === false ? 'false' : 'inherit';
        return select;
    }

    function renderProfileEditor(profile) {
        selectedProfile = profile || null;
        const panel = document.getElementById('policy_profile_editor_panel');
        panel.style.display = 'block';
        document.getElementById('policy_profile_name').value = profile ? profile.name : '';
        document.getElementById('policy_profile_privacy').value = profile ? profile.privacy || 'inherit' : 'inherit';
        document.getElementById('policy_profile_apps').value = profile ? (profile.applications || []).join('\n') : '';
        document.getElementById('policy_profile_template').value = profile && profile.template ? profile.template : '';
        document.getElementById('policy_profile_keybox').value = profile && profile.keybox ? profile.keybox : '';
        const features = document.getElementById('policy_profile_features');
        features.replaceChildren();
        profileFeatureKeys.forEach(key => {
            const row = document.createElement('div');
            row.className = 'row';
            const metadata = key === 'securityPatch' ? ['securityPatch', 'Security Patch Override', securityPatchDescription] : featureKeys.find(item => item[0] === key);
            const label = document.createElement('label');
            label.textContent = metadata[1];
            const info = makeFeatureInfo(metadata[1], `${metadata[2]} Inherit follows the base policy.`);
            row.append(label, info, profileFeatureSelect(key, profile && profile.features ? profile.features[key] : undefined));
            features.appendChild(row);
        });
        const patches = document.getElementById('policy_profile_patches');
        patches.replaceChildren();
        ['system', 'vendor', 'boot'].forEach(component => {
            const current = profile && profile.securityPatch ? profile.securityPatch[component] : null;
            const editor = componentEditor(component, component[0].toUpperCase() + component.slice(1), current || { mode: 'device_default' }, 'policy_profile_patch');
            const select = editor.querySelector('select');
            const inherit = document.createElement('option');
            inherit.value = 'inherit';
            inherit.textContent = 'Inherit';
            select.insertBefore(inherit, select.firstChild);
            select.value = current ? current.mode : 'inherit';
            select.dispatchEvent(new Event('change'));
            patches.appendChild(editor);
        });
        document.getElementById('policy_profile_rkp').value = profile && typeof profile.rkpPassthrough === 'boolean' ? String(profile.rkpPassthrough) : 'inherit';
        document.getElementById('policy_profile_drm').value = profile && typeof profile.drmPassthrough === 'boolean' ? String(profile.drmPassthrough) : 'inherit';
        document.getElementById('policy_profile_delete').disabled = !profile;
        document.getElementById('policy_profile_clone').disabled = !profile;
    }

    function renderProfiles() {
        const container = document.getElementById('policy_profile_list');
        container.replaceChildren();
        const profiles = Array.isArray(policyState.profiles) ? policyState.profiles : [];
        if (!profiles.length) {
            const empty = document.createElement('div');
            empty.className = 'scope-note';
            empty.textContent = 'No user-defined profiles yet.';
            container.appendChild(empty);
            return;
        }
        profiles.forEach(profile => {
            const row = document.createElement('div');
            row.className = 'server-item';
            row.style.gap = '10px';
            row.style.flexWrap = 'wrap';
            const text = document.createElement('div');
            const title = document.createElement('strong');
            title.textContent = profile.name;
            const detail = document.createElement('span');
            detail.className = 'res-desc';
            detail.textContent = `${(profile.applications || []).length} assignment(s)${policyState.activeProfile === profile.name ? ' · active' : ''}`;
            text.append(title, detail);
            const actions = document.createElement('div');
            actions.style.display = 'flex';
            actions.style.gap = '6px';
            const edit = document.createElement('button');
            edit.textContent = 'Edit';
            edit.onclick = () => renderProfileEditor(profile);
            const activate = document.createElement('button');
            activate.textContent = policyState.activeProfile === profile.name ? 'Active' : 'Activate';
            activate.disabled = policyState.activeProfile === profile.name;
            activate.onclick = () => profileAction('activate', { name: profile.name });
            actions.append(edit, activate);
            row.append(text, actions);
            container.appendChild(row);
        });
    }

    function profilePayload() {
        const features = {};
        profileFeatureKeys.forEach(key => {
            const value = document.getElementById(`policy_profile_feature_${key}`).value;
            if (value !== 'inherit') features[key] = value === 'true';
        });
        const securityPatch = {};
        ['system', 'vendor', 'boot'].forEach(component => {
            const value = readPatchPolicy('policy_profile_patch', component, true);
            if (value) securityPatch[component] = value;
        });
        const boolOrNull = id => {
            const value = document.getElementById(id).value;
            return value === 'inherit' ? null : value === 'true';
        };
        return {
            name: document.getElementById('policy_profile_name').value.trim(),
            applications: document.getElementById('policy_profile_apps').value.split(/\r?\n/).map(value => value.trim()).filter(Boolean),
            template: document.getElementById('policy_profile_template').value.trim() || null,
            keybox: document.getElementById('policy_profile_keybox').value.trim() || null,
            privacy: document.getElementById('policy_profile_privacy').value,
            features,
            securityPatch,
            rkpPassthrough: boolOrNull('policy_profile_rkp'),
            drmPassthrough: boolOrNull('policy_profile_drm')
        };
    }

    async function profileAction(action, data) {
        const body = new URLSearchParams();
        body.set('action', action);
        body.set('data', JSON.stringify(data || {}));
        policyState = await request('/api/profile_v2', { method: 'POST', body });
        selectedProfile = null;
        document.getElementById('policy_profile_editor_panel').style.display = 'none';
        renderAll();
        notifyUser('Profile updated');
    }

    async function saveProfile() {
        const profile = profilePayload();
        if (!profile.name) throw new Error('Profile name is required');
        if (selectedProfile) await profileAction('edit', { name: selectedProfile.name, profile });
        else await profileAction('create', { profile });
    }

    async function cloneProfile() {
        if (!selectedProfile) return;
        const name = global.prompt('New profile name', `${selectedProfile.name} Copy`);
        if (!name) return;
        await profileAction('duplicate', { name: selectedProfile.name, newName: name.trim() });
    }

    async function deleteProfile() {
        if (!selectedProfile) return;
        if (!global.confirm(`Delete profile ${selectedProfile.name}?`)) return;
        await profileAction('delete', { name: selectedProfile.name });
    }

    async function exportProfiles() {
        const safe = stateForSave();
        const blob = new Blob([JSON.stringify(safe, null, 2)], { type: 'application/json' });
        await bridge.exportBlob(blob, 'cleverestricky-profiles-v2.json');
    }

    async function importProfiles(file) {
        if (!(file instanceof File) || file.size < 1 || file.size > 512 * 1024) throw new Error('Profile import file is outside the supported size');
        const parsed = JSON.parse(await file.text());
        await saveState(parsed);
    }

    function renderPatchResolution(state) {
        const container = document.getElementById('policy_patch_resolution');
        container.replaceChildren();
        ['system', 'vendor', 'boot'].forEach(component => {
            const value = state.securityPatch[component];
            const panel = document.createElement('div');
            panel.className = 'panel';
            panel.style.padding = '12px';
            const title = document.createElement('strong');
            title.textContent = component[0].toUpperCase() + component.slice(1);
            const captured = document.createElement('span');
            captured.className = 'res-desc';
            captured.textContent = `Captured: ${value.captured === null ? 'not captured yet' : value.captured}`;
            const configured = document.createElement('span');
            configured.className = 'res-desc';
            configured.textContent = `Configured: ${value.configured}`;
            const effective = document.createElement('span');
            effective.className = 'res-desc';
            effective.textContent = `Effective: ${value.effective}`;
            panel.append(title, captured, configured, effective);
            container.appendChild(panel);
        });
    }

    async function inspectPatch() {
        const packageName = document.getElementById('policy_patch_package').value.trim();
        if (!packageName) throw new Error('Select an application');
        const state = await request(`/api/effective_state?package=${encodeURIComponent(packageName)}`);
        renderPatchResolution(state);
    }

    function renderEffective(state) {
        const container = document.getElementById('policy_effective_result');
        container.replaceChildren();
        const ordered = [
            ['matchedApplicationRule', 'Matched application rule'],
            ['matchedProfile', 'Matched profile'],
            ['scope', 'Scope'],
            ['identityTemplate', 'Identity template'],
            ['keyboxReference', 'Keybox reference'],
            ['privacy', 'Privacy'],
            ['buildIdentity', 'Device / Build Identity'],
            ['attestationIdentity', 'Attestation Identity'],
            ['telephonyIdentity', 'Telephony Identity'],
            ['regionIdentity', 'Region Identity'],
            ['identityRefresh', 'Identity Refresh'],
            ['securityPatchOverride', 'Security Patch Override'],
            ['rkp', 'RKP'],
            ['drm', 'DRM'],
            ['keyMint', 'KeyMint / StrongBox'],
            ['keystoreCore', 'Core Keystore'],
            ['providerCoexistence', 'Provider coexistence'],
            ['rebootRequired', 'Reboot required']
        ];
        ordered.forEach(([key, label]) => {
            const row = document.createElement('div');
            row.className = 'row';
            const name = document.createElement('span');
            name.textContent = label;
            const value = document.createElement('span');
            value.className = 'tag';
            value.textContent = state[key] === null ? 'none' : String(state[key]);
            row.append(name, value);
            container.appendChild(row);
        });
        const patchTitle = document.createElement('div');
        patchTitle.className = 'section-header';
        patchTitle.textContent = 'Security Patch';
        container.appendChild(patchTitle);
        ['system', 'vendor', 'boot'].forEach(component => {
            const value = state.securityPatch[component];
            const row = document.createElement('div');
            row.className = 'row wrap';
            const name = document.createElement('span');
            name.textContent = component[0].toUpperCase() + component.slice(1);
            const detail = document.createElement('span');
            detail.className = 'res-desc';
            detail.textContent = `Captured ${value.captured === null ? 'n/a' : value.captured} · Configured ${value.configured} · Effective ${value.effective}`;
            row.append(name, detail);
            container.appendChild(row);
        });
    }

    async function inspectEffective() {
        const packageName = document.getElementById('policy_effective_package').value.trim();
        if (!packageName) throw new Error('Select an application');
        renderEffective(await request(`/api/effective_state?package=${encodeURIComponent(packageName)}`));
    }

    function renderPackages() {
        const list = document.getElementById('policy_package_list');
        list.replaceChildren();
        packages.forEach(packageName => {
            const option = document.createElement('option');
            option.value = packageName;
            list.appendChild(option);
        });
    }

    function renderAll() {
        renderFeatureControls();
        renderPatchControls();
        renderProfiles();
        renderRuntime();
    }

    function guard(action) {
        return async () => {
            try {
                await action();
            } catch (error) {
                notifyUser(error && error.message ? error.message : String(error), 'error');
            }
        };
    }

    async function initialize() {
        staticMarkup();
        packages = bridge.listPackages();
        renderPackages();
        policyState = await request('/api/policy_state');
        renderAll();
        document.getElementById('policy_feature_save').onclick = guard(saveFeatures);
        document.getElementById('policy_patch_save').onclick = guard(savePatch);
        document.getElementById('policy_patch_inspect').onclick = guard(inspectPatch);
        document.getElementById('policy_effective_load').onclick = guard(inspectEffective);
        document.getElementById('policy_profile_new').onclick = () => renderProfileEditor(null);
        document.getElementById('policy_profile_save').onclick = guard(saveProfile);
        document.getElementById('policy_profile_clone').onclick = guard(cloneProfile);
        document.getElementById('policy_profile_delete').onclick = guard(deleteProfile);
        document.getElementById('policy_profile_export').onclick = guard(exportProfiles);
        document.getElementById('policy_profile_import').onclick = () => document.getElementById('policy_profile_import_file').click();
        document.addEventListener('click', () => closeFeatureInfoCards(null));
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') closeFeatureInfoCards(null);
        });
        document.getElementById('policy_profile_import_file').onchange = guard(async () => {
            const input = document.getElementById('policy_profile_import_file');
            const file = input.files && input.files[0];
            if (file) await importProfiles(file);
            input.value = '';
        });
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', guard(initialize), { once: true });
    else guard(initialize)();
})(window);