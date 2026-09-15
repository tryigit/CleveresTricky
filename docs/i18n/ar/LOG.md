# تسجيل السجلات والتشخيص (Logging and Diagnostics)

**اللغة:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | **العربية**

يوفر CleveresTricky العديد من الأدوات المضمنة لالتقاط لقطات التشخيص وسجلات التشغيل وحزم تقارير الأخطاء الطارئة. عند فتح تذكرة خطأ (issue) على GitHub أو الإبلاغ عن سلوك غير متوقع، يعد إرفاق التشخيص والسجلات أمرًا ضروريًا لتحديد السبب الجذري للمشكلة.

---

## الطريقة 1: واجهة إدارة WebUI (الموصى بها)

توفر WebUI أسهل وأسرع طريقة لجمع التشخيص مباشرة من هاتفك دون الحاجة إلى ADB أو كمبيوتر.

### أ. لقطة تشخيص الدعم (Support Diagnostics Snapshot)
1. افتح واجهة CleveresTricky WebUI من المتصفح أو تطبيق إدارة الروت.
2. انتقل إلى علامة التبويب **المعلومات والموارد (Info & Resources)**.
3. مرر لأسفل إلى قسم **تشخيصات الدعم (Support Diagnostics)**.
4. انقر فوق الزر **نسخ التشخيص (Copy Diagnostics)**.
5. الصق النص المنسوخ مباشرة في قسم **Support Diagnostics Snapshot** في تقرير المشكلة على GitHub.

> [!NOTE]
> لقطة التشخيص مقيدة تمامًا لحماية خصوصيتك. فهي تحتوي فقط على حالة الوحدة، وإصدار التشغيل، ونوع بيئة الروت، واستهلاك الذاكرة والمعالج، وحالة مفاتيح الميزات. لا يتم تضمين المفاتيح الخاصة، أو بيانات الاعتماد، أو شهادات keybox، أو أسماء الحزم، أو قيم الهوية مطلقًا.

### ب. سجلات WebUI المباشرة وتسجيل تصحيح الأخطاء (Debug Logging)
1. في WebUI، انتقل إلى علامة التبويب **السجلات (Logs)**.
2. قم بتفعيل مفتاح **Debug Logging** (يتحول المفتاح إلى اللون الأخضر). يؤدي ذلك إلى تمكين التتبع التفصيلي دون الحاجة إلى تثبيت إصدار تصحيح.
3. كرر المشكلة أو فحص Play Integrity على جهازك.
4. ارجع إلى علامة التبويب **Logs** في WebUI وانقر على **نسخ (Copy)**.
5. الصق السجلات في تقرير الخطأ الخاص بك.
6. قم بإيقاف تشغيل **Debug Logging** بعد الانتهاء للحفاظ على موارد الجهاز.

---

## الطريقة 2: حزمة تقرير الأخطاء الطارئ بنقرة واحدة (`action.sh`)

يحتوي CleveresTricky على أداة مؤتمتة تجمع سجلات النظام وحالة الوحدة وبيانات بيئة الروت في أرشيف مضغوط (`.tar.gz`).

### من خلال مدير الروت:
- في **KernelSU** أو **APatch**، اضغط على زر **إجراء (Action)** بجوار بطاقة وحدة CleveresTricky.

### من خلال Terminal (تطبيق Termux أو سطر أوامر الروت):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### موقع ملف التقرير:
- يتم حفظ الأرشيف الذي تم إنشاؤه في المسار:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<الطابع_الزمني>.tar.gz
  ```
  (ويتم نسخه تلقائيًا إلى مجلد `Download/` بالجهاز إذا توفرت الصلاحية).
- يمكنك إرفاق هذا الأرشيف مباشرة في مشكلتك على GitHub.

> [!TIP]
> يتم استبعاد ملفات Keybox XML/CBOX والشهادات الخاصة عمدًا من هذا الأرشيف لحماية أمانك وخصوصيتك.

---

## الطريقة 3: سجل تشغيل العملية الخلفية الأصلية (`native_runtime.log`)

تسجل الخدمة الأصلية في الخلفية عمليات التهيئة وخطافات النظام والحالة التشغيلية باستمرار في:
```
/data/adb/cleverestricky/native_runtime.log
```

لعرض أحدث السطور من خلال سطر الأوامر:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## الطريقة 4: Android Logcat (عبر ADB أو الطرفية)

إذا تعذر فتح واجهة WebUI أو فشل بدء تشغيل الوحدة، فإن logcat يوفر رؤية مباشرة لمرحلة الإقلاع وعمليات Binder.

### المراقبة المباشرة عبر ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### في تطبيق Termux أو طرفية الروت:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### التقاط إقلاع نظيف (بإعادة تشغيل Keystore):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### علامات الإقلاع الهامة:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### علامة فشل عتاد KeyMint / TEE:
إذا ظهر في السجلات لديك:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
يشير هذا إلى فشل الاتصال بعتاد TEE أو طبقة KeyMint HAL من الشركة المصنعة (رمز الخطأ -49 / 10). يوقف CleveresTricky التدخل عمداً لتجنب حدوث deadlock في النظام. راجع [Attestation.md](security/Attestation.md#تهيئة-tee-للعتاد-وملاحظة-استعادة-oneplus) لمعرفة خيارات استعادة TEE من جانب الجهاز (مثل استعادة TEE RKP لأجهزة OnePlus 13/15 المفتوحة).

> [!WARNING]
> راجع السجلات قبل نشرها للعامة. على الرغم من عدم تسجيل رموز WebUI أو بيانات الاعتماد، إلا أن أسماء طراز الجهاز ومعرفات العمليات (PID) وأسماء الحزم قد تكون مرئية.

---

## المطلوب تقديمه عند فتح تذكرة

عند الإبلاغ عن خطأ على GitHub، يرجى تضمين:
1. **لقطة تشخيص الدعم** (من علامة تبويب المعلومات في WebUI)
2. **السجلات** (من تبويب Logs مع تفعيل Debug Logging، أو ملف `action.sh`، أو مخرجات logcat)
3. طراز الجهاز، إصدار أندرويد، ونوع الروت المستخدم (KernelSU / APatch / Magisk)
