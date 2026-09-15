# Attestation

**اللغة:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | **العربية**

توفر طبقة attestation توافقاً مضبوطاً لسلاسل الشهادات للتطبيقات المحددة مع إبقاء إنشاء المفاتيح الحقيقي في Android والعمليات التشفيرية اللاحقة كما هي.

تبقى نداءات بنية RKP دائماً على مسار provisioning الحقيقي في Android. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` الناجحة وقراءات الشهادة اللاحقة عبر `getKeyEntry` مسار توافق واحداً كي لا يعرض alias واحد شهادتي attestation leaf مختلفتين.

تظل عملية المفتاح الخاص من تنفيذ Android KeyMint أو StrongBox. وقبل تفعيل المادة يتم التحقق من تطابق المفتاح والشهادة والخوارزمية والسلسلة والصلاحية والالتباس وحالة revocation. استبدال الشهادة لا ينشئ hardware root of trust ولا يقفل bootloader فعلياً ولا يضمن remote verdict.

## تهيئة TEE للعتاد وملاحظة الاستعادة للأجهزة غير المقفلة

يتطلب CleveresTricky بنية تحتية صالحة للعمل لـ KeyMint/TEE للعتاد، ولا يحاكي عمداً بيئة TEE برمجية وهمية أو إثبات صحة زائفاً. عندما يفشل الاتصال بعتاد TEE (`SECURE_HW_COMMUNICATION_FAILED` / رمز الخطأ -49)، يوقف الموديول التدخل لمنع حدوث deadlock في النظام.

في أجهزة أندرويد المفتوحة أداة التحميل (Bootloader Unlocked)، يمكن أن يؤدي فتح القفل إلى تعطيل إثبات صحة العتاد أو التزويد عن بُعد للمفاتيح (RKP):
- **استعادة TEE RKP من جانب الجهاز**: كما هو موضح في [بحث wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) (تم اختباره على أجهزة تشمل OnePlus 13/15)، يمكن لتزويد معرّف الجهاز عبر `KmInstallKeybox` استعادة TEE RKP الأصلي (و Widevine L1 RKP) دون حقن مفاتيح مسرّبة. هذا الأسلوب خاص بالجهاز والبرنامج الثابت؛ لا يمكن ضمان النتائج على جميع الأجهزة. تحقق من توافق جهازك/برنامجه الثابت قبل المحاولة.
- **النطاق والتوصيات**:
  - يستعيد تزويد `KmInstallKeybox` من جانب الجهاز إصدار شهادات TEE RKP الأصلية من البنية التحتية للتزويد عن بُعد.
  - لا يستعيد مفتاح إثبات صحة TEE دون اتصال الأصلي من المصنع بشكل شامل.
  - **تحذير من المخاطر**: ينطوي تعديل قسم `persist` أو تهيئة TEE في العتاد على مخاطر تلف دائم أو تعطل الجهاز. احرص دائماً على أخذ نسخة احتياطية كاملة من `persist` والبرامج الثابتة قبل محاولة استعادة TEE.
