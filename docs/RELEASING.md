# Builds, releases and troubleshooting

`Android APK` compiles bundled Rclone, runs Kotlin unit tests and Android lint, builds debug and unsigned release variants, and uploads artifacts and SHA-256 sums. Main-branch success publishes or refreshes the `android-preview` prerelease. A version tag such as `v0.1.0` publishes versioned APK assets. Install the debug APK for development. It uses a development certificate, which may change on a fresh runner and require uninstalling an earlier preview; export metadata before uninstalling.

Production signing is configurable with `UTH_KEYSTORE`, `UTH_STORE_PASSWORD`, `UTH_KEY_ALIAS`, `UTH_KEY_PASSWORD` build environment variables. The workflow currently packages the unsigned release output. A production deployment must supply a stable private signing key through repository/environment secrets and adjust the asset copy to the signed output; never commit a keystore.

`Core, web and desktop` checks the Rust core, local companion integration and web type/build validation, then builds Tauri packages on Windows, Linux and macOS. Desktop installers are CI artifacts. The `Publish desktop and web previews` workflow publishes successful main builds to `desktop-preview`, with ZIP assets and checksums. Unsigned Windows and unnotarized macOS builds may require OS trust overrides. No trusted production signing is implied.

If an OAuth provider rejects built-in credentials, configure a custom client if available or use another backend. Do not bypass provider restrictions. Connect the same remote ID on each device; Supabase metadata does not transfer cloud credentials.

If a transfer fails, inspect account authorization, network, quota and SAF grants. Retry failed work in Transfers. Temporary connectivity errors never delete cloud originals. If the local profile is lost, keep cloud data intact, export/restore metadata where supported, and reconnect provider accounts. There is no full recovery wizard yet.

When Supabase says an organization is unavailable, authenticate the plugin with an account that can access the intended organization. Application code cannot grant organization membership. The implementation session requested `xfckukypywapxwmrvwjg` (UnderTheHood), but the connection initially exposed a different organization and then returned unknown-tool errors. No unrelated project was modified.

The project-scoped Codex MCP server for `vqkbjzumonqudkeapgxv` was saved successfully. Login remained blocked by an HTTP 403 from the managed environment proxy to `mcp.supabase.com`; auth status was Unknown. The Supabase plugin also stopped exposing its tools. Reauthenticate the plugin and allow the MCP host in environment networking before deployment.
