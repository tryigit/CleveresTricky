---
name: Integrity Compatibility Help
about: Diagnose an integrity or attestation compatibility issue
title: '[INTEGRITY] '
labels: 'help wanted'
assignees: ''
---

## Environment

- **Device:** (e.g., Pixel 8 Pro)
- **Android Version:** (e.g., Android 15)
- **Root Method:** (KernelSU / APatch)
- **Module Version:**

## Current Configuration

Describe your current setup:
- Certificate Safe Mode: Enabled / Disabled
- Keybox: Yes / No / .cbox
- Template: (e.g., pixel8pro, default)
- Global Mode: Yes / No
- Bootloader: Locked / Unlocked
- Device certification in Play Store: Certified / Not certified / Unknown

## Integrity Check Results

From [Play Integrity API Checker](https://play.google.com/store/apps/details?id=gr.nickas.playintegrity):

- MEETS_BASIC_INTEGRITY: ✅ / ❌
- MEETS_DEVICE_INTEGRITY: ✅ / ❌
- MEETS_STRONG_INTEGRITY: ✅ / ❌

`MEETS_STRONG_INTEGRITY` is issued by Google from hardware-backed and server-side evidence. CleveresTricky cannot guarantee this verdict or convert an unlocked/uncertified device into genuine locked-bootloader evidence.

## What You've Tried

List the steps you've already attempted.

## Logs

<details>
<summary>Click to expand logs</summary>

```
Paste logcat output here (see LOG.md for filters)
```

</details>
