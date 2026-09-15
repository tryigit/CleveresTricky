# Logging and Diagnostics

**Language:** **English** | [Türkçe](docs/i18n/tr/LOG.md) | [简体中文](docs/i18n/zh-CN/LOG.md) | [Español](docs/i18n/es/LOG.md) | [Deutsch](docs/i18n/de/LOG.md) | [Русский](docs/i18n/ru/LOG.md) | [Bahasa Indonesia](docs/i18n/id/LOG.md) | [हिन्दी](docs/i18n/hi/LOG.md) | [العربية](docs/i18n/ar/LOG.md)

CleveresTricky provides several built-in mechanisms for capturing diagnostic snapshots, runtime logs, and emergency bug reports. When opening an issue or reporting an unexpected behavior, providing diagnostics and logs is critical to diagnosing the problem.

---

## Method 1: WebUI Management Interface (Recommended)

The WebUI offers the quickest, easiest way to collect diagnostics without needing ADB or a computer.

### A. Support Diagnostics Snapshot
1. Open the CleveresTricky WebUI in your browser or module manager.
2. Navigate to the **Info & Resources** tab.
3. Scroll to the **Support Diagnostics** section.
4. Click **Copy Diagnostics**.
5. Paste the copied text directly into your GitHub issue under the **Support Diagnostics Snapshot** section.

> [!NOTE]
> The diagnostics snapshot is strictly privacy-bounded. It contains only module state, runtime version, environment type, CPU/RAM usage, and feature flags. It never contains private keys, credentials, keybox certificates, package names, or identity values.

### B. WebUI Live Logs & Debug Logging
1. In the WebUI, navigate to the **Logs** tab.
2. Toggle **Debug Logging** to **ON** (the toggle turns green). This activates detailed runtime tracing without needing a debug build.
3. Reproduce your issue or integrity check on your device.
4. Return to the WebUI **Logs** tab and click **Copy**.
5. Paste the logs into your bug report.
6. Toggle **Debug Logging** back **OFF** when finished to conserve system resources.

---

## Method 2: One-Click Emergency Bug Report Archive (`action.sh`)

CleveresTricky includes an automated bug report tool that packages system logs, module state, and root environment information into a compressed archive (`.tar.gz`).

### Via Root Manager:
- In **KernelSU** or **APatch**, tap the **Action** button next to the CleveresTricky module card.

### Via Terminal (Termux / Root Shell):
```bash
su -c /data/adb/modules/cleverestricky/action.sh
```

### Where to find the report:
- The generated archive is saved to:
  ```
  /data/adb/cleverestricky/bugreports/CleveresTricky_Bugreport_<timestamp>.tar.gz
  ```
  (and automatically copied to your device's `Download/` folder if accessible).
- You can attach this archive directly to your GitHub issue.

> [!TIP]
> Keybox XML/CBOX files and private credentials are intentionally excluded from the bug report archive to protect your security.

---

## Method 3: Native Daemon Runtime Log

The native background daemon continuously records its initialization, hooks, and operational status to:
```
/data/adb/cleverestricky/native_runtime.log
```

To view or extract the latest runtime logs via terminal:
```bash
su -c "tail -n 100 /data/adb/cleverestricky/native_runtime.log"
```

---

## Method 4: Android Logcat (ADB or Terminal)

If the WebUI does not open or the module cannot start, logcat provides direct insight into early startup and Binder transactions.

### Live capture via ADB:
```bash
adb logcat -s cleverestricky CleveresTricky
```

### In Termux or root shell:
```bash
su -c "logcat -d -s cleverestricky CleveresTricky"
```

### Clean startup capture (restarting Keystore):
```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

### Important startup markers:
- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

### KeyMint / TEE Hardware Failure Marker:
If you see:
```text
[WARN] Platform KeyMint HAL unreachable or TEE broken (SECURE_HW_COMMUNICATION_FAILED).
[INFO] CleveresTricky requires a functional hardware KeyMint; aborting injection to prevent framework deadlock.
[INFO] Please consult documentation (docs/security/Attestation.md or docs/LOG.md) for TEE recovery guidance.
```
This indicates that the device's hardware TEE or vendor KeyMint HAL is failing to communicate (error code -49 / SECURE_HW_COMMUNICATION_FAILED). CleveresTricky intentionally trips an internal circuit breaker and halts interception rather than deadlocking the framework. Refer to [Attestation.md](docs/security/Attestation.md#hardware-tee-provisioning--unlocked-device-recovery-note) for device-side TEE provisioning recovery options on unlocked devices.

> [!WARNING]
> Review your logs before sharing publicly. While credentials and WebUI tokens are never logged, device model names, process IDs, and package names may still be visible.

---

## Summary for Issue Reports

When reporting a bug on GitHub, please include:
1. **Support Diagnostics Snapshot** (from WebUI Info tab)
2. **Logs** (from WebUI Logs tab with Debug Logging enabled, `action.sh` archive, or logcat)
3. Device model, Android version, and root manager (KernelSU / APatch / Magisk)
