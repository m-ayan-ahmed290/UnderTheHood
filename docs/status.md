# Android feature status

This document describes implemented behavior, not an assertion that live cloud providers have been tested.

| Area | Android preview behavior |
|---|---|
| Native app | Kotlin, Compose, Material 3, system light/dark theme, Room, WorkManager, Android 10+ |
| Local profile | Argon2id (64 MiB, 3 iterations, 2 lanes); password-wrapped random Rclone config key; device Keystore storage for background operation |
| Provider engine | Official Rclone 1.75.2 compiled as Android shared library; JNI calls in process; no RC HTTP server |
| Connections | Data-driven providers/options, multiple accounts, default or custom OAuth client credentials, browser handoff, test, reconnect, disconnect |
| Provider passwords | Not collected; password-only backends including MEGA cannot be connected |
| Original cloud files | Physical browsing, individual import, manual recursive indexing; existing organization preserved on rescan |
| Virtual library | Folder creation, rename/move, search over indexed names/tags/notes, stars, notes, tags, list/grid, selection, metadata trash/restore, duplicate references |
| Deletion | Removing library references never deletes provider data; cloud-object garbage collection is not implemented |
| Uploads | File picker, persistent job records, WorkManager constraints, Rclone transfers, actual byte counters/errors, retries, pause/cancel/resume |
| Large files | 16 MiB streaming chunks, round-robin distribution across eligible remotes, SHA-256, round-trip verification before logical commit |
| Protection | Two distinct writable accounts; both copies round-trip verified; download tries other replicas on failure |
| Interrupted uploads | Verified chunk checkpoints; retry uses the same object paths and reuses matching verified chunks; checkpoints live in app cache and can be evicted |
| Download/open | Reassemble and verify chunks; save via SAF; external installed viewers; cached opening |
| Backup | SAF-selected folder; recursive scan; incremental based on timestamp/size; six-hour periodic WorkManager rule; new versions; no source-delete propagation |
| Quotas/planner | Known total/used/free, unknown quota labels, most-free/manual placement; chunk round robin; no throughput/cost predictions |
| Metadata recovery | Password-encrypted local export/import; versioned chunk manifests; explicit conflict-preserving merge |
| Optional Supabase | Email/password auth, refreshed sessions, Keystore-encrypted session storage; immutable encrypted snapshots with owner RLS |
| Cross-device sync | Manual upload and merge of snapshots; conflicting existing nodes become separate references; account credentials must be reconnected |
| CI/release | Native tests, Kotlin unit tests/lint, debug/release/test APK assembly, emulator instrumentation, checksums and tag prereleases |

## Not implemented or incomplete

Automatic delta sync, version vectors/device enrollment/revocation, QR pairing, automatic remote manifest backups, last-backup deletion protection, erasure coding, compact bundles, content encryption toggle, biometric unlock, profile password reset, full metadata database encryption, thumbnails/native multimedia previews, HTTP range streaming, photo-picker-specific backup shortcuts, content search, duplicate finder, analytics/history dashboards, provider-specific incremental change feeds, physical remote editing/deletion UI, cloud orphan garbage collection, bandwidth controls, configurable parallelism, conflict-resolution editor, backup exclusions/retention, undo, and full keyboard/drag-and-drop support are not complete.

Recursive remote scans currently return a complete Rclone listing to the app; very large account indexes can consume substantial memory. Normal browser rows use lazy rendering. Pause/resume restarts a native transfer at a safe file/chunk boundary rather than promising byte-level resumability on every provider. Download failure can leave a partial destination document; failed jobs are explicitly marked and retry truncates that document.

Unknown quota is not zero quota or unlimited quota. Reported free-space estimates can become stale, and a provider can reject writes after a successful connection test. Two-copy status records verification at upload time; it is not a continuous cloud-health guarantee.

Desktop, web, companion API and optional self-hosted server are outside this Android-first delivery and have not been scaffolded or built.
