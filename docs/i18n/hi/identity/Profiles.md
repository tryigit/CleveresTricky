# Profiles

**भाषा:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | **हिन्दी** | [العربية](../../ar/identity/Profiles.md)

Profiles optional settings के समूह को एक validated transaction में लागू करते हैं; core boot, Keystore और RKP infrastructure protection स्वतंत्र रूप से active रहती है।

Daily Compatibility targeted scope और keybox monitoring उपयोग करता है; Default conservative setup है (Default profile लागू करने या WebUI में environment reset करने से `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode`, और `security_patch.txt` साफ़ डिफ़ॉल्ट पर रीसेट हो जाते हैं और हर कॉन्फ़िगर किए गए रिमोट सर्वर तथा उसका cached keybox content हटा देते हैं); Maximum Compatibility Global Mode, build identity, identity refresh और telephony चालू करके DRM passthrough बंद करता है; Minimal optional identity और scheduled keybox checks बंद करता है। इनमें से कोई preset RKP infrastructure protection नहीं बदलता।

पुरानी configuration में retired `rkp_passthrough` marker रह सकता है, लेकिन generated-key behavior अब उस पर निर्भर नहीं है। Version two profiles app assignment, template, validated keybox, privacy, patch और optional identity/DRM choices रख सकते हैं; legacy RKP field केवल migration compatibility के लिए है और live WebUI option नहीं है।
