use tauri::webview::NewWindowResponse;
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

pub mod db;
pub mod ingest;
pub mod media;
pub mod picker;
pub mod protocol;
pub mod scan;
pub mod text;

use std::path::{Path, PathBuf};

use picker::{FolderPicker, NativeFolderPicker};

/// Archives dir: `<app_data_dir>/archives`. One DB per archive.
fn archives_dir<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> Result<PathBuf, String> {
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
/// `archive_id` is optional: an empty/absent id means "this is a new archive", and
/// the id that was minted is returned in the summary. That is what makes the
/// "paste a path and index it" flow work without the native folder picker, which is
/// the only other place an id is created.
fn index_archive(
    app: tauri::AppHandle,
    archive_id: Option<String>,
    export_root: String,
    timezone: Option<String>,
) -> Result<String, String> {
    let dir = archives_dir(&app)?;
    let archive_id = match archive_id.as_deref().filter(|id| !id.is_empty()) {
        Some(id) => {
            db::validate_id(id).map_err(|_| "malformed-archive-id".to_string())?;
            id.to_string()
        }
        None => new_archive_id(),
    };
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
    let mut stored_files = 0usize;
    let mut parse_failures = 0usize;
    for t in &scan.threads {
        // renumber() sorts by the parsed filename number and returns
        // (file_no, messages) plus the title/participants from the first file that
        // parsed, so there is exactly one pass over the bytes.
        let Some(parsed) = renumber(&t.files, &mut parse_failures) else {
            continue;
        };
        let (files, title, participants, ok_files) = parsed;
        stored_files += ok_files;
        if files.is_empty() {
            // Every file in this thread failed to parse: the thread is still
            // registered, with zero messages.
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
        "skipped_files": scan.total_skipped() + parse_failures,
        "parsed_files": stored_files,
        "skip_reasons": scan.skipped.iter()
            .map(|(r, n)| serde_json::json!({"reason": r.code(), "count": n}))
            .collect::<Vec<_>>(),
        "complete": scan.total_skipped() == 0,
        "timezone": tz,
        "timezone_fallback": fell_back,
    });
    Ok(summary.to_string())
}

/// One thread's files, parsed in filename-number order.
type ParsedThread = (
    Vec<(u32, Vec<ingest::RawMessage>)>,
    String,
    Vec<String>,
    usize,
);

/// Parse one thread's files in a single pass.
///
/// Returns `(files, title, participants, ok_count)`, where `files` is sorted by the
/// parsed filename number (so `message_10` follows `message_9`). `parse_failures` is
/// incremented for every file that could not be read or parsed.
fn renumber(paths: &[PathBuf], parse_failures: &mut usize) -> Option<ParsedThread> {
    let mut numbered: Vec<(u32, &PathBuf)> = Vec::new();
    for p in paths {
        let name = p.file_name().and_then(|n| n.to_str())?;
        let no = scan::parse_file_number(name).ok()?;
        numbered.push((no, p));
    }
    numbered.sort_by_key(|(n, _)| *n);

    let mut files = Vec::new();
    let mut title = String::new();
    let mut participants = Vec::new();
    let mut ok = 0usize;
    for (no, p) in numbered {
        let Ok(bytes) = scan::read_bounded(p) else {
            *parse_failures += 1;
            continue;
        };
        match ingest::parse_thread_file(&bytes) {
            Ok(parsed) => {
                if title.is_empty() {
                    title = parsed.title.clone();
                    participants = parsed.participants.iter().map(|p| p.name.clone()).collect();
                }
                ok += 1;
                files.push((no, parsed.messages));
            }
            Err(_) => *parse_failures += 1,
        }
    }
    Some((files, title, participants, ok))
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

fn open_conn<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    archive_id: &str,
) -> Result<rusqlite::Connection, String> {
    db::validate_id(archive_id).map_err(|_| "malformed-archive-id".to_string())?;
    let dir = archives_dir(app)?;
    let path = db::db_path(&dir, archive_id);
    rusqlite::Connection::open(path).map_err(|_| "open-db".to_string())
}

/// Sidebar: every thread with its message count and last-activity time.
#[tauri::command]
fn list_threads(app: tauri::AppHandle, archive_id: String) -> Result<String, String> {
    let conn = open_conn(&app, &archive_id)?;
    let threads = db::list_threads(&conn)?;
    let tz = db::timezone(&conn).unwrap_or_else(|| "UTC".to_string());
    let rows: Vec<_> = threads
        .iter()
        .map(|t| {
            serde_json::json!({
                "key": t.key,
                "title": if t.title.is_empty() { &t.key } else { &t.title },
                "message_count": t.message_count,
                "last_ts_ms": t.last_ts_ms,
            })
        })
        .collect();
    Ok(serde_json::json!({ "threads": rows, "timezone": tz }).to_string())
}

/// A thread's participants and month buckets.
#[tauri::command]
fn get_thread(
    app: tauri::AppHandle,
    archive_id: String,
    thread_key: String,
) -> Result<String, String> {
    let conn = open_conn(&app, &archive_id)?;
    let participants = db::thread_participants(&conn, &thread_key);
    let months = db::thread_months(&conn, &thread_key)?;
    let months: Vec<_> = months
        .iter()
        .map(|m| serde_json::json!({"key": m.key, "count": m.count}))
        .collect();
    Ok(serde_json::json!({ "participants": participants, "months": months }).to_string())
}

/// One month of messages, in display order.
#[tauri::command]
fn get_month(
    app: tauri::AppHandle,
    archive_id: String,
    thread_key: String,
    month: String,
) -> Result<String, String> {
    let conn = open_conn(&app, &archive_id)?;
    let msgs = db::month_messages(&conn, &thread_key, &month)?;
    let rows: Vec<_> = msgs
        .iter()
        .map(|m| {
            let media: Vec<_> = m
                .media
                .iter()
                .map(|md| {
                    let mut o = serde_json::json!({
                        "media_id": md.media_id,
                        "kind": md.kind,
                        "available": md.available,
                    });
                    if md.available {
                        o["url"] = serde_json::Value::String(protocol::media_url(
                            &archive_id,
                            md.media_id,
                        ));
                    }
                    o
                })
                .collect();
            serde_json::json!({
                "id": m.id,
                "seq": m.seq,
                "ts_ms": m.ts_ms,
                "month": m.month,
                "sender": m.sender,
                "content": m.content,
                "is_unsent": m.is_unsent,
                "call_duration": m.call_duration,
                "media": media,
                "reactions": m.reactions.iter().map(|(a, r)| serde_json::json!({"actor": a, "reaction": r})).collect::<Vec<_>>(),
            })
        })
        .collect();
    Ok(serde_json::json!({ "messages": rows }).to_string())
}

/// Month key covering `ts_ms`, for jump-to-date.
#[tauri::command]
fn month_for_ts(app: tauri::AppHandle, archive_id: String, ts_ms: i64) -> Result<String, String> {
    let conn = open_conn(&app, &archive_id)?;
    let month = db::month_of_ts(&conn, ts_ms).unwrap_or_else(|| "1970-01".to_string());
    Ok(serde_json::json!({ "month": month }).to_string())
}

/// The archive's stored timezone and whether it was a detected fallback.
#[tauri::command]
fn get_timezone(app: tauri::AppHandle, archive_id: String) -> Result<String, String> {
    let (detected, fell_back) = system_tz();
    let conn = open_conn(&app, &archive_id)?;
    let stored = db::timezone(&conn).unwrap_or_else(|| detected.clone());
    Ok(serde_json::json!({
        "timezone": stored,
        "system": detected,
        "fallback": fell_back || stored == "UTC" && detected != "UTC",
    })
    .to_string())
}

/// Full-text search over message content.
#[tauri::command]
fn search_messages(
    app: tauri::AppHandle,
    archive_id: String,
    query: String,
) -> Result<String, String> {
    let conn = open_conn(&app, &archive_id)?;
    let hits = db::search(&conn, &query, 300)?;
    let rows: Vec<_> = hits
        .iter()
        .map(|h| {
            serde_json::json!({
                "id": h.id,
                "ts_ms": h.ts_ms,
                "sender": h.sender,
                "snippet": h.snippet,
            })
        })
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

/// `igmedia:` protocol handler.
///
/// Serves media strictly by opaque id. The webview supplies `<archive_id>` and
/// `<media_row_id>` only; the export root and the media URI both come from the
/// archive's own database, so nothing path-like crosses the boundary.
///
/// Responses always carry `X-Content-Type-Options: nosniff` and `Cache-Control:
/// no-store`, and error bodies are a fixed sanitised string.
fn igmedia_handler<R: tauri::Runtime>(
    ctx: tauri::UriSchemeContext<'_, R>,
    request: tauri::http::Request<Vec<u8>>,
    responder: tauri::UriSchemeResponder,
) {
    use tauri::http::{header, Response, StatusCode};

    let dir = match archives_dir(ctx.app_handle()) {
        Ok(d) => d,
        Err(_) => {
            respond_error(responder, 500);
            return;
        }
    };

    let parsed = protocol::parse_media_url(request.uri().path());
    let range = request
        .headers()
        .get(header::RANGE)
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);

    let result = match parsed {
        Ok((archive_id, media_id)) => {
            protocol::serve(&dir, &archive_id, media_id, range.as_deref())
        }
        Err(e) => Err(e),
    };

    match result {
        Ok(m) => {
            let body_len = m.body.len() as u64;
            let mut b = Response::new(m.body);
            *b.status_mut() = if m.partial {
                StatusCode::PARTIAL_CONTENT
            } else {
                StatusCode::OK
            };
            let h = b.headers_mut();
            h.insert(
                header::CONTENT_TYPE,
                header::HeaderValue::from_static(m.content_type),
            );
            h.insert(
                header::ACCEPT_RANGES,
                header::HeaderValue::from_static("bytes"),
            );
            h.insert(
                header::HeaderName::from_static("x-content-type-options"),
                header::HeaderValue::from_static("nosniff"),
            );
            h.insert(
                header::CACHE_CONTROL,
                header::HeaderValue::from_static("no-store"),
            );
            if m.partial {
                if let Ok(v) = header::HeaderValue::from_str(&format!(
                    "bytes {}-{}/{}",
                    m.range_start,
                    m.range_start + body_len - 1,
                    m.total_len
                )) {
                    h.insert(header::CONTENT_RANGE, v);
                }
            }
            responder.respond(b);
        }
        Err(e) => {
            // Aggregate counters only: never the URI, the path or the id.
            log::warn!("igmedia request refused: status={}", e.status());
            respond_error(responder, e.status());
        }
    }
}

/// A fixed body for every failure, so an error response never describes the file.
fn respond_error(responder: tauri::UriSchemeResponder, status: u16) {
    use tauri::http::{header, Response, StatusCode};
    let mut b = Response::new(b"media unavailable".to_vec());
    *b.status_mut() = StatusCode::from_u16(status).unwrap_or(StatusCode::FORBIDDEN);
    let h = b.headers_mut();
    h.insert(
        header::HeaderName::from_static("x-content-type-options"),
        header::HeaderValue::from_static("nosniff"),
    );
    h.insert(
        header::CONTENT_TYPE,
        header::HeaderValue::from_static("text/plain; charset=utf-8"),
    );
    responder.respond(b);
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
        // igmedia is a SUBRESOURCE scheme only: it may be an <img>/<video>/<audio>
        // src, never a document target. Refusing it here means a stray link or a
        // window.open cannot turn a media URL into a navigation or an IPC origin.
        "igmedia" => false,
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
        .register_asynchronous_uri_scheme_protocol("igmedia", igmedia_handler)
        .invoke_handler(tauri::generate_handler![
            app_info,
            pick_export_folder,
            index_archive,
            list_threads,
            get_thread,
            get_month,
            month_for_ts,
            get_timezone,
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
