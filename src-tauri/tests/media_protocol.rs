//! Milestone 4 tests: the `igmedia:` protocol serves media by opaque id, and every
//! escape attempt is refused.
//!
//! These exercise `protocol::serve` directly rather than through a webview, because
//! the handler's only job is to turn the result into an HTTP response and that
//! mapping is covered by the unit tests in `protocol.rs`.

mod common;

use ig_viewer_lib::{db, media, protocol};
use std::path::Path;

const THREAD: &str = "mira_okonkwo_4821";

/// Build an archive from the fixture and return (archives_dir, archive_id).
fn indexed_archive(root: &Path, tz: &str) -> (tempfile::TempDir, String, i64) {
    let archives = tempfile::tempdir().expect("archives dir");
    let id = "5".repeat(32);
    let conn = db::open(archives.path(), &id, root, tz).unwrap();
    let month_of = |ts: i64| {
        use chrono::TimeZone;
        use std::str::FromStr;
        chrono_tz::Tz::from_str(tz)
            .unwrap()
            .timestamp_millis_opt(ts)
            .unwrap()
            .format("%Y-%m")
            .to_string()
    };
    let status_of = |uri: &str| match media::resolve(root, uri) {
        Ok(_) => "ok".into(),
        Err(e) => e.code().into(),
    };
    let ing = common::ingest_util::ingest_thread(root, THREAD);
    db::store_thread(&conn, &ing, THREAD, &month_of, &status_of).unwrap();

    // Return a usable media id: one whose status is "ok" and whose type is allowed.
    let id_ok: i64 = conn
        .query_row(
            "SELECT id FROM media WHERE status = 'ok' LIMIT 1",
            [],
            |r| r.get(0),
        )
        .unwrap();
    (archives, id, id_ok)
}

#[test]
fn serves_valid_media_by_opaque_id() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (archives, archive, media_id) = indexed_archive(&root, "Europe/Warsaw");

    let r = protocol::serve(archives.path(), &archive, media_id, None).expect("should serve");
    assert!(
        r.content_type.starts_with("image/")
            || r.content_type.starts_with("video/")
            || r.content_type.starts_with("audio/")
    );
    assert!(!r.body.is_empty());
    assert_eq!(r.total_len, r.body.len() as u64);
    assert!(!r.partial);
    // The fixture stub is a real 1x1 PNG or an ftyp-prefixed stub.
    assert!(r.total_len > 0);
}

#[test]
fn range_requests_return_206_equivalent_slices() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (archives, archive, media_id) = indexed_archive(&root, "Europe/Warsaw");
    let full = protocol::serve(archives.path(), &archive, media_id, None).unwrap();
    if full.total_len < 10 {
        return; // stub too small to range over
    }
    let part = protocol::serve(archives.path(), &archive, media_id, Some("bytes=0-3")).unwrap();
    assert!(part.partial);
    assert_eq!(part.body, &full.body[0..4]);
    assert_eq!(part.total_len, full.total_len);
    // A malformed range is refused rather than approximated.
    assert!(protocol::serve(archives.path(), &archive, media_id, Some("bytes=bad")).is_err());
}

#[test]
fn unknown_media_id_is_not_found() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (archives, archive, _) = indexed_archive(&root, "UTC");
    assert_eq!(
        protocol::serve(archives.path(), &archive, 999_999, None).unwrap_err(),
        protocol::ServeError::NotFound
    );
}

#[test]
fn missing_media_returns_sanitised_error() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (archives, archive, _) = indexed_archive(&root, "UTC");
    // deleted_41.jpg is referenced by a message but absent from disk, so its status
    // is "missing" and serving it must fail cleanly rather than read anything.
    let conn = conn_for(archives.path(), &archive);
    let missing_id: i64 = conn
        .query_row(
            "SELECT id FROM media WHERE status = 'missing' LIMIT 1",
            [],
            |r| r.get(0),
        )
        .unwrap();
    drop(conn);
    let err = protocol::serve(archives.path(), &archive, missing_id, None).unwrap_err();
    assert!(
        matches!(
            err,
            protocol::ServeError::Unavailable | protocol::ServeError::UnsupportedType
        ),
        "missing media must be refused, got {err:?}"
    );
}

#[test]
fn traversal_media_is_refused() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let archives = tempfile::tempdir().unwrap();
    let archive = "6".repeat(32);
    let conn = db::open(archives.path(), &archive, &root, "UTC").unwrap();
    conn.execute(
        "INSERT INTO threads(key,title,raw_count,kept_count) VALUES('t','t',1,1)",
        [],
    )
    .unwrap();
    // Directly insert a traversal URI, bypassing the scanner.
    conn.execute(
        "INSERT INTO messages(thread_id,seq,file_no,idx_in_file,ts_ms,month,sender,content)
         VALUES (1,0,1,0,1,'1970-01','a','x')",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO media(message_id,kind,media_index,uri,status)
         VALUES (1,'photos',0,'../../../../etc/passwd','ok')",
        [],
    )
    .unwrap();
    let id: i64 = conn
        .query_row("SELECT id FROM media LIMIT 1", [], |r| r.get(0))
        .unwrap();
    drop(conn);

    assert_eq!(
        protocol::serve(archives.path(), &archive, id, None).unwrap_err(),
        protocol::ServeError::Unavailable,
        "traversal URI must never be served"
    );
}

