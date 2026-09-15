# Soporte para Magisk

**Idioma:** [English](../../../system/Magisk.md) | [Türkçe](../../tr/system/Magisk.md) | [简体中文](../../zh-CN/system/Magisk.md) | **Español** | [Deutsch](../../de/system/Magisk.md) | [Русский](../../ru/system/Magisk.md) | [Bahasa Indonesia](../../id/system/Magisk.md) | [हिन्दी](../../hi/system/Magisk.md) | [العربية](../../ar/system/Magisk.md)

## Aviso importante

> [!CAUTION]
> **Magisk NO está recomendado para CleveresTricky.**
>
> Los marcos modernos de detección de aplicaciones y Google Play Integrity inspeccionan activamente los espacios de montaje en espacio de usuario, los binarios de root y las inyecciones en Zygote. La arquitectura en espacio de usuario de Magisk deja huellas detectables que dificultan seriamente la ocultación a largo plazo.
>
> Para una comparativa arquitectónica detallada entre soluciones de root en kernel y en espacio de usuario, consulte:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **Siempre que sea posible, elija [KernelSU](https://kernelsu.org) o [APatch](https://apatch.dev).**

---

## Arquitectura sin WebUI (Modo Headless)

KernelSU y APatch proporcionan entornos integrados de extensión WebUI para módulos dentro de sus aplicaciones de gestión. Magisk no implementa este estándar.

Por lo tanto, al instalarse bajo Magisk, **CleveresTricky se ejecuta en modo daemon en segundo plano sin interfaz WebUI**. El daemon nativo (`cleverestrickyd`), el backend y los interceptores de Keystore funcionan a pleno rendimiento, pero la configuración debe gestionarse manualmente editando archivos en `/data/adb/cleverestricky/`.

---

## Guía de Configuración Manual

Todos los archivos de configuración residen en `/data/adb/cleverestricky/`.

### 1. Ámbito y Aplicaciones Objetivo

* **`target.txt`**: Nombres de paquetes (uno por línea) para la interceptación de attestation en Keystore:
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: Archivo marcador vacío.
  * **Presente**: Modo Global activo (intercepta todas las aplicaciones ajenas al sistema y root).
  * **Ausente**: Solo se interceptan los paquetes listados en `target.txt`.
  * *Comando:* `touch /data/adb/cleverestricky/global_mode` (habilitar) o `rm -f /data/adb/cleverestricky/global_mode` (deshabilitar).

* **`identity_target.txt`**: Paquetes objetivo para la suplantación de identidad del dispositivo.
* **`global_identity_mode`**: Archivo marcador vacío. Si existe, aplica la suplantación de identidad globalmente.

---

### 2. Identidad y Propiedades de Construcción

* **`spoof_build_vars`**: Propiedades a suplantar en formato `CLAVE=VALOR`:
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
* **`security_patch.txt`**: Fecha del parche de seguridad (ej. `2026-03-05`). Dejar en blanco o ausente para alineación automática con el sistema.
* **`boot_props_mode`**: Controla el modo de propiedades del bootloader (`auto`, `manual` o `disabled`).

---

### 3. Keybox de Hardware

* **`keybox.xml`**: Coloque el archivo XML de keybox directamente en `/data/adb/cleverestricky/keybox.xml`. Asegúrese de restringir los permisos (`chmod 600`).
* **`keyboxes/`**: Directorio para almacenar múltiples archivos de keybox.

---

### 4. Ámbito de DRM y Privacidad

* **`drm_packages.txt`**: Aplicaciones multimedia que deben excluirse de la interceptación de Keystore para preservar Widevine L1:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. Indicadores de Funciones (Archivos Marcadores)

Habilite funciones creando el archivo (`touch <archivo>`) o deshabilítelas eliminándolo (`rm -f <archivo>`):

| Archivo Marcador | Efecto al estar presente |
| :--- | :--- |
| `spoof_enabled` | Activa el motor de suplantación de identidad. |
| `spoof_build_identity` | Activa la suplantación de propiedades de construcción. |
| `auto_keybox_check` | Valida periódicamente las keyboxes y verifica revocaciones. |
| `drm_passthrough` | Activa la exclusión de DRM para los paquetes en `drm_packages.txt`. |
| `hide_sensitive_props` | Oculta propiedades sensibles de root, depuración y bootloader. |
| `tee_broken_mode` | Habilita fallback por software en dispositivos con TEE físico dañado. |
| `debug_logging` | Habilita registros nativos detallados en `native_runtime.log`. |

---

## Aplicación de Cambios y Verificación

### Aplicar Cambios de Configuración
Dado que en Magisk no existe el mecanismo de recarga dinámica de WebUI, se recomienda reiniciar el dispositivo:
```sh
su -c "reboot"
```

### Inspección de Registros
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### Verificación de Procesos
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### Generación de Reporte de Diagnóstico
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
El archivo de diagnóstico resultante se creará en `/data/adb/cleverestricky/bugreports/`.
