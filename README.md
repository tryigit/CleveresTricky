# CleveresTricky

**Advanced Keystore and Attestation Spoofing Module for Android**

**Requires Android 12+**

## Features

CleveresTricky provides comprehensive keystore spoofing with the following capabilities:

**Core Features:**
- Binder level system property spoofing (invisible to DroidGuard/GMS)
- KeyMint 4.0 support
- KeyMint 4.0 support
- **Advanced RKP Emulation** for MEETS_STRONG_INTEGRITY
- **Dynamic Identity Mutation** (Anti-Fingerprinting)
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

For Magisk users without Zygisk, remove `/data/adb/modules/cleverestricky/zygisk`.

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

**How it works (The Truth):**
This module uses a sophisticated "Local RKP Proxy" to emulate a secure hardware element. It generates valid, RFC-compliant COSE/CBOR cryptographic proofs signed by a local authority.
- **Goal:** To trick Google's backend into accepting the device as a new, unprovisioned unit or a trusted generic implementation.
- **Reality Check:** This is a "Cat & Mouse" game. While the implementation is technically robust (canonical CBOR, correct P-256 math), Google can theoretically ban the specific implementation pattern or require hardware-root verification.
- **Counter-Measure:** The module features "Dynamic Identity Mutation". The internal root secret rotates automatically every 24 hours, ensuring you don't get stuck with a banned "digital fingerprint".

**Custom keys (optional):**
Place custom remote keys at `/data/adb/cleverestricky/remote_keys.xml`.

### Smart RKP Identity (Custom Keys)

For advanced users, you can provide custom RKP keys to be used instead of auto-generated ones.
This improves resilience by allowing:
1.  **Usage of valid RKP keys** dumped from other devices.
2.  **Smart Rotation:** Supply multiple keys, and the module will randomly rotate between them to avoid pattern detection.
3.  **Hardware Info Overrides:** spoof RKP hardware properties.

Place configuration at `/data/adb/cleverestricky/remote_keys.xml`:

```xml
<RemoteKeyProvisioning>
    <Keys>
        <!-- Repeat <Key> block for multiple keys -->
        <Key>
            <PrivateKey format="pem">
-----BEGIN EC PRIVATE KEY-----
...
-----END EC PRIVATE KEY-----
            </PrivateKey>
            <!-- Optional: Override COSE_Mac0 public key (Base64) -->
            <PublicKeyCose>...</PublicKeyCose>
            <!-- Optional: Override DeviceInfo CBOR (Base64) -->
            <DeviceInfo>...</DeviceInfo>
        </Key>
    </Keys>
    <!-- Optional: Override Hardware Info -->
    <HardwareInfo>
        <RpcAuthorName>Google</RpcAuthorName>
        <VersionNumber>3</VersionNumber>
    </HardwareInfo>
</RemoteKeyProvisioning>
```

If the file is missing, the module falls back to generating fresh random keys for every request.

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

**Security Patch sync:**
```bash
# Enable sync
sh /data/adb/modules/cleverestricky/security_patch.sh --enable

# Disable sync
sh /data/adb/modules/cleverestricky/security_patch.sh --disable
```

## Roadmap

Contributions welcome.

## Acknowledgements

- [PlayIntegrityFix](https://github.com/chiteroman/PlayIntegrityFix)
- [FrameworkPatch](https://github.com/chiteroman/FrameworkPatch)
- [BootloaderSpoofer](https://github.com/chiteroman/BootloaderSpoofer)
- [KeystoreInjection](https://github.com/aviraxp/Zygisk-KeystoreInjection)
- [LSPosed](https://github.com/LSPosed/LSPosed)

## Credits

Cleverestech Telegram Group
