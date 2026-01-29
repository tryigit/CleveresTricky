# CleveresTricky (Beta)

**The AI-Powered, Unrestricted, God-Tier Keystore & Attestation Spoofing Module**

*Formerly TrickyStore*

**Android 12 or above is required**.

## Why CleveresTricky?

Compared to the standard TrickyStore, **CleveresTricky** brings:
- **AI-Powered Continuous Updates:** Leveraging advanced AI to stay ahead of Google's detections.
- **Unrivaled Security & Stealth:** Implements **Binder-level System Property Spoofing** to hide sensitive props (like `ro.boot.verifiedbootstate`) from deep inspection methods (DroidGuard/GMS) without relying on fragile hooking frameworks for every app.
- **Peak Performance:** Optimized C++ injection and lightweight Java service.
- **God-Mode Features:**
    - **Safe Binder Spoofing:** Bypasses ABI issues to safely spoof system properties at the IPC level.
    - **KeyMint 4.0 Support:** Ready for the future.
    - **Module Hash Spoofing:** (Experimental) To match official firmware fingerprints.

## Usage

1. Flash this module and reboot.  
2. For more than DEVICE integrity, put an unrevoked hardware keybox.xml at `/data/adb/cleveres_tricky/keybox.xml` (Optional).
3. Customize target packages at `/data/adb/cleveres_tricky/target.txt` (Optional).
4. Enjoy!  

**All configuration files will take effect immediately.**

## Low RAM Usage

Tricky Store is optimized for low RAM devices. It automatically releases memory used by configuration files (like `keybox.xml`) immediately after parsing.

## keybox.xml

format:

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
                ... more certificates
            </CertificateChain>
        </Key>...
    </Keybox>
</AndroidAttestation>
```

## Build Vars Spoofing (Advanced Privacy)

> **Zygisk (or Zygisk Next) is needed for this feature to work.**

CleveresTricky allows you to spoof ANY system property via Binder interception, making it invisible to standard `getprop` checks from targeted apps.

Create/edit `/data/adb/cleveres_tricky/spoof_build_vars`.

Example:

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
# Advanced hidden props
ro.boot.verifiedbootstate=green
ro.boot.flash.locked=1
```

For Magisk users: if you don't need this feature and zygisk is disabled, please remove or rename the
folder `/data/adb/modules/cleveres_tricky/zygisk` manually.

## Support TEE broken devices

CleveresTricky will hack the leaf certificate by default. On TEE broken devices, this will not work because we can't retrieve the leaf certificate from TEE. You can add a `!` after a package name to enable generate certificate support for this package.

For example:

```
# target.txt
# use leaf certificate hacking mode for KeyAttestation App
io.github.vvb2060.keyattestation
# use certificate generating mode for gms
com.google.android.gms!
```

## TODO

- Support App Attest Key.
- Support Android 11 and below.
- Support automatic selection mode.

PR is welcomed.

## Acknowledgement

- [PlayIntegrityFix](https://github.com/chiteroman/PlayIntegrityFix)
- [FrameworkPatch](https://github.com/chiteroman/FrameworkPatch)
- [BootloaderSpoofer](https://github.com/chiteroman/BootloaderSpoofer)
- [KeystoreInjection](https://github.com/aviraxp/Zygisk-KeystoreInjection)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- TrickyStore
  
## Credits

**Cleverestech Telegram Group** - AI-Powered Development.
