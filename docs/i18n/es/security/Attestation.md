# Attestation

**Idioma:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | **Español** | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

La capa de attestation ofrece compatibilidad controlada de cadenas de certificados para las aplicaciones seleccionadas, manteniendo la creación real de claves de Android y las operaciones criptográficas posteriores.

Los callers de infraestructura RKP permanecen siempre en la ruta genuina de provisioning de Android. Para los UID de aplicaciones objetivo, las respuestas correctas de `generateKey` y las lecturas posteriores de certificados mediante `getKeyEntry` comparten una sola ruta de compatibilidad, evitando que un mismo alias muestre hojas de attestation distintas.

La operación de clave privada sigue realizándose en Android KeyMint o StrongBox. Antes de activar material se validan correspondencia clave/certificado, algoritmo, cadena, vigencia, ambigüedad y revocación. La sustitución de certificados no crea una raíz de confianza hardware, no bloquea físicamente el bootloader ni garantiza un veredicto remoto.

## Aprovisionamiento de TEE por hardware y nota de recuperación de OnePlus

CleveresTricky requiere un subsistema KeyMint/TEE por hardware funcional y deliberadamente no simula ni emula un TEE o atestación por software falso. Cuando la comunicación con el TEE por hardware falla (`SECURE_HW_COMMUNICATION_FAILED` / código de error 10), el módulo detiene la interceptación para evitar bloqueos (deadlocks) en el framework.

En ciertos dispositivos con bootloader desbloqueado, el desbloqueo puede invalidar o romper la atestación de hardware o el aprovisionamiento RKP del dispositivo:
- **Atestación / Recuperación de TEE RKP en OnePlus 13 / 15 desbloqueados**: Según lo documentado en la [investigación de wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/), el desbloqueo del bootloader en dispositivos OnePlus 13 y 15 rompe la atestación TEE y el aprovisionamiento RKP.
- **Alternativa de aprovisionamiento en el dispositivo**: El artículo informa que el aprovisionamiento de ID de dispositivo mediante `KmInstallKeybox` puede restaurar el TEE RKP genuino (y en los modelos probados Widevine L1 RKP) sin inyectar claves de atestación filtradas. Esta es una alternativa legítima de aprovisionamiento/recuperación de TEE en el propio dispositivo, no un falseo (spoof) ni emulación de Keystore.
- **Alcance y limitaciones**:
  - Esto **NO** restaura universalmente la clave de atestación TEE fuera de línea (offline TEE Attestation Key).
  - Es estrictamente **específico del dispositivo y firmware**. No debe recomendarse de forma genérica ni asumirse compatible con todos los modelos de OnePlus.
  - **Advertencia de riesgo**: Modificar la partición `persist` o el aprovisionamiento TEE en el dispositivo conlleva un riesgo significativo de corrupción permanente del almacén de claves o brickeo del dispositivo. Realice siempre una copia de seguridad completa de `persist` y el firmware antes de intentar cualquier recuperación de TEE.
