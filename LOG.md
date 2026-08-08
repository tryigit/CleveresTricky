# Logging and diagnostics

CleveresTricky writes diagnostics to Android logcat; it does not store a separate plaintext log file.

```bash
adb logcat -s cleverestricky CleveresTricky
```

Useful startup markers are:

- `Welcome to Service!`
- `Web server on port ...`
- `libbinder ioctl hook installed successfully`
- `Keystore Binder interceptor registered`
- `TEE SecurityLevel interceptor registered`

Errors such as `TAMPER DETECTED`, `Binder ABI validation failed`, a rejected keybox, or an injector timeout are actionable. Release builds retain informational, warning, and error logs; debug builds additionally emit verbose native diagnostics.

For a clean capture:

```bash
adb logcat -c
adb shell su -c 'setprop ctl.restart keystore2'
adb logcat -d -s cleverestricky CleveresTricky
```

Do not publish logs without reviewing them. Although credentials and WebUI tokens are not intentionally logged, filenames, package names, device properties, and process identifiers may still be sensitive.
