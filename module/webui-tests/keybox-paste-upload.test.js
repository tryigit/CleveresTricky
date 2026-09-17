const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const indexSource = fs.readFileSync('module/template/webroot/index.html', 'utf8');

const fnStart = indexSource.indexOf('function transliterateTurkish(');
assert.ok(fnStart >= 0, 'transliterateTurkish function must exist in index.html');
const fnEnd = indexSource.indexOf('const WEB_UI_SETTINGS =', fnStart);
assert.ok(fnEnd > fnStart, 'savePastedKeybox end boundary must be found');

const implementation = indexSource.slice(fnStart, fnEnd);

// Architecture guard: verify that bare undeclared filenameInput is NOT used
assert.ok(!implementation.includes('/rkp/i.test(filenameInput);'), 'Must not reference undeclared filenameInput');
assert.ok(implementation.includes('document.getElementById(\'kbFilenameInput\')'), 'Must read filename from kbFilenameInput element');
assert.ok(implementation.includes('transliterateTurkish'), 'Must call transliterateTurkish');

async function runTest() {
  const elements = {
    kbContent: { value: '' },
    kbFilenameInput: { value: '' },
    keyboxStatus: { innerText: '' }
  };

  const notifications = [];
  let fetchAuthCalls = [];

  function createTestContext() {
    const ctx = {
      console,
      FormData: class {
        constructor() { this.data = new Map(); }
        append(key, val) { this.data.set(key, val); }
        get(key) { return this.data.get(key); }
      },
      document: {
        getElementById: (id) => elements[id] || null
      },
      notify: (msg, type) => { notifications.push({ msg, type }); },
      fetchAuth: async (url, options) => {
        fetchAuthCalls.push({ url, options });
        return {
          ok: true,
          async text() { return 'OK'; },
          clone() {
            return {
              async json() { return { status: 'ok', filename: options.body.get('filename'), keybox_count: 5 }; }
            };
          }
        };
      },
      loadKeyInfo: () => {}
    };
    ctx.window = ctx;
    ctx.global = ctx;
    vm.createContext(ctx);
    vm.runInContext(implementation + '\nthis.savePastedKeybox = savePastedKeybox;\nthis.transliterateTurkish = transliterateTurkish;', ctx);
    return ctx;
  }

  // Test 1: Empty content shows error notification
  {
    const ctx = createTestContext();
    elements.kbContent.value = '   ';
    elements.kbFilenameInput.value = 'test.xml';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 0, 'Must not send request when content is empty');
    assert.ok(notifications.some(n => n.msg.includes('Please paste XML content first')), 'Must notify empty content error');
  }

  // Test 2: Custom filename provided in input field (the bug reported in the screenshot)
  {
    const ctx = createTestContext();
    elements.kbContent.value = '<Keybox><CertificateChain/></Keybox>';
    elements.kbFilenameInput.value = 'keybox2.xml';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 1, 'Must send request');
    assert.equal(fetchAuthCalls[0].url, '/api/upload_keybox');
    assert.equal(fetchAuthCalls[0].options.body.get('filename'), 'keybox2.xml');
    assert.equal(elements.kbContent.value, '', 'Must clear kbContent on success');
    assert.equal(elements.kbFilenameInput.value, '', 'Must clear kbFilenameInput on success');
    assert.equal(elements.keyboxStatus.innerText, '5 Keys Loaded');
  }

  // Test 3: Empty filename defaults to keybox.xml for non-RKP keybox
  {
    const ctx = createTestContext();
    elements.kbContent.value = '<Keybox><CertificateChain/></Keybox>';
    elements.kbFilenameInput.value = '';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 1);
    assert.equal(fetchAuthCalls[0].options.body.get('filename'), 'keybox.xml', 'Must default to keybox.xml when filename empty');
  }

  // Test 4: Empty filename defaults to rkp.xml for RKP content
  {
    const ctx = createTestContext();
    elements.kbContent.value = '<Keybox><CertificateChain>...droid ca...</CertificateChain></Keybox>';
    elements.kbFilenameInput.value = '';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 1);
    assert.equal(fetchAuthCalls[0].options.body.get('filename'), 'rkp.xml', 'Must default to rkp.xml when RKP detected');
    assert.equal(fetchAuthCalls[0].options.body.get('rkp_hint'), 'true', 'Must include rkp_hint');
  }

  // Test 5: Filename without .xml extension automatically appends .xml
  {
    const ctx = createTestContext();
    elements.kbContent.value = '<Keybox><CertificateChain/></Keybox>';
    elements.kbFilenameInput.value = 'custom_keybox';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 1);
    assert.equal(fetchAuthCalls[0].options.body.get('filename'), 'custom_keybox.xml', 'Must append .xml extension');
  }

  // Test 6: Turkish characters in filename are transliterated to ASCII
  {
    const ctx = createTestContext();
    assert.equal(ctx.transliterateTurkish('sağlam_şüpheli_özel_çözüm_üretici_ışık'), 'saglam_supheli_ozel_cozum_uretici_isik');
    assert.equal(ctx.transliterateTurkish('SAĞLAM_ŞÜPHELİ_ÖZEL_ÇÖZÜM_ÜRETİCİ_IŞIK'), 'SAGLAM_SUPHELI_OZEL_COZUM_URETICI_ISIK');

    elements.kbContent.value = '<Keybox><CertificateChain/></Keybox>';
    elements.kbFilenameInput.value = 'sağlam_özel.xml';
    notifications.length = 0;
    fetchAuthCalls.length = 0;

    await ctx.savePastedKeybox();
    assert.equal(fetchAuthCalls.length, 1);
    assert.equal(fetchAuthCalls[0].options.body.get('filename'), 'saglam_ozel.xml', 'Must transliterate Turkish characters in filename');
  }

  console.log('All keybox-paste-upload tests passed successfully.');
}

runTest().catch(err => {
  console.error(err);
  process.exit(1);
});
