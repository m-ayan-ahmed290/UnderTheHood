use hood_core::Rclone;
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    io::{BufRead, BufReader, Read},
    process::{Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex,
    },
    time::{Duration, Instant},
};

struct State {
    status: String,
    message: String,
    url: Option<String>,
    question: Option<Value>,
    answer: Option<mpsc::Sender<String>>,
}
#[derive(Clone)]
pub struct Setup {
    state: Arc<Mutex<State>>,
    cancel: Arc<AtomicBool>,
}
impl Setup {
    pub fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(State {
                status: "idle".into(),
                message: String::new(),
                url: None,
                question: None,
                answer: None,
            })),
            cancel: Arc::new(AtomicBool::new(false)),
        }
    }
    pub fn snapshot(&self) -> Value {
        let s = self.state.lock().unwrap();
        json!({"status":s.status,"message":s.message,"auth_url":s.url,"question":s.question})
    }
    pub fn active(&self) -> bool {
        matches!(
            self.state.lock().unwrap().status.as_str(),
            "authorizing" | "question" | "checking" | "cancelling"
        )
    }
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }
    pub fn answer(&self, value: String) -> Result<(), String> {
        if value.len() > 65536 {
            return Err("Answer is too long".into());
        }
        self.state
            .lock()
            .unwrap()
            .answer
            .take()
            .ok_or("No provider question is pending")?
            .send(value)
            .map_err(|_| "Setup has ended".into())
    }
    pub fn open(&self) -> Result<(), String> {
        let url = self
            .state
            .lock()
            .unwrap()
            .url
            .clone()
            .ok_or("No authorization page is available")?;
        open_browser(&url)
    }
    fn progress(&self, status: &str, message: &str) {
        let mut s = self.state.lock().unwrap();
        s.status = status.into();
        s.message = message.into();
        s.question = None;
        s.answer = None;
    }
    pub fn start(
        &self,
        adapter: Rclone,
        name: String,
        provider: String,
        mut options: BTreeMap<String, String>,
        resume: bool,
    ) {
        self.cancel.store(false, Ordering::SeqCst);
        {
            let mut s = self.state.lock().unwrap();
            s.status = "authorizing".into();
            s.message = "Starting provider authorization…".into();
            s.url = None;
            s.question = None;
            s.answer = None;
        }
        options.insert("config_is_local".into(), "true".into());
        options.insert("config_auth_no_browser".into(), "true".into());
        if options
            .get("client_id")
            .map(|v| v.trim().is_empty())
            .unwrap_or(true)
        {
            options.insert("config_shared_client_id".into(), "true".into());
        }
        let this = self.clone();
        std::thread::spawn(move || {
            let result = this.configure(&adapter, &name, &provider, &options, resume);
            match result {
                Ok(()) => this.progress("connected", "Account connected and checked"),
                Err(_e) if this.cancel.load(Ordering::SeqCst) => this.progress(
                    "cancelled",
                    "Connection cancelled. You can retry this account name.",
                ),
                Err(e) => this.progress("failed", &e),
            }
        });
    }
    fn configure(
        &self,
        adapter: &Rclone,
        name: &str,
        provider: &str,
        options: &BTreeMap<String, String>,
        resume: bool,
    ) -> Result<(), String> {
        let mut args = vec![
            "config".into(),
            if resume {
                "update".into()
            } else {
                "create".into()
            },
            name.into(),
        ];
        if !resume {
            args.push(provider.into());
        }
        let defaults: Vec<String> = options
            .iter()
            .filter(|(_, v)| !v.trim().is_empty())
            .flat_map(|(k, v)| [k.clone(), v.clone()])
            .collect();
        args.extend(defaults.clone());
        args.push("--non-interactive".into());
        for _ in 0..40 {
            let result = self.command(adapter, &args)?;
            let state = result["State"]
                .as_str()
                .ok_or("Rclone returned an invalid setup response")?;
            if state.is_empty() {
                self.progress("checking", "Checking account authorization…");
                self.command(
                    adapter,
                    &["lsjson".into(), format!("{name}:"), "--stat".into()],
                )?;
                return Ok(());
            }
            let question = result["Option"].clone();
            if !question.is_object() {
                return Err("Provider setup needs an unsupported response".into());
            }
            if question["IsPassword"] == true {
                return Err("This provider requires a password-based configuration that this wizard does not support".into());
            }
            let (tx, rx) = mpsc::channel();
            {
                let mut s = self.state.lock().unwrap();
                s.status = "question".into();
                s.message = "Choose the next provider option".into();
                s.question = Some(question);
                s.answer = Some(tx);
            }
            let deadline = Instant::now() + Duration::from_secs(300);
            let answer = loop {
                if self.cancel.load(Ordering::SeqCst) {
                    return Err("Cancelled".into());
                }
                if Instant::now() > deadline {
                    return Err("Provider setup timed out. Try connecting again.".into());
                }
                match rx.recv_timeout(Duration::from_millis(100)) {
                    Ok(a) => break a,
                    Err(mpsc::RecvTimeoutError::Timeout) => {}
                    Err(_) => return Err("Connection closed".into()),
                }
            };
            self.progress("authorizing", "Continuing provider setup…");
            args = vec!["config".into(), "update".into(), name.into()];
            args.extend(defaults.clone());
            args.extend([
                "--non-interactive".into(),
                "--continue".into(),
                "--state".into(),
                state.into(),
                "--result".into(),
                answer,
            ]);
        }
        Err("Provider setup exceeded its step limit".into())
    }
    fn command(&self, adapter: &Rclone, args: &[String]) -> Result<Value, String> {
        let mut child = Command::new(&adapter.executable)
            .args(args)
            .arg("--config")
            .arg(&adapter.config)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|_| "Could not start the bundled Rclone engine")?;
        let stdout = child.stdout.take().unwrap();
        let output = std::thread::spawn(move || {
            let mut bytes = Vec::new();
            stdout
                .take(2 * 1024 * 1024)
                .read_to_end(&mut bytes)
                .map(|_| bytes)
        });
        let stderr = child.stderr.take().unwrap();
        let this = self.clone();
        let errors = std::thread::spawn(move || {
            for line in BufReader::new(stderr.take(2 * 1024 * 1024))
                .lines()
                .map_while(Result::ok)
            {
                if let Some(url) = authorization_url(&line) {
                    {
                        let mut s = this.state.lock().unwrap();
                        s.url = Some(url.clone());
                        s.message = "Complete sign-in in your browser, then return here.".into();
                    }
                    if std::env::var_os("UTH_NO_BROWSER").is_none() {
                        let _ = open_browser(&url);
                    }
                }
            }
        });
        let deadline = Instant::now() + Duration::from_secs(300);
        let exit = loop {
            if self.cancel.load(Ordering::SeqCst) || Instant::now() > deadline {
                let _ = child.kill();
                let _ = child.wait();
                let _ = output.join();
                let _ = errors.join();
                return Err("Authorization timed out or was cancelled. Try again.".into());
            }
            match child.try_wait() {
                Ok(Some(s)) => break s,
                Ok(None) => std::thread::sleep(Duration::from_millis(100)),
                Err(_) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Err("Could not monitor authorization".into());
                }
            }
        };
        let bytes = output
            .join()
            .map_err(|_| "Could not read provider response")?
            .map_err(|_| "Could not read provider response")?;
        let _ = errors.join();
        if !exit.success() {
            return Err("Provider authorization failed. Check required options, custom OAuth credentials, or whether shared credentials are still accepted.".into());
        }
        serde_json::from_slice(&bytes)
            .map_err(|_| "Provider returned an invalid setup response".into())
    }
}
pub fn authorization_url(line: &str) -> Option<String> {
    for word in line.split_whitespace() {
        let candidate = word.trim_matches(['\'', '"']);
        if let Ok(url) = url::Url::parse(candidate) {
            if url.scheme() == "http"
                && matches!(url.host_str(), Some("127.0.0.1" | "localhost"))
                && url.port().is_some()
                && url.path() == "/auth"
                && url.query_pairs().any(|(k, _)| k == "state")
            {
                return Some(url.to_string());
            }
        }
    }
    None
}
fn open_browser(url: &str) -> Result<(), String> {
    if authorization_url(url).as_deref() != Some(url) {
        return Err("Invalid authorization address".into());
    }
    #[cfg(target_os = "windows")]
    let result = Command::new("rundll32.exe")
        .arg("url.dll,FileProtocolHandler")
        .arg(url)
        .spawn();
    #[cfg(target_os = "macos")]
    let result = Command::new("open").arg(url).spawn();
    #[cfg(all(not(target_os = "windows"), not(target_os = "macos")))]
    let result = Command::new("xdg-open").arg(url).spawn();
    result
        .map(|_| ())
        .map_err(|_| "Could not open your browser. Open the authorization link manually.".into())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_official_loopback_auth_links_are_opened() {
        assert!(authorization_url("NOTICE: http://127.0.0.1:53682/auth?state=fixture").is_some());
        for s in [
            "https://evil.example/auth?state=a",
            "http://127.0.0.1.evil.example:99/auth?state=a",
            "http://127.0.0.1:99/delete?state=a",
        ] {
            assert!(authorization_url(s).is_none())
        }
    }
    #[cfg(unix)]
    #[test]
    fn provider_questions_wait_for_an_answer_and_check_completion() {
        use std::os::unix::fs::PermissionsExt;
        let tmp = tempfile::tempdir().unwrap();
        let script = tmp.path().join("fixture");
        std::fs::write(&script,r#"#!/bin/sh
case "$1 $2" in
 'config create') echo '{"State":"choose-drive","Option":{"Name":"drive","Help":"Choose drive","DefaultStr":"one"}}';;
 'config update') case "$*" in *'--result two'*) echo '{"State":""}';; *) exit 1;; esac;;
 'lsjson fixture:') echo '{}';;
 *) exit 1;;
esac
"#).unwrap();
        std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o700)).unwrap();
        let setup = Setup::new();
        setup.start(
            Rclone {
                executable: script,
                config: tmp.path().join("config"),
            },
            "fixture".into(),
            "local".into(),
            BTreeMap::new(),
            false,
        );
        for _ in 0..100 {
            if setup.snapshot()["status"] == "question" {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(setup.snapshot()["status"], "question");
        setup.answer("two".into()).unwrap();
        for _ in 0..100 {
            if setup.snapshot()["status"] == "connected" {
                break;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(setup.snapshot()["status"], "connected");
    }
}
