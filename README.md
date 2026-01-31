# CleveresTricky

**Advanced Keystore and Attestation Spoofing Module for Android**

*Formerly TrickyStore*

**Requires Android 12+**

## Features

CleveresTricky provides comprehensive keystore spoofing with the following capabilities:

**Core Features:**
- Binder level system property spoofing (invisible to DroidGuard/GMS)
- KeyMint 4.0 support
- Remote Key Provisioning (RKP) spoofing for STRONG integrity
- Automatic Pixel Beta fingerprint fetching
- Security patch level customization
- Low memory footprint with immediate config release

**Integrity Support:**
- MEETS_BASIC_INTEGRITY
- MEETS_DEVICE_INTEGRITY
- MEETS_STRONG_INTEGRITY (via RKP spoofing)

## Quick Start

1. Flash the module and reboot
2. Place keybox.xml at `/data/adb/cleverestricky/keybox.xml` (optional, for hardware attestation)
3. Configure target packages in `/data/adb/cleverestricky/target.txt` (optional)
4. Enable RKP bypass for STRONG integrity: `touch /data/adb/cleverestricky/rkp_bypass`

Configuration changes take effect immediately.

## Configuration

### keybox.xml

```xml
<?xml version="1.0"?>
<AndroidAttestation>
    <NumberOfKeyboxes>1</NumberOfKeyboxes>
    <Keybox DeviceID="...">
        <Key algorithm="ecdsa|rsa">
            <PrivateKey format="pem">
-----BEGIN EC PRIVATE KEY-----
...
-----END EC PRIVATE KEY-----
            </PrivateKey>
            <CertificateChain>
                <NumberOfCertificates>...</NumberOfCertificates>
                <Certificate format="pem">
-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----
                </Certificate>
            </CertificateChain>
        </Key>
    </Keybox>
</AndroidAttestation>
```

### Build Vars Spoofing

> Requires Zygisk or Zygisk Next

Create/edit `/data/adb/cleverestricky/spoof_build_vars`:

```
MANUFACTURER=Google
MODEL=Pixel 8 Pro
FINGERPRINT=google/husky_beta/husky:15/AP31.240617.009/12094726:user/release-keys
BRAND=google
PRODUCT=husky_beta
DEVICE=husky
RELEASE=15
ID=AP31.240617.009
INCREMENTAL=12094726
TYPE=user
TAGS=release-keys
SECURITY_PATCH=2024-07-05
ro.boot.verifiedbootstate=green
ro.boot.flash.locked=1
```

For Magisk users without Zygisk, remove `/data/adb/modules/cleveres_tricky/zygisk`.

### Device Templates

Built-in templates available:
- `pixel8pro`, `pixel7pro`, `pixel6pro`
- `xiaomi14`, `s23ultra`, `oneplus11`

Usage in spoof_build_vars:
```
TEMPLATE=pixel8pro
MODEL=Custom Override
```

### Target Configuration

In `/data/adb/cleverestricky/target.txt`:

```
# Standard mode (leaf certificate hack)
io.github.vvb2060.keyattestation

# Generate mode for TEE broken devices (append !)
com.google.android.gms!
```

## RKP Spoofing (STRONG Integrity)

Remote Key Provisioning spoofing enables MEETS_STRONG_INTEGRITY.

**Enable:**
```bash
touch /data/adb/cleverestricky/rkp_bypass
```

**Disable:**
```bash
rm /data/adb/cleverestricky/rkp_bypass
```

**Custom keys (optional):**
Place custom remote keys at `/data/adb/cleverestricky/remote_keys.xml`.

**Verification:**
Use [Play Integrity API Checker](https://play.google.com/store/apps/details?id=gr.nickas.playintegrity) to confirm all three integrity levels pass.

## AutoPIF (Automatic Fingerprint Updates)

Fetches latest Pixel Beta/Canary fingerprints from Google servers.

**Manual execution:**
```bash
# Random device
sh /data/adb/modules/cleverestricky/autopif.sh

# Specific device
sh /data/adb/modules/cleverestricky/autopif.sh --device husky

# List devices
sh /data/adb/modules/cleverestricky/autopif.sh --list
```

**Background updates (24 hour interval, battery optimized):**
```bash
# Enable
touch /data/adb/cleverestricky/auto_beta_fetch

# Disable
rm /data/adb/cleverestricky/auto_beta_fetch
```

## Security Patch Customization

Create `/data/adb/cleverestricky/security_patch.txt`:

**Simple format:**
```
20241101
```

**Advanced format:**
```
system=202411
boot=2024-11-01
vendor=2024-11-01
```

Special values:
- `no` disables patching for that component
- `prop` keeps system prop consistent

## Roadmap

Contributions welcome.

## Acknowledgements

- [PlayIntegrityFix](https://github.com/chiteroman/PlayIntegrityFix)
- [FrameworkPatch](https://github.com/chiteroman/FrameworkPatch)
- [BootloaderSpoofer](https://github.com/chiteroman/BootloaderSpoofer)
- [KeystoreInjection](https://github.com/aviraxp/Zygisk-KeystoreInjection)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- TrickyStore

## Credits

Cleverestech Telegram Group
