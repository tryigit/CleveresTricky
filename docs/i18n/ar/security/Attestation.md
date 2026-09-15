# Attestation

**اللغة:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | **العربية**

توفر طبقة attestation توافقاً مضبوطاً لسلاسل الشهادات للتطبيقات المحددة مع إبقاء إنشاء المفاتيح الحقيقي في Android والعمليات التشفيرية اللاحقة كما هي.

تبقى نداءات بنية RKP دائماً على مسار provisioning الحقيقي في Android. وبالنسبة إلى UID التطبيقات المستهدفة، تستخدم ردود `generateKey` الناجحة وقراءات الشهادة اللاحقة عبر `getKeyEntry` مسار توافق واحداً كي لا يعرض alias واحد شهادتي attestation leaf مختلفتين.

تظل عملية المفتاح الخاص من تنفيذ Android KeyMint أو StrongBox. وقبل تفعيل المادة يتم التحقق من تطابق المفتاح والشهادة والخوارزمية والسلسلة والصلاحية والالتباس وحالة revocation. استبدال الشهادة لا ينشئ hardware root of trust ولا يقفل bootloader فعلياً ولا يضمن remote verdict.

## تهيئة TEE للعتاد وملاحظة استعادة OnePlus

يتطلب CleveresTricky بنية تحتية صالحة للعمل لـ KeyMint/TEE للعتاد، ولا يحاكي عمداً بيئة TEE برمجية وهمية أو إثبات صحة زائفاً. عندما يفشل الاتصال بعتاد TEE (`SECURE_HW_COMMUNICATION_FAILED` / رمز الخطأ 10)، يوقف الموديول التدخل لمنع حدوث deadlock في النظام.

في بعض الأجهزة غير المقفلة، يمكن أن يؤدي فتح أداة تحميل التشغيل (Bootloader) إلى تعطيل إثبات صحة العتاد أو إعداد RKP من جانب الجهاز:
- **إثبات صحة أجهزة OnePlus 13 / 15 غير المقفلة واستعادة TEE RKP**: كما هو موثق في [بحث wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/)، يؤدي فتح أداة تحميل التشغيل على أجهزة OnePlus 13 و 15 المختبرة إلى كسر TEE Attestation وتهيئة RKP.
- **بديل التزويد من جانب الجهاز**: يشير المقال إلى أن تزويد معرّف الجهاز عبر `KmInstallKeybox` يمكنه استعادة TEE RKP الأصلي (وفي النماذج المختبرة Widevine L1 RKP) دون حقن مفاتيح إثبات صحة مسربة. هذا بديل شرعي للتزويد/الاستعادة من جانب الجهاز لـ TEE، وليس انتحالاً أو محاكاة لـ Keystore.
- **النطاق والقيود**:
  - هذا الإجراء **لا** يستعيد مفتاح إثبات صحة TEE دون اتصال (offline TEE Attestation Key) بشكل شامل.
  - هو **خاص جداً بالجهاز والبرامج الثابتة (firmware)**. لا ينبغي التوصية به كحل عام أو افتراض دعمه لجميع طرازات OnePlus.
  - **تحذير من المخاطر**: ينطوي تعديل قسم `persist` أو تهيئة TEE في الجهاز على مخاطر كبيرة لتلف دائم في مخزن المفاتيح أو تعطل الجهاز كلياً (brick). احرص دائماً على أخذ نسخة احتياطية كاملة من `persist` والبرامج الثابتة قبل محاولة أي استعادة لـ TEE.
