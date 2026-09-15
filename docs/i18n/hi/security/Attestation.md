# Attestation

**भाषा:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | [Español](../../es/security/Attestation.md) | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | **हिन्दी** | [العربية](../../ar/security/Attestation.md)

Attestation layer चुने हुए apps के लिए नियंत्रित certificate-chain compatibility देता है, जबकि Android की वास्तविक key creation और बाद की cryptographic operations बनी रहती हैं।

RKP infrastructure callers हमेशा Android के genuine provisioning path पर रहते हैं। Target app UID के लिए सफल `generateKey` replies और बाद की `getKeyEntry` certificate reads एक ही compatibility path का उपयोग करती हैं, ताकि एक alias अलग-अलग attestation leaf न दिखाए।

Private-key operation Android KeyMint या StrongBox ही करता है। Material active होने से पहले key/certificate match, algorithm, chain, validity, ambiguity और revocation जाँचे जाते हैं। Certificate substitution hardware root of trust नहीं बनाता, bootloader को physically lock नहीं करता और remote verdict की guarantee नहीं देता।

## हार्डवेयर TEE प्रोविज़निंग और OnePlus रिकवरी नोट

CleveresTricky को एक कार्यात्मक हार्डवेयर KeyMint/TEE की आवश्यकता होती है और यह जानबूझकर नकली सॉफ़्टवेयर TEE या नकली अटेस्टेशन का अनुकरण नहीं करता है। जब हार्डवेयर TEE संचार विफल हो जाता है (`SECURE_HW_COMMUNICATION_FAILED` / त्रुटि कोड 10), तो मॉड्यूल फ्रेमवर्क गतिरोध (deadlock) को रोकने के लिए इंटरसेप्शन रोक देता है।

कुछ अनलॉक किए गए उपकरणों पर, बूटलोडर अनलॉक करने से डिवाइस-साइड हार्डवेयर अटेस्टेशन या RKP प्रोविज़निंग टूट सकती है:
- **OnePlus 13 / 15 अनलॉक डिवाइस अटेस्टेशन / TEE RKP रिकवरी**: [wuxianlin के शोध](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) के अनुसार, परीक्षित OnePlus 13 और 15 उपकरणों पर बूटलोडर अनलॉक करने से TEE अटेस्टेशन और RKP प्रोविज़निंग टूट जाती है।
- **डिवाइस-साइड प्रोविज़निंग विकल्प**: लेख रिपोर्ट करता है कि `KmInstallKeybox` डिवाइस-आईडी प्रोविज़निंग लीक हुए अटेस्टेशन कीज़ को इंजेक्ट किए बिना वास्तविक TEE RKP (और परीक्षित मॉडलों पर Widevine L1 RKP) को पुनर्स्थापित कर सकता है। यह एक वैध डिवाइस-साइड TEE प्रोविज़निंग/रिकवरी विकल्प है, कोई कीस्टोर स्पूफ़ या अनुकरण नहीं।
- **दायरा और सीमाएँ**:
  - यह ऑफ़लाइन TEE अटेस्टेशन कुंजी (offline TEE Attestation Key) को सार्वभौमिक रूप से पुनर्स्थापित **नहीं** करता है।
  - यह पूरी तरह से **डिवाइस और फर्मवेयर-विशिष्ट** है। इसे सामान्य रूप से अनुशंसित नहीं किया जाना चाहिए और सभी OnePlus मॉडलों पर लागू नहीं माना जाना चाहिए।
  - **जोखिम चेतावनी**: डिवाइस-साइड `persist` या TEE प्रोविज़निंग में बदलाव करने से स्थायी कीस्टोर क्षति या डिवाइस के ब्रिक होने का गंभीर जोखिम होता है। किसी भी TEE रिकवरी प्रयास से पहले हमेशा अपने `persist` और फर्मवेयर पार्टीशन का पूरा बैकअप लें।
