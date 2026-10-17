#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
use tauri::Manager;
#[tauri::command]
fn pairing_token(state: tauri::State<'_, String>) -> String {
    state.inner().clone()
}
fn main() {
    tauri::Builder::default()
        .setup(|app| {
            let mut config = hood_companion::Config::system();
            let resource = app.path().resource_dir()?;
            let suffix = if cfg!(windows) { ".exe" } else { "" };
            let rclone = resource.join(format!("rclone{suffix}"));
            if rclone.is_file() {
                config.rclone = rclone;
            }
            config.assets = resource.join("web");
            app.manage(config.token.clone());
            config.origin = Some("http://tauri.localhost".into());
            std::thread::spawn(move || {
                let _ = hood_companion::serve(config);
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![pairing_token])
        .run(tauri::generate_context!())
        .expect("Could not launch UnderTheHood");
}
