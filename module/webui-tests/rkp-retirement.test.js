const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../template/webroot/index.html'), 'utf8');

test('retired RKP passthrough is presented as always-on protection', () => {
  assert.match(html, /RKP Protection/);
  assert.match(html, /Always on/i);
  assert.doesNotMatch(html, /RKP Bypass/);

  const settings = html.match(/const WEB_UI_SETTINGS = \[([^\]]+)\]/)?.[1] || '';
  assert.doesNotMatch(settings, /rkp_passthrough/);
  assert.doesNotMatch(html, /updateRkpStatus/);
  assert.doesNotMatch(html, /data\.rkp_passthrough/);
  assert.match(html, /id: 'rkp_protection'.*status: 'Always on'/s);
});
