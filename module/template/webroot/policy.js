(function (global) {
'use strict';

const bridge = global.CleveresBridge;
if (!bridge) return;

const FEATURE_KEYS = [
  ['buildIdentity', 'Build identity', 'Boot fingerprint, model and build fields. Requires a reboot when early-boot properties change.'],
  ['attestationIdentity', 'Attestation identity', 'Uses the configured attestation identity only for selected targets; genuine hardware key operations remain on Android.'],
  ['telephonyIdentity', 'Telephony identity', 'Controls optional IMEI/IMSI/ICCID/phone presentation for selected apps.'],
  ['regionIdentity', 'Region identity', 'Controls optional region/hardware-region presentation. Some values require a reboot.'],
  ['identityRefresh', 'Identity refresh', 'Prepares a new identity for the next boot only while this option is enabled.']
];
const PATCH_COMPONENTS = [['system', 'System'], ['vendor', 'Vendor'], ['boot', 'Boot']];
const PATCH_MODES = [
  ['device_default', 'Device default'], ['prop', 'ROM property'], ['manual', 'Manual date'], ['automatic', 'Automatic'], ['no', 'Omit']
];
const PROFILE_FEATURES = FEATURE_KEYS.concat([
  ['securityPatch', 'Security Patch', 'Override the Security Patch feature only for apps assigned to this profile.']
]);
const IMPACTS = {
  'Identity Spoof Engine': 'Estimated impact: CPU low per matching identity/attestation call; RAM low and bounded.',
  'Keystore Runtime': 'Estimated impact: CPU very low while idle and low per matching Binder call; RAM low.',
  'Telephony Runtime': 'Estimated impact: CPU low only on matching calls; RAM low.',
  'Global Mode': 'Estimated impact: CPU very low per UID decision; RAM low with a bounded UID cache.',
  'Automatic Keybox Check': 'Estimated impact: CPU/network low during scheduled verification; RAM low and temporary.',
  'Identity Refresh on Boot': 'Estimated impact: CPU low at boot only; RAM negligible after initialization.',
  'Telephony Interception': 'Estimated impact: CPU low per matching Binder call; RAM low.',
  'RKP Protection': 'Estimated impact: CPU negligible on protected infrastructure paths; RAM negligible.',
  'DRM App Passthrough': 'Estimated impact: CPU low per matching package lookup; RAM low and bounded.',
  'Template Build Identity': 'Estimated impact: CPU low at boot only; RAM negligible after properties are prepared.',
  'Region Property View': 'Estimated impact: CPU low at boot only; RAM negligible.',
  'Keybox Storage': 'Estimated impact: CPU low during refresh/verification; RAM moderate and bounded by active certificate chains.',
  'App Rules': 'Estimated impact: CPU very low with cached lookups; RAM low and proportional to configured rules.'
};

let policyState = null;
let legacyConfig = null;
let packages = [];
let keyboxes = [];
let templates = [];
let selectedProfileIndex = -1;
let saving = false;

function onReady(fn) {
  // policy.js is loaded at the end of <body>, before the legacy inline bootstrap.
  // Install structural UI synchronously so that bootstrap code never sees missing nodes.
  if (document.body) fn();
  else document.addEventListener('DOMContentLoaded', fn, {once:true});
}

async function request(path, options) {
  const response = await bridge.fetch(path, options || {});
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed (${response.status})`);
  }
  const type = response.headers.get('content-type') || '';
  return type.includes('application/json') ? response.json() : response.text();
}

function notify(message, type) {
  if (typeof global.notify === 'function') global.notify(message, type || 'normal');
}

function refreshPresentation() {
  const selector = document.getElementById('ct_language_selector');
  if (selector) selector.dispatchEvent(new Event('change',{bubbles:true}));
}

function safeClone(value) {
  return JSON.parse(JSON.stringify(value));
}

function normalizeProfile(profile) {
  const source = profile || {};
  const features = {};
  if (source.features && typeof source.features === 'object') {
    PROFILE_FEATURES.forEach(([key]) => {
      if (typeof source.features[key] === 'boolean') features[key] = source.features[key];
    });
  }
  const patch = {};
  PATCH_COMPONENTS.forEach(([key]) => {
    const value = source.securityPatch && source.securityPatch[key];
    if (value && typeof value === 'object' && value.mode) {
      patch[key] = {mode:value.mode};
      if (value.mode === 'manual' && value.value) patch[key].value = value.value;
    }
  });
  return {
    name: String(source.name || '').trim(),
    applications: Array.isArray(source.applications) ? [...new Set(source.applications.map(String).map(value => value.trim()).filter(Boolean))] : [],
    template: source.template || null,
    keybox: source.keybox || null,
    privacy: ['inherit','isolate','redact'].includes(source.privacy) ? source.privacy : 'inherit',
    features,
    securityPatch: patch,
    rkpPassthrough: typeof source.rkpPassthrough === 'boolean' ? source.rkpPassthrough : null,
    drmPassthrough: typeof source.drmPassthrough === 'boolean' ? source.drmPassthrough : null
  };
}

function stateForSave(source) {
  const features = source.features || {};
  const patch = source.securityPatch || {};
  const normalizedPatch = {};
  PATCH_COMPONENTS.forEach(([key]) => {
    const value = patch[key] || {mode:'device_default'};
    normalizedPatch[key] = {mode:value.mode || 'device_default'};
    if (normalizedPatch[key].mode === 'manual' && value.value) normalizedPatch[key].value = value.value;
  });
  return {
    version: Number(source.version) || 2,
    features: {
      buildIdentity: Boolean(features.buildIdentity),
      attestationIdentity: Boolean(features.attestationIdentity),
      telephonyIdentity: Boolean(features.telephonyIdentity),
      regionIdentity: Boolean(features.regionIdentity),
      identityRefresh: Boolean(features.identityRefresh),
      securityPatch: Boolean(features.securityPatch)
    },
    securityPatch: {
      automaticThresholdMonths: Math.min(24, Math.max(1, Number(patch.automaticThresholdMonths) || 6)),
      system: normalizedPatch.system,
      vendor: normalizedPatch.vendor,
      boot: normalizedPatch.boot
    },
    profiles: Array.isArray(source.profiles) ? source.profiles.map(normalizeProfile) : [],
    activeProfile: source.activeProfile || null
  };
}

async function savePolicy(mutator, successMessage) {
  if (!policyState || saving) return;
  saving = true;
  document.documentElement.classList.add('ct-saving');
  try {
    const next = safeClone(policyState);
    mutator(next);
    const body = new URLSearchParams();
    body.set('data', JSON.stringify(stateForSave(next)));
    policyState = await request('/api/policy_state', {method:'POST', body});
    renderAll();
    notify(successMessage || 'Saved');
  } catch (error) {
    notify(error.message || 'Could not save policy', 'error');
    renderAll();
  } finally {
    saving = false;
    document.documentElement.classList.remove('ct-saving');
  }
}

function injectStyles() {
  if (document.getElementById('ctPolicyUxStyle')) return;
  const style = document.createElement('style');
  style.id = 'ctPolicyUxStyle';
  style.textContent = `
    .ct-feature-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
    .ct-feature-card{border:1px solid var(--border);border-radius:14px;padding:15px;background:#1a1a1a;min-width:0}
    .ct-feature-card .row{margin:0;align-items:center}
    .ct-feature-card strong{display:block;color:#fff}
    .ct-feature-card p{margin:6px 0 0;color:#999;font-size:.84em;line-height:1.45}
    .ct-subcontrols{margin-top:12px;padding-top:10px;border-top:1px solid var(--border)}
    .ct-subcontrols[hidden]{display:none!important}
    .ct-subcontrols .row{margin-bottom:10px}
    .ct-subcontrols .row:last-child{margin-bottom:0}
    .ct-help{margin-top:10px;border-top:1px dashed #333;padding-top:8px}
    .ct-help summary{cursor:pointer;color:#bbb;font-size:.85em;min-height:34px;display:flex;align-items:center}
    .ct-help p{margin:5px 0 0!important}
    .ct-banner{border:1px solid #5c4b19;background:rgba(245,158,11,.09);border-radius:10px;padding:13px 14px;margin-bottom:16px;color:#f3d68a;line-height:1.45}
    .ct-action-grid{display:grid!important;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px!important;width:100%}
    .ct-action-grid>button{width:100%!important;min-width:0!important;white-space:normal;overflow-wrap:anywhere;line-height:1.25;padding:12px 10px!important}
    .ct-toolbar{display:flex;gap:10px;flex-wrap:wrap}.ct-toolbar button{flex:1 1 160px;min-width:0}
    .ct-choice-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
    .ct-inline-note{font-size:.82em;color:#999;line-height:1.45;margin:8px 0 0}
    .ct-chip-wrap{display:flex;gap:7px;flex-wrap:wrap;margin-top:8px}
    .ct-chip{display:inline-flex;gap:7px;align-items:center;border:1px solid #3a3a3a;border-radius:999px;padding:6px 9px;font-size:.8em;background:#1c1c1c}
    .ct-chip button{min-width:28px;min-height:28px;padding:0;border-radius:50%;font-size:16px}
    .ct-profile-item{display:flex;align-items:center;gap:10px;border-bottom:1px solid var(--border);padding:10px 0}
    .ct-profile-item:last-child{border-bottom:0}.ct-profile-item .ct-profile-copy{flex:1;min-width:0}
    .ct-profile-item .ct-profile-copy small{display:block;color:#888;margin-top:3px;overflow-wrap:anywhere}
    .ct-profile-item button{flex:0 0 auto;padding:9px 12px}
    .ct-patch-component{border:1px solid var(--border);border-radius:10px;padding:12px;margin-bottom:10px}
    .ct-patch-component:last-child{margin-bottom:0}
    .ct-saving button,.ct-saving input,.ct-saving select,.ct-saving textarea{pointer-events:none}
    .ct-keybox-summary{margin-top:8px;color:#999;font-size:.84em;line-height:1.4}
    .ct-impact-note{display:block;color:#8fa3b8;font-size:.78em;line-height:1.4;margin-top:6px}
    .ct-readonly-state{display:inline-flex;align-items:center;min-height:28px;padding:4px 9px;border:1px solid #3a3a3a;border-radius:999px;color:#bbb;background:#1d1d1d;font-size:.8em;white-space:nowrap}
    .ct-package-picker{position:relative}.ct-package-suggestions{position:absolute;z-index:1300;left:0;right:0;top:calc(100% + 4px);max-height:min(42dvh,320px);overflow:auto;border:1px solid var(--border);border-radius:10px;background:#181818;box-shadow:0 10px 28px rgba(0,0,0,.45)}.ct-package-suggestions[hidden]{display:none!important}.ct-package-option{display:block;width:100%;min-height:44px;padding:10px 12px;border:0;border-bottom:1px solid #2b2b2b;border-radius:0;background:transparent;color:var(--fg);text-align:left;text-transform:none;letter-spacing:0;overflow-wrap:anywhere}.ct-package-option:last-child{border-bottom:0}.ct-package-option:focus,.ct-package-option:hover{background:#2a2a2a}
    input[type="checkbox"].ct-switch{appearance:none!important;-webkit-appearance:none!important;width:48px!important;height:28px!important;min-width:48px!important;min-height:28px!important;padding:0!important;margin:0!important;border:1px solid #4a4d52!important;border-radius:999px!important;background:#292b2f!important;box-sizing:border-box!important;position:relative!important;cursor:pointer!important;transition:background .18s ease,border-color .18s ease!important;flex:0 0 48px!important}
    input[type="checkbox"].ct-switch::after{content:''!important;position:absolute!important;width:22px!important;height:22px!important;left:2px!important;top:2px!important;border-radius:50%!important;background:#f4f4f5!important;box-shadow:0 1px 4px rgba(0,0,0,.45)!important;transform:translateX(0)!important;transition:transform .18s cubic-bezier(.22,.8,.25,1),background .18s ease!important}
    input[type="checkbox"].ct-switch:checked{background:#16634d!important;border-color:#34d399!important}
    input[type="checkbox"].ct-switch:checked::after{transform:translateX(20px)!important;background:#ecfdf5!important}
    input[type="checkbox"].ct-switch:focus-visible{outline:2px solid var(--accent)!important;outline-offset:3px!important}
    input[type="checkbox"].ct-switch:disabled{opacity:.5!important;cursor:not-allowed!important}
    #dashboard,#info,#spoof,#profiles,#effective,#apps{padding-bottom:max(120px,calc(78px + env(safe-area-inset-bottom)))!important}
    @media(max-width:640px){.ct-feature-grid,.ct-choice-grid{grid-template-columns:1fr}.ct-action-grid{grid-template-columns:1fr!important}.identity-actions{display:grid!important;grid-template-columns:1fr!important;width:100%}.identity-actions>button{width:100%!important;min-width:0!important;white-space:normal}.grid-2{grid-template-columns:1fr!important}.panel{padding:16px}.ct-profile-item{align-items:flex-start;flex-wrap:wrap}.ct-profile-item button{width:100%}}
  `;
  document.head.appendChild(style);
}

function navigateTabs(event) {
  if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
  const tabs = document.querySelector('.tabs');
  const current = event.target && event.target.closest ? event.target.closest('.tab') : null;
  if (!tabs || !current || !tabs.contains(current)) return;
  const items = [...tabs.querySelectorAll('.tab')].filter(tab => !tab.hidden && tab.getAttribute('aria-hidden') !== 'true');
  const index = items.indexOf(current);
  if (index < 0 || items.length < 2) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  const delta = event.key === 'ArrowRight' ? 1 : -1;
  const next = items[(index + delta + items.length) % items.length];
  const id = next.id.replace(/^tab_/, '');
  if (typeof global.switchTab === 'function') global.switchTab(id);
  next.focus();
}

function installTabNavigationOwner() {
  const tabs = document.querySelector('.tabs');
  if (!tabs || tabs.dataset.ctPolicyKeyboardNav === '1') return;
  tabs.dataset.ctPolicyKeyboardNav = '1';
  tabs.addEventListener('keydown', navigateTabs, true);
}

function makeTab(id, title, afterId) {
  if (document.getElementById(`tab_${id}`)) return;
  const tabs = document.querySelector('.tabs');
  const after = document.getElementById(`tab_${afterId}`);
  if (!tabs) return;
  const tab = document.createElement('div');
  tab.className = 'tab';
  tab.id = `tab_${id}`;
  tab.setAttribute('role','tab');
  tab.setAttribute('tabindex','-1');
  tab.setAttribute('aria-selected','false');
  tab.setAttribute('aria-controls',id);
  tab.textContent = title;
  tab.onclick = () => global.switchTab && global.switchTab(id);
  if (after && after.nextSibling) tabs.insertBefore(tab, after.nextSibling);
  else tabs.appendChild(tab);
}

function makePage(id, afterId) {
  let page = document.getElementById(id);
  if (page) return page;
  page = document.createElement('div');
  page.id = id;
  page.className = 'content';
  page.setAttribute('role','tabpanel');
  page.setAttribute('aria-labelledby',`tab_${id}`);
  const after = document.getElementById(afterId);
  if (after && after.nextSibling) after.parentNode.insertBefore(page, after.nextSibling);
  else document.body.appendChild(page);
  return page;
}

function removeLegacySurfaces() {
  const dashboard = document.getElementById('dashboard');
  if (dashboard) {
    const statusEngine = document.getElementById('status_engine');
    const statusGlobal = document.getElementById('status_global');
    if (statusEngine && statusGlobal) {
      const strip = statusEngine.parentElement && statusEngine.parentElement.parentElement;
      if (strip && strip.contains(statusGlobal) && strip.parentElement === dashboard) strip.remove();
    }
    [...dashboard.querySelectorAll('.panel')].forEach(panel => {
      const title = (panel.querySelector('h3')?.textContent || '').trim();
      if (/^System Control$/i.test(title)) panel.remove();
    });
  }
  ['spoof_enabled','spoof_build_identity','random_on_boot','spoof_region_cn','telephony','global_mode','rkp_passthrough','drm_passthrough'].forEach(id => {
    const node = document.getElementById(id);
    if (!node) return;
    const panel = node.closest('.panel');
    const row = node.closest('.row');
    if (panel && /System Control|DRM Passthrough/i.test(panel.textContent || '')) panel.remove();
    else if (row) row.remove();
  });
  const spoof = document.getElementById('spoof');
  if (spoof) {
    [...spoof.querySelectorAll('.panel')].forEach(panel => {
      const title = (panel.querySelector('h3')?.textContent || '').trim();
      if (/^Identity Controls$/i.test(title) && panel.id !== 'ct_identity_controls') panel.remove();
    });
  }
  const stale = document.getElementById('ct_resources_controls');
  if (stale) stale.remove();
}

function retireLegacyLocalization() {
  global.setTimeout(() => {
    if (typeof global.loadLanguage === 'function') global.loadLanguage = async function () {};
    if (typeof global.applyTranslations === 'function') global.applyTranslations = function () {};
  }, 0);
}

function markIdentityActionGroups() {
  const spoof = document.getElementById('spoof');
  if (!spoof) return;
  spoof.querySelectorAll('button').forEach(button => {
    const text = (button.textContent || '').trim().toUpperCase();
    if (text.includes('AUTO IDENTITY') || text.includes('RANDOMIZE ALL') || text === 'APPLY IDENTITY' || text === 'CLEAR ALL') {
      const parent = button.parentElement;
      if (parent && parent.children.length >= 2) parent.classList.add('ct-action-grid');
    }
  });
}

function policyIdentityEnabled() {
  if (!policyState || !policyState.features) return false;
  return Boolean(policyState.features.securityPatch) || FEATURE_KEYS.some(([key]) => Boolean(policyState.features[key]));
}

function identityEnabled() {
  return policyIdentityEnabled() || Boolean(legacyConfig && legacyConfig.camera_visibility);
}

function helpMarkup(text) {
  return `<details class="ct-help"><summary>What does this do?</summary><p>${escapeHtml(text)}</p></details>`;
}

function switchMarkup(id, checked, extra) {
  return `<input id="${id}" type="checkbox" class="ct-switch" ${extra || ''} ${checked ? 'checked' : ''}>`;
}

function cardMarkup(id, title, description, checked, children) {
  return `<div class="ct-feature-card"><div class="row"><label for="${id}" style="flex:1;min-width:0;padding-right:12px"><strong>${escapeHtml(title)}</strong><span class="res-desc">${escapeHtml(description)}</span></label>${switchMarkup(id,checked)}</div>${children || ''}</div>`;
}

function defaultPatch() {
  return {automaticThresholdMonths:6,system:{mode:'device_default'},vendor:{mode:'device_default'},boot:{mode:'device_default'}};
}

function isAutoPatch() {
  if (!policyState || !policyState.securityPatch) return false;
  return PATCH_COMPONENTS.every(([key]) => policyState.securityPatch[key] && policyState.securityPatch[key].mode === 'automatic');
}

function buildFeatureCenterMarkup(prefix) {
  const globalOn = Boolean(legacyConfig && legacyConfig.global_mode);
  const keyboxOn = Boolean(legacyConfig && legacyConfig.auto_keybox_check);
  const drmOn = Boolean(legacyConfig && legacyConfig.drm_passthrough);
  const identityCards = identityFeatureCardsMarkup(`${prefix}_identity`);
  const drmChildren = `<div class="ct-subcontrols" id="${prefix}_drm_children" ${drmOn ? '' : 'hidden'}><strong>DRM Identifier Privacy</strong><p>Profile privacy <b>Isolate</b> replaces only DRM <code>deviceUniqueId</code> with a stable app-scoped pseudonymous ID. Licenses, provisioning and security level stay on Android's genuine DRM path.</p>${helpMarkup('Use Profiles > Privacy > Isolate for apps that should not share the genuine DRM device identifier.')}<button type="button" data-open-tab="profiles" style="width:100%;margin-top:10px">Configure Profiles</button></div>`;
  return `<div class="ct-feature-grid">
    ${cardMarkup(`${prefix}_global`,'Global Mode','Applies target rules globally when no narrower application rule wins. Fresh installs default to ON.',globalOn,helpMarkup('Global Mode is the module-wide application scope switch.'))}
    ${identityCards}
    ${cardMarkup(`${prefix}_keybox`,'Auto Keybox Check','Checks configured keyboxes against the module revocation source when enabled.',keyboxOn,helpMarkup('Optional network-backed keybox hygiene; manual management remains available.'))}
    ${cardMarkup(`${prefix}_drm_passthrough`,'DRM App Passthrough',"Keeps packages from drm_packages.txt on Android's genuine Keystore path. This does not fake a DRM security level.",drmOn,drmChildren)}
    <div class="ct-feature-card"><strong>Keybox / TEE path</strong><p>Keyboxes are selected per profile or from the stored pool. Stored XML/CBOX sources are reloaded without requiring an environment reset.</p>${helpMarkup('The core Keystore hook remains separate from Identity. Certificate chains are cached to avoid repeated expensive work.')}<button type="button" data-open-tab="keys" style="width:100%;margin-top:10px">Open keyboxes</button></div>
  </div>`;
}

function renderFeatureCenter() {
  if (!policyState) return;
  const panel = document.getElementById('ct_dashboard_controls');
  if (!panel) return;
  const host = panel.querySelector('.ct-control-host');
  if (!host) return;
  host.innerHTML = buildFeatureCenterMarkup('ct_dash');
  bindFeatureCenter(panel,'ct_dash');
  const status = document.getElementById('keyboxStatus');
  if (status && legacyConfig && Number.isFinite(Number(legacyConfig.keybox_count))) status.textContent = `${Number(legacyConfig.keybox_count)} Keys Loaded`;
}

function bindFeatureCenter(panel, prefix) {
  const globalToggle = panel.querySelector(`#${prefix}_global`);
  const keyboxToggle = panel.querySelector(`#${prefix}_keybox`);
  const drmToggle = panel.querySelector(`#${prefix}_drm_passthrough`);
  const drmChildren = panel.querySelector(`#${prefix}_drm_children`);
  if (globalToggle) globalToggle.onchange = () => setLegacyToggle('global_mode',globalToggle.checked);
  if (keyboxToggle) keyboxToggle.onchange = () => setLegacyToggle('auto_keybox_check',keyboxToggle.checked);
  if (drmToggle) drmToggle.onchange = () => {
    if (drmChildren) drmChildren.hidden = !drmToggle.checked;
    setLegacyToggle('drm_passthrough',drmToggle.checked);
  };
  bindIdentityControls(panel, `${prefix}_identity`);
  panel.querySelectorAll('[data-open-tab]').forEach(button => { button.onclick = () => global.switchTab && global.switchTab(button.dataset.openTab); });
}

async function setLegacyToggle(setting, enabled) {
  try {
    const body = new URLSearchParams();
    body.set('setting',setting);
    body.set('value',String(Boolean(enabled)));
    await request('/api/toggle',{method:'POST',body});
    await loadLegacyConfig();
    renderFeatureCenter();
    renderIdentityControls();
    refreshPresentation();
  } catch (error) {
    notify(error.message || 'Could not update setting','error');
    await loadLegacyConfig();
    renderFeatureCenter();
    renderIdentityControls();
    refreshPresentation();
  }
}

function installFeatureCenter() {
  const dashboard = document.getElementById('dashboard');
  if (!dashboard) return;
  removeLegacySurfaces();
  if (!document.getElementById('ct_dashboard_controls')) {
    const panel = document.createElement('div');
    panel.id = 'ct_dashboard_controls';
    panel.className = 'panel';
    panel.innerHTML = '<h3>Feature Center</h3><div class="scope-note">Main controls are here. Parent features reveal only the settings that belong to them.</div><div class="ct-control-host"></div>';
    const corePanel = [...dashboard.querySelectorAll('.panel')].find(item => /^Core Protection$/i.test((item.querySelector('h3')?.textContent || '').trim()));
    if (corePanel && corePanel.nextSibling) dashboard.insertBefore(panel,corePanel.nextSibling);
    else dashboard.prepend(panel);
  }
  const keysPage = document.getElementById('keys');
  if (keysPage && !document.getElementById('keyboxStatus')) {
    const statusPanel = document.createElement('div');
    statusPanel.id = 'ct_keybox_status_panel';
    statusPanel.className = 'panel';
    statusPanel.innerHTML = '<h3>Keybox Status</h3><div id="keyboxStatus" class="ct-keybox-summary" aria-live="polite">Loading keybox state...</div>';
    keysPage.prepend(statusPanel);
  }
}

function identityFeatureCardsMarkup(prefix) {
  const features = policyState ? policyState.features : {};
  const identityOn = policyIdentityEnabled();
  const cameraOn = Boolean(legacyConfig && legacyConfig.camera_visibility);
  const securityPatchRow = `<div class="row"><label for="${prefix}_securityPatch" style="flex:1;min-width:0;padding-right:10px"><strong>Security Patch</strong><span class="res-desc">Controls patch-level presentation independently from the other Identity child features.</span></label>${switchMarkup(`${prefix}_securityPatch`,Boolean(features && features.securityPatch),`data-policy-feature="securityPatch"`)}</div>`;
  const children = FEATURE_KEYS.map(([key,title,desc]) => `<div class="row"><label for="${prefix}_${key}" style="flex:1;padding-right:10px"><strong>${escapeHtml(title)}</strong><span class="res-desc">${escapeHtml(desc)}</span></label>${switchMarkup(`${prefix}_${key}`,Boolean(features && features[key]),`data-policy-feature="${key}"`)}</div>`).join('') + securityPatchRow;
  const core = `<div class="ct-feature-card"><div class="row"><label for="${prefix}_master" style="flex:1;min-width:0;padding-right:12px"><strong>Identity</strong><span class="res-desc">All Identity enable/disable controls live on Dashboard. Turn Identity on to reveal its child switches.</span></label>${switchMarkup(`${prefix}_master`,identityOn)}</div><div class="ct-subcontrols" id="${prefix}_children" ${identityOn ? '' : 'hidden'}>${children}<button type="button" data-open-tab="spoof" class="primary" style="width:100%;margin-top:10px">Open Identity settings</button></div>${helpMarkup('The Identity master toggles all child features together. Security Patch also has its own child switch so it can be disabled without disabling the other Identity paths.')}</div>`;
  const camera = cardMarkup(`${prefix}_camera_visibility`,'Camera visibility','Filters camera discovery for selected apps. Disabled means no cameraserver interceptor is started.',cameraOn,helpMarkup('This only reduces discoverable real camera IDs; it does not create cameras or block direct access.'));
  return `${core}${camera}`;
}

function identityControlsMarkup(prefix) {
  return `<div class="ct-feature-grid">${identityFeatureCardsMarkup(prefix)}</div>`;
}

function bindIdentityControls(panel, prefix) {
  const master = panel.querySelector(`#${prefix}_master`);
  const children = panel.querySelector(`#${prefix}_children`);
  const cameraToggle = panel.querySelector(`#${prefix}_camera_visibility`);
  if (cameraToggle) cameraToggle.onchange = () => setLegacyToggle('camera_visibility',cameraToggle.checked);
  if (master) master.onchange = () => {
    const enabled = master.checked;
    if (children) children.hidden = !enabled;
    savePolicy(next => {
      FEATURE_KEYS.forEach(([key]) => { next.features[key] = enabled; });
      next.features.securityPatch = enabled;
    }, enabled ? 'Identity enabled' : 'Identity disabled');
  };
  panel.querySelectorAll('[data-policy-feature]').forEach(toggle => {
    toggle.onchange = () => {
      const key = toggle.dataset.policyFeature;
      savePolicy(next => { next.features[key] = toggle.checked; }, `${toggle.closest('.row').querySelector('strong').textContent} updated`);
    };
  });
}

function installIdentityControls() {
  const stale = document.getElementById('ct_identity_controls');
  if (stale) stale.remove();
}

function renderIdentityControls() {
  installIdentityControls();
}

function installIdentityBanner() {
  const stale = document.getElementById('ct_identity_disabled_banner');
  if (stale) stale.remove();
}

function installConfigurationActions() {
  const dashboard = document.getElementById('dashboard');
  if (!dashboard || document.getElementById('ct_restore_defaults')) return;
  const panel = [...dashboard.querySelectorAll('.panel')].find(item => /^Configuration Management$/i.test((item.querySelector('h3')?.textContent || '').trim()));
  if (!panel) return;
  const note = document.createElement('div');
  note.className = 'scope-note';
  note.style.marginTop = '12px';
  note.textContent = 'Restores module settings using the built-in default profile. Stored keyboxes and encrypted backups are not deleted.';
  const button = document.createElement('button');
  button.id = 'ct_restore_defaults';
  button.type = 'button';
  button.className = 'danger';
  button.style.width = '100%';
  button.textContent = 'Restore Defaults';
  button.onclick = async () => {
    if (global.confirm && !global.confirm('Restore module settings to defaults?')) return;
    const original = button.textContent;
    button.disabled = true;
    button.textContent = 'Restoring...';
    try {
      const body = new URLSearchParams();
      body.set('profile','default');
      await request('/api/apply_profile',{method:'POST',body});
      await request('/api/reload',{method:'POST'});
      notify('Default settings restored');
      global.setTimeout(() => global.location.reload(),600);
    } catch (error) {
      notify(error.message || 'Could not restore defaults','error');
      button.disabled = false;
      button.textContent = original;
    }
  };
  panel.append(note,button);
}


const BUILT_IN_TEMPLATE_IDS = new Set(['pixel8pro','pixel8','pixel7pro','pixel6pro','s24ultra','s23ultra','xiaomi14','oneplus11','nothing2']);
const CUSTOM_TEMPLATE_FIELDS = [
  ['id','Template ID'],['manufacturer','Manufacturer'],['model','Model'],['fingerprint','Fingerprint'],
  ['brand','Brand'],['product','Product'],['device','Device'],['release','Android release'],
  ['buildId','Build ID'],['incremental','Incremental'],['type','Build type'],['tags','Build tags'],
  ['securityPatch','Security patch']
];

function installCustomTemplateBuilder() {
  const spoof = document.getElementById('spoof');
  if (!spoof || document.getElementById('ct_custom_template_panel')) return;
  const identityPanel = [...spoof.querySelectorAll('.panel')].find(item => /^Identity Manager$/i.test((item.querySelector('h3')?.textContent || '').trim()));
  if (!identityPanel) return;
  const panel = document.createElement('div');
  panel.id = 'ct_custom_template_panel';
  panel.className = 'panel';
  const fields = CUSTOM_TEMPLATE_FIELDS.map(([key,label]) => {
    const value = key === 'type' ? 'user' : (key === 'tags' ? 'release-keys' : '');
    return `<div><label for="ct_template_${key}">${escapeHtml(label)}</label><input id="ct_template_${key}" type="text" maxlength="512" value="${escapeHtml(value)}" autocomplete="off" spellcheck="false"></div>`;
  }).join('');
  panel.innerHTML = `<details id="ct_custom_template_details"><summary><strong>Custom Templates</strong></summary><div class="scope-note" style="margin-top:12px">Create a reusable device identity template. The form stays collapsed until you open it.</div><div class="ct-choice-grid">${fields}</div><button id="ct_template_save" type="button" class="primary" style="width:100%;margin-top:14px">Save custom template</button></details>`;
  identityPanel.insertAdjacentElement('afterend',panel);
  panel.querySelector('#ct_template_save').onclick = () => saveCustomTemplate().catch(error => notify(error.message || 'Could not save custom template','error'));
}

async function saveCustomTemplate() {
  const template = {};
  for (const [key] of CUSTOM_TEMPLATE_FIELDS) {
    const input = document.getElementById(`ct_template_${key}`);
    template[key] = input ? input.value.trim() : '';
  }
  template.id = template.id.toLowerCase();
  if (!/^[a-z0-9_-]{1,64}$/.test(template.id)) throw new Error('Template ID is invalid');
  if (BUILT_IN_TEMPLATE_IDS.has(template.id)) throw new Error('Built-in template IDs cannot be replaced');
  if (Object.entries(template).some(([key,value]) => key !== 'id' && (!value || value.length > 512 || /[\u0000-\u001f\u007f]/.test(value)))) throw new Error('All template fields are required');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(template.securityPatch)) throw new Error('Security patch must be YYYY-MM-DD');

  const currentResponse = await bridge.fetch('/api/file?filename=templates.json');
  if (!currentResponse.ok) throw new Error('Template catalog is unavailable');
  let current;
  try { current = JSON.parse(await currentResponse.text()); } catch (_) { throw new Error('Template catalog is unavailable'); }
  if (!Array.isArray(current)) throw new Error('Template catalog is unavailable');
  const next = current.filter(item => String(item && item.id || '').toLowerCase() !== template.id);
  next.push(template);
  const body = new URLSearchParams();
  body.set('filename','templates.json');
  body.set('content',JSON.stringify(next,null,2));
  const save = await bridge.fetch('/api/save',{method:'POST',body});
  if (!save.ok) throw new Error((await save.text()) || 'Could not save custom template');
  await loadReferenceData();
  notify('Custom template saved');
  global.setTimeout(() => global.location.reload(),500);
}


function installKernelIdentityControls() {
  const spoof = document.getElementById('spoof');
  if (!spoof || document.getElementById('ct_kernel_identity_panel')) return;
  const panel = document.createElement('div');
  panel.id = 'ct_kernel_identity_panel';
  panel.className = 'panel';
  panel.innerHTML = `<details><summary><strong>Kernel Identity</strong></summary><div class="scope-note" style="margin-top:12px">Optionally overrides uname release/version inside the injected Keystore runtime. Official GKI presets use published base kernel versions and remain editable.</div><div class="row"><label for="ct_kernel_enabled" style="flex:1"><strong>Hook kernel name</strong><span class="res-desc">Disabled by default. Core Binder protection is independent from this option.</span></label>${switchMarkup('ct_kernel_enabled',false)}</div><div id="ct_kernel_children" hidden><label for="ct_kernel_preset">GKI preset</label><select id="ct_kernel_preset"></select><div class="ct-choice-grid" style="margin-top:10px"><div><label for="ct_kernel_release">uname release</label><input id="ct_kernel_release" type="text" maxlength="64" autocomplete="off" spellcheck="false"></div><div><label for="ct_kernel_version">uname version</label><input id="ct_kernel_version" type="text" maxlength="64" autocomplete="off" spellcheck="false"></div></div><button id="ct_kernel_save" class="primary" type="button" style="width:100%;margin-top:12px">Save kernel identity</button></div></details>`;
  const customPanel = document.getElementById('ct_custom_template_panel');
  if (customPanel) customPanel.insertAdjacentElement('afterend',panel); else spoof.append(panel);
  loadKernelIdentity().catch(error => notify(error.message || 'Could not load kernel identity','error'));
}

async function loadKernelIdentity() {
  const state = await request('/api/kernel_identity');
  const enabled = document.getElementById('ct_kernel_enabled');
  const children = document.getElementById('ct_kernel_children');
  const preset = document.getElementById('ct_kernel_preset');
  const release = document.getElementById('ct_kernel_release');
  const version = document.getElementById('ct_kernel_version');
  if (!enabled || !children || !preset || !release || !version) return;
  preset.innerHTML = '<option value="custom">Custom</option>' + (state.presets || []).map(item => `<option value="${escapeHtml(item.id)}">${escapeHtml(item.label)}</option>`).join('');
  enabled.checked = Boolean(state.enabled);
  children.hidden = !enabled.checked;
  preset.value = state.preset || 'custom';
  release.value = state.release || '';
  version.value = state.version || '';
  enabled.onchange = () => { children.hidden = !enabled.checked; };
  preset.onchange = () => {
    const selected = (state.presets || []).find(item => item.id === preset.value);
    if (selected) { release.value = selected.release; version.value = selected.version; }
  };
  document.getElementById('ct_kernel_save').onclick = async () => {
    const payload = {enabled:enabled.checked,preset:preset.value,release:release.value.trim(),version:version.value.trim()};
    const body = new URLSearchParams(); body.set('data',JSON.stringify(payload));
    const result = await request('/api/kernel_identity',{method:'POST',body});
    notify(result.applied ? 'Kernel identity applied' : 'Kernel identity saved for next native activation');
  };
}

function installAppsProfileCard() {
  const apps = document.getElementById('apps');
  if (!apps || document.getElementById('ct_apps_profiles_card')) return;
  const panel = document.createElement('div');
  panel.id = 'ct_apps_profiles_card';
  panel.className = 'panel';
  panel.innerHTML = '<h3>Profiles</h3><div class="scope-note">Use Profiles for app assignments, identity template, custom keybox, DRM identifier privacy, per-feature overrides and per-app Security Patch rules in one place.</div><button type="button" class="primary" style="width:100%">Open Profiles</button>';
  panel.querySelector('button').onclick = () => global.switchTab && global.switchTab('profiles');
  apps.prepend(panel);
}

function staticPages() {
  const tabs = document.querySelector('.tabs');
  const dashboardTab = document.getElementById('tab_dashboard');
  const keyboxTab = document.getElementById('tab_keys');
  if (tabs && dashboardTab && keyboxTab && dashboardTab.nextElementSibling !== keyboxTab) tabs.insertBefore(keyboxTab,dashboardTab.nextSibling);
  const staleEffectiveTab = document.getElementById('tab_effective');
  if (staleEffectiveTab) staleEffectiveTab.remove();
  const stalePatchTab = document.getElementById('tab_patch');
  if (stalePatchTab) stalePatchTab.remove();
  const stalePatchPage = document.getElementById('patch');
  if (stalePatchPage) stalePatchPage.remove();
  makeTab('profiles','Profiles','spoof');

  const spoofPage = document.getElementById('spoof');
  let patchHost = document.getElementById('ct_identity_patch');
  if (!patchHost && spoofPage) {
    patchHost = document.createElement('div');
    patchHost.id = 'ct_identity_patch';
    patchHost.innerHTML = `<div class="panel"><h3>Security Patch</h3><div id="ct_patch_identity_state" class="scope-note">Enable or disable Security Patch from its Dashboard switch.</div><div id="ct_patch_children"><div class="row"><label for="ct_patch_auto" style="flex:1;padding-right:12px"><strong style="color:#fff">Auto Security Patch</strong><span class="res-desc">Use automatic mode for System, Vendor and Boot.</span></label>${switchMarkup('ct_patch_auto',false)}</div><div style="margin:12px 0"><label for="ct_patch_threshold">Stale ROM threshold (months)</label><input id="ct_patch_threshold" type="number" min="1" max="24" inputmode="numeric"></div><div id="ct_patch_components"></div><button id="ct_patch_save" class="primary" type="button" style="width:100%">Save Security Patch</button></div></div><div class="panel"><h3>Resolve for an app</h3><div class="scope-note">Shows captured, configured and effective values from the runtime resolver.</div><input id="ct_patch_package" type="search" placeholder="com.example.app" autocomplete="off"><button id="ct_patch_inspect" type="button" style="width:100%;margin-top:10px">Resolve</button><div id="ct_patch_result" class="scope-note" style="margin-top:12px"></div></div>`;
    const identityManager = [...spoofPage.querySelectorAll('.panel')].find(panel => /^Identity Manager$/i.test((panel.querySelector('h3')?.textContent || '').trim()));
    if (identityManager) identityManager.insertAdjacentElement('afterend', patchHost);
    else spoofPage.prepend(patchHost);
  }

  const profilesPage = makePage('profiles','spoof');
  profilesPage.innerHTML = `<div class="panel"><h3>Profiles</h3><div class="scope-note">App-centric configuration. Assign installed apps or wildcards, then choose privacy, identity, keybox and feature overrides.</div><div class="ct-toolbar"><button id="ct_profile_new" type="button" class="primary">New profile</button><button id="ct_profile_export" type="button">Export</button><button id="ct_profile_import" type="button">Import</button><input id="ct_profile_import_file" type="file" accept="application/json,.json" hidden></div><div id="ct_profile_list" style="margin-top:12px"></div></div><div class="panel" id="ct_profile_editor_panel" hidden><h3>Profile Editor</h3><div class="ct-choice-grid"><div><label for="ct_profile_name">Name</label><input id="ct_profile_name" type="text" maxlength="64"></div><div><label for="ct_profile_privacy">DRM / privacy mode</label><select id="ct_profile_privacy"><option value="inherit">Inherit</option><option value="isolate">Isolate - app-scoped pseudonymous DRM ID</option><option value="redact">Redact</option></select></div></div><div style="margin-top:12px"><label for="ct_profile_app_picker">Add installed app</label><div class="ct-toolbar"><input id="ct_profile_app_picker" type="search" placeholder="com.example.app" autocomplete="off"><button id="ct_profile_add_app" type="button">Add app</button></div><label for="ct_profile_apps" style="display:block;margin-top:10px">Assignments (one package or wildcard per line)</label><textarea id="ct_profile_apps" rows="4" maxlength="16384" placeholder="com.example.app&#10;com.example.*"></textarea><div id="ct_profile_app_chips" class="ct-chip-wrap"></div></div><div class="ct-choice-grid" style="margin-top:12px"><div><label for="ct_profile_template">Identity template</label><select id="ct_profile_template"><option value="">Inherit / none</option></select></div><div><label for="ct_profile_keybox">Keybox</label><select id="ct_profile_keybox"><option value="">Inherit / none</option></select></div></div><div class="section-header">Feature overrides</div><div id="ct_profile_features"></div><div class="section-header">Security Patch override</div><div class="row"><label for="ct_profile_patch_master" style="flex:1">Security Patch</label><select id="ct_profile_patch_master" style="max-width:180px"><option value="inherit">Inherit</option><option value="true">Enabled</option><option value="false">Disabled</option></select></div><div id="ct_profile_patch_children" hidden></div><div class="ct-toolbar" style="margin-top:16px"><button id="ct_profile_save" type="button" class="primary">Save profile</button><button id="ct_profile_clone" type="button">Clone</button><button id="ct_profile_delete" type="button" class="danger">Delete</button></div></div>`;

  const appsPage = document.getElementById('apps');
  let effective = document.getElementById('ct_effective_apps_host');
  if (appsPage && !effective) {
    effective = document.createElement('div');
    effective.id = 'ct_effective_apps_host';
    appsPage.appendChild(effective);
  }
  if (effective) effective.innerHTML = '<div class="panel"><h3>Effective State</h3><div class="scope-note">Inspect the exact resolver output for an installed application without exposing private key material.</div><input id="ct_effective_package" type="search" placeholder="com.example.app" autocomplete="off"><button id="ct_effective_load" class="primary" type="button" style="width:100%;margin-top:10px">Inspect</button></div><div class="panel"><h3>Resolved Configuration</h3><div id="ct_effective_result" class="scope-note">Select an app.</div></div>';
}

function patchEditor(prefix, component, title, value, allowInherit) {
  const options = (allowInherit ? [['inherit','Inherit']] : []).concat(PATCH_MODES);
  const selected = value && value.mode ? value.mode : (allowInherit ? 'inherit' : 'device_default');
  return `<div class="ct-patch-component"><label for="${prefix}_${component}_mode"><strong style="color:#fff">${escapeHtml(title)}</strong></label><select id="${prefix}_${component}_mode">${options.map(([optionValue,label]) => `<option value="${optionValue}" ${optionValue===selected?'selected':''}>${escapeHtml(label)}</option>`).join('')}</select><input id="${prefix}_${component}_value" type="text" maxlength="10" inputmode="numeric" placeholder="YYYY-MM-DD" value="${escapeHtml(value && value.value ? value.value : '')}" style="margin-top:8px;${selected==='manual'?'':'display:none'}"></div>`;
}

function bindPatchEditor(prefix, component) {
  const select = document.getElementById(`${prefix}_${component}_mode`);
  const manual = document.getElementById(`${prefix}_${component}_value`);
  if (!select || !manual) return;
  select.onchange = () => { manual.style.display = select.value === 'manual' ? 'block' : 'none'; };
}

function readPatchEditor(prefix, component, allowInherit) {
  const select = document.getElementById(`${prefix}_${component}_mode`);
  if (!select) return null;
  const mode = select.value;
  if (allowInherit && mode === 'inherit') return null;
  const result = {mode};
  if (mode === 'manual') {
    const value = document.getElementById(`${prefix}_${component}_value`).value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error(`${component} manual date must be YYYY-MM-DD`);
    result.value = value;
  }
  return result;
}

function renderPatchPage() {
  if (!policyState) return;
  const children = document.getElementById('ct_patch_children');
  const identityState = document.getElementById('ct_patch_identity_state');
  const auto = document.getElementById('ct_patch_auto');
  const threshold = document.getElementById('ct_patch_threshold');
  const host = document.getElementById('ct_patch_components');
  const saveButton = document.getElementById('ct_patch_save');
  if (!children || !identityState || !auto || !threshold || !host || !saveButton) return;
  const patchOn = Boolean(policyState.features && policyState.features.securityPatch);
  children.hidden = !patchOn;
  identityState.textContent = patchOn
    ? 'Security Patch is enabled on Dashboard. Configure its patch modes here.'
    : 'Enable Security Patch on Dashboard to configure its patch modes.';
  auto.checked = isAutoPatch();
  threshold.value = String(policyState.securityPatch.automaticThresholdMonths || 6);
  host.innerHTML = PATCH_COMPONENTS.map(([key,title]) => patchEditor('ct_patch',key,title,policyState.securityPatch[key],false)).join('');
  PATCH_COMPONENTS.forEach(([key]) => bindPatchEditor('ct_patch',key));
  auto.disabled = !patchOn;
  threshold.disabled = !patchOn;
  saveButton.disabled = !patchOn;
  host.querySelectorAll('select,input').forEach(control => { control.disabled = !patchOn; });
  auto.onchange = event => savePolicy(next => {
    PATCH_COMPONENTS.forEach(([key]) => {
      if (event.target.checked) next.securityPatch[key] = {mode:'automatic'};
      else if ((next.securityPatch[key] || {}).mode === 'automatic') next.securityPatch[key] = {mode:'device_default'};
    });
  },event.target.checked ? 'Auto Security Patch enabled' : 'Auto Security Patch disabled');
  saveButton.onclick = () => savePolicy(next => {
    next.securityPatch.automaticThresholdMonths = Number(threshold.value) || 6;
    PATCH_COMPONENTS.forEach(([key]) => { next.securityPatch[key] = readPatchEditor('ct_patch',key,false); });
  },'Security Patch policy saved');
  document.getElementById('ct_patch_inspect').onclick = inspectPatch;
}

async function inspectPatch() {
  const input = document.getElementById('ct_patch_package');
  const result = document.getElementById('ct_patch_result');
  const pkg = input ? input.value.trim() : '';
  if (!pkg || !result) return;
  try {
    const data = await request(`/api/effective_state?package=${encodeURIComponent(pkg)}`);
    const patch = data.securityPatch || {};
    result.innerHTML = PATCH_COMPONENTS.map(([key,title]) => {
      const item = patch[key] || {};
      return `<div class="ct-patch-component"><strong>${escapeHtml(title)}</strong><div class="ct-inline-note">Captured: ${escapeHtml(String(item.captured ?? '-'))}<br>Configured: ${escapeHtml(String(item.configured ?? '-'))}<br>Effective: ${escapeHtml(String(item.effective ?? '-'))}</div></div>`;
    }).join('');
  } catch (error) { result.textContent = error.message || 'Could not resolve patch state'; }
}

function renderProfiles() {
  if (!policyState) return;
  const list = document.getElementById('ct_profile_list');
  if (!list) return;
  const profiles = Array.isArray(policyState.profiles) ? policyState.profiles : [];
  list.innerHTML = profiles.length ? profiles.map((profile,index) => `<div class="ct-profile-item"><div class="ct-profile-copy"><strong>${escapeHtml(profile.name)}</strong><small>${escapeHtml((profile.applications||[]).join(', ') || 'No app assignments')} · privacy=${escapeHtml(profile.privacy || 'inherit')}</small></div><button type="button" data-edit-profile="${index}">Edit</button></div>`).join('') : '<div class="scope-note">No custom profiles yet.</div>';
  list.querySelectorAll('[data-edit-profile]').forEach(button => { button.onclick = () => openProfile(Number(button.dataset.editProfile)); });
  document.getElementById('ct_profile_new').onclick = () => openProfile(-1);
  document.getElementById('ct_profile_export').onclick = exportProfiles;
  document.getElementById('ct_profile_import').onclick = () => document.getElementById('ct_profile_import_file').click();
  document.getElementById('ct_profile_import_file').onchange = importProfiles;
}

function emptyProfile() {
  return {name:'',applications:[],template:null,keybox:null,privacy:'inherit',features:{},securityPatch:{},rkpPassthrough:null,drmPassthrough:null};
}

function fillSelect(select, values, selected, emptyLabel) {
  if (!select) return;
  select.replaceChildren();
  const empty = document.createElement('option');
  empty.value = '';
  empty.textContent = emptyLabel;
  select.appendChild(empty);
  [...new Set(values)].sort().forEach(value => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value;
    select.appendChild(option);
  });
  select.value = selected;
  if (selected && select.value !== selected) {
    const option = document.createElement('option');
    option.value = selected;
    option.textContent = `${selected} (current)`;
    select.appendChild(option);
    select.value = selected;
  }
}

function profileAppsFromEditor() {
  const source = document.getElementById('ct_profile_apps');
  return source ? [...new Set(source.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean))] : [];
}

function renderAppChips() {
  const host = document.getElementById('ct_profile_app_chips');
  if (!host) return;
  const apps = profileAppsFromEditor();
  host.innerHTML = apps.slice(0,64).map((app,index) => `<span class="ct-chip">${escapeHtml(app)}<button type="button" data-remove-app="${index}" aria-label="Remove ${escapeHtml(app)}">×</button></span>`).join('');
  host.querySelectorAll('[data-remove-app]').forEach(button => { button.onclick = () => {
    const next = profileAppsFromEditor();
    next.splice(Number(button.dataset.removeApp),1);
    document.getElementById('ct_profile_apps').value = next.join('\n');
    renderAppChips();
  }; });
}

function addProfileApp() {
  const picker = document.getElementById('ct_profile_app_picker');
  if (!picker) return;
  const value = picker.value.trim();
  if (!value) return;
  const apps = profileAppsFromEditor();
  if (!apps.includes(value)) apps.push(value);
  document.getElementById('ct_profile_apps').value = apps.join('\n');
  picker.value = '';
  renderAppChips();
}

function boolOrInherit(id) {
  const node = document.getElementById(id);
  if (!node) return null;
  return node.value === 'inherit' ? null : node.value === 'true';
}

function openProfile(index) {
  selectedProfileIndex = index;
  const profile = normalizeProfile(index >= 0 ? policyState.profiles[index] : emptyProfile());
  const panel = document.getElementById('ct_profile_editor_panel');
  if (!panel) return;
  panel.hidden = false;
  document.getElementById('ct_profile_name').value = profile.name;
  document.getElementById('ct_profile_privacy').value = profile.privacy;
  document.getElementById('ct_profile_apps').value = profile.applications.join('\n');
  fillSelect(document.getElementById('ct_profile_template'),templates,profile.template || '','Inherit / none');
  fillSelect(document.getElementById('ct_profile_keybox'),keyboxes,profile.keybox || '','Inherit / none');
  const featureHost = document.getElementById('ct_profile_features');
  featureHost.innerHTML = FEATURE_KEYS.map(([key,title,desc]) => {
    const current = typeof profile.features[key] === 'boolean' ? String(profile.features[key]) : 'inherit';
    return `<div class="row"><label for="ct_pf_${key}" style="flex:1;padding-right:10px"><strong style="color:#fff">${escapeHtml(title)}</strong><span class="res-desc">${escapeHtml(desc)}</span></label><select id="ct_pf_${key}" style="max-width:180px"><option value="inherit" ${current==='inherit'?'selected':''}>Inherit</option><option value="true" ${current==='true'?'selected':''}>Enabled</option><option value="false" ${current==='false'?'selected':''}>Disabled</option></select></div>`;
  }).join('');
  const patchMaster = document.getElementById('ct_profile_patch_master');
  const patchOverride = typeof profile.features.securityPatch === 'boolean' ? String(profile.features.securityPatch) : 'inherit';
  patchMaster.value = patchOverride;
  const patchChildren = document.getElementById('ct_profile_patch_children');
  patchChildren.innerHTML = PATCH_COMPONENTS.map(([key,title]) => patchEditor('ct_profile_patch',key,title,profile.securityPatch[key],true)).join('');
  PATCH_COMPONENTS.forEach(([key]) => bindPatchEditor('ct_profile_patch',key));
  const syncPatchChildren = () => { patchChildren.hidden = patchMaster.value !== 'true'; };
  patchMaster.onchange = syncPatchChildren;
  syncPatchChildren();
  renderAppChips();
  document.getElementById('ct_profile_add_app').onclick = addProfileApp;
  document.getElementById('ct_profile_apps').oninput = renderAppChips;
  document.getElementById('ct_profile_save').onclick = saveProfile;
  document.getElementById('ct_profile_clone').onclick = cloneProfile;
  document.getElementById('ct_profile_delete').onclick = deleteProfile;
  document.getElementById('ct_profile_delete').hidden = index < 0;
  panel.scrollIntoView({behavior:'smooth',block:'start'});
}

function collectProfile() {
  const existing = selectedProfileIndex >= 0 ? normalizeProfile(policyState.profiles[selectedProfileIndex]) : emptyProfile();
  const name = document.getElementById('ct_profile_name').value.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$/.test(name)) throw new Error('Profile name is invalid');
  const features = {};
  FEATURE_KEYS.forEach(([key]) => {
    const value = boolOrInherit(`ct_pf_${key}`);
    if (value !== null) features[key] = value;
  });
  const patchMaster = boolOrInherit('ct_profile_patch_master');
  if (patchMaster !== null) features.securityPatch = patchMaster;
  const patch = {};
  if (patchMaster === true) PATCH_COMPONENTS.forEach(([key]) => {
    const value = readPatchEditor('ct_profile_patch',key,true);
    if (value) patch[key] = value;
  });
  return {
    name,
    applications: profileAppsFromEditor(),
    template: document.getElementById('ct_profile_template').value || null,
    keybox: document.getElementById('ct_profile_keybox').value || null,
    privacy: document.getElementById('ct_profile_privacy').value,
    features,
    securityPatch: patch,
    rkpPassthrough: existing.rkpPassthrough,
    drmPassthrough: existing.drmPassthrough
  };
}

function saveProfile() {
  let profile;
  try { profile = collectProfile(); } catch (error) { notify(error.message,'error'); return; }
  const index = selectedProfileIndex;
  savePolicy(next => {
    const profiles = Array.isArray(next.profiles) ? next.profiles : (next.profiles = []);
    const conflict = profiles.findIndex((item,itemIndex) => itemIndex !== index && String(item.name).toLowerCase() === profile.name.toLowerCase());
    if (conflict >= 0) throw new Error('Profile name already exists');
    if (index >= 0) {
      const oldName = profiles[index].name;
      profiles[index] = profile;
      if (next.activeProfile && String(next.activeProfile).toLowerCase() === String(oldName).toLowerCase()) next.activeProfile = profile.name;
    } else profiles.push(profile);
  },'Profile saved');
  selectedProfileIndex = -1;
  document.getElementById('ct_profile_editor_panel').hidden = true;
}

function cloneProfile() {
  let profile;
  try { profile = collectProfile(); } catch (error) { notify(error.message,'error'); return; }
  const base = profile.name || 'Profile';
  let name = `${base} Copy`;
  let number = 2;
  const names = new Set((policyState.profiles || []).map(item => String(item.name).toLowerCase()));
  while (names.has(name.toLowerCase())) name = `${base} Copy ${number++}`;
  profile.name = name;
  profile.applications = [];
  savePolicy(next => { next.profiles.push(profile); },'Profile cloned');
  selectedProfileIndex = -1;
  document.getElementById('ct_profile_editor_panel').hidden = true;
}

function deleteProfile() {
  if (selectedProfileIndex < 0) return;
  const profile = policyState.profiles[selectedProfileIndex];
  if (!global.confirm || global.confirm(`Delete profile "${profile.name}"?`)) {
    const index = selectedProfileIndex;
    savePolicy(next => {
      const removed = next.profiles.splice(index,1)[0];
      if (next.activeProfile && removed && String(next.activeProfile).toLowerCase() === String(removed.name).toLowerCase()) next.activeProfile = null;
    },'Profile deleted');
    selectedProfileIndex = -1;
    document.getElementById('ct_profile_editor_panel').hidden = true;
  }
}

function exportProfiles() {
  const payload = JSON.stringify(stateForSave(policyState),null,2);
  try {
    if (typeof bridge.exportBlob === 'function') {
      bridge.exportBlob(new Blob([payload],{type:'application/json'}),'cleverestricky-profiles.json');
      return;
    }
  } catch (_) {}
  if (navigator.clipboard) navigator.clipboard.writeText(payload);
  notify('Profile policy copied/exported');
}

async function importProfiles(event) {
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (!file) return;
  try {
    if (file.size > 512*1024) throw new Error('Policy file is too large');
    const parsed = JSON.parse(await file.text());
    const body = new URLSearchParams();
    body.set('data',JSON.stringify(stateForSave(parsed)));
    policyState = await request('/api/policy_state',{method:'POST',body});
    renderAll();
    notify('Profile policy imported');
  } catch (error) { notify(error.message || 'Could not import profile policy','error'); }
}

async function inspectEffective() {
  const input = document.getElementById('ct_effective_package');
  const host = document.getElementById('ct_effective_result');
  const pkg = input ? input.value.trim() : '';
  if (!pkg || !host) return;
  try {
    const data = await request(`/api/effective_state?package=${encodeURIComponent(pkg)}`);
    const rows = [
      ['Scope',data.scope],['Matched profile',data.matchedProfile],['Matched rule',data.matchedApplicationRule],['Identity template',data.identityTemplate],['Keybox',data.keyboxReference],['DRM privacy',data.privacy],['Build identity',data.buildIdentity],['Attestation identity',data.attestationIdentity],['Telephony identity',data.telephonyIdentity],['Region identity',data.regionIdentity],['Identity refresh',data.identityRefresh],['Security Patch',data.securityPatchOverride],['Keystore core',data.keystoreCore],['KeyMint',data.keyMint],['Reboot required',data.rebootRequired]
    ];
    host.innerHTML = rows.map(([key,value]) => `<div class="row"><span>${escapeHtml(key)}</span><span class="tag">${escapeHtml(String(value ?? '-'))}</span></div>`).join('');
  } catch (error) { host.textContent = error.message || 'Could not inspect effective state'; }
}

function installAutoIdentityOverride() {
  const spoof = document.getElementById('spoof');
  if (!spoof) return;
  const button = [...spoof.querySelectorAll('button')].find(node => (node.textContent || '').toUpperCase().includes('AUTO IDENTITY'));
  if (!button || button.dataset.ctAutoIdentity === '1') return;
  button.dataset.ctAutoIdentity = '1';
  button.onclick = null;
  button.removeAttribute('onclick');
  button.addEventListener('click',async event => {
    event.preventDefault();
    if (button.disabled) return;
    const original = button.textContent;
    button.disabled = true;
    button.textContent = 'RESOLVING PIXEL IDENTITY...';
    notify('Resolving Pixel identity...','working');
    try {
      const data = await request('/api/auto_identity',{method:'POST',timeoutMs:18000});
      if (typeof global.loadIdentity === 'function') await global.loadIdentity();
      notify(`Identity ready: ${data.model || data.device || 'Pixel'} · ${data.build_id || data.buildId || 'current build'}`);
    } catch (error) {
      notify(/unavailable|timeout|timed out|network/i.test(error.message || '') ? 'Auto Identity source is temporarily unavailable. Try again later or choose a local template.' : (error.message || 'Auto Identity failed'),'error');
    } finally {
      button.disabled = false;
      button.textContent = original;
    }
  });
}

function resourceFeatureName(row) {
  const cell = row && row.cells && row.cells[0];
  if (!cell) return '';
  const first = cell.querySelector('div > div') || cell.querySelector('div') || cell;
  return (first.textContent || '').trim();
}

function sanitizeResourceTable() {
  const body = document.getElementById('resourceBody');
  if (!body) return;
  [...body.querySelectorAll('tr')].forEach(row => {
    const name = resourceFeatureName(row);
    const statusCell = row.cells && row.cells[1];
    if (statusCell) {
      const toggle = statusCell.querySelector('input[type="checkbox"]');
      if (toggle) {
        const enabled = toggle.checked;
        statusCell.replaceChildren();
        const badge = document.createElement('span');
        badge.className = 'ct-readonly-state';
        badge.textContent = enabled ? 'Enabled' : 'Disabled';
        statusCell.appendChild(badge);
      }
    }
    const impact = IMPACTS[name];
    if (!impact) return;
    const firstCell = row.cells && row.cells[0];
    if (!firstCell || firstCell.querySelector('.ct-impact-note')) return;
    const note = document.createElement('span');
    note.className = 'ct-impact-note';
    note.textContent = impact;
    const description = firstCell.querySelector('.res-desc') || firstCell;
    description.appendChild(note);
  });
}

function normalizedPackageNames() {
  return [...new Set(packages.map(value => typeof value === 'string' ? value : (value && (value.packageName || value.name)) || '').filter(Boolean))].sort();
}

function installPackagePicker(inputId) {
  const input = document.getElementById(inputId);
  if (!input || input.dataset.ctPackagePicker === '1') return;
  input.dataset.ctPackagePicker = '1';
  input.removeAttribute('list');
  const parent = input.parentElement;
  if (!parent) return;
  const wrapper = document.createElement('div');
  wrapper.className = 'ct-package-picker';
  parent.insertBefore(wrapper,input);
  wrapper.appendChild(input);
  const suggestions = document.createElement('div');
  suggestions.className = 'ct-package-suggestions';
  suggestions.hidden = true;
  wrapper.appendChild(suggestions);
  const render = () => {
    const query = input.value.trim().toLowerCase();
    const matches = normalizedPackageNames().filter(name => !query || name.toLowerCase().includes(query)).slice(0,24);
    suggestions.replaceChildren();
    matches.forEach(name => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'ct-package-option';
      button.textContent = name;
      button.addEventListener('pointerdown',event => event.preventDefault());
      button.onclick = () => { input.value = name; suggestions.hidden = true; input.dispatchEvent(new Event('change',{bubbles:true})); };
      suggestions.appendChild(button);
    });
    suggestions.hidden = matches.length === 0;
  };
  input.addEventListener('focus',render);
  input.addEventListener('input',render);
  input.addEventListener('blur',() => global.setTimeout(() => { suggestions.hidden = true; },100));
}

function installPackagePickers() {
  ['ct_profile_app_picker','ct_effective_package','ct_patch_package'].forEach(installPackagePicker);
}

function installResourceOwner() {
  const original = global.loadResourceUsage;
  if (typeof original === 'function' && !original.ctPolicyOwned) {
    const wrapped = async function() {
      const result = await original.apply(this,arguments);
      sanitizeResourceTable();
      return result;
    };
    wrapped.ctPolicyOwned = true;
    global.loadResourceUsage = wrapped;
  }
  sanitizeResourceTable();
}

function sanitizeErrors() {
  const original = global.notify;
  if (typeof original !== 'function' || original.ctPolicyWrapped) return;
  const wrapped = function(message,type) {
    let text = String(message || '');
    if (type === 'error' && /^[a-f0-9]{24,128}$/i.test(text.trim())) text = 'The operation failed. Open Logs for details.';
    return original(text,type);
  };
  wrapped.ctPolicyWrapped = true;
  global.notify = wrapped;
}

async function loadLegacyConfig() {
  try { legacyConfig = await request('/api/config'); } catch (_) { legacyConfig = legacyConfig || {}; }
}

async function loadReferenceData() {
  const tasks = [];
  tasks.push(request('/api/packages').then(value => {
    packages = Array.isArray(value) ? value : [];
    if (!packages.length && typeof bridge.listPackages === 'function') {
      const fallback = bridge.listPackages();
      packages = Array.isArray(fallback) ? fallback : [];
    }
  }).catch(() => {
    if (typeof bridge.listPackages === 'function') {
      const fallback = bridge.listPackages();
      packages = Array.isArray(fallback) ? fallback : [];
    } else packages = [];
  }));
  tasks.push(request('/api/keyboxes').then(value => { keyboxes = Array.isArray(value) ? value : []; }).catch(()=>{}));
  tasks.push(request('/api/config').then(value => { legacyConfig = value || {}; if (Array.isArray(value.templates)) templates = value.templates; }).catch(()=>{}));
  await Promise.all(tasks);
}

function renderAll() {
  if (!policyState) return;
  renderFeatureCenter();
  renderIdentityControls();
  installIdentityBanner();
  renderPatchPage();
  renderProfiles();
  const inspect = document.getElementById('ct_effective_load');
  if (inspect) inspect.onclick = inspectEffective;
  removeLegacySurfaces();
  sanitizeResourceTable();
  refreshPresentation();
}

function escapeHtml(value) {
  return String(value == null ? '' : value).replace(/[&<>"']/g,char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
}

async function initialize() {
  injectStyles();
  staticPages();
  installTabNavigationOwner();
  retireLegacyLocalization();
  installFeatureCenter();
  installIdentityControls();
  installConfigurationActions();
  installCustomTemplateBuilder();
  installKernelIdentityControls();
  installAppsProfileCard();
  removeLegacySurfaces();
  markIdentityActionGroups();
  installAutoIdentityOverride();

  try { policyState = await request('/api/policy_state'); }
  catch (error) { notify(`Policy controls unavailable: ${error.message}`,'error'); return; }
  await loadReferenceData();
  renderAll();
  sanitizeErrors();
  installResourceOwner();
  installPackagePickers();
}

onReady(initialize);

})(window);
