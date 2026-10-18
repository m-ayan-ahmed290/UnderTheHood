# UnderTheHood — Android first

A native Kotlin / Jetpack Compose cloud library, built from scratch. Android 10+; ARM64 phones and x86_64 emulators. No WebView, root, Termux, separate Rclone install, or mandatory cloud backend.

This is an **Android preview**, not completion of every feature in the original multi-platform brief. Desktop and web applications are intentionally not built. See [feature coverage](docs/status.md) and [security and recovery](docs/security.md) before storing important files.

## Install

GitHub Actions workflow **Android APK · build, test and release** builds the installable `UnderTheHood-Android-debug.apk`, an unsigned release variant, instrumented tests, license notices and SHA-256 checksums. Download an APK from a successful workflow's `UnderTheHood-Android` artifact or from a tagged GitHub prerelease. The release job waits for unit/native checks and Android emulator tests.

`debug.apk` uses development signing. The unsigned release APK cannot be installed until signed. A newly generated development signing key can require uninstalling an earlier development build; **export metadata before uninstalling**. Production signing uses optional GitHub secrets described in [the release guide](docs/releases.md).

## Use

1. Create a local profile with a passphrase of at least 12 characters.
2. Open **Accounts → Connect an account**. Choose a provider. Leave custom OAuth client ID/secret empty to use Rclone's defaults, or supply your own overrides.
3. Complete the provider configuration questions and official browser authorization. A backend's shared application may be restricted or revoked; the actual error is shown. UnderTheHood cannot bypass provider rules.
4. Test the account. Choose **Browse** to inspect original provider folders and import individual files, or **Index existing** for a recursive metadata scan. Neither duplicates remote data.
5. Organize references under **My Cloud**. Virtual moves/renames do not move original cloud objects. Use the file picker to upload and **Save to device** to download. **Open** reconstructs and caches a file, then opens a compatible installed app.
6. In **Settings**, choose most-free/manual placement, 16 MiB chunks, two-account protection, unmetered-network/charging constraints, or a user-granted folder backup.
7. Export encrypted metadata archives regularly. They contain the virtual tree and chunk manifests, not provider login tokens.

Local operation works without Supabase. Optional app account sign-up/sign-in and encrypted snapshot upload/merge use the existing UnderTheHood Supabase project. Email confirmation depends on its mail configuration; no new Supabase project or developer registration is needed. Snapshot synchronization is manual; it is not automatic multi-platform delta synchronization. Reconnect provider accounts on each device.

## Build

Requirements: Linux x86_64, JDK 17, Go **1.27.2**, Android SDK 35, build tools **35.0.0**, NDK **28.2.13676358**. Gradle **8.13** is pinned with a distribution checksum; Rclone **1.75.2** and its module graph are pinned in `native/rclone/go.mod` / `go.sum`.

```bash
export ANDROID_HOME=/path/to/android-sdk
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358'
./scripts/build-native.sh
./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug :android:assembleRelease :android:assembleDebugAndroidTest
(cd native/rclone && go test -mod=readonly -v ./...)
```

Use Android command-line tools build **13114758** for the `sdkmanager` syntax above. The latest Android CLI has changed; CI pins the compatible tools. `UTH_ABIS=arm64-v8a ./scripts/build-native.sh` builds only ARM64. Do not omit the native build before packaging: JNI libraries are deliberately excluded from source control.

Outputs: `apps/android/build/outputs/apk/debug/android-debug.apk` and `apps/android/build/outputs/apk/release/android-release-unsigned.apk`.

Run instrumented tests with an API 35 x86_64 emulator connected:

```bash
./gradlew :android:connectedDebugAndroidTest
```

## Structure

- `apps/android`: Compose UI, Room library, WorkManager transfers/backups, Keystore vault and optional HTTPS account/snapshot client.
- `native/rclone`: pinned official Rclone library, C/JNI bridge, Argon2id derivation and credential-free integration tests.
- `scripts`: Android-native build, license collection, device test runner.
- `.github/workflows/android.yml`: Android-only build, checks, device tests and gated prereleases.
- `docs`: architecture, feature status, security, recovery and release instructions.

No existing repository application files were reused. The original repository history remains available through the parent commit.

## Provider compatibility

The provider catalog comes from the compiled Rclone registry. Availability in the catalog is not proof of working credentials or Android compatibility. Google Drive, OneDrive and Dropbox use Rclone's official configuration/OAuth paths. Default-vs-custom OAuth configuration is covered by local tests; live account sessions are not part of credential-free CI. Quotas are optional and unknown values remain unknown.

MEGA's Rclone backend requires a cloud account password. The wizard deliberately blocks provider password collection to honor the brief; MEGA login is not available. Key-based backends and Rclone crypt can be configured through the generic options; each provider's capabilities still apply. No cloud provider login is claimed certified without a real session.

License: MIT for UnderTheHood. Rclone is MIT; other bundled dependencies retain their own licenses. Release notices are generated from the native dependency graph.
