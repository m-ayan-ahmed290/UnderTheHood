use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Component, Path},
    process::{Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Invalid(String),
    #[error(transparent)]
    Db(#[from] rusqlite::Error),
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
}
pub type Result<T> = std::result::Result<T, Error>;
pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub fn valid_name(s: &str) -> Result<()> {
    if s.trim().is_empty()
        || s.chars().count() > 255
        || s == "."
        || s == ".."
        || s.chars().any(|c| c.is_control() || c == '/' || c == '\\')
    {
        return Err(Error::Invalid("Invalid file name".into()));
    }
    Ok(())
}
pub fn safe_relative(s: &str) -> Result<()> {
    if s.contains('\\')
        || Path::new(s)
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err(Error::Invalid("Unsafe relative path".into()));
    }
    Ok(())
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Node {
    pub id: String,
    pub parent: String,
    pub name: String,
    pub folder: bool,
    pub remote: Option<String>,
    pub path: Option<String>,
    pub size: u64,
    pub mime: String,
    pub starred: bool,
    pub trash: bool,
    pub modified: u64,
}
impl Node {
    pub fn folder(name: &str, parent: &str) -> Self {
        Self {
            id: Uuid::new_v4().to_string(),
            parent: parent.into(),
            name: name.into(),
            folder: true,
            remote: None,
            path: None,
            size: 0,
            mime: "inode/directory".into(),
            starred: false,
            trash: false,
            modified: now(),
        }
    }
}
pub struct Library {
    db: Connection,
}
impl Library {
    pub fn open(path: &Path) -> Result<Self> {
        let db = Connection::open(path)?;
        db.execute_batch("PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL);
        INSERT INTO schema_version SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM schema_version);
        CREATE TABLE IF NOT EXISTS nodes(id TEXT PRIMARY KEY,parent TEXT NOT NULL,name TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(parent,name));
        CREATE INDEX IF NOT EXISTS nodes_parent ON nodes(parent);
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,state TEXT NOT NULL,payload TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS journal(seq INTEGER PRIMARY KEY AUTOINCREMENT,node_id TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL);")?;
        let version: i64 = db.query_row("SELECT version FROM schema_version", [], |r| r.get(0))?;
        if version != 1 {
            return Err(Error::Invalid("Unsupported metadata schema".into()));
        }
        Ok(Self { db })
    }
    pub fn nodes(&self) -> Result<Vec<Node>> {
        let mut statement = self
            .db
            .prepare("SELECT payload FROM nodes ORDER BY name COLLATE NOCASE")?;
        let rows = statement.query_map([], |r| r.get::<_, String>(0))?;
        rows.map(|row| Ok(serde_json::from_str(&row?)?)).collect()
    }
    pub fn save(&mut self, node: &Node) -> Result<()> {
        valid_name(&node.name)?;
        let nodes = self.nodes()?;
        let map: std::collections::HashMap<_, _> = nodes.iter().map(|n| (&n.id, n)).collect();
        let mut parent = &node.parent;
        let mut visited = std::collections::HashSet::new();
        while parent != "root" {
            if parent == &node.id || !visited.insert(parent) {
                return Err(Error::Invalid("Folder cycle".into()));
            }
            let ancestor = map
                .get(parent)
                .ok_or_else(|| Error::Invalid("Missing parent folder".into()))?;
            if !ancestor.folder {
                return Err(Error::Invalid("Invalid parent folder".into()));
            }
            parent = &ancestor.parent;
        }
        if node.folder && (node.remote.is_some() || node.path.is_some()) {
            return Err(Error::Invalid(
                "Virtual folders cannot reference provider objects".into(),
            ));
        }
        let payload = serde_json::to_string(node)?;
        let tx = self.db.transaction()?;
        tx.execute("INSERT INTO nodes(id,parent,name,payload) VALUES(?1,?2,?3,?4) ON CONFLICT(id) DO UPDATE SET parent=excluded.parent,name=excluded.name,payload=excluded.payload",params![node.id,node.parent,node.name,payload])?;
        tx.execute(
            "INSERT INTO journal(node_id,payload,created) VALUES(?1,?2,?3)",
            params![node.id, payload, now()],
        )?;
        tx.commit()?;
        Ok(())
    }
    pub fn snapshot(&self) -> Result<serde_json::Value> {
        Ok(serde_json::json!({"version":1,"nodes":self.nodes()?}))
    }
    pub fn restore(&mut self, value: serde_json::Value) -> Result<()> {
        if value["version"] != 1 {
            return Err(Error::Invalid("Unsupported snapshot".into()));
        }
        let incoming: Vec<Node> = serde_json::from_value(value["nodes"].clone())?;
        // Validate a complete snapshot in an isolated database before touching the live one.
        let mut staging = Library::open(Path::new(":memory:"))?;
        let mut pending = incoming;
        while !pending.is_empty() {
            let known: std::collections::HashSet<_> = staging
                .nodes()?
                .into_iter()
                .filter(|n| n.folder)
                .map(|n| n.id)
                .collect();
            let before = pending.len();
            let mut rest = vec![];
            for n in pending {
                if n.parent == "root" || known.contains(&n.parent) {
                    staging.save(&n)?
                } else {
                    rest.push(n)
                }
            }
            if rest.len() == before {
                return Err(Error::Invalid(
                    "Snapshot has missing parents or cycles".into(),
                ));
            }
            pending = rest;
        }
        if !self.nodes()?.is_empty() {
            return Err(Error::Invalid(
                "Restore requires an empty library to avoid overwriting data".into(),
            ));
        }
        let tx = self.db.transaction()?;
        for n in staging.nodes()? {
            tx.execute(
                "INSERT INTO nodes VALUES(?1,?2,?3,?4)",
                params![n.id, n.parent, n.name, serde_json::to_string(&n)?],
            )?;
        }
        tx.commit()?;
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RemoteCapacity {
    pub name: String,
    pub free: Option<u64>,
    pub healthy: bool,
    pub read_only: bool,
    pub priority: i32,
}
#[derive(Clone, Copy, Debug)]
pub enum Policy {
    MostFree,
    Priority,
    RoundRobin(usize),
}
pub fn plan(
    remotes: &[RemoteCapacity],
    size: u64,
    policy: Policy,
    copies: usize,
) -> Result<Vec<String>> {
    if copies == 0 {
        return Err(Error::Invalid("At least one copy is required".into()));
    }
    let mut seen = std::collections::HashSet::new();
    let mut eligible: Vec<_> = remotes
        .iter()
        .filter(|r| r.healthy && !r.read_only && r.free.map(|f| f >= size).unwrap_or(false))
        .filter(|r| seen.insert(r.name.clone()))
        .collect();
    match policy {
        Policy::MostFree => eligible.sort_by_key(|r| std::cmp::Reverse(r.free)),
        Policy::Priority => eligible.sort_by_key(|r| r.priority),
        Policy::RoundRobin(index) => {
            if !eligible.is_empty() {
                let n = eligible.len();
                eligible.rotate_left(index % n)
            }
        }
    }
    if eligible.len() < copies {
        return Err(Error::Invalid(
            "Not enough healthy accounts with confirmed capacity".into(),
        ));
    }
    Ok(eligible
        .into_iter()
        .take(copies)
        .map(|r| r.name.clone())
        .collect())
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Chunk {
    pub object: String,
    pub size: u64,
    pub sha256: String,
}
#[derive(Debug, Serialize, Deserialize)]
pub struct Manifest {
    pub version: u32,
    pub size: u64,
    pub sha256: String,
    pub chunks: Vec<Chunk>,
}
/// Bounded memory; manifests are committed only after every object is flushed.
pub fn chunk(input: &mut impl Read, directory: &Path, chunk_size: usize) -> Result<Manifest> {
    if !(1024..=64 * 1024 * 1024).contains(&chunk_size) {
        return Err(Error::Invalid("Chunk size must be 1 KiB–64 MiB".into()));
    }
    fs::create_dir_all(directory)?;
    if directory.join("manifest.json").exists() {
        return Err(Error::Invalid("A committed manifest already exists".into()));
    }
    let mut full = Sha256::new();
    let mut chunks = vec![];
    let mut size = 0;
    let mut buffer = vec![0u8; chunk_size];
    loop {
        let mut count = 0;
        while count < buffer.len() {
            let n = input.read(&mut buffer[count..])?;
            if n == 0 {
                break;
            }
            count += n;
        }
        if count == 0 {
            break;
        }
        let bytes = &buffer[..count];
        full.update(bytes);
        size += count as u64;
        let hash = format!("{:x}", Sha256::digest(bytes));
        let object = format!("{}.chunk", Uuid::new_v4());
        let mut file = File::create(directory.join(&object))?;
        file.write_all(bytes)?;
        file.sync_all()?;
        chunks.push(Chunk {
            object,
            size: count as u64,
            sha256: hash,
        });
    }
    let manifest = Manifest {
        version: 1,
        size,
        sha256: format!("{:x}", full.finalize()),
        chunks,
    };
    let temporary = directory.join(format!("{}.manifest.pending", Uuid::new_v4()));
    let mut file = File::create(&temporary)?;
    file.write_all(&serde_json::to_vec(&manifest)?)?;
    file.sync_all()?;
    fs::hard_link(&temporary, directory.join("manifest.json"))?;
    fs::remove_file(temporary)?;
    Ok(manifest)
}
/// Verify into a temporary file and atomically rename; a corrupt file is never exposed as complete.
pub fn reconstruct(manifest: &Manifest, directory: &Path, destination: &Path) -> Result<()> {
    if manifest.version != 1 {
        return Err(Error::Invalid("Unsupported manifest".into()));
    }
    if destination.exists() {
        return Err(Error::Invalid("Destination already exists".into()));
    }
    let temporary = destination.with_extension(format!("{}.pending", Uuid::new_v4()));
    let result = (|| {
        let mut out = File::create(&temporary)?;
        let mut full = Sha256::new();
        let mut size = 0;
        let mut buffer = [0u8; 256 * 1024];
        for chunk in &manifest.chunks {
            safe_relative(&chunk.object)?;
            let object = directory.join(&chunk.object);
            if !fs::symlink_metadata(&object)?.file_type().is_file() {
                return Err(Error::Invalid("Chunk object is not a regular file".into()));
            }
            let mut file = File::open(object)?;
            let mut hash = Sha256::new();
            let mut count = 0;
            loop {
                let n = file.read(&mut buffer)?;
                if n == 0 {
                    break;
                }
                count += n as u64;
                hash.update(&buffer[..n]);
                full.update(&buffer[..n]);
                out.write_all(&buffer[..n])?;
            }
            if count != chunk.size || format!("{:x}", hash.finalize()) != chunk.sha256 {
                return Err(Error::Invalid("Chunk integrity check failed".into()));
            }
            size += count;
        }
        if size != manifest.size || format!("{:x}", full.finalize()) != manifest.sha256 {
            return Err(Error::Invalid("File integrity check failed".into()));
        }
        out.sync_all()?;
        fs::hard_link(&temporary, destination)?;
        fs::remove_file(&temporary)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}
#[derive(Clone)]
pub struct Rclone {
    pub executable: std::path::PathBuf,
    pub config: std::path::PathBuf,
}
impl Rclone {
    pub fn run(&self, args: &[&str]) -> Result<serde_json::Value> {
        let output = Command::new(&self.executable)
            .args(args)
            .arg("--config")
            .arg(&self.config)
            .stdin(Stdio::null())
            .output()?;
        if !output.status.success() {
            return Err(Error::Invalid(
                "Rclone operation failed; check account authorization, network and quota".into(),
            ));
        }
        if output.stdout.is_empty() {
            return Ok(serde_json::json!({}));
        }
        Ok(serde_json::from_slice(&output.stdout)?)
    }
    pub fn remotes(&self) -> Result<serde_json::Value> {
        self.run(&["config","dump"]).map(|v|serde_json::json!({"remotes":v.as_object().map(|o|o.keys().cloned().collect::<Vec<_>>()).unwrap_or_default()}))
    }
    pub fn list(&self, remote: &str, path: &str) -> Result<serde_json::Value> {
        valid_remote(remote)?;
        if !path.is_empty() {
            safe_relative(path)?;
        }
        self.run(&["lsjson", &format!("{remote}:{path}"), "--hash"])
    }
}
pub fn valid_remote(s: &str) -> Result<()> {
    if s.is_empty()
        || s.len() > 64
        || !s
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        return Err(Error::Invalid("Invalid remote ID".into()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn virtual_moves_and_cycle_rejection() {
        let mut db = Library::open(Path::new(":memory:")).unwrap();
        let a = Node::folder("大学", "root");
        db.save(&a).unwrap();
        let b = Node::folder("Projects", &a.id);
        db.save(&b).unwrap();
        let mut bad = a.clone();
        bad.parent = b.id.clone();
        assert!(db.save(&bad).is_err());
        assert_eq!(db.nodes().unwrap().len(), 2);
    }
    #[test]
    fn duplicate_names_are_transactional() {
        let mut db = Library::open(Path::new(":memory:")).unwrap();
        db.save(&Node::folder("Docs", "root")).unwrap();
        assert!(db.save(&Node::folder("Docs", "root")).is_err());
        assert_eq!(db.nodes().unwrap().len(), 1);
    }
    #[test]
    fn snapshot_restores_nested_metadata() {
        let mut a = Library::open(Path::new(":memory:")).unwrap();
        let x = Node::folder("Docs", "root");
        let y = Node::folder("A", &x.id);
        a.save(&x).unwrap();
        a.save(&y).unwrap();
        let mut b = Library::open(Path::new(":memory:")).unwrap();
        b.restore(a.snapshot().unwrap()).unwrap();
        assert_eq!(a.nodes().unwrap(), b.nodes().unwrap());
        assert!(b.restore(a.snapshot().unwrap()).is_err());
    }
    #[test]
    fn corrupt_snapshot_does_not_partially_restore() {
        let mut db = Library::open(Path::new(":memory:")).unwrap();
        let mut n = Node::folder("bad", "missing");
        n.id = "bad".into();
        assert!(db
            .restore(serde_json::json!({"version":1,"nodes":[n]}))
            .is_err());
        assert!(db.nodes().unwrap().is_empty());
    }
    #[test]
    fn chunk_round_trip_and_corruption() {
        let d = tempfile::tempdir().unwrap();
        let input: Vec<u8> = (0..10000).map(|i| (i % 251) as u8).collect();
        let m = chunk(&mut input.as_slice(), d.path(), 1024).unwrap();
        let out = d.path().join("out");
        reconstruct(&m, d.path(), &out).unwrap();
        assert_eq!(fs::read(out).unwrap(), input);
        fs::write(d.path().join(&m.chunks[0].object), b"corrupt").unwrap();
        let bad = d.path().join("bad");
        assert!(reconstruct(&m, d.path(), &bad).is_err());
        assert!(!bad.exists());
    }
    #[test]
    fn traversal_is_rejected() {
        for p in ["../secret", "/etc/passwd", "x/../x", "a\\b"] {
            assert!(safe_relative(p).is_err());
        }
    }
    #[test]
    fn placement_requires_real_capacity_and_distinct_redundancy() {
        let r = vec![
            RemoteCapacity {
                name: "A".into(),
                free: Some(100),
                healthy: true,
                read_only: false,
                priority: 1,
            },
            RemoteCapacity {
                name: "Unknown".into(),
                free: None,
                healthy: true,
                read_only: false,
                priority: 0,
            },
        ];
        assert_eq!(plan(&r, 20, Policy::MostFree, 1).unwrap(), vec!["A"]);
        assert!(plan(&r, 20, Policy::MostFree, 2).is_err());
        assert!(plan(&r, 101, Policy::MostFree, 1).is_err());
    }
    #[test]
    fn replica_planning_rejects_duplicate_remote_ids() {
        let remote = RemoteCapacity {
            name: "same".into(),
            free: Some(100),
            healthy: true,
            read_only: false,
            priority: 0,
        };
        assert!(plan(&[remote.clone(), remote], 1, Policy::MostFree, 2).is_err());
    }
    #[test]
    fn committed_manifest_and_existing_output_are_preserved() {
        let d = tempfile::tempdir().unwrap();
        let m = chunk(&mut b"original".as_slice(), d.path(), 1024).unwrap();
        let before = fs::read(d.path().join("manifest.json")).unwrap();
        assert!(chunk(&mut b"replacement".as_slice(), d.path(), 1024).is_err());
        assert_eq!(before, fs::read(d.path().join("manifest.json")).unwrap());
        let destination = d.path().join("existing");
        fs::write(&destination, b"keep").unwrap();
        assert!(reconstruct(&m, d.path(), &destination).is_err());
        assert_eq!(fs::read(destination).unwrap(), b"keep");
    }
    #[cfg(unix)]
    #[test]
    fn reconstruction_rejects_symlink_objects() {
        let d = tempfile::tempdir().unwrap();
        let m = chunk(&mut b"fixture".as_slice(), d.path(), 1024).unwrap();
        let object = d.path().join(&m.chunks[0].object);
        fs::remove_file(&object).unwrap();
        let external = d.path().join("external");
        fs::write(&external, b"fixture").unwrap();
        std::os::unix::fs::symlink(external, &object).unwrap();
        assert!(reconstruct(&m, d.path(), &d.path().join("out")).is_err());
    }
}
