# लॉगिंग और डायग्नोस्टिक्स (Logging and Diagnostics)

**भाषा:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | [Español](../es/LOG.md) | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | **हिन्दी** | [العربية](../ar/LOG.md)

CleveresTricky डायग्नोस्टिक स्नैपशॉट, रनटाइम लॉग और आपातकालीन बग रिपोर्ट एकत्रित करने के लिए कई अंतर्निहित तरीके प्रदान करता है। GitHub पर कोई समस्या दर्ज करते समय डायग्नोस्टिक्स और लॉग प्रदान करना समस्या का सटीक समाधान खोजने के लिए अनिवार्य है।

---

## तरीका 1: WebUI प्रबंधन इंटरफ़ेस (अनुशंसित)

WebUI बिना किसी कंप्यूटर या ADB की आवश्यकता के सीधे डिवाइस से डायग्नोस्टिक्स एकत्र करने का सबसे आसान और तेज़ तरीका है।

### क. सपोर्ट डायग्नोस्टिक्स स्नैपशॉट (Support Diagnostics Snapshot)
1. अपने ब्राउज़र या रूट मैनेजर में CleveresTricky WebUI खोलें।
2. **जानकारी और संसाधन (Info & Resources)** टैब पर जाएँ।
3. **सपोर्ट डायग्नोस्टिक्स (Support Diagnostics)** अनुभाग तक स्क्रॉल करें।
4. **कॉपी डायग्नोस्टिक्स (Copy Diagnostics)** बटन पर क्लिक करें।
5. कॉपी किए गए टेक्स्ट को सीधे अपनी GitHub समस्या रिपोर्ट के **Support Diagnostics Snapshot** अनुभाग में पेस्ट करें।

> [!NOTE]
> यह डायग्नोस्टिक स्नैपशॉट गोपनीयता के नियमों का पूर्ण पालन करता है। इसमें केवल मॉड्यूल स्थिति, रनटाइम संस्करण, रूट प्रकार, मेमोरी/सीपीयू उपयोग और फीचर फ्लैग शामिल होते हैं। इसमें कभी भी निजी कुंजियाँ, क्रेडेंशियल्स, keybox प्रमाणपत्र, पैकेज नाम या पहचान डेटा शामिल नहीं होते हैं।

### ख. WebUI लाइव लॉग और डिबग लॉगिंग (Debug Logging)
1. WebUI में **लॉग (Logs)** टैब पर जाएँ।
2. **Debug Logging** टॉगल को **चालू (ON)** करें (टॉगल का रंग हरा हो जाता है)। यह बिना किसी डिबग बिल्ड के विस्तृत रनटाइम ट्रेसिंग सक्षम करता है।
3. अपने डिवाइस पर समस्या या Play Integrity जाँच को पुनः दोहराएँ।
4. WebUI के **Logs** टैब पर वापस आएँ और **कॉपी (Copy)** बटन दबाएँ।
5. लॉग्स को अपनी बग रिपोर्ट में पेस्ट करें।
6. कार्य समाप्त होने पर सिस्टम संसाधनों की बचत के लिए **Debug Logging** को पुनः बंद कर दें।

---

## तरीका 2: वन-क्लिक आपातकालीन बग रिपोर्ट संग्रह (`action.sh`)

CleveresTricky में एक स्वचालित टूल है जो सिस्टम लॉग, मॉड्यूल स्थिति और रूट परिवेश की जानकारी को एक संपीड़ित फ़ाइल (`.tar.gz`) में संग्रहीत करता है।

### रूट मैनेजर के माध्यम से:
- **KernelSU** या **APatch** में, CleveresTricky मॉड्यूल कार्ड के आगे दिए गए **Action** बटन पर टैप करें।

### टर्मिनल (Termux / Root Shell) के माध्यम से:
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### रिपोर्ट संग्रह कहाँ मिलेगा:
- तैयार की गई फ़ाइल यहाँ सहेजी जाती है:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<टाइमस्टैम्प>.tar.gz
  ```
  (और अनुमत होने पर डिवाइस के `Download/` फ़ोल्डर में भी स्वचालित रूप से कॉपी हो जाती है)।
- आप इस फ़ाइल को सीधे अपनी GitHub समस्या में संलग्न कर सकते हैं।

> [!TIP]
> आपकी सुरक्षा सुनिश्चित करने के लिए Keybox XML/CBOX फ़ाइलें और निजी प्रमाणपत्र इस संग्रह से जानबूझकर बाहर रखे जाते हैं।

---

## तरीका 3: नेटिव डेमॉन रनटाइम लॉग (`native_runtime.log`)

नेटिव बैकग्राउंड सेवा अपनी सभी प्रक्रियाओं और स्थिति को निरंतर यहाँ दर्ज करती है:
```
/data/adb/cleverestricky/native_runtime.log
```

टर्मिनल में नवीनतम लॉग देखने के लिए:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## तरीका 4: Android Logcat (ADB या टर्मिनल)

यदि WebUI नहीं खुलती है या सेवाएँ प्रारंभ नहीं हो पाती हैं, तो Android logcat बूट और Binder प्रक्रियाओं की सीधी जानकारी देता है।

### ADB के माध्यम से लाइव निगरानी:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### Termux या लोकल रूट शेल में:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### साफ़ स्टार्टअप कैप्चर (Keystore को पुनः प्रारंभ करके):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### महत्वपूर्ण स्टार्टअप संकेत:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### KeyMint / TEE हार्डवेयर विफलता मार्कर:
यदि आपको लॉग में यह दिखाई देता है:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
यह इंगित करता है कि डिवाइस का हार्डवेयर TEE या वेंडर KeyMint HAL संचार करने में विफल हो रहा है (त्रुटि कोड -49 / 10)। CleveresTricky फ्रेमवर्क डेडलॉक को रोकने के लिए जानबूझकर इंटरसेप्शन रोक देता है। डिवाइस-साइड TEE रिकवरी विकल्पों के लिए [Attestation.md](security/Attestation.md#हार्डवेयर-tee-प्रोविज़निंग-और-oneplus-रिकवरी-नोट) देखें।

> [!WARNING]
> सार्वजनिक रूप से साझा करने से पहले अपने लॉग की जाँच कर लें। यद्यपि क्रेडेंशियल्स और WebUI टोकन कभी दर्ज नहीं होते, फिर भी डिवाइस मॉडल नाम, प्रक्रिया आईडी (PID) और पैकेज नाम दिख सकते हैं।

---

## समस्या दर्ज करते समय क्या शामिल करें

GitHub पर बग रिपोर्ट दर्ज करते समय कृपया निम्नलिखित विवरण अवश्य दें:
1. **सपोर्ट डायग्नोस्टिक्स स्नैपशॉट** (WebUI Info टैब से)
2. **लॉग्स** (Debug Logging चालू के साथ WebUI Logs टैब से, `action.sh` फ़ाइल, या logcat)
3. डिवाइस का मॉडल, Android संस्करण और रूट विधि (KernelSU / APatch / Magisk)
