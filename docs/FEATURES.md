# Feature status

Status describes code, not live-provider certification. See CI and test reports for verified results.

| Capability | Android | Desktop / web |
|---|---|---|
| Native UI | Kotlin, Compose, Material 3 | Tauri 2 desktop; React PWA |
| Local profile unlock | Argon2id verifier; Keystore secrets | Pending; relies on OS user session |
| Multiple Rclone remotes | Implemented | Implemented |
| Default/custom OAuth credentials | Blank options omitted; built-in flow | Same via companion |
| Provider discovery | Runtime backend list | API discovery; common providers in UI |
| Existing remote files | Browse folders; logical file import | Same |
| Full recursive/incremental indexing | Pending | Pending |
| Virtual folders, rename, move | Implemented | Folders/rename; move API supported |
| Favorites, trash, restore, search | Implemented | Implemented; recent view |
| Upload/download | SAF + persistent WorkManager queue | Stream through companion, synchronous |
| Preview | Download ≤100 MB then external app | Download/open locally |
| Persistent pause/cancel/retry | Implemented; resumes by retrying whole file | Pending |
| Scheduled folder backups | SAF, 12-hour unmetered scheduling | Pending |
| Metadata export | Implemented | Implemented |
| Transactional metadata restore | Pending UI | Rust core + companion API; empty library only |
| Optional Supabase accounts/sync | Implemented client; deployment blocked | Pending |
| Chunk checksums/reconstruction | Shared Rust core only, not Android UI | Tested Rust core, not upload pipeline |
| Smart placement | Shared Rust core only | Most free / priority / round robin; not wired to UI |
| Redundant storage | Planner validates distinct accounts only | No end-to-end protected file storage yet |
| Rclone crypt | Backend configurable; no dedicated wizard | Same |
| E2E metadata encryption/recovery bundle | Pending | Pending |
| Biometric unlock, QR enrollment | Pending | Pending |
| Small-file bundles, erasure coding | Pending | Pending |
| Retention, version history, GC | Cloud backup revisions retained; no GC | Pending |
| Device revocation/version vectors | Pending; sync preserves visible conflicts | Pending |
| Local companion browser protection | Not exposed | Host/origin/Bearer checks; loopback only |
| Self-hosted remote server | Supabase metadata schema only | No public file server shipped |
| OS tray, autostart, auto update | N/A | Pending |
| Production signing/notarization | Configurable signing; dev preview | Unsigned/unnotarized preview builds |

No live Google Drive, OneDrive, Dropbox, MEGA, or other provider session was available during implementation. Shared OAuth credentials can be restricted or withdrawn by providers. MEGA does not use browser OAuth and is not supported by the current password-free connection wizard. Do not enter a provider password in advanced options. Read-only and quota/health-aware automatic destination selection are not yet connected to transfer UIs.

## Important development limitations

- Virtual trash never deletes physical originals. Permanent cloud deletion and safe chunk garbage collection are intentionally not implemented.
- Metadata SQLite is app-private, not application-encrypted. Exports contain filenames and remote paths.
- Android's local passphrase gates the UI. Keystore-backed background credentials are usable by the app for scheduled work without passphrase reentry. This is not a passphrase-encrypted database.
- A failed provider setup may leave an incomplete remote. Disconnect it and reconnect with a new unique ID.
- Large transfers can restart after interruption; byte-level resume is provider-dependent and not promised. Worker execution is bounded by Android WorkManager limits.
- Desktop Rclone config currently relies on filesystem/OS user protection, not keychain encryption.
- Desktop companion handles requests serially; transfers block other requests. Web upload is limited to 1 GiB. Web download uses a browser Blob and may consume substantial memory.
- Background Android backup rules cannot yet be edited/deleted from the UI; clear app storage to reset them. Do not rely on this preview as your sole backup.
- Conflicting sync revisions are retained as named conflict entries. This is conservative preservation, not full version-vector reconciliation or automatic device enrollment.
