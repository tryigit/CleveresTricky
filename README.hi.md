# CleveresTricky

**भाषा:** [English](README.md) | [Türkçe](README.tr.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Deutsch](README.de.md) | [Русский](README.ru.md) | [Bahasa Indonesia](README.id.md) | **हिन्दी** | [العربية](README.ar.md)

[![Release](https://img.shields.io/github/v/release/tryigit/CleveresTricky?display_name=tag&sort=semver&label=Release)](https://github.com/tryigit/CleveresTricky/releases/latest)
[![डाउनलोड](https://img.shields.io/github/downloads/tryigit/CleveresTricky/total?color=0A84FF&label=%E0%A4%A1%E0%A4%BE%E0%A4%89%E0%A4%A8%E0%A4%B2%E0%A4%9B%E0%A4%A1)](https://github.com/tryigit/CleveresTricky/releases)
![Android](https://img.shields.io/badge/Android-12--17-3DDC84?logo=android&logoColor=white)
![Module](https://img.shields.io/badge/Module-KernelSU%20%7C%20APatch-6f42c1)

CleveresTricky Android 12-17 के लिए KernelSU और APatch मॉड्यूल है। यह Android Keystore और attestation compatibility, Keybox/CBOX management, app targeting, optional identity controls, patch-level controls और privacy tools को एक mobile WebUI में लाता है।

पहले default settings से शुरू करें और केवल वही features चालू करें जिनकी आपको वास्तव में ज़रूरत है।

## आप क्या कर सकते हैं

- **Keybox/CBOX** files को manage, verify, select और rotate करें।
- Global Mode इस्तेमाल करें या अलग-अलग apps पर per-app rules लागू करें।
- Device/build, attestation, telephony, region और security patch presentation को आवश्यकता के अनुसार configure करें।
- Remote Key Provisioning flows को protect करें और DRM bypass का दावा किए बिना supported DRM identifiers की exposure कम करें।
- Settings का backup लें, effective state देखें और WebUI या module Action से diagnostics इकट्ठा करें।

## Quick start

1. Official [Releases](https://github.com/tryigit/CleveresTricky/releases/latest) page से latest ZIP डाउनलोड करें।
2. Android चल रहा हो तब ZIP को KernelSU या APatch से install करें।
3. अपने module manager से CleveresTricky WebUI खोलें।
4. केवल वही **Keybox या CBOX** जोड़ें जो आपका हो या जिसे test करने की आपको अनुमति हो।
5. पहले default configuration रखें; identity, app rules या privacy options केवल ज़रूरत होने पर enable करें।

Project के साथ कोई usable Keybox या private attestation key शामिल नहीं है।

## Supported environment

- Android **12-17** / API **31-37**
- **ARM64** और **x86-64**
- **KernelSU** और **APatch** (अनुशंसित, पूर्ण WebUI समर्थन)
- **Magisk** (हेडलेस / `/data/adb/cleverestricky/` के माध्यम से मैन्युअल कॉन्फ़िगरेशन, [अनुशंसित नहीं](https://tryigit.dev/advanced-android-root-architecture-concealment/))

Recovery installation समर्थित नहीं है।

## ज़रूरी बातें

CleveresTricky local compatibility path को बेहतर करता है, लेकिन remote result असली device, firmware, certification state, Google Play services, server policy और आपकी configuration पर निर्भर रहता है। यह किसी खास Play Integrity या attestation verdict की guarantee नहीं दे सकता।

यह bootloader को physically relock नहीं करता, Verified Boot measurements को rewrite नहीं करता, hardware Root of Trust को नहीं बदलता, modem/baseband को modify नहीं करता और DRM privacy controls को DRM bypass में नहीं बदलता।

केवल वही configuration और credentials इस्तेमाल करें जिन्हें उपयोग करने की आपको अनुमति हो।

## और जानकारी

- [Strong Integrity Guide](docs/i18n/hi/security/StrongIntegrityGuide.md) - Official, AOSP और Custom ROMs पर Google Play Integrity (MEETS_STRONG_INTEGRITY) पास करने के लिए त्वरित मार्गदर्शिका।
- [Magisk सपोर्ट गाइड](docs/i18n/hi/system/Magisk.md) - Magisk यूज़र्स के लिए हेडलेस मैन्युअल कॉन्फ़िगरेशन और रूट कंसीलमेंट गाइड।
- [Keybox Manager](docs/i18n/hi/security/KeyboxManager.md) - Keybox/CBOX loading, verification, selection और revocation checks।
- [Application Scope](docs/i18n/hi/identity/ApplicationScope.md) और [Application Rules](docs/i18n/hi/identity/ApplicationRules.md) - तय करें features किन apps पर लागू हों।
- [Build Identity](docs/i18n/hi/identity/BuildIdentity.md), [Telephony Identity](docs/i18n/hi/identity/TelephonyIdentity.md) और [Patch Levels](docs/i18n/hi/identity/PatchLevels.md) - optional identity controls।
- [RKP Protection](docs/i18n/hi/security/RkpProtection.md) और [DRM Privacy](docs/i18n/hi/system/DrmPassthrough.md) - platform compatibility और privacy behavior।
- [Backup and Restore](docs/i18n/hi/system/BackupRestore.md) - encrypted configuration backup और recovery।
- [Security Model](docs/i18n/hi/security/SecurityModel.md) और [Installer](docs/i18n/hi/system/Installer.md) - trust boundaries और installation details।

## मदद चाहिए?

WebUI के **Logs** page या module **Action** से emergency diagnostic report बनाएं। Share करने से पहले archive को देख लें, क्योंकि diagnostics में device और system information हो सकती है।

Common problems और troubleshooting steps के लिए [Diagnostics](docs/i18n/hi/system/Diagnostics.md) देखें।

## Project

[Changelog](CHANGELOG.md) · [Contributing](docs/i18n/hi/CONTRIBUTING.md) · [Languages](docs/i18n/hi/LANGUAGES.md) · [Licensing](LICENSING.md) · [Donate](docs/i18n/hi/DONATE.md) · [Telegram](https://t.me/cleverestech)
