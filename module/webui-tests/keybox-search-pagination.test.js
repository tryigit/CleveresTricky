'use strict';

const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

function locateUx() {
    const candidates = [
        path.resolve('template/webroot/ux.js'),
        path.resolve('module/template/webroot/ux.js')
    ];
    const file = candidates.find(candidate => fs.existsSync(candidate));
    if (!file) throw new Error('Could not locate module/template/webroot/ux.js');
    return fs.readFileSync(file, 'utf8');
}

test('stored keyboxes and Check All expose filtered selection, search and five-item pagination', () => {
    const source = locateUx();

    assert.match(source, /const PAGE_SIZE = 5;/);
    assert.match(source, /ct_keybox_select_filtered/);
    assert.match(source, /toggleFilteredSelection/);
    assert.match(source, /ct_verify_controls/);
    assert.match(source, /ct_verify_filter/);
    assert.match(source, /ct_verify_search/);
    assert.match(source, /ct_verify_clear/);
    assert.match(source, /ct_verify_pager/);
    assert.match(source, /input\.addEventListener\('input', applySearch\)/);
    assert.match(source, /input\.addEventListener\('search', applySearch\)/);
    assert.match(source, /if \(pages <= 1\)\s*\{\s*pager\.style\.display = 'none';/);
    assert.match(source, /filteredVerification/);
    assert.match(source, /\[item\.filename, item\.status, item\.certificate_serial, item\.details\]/);
    assert.match(source, /items\.slice\(\(verificationPage - 1\) \* PAGE_SIZE, verificationPage \* PAGE_SIZE\)/);
    assert.match(source, /\/api\/verify_keyboxes/);
    assert.match(source, /global\.verifyKeyboxes = verify/);
});
