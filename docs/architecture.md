# Android architecture and manifest format

Compose → lifecycle ViewModel → Room / WorkManager → Kotlin Engine → byte-array JNI bridge → Go c-shared Rclone library.

The native library is packaged under `lib/arm64-v8a/libuth.so` and `lib/x86_64/libuth.so`. `System.loadLibrary` loads executable code directly from the APK. Nothing is copied into writable storage for execution. NDK 28 and a 16 KiB maximum ELF page-size linker setting support current Android alignment requirements. UTF-8 byte arrays preserve non-ASCII paths across JNI.

Room uses SQLite WAL. Nodes have stable UUIDs, logical parents, version counters, tags, notes, physical references or chunk manifests. File moves/renames normally update only the node. Transfer jobs and backup rules survive process restart through Room and WorkManager. WorkManager handles permitted background execution, charging and network constraints; a data-sync foreground notification is used for active transfers.

New uploads are stored under `UnderTheHood/objects/<job UUID>/<remote UUID>/<chunk index>`. At most a chunk plus verification copy is staged at a time when chunking is enabled. An unchunked upload needs the full file's local staging space. Every uploaded replica is downloaded and SHA-256 checked before its chunk enters the pending manifest. Only the complete manifest is inserted into the logical library. Verified checkpoints use a synced temporary file and atomic rename, so retries can reuse finished chunks. Android can evict cache checkpoints; such retries reupload deterministically. There is no cross-device concurrent writer for a job UUID.

Manifest version 1:

```json
{"version":1,"protected":true,"chunks":[{"index":0,"size":16777216,"sha256":"lowercase hex","copies":[{"remote":"stable-remote-uuid","path":"UnderTheHood/objects/job-id/0"},{"remote":"other-remote-uuid","path":"UnderTheHood/objects/job-id/0"}]}]}
```

Chunks are ordered in the array. Download validates each chunk size and hash and tries another copy on failure. The manifest references Rclone configuration names that equal the account's stable internal UUID. A duplicate library reference shares physical storage, and deleting one reference never deletes those shared objects.

Physical file import stores remote/path references rather than copying data. Indexing is explicit and recursive. Rescans preserve virtual names and parents; removed/changed files on provider sites may appear stale until accessed. No remote deletion is inferred from a transient scan failure.

Snapshot format 1 contains `version`, `nodes` and account descriptors (`id`, `name`, `provider`). It deliberately excludes credentials. The optional Supabase schema in `supabase-schema.sql` stores immutable encrypted payloads. Owner policies restrict select/insert/delete and anonymous access is revoked. Automatic delta synchronization and protocol implementations for other platforms are future work.
