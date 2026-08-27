const assert = require('assert');
const fs = require('fs');

const root = 'module/template/webroot';
const webRootFiles = fs.readdirSync(root).sort();
assert.deepStrictEqual(
  webRootFiles,
  ['LOCALES.md', 'bridge.js', 'index.html', 'policy.js', 'ux.js'],
  'WebUI source tree must contain only the canonical entrypoint, runtime owners, and localization documentation'
);
assert.strictEqual(fs.readdirSync(root).filter(name => /\.(php|phtml|html?|inc)$/i.test(name) && name !== 'index.html').length, 0, 'legacy PHP/HTML entrypoints must not be reintroduced');
const runtimeScripts = fs.readdirSync(root).filter(name => name.endsWith('.js')).sort();
assert.deepStrictEqual(runtimeScripts, ['bridge.js', 'policy.js', 'ux.js'], 'WebUI runtime JS layout must stay fixed');

const ux = fs.readFileSync(`${root}/ux.js`, 'utf8');
const index = fs.readFileSync(`${root}/index.html`, 'utf8');
assert.ok(ux.includes('const MAX_SUPPORTED_FILES = 64;'), 'module ZIP import must respect the 64 active XML source runtime limit');
assert.ok(ux.includes('const MAX_XML_BYTES = 10 * 1024 * 1024;'), 'XML ZIP limit must remain 10 MiB');
assert.ok(ux.includes('const MAX_CBOX_BYTES = MAX_XML_BYTES + 36;'), 'CBOX ZIP limit must include the envelope header');
assert.ok(ux.includes('I understand that every supported XML/CBOX file in this ZIP will be imported individually.'), 'ZIP confirmation copy is missing');
assert.ok(index.includes('accept=".xml,.cbox,.zip"'), 'keybox picker must accept ZIP');
assert.ok(index.includes("lowerName.endsWith('.cbox') ? 10 * 1024 * 1024 + 36 : 10 * 1024 * 1024"), 'direct CBOX upload must include the envelope header');
assert.ok(!index.includes("if (!pwd.trim())"), 'legacy empty-password CBOX unlock must reach the backend');
assert.ok(index.includes("formData.append('password', pwd)"), 'CBOX unlock must submit the exact password value');
assert.ok(!fs.existsSync(`${root}/ux-core.js`) && !fs.existsSync(`${root}/zip-import.js`), 'feature-specific runtime JS must not be reintroduced');

console.log('WebUI ZIP runtime layout checks passed');
