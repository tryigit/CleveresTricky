# Magisk Support

**Language:** **English** | [Türkçe](../i18n/tr/system/Magisk.md) | [简体中文](../i18n/zh-CN/system/Magisk.md) | [Español](../i18n/es/system/Magisk.md) | [Deutsch](../i18n/de/system/Magisk.md) | [Русский](../i18n/ru/system/Magisk.md) | [Bahasa Indonesia](../i18n/id/system/Magisk.md) | [हिन्दी](../i18n/hi/system/Magisk.md) | [العربية](../i18n/ar/system/Magisk.md)

## Advisory Notice

> [!CAUTION]
> **Magisk is NOT recommended for CleveresTricky.**
>
> Modern application detection frameworks and Google Play Integrity actively inspect userspace mount namespaces, root binaries, and Zygote hooks. Magisk's userspace architecture leaves noticeable detection footprints that make long-term root concealment significantly harder.
>
> For a comprehensive architectural comparison between kernel-level and userspace root implementations, read:
> 👉 **[Advanced Android Root Guide: KernelSU, APatch & Concealment | Yiğit - tryigit.dev](https://tryigit.dev/advanced-android-root-architecture-concealment/)**
>
> **Whenever possible, prefer [KernelSU](https://kernelsu.org) or [APatch](https://apatch.dev).**

---

## Headless Architecture (No WebUI)

KernelSU and APatch provide built-in module WebUI extension environments within their manager applications. Magisk does not implement this interface.

Consequently, when installed under Magisk, **CleveresTricky runs in headless daemon mode without a WebUI**. The core native daemon (`cleverestrickyd`), backend, and Keystore interceptors function fully, but configuration must be performed manually by editing files in `/data/adb/cleverestricky/`.

---

## Manual Configuration Guide

All module settings and policy files reside in `/data/adb/cleverestricky/`.

### 1. Scope & Targeting

* **`target.txt`**: Package names (one per line) targeted for Keystore attestation spoofing.
  ```text
  com.google.android.gms
  com.google.android.gms.unstable
  com.android.vending
  ```
* **`global_mode`**: Empty marker file.
  * **Present**: Global Mode is active (intercepts all non-system, non-root applications).
  * **Absent**: Only applications explicitly listed in `target.txt` are intercepted.
  * *Command:* `touch /data/adb/cleverestricky/global_mode` (enable) or `rm -f /data/adb/cleverestricky/global_mode` (disable).

* **`identity_target.txt`**: Package names targeted for device build property spoofing.
* **`global_identity_mode`**: Empty marker file. If present, applies identity property spoofing globally.

---

### 2. Device Identity & Build Spoofing

* **`spoof_build_vars`**: Device model and build properties to spoof in `KEY=VALUE` format:
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
* **`security_patch.txt`**: Security patch date (e.g., `2026-03-05`). Leave empty or absent for automatic system date alignment.
* **`boot_props_mode`**: Controls bootloader property simulation (`auto`, `force`, or `disable`).

---

### 3. Hardware Keybox

* **`keybox.xml`**: Place your hardware-backed attestation keybox XML directly at `/data/adb/cleverestricky/keybox.xml`. Ensure permissions are restricted (`chmod 600`).
* **`keyboxes/`**: Directory for placing multiple keybox files.
* **Uploads never overwrite:** Dropping or pasting a keybox is stored as `keybox.xml`; when that name is already taken, the next free name (`keybox2.xml`, `keybox3.xml`, ...) is used automatically.
* **`disabled_keyboxes`**: Pool opt-out list. Each line holds one `scope:filename` identifier (`keyboxes:keybox2.xml`, `root:keybox.xml`) matching the filenames shown in the WebUI Keybox panel. Listed keyboxes stay visible and manageable but are never loaded into the attestation pool. The WebUI Disable and Enable buttons read and write this file, so you can also maintain it by hand.

---

### 4. DRM & Privacy Scope

* **`drm_packages.txt`**: Package names for media applications that should bypass Keystore interception to preserve Widevine L1 hardware DRM:
  ```text
  com.netflix.mediaclient
  com.amazon.avod.thirdpartyclient
  com.disney.disneyplus
  ```

---

### 5. Feature Flags (Marker Files)

Enable features by creating the marker file (`touch <file>`), or disable them by removing it (`rm -f <file>`):

| Marker File | Effect When Present |
| :--- | :--- |
| `spoof_enabled` | Activates the core identity spoofing engine. |
| `spoof_build_identity` | Activates build property spoofing. |
| `auto_keybox_check` | Automatically validates keyboxes and checks revocation status. |
| `drm_passthrough` | Enables DRM passthrough protection for packages in `drm_packages.txt`. |
| `hide_sensitive_props` | Conceals root, debugging, and bootloader status properties. |
| `tee_broken_mode` | Legacy migration/compatibility state. When present, the service retains legacy migration handling; core protection is unchanged and the fail-closed circuit breaker activates separately upon hardware TEE communication failure. |
| `debug_logging` | Enables verbose runtime logging to `native_runtime.log`. |

---

## Applying Changes & Verification

### Applying Configuration Changes
Since WebUI dynamic reload is not present on Magisk, rebooting the device is recommended after modifying configuration or marker files:
```sh
su -c "reboot"
```

### Inspecting Runtime Logs
To check whether the CleveresTricky daemon and interceptor are operating correctly:
```sh
su -c "cat /data/adb/cleverestricky/native_runtime.log"
```

### Verifying Running Processes
```sh
su -c "ps -A | grep -E 'cleverestrickyd|cleverestricky_backend'"
```

### Generating Diagnostic Bug Reports
To gather a complete diagnostic archive:
```sh
su -c "/data/adb/modules/cleverestricky/action.sh"
```
The resulting archive will be created in `/data/adb/cleverestricky/bugreports/`.
