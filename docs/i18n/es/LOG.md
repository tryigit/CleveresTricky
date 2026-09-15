# Registro y Diagnóstico (Logging and Diagnostics)

**Idioma:** [English](../../../LOG.md) | [Türkçe](../tr/LOG.md) | [简体中文](../zh-CN/LOG.md) | **Español** | [Deutsch](../de/LOG.md) | [Русский](../ru/LOG.md) | [Bahasa Indonesia](../id/LOG.md) | [हिन्दी](../hi/LOG.md) | [العربية](../ar/LOG.md)

CleveresTricky proporciona múltiples mecanismos integrados para capturar instantáneas de diagnóstico, registros de ejecución e informes de errores de emergencia. Al abrir una incidencia (issue) o notificar un comportamiento inesperado, proporcionar diagnósticos y registros es fundamental para identificar la causa del problema.

---

## Método 1: Interfaz de Gestión WebUI (Recomendado)

La WebUI ofrece la forma más rápida y sencilla de recopilar diagnósticos sin necesidad de ADB ni de un ordenador.

### A. Instantánea de Diagnóstico de Soporte (Support Diagnostics Snapshot)
1. Abre la WebUI de CleveresTricky en tu navegador o administrador de root.
2. Ve a la pestaña **Información y Recursos (Info & Resources)**.
3. Desplázate hasta la sección **Diagnóstico de Soporte (Support Diagnostics)**.
4. Haz clic en **Copiar Diagnósticos (Copy Diagnostics)**.
5. Pega el texto directamente en tu informe de GitHub en la sección **Support Diagnostics Snapshot**.

> [!NOTE]
> La instantánea de diagnóstico respeta rigurosamente la privacidad. Contiene únicamente el estado del módulo, la versión de ejecución, el entorno de root, el consumo de memoria/CPU y los selectores de funciones. Nunca incluye claves privadas, credenciales, certificados keybox, nombres de paquetes ni valores de identidad.

### B. Registros en Vivo y Depuración (Debug Logging)
1. En la WebUI, dirígete a la pestaña **Registros (Logs)**.
2. Activa el interruptor **Debug Logging** (cambia a color verde). Esto habilita el seguimiento detallado en tiempo de ejecución sin necesidad de instalar un paquete de depuración.
3. Reproduce el fallo o la comprobación de integridad en tu dispositivo.
4. Vuelve a la pestaña **Logs** y pulsa **Copiar (Copy)**.
5. Pega los registros en tu reporte de error.
6. Desactiva **Debug Logging** una vez terminado para ahorrar recursos del sistema.

---

## Método 2: Archivo de Informe de Emergencia en un Clic (`action.sh`)

CleveresTricky incluye una herramienta automatizada que empaqueta registros del sistema, estado del módulo e información del entorno de root en un archivo comprimido (`.tar.gz`).

### Desde el Administrador de Root:
- En **KernelSU** o **APatch**, pulsa el botón **Acción (Action)** junto a la tarjeta del módulo CleveresTricky.

### Desde la Terminal (Termux / Shell de Root):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Ubicación del Informe:
- El archivo generado se guarda en:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<marca_de_tiempo>.tar.gz
  ```
  (y se copia automáticamente en la carpeta `Download/` de tu dispositivo si está disponible).
- Puedes adjuntar este archivo directamente a tu incidencia en GitHub.

> [!TIP]
> Los archivos XML/CBOX de keybox y las credenciales privadas quedan excluidos deliberadamente de este archivo para proteger tu seguridad.

---

## Método 3: Registro de Ejecución del Demonio Nativo (`native_runtime.log`)

El servicio nativo en segundo plano registra continuamente su inicio, ganchos (hooks) y estado operativo en:
```
/data/adb/cleverestricky/native_runtime.log
```

Para ver las últimas líneas mediante terminal:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Método 4: Android Logcat (ADB o Terminal)

Si la WebUI no abre o el módulo no inicia, Android logcat proporciona visibilidad directa sobre el arranque temprano y las transacciones de Binder.

### Captura en vivo mediante ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### En Termux o shell con permisos de root:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Captura limpia al reiniciar Keystore:
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Marcadores clave de inicio:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### Marcador de fallo de hardware KeyMint / TEE:
Si observa lo siguiente en sus registros:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
Esto indica que el TEE por hardware del dispositivo o la HAL KeyMint del fabricante no responden (código de error -49 / SECURE_HW_COMMUNICATION_FAILED). CleveresTricky detiene intencionalmente la interceptación para evitar un bloqueo en el framework. Consulte [Attestation.md](security/Attestation.md#aprovisionamiento-de-tee-por-hardware-y-nota-de-recuperaci%C3%B3n-en-dispositivos-desbloqueados) para conocer las opciones de recuperación de TEE en el dispositivo.

> [!WARNING]
> Revisa tus registros antes de publicarlos. Aunque las credenciales y los tokens de la WebUI nunca se registran, los nombres de modelos de dispositivos, IDs de procesos y nombres de paquetes pueden aparecer.

---

## Requisitos para el Reporte de Errores

Al abrir una incidencia en GitHub, por favor incluye:
1. **Instantánea de Diagnóstico de Soporte** (desde la pestaña Información de la WebUI)
2. **Registros** (de la pestaña Logs con Debug Logging activo, archivo `action.sh` o salida de logcat)
3. Modelo del dispositivo, versión de Android y método de Root (KernelSU / APatch / Magisk)
