# Attestation

**Idioma:** [English](../../../security/Attestation.md) | [Türkçe](../../tr/security/Attestation.md) | [简体中文](../../zh-CN/security/Attestation.md) | **Español** | [Deutsch](../../de/security/Attestation.md) | [Русский](../../ru/security/Attestation.md) | [Bahasa Indonesia](../../id/security/Attestation.md) | [हिन्दी](../../hi/security/Attestation.md) | [العربية](../../ar/security/Attestation.md)

La capa de attestation ofrece compatibilidad controlada de cadenas de certificados para las aplicaciones seleccionadas, manteniendo la creación real de claves de Android y las operaciones criptográficas posteriores.

Los callers de infraestructura RKP permanecen siempre en la ruta genuina de provisioning de Android. Para los UID de aplicaciones objetivo, las respuestas correctas de `generateKey` y las lecturas posteriores de certificados mediante `getKeyEntry` comparten una sola ruta de compatibilidad, evitando que un mismo alias muestre hojas de attestation distintas.

La operación de clave privada sigue realizándose en Android KeyMint o StrongBox. Antes de activar material se validan correspondencia clave/certificado, algoritmo, cadena, vigencia, ambigüedad y revocación. La sustitución de certificados no crea una raíz de confianza hardware, no bloquea físicamente el bootloader ni garantiza un veredicto remoto.

## Aprovisionamiento de TEE por hardware y nota de recuperación en dispositivos desbloqueados

CleveresTricky requiere un subsistema KeyMint/TEE por hardware funcional y deliberadamente no simula ni emula un TEE o atestación por software falso. Cuando la comunicación con el TEE por hardware falla (`SECURE_HW_COMMUNICATION_FAILED` / código de error -49), el módulo detiene la interceptación para evitar bloqueos (deadlocks) en el framework.

En dispositivos Android con bootloader desbloqueado, el desbloqueo puede invalidar o romper la atestación de hardware o el aprovisionamiento remoto de claves (RKP):
- **Recuperación de TEE RKP en el dispositivo**: Como se demuestra en la [investigación de wuxianlin](https://wuxianlin.com/2025/11/12/oneplus-13-15-attestation-rkp-test/) (evaluado en dispositivos modernos incluyendo OnePlus 13/15), el aprovisionamiento de ID de dispositivo mediante `KmInstallKeybox` puede restaurar el TEE RKP genuino (y Widevine L1 RKP) sin inyectar claves filtradas. Esto opera como un mecanismo legítimo de aprovisionamiento/recuperación de TEE en el propio dispositivo a través de dispositivos Android desbloqueados, en lugar de una suplantación de Keystore o emulación por software.
- **Alcance y recomendaciones**:
  - El aprovisionamiento `KmInstallKeybox` en el dispositivo restaura la emisión genuina de certificados TEE RKP desde la infraestructura remota.
  - No restaura de forma universal la clave de atestación TEE fuera de línea de fábrica (offline TEE Attestation Key).
  - **Advertencia de riesgo**: Modificar la partición `persist` o el aprovisionamiento de TEE conlleva riesgo de corrupción permanente o brickeo. Realice siempre una copia de seguridad completa de `persist` y el firmware antes de intentar cualquier recuperación de TEE.