#[test]
fn symlinked_media_parent_component_is_refused() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let archives = tempfile::tempdir().unwrap();
    let archive = "7".repeat(32);

    // A symlinked parent directory pointing outside the export.
    let thread_dir = root
        .join("your_instagram_activity/messages/inbox")
        .join(THREAD);
    let outside = tempfile::tempdir().unwrap();
    std::fs::write(outside.path().join("secret.png"), b"secret-bytes").unwrap();
    std::os::unix::fs::symlink(outside.path(), thread_dir.join("linkdir")).unwrap();

    let conn = db::open(archives.path(), &archive, &root, "UTC").unwrap();
    conn.execute(
        "INSERT INTO threads(key,title,raw_count,kept_count) VALUES('t','t',1,1)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO messages(thread_id,seq,file_no,idx_in_file,ts_ms,month,sender,content)
         VALUES (1,0,1,0,1,'1970-01','a','x')",
        [],
    )
    .unwrap();
    let uri = format!("your_instagram_activity/messages/inbox/{THREAD}/linkdir/secret.png");
    conn.execute(
        "INSERT INTO media(message_id,kind,media_index,uri,status) VALUES (1,'photos',0,?1,'ok')",
        [&uri],
    )
    .unwrap();
    let id: i64 = conn
        .query_row("SELECT id FROM media LIMIT 1", [], |r| r.get(0))
        .unwrap();
    drop(conn);

    assert_eq!(
        protocol::serve(archives.path(), &archive, id, None).unwrap_err(),
        protocol::ServeError::Unavailable,
        "symlinked parent component must never be served"
    );
}

#[test]
fn hostile_media_types_are_refused() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let archives = tempfile::tempdir().unwrap();
    let archive = "8".repeat(32);
    let conn = db::open(archives.path(), &archive, &root, "UTC").unwrap();
    conn.execute(
        "INSERT INTO threads(key,title,raw_count,kept_count) VALUES('t','t',2,2)",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO messages(thread_id,seq,file_no,idx_in_file,ts_ms,month,sender,content)
         VALUES (1,0,1,0,1,'1970-01','a','x'),(1,1,1,1,2,'1970-01','a','y')",
        [],
    )
    .unwrap();
    for (i, uri) in [
        "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/evil_55.html",
        "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/script_55.svg",
    ]
    .iter()
    .enumerate()
    {
        conn.execute(
            "INSERT INTO media(message_id,kind,media_index,uri,status)
             VALUES (?1,'photos',0,?2,'ok')",
            rusqlite::params![i as i64 + 1, uri],
        )
        .unwrap();
    }
    let ids: Vec<i64> = {
        let mut st = conn.prepare("SELECT id FROM media ORDER BY id").unwrap();
        let v = st
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<Vec<i64>, _>>()
            .unwrap();
        v
    };
    drop(conn);
    for id in ids {
        assert_eq!(
            protocol::serve(archives.path(), &archive, id, None).unwrap_err(),
            protocol::ServeError::UnsupportedType,
            "html/svg must never be served, even though they are inside the export"
        );
    }
}

#[test]
fn media_id_cannot_be_forged_across_archives() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let archives = tempfile::tempdir().unwrap();

    // Two archives over the same export.
    let a = "9".repeat(32);
    let b = "a".repeat(32);
    for id in [&a, &b] {
        let conn = db::open(archives.path(), id, &root, "UTC").unwrap();
        conn.execute(
            "INSERT INTO threads(key,title,raw_count,kept_count) VALUES('t','t',1,1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(thread_id,seq,file_no,idx_in_file,ts_ms,month,sender,content)
             VALUES (1,0,1,0,1,'1970-01','a','x')",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO media(message_id,kind,media_index,uri,status)
             VALUES (1,'photos',0,'your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/present_photo_40.png','ok')",
            [],
        )
        .unwrap();
        drop(conn);
    }
    // Archive A's media id used against archive B: each archive has its own row
    // space, so a forged id either misses or resolves inside B's own export root
    // only. It must never read something outside either root.
    let r = protocol::serve(archives.path(), &b, 1, None);
    assert!(r.is_ok(), "id 1 exists in archive b as well");
    // A malformed archive id is refused outright.
    assert!(protocol::serve(archives.path(), "../escape", 1, None).is_err());
    assert!(protocol::serve(archives.path(), &"A".repeat(32), 1, None).is_err());
}

#[test]
fn webview_urls_contain_no_paths() {
    let id = "b".repeat(32);
    let url = protocol::media_url(&id, 1234);
    assert_eq!(url, format!("igmedia://media/{id}/1234"));
    // No path separator beyond the fixed route, no filename, no extension.
    assert!(!url.contains(".png"));
    assert!(!url.contains("inbox"));
    assert!(!url.contains("your_instagram_activity"));
    // igmedia://media/<32 hex>/<id> -> 4 separators, all fixed by the route
    assert_eq!(url.matches('/').count(), 4);
}

#[test]
fn serving_leaves_the_export_unchanged() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let before_digest = common::tree_digest(&root);
    let before_structure = common::tree_structure(&root);

    let (archives, archive, media_id) = indexed_archive(&root, "Europe/Warsaw");
    for _ in 0..3 {
        let _ = protocol::serve(archives.path(), &archive, media_id, None);
        let _ = protocol::serve(archives.path(), &archive, media_id, Some("bytes=0-1"));
    }
    // Also exercise the refusal paths.
    let _ = protocol::serve(archives.path(), &archive, 999_999, None);
    let _ = protocol::serve(archives.path(), "../x", 1, None);

    let after_digest = common::tree_digest(&root);
    let after_structure = common::tree_structure(&root);
    for (b, a) in before_digest.iter().zip(&after_digest) {
        assert_eq!(b.0, a.0);
        assert_eq!(b.1, a.1, "bytes changed for {}", b.0);
    }
    assert_eq!(before_structure, after_structure);
}

// Small helpers so the test bodies stay readable.
fn conn_for(dir: &Path, id: &str) -> rusqlite::Connection {
    rusqlite::Connection::open(db::db_path(dir, id)).unwrap()
}
