fn main() {
    let config = hood_companion::Config::system();
    println!(
        "Open http://127.0.0.1:{}/#{} to pair this browser. Keep this link private.",
        hood_companion::PORT,
        config.token
    );
    if let Err(e) = hood_companion::serve(config) {
        eprintln!("Companion could not start: {e}");
        std::process::exit(1)
    }
}
