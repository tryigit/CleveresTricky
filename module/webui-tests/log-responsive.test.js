'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const indexSource = fs.readFileSync(
  path.join(__dirname, '..', 'template', 'webroot', 'index.html'),
  'utf8',
);

assert.match(
  indexSource,
  /<div class="log-toolbar" role="group" aria-label="Log actions">/,
  'Logs actions must use a semantic toolbar container',
);
assert.match(
  indexSource,
  /\.log-toolbar\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\) repeat\(3, minmax\(124px, auto\)\)/s,
  'Logs toolbar must have a bounded desktop grid',
);
assert.match(
  indexSource,
  /@media screen and \(max-width: 700px\) \{[\s\S]*?\.log-toolbar\s*\{\s*grid-template-columns:\s*1fr;\s*\}/,
  'Logs toolbar must stack controls on narrow screens',
);
assert.match(
  indexSource,
  /<select id="logType" aria-label="Select Log Type">/,
  'Log type select must not carry a competing fixed inline layout',
);
assert.doesNotMatch(
  indexSource,
  /<select id="logType"[^>]*style=/,
  'Log type select must keep responsive sizing in the stylesheet',
);
assert.match(
  indexSource,
  /<textarea id="logViewer" class="log-viewer" wrap="soft"[^>]*aria-describedby="logHint">/,
  'Log viewer must soft-wrap and expose its description to assistive technology',
);
assert.match(
  indexSource,
  /#logViewer\s*\{[^}]*width:\s*100%;[^}]*box-sizing:\s*border-box;[^}]*overflow:\s*auto;/s,
  'Log viewer must be bounded to its panel and independently scrollable',
);
assert.match(
  indexSource,
  /#logViewer\s*\{[^}]*background:\s*var\(--input-bg\);[^}]*color:\s*var\(--text\);/s,
  'Log viewer must use defined theme variables',
);
assert.match(
  indexSource,
  /if \(id !== 'log' && logsRequestController\) \{[\s\S]*?logsRequestController\.abort\(\);/,
  'Leaving Logs must abort a pending refresh',
);

console.log('Logs responsive layout regression checks passed');

const fetchLogsStart = indexSource.indexOf('async function fetchLogs()');
const fetchLogsEnd = indexSource.indexOf('async function downloadLogs()', fetchLogsStart);
assert.ok(fetchLogsStart >= 0 && fetchLogsEnd > fetchLogsStart, 'fetchLogs implementation is missing');

const notifications = [];
const viewer = { value: '', scrollTop: 0, scrollHeight: 42 };
const logType = { value: 'cleverestricky' };
const calls = [];
let releaseFirst;
const firstResponse = new Promise(resolve => { releaseFirst = resolve; });
const context = {
  AbortController,
  document: {
    getElementById(id) {
      if (id === 'logType') return logType;
      if (id === 'logViewer') return viewer;
      return null;
    },
  },
  fetchAuth(url, options) {
    calls.push({ url, signal: options.signal });
    return calls.length === 1
      ? firstResponse
      : Promise.resolve({ ok: true, text: async () => 'newest logs' });
  },
  notify(message) { notifications.push(message); },
};
vm.runInNewContext(
  `let logsRequestController = null;\n${indexSource.slice(fetchLogsStart, fetchLogsEnd)}`,
  context,
  { filename: 'index.html#fetchLogs' },
);

(async () => {
  const first = context.fetchLogs();
  await new Promise(resolve => setTimeout(resolve, 0));
  const second = context.fetchLogs();
  assert.equal(calls.length, 2, 'a second refresh should start a replacement request');
  assert.equal(calls[0].signal.aborted, true, 'the stale refresh must be aborted');
  await second;
  releaseFirst({ ok: true, text: async () => 'stale logs' });
  await first;
  assert.equal(viewer.value, 'newest logs', 'a stale response must not overwrite the newest logs');
  assert.deepEqual(notifications, ['Logs refreshed'], 'aborted refreshes must not produce error or success noise');
  console.log('Logs refresh cancellation regression checks passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
