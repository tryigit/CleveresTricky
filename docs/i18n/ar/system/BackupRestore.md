# Backup and Restore

**اللغة:** [English](../../../system/BackupRestore.md) | [Türkçe](../../tr/system/BackupRestore.md) | [简体中文](../../zh-CN/system/BackupRestore.md) | [Español](../../es/system/BackupRestore.md) | [Deutsch](../../de/system/BackupRestore.md) | [Русский](../../ru/system/BackupRestore.md) | [Bahasa Indonesia](../../id/system/BackupRestore.md) | [हिन्दी](../../hi/system/BackupRestore.md) | **العربية**

ينقل config وauthorized key material داخل authenticated encrypted archive. Export يحتاج password من 12 حرفا على الأقل ويستخدم allowlist، ويرفض symlink وunknown path والحجم الزائد.

Import يقبل encrypted CTSB فقط ويضع حدودا للرفع وعدد entries/keyboxes والحجم expanded. Traversal وduplicates وdirectories وsymlink destination وsettings/keybox غير الصالحة ترفض قبل الكتابة. Policy v2 تتحقق وتنشر snapshot كاملة. تُضمّن إعدادات الخوادم البعيدة أيضًا بصيغة مشفرة مرتبطة بالجهاز، ولا تُستعاد إلا إذا فُكّ تشفيرها وتحققت على الجهاز نفسه، ومع غياب الإدخال تبقى إعدادات الخوادم الحالية. تطبيق ملف Default يحذف كل الخوادم البعيدة ومحتوى keybox المخزّن مؤقتًا.
