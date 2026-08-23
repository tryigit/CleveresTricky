'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.resolve('module/template/post-fs-data.sh');
assert.ok(fs.existsSync(script), 'post-fs-data.sh is missing');

const scriptText = fs.readFileSync(script, 'utf8');
const gateStart = scriptText.indexOf('policy_feature_enabled() {');
const gateEnd = scriptText.indexOf('\npromote_staged_identity() {', gateStart);
assert.ok(gateStart >= 0 && gateEnd > gateStart, 'boot policy gate functions are missing');
const gateSource = scriptText.slice(gateStart, gateEnd);

function runCase(policy, expectedEnabled) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ct-policy-identity-'));
    const config = path.join(root, 'config');
    fs.mkdirSync(config);
    fs.writeFileSync(path.join(config, 'spoof_build_identity'), '');
    if (policy !== null) {
        const text = typeof policy === 'string' ? policy : JSON.stringify(policy);
        fs.writeFileSync(path.join(config, 'policy_state_v2.json'), text);
    }

    const result = spawnSync(
        '/bin/sh',
        ['-c', `${gateSource}\noptional_marker_enabled buildIdentity spoof_build_identity`],
        {
            encoding: 'utf8',
            env: {
                ...process.env,
                CONFIG_DIR: config,
                CLEVERES_TRICKY_CONFIG_DIR: config
            }
        }
    );
    assert.equal(
        result.status,
        expectedEnabled ? 0 : 1,
        result.stderr || result.stdout || `unexpected policy gate status ${result.status}`
    );
    fs.rmSync(root, { recursive: true, force: true });
}

const basePolicy = enabled => ({
    version: 2,
    features: {
        buildIdentity: enabled,
        attestationIdentity: false,
        telephonyIdentity: false,
        regionIdentity: false,
        identityRefresh: false,
        securityPatch: false
    },
    securityPatch: {
        automaticThresholdMonths: 6,
        system: { mode: 'automatic' },
        vendor: { mode: 'automatic' },
        boot: { mode: 'automatic' }
    },
    profiles: [],
    activeProfile: null
});

runCase(basePolicy(false), false);
runCase(basePolicy(true), true);
runCase(null, true);
runCase('{not valid json', false);

const nestedOverride = {
    ...basePolicy(false),
    profiles: [{
        name: 'App Override',
        enabled: true,
        applications: ['com.example.app'],
        features: { buildIdentity: true }
    }]
};
runCase(nestedOverride, false);

const nestedDisable = {
    ...basePolicy(true),
    profiles: [{
        name: 'App Override',
        enabled: true,
        applications: ['com.example.app'],
        features: { buildIdentity: false }
    }]
};
runCase(nestedDisable, true);

console.log('v2 policy authority for early Build Identity passed');
