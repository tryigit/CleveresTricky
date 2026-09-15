---
name: Bug Report
about: Report a bug or unexpected behavior
title: '[BUG] '
labels: 'bug'
assignees: ''
---

## Environment (Required)

> **Mandatory.** Provide complete device and ROM information below, or paste the **Support Diagnostics Snapshot** in the section below (which captures these automatically). Issues missing device/ROM details cannot be diagnosed.

- **Manufacturer & Brand:** (e.g., Google, Xiaomi, OnePlus, Samsung)
- **Device & Model:** (e.g., Pixel 8 Pro, cheetah, OP515BL1)
- **ROM Name & Build ID:** (e.g., LineageOS 21, OxygenOS 15.0.0.200, HyperOS 1.0.4.0, AP1A.240505.004)
- **Android Version & SDK:** (e.g., Android 14 / SDK 34, Android 15 / SDK 35)
- **Security Patch Level:** (e.g., 2024-05-05)
- **Root Method & Version:** (KernelSU / APatch / Magisk + version)
- **Module Version:** (check WebUI or `module.prop`, e.g., v2.8.0)

## Support Diagnostics Snapshot (Required)

> **Mandatory.** In WebUI, go to **Info & Resources** -> scroll down to **Support Diagnostics** -> click **Copy Diagnostics**, then paste below.
> *(If running headless Magisk without WebUI, run `su -c /data/adb/modules/cleverestricky/action.sh` or fill in the Environment fields above).*

<details open>
<summary>Click to expand Support Diagnostics Snapshot</summary>

```
Paste your diagnostics snapshot here (e.g. CleveresTricky diagnostics schema=2 ...)
```

</details>

## Description

A clear description of the bug.

## Steps to Reproduce

1. ...
2. ...
3. ...

## Expected Behavior

What should have happened?

## Actual Behavior

What actually happened?

## Logs & Diagnostics

> **Required.** Without logs, issues may be closed as non-actionable.
> See [LOG.md](https://github.com/tryigit/CleveresTricky/blob/master/LOG.md) for full diagnostic and capture guides.
>
> **How to obtain logs:**
> 1. **WebUI Logs (Easiest)**: Go to WebUI **Logs** tab -> enable **Debug Logging** toggle (turns green) -> reproduce the bug -> click **Copy** -> paste below.
> 2. **Automated Bug Report**: In KernelSU/APatch tap **Action**, or run `su -c /data/adb/modules/cleverestricky/action.sh` -> attach the archive from `/data/adb/cleverestricky/bugreports/`.
> 3. **Logcat**: Run `adb logcat -d -s cleverestricky CleveresTricky` (or in Termux: `su -c "logcat -d -s cleverestricky CleveresTricky"`).

<details>
<summary>Click to expand logs</summary>

```
Paste your log output here
```

</details>

## Integrity Check Result

If applicable, include the output from [Play Integrity API Checker](https://play.google.com/store/apps/details?id=gr.nickas.playintegrity):

- MEETS_BASIC_INTEGRITY: ✅ / ❌
- MEETS_DEVICE_INTEGRITY: ✅ / ❌
- MEETS_STRONG_INTEGRITY: ✅ / ❌

## Additional Context

Any other information (screenshots, configuration files, etc.)
