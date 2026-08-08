# Contributing

Changes should preserve the module's fail-closed security model and its supported Android 12–16 / KernelSU–APatch scope. Claims in code, tests, and documentation must describe behavior that can actually be verified; userspace code must not claim to manufacture hardware-backed integrity.

## Development checks

Run the checks relevant to your change before opening a pull request:

```bash
./gradlew ktlintCheck
./gradlew :service:lintDebug :stub:lintDebug :encryptor-app:lintDebug
./gradlew testDebugUnitTest

cd rust
cargo fmt -- --check
cargo clippy -- -D warnings
cargo test
```

For a complete module build, install Android SDK 36, NDK `27.3.13750724`, Rust, `cargo-ndk`, and the `aarch64-linux-android` and `x86_64-linux-android` targets, then run `./gradlew zipDebug`.

## Expectations

- Kotlin and Java follow Android conventions; Rust must pass `rustfmt` and Clippy; native code builds with `-Wall -Wextra -Werror`.
- Treat Binder parcels, XML, ZIP/CBOX input, HTTP responses, file paths, and process IDs as untrusted.
- Keep input and memory bounds explicit and fail closed on unknown Binder layouts or incomplete validation.
- Add focused regression tests for changed behavior, including malformed input and failure paths.
- Never commit private keys, keyboxes, access tokens, device secrets, generated APKs, or module ZIPs.
- Do not broaden SELinux rules without documenting why each permission is required.
- Update README and CHANGELOG when user-visible behavior changes.

Open a feature branch, use a clear commit message, and describe local or device verification in the pull request. Native injection changes should be tested on a supported KernelSU or APatch device in addition to CI whenever possible.
