const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('module/template/webroot/ux.js', 'utf8');
const exposeMarker = '    global.CleveresI18n = Object.freeze({';
assert.ok(source.includes(exposeMarker), 'UX localization export marker is missing');
const instrumentedSource = source.replace(
  exposeMarker,
  '    global.__testEnsureFooterOrder = ensureFooterOrder;\n' + exposeMarker,
);

function node(text = '') {
  return {
    textContent: text,
    parentElement: null,
    nextElementSibling: null,
    dataset: Object.create(null),
    attributes: Object.create(null),
    setAttribute(name, value) { this.attributes[name] = String(value); },
    querySelector(selector) {
      if (selector === 'strong') return this.titleNode;
      if (selector === 'p') return this.copyNode;
      if (selector === 'a') return this.linkNode;
      return null;
    },
    addEventListener() {}
  };
}

function loadWidget(locale) {
  const dashboard = node();
  const card = node();
  const title = node('CleveresTech Community');
  const copy = node('Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.');
  const link = node('Join Telegram Community');
  title.parentElement = card;
  copy.parentElement = card;
  link.parentElement = card;
  card.parentElement = dashboard;
  card.titleNode = title;
  card.copyNode = copy;
  card.linkNode = link;

  const document = {
    readyState: 'loading',
    documentElement: { dataset: Object.create(null) },
    body: null,
    addEventListener() {},
    getElementById(id) {
      if (id === 'dashboard') return dashboard;
      if (id === 'cleveresCommunityCard') return card;
      return null;
    }
  };
  const context = {
    console,
    document,
    localStorage: { getItem() { return locale; }, setItem() {} },
    CleveresBridge: { openCommunity: async () => true },
    setTimeout() {},
    clearTimeout() {},
    addEventListener() {},
    open() {}
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(instrumentedSource, context, { filename: 'ux.js' });
  context.__testEnsureFooterOrder();
  return { card, title, copy, link, i18n: context.CleveresI18n };
}

const expected = {
  tr: {
    label: 'CleveresTech Telegram topluluğu',
    title: 'CleveresTech Topluluğu',
    action: 'Telegram Topluluğunu Aç'
  },
  'zh-CN': {
    label: 'CleveresTech Telegram 社区',
    title: 'CleveresTech 社区',
    action: '打开 Telegram 社区'
  },
  es: {
    label: 'comunidad de Telegram de CleveresTech',
    title: 'Comunidad de CleveresTech',
    action: 'Abrir comunidad de Telegram'
  },
  de: {
    label: 'CleveresTech-Telegram-Community',
    title: 'CleveresTech-Community',
    action: 'Telegram-Community öffnen'
  },
  ru: {
    label: 'Telegram-сообщество CleveresTech',
    title: 'Сообщество CleveresTech',
    action: 'Открыть Telegram-сообщество'
  },
  id: {
    label: 'komunitas Telegram CleveresTech',
    title: 'Komunitas CleveresTech',
    action: 'Buka Komunitas Telegram'
  },
  hi: {
    label: 'CleveresTech Telegram समुदाय',
    title: 'CleveresTech समुदाय',
    action: 'Telegram समुदाय खोलें'
  },
  ar: {
    label: 'مجتمع CleveresTech على Telegram',
    title: 'مجتمع CleveresTech',
    action: 'فتح مجتمع Telegram'
  }
};

for (const [locale, copy] of Object.entries(expected)) {
  const widget = loadWidget(locale);
  assert.strictEqual(widget.card.attributes['aria-label'], copy.label, `${locale} Telegram aria-label is not localized`);
  assert.strictEqual(widget.title.textContent, copy.title, `${locale} Telegram title is not localized`);
  assert.match(widget.copy.textContent, /Telegram|Telegram/i, `${locale} Telegram description lost its product name`);
  assert.notStrictEqual(widget.copy.textContent, 'Join our Telegram group for mutual help, testing, discussion, and development of CleveresTricky.', `${locale} Telegram description remains English`);
  assert.strictEqual(widget.link.textContent, copy.action, `${locale} Telegram action is not localized`);
  assert.strictEqual(widget.i18n.locale, locale);
}

const english = loadWidget('en');
assert.strictEqual(english.card.attributes['aria-label'], 'CleveresTech Telegram community');
assert.strictEqual(english.title.textContent, 'CleveresTech Community');
assert.strictEqual(english.link.textContent, 'Open Telegram Community');

console.log('Telegram widget localization behavior tests passed');
