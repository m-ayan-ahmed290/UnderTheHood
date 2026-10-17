use hood_core::{now, valid_name, valid_remote, Library, Node, Rclone};
use serde_json::{json, Value};
use std::{
    fs::{self, File},
    io::Read,
    path::PathBuf,
    process::{Command, Stdio},
    sync::{Arc, Mutex},
};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};
use uuid::Uuid;
pub const PORT: u16 = 47651;
#[derive(Clone)]
pub struct Config {
    pub data: PathBuf,
    pub rclone: PathBuf,
    pub assets: PathBuf,
    pub token: String,
    pub origin: Option<String>,
}
impl Config {
    pub fn system() -> Self {
        let data = std::env::var_os("UTH_DATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                directories::ProjectDirs::from("dev", "UnderTheHood", "UnderTheHood")
                    .expect("user directory")
                    .data_local_dir()
                    .to_path_buf()
            });
        Self {
            data,
            rclone: std::env::var_os("UTH_RCLONE")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("rclone")),
            assets: std::env::var_os("UTH_WEB_ASSETS")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("apps/web/dist")),
            token: Uuid::new_v4().to_string() + &Uuid::new_v4().to_string(),
            origin: std::env::var("UTH_WEB_ORIGIN").ok(),
        }
    }
    fn adapter(&self) -> Rclone {
        Rclone {
            executable: self.rclone.clone(),
            config: self.data.join("rclone.conf"),
        }
    }
}
pub fn authorized(host: &str, origin: Option<&str>, token: Option<&str>, config: &Config) -> bool {
    let allowed_host = host == format!("127.0.0.1:{PORT}") || host == format!("localhost:{PORT}");
    let allowed_origin = origin
        .map(|o| {
            o == format!("http://127.0.0.1:{PORT}")
                || o == format!("http://localhost:{PORT}")
                || config.origin.as_deref() == Some(o)
                || (config.origin.as_deref() == Some("http://tauri.localhost")
                    && o == "tauri://localhost")
        })
        .unwrap_or(true);
    allowed_host
        && allowed_origin
        && token
            .map(|t| t == format!("Bearer {}", config.token))
            .unwrap_or(false)
}
fn header<'a>(r: &'a Request, name: &str) -> Option<&'a str> {
    r.headers()
        .iter()
        .find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name))
        .map(|h| h.value.as_str())
}
fn json_response(r: Request, code: u16, value: Value, origin: Option<String>) {
    let mut response = Response::from_string(value.to_string()).with_status_code(StatusCode(code));
    response.add_header(Header::from_bytes("Content-Type", "application/json").unwrap());
    response.add_header(Header::from_bytes("Cache-Control", "no-store").unwrap());
    if let Some(origin) = origin {
        response.add_header(Header::from_bytes("Access-Control-Allow-Origin", origin).unwrap());
        response.add_header(Header::from_bytes("Vary", "Origin").unwrap());
    }
    let _ = r.respond(response);
}
pub fn serve(config: Config) -> std::io::Result<()> {
    fs::create_dir_all(&config.data)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&config.data, fs::Permissions::from_mode(0o700))?;
    }
    let server = Server::http(("127.0.0.1", PORT)).map_err(std::io::Error::other)?;
    let setup = Arc::new(Mutex::new(String::from("idle")));
    for mut r in server.incoming_requests() {
        let host = header(&r, "Host").unwrap_or("").to_string();
        let origin = header(&r, "Origin").map(String::from);
        let url = match url::Url::parse(&format!("http://127.0.0.1{}", r.url())) {
            Ok(u) => u,
            Err(_) => {
                json_response(r, 400, json!({"error":"Invalid URL"}), None);
                continue;
            }
        };
        let path = url.path().to_string();
        let param = |name: &str| {
            url.query_pairs()
                .find(|(k, _)| k == name)
                .map(|(_, v)| v.into_owned())
                .unwrap_or_default()
        };
        if !path.starts_with("/v1/") {
            if ![format!("127.0.0.1:{PORT}"), format!("localhost:{PORT}")].contains(&host) {
                json_response(r, 403, json!({"error":"Invalid host"}), None);
                continue;
            }
            let path = if path == "/" {
                "index.html"
            } else {
                path.trim_start_matches('/')
            };
            if hood_core::safe_relative(path).is_err() {
                json_response(r, 400, json!({"error":"Invalid path"}), None);
                continue;
            }
            match File::open(config.assets.join(path)) {
                Ok(file) => {
                    let mime = if path.ends_with(".js") {
                        "application/javascript"
                    } else if path.ends_with(".css") {
                        "text/css"
                    } else if path.ends_with(".json") {
                        "application/json"
                    } else if path.ends_with(".svg") {
                        "image/svg+xml"
                    } else {
                        "text/html"
                    };
                    let mut response = Response::from_file(file);
                    response.add_header(Header::from_bytes("Content-Type", mime).unwrap());
                    response.add_header(
                        Header::from_bytes("X-Content-Type-Options", "nosniff").unwrap(),
                    );
                    let _ = r.respond(response);
                }
                Err(_) => {
                    json_response(r, 404, json!({"error":"Build the web client first"}), None)
                }
            }
            continue;
        }
        if r.method() == &Method::Options {
            if !authorized(
                &host,
                origin.as_deref(),
                Some(&format!("Bearer {}", config.token)),
                &config,
            ) {
                json_response(r, 403, json!({"error":"Origin denied"}), None);
                continue;
            }
            let mut response = Response::empty(204);
            if let Some(ref o) = origin {
                response.add_header(
                    Header::from_bytes("Access-Control-Allow-Origin", o.as_str()).unwrap(),
                );
            }
            response.add_header(
                Header::from_bytes(
                    "Access-Control-Allow-Headers",
                    "Authorization, Content-Type",
                )
                .unwrap(),
            );
            response.add_header(
                Header::from_bytes("Access-Control-Allow-Methods", "GET, POST, OPTIONS").unwrap(),
            );
            let _ = r.respond(response);
            continue;
        }
        if !authorized(
            &host,
            origin.as_deref(),
            header(&r, "Authorization"),
            &config,
        ) {
            json_response(
                r,
                403,
                json!({"error":"Pair this browser with your local companion"}),
                None,
            );
            continue;
        }
        if path == "/v1/upload" && r.method() == &Method::Post {
            let remote = param("remote");
            let name = param("name");
            let parent = param("parent");
            if valid_remote(&remote).is_err() || valid_name(&name).is_err() {
                json_response(r, 400, json!({"error":"Invalid destination"}), origin);
                continue;
            }
            let temporary = config.data.join(format!("{}.upload", Uuid::new_v4()));
            let result = (|| -> hood_core::Result<Value> {
                let mut file = File::create(&temporary)?;
                let size =
                    std::io::copy(&mut r.as_reader().take(1024 * 1024 * 1024 + 1), &mut file)?;
                if size > 1024 * 1024 * 1024 {
                    return Err(hood_core::Error::Invalid(
                        "Web upload limit is 1 GiB".into(),
                    ));
                }
                let object = format!(".underthehood/objects/{}/{}", Uuid::new_v4(), name);
                config.adapter().run(&[
                    "copyto",
                    temporary.to_str().unwrap(),
                    &format!("{remote}:{object}"),
                ])?;
                let mut node =
                    Node::folder(&name, if parent.is_empty() { "root" } else { &parent });
                node.folder = false;
                node.remote = Some(remote);
                node.path = Some(object);
                node.size = size;
                node.mime = "application/octet-stream".into();
                Library::open(&config.data.join("library.db"))?.save(&node)?;
                Ok(json!(node))
            })();
            let _ = fs::remove_file(temporary);
            match result {
                Ok(v) => json_response(r, 200, v, origin),
                Err(e) => json_response(r, 400, json!({"error":e.to_string()}), origin),
            }
            continue;
        }
        if path == "/v1/download" && r.method() == &Method::Get {
            let result = (|| -> hood_core::Result<(File, PathBuf, String)> {
                let node = Library::open(&config.data.join("library.db"))?
                    .nodes()?
                    .into_iter()
                    .find(|n| n.id == param("id"))
                    .ok_or_else(|| hood_core::Error::Invalid("Missing file".into()))?;
                let file = config.data.join(format!("{}.download", Uuid::new_v4()));
                config.adapter().run(&[
                    "copyto",
                    &format!(
                        "{}:{}",
                        node.remote.unwrap_or_default(),
                        node.path.unwrap_or_default()
                    ),
                    file.to_str().unwrap(),
                ])?;
                Ok((File::open(&file)?, file, node.name))
            })();
            match result {
                Ok((file, path, _name)) => {
                    let mut response = Response::from_file(file);
                    response.add_header(
                        Header::from_bytes("Content-Type", "application/octet-stream").unwrap(),
                    );
                    if let Some(o) = origin {
                        response.add_header(
                            Header::from_bytes("Access-Control-Allow-Origin", o.as_str()).unwrap(),
                        );
                    }
                    let _ = r.respond(response);
                    let _ = fs::remove_file(path);
                }
                Err(e) => json_response(r, 400, json!({"error":e.to_string()}), origin),
            }
            continue;
        }
        let method = r.method().clone();
        let mut body = String::new();
        if r.body_length().unwrap_or(0) > 1024 * 1024 {
            json_response(r, 413, json!({"error":"Request too large"}), origin);
            continue;
        }
        if method == Method::Post
            && r.as_reader()
                .take(1024 * 1024 + 1)
                .read_to_string(&mut body)
                .is_err()
        {
            json_response(r, 400, json!({"error":"Invalid body"}), origin);
            continue;
        }
        let result = (|| -> hood_core::Result<Value> {
            let mut db = Library::open(&config.data.join("library.db"))?;
            let adapter = config.adapter();
            match (method.as_str(), path.as_str()) {
                ("GET", "/v1/health") => Ok(json!({"version":1,"mode":"local-companion"})),
                ("GET", "/v1/nodes") => Ok(json!(db.nodes()?)),
                ("POST", "/v1/nodes") => {
                    let mut node: Node = serde_json::from_str(&body)?;
                    node.modified = now();
                    db.save(&node)?;
                    Ok(json!(node))
                }
                ("GET", "/v1/snapshot") => db.snapshot(),
                ("POST", "/v1/restore") => {
                    db.restore(serde_json::from_str(&body)?)?;
                    Ok(json!({"restored":true}))
                }
                ("GET", "/v1/remotes") => adapter.remotes(),
                ("GET", "/v1/providers") => adapter.run(&["config", "providers"]),
                ("GET", "/v1/files") => adapter.list(&param("remote"), &param("path")),
                ("GET", "/v1/quota") => {
                    valid_remote(&param("remote"))?;
                    adapter.run(&["about", &format!("{}:", param("remote")), "--json"])
                }
                ("GET", "/v1/setup") => Ok(json!({"status":setup.lock().unwrap().clone()})),
                ("POST", "/v1/connect") => {
                    let input: Value = serde_json::from_str(&body)?;
                    let name = input["name"].as_str().unwrap_or("");
                    let provider = input["provider"].as_str().unwrap_or("");
                    valid_remote(name)?;
                    valid_remote(provider)?;
                    if provider == "mega" {
                        return Err(hood_core::Error::Invalid("MEGA requires a provider password and is unavailable in this password-free wizard".into()));
                    }
                    if adapter.remotes()?["remotes"]
                        .as_array()
                        .map(|r| r.iter().any(|v| v.as_str() == Some(name)))
                        .unwrap_or(false)
                    {
                        return Err(hood_core::Error::Invalid(
                            "Account ID already exists".into(),
                        ));
                    }

                    if *setup.lock().unwrap() == "authorizing" {
                        return Err(hood_core::Error::Invalid(
                            "Authorization already in progress".into(),
                        ));
                    }
                    let mut args = vec![
                        "config".to_string(),
                        "create".into(),
                        name.into(),
                        provider.into(),
                    ];
                    if let Some(options) = input["options"].as_object() {
                        for (key, value) in options {
                            if key.is_empty()
                                || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
                            {
                                return Err(hood_core::Error::Invalid(
                                    "Invalid provider option".into(),
                                ));
                            }
                            if let Some(v) = value.as_str() {
                                if !v.trim().is_empty() {
                                    args.push(key.clone());
                                    args.push(v.into());
                                }
                            }
                        }
                    }
                    args.push("--config".into());
                    args.push(adapter.config.to_string_lossy().into());
                    let status = setup.clone();
                    *status.lock().unwrap() = "authorizing".into();
                    std::thread::spawn(move || {
                        let result = Command::new(adapter.executable)
                            .args(args)
                            .stdin(Stdio::null())
                            .stdout(Stdio::null())
                            .stderr(Stdio::null())
                            .status();
                        *status.lock().unwrap() = if result.map(|s| s.success()).unwrap_or(false) {
                            "connected"
                        } else {
                            "failed: check required provider options or shared OAuth availability"
                        }
                        .into();
                    });
                    Ok(json!({"status":"authorizing"}))
                }
                _ => Err(hood_core::Error::Invalid(
                    "Unsupported API operation".into(),
                )),
            }
        })();
        match result {
            Ok(v) => json_response(r, 200, v, origin),
            Err(e) => json_response(r, 400, json!({"error":e.to_string()}), origin),
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn browser_boundary() {
        let mut c = Config::system();
        c.token = "secret".into();
        assert!(authorized(
            "127.0.0.1:47651",
            Some("http://127.0.0.1:47651"),
            Some("Bearer secret"),
            &c
        ));
        assert!(!authorized(
            "evil.example:47651",
            None,
            Some("Bearer secret"),
            &c
        ));
        assert!(!authorized(
            "127.0.0.1:47651",
            Some("https://evil.example"),
            Some("Bearer secret"),
            &c
        ));
        assert!(!authorized("127.0.0.1:47651", None, None, &c));
    }
}
