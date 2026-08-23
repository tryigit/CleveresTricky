'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const script = path.resolve('module/template/post-fs-data.sh');
assert.ok(fs.existsSync(script), 'post-fs-data.sh is missing');

function runCase(policy, expectedApplied) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ct-policy-identity-'));
    const bin = path.join(root, 'bin');
    const config = path.join(root, 'config');
    const resetLog = path.join(root, 'resetprop.log');
    fs.mkdirSync(bin);
    fs.mkdirSync(config);
    fs.writeFileSync(path.join(config, 'spoof_enabled'), '');
    fs.writeFileSync(path.join(config, 'spoof_build_identity'), '');
    fs.writeFileSync(path.join(config, 'spoof_build_vars'), 'FINGERPRINT=google/test/device:16/TEST/1:user/release-keys\n');
    if (policy !== null) fs.writeFileSync(path.join(config, 'policy_state_v2.json'), JSON.stringify(policy));

    const resetprop = path.join(bin, 'resetprop');
    fs.writeFileSync(resetprop, '#!/bin/sh\nprintf "%s\\n" "$*" >> "$RESET_LOG"\n');
    fs.chmodSync(resetprop, 0o755);

    const result = spawnSync('/bin/sh', [script], {
        encoding: 'utf8',
        env: {
            ...process.env,
            PATH: `${bin}:${process.env.PATH}`,
            RESET_LOG: resetLog,
            CLEVERES_TRICKY_CONFIG_DIR: config,
            CLEVERES_TRICKY_IDENTITY_ONLY: '1'
        }
    });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const calls = fs.existsSync(resetLog) ? fs.readFileSync(resetLog, 'utf8') : '';
    assert.equal(calls.includes('ro.build.fingerprint'), expectedApplied, calls);
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

console.log('v2 policy authority for early Build Identity passed');
