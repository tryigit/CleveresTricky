# WebUI locales

CleveresTricky WebUI translations are local-only and live in `ux.js`; switching language never requires network access.

## Add a language

1. Add `[locale, displayName]` to `SUPPORTED` in `ux.js`.
2. Add a `TRANSLATIONS[locale]` catalog for visible UI strings. English remains the fallback for missing strings.
3. Add `GUIDE[locale]` when a localized guide is available; otherwise the English guide is used.
4. Keep RTL locales covered by the `html[dir="rtl"]` rules and extend the direction rule if another RTL locale is added.
5. Do not create a locale-specific JS or CSS file. Localization must remain inside `ux.js` and use the existing WebUI file set.
6. Run `node --check module/template/webroot/ux.js` and the existing WebUI checks before opening a PR.

The language selector is rendered on Dashboard immediately after Feature Center, and the chosen locale is persisted in `localStorage` under `cleverestricky.language.v1`.
