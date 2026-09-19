# Magisk सपोर्ट

**भाषा:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | [简体中文](../../zh-CN/system/Magisk.md) | [Español](../../es/system/Magisk.md) | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | [Bahasa Indonesia](../../id/system/Magisk.md) | **हिन्दी** | [العربية](../../ar/system/Magisk.md)

## महत्वपूर्ण चेतावनी

> [!CAUTION]
> **CleveresTricky के लिए Magisk की अनुशंसा नहीं की जाती है।**
>
> आधुनिक ऐप डिटेक्शन फ्रेमवर्क और Google Play Integrity सक्रिय रूप से यूज़रस्पेस माउंट नेमस्पेस, रूट बाइनरी और Zygote इंजेक्शन का पता लगा सकते हैं। Magisk का यूज़रस्पेस आर्किटेक्चर ऐसे पहचान निशान छोड़ता है जो लंबे समय तक रूट को छिपाए रखना बेहद मुश्किल बनाते हैं।
>
> कर्नेल-स्तरीय और यूज़रस्पेस रूट आर्किटेक्चर के विस्तृत तुलनात्मक विश्लेषण के लिए कृपया यह गाइड पढ़ें:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **यदि संभव हो, तो हमेशा [KernelSU](https://kernelsu.org) या [APatch](https://apatch.dev) का उपयोग करें।**

---

## बिना WebUI आर्किटेक्चर (Headless मोड)

KernelSU और APatch अपने प्रबंधक ऐप्स में इनबिल्ट मॉड्यूल WebUI एक्सटेंशन वातावरण प्रदान करते हैं। Magisk इस इंटरफ़ेस मानक का समर्थन नहीं करता है।

इसलिए, Magisk के तहत इंस्टॉल होने पर, **CleveresTricky बिना WebUI के बैकग्राउंड डेमॉन मोड में चलता है**। नेटिव डेमॉन (`cleverestrickyd`), बैकएंड और Keystore इंटरसेप्टर पूरी तरह से काम करते हैं, लेकिन सभी सेटिंग्स को `/data/adb/cleverestricky/` में फ़ाइलों को संपादित करके मैन्युअल रूप से प्रबंधित किया जाना चाहिए।

---

## मैन्युअल कॉन्फ़िगरेशन गाइड

सभी सेटिंग्स और नीति फ़ाइलें `/data/adb/cleverestricky/` डायरेक्टरी में स्थित हैं।

### 1. दायरा और लक्ष्य ऐप्स

* **`target.txt`**: Keystore अटेस्टेशन स्पूफिंग के लिए लक्षित पैकेज नाम (प्रति पंक्ति एक):
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: खाली मार्कर फ़ाइल।
  * **उपस्थित होने पर**: ग्लोबल मोड सक्रिय है (सिस्टम और रूट के अलावा सभी ऐप्स इंटरसेप्ट होते हैं)।
  * **अनुपस्थित होने पर**: केवल `target.txt` में सूचीबद्ध ऐप्स ही इंटरसेप्ट होते हैं।
  * *कमांड:* `touch /data/adb/cleverestricky/global_mode` (सक्षम करें) या `rm -f /data/adb/cleverestricky/global_mode` (अक्षम करें)।

* **`identity_target.txt`**: डिवाइस पहचान स्पूफिंग के लिए लक्षित ऐप्स।
* **`global_identity_mode`**: पहचान स्पूफिंग को विश्व स्तर पर लागू करने के लिए मार्कर फ़ाइल।

---

### 2. डिवाइस पहचान और बिल्ड प्रॉपर्टीज़

* **`spoof_build_vars`**: `KEY=VALUE` प्रारूप में स्पूफ की जाने वाली डिवाइस प्रॉपर्टीज़:
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
* **`security_patch.txt`**: सुरक्षा पैच तिथि (उदा. `2026-03-05`)। स्वचालित संरेखण के लिए इसे खाली छोड़ें।
* **`boot_props_mode`**: बूटलोडर प्रॉपर्टी सिमुलेशन (`auto`, `force`, या `disable`)।

---

### 3. हार्डवेयर Keybox

* **`keybox.xml`**: अपनी मान्य हार्डवेयर अटेस्टेशन XML फ़ाइल को सीधे `/data/adb/cleverestricky/keybox.xml` पर रखें। अनुमतियाँ सुरक्षित रखें (`chmod 600`)।
* **`keyboxes/`**: एकाधिक कीबॉक्स फ़ाइलें संग्रहीत करने के लिए डायरेक्टरी।
* **अपलोड कभी ओवरराइट नहीं करते:** ड्रॉप या पेस्ट किया गया Keybox `keybox.xml` के रूप में सहेजा जाता है; यदि यह नाम पहले से लिया हुआ है तो अगला उपलब्ध नाम (`keybox2.xml`, `keybox3.xml`, ...) स्वतः उपयोग होता है।
* **`disabled_keyboxes`**: पूल ऑप्ट-आउट सूची। प्रत्येक पंक्ति में एक `स्कोप:फ़ाइलनाम` पहचानकर्ता होता है (`keyboxes:keybox2.xml`, `root:keybox.xml`), जो WebUI Keybox पैनल में दिखा जाने वाले फ़ाइल नामों से मेल खाता है। सूचीबद्ध Keybox दिखाई और प्रबंधनीय बने रहते हैं, परंतु अटेस्टेशन पूल में कभी लोड नहीं होते। WebUI के अक्षम और सक्षम बटन इस फ़ाइल को पढ़ते और लिखते हैं, इसलिए इसे मैन्युअल रूप से भी रखा जा सकता है।

---

### 4. DRM और गोपनीयता दायरा

* **`drm_packages.txt`**: Widevine L1 हार्डवेयर सुरक्षा बनाए रखने के लिए Keystore इंटरसेप्शन से बाहर रखे जाने वाले मीडिया ऐप्स:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. फीचर टॉगल (मार्कर फ़ाइलें)

फ़ीचर चालू करने के लिए फ़ाइल बनाएं (`touch <file>`), और बंद करने के लिए हटाएं (`rm -f <file>`):

| मार्कर फ़ाइल | उपस्थित होने पर प्रभाव |
| :--- | :--- |
| `spoof_enabled` | कोर पहचान स्पूफिंग इंजन को सक्रिय करता है। |
| `spoof_build_identity` | बिल्ड प्रॉपर्टीज़ स्पूफिंग को सक्रिय करता है। |
| `auto_keybox_check` | कीबॉक्स वैधता और निरस्तीकरण स्थिति की स्वतः जाँच करता है। |
| `drm_passthrough` | `drm_packages.txt` में सूचीबद्ध ऐप्स के लिए DRM पासथ्रू सक्षम करता है। |
| `hide_sensitive_props` | संवेदनशील रूट, डीबग और बूटलोडर प्रॉपर्टीज़ को छुपाता है। |
| `tee_broken_mode` | पुराना माइग्रेशन/अनुकूलता स्थिति। उपस्थित होने पर सेवा पुरानी माइग्रेशन प्रसंस्करण बनाए रखती है; कोर सुरक्षा अपरिवर्तित रहती है और हार्डवेयर TEE संचार विफलता पर सर्किट ब्रेकर अलग से सक्रिय होता है। |
| `debug_logging` | `native_runtime.log` में विस्तृत डीबग लॉगिंग सक्षम करता है। |

---

## परिवर्तन लागू करना और सत्यापन

### सेटिंग्स लागू करना
चूँकि Magisk में WebUI नहीं है, फ़ाइलों को संपादित करने के बाद डिवाइस को रीबूट करने की अनुशंसा की जाती है:
```sh
su -c "reboot"
```

### रनटाइम लॉग देखना
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### चल रही प्रक्रियाओं की पुष्टि
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### नैदानिक बग रिपोर्ट तैयार करना
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
तैयार रिपोर्ट `/data/adb/cleverestricky/bugreports/` में सहेजी जाएगी।
