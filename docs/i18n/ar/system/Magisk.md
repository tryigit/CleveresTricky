# دعم Magisk

**اللغة:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | [简体中文](../../zh-CN/system/Magisk.md) | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | [Bahasa Indonesia](../../id/system/Magisk.md) | [हिन्दी](../../hi/system/Magisk.md) | **العربية**

## تنبيه هام

> [!CAUTION]
> **لا يُنصح باستخدام Magisk مع CleveresTricky.**
>
> تفحص أطر الكشف الحديثة وGoogle Play Integrity مساحات نقاط التثبيت في مساحة المستخدم وملفات الروت الثنائية وحقن Zygote. تترك بنية Magisk آثاراً واضحة تجعل إخفاء الروت على المدى الطويل أمراً بالغ الصعوبة.
>
> للحصول على مقارنة معمارية مفصلة بين حلول الروت على مستوى النواة (Kernel) ومساحة المستخدم (Userspace):
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **يُوصى دائماً باختيار [KernelSU](https://kernelsu.org) أو [APatch](https://apatch.dev) كلما أمكن ذلك.**

---

## التشغيل بدون واجهة مستخدم (Headless Mode)

توفر تطبيقات إدارة KernelSU وAPatch بيئات ملحقة مدمجة لواجهة WebUI داخل تطبيقات الإدارة. لا يدعم تطبيق Magisk هذا المعيار.

لذا، عند التثبيت في بيئة Magisk، **يعمل CleveresTricky في الخلفية كخدمة خفية (Daemon) بدون واجهة WebUI**. تستمر الخدمة الثنائية (`cleverestrickyd`) والخلفية واعتراضات Keystore في العمل بكامل كفاءتها، وتتم إدارة كافة الإعدادات يدوياً عن طريق تعديل الملفات في `/data/adb/cleverestricky/`.

---

## دليل التهيئة اليدوية

توجد جميع ملفات الإعدادات والسياسات داخل الدليل `/data/adb/cleverestricky/`.

### 1. النطاق والتطبيقات المستهدفة

* **`target.txt`**: قائمة بأسماء الحزم المستهدفة لتزييف مصادقة Keystore (حزمة واحدة في كل سطر):
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: ملف علامة فارغ.
  * **عند وجوده**: يكون الوضع العام نشطاً (يتم اعتراض جميع التطبيقات غير التابعة للنظام أو الروت).
  * **عند غيابه**: يتم اعتراض التطبيقات المحددة فقط في `target.txt`.
  * *الأمر:* `touch /data/adb/cleverestricky/global_mode` (تفعيل) أو `rm -f /data/adb/cleverestricky/global_mode` (تعطيل).

* **`identity_target.txt`**: التطبيقات المستهدفة لتزييف هوية وبنية الجهاز.
* **`global_identity_mode`**: ملف علامة لتطبيق تزييف الهوية بشكل عام.

---

### 2. هوية الجهاز وخصائص البناء

* **`spoof_build_vars`**: خصائص الجهاز المطلوب تزييفها بصيغة `KEY=VALUE`:
  ```properties
  MANUFACTURER=Google
  MODEL=Pixel 8 Pro
  FINGERPRINT=google/husky/husky:14/UQ1A.240105.004/11269998:user/release-keys
  BRAND=google
  PRODUCT=husky
  DEVICE=husky
  RELEASE=14
  ID=UQ1A.240105.004
  INCREMENTAL=11269998
  TYPE=user
  TAGS=release-keys
  ```
* **`security_patch.txt`**: تاريخ التحديث الأمني (مثال `2026-03-05`). اتركه فارغاً للمطابقة التلقائية مع النظام.
* **`boot_props_mode`**: التحكم في محاكاة خصائص البوتلودر (`auto` أو `force` أو `disable`).

---

### 3. مفاتيح العتاد (Keybox)

* **`keybox.xml`**: ضع ملف XML لمفتاح المصادقة العتادي مباشرة في المسار `/data/adb/cleverestricky/keybox.xml`. تأكد من تأمين الصلاحيات (`chmod 600`).
* **`keyboxes/`**: دليل لحفظ ملفات keybox متعددة.
* **الرفع لا يستبدل الملفات أبداً:** يُحفظ Keybox الذي تسحبه أو تلصقه باسم `keybox.xml`؛ وإذا كان الاسم مستخدماً بالفعل يُستخدم تلقائياً الاسم الحر التالي (`keybox2.xml`، `keybox3.xml`، ...).
* **`disabled_keyboxes`**: قائمة الاستبعاد من المجموعة. كل سطر يحتوي على معرّف `النطاق:اسم الملف` (`keyboxes:keybox2.xml`، `root:keybox.xml`) يطابق الأسماء المعروضة في لوحة Keybox في WebUI. تبقى مفاتيح Keybox المدرجة ظاهرة وقابلة للإدارة لكنها لا تُحمَّل أبداً في مجموعة التصديق. تقرأ أزرار التعطيل والتفعيل في WebUI هذا الملف وتكتبه، لذا يمكن إدارته يدوياً أيضاً.

---

### 4. نطاق حماية DRM والخصوصية

* **`drm_packages.txt`**: تطبيقات الوسائط المعفاة من اعتراض Keystore للحفاظ على دعم Widevine L1:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. ملفات العلامات للخصائص (Feature Flags)

لتفعيل خاصية قم بإنشاء ملفها (`touch <file>`) ولتعطيلها احذف الملف (`rm -f <file>`):

| ملف العلامة | التأثير عند الوجود |
| :--- | :--- |
| `spoof_enabled` | تفعيل محرك تزييف الهوية. |
| `spoof_build_identity` | تفعيل تزييف خصائص البناء. |
| `auto_keybox_check` | التحقق التلقائي الدوري من صلاحية مفاتيح keybox وإلغائها. |
| `drm_passthrough` | تفعيل استثناء DRM للحزم المسجلة في `drm_packages.txt`. |
| `hide_sensitive_props` | إخفاء مؤشرات الروت والتصحيح وحالة البوتلودر الحساسة. |
| `tee_broken_mode` | حالة ترحيل/توافق قديمة. عند وجوده يحتفظ الخدمة بمعالجة الترحيل القديمة؛ تظل الحماية الجوهرية دون تغيير ويعمل قاطع الدائرة الفاشل بشكل منفصل عند فشل اتصال TEE العتادي. |
| `debug_logging` | تفعيل تسجيل السجلات البرمجية المفصلة في `native_runtime.log`. |

---

## تطبيق التغييرات والتحقق

### تطبيق الإعدادات
نظراً لعدم توفر WebUI لإعادة التحميل الفوري في Magisk، يُنصح بإعادة تشغيل الجهاز:
```sh
su -c "reboot"
```

### فحص سجلات التشغيل
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### التحقق من العمليات النشطة
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### إنشاء تقرير تشخيصي شامل
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
سيتم حفظ الأرشيف التشخيصي داخل الدليل `/data/adb/cleverestricky/bugreports/`.
