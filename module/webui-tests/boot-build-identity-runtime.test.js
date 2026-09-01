'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const repoRoot = path.resolve(__dirname, '..', '..');
const postFs = path.join(repoRoot, 'module/template/post-fs-data.sh');
const postMount = path.join(repoRoot, 'module/template/post-mount.sh');

const pixel = {
  'ro.build.fingerprint': 'google/husky/husky:14/AP1A.240405.002/11480754:user/release-keys',
  'ro.product.brand': 'google',
  'ro.product.device': 'husky',
  'ro.product.name': 'husky',
  'ro.product.manufacturer': 'Google',
  'ro.product.model': 'Pixel 8 Pro',
  'ro.build.id': 'AP1A.240405.002',
  'ro.build.version.release': '14',
  'ro.build.version.release_or_codename': '14',
  'ro.build.version.incremental': '11480754',
  'ro.build.version.security_patch': '2024-04-05',
};

const physical = {
  'ro.build.fingerprint': 'Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.0.23.XPBCNXM:user/release-keys',
  'ro.product.brand': 'Xiaomi',
  'ro.product.device': 'popsicle',
  'ro.product.name': 'popsicle',
  'ro.product.manufacturer': 'Xiaomi',
  'ro.product.model': '2509FPN0BC',
  'ro.build.id': 'CP2A.260605.016',
  'ro.build.version.release': '17',
  'ro.build.version.release_or_codename': '17',
  'ro.build.version.incremental': 'OS4.0.0.23.XPBCNXM',
  'ro.build.version.security_patch': '2026-06-05',
  'ro.build.version.sdk': '37',
};

function writeExecutable(filename, content) {
  fs.writeFileSync(filename, content, { mode: 0o755 });
}

function readProps(filename) {
  return JSON.parse(fs.readFileSync(filename, 'utf8'));
}

function writeProps(filename, value) {
  fs.writeFileSync(filename, JSON.stringify(value, null, 2));
}

function assertIdentity(actual, expected, context) {
  for (const [key, value] of Object.entries(expected)) {
    if (key === 'ro.build.version.sdk') continue;
    assert.equal(actual[key], value, `${context}: ${key}`);
  }
}

if (process.platform === 'win32') {
  console.log('Skipping boot-build-identity-runtime shell harness on Windows (runs on Linux CI)');
  process.exit(0);
}

function execute(script, env) {
  const result = spawnSync('/bin/sh', [script], {
    cwd: repoRoot,
    env,
    encoding: 'utf8',
  });
  assert.equal(
    result.status,
    0,
    `${path.basename(script)} failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`,
  );
}

const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ct-build-identity-'));
try {
  const configDir = path.join(tempRoot, 'config');
  const fakeBin = path.join(tempRoot, 'bin');
  const propDb = path.join(tempRoot, 'properties.json');
  fs.mkdirSync(configDir);
  fs.mkdirSync(fakeBin);

  // post-fs-data runs as root on-device. Make only the privileged metadata
  // commands no-ops here; the production script, marker checks and property
  // application path are otherwise executed unchanged.
  for (const command of ['chown', 'chmod', 'chcon', 'log']) {
    writeExecutable(path.join(fakeBin, command), '#!/bin/sh\nexit 0\n');
  }

  writeExecutable(
    path.join(fakeBin, 'getprop'),
    `#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const props = JSON.parse(fs.readFileSync(process.env.PROP_DB, 'utf8'));
process.stdout.write(String(props[process.argv[2]] ?? '') + '\\n');
`,
  );
  writeExecutable(
    path.join(fakeBin, 'resetprop'),
    `#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const db = process.env.PROP_DB;
const props = JSON.parse(fs.readFileSync(db, 'utf8'));
let key;
if (process.argv[2] === '--delete') {
  key = process.argv[3];
  if (key === process.env.FAIL_RESETPROP_KEY) process.exit(17);
  delete props[key];
} else if (process.argv[2] === '-n') {
  key = process.argv[3];
  if (key === process.env.FAIL_RESETPROP_KEY) process.exit(18);
  props[key] = process.argv[4] ?? '';
} else {
  process.exit(19);
}
fs.writeFileSync(db, JSON.stringify(props, null, 2));
`,
  );

  fs.writeFileSync(path.join(configDir, 'spoof_enabled'), '');
  fs.writeFileSync(path.join(configDir, 'spoof_build_identity'), '');
  fs.writeFileSync(path.join(configDir, 'global_identity_mode'), '');
  fs.writeFileSync(path.join(configDir, 'boot_props_mode'), 'auto\n');
  fs.writeFileSync(
    path.join(configDir, 'spoof_build_vars'),
    [
      `FINGERPRINT=${pixel['ro.build.fingerprint']}`,
      `BRAND=${pixel['ro.product.brand']}`,
      `DEVICE=${pixel['ro.product.device']}`,
      `PRODUCT=${pixel['ro.product.name']}`,
      `MANUFACTURER=${pixel['ro.product.manufacturer']}`,
      `MODEL=${pixel['ro.product.model']}`,
      `BUILD_ID=${pixel['ro.build.id']}`,
      `RELEASE=${pixel['ro.build.version.release']}`,
      `INCREMENTAL=${pixel['ro.build.version.incremental']}`,
      'TYPE=user',
      'TAGS=release-keys',
      `SECURITY_PATCH=${pixel['ro.build.version.security_patch']}`,
      '',
    ].join('\n'),
  );

  const env = {
    ...process.env,
    PATH: `${fakeBin}:${process.env.PATH}`,
    PROP_DB: propDb,
    CLEVERES_TRICKY_CONFIG_DIR: configDir,
    // Android 16+ removes this legacy property. A failure here used to return
    // from apply_early_properties() and silently skip Build Identity entirely.
    FAIL_RESETPROP_KEY: 'sys.oem_unlock_allowed',
  };

  writeProps(propDb, physical);
  execute(postFs, env);
  assertIdentity(
    readProps(propDb),
    pixel,
    'a core boot-property failure must not suppress enabled Build Identity',
  );

  // KernelSU/APatch can load module system.prop data after regular post-fs-data
  // scripts. Model a competing identity provider overwriting CT at that point.
  // post-mount must reassert CT before application processes snapshot Build.*.
  writeProps(propDb, { ...readProps(propDb), ...physical });
  assertIdentity(readProps(propDb), physical, 'test setup must model the downstream overwrite');
  execute(postMount, env);
  assertIdentity(
    readProps(propDb),
    pixel,
    'post-mount must restore the explicitly enabled CT identity after downstream property loading',
  );

  // Build Identity remains opt-in. The second-pass owner must not turn the
  // feature on merely because persisted Pixel values exist.
  fs.unlinkSync(path.join(configDir, 'spoof_build_identity'));
  writeProps(propDb, physical);
  execute(postMount, env);
  assertIdentity(readProps(propDb), physical, 'disabled Build Identity must preserve the physical property view');

  // Region Identity is an independent child feature. Even a region resetprop
  // failure must not suppress Build Identity.
  fs.writeFileSync(path.join(configDir, 'spoof_build_identity'), '');
  fs.writeFileSync(path.join(configDir, 'spoof_region_cn'), '');
  writeProps(propDb, physical);
  execute(postMount, { ...env, FAIL_RESETPROP_KEY: 'ro.boot.hwc' });
  assertIdentity(readProps(propDb), pixel, 'Region Identity failure must not suppress Build Identity');

  console.log('Build Identity survives Android 17 core-property failures and downstream post-fs property overwrites.');
} finally {
  fs.rmSync(tempRoot, { recursive: true, force: true });
}
