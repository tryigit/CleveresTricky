# Attestation

**भाषा:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | **हिन्दी** | [العربية](../../ar/security/Attestation.md)

Attestation layer चुने हुए apps के लिए नियंत्रित certificate-chain compatibility देता है, जबकि Android की वास्तविक key creation और बाद की cryptographic operations बनी रहती हैं।

RKP infrastructure callers हमेशा Android के genuine provisioning path पर रहते हैं। Target app UID के लिए सफल `generateKey` replies और बाद की `getKeyEntry` certificate reads एक ही compatibility path का उपयोग करती हैं, ताकि एक alias अलग-अलग attestation leaf न दिखाए।

Private-key operation Android KeyMint या StrongBox ही करता है। Material active होने से पहले key/certificate match, algorithm, chain, validity, ambiguity और revocation जाँचे जाते हैं। Certificate substitution hardware root of trust नहीं बनाता, bootloader को physically lock नहीं करता और remote verdict की guarantee नहीं देता।

## हार्डवेयर TEE प्रोविज़निंग और अनलॉक डिवाइस रिकवरी नोट

CleveresTricky को एक कार्यात्मक हार्डवेयर KeyMint/TEE की आवश्यकता होती है और यह जानबूझकर नकली सॉफ़्टवेयर TEE या नकली अटेस्टेशन का अनुकरण नहीं करता है। जब हार्डवेयर TEE संचार विफल हो जाता है (`SECURE_HW_COMMUNICATION_FAILED` / त्रुटि कोड -49), तो मॉड्यूल फ्रेमवर्क गतिरोध (deadlock) को रोकने के लिए इंटरसेप्शन रोक देता है।

अनलॉक बूटलोडर वाले Android उपकरणों पर, अनलॉक करने से डिवाइस-साइड हार्डवेयर अटेस्टेशन या Remote Key Provisioning (RKP) टूट सकती है:
- **डिवाइस-साइड TEE RKP रिकवरी**: जैसा कि [wuxianlin के शोध](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) में प्रदर्शित किया गया है (OnePlus 13/15 सहित आधुनिक उपकरणों पर मूल्यांकित), `KmInstallKeybox` डिवाइस-आईडी प्रोविज़निंग लीक हुई कीज़ को इंजेक्ट किए बिना वास्तविक TEE RKP (और Widevine L1 RKP) को पुनर्स्थापित कर सकता है। यह अनलॉक किए गए Android उपकरणों में एक वैध डिवाइस-साइड TEE प्रोविज़निंग रिकवरी तंत्र के रूप में कार्य करता है, न कि कीस्टोर स्पूफ़ या सॉफ़्टवेयर अनुकरण।
- **दायरा और अनुशंसाएँ**:
  - डिवाइस-साइड `KmInstallKeybox` प्रोविज़निंग रिमोट प्रोविज़निंग इंफ्रास्ट्रक्चर से वास्तविक TEE RKP प्रमाणपत्र जारी करने को पुनर्स्थापित करता है।
  - यह फ़ैक्टरी ऑफ़लाइन TEE अटेस्टेशन कुंजी (offline TEE Attestation Key) को सार्वभौमिक रूप से पुनर्स्थापित नहीं करता है।
  - **जोखिम चेतावनी**: डिवाइस-साइड `persist` या हार्डवेयर TEE प्रोविज़निंग में बदलाव करने से स्थायी क्षति का जोखिम होता है। किसी भी TEE रिकवरी प्रयास से पहले हमेशा अपने `persist` और फर्मवेयर पार्टीशन का पूरा बैकअप लें।
