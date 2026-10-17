# Verification record

- Native Android Kotlin/Compose debug and release variants built successfully with bundled Android ARM64 Rclone 1.75.2 in GitHub Actions.
- Android lint passed; five JVM tests passed, including Unicode, traversal rejection, nested snapshot round trips, missing parents and cycles.
- Android 10 x86_64 emulator instrumentation passed: provider discovery, native process startup, encrypted Rclone configuration creation, and a real local file copy with content comparison.
- Rust core and companion tests passed. Additional local tests cover duplicate replica targets, preserving committed manifests/output files, and rejecting symlink chunk objects.
- Real Rclone local-backend integration passed: existing-file listing, streamed upload/download, SHA-256 content round trip, duplicate/cycle/name constraints, snapshots, missing auth, hostile Origin/Host rejection.
- Browser test passed in Chromium: private companion pairing, virtual folder creation, real remote file import, favorites, responsive desktop/mobile layouts, zero page errors.
- Web TypeScript/production build passed. Full npm audit returned zero known vulnerabilities for the locked dependency tree at verification time.
- Rust Clippy completed with warnings denied for core/companion.
- GitHub Tauri packages built successfully for Windows, Linux and macOS. Installer execution was not tested on those host operating systems.
- APK signature and published checksum verified separately after downloading the GitHub release artifact.

No live Google Drive, OneDrive, Dropbox, MEGA or other cloud-provider session was used. No Supabase schema was deployed: plugin tools returned unknown-tool errors and direct MCP authentication was blocked by the managed network policy. This is a development preview, not a claim that every requirement in the original brief is implemented. See FEATURES.md for the explicit remaining work.
