# UnderTheHood

A local-first unified cloud library. Native Kotlin/Compose Android app, Rust/Tauri desktop app, and React PWA, with Rclone 1.75.2 handling cloud providers.

**Development preview.** See [feature status](docs/FEATURES.md) for exact implementation boundaries. Android is the first deliverable; desktop/web are companion-based previews. Provider interoperability requires live account testing and has not been certified.

## Android APK

[Download the installable Android preview APK](https://github.com/m-ayan-ahmed290/UnderTheHood/releases/download/android-preview/UnderTheHood-Android-debug.apk).

This link becomes available after the **Android APK** workflow passes. The debug APK uses development signing. ARM64 Android 10+; no root, Termux, developer cloud account, or separate Rclone installation required. The unsigned release variant is a build artifact, not directly installable.

1. Install the debug APK and create a local passphrase (12+ characters).
2. Open Accounts → Connect. Pick a Rclone backend and a unique account ID.
3. Leave OAuth client ID/secret empty to use Rclone's defaults where supported, or enter custom credentials.
4. Complete provider authorization in the official browser flow.
5. Browse existing files in Accounts and import them into My Cloud without copying them. Upload through the file picker; save through Android's destination picker.

Scheduled folder backups use user-granted Storage Access Framework folders and WorkManager. They do not mirror source deletions. Android may defer work, and the current transfer worker has the standard WorkManager execution time limit.

## Build Android

JDK 17+, Gradle 8.10.2, Go 1.26.1, Android SDK platform 35. Set `ANDROID_HOME` to your SDK.

```sh
bash scripts/build-rclone-android.sh arm64-v8a
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Output: `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Rclone is cross-compiled for Android and packaged as an extracted executable in the native library directory. It is never copied into an app-writable executable location.

## Desktop and web

```sh
npm ci
npm run build:web
python scripts/prepare-desktop.py
npm run desktop
```

The desktop application starts the loopback companion automatically. For development, the Rust workspace and Go compiler must be available. Bundled desktop releases include their own Rclone.

Standalone companion development:

```sh
# UTH_RCLONE may point to a local Rclone executable.
cargo run -p hood-companion
```

Open the private pairing link printed to the terminal. The companion binds only `127.0.0.1:47651`. Do not share its pairing token. The PWA shows cached metadata while disconnected; it cannot transfer through an offline companion. `npm run dev:web` requires `UTH_WEB_ORIGIN=http://127.0.0.1:5173` on the companion for that development origin.

## Optional Supabase

Local operation needs no backend. Android includes optional email/password accounts and append-only metadata sync. Deploy [the schema](services/optional-server/supabase/schema.sql) only to a dedicated UnderTheHood project, then configure its URL and publishable key in Settings. Never use a service-role key. Tokens remain device-local; metadata sync is **not end-to-end encrypted**. Devices connect their provider accounts separately using the same remote IDs.

Project `vqkbjzumonqudkeapgxv` in the UnderTheHood organization is the intended backend. Deployment is pending connector authentication/network access. Desktop/web Supabase account sync is not yet implemented. Independent offline profiles do not automatically share an identity.

## Verification

```sh
cargo test --locked --workspace --exclude underthehood-desktop
cargo fmt --all -- --check
npm run build:web
python scripts/integration-test.py
```

Workflows: **Android APK** and **Core, web and desktop**. Successful main-branch Android builds publish the `android-preview` prerelease; version tags publish versioned Android assets. Desktop builds produce platform artifacts. See [architecture and security](docs/ARCHITECTURE.md) and [release notes](docs/RELEASING.md).

MIT licensed application. Rclone and dependencies retain their own licenses.
