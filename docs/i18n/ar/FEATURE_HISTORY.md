# سجل الميزات

> **اللغات:** [English](../../../FEATURE_HISTORY.md) · [Türkçe](../tr/FEATURE_HISTORY.md) · **العربية** · [Deutsch](../de/FEATURE_HISTORY.md) · [Español](../es/FEATURE_HISTORY.md) · [Русский](../ru/FEATURE_HISTORY.md) · [Bahasa Indonesia](../id/FEATURE_HISTORY.md) · [हिन्दी](../hi/FEATURE_HISTORY.md) · [中文](../zh/FEATURE_HISTORY.md)

توثق هذه الصفحة التاريخ العام لتطوير الميزات الرئيسية في CleveresTricky مع روابط GitHub المباشرة.

## هوية الجهاز وAttestation

**#79، 2026-02-01**

إعدادات خاصة بالتطبيق ومعالجة `ATTESTATION_ID_*`، بما في ذلك IMEI وSerial.

https://github.com/tryigit/CleveresTricky/pull/79

**#139، 2026-02-05**

إنشاء هوية جهاز عشوائية، بما في ذلك IMEI وSerial، مع إنشاء من WebUI.

https://github.com/tryigit/CleveresTricky/pull/139

**#871، 2026-08-09**

عناصر تحكم بهوية الجهاز وDual-SIM على مستوى التطبيق تشمل IMEI وIMEI2 وMEID وIMSI وICCID ورقم الهاتف وSerial، مع نطاق التطبيق والملف ودورة حياة runtime.

https://github.com/tryigit/CleveresTricky/pull/871

## Keybox وAttestation

**#77، 2026-02-01**

إدارة وتحميل وتدوير عدة Keybox مع إدارة WebUI.

https://github.com/tryigit/CleveresTricky/pull/77

**#79، 2026-02-01**

التحقق من Keybox ومعالجة هوية Attestation الخاصة بالتطبيق.

https://github.com/tryigit/CleveresTricky/pull/79

## الملفات الشخصية ونطاق التطبيق وRuntime

**#376**

إدارة الملفات الشخصية والإعدادات وتطبيق الملفات من WebUI.

https://github.com/tryigit/CleveresTricky/pull/376

**#908**

حالة policy مفصلة، عناصر تحكم مستقلة في Security Patch، وProfiles وEffective State.

https://github.com/tryigit/CleveresTricky/pull/908

**#909**

Identity policy، تخصيص التطبيقات، تعيين Profiles، هوية runtime وأعمال WebUI.

https://github.com/tryigit/CleveresTricky/pull/909

## بنية Native وRust

**#876، 2026-08-09**

تحويل injector وBinder interception إلى Rust/native مع إدارة دورة الحياة وتقوية native.

https://github.com/tryigit/CleveresTricky/pull/876

## Identity وPrivacy والتكامل مع المنصة

**#476**

معالجة خصائص early boot، Build Identity المتعلقة بـ Play Integrity، Profiles وRandomization.

https://github.com/tryigit/CleveresTricky/pull/476

**#618**

تحسينات أداء Telephony interceptor ومعالجة الهوية.

https://github.com/tryigit/CleveresTricky/pull/618

**#910**

Auto Identity، التشخيص، WebUI وتحسينات أداء TEE.

https://github.com/tryigit/CleveresTricky/pull/910

**#952**

Randomization للهوية بشكل فردي ومجموعات، تحكم Visible SIM، رؤية الكاميرا وأعمال Identity runtime.

https://github.com/tryigit/CleveresTricky/pull/952

## StrongBox

**#1132، 2026-08-30**

إعادة توجيه StrongBox إلى TEE وتوحيد security level الخاص بـ Attestation. تم التراجع عن هذا التغيير لاحقاً ولا يوجد في `master` الحالي.

https://github.com/tryigit/CleveresTricky/pull/1132

## الخط الزمني

| التاريخ | PR | المجال |
|---|---:|---|
| 2026-02-01 | #77 | Multi-keybox |
| 2026-02-01 | #79 | App-specific attestation identity |
| 2026-02-05 | #139 | Randomized device identity |
| 2026-08-09 | #871 | Dual-SIM وApp-facing identity |
| 2026-08-09 | #876 | Rust/native runtime architecture |
| 2026-08-30 | #1132 | StrongBox وTEE redirection |
