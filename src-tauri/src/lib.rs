use tauri::webview::NewWindowResponse;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

pub mod db;
pub mod ingest;
pub mod media;
pub mod picker;
pub mod scan;
pub mod text;

use std::path::{Path, PathBuf};

use picker::{FolderPicker, NativeFolderPicker};

/// Archives dir: `<app_data_dir>/archives`. One DB per archive.
fn archives_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|_| "no-app-data-dir".to_string())?
        .join("archives");
    std::fs::create_dir_all(&dir).map_err(|_| "create-archives-dir".to_string())?;
    Ok(dir)
}

/// System IANA timezone, or UTC when detection fails.
fn system_tz() -> (String, bool) {
    match iana_time_zone::get_timezone() {
        Ok(tz) => (tz, false),
        Err(_) => ("UTC".to_string(), true), // fell back; the UI must say so
    }
}

// ---------------------------------------------------------------- commands

#[tauri::command]
fn app_info() -> String {
    format!("ig-viewer {}", env!("CARGO_PKG_VERSION"))
}

/// Pick an export folder. Native dialog via rfd, so no capability is needed.
#[tauri::command]
fn pick_export_folder(app: tauri::AppHandle) -> Result<Option<String>, String> {
    let picker = NativeFolderPicker;
    let picked = picker
        .pick_folder("Select your unzipped Instagram export folder")
        .ok_or_else(|| "picker-unavailable".to_string())?;
    let dir = archives_dir(&app)?;
    let id = new_archive_id();
    let (tz, fell_back) = system_tz();
    let conn = db::open(&dir, &id, &picked, &tz)?;
    drop(conn);
    Ok(Some(
        serde_json::json!({ "archive_id": id, "export_root": picked, "timezone": tz,
                                 "timezone_fallback": fell_back })
        .to_string(),
    ))
}

/// Ingest an already-picked root into an existing archive.
#[tauri::command]
fn index_archive(
    app: tauri::AppHandle,
    archive_id: String,
    export_root: String,
    timezone: Option<String>,
) -> Result<String, String> {
    let dir = archives_dir(&app)?;
    let root = PathBuf::from(&export_root);
    let (tz, fell_back) = timezone.map(|t| (t, false)).unwrap_or_else(system_tz);

    let scan = scan::scan(&root).map_err(|_| "scan-failed".to_string())?;
    let conn = db::open(&dir, &archive_id, &root, &tz)?;
    let month_of = month_bucketer(&tz);
    let status_of = |uri: &str| {
        media::resolve(&root, uri)
            .map(|_| "ok".to_string())
            .unwrap_or_else(|e| e.code().to_string())
    };

    let mut stored_threads = 0usize;
    let mut stored_messages = 0usize;
    for t in &scan.threads {
        let mut files: Vec<(u32, Vec<ingest::RawMessage>)> = Vec::new();
        let mut title = String::new();
        let mut participants = Vec::new();
        for path in &t.files {
            let bytes = match scan::read_bounded(path) {
                Ok(b) => b,
                Err(_) => continue, // already counted by the scanner
            };
            let parsed = match ingest::parse_thread_file(&bytes) {
                Ok(p) => p,
                Err(_) => continue,
            };
            if title.is_empty() {
                title = parsed.title.clone();
                participants = parsed.participants.iter().map(|p| p.name.clone()).collect();
            }
            let no = t
                .files
                .iter()
                .position(|p| p == path)
                .map(|i| i as u32 + 1)
                .unwrap_or(0);
            files.push((no, parsed.messages));
        }
        // Re-number from actual filenames rather than enumeration position.
        files = renumber(&t.files, &root)?;
        if files.is_empty() {
            continue;
        }
        let thread = ingest::ingest_thread(&title, &participants, &files);
        db::store_thread(&conn, &thread, &t.key, &month_of, &status_of)?;
        stored_threads += 1;
        stored_messages += thread.kept.len();
    }

    // Aggregate counts only. No content, sender, title, URI or path is logged.
    log::info!(
        "archive indexed: threads={} messages={} skipped_files={}",
        stored_threads,
        stored_messages,
        scan.total_skipped()
    );

    let summary = serde_json::json!({
        "archive_id": archive_id,
        "threads": stored_threads,
        "messages": stored_messages,
        "skipped_files": scan.total_skipped(),
        "skip_reasons": scan.skipped.iter()
            .map(|(r, n)| serde_json::json!({"reason": r.code(), "count": n}))
            .collect::<Vec<_>>(),
        "complete": scan.total_skipped() == 0,
        "timezone": tz,
        "timezone_fallback": fell_back,
    });
    Ok(summary.to_string())
}

