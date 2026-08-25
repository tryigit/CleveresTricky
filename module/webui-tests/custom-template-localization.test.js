const fs = require('fs');
const assert = require('assert');
const uxLoader = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const uxCore = fs.readFileSync('module/template/webroot/ux-core.js', 'utf8');
const ux = `${uxLoader}\n${uxCore}`;
const required = [
  'Custom Templates','Create a reusable device identity template. The form stays collapsed until you open it.',
  'Template ID','Manufacturer','Fingerprint','Brand','Product','Device','Android release','Build ID','Incremental',
  'Build type','Build tags','Security patch','Save custom template','Custom template saved','Template ID is invalid',
  'Built-in template IDs cannot be replaced','All template fields are required','Security patch must be YYYY-MM-DD',
  'Template catalog is unavailable','Could not save custom template'
];
for (const key of required) assert(ux.includes(key), `missing localized custom-template string: ${key}`);
assert(ux.includes("'Custom Templates': 'Özel Şablonlar'"), 'Turkish custom-template translation missing');
console.log('custom-template localization checks passed');