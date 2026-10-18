# Android builds and releases

Workflow: `.github/workflows/android.yml` — **Android APK · build, test and release**.

Pushes to `main` or `codex/android-first`, pull requests, and manual workflow dispatch compile Android. Version tags beginning with `v` additionally publish a prerelease after the build and emulator-test jobs pass. No desktop or web builds run.

```bash
git tag v0.1.0-android.1
git push origin v0.1.0-android.1
```

Download `UnderTheHood-Android-debug.apk` for a development install. The unsigned release variant is an additional build output, not an installable artifact. APK test packages are kept in workflow artifacts and excluded from releases. SHA256SUMS covers published APKs and dependency notices.

Actions are pinned to commit SHAs. Build tools, Go, Gradle, Kotlin, Android libraries and Rclone versions are pinned; the Go module checksum database authenticates module downloads. The Gradle wrapper verifies its distribution checksum. This is not a claim of independently reproduced bit-identical APKs or a signed SLSA provenance attestation. Build logs identify the source commit and toolchain. Go dependency license texts are collected with `scripts/notices.py`.

Optional production signing secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If a keystore is configured, the workflow also publishes `UnderTheHood-Android-release.apk` and verifies its signature. Otherwise it always provides the debug-signed APK and accurately named unsigned release APK. The temporary signing keystore is removed on exit. No private signing material is committed.

For reliable upgrades, configure and retain a production signing key before distributing a long-lived installation. GitHub runner-generated debug keys may vary by run, so upgrading development builds may require uninstalling them first. Export metadata before uninstalling. Android's unknown-source installation approval is an operating-system requirement.
