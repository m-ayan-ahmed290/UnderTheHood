# Security and recovery

The app creates a random configuration secret, wraps it with an Argon2id-derived AES-256-GCM key, and asks Rclone to encrypt its configuration. Android Keystore also protects the device's background copy of the configuration secret and Supabase session data. The app never embeds a Supabase service-role key. The publishable key is intentionally public; database row policies enforce ownership.

Local unlock protects the interactive UI. Background backups remain able to access the device-wrapped configuration key while the UI is locked. Rooted-device resistance, biometric-bound key authorization, and encryption of the Room database are not claimed. File metadata in the local SQLite database is protected by Android's app sandbox and device security, not application database encryption. Cache previews are ordinary plaintext files inside the app sandbox. **Do not treat the UI lock as a cryptographic lock of all running operations.**

Rclone's official OAuth flow owns state verification and its loopback browser callback. The JNI bridge invokes privileged APIs in process and never starts an RC network server. Authorization URLs are consumed through a narrow in-memory queue. Native Rclone logs are set to emergency level; errors returned to Kotlin exclude the request parameter dictionary. User-visible backend error messages still require care when sharing diagnostics. No token/config dumps appear in the normal UI.

The wizard permits custom client IDs/secrets, otherwise leaves them unset so Rclone uses its provider defaults. Password-only cloud-provider questions are blocked. OAuth credentials are not invented and provider restrictions are not bypassed. An expired/withdrawn shared application can require a custom registration; unaffected accounts remain usable.

Metadata archives use AES-GCM with a fresh salt/nonce and the same Argon2id settings. Supabase snapshots use a salt derived from the authenticated user ID and an explicitly supplied sync passphrase, with a new random GCM nonce per snapshot. The server stores opaque ciphertext, device IDs, timestamps and owner IDs. No file bytes or provider login tokens are synchronized. Server-side account deletion/administration is not exposed in this preview. Cloud sign-up/session refresh require reachable Supabase infrastructure.

Keep the archive passphrase independently of the device. Losing a device without a metadata archive or snapshot can lose the logical tree and the chunk-reassembly manifests. Imported original provider files remain ordinary cloud files; managed chunks require their manifests. Losing the sync/archive passphrase makes its encrypted snapshots unrecoverable. There is no undocumented backdoor or password-reset decryption mechanism.

Managed content is **not client-side encrypted by default**. Rclone crypt can be configured as a remote using the generic provider options, but a complete guided encryption/recovery workflow is not implemented. Provider TLS and app credential encryption are separate from file-content encryption.

## Recover metadata

1. On the original device, enter an archive passphrase in Settings and export an encrypted metadata archive through the Android file picker. Keep a copy outside this app.
2. On a fresh install, create a local profile. Enter the archive passphrase, then restore the archive. Alternatively sign in to the same optional Supabase app account, find a snapshot and merge it using the same sync passphrase.
3. The account placeholders retain stable remote IDs. Use their **Reconnect** action to authorize each provider on the new device; testing marks them available.
4. Download a sample of managed files and confirm reconstruction before retiring the old device.

Merge does not overwrite an existing different node. It preserves a separate conflict reference, including a trashed incoming version. Because this is snapshot merge rather than a distributed operation log, a merge can require manual organization afterwards.

No physical garbage collection runs automatically. Cancelled uploads can leave private managed objects, deliberately favoring recovery over destructive cleanup. Uninstalling clears local queues, caches, secrets and metadata; export first. The Android application disables OS app backup to avoid pretending a Keystore-encrypted backup would be decryptable on another device.
