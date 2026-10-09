use tauri::webview::NewWindowResponse;
use tauri::{WebviewUrl, WebviewWindowBuilder};

/// Allow only the app's own origin and, later, the local media protocol.
///
/// Production origin is `tauri://localhost` (or `http(s)://tauri.localhost`).
/// In `tauri dev` the CLI's built-in asset server binds to loopback
/// (`http://127.0.0.1:<port>`), so loopback hosts are allowed in dev builds only.
/// Returning `false` cancels the navigation (Tauri 2 `WebviewWindowBuilder::on_navigation`).
fn navigation_allowed(url: &tauri::Url) -> bool {
    match url.scheme() {
        "tauri" => true,
        "http" | "https" => url.host_str() == Some("tauri.localhost") || allow_dev_server(url),
        // Registered in a later milestone; inert until then.
        "igmedia" => true,
        _ => false,
    }
}

/// The built-in `tauri dev` asset server only ever binds to loopback.
#[cfg(dev)]
fn allow_dev_server(url: &tauri::Url) -> bool {
    matches!(
        url.host_str(),
        Some("localhost") | Some("127.0.0.1") | Some("::1") | Some("[::1]")
    )
}

#[cfg(not(dev))]
fn allow_dev_server(_url: &tauri::Url) -> bool {
    false
}

#[tauri::command]
fn app_info() -> String {
    format!("ig-viewer {}", env!("CARGO_PKG_VERSION"))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![app_info])
        .setup(|app| {
            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("IG Chat Archive Viewer")
                .inner_size(1200.0, 800.0)
                .min_inner_size(700.0, 480.0)
                // Enabled in dev only. Release builds additionally require the
                // `devtools` cargo feature, which is intentionally NOT enabled.
                .devtools(cfg!(debug_assertions))
                // No in-app navigation away from the app origin. The app is
                // offline-only, so there is no external-link path at all.
                .on_navigation(navigation_allowed)
                // `window.open`/target=_blank must never create a new webview.
                .on_new_window(|_url, _features| NewWindowResponse::Deny)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running ig-viewer");
}