/// `(file_no, messages)` using the real parsed filename numbers.
fn renumber(paths: &[PathBuf], root: &Path) -> Result<Vec<(u32, Vec<ingest::RawMessage>)>, String> {
    let mut numbered: Vec<(u32, &PathBuf)> = Vec::new();
    for p in paths {
        let name = p.file_name().and_then(|n| n.to_str()).ok_or("bad-name")?;
        let no = scan::parse_file_number(name).map_err(|_| "bad-name".to_string())?;
        numbered.push((no, p));
    }
    numbered.sort_by_key(|(n, _)| *n);
    let mut out = Vec::new();
    for (no, p) in numbered {
        let bytes = scan::read_bounded(p).map_err(|_| "read-failed".to_string())?;
        if let Ok(parsed) = ingest::parse_thread_file(&bytes) {
            out.push((no, parsed.messages));
        }
    }
    let _ = root;
    Ok(out)
}

/// Change the stored timezone and re-bucket months from stored timestamps only.
#[tauri::command]
fn set_timezone(app: tauri::AppHandle, archive_id: String, timezone: String) -> Result<(), String> {
    let dir = archives_dir(&app)?;
    let conn = db::open(&dir, &archive_id, Path::new("."), &timezone)?;
    let month_of = month_bucketer(&timezone);
    conn.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES ('timezone', ?1)",
        [&timezone],
    )
    .map_err(|e| e.to_string())?;
    let mut st = conn
        .prepare("SELECT id, ts_ms FROM messages")
        .map_err(|e| e.to_string())?;
    let rows: Vec<(i64, i64)> = st
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(|e| e.to_string())?
        .collect::<Result<_, _>>()
        .map_err(|e| e.to_string())?;
    drop(st);
    for (id, ts) in rows {
        conn.execute(
            "UPDATE messages SET month = ?1 WHERE id = ?2",
            [month_of(ts), id.to_string()],
        )
        .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Full-text search over message content.
#[tauri::command]
fn search_messages(
    app: tauri::AppHandle,
    archive_id: String,
    query: String,
) -> Result<String, String> {
    let dir = archives_dir(&app)?;
    let conn = db::open(&dir, &archive_id, Path::new("."), "UTC")?;
    let mut st = conn
        .prepare(
            "SELECT m.id, m.ts_ms FROM messages_fts f
             JOIN messages m ON m.id = f.rowid
             WHERE messages_fts MATCH ?1 ORDER BY m.ts_ms ASC LIMIT 300",
        )
        .map_err(|e| e.to_string())?;
    let hits: Vec<(i64, i64)> = st
        .query_map([&query], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(|e| e.to_string())?
        .collect::<Result<_, _>>()
        .map_err(|e| e.to_string())?;
    let rows: Vec<_> = hits
        .iter()
        .map(|(i, t)| serde_json::json!({ "id": i, "ts_ms": t }))
        .collect();
    Ok(serde_json::json!({ "hits": rows }).to_string())
}

/// Delete an archive index. Only a validated 32-hex id is accepted.
#[tauri::command]
fn delete_index(app: tauri::AppHandle, archive_id: String) -> Result<(), String> {
    let dir = archives_dir(&app)?;
    db::delete_index(&dir, &archive_id).map_err(|e| format!("{e:?}"))
}

/// Month key `YYYY-MM` for a timestamp, evaluated in `tz`.
///
/// The app must stay offline, so rather than depending on a tz database this
/// resolves the zone's *offset* for the given instant via `chrono-tz`, which
/// bundles the IANA rules. An unknown zone falls back to UTC, and the caller
/// surfaces that in the UI.
fn month_bucketer(tz: &str) -> impl Fn(i64) -> String {
    let zone = tz.parse::<chrono_tz::Tz>().ok();
    move |ts_ms: i64| {
        use chrono::TimeZone;
        let Some(zone) = zone else {
            return chrono::Utc
                .timestamp_millis_opt(ts_ms)
                .single()
                .map(|d| d.format("%Y-%m").to_string())
                .unwrap_or_else(|| "1970-01".to_string());
        };
        zone.timestamp_millis_opt(ts_ms)
            .single()
            .map(|d| d.format("%Y-%m").to_string())
            .unwrap_or_else(|| "1970-01".to_string())
    }
}

/// A random 32-hex archive id.
fn new_archive_id() -> String {
    // Not cryptographic: it only has to be unique on the local filesystem.
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let pid = std::process::id() as u128;
    let mixed = nanos
        .wrapping_mul(0x9E37_79B9_7F4A_7C15)
        .wrapping_add(pid.wrapping_mul(0xBF58_476D_1CE4_E5B9));
    format!("{mixed:032x}")
}

// ---------------------------------------------------------------- app setup

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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            app_info,
            pick_export_folder,
            index_archive,
            set_timezone,
            search_messages,
            delete_index,
        ])
        .setup(|app| {
            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("IG Chat Archive Viewer")
                .inner_size(1200.0, 800.0)
                .min_inner_size(700.0, 480.0)
                // Enabled in dev only. Release builds additionally require the
                // `devtools` cargo feature, which is intentionally NOT enabled.
                .devtools(cfg!(debug_assertions))
                // No in-app navigation away from the app origin.
                .on_navigation(navigation_allowed)
                // `window.open`/target=_blank must never create a new webview.
                .on_new_window(|_url, _features| NewWindowResponse::Deny)
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running ig-viewer");
}
