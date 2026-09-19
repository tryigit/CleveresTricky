# Profiles

**اللغة:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | [Español](../../es/identity/Profiles.md) | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | **العربية**

تطبق Profiles مجموعة من الإعدادات الاختيارية في معاملة واحدة متحقق منها؛ وتظل حماية boot وKeystore وبنية RKP الأساسية فعالة بشكل مستقل.

يستخدم Daily Compatibility نطاقاً مستهدفاً ومراقبة keybox؛ وDefault إعداد محافظ (تطبيق Default أو إعادة ضبط البيئة في WebUI يعيد `target.txt` و`identity_target.txt` و`drm_packages.txt` و`boot_props_mode` و`security_patch.txt` إلى إعداداتها الافتراضية النظيفة، ويحذف كل خادم بعيد مُهيأ مع محتوى keybox المخزّن مؤقتاً)؛ ويشغل Maximum Compatibility ‏Global Mode وbuild identity وidentity refresh وtelephony مع تعطيل DRM passthrough؛ بينما يعطل Minimal الهوية الاختيارية وفحوص keybox المجدولة. لا يغير أي preset حماية بنية RKP.

قد تبقى علامة `rkp_passthrough` المتقاعدة في الإعدادات القديمة، لكن سلوك generated-key لم يعد يعتمد عليها. تستطيع Profiles version two حفظ تعيينات التطبيقات وtemplate وkeybox متحقق منه وprivacy وpatch وخيارات identity/DRM؛ أما حقل RKP القديم فيبقى فقط لتوافق الترحيل وليس خياراً حياً في WebUI.
