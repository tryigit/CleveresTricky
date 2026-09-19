# Profiles

**Idioma:** [English](../../../identity/Profiles.md) | [Türkçe](../../tr/identity/Profiles.md) | [简体中文](../../zh-CN/identity/Profiles.md) | **Español** | [Deutsch](../../de/identity/Profiles.md) | [Русский](../../ru/identity/Profiles.md) | [Bahasa Indonesia](../../id/identity/Profiles.md) | [हिन्दी](../../hi/identity/Profiles.md) | [العربية](../../ar/identity/Profiles.md)

Los perfiles aplican grupos de ajustes opcionales en una transacción validada; la protección central de boot, Keystore e infraestructura RKP permanece activa de forma independiente.

Daily Compatibility usa alcance dirigido y monitorización de keybox; Default es una configuración conservadora (aplicar el perfil Default o restablecer el entorno en la WebUI restaura `target.txt`, `identity_target.txt`, `drm_packages.txt`, `boot_props_mode` y `security_patch.txt` a los valores limpios por defecto y elimina todos los servidores remotos configurados junto con su contenido de keybox en caché); Maximum Compatibility activa Global Mode, build identity, identity refresh y telephony y desactiva DRM passthrough; Minimal desactiva identity opcional y comprobaciones programadas de keybox. Ninguno de estos presets cambia la protección de infraestructura RKP.

Las configuraciones antiguas pueden conservar el marcador retirado `rkp_passthrough`, pero el runtime ya no basa en él el comportamiento generated-key. Los perfiles version two pueden guardar aplicaciones, template, keybox validado, privacy, patch y opciones identity/DRM; el campo RKP heredado se conserva solo para migración y no es una opción activa del WebUI.
