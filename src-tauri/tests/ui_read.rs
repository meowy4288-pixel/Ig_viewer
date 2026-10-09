//! Tests for the read path the M5 UI depends on: month paging order, search,
//! jump-to-date, media ids, timezone metadata and export immutability.

mod common;

use ig_viewer_lib::{db, media};
use std::path::Path;

const THREADS: [&str; 4] = [
    "mira_okonkwo_4821",
    "dev_team_7730",
    "sam_lee_9012",
    "ana_silva_5544",
];

fn month_of(tz: &str, ts_ms: i64) -> String {
    use chrono::TimeZone;
    use std::str::FromStr;
    chrono_tz::Tz::from_str(tz)
        .unwrap()
        .timestamp_millis_opt(ts_ms)
        .unwrap()
        .format("%Y-%m")
        .to_string()
}

/// Index every fixture thread into a temp archive. Returns (dir, archive_id).
fn full_archive(root: &Path, tz: &str) -> (tempfile::TempDir, String, rusqlite::Connection) {
    let archives = tempfile::tempdir().unwrap();
    let id = "c".repeat(32);
    let conn = db::open(archives.path(), &id, root, tz).unwrap();
    let month_of_fn = |ts: i64| month_of(tz, ts);
    let status_of = |uri: &str| match media::resolve(root, uri) {
        Ok(_) => "ok".into(),
        Err(e) => e.code().into(),
    };
    for t in THREADS {
        let ing = common::ingest_util::ingest_thread(root, t);
        db::store_thread(&conn, &ing, t, &month_of_fn, &status_of).unwrap();
    }
    (archives, id, conn)
}

#[test]
fn thread_list_is_ordered_by_recent_activity() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    let threads = db::list_threads(&conn).unwrap();
    assert_eq!(threads.len(), 4);
    // Newest activity first.
    for w in threads.windows(2) {
        assert!(w[0].last_ts_ms >= w[1].last_ts_ms, "must be descending");
    }
    assert!(threads.iter().all(|t| !t.title.is_empty()));
}

#[test]
fn month_paging_returns_messages_in_display_order() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    let months = db::thread_months(&conn, THREADS[0]).unwrap();
    assert_eq!(months.len(), 6, "six month buckets in the large thread");
    assert_eq!(months[0].key, "2024-01");
    assert_eq!(months.last().unwrap().key, "2025-01");

    // Every month's messages must be strictly in (ts ASC, seq DESC) order.
    let mut total = 0i64;
    for m in &months {
        let msgs = db::month_messages(&conn, THREADS[0], &m.key).unwrap();
        total += msgs.len() as i64;
        assert_eq!(msgs.len() as i64, m.count, "{} count", m.key);
        for w in msgs.windows(2) {
            let a = &w[0];
            let b = &w[1];
            assert!(
                a.ts_ms < b.ts_ms || (a.ts_ms == b.ts_ms && a.seq > b.seq),
                "{}: order broken at ts={} / {}",
                m.key,
                a.ts_ms,
                b.ts_ms
            );
        }
    }
    assert_eq!(total, 157, "all messages accounted for across months");
}

#[test]
fn month_buckets_partition_the_thread_without_gaps_or_overlap() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    let months = db::thread_months(&conn, THREADS[0]).unwrap();
    let mut keys: Vec<&str> = months.iter().map(|m| m.key.as_str()).collect();
    let sorted = {
        let mut k = keys.clone();
        k.sort_unstable();
        k
    };
    assert_eq!(keys, sorted, "month keys must be sorted");
    keys.dedup();
    assert_eq!(keys.len(), months.len(), "no duplicate month buckets");
    // Buckets are contiguous in time.
    for w in months.windows(2) {
        assert!(
            w[0].last_ts_ms <= w[1].first_ts_ms,
            "months must not overlap"
        );
    }
}

#[test]
fn messages_carry_opaque_media_ids_and_no_paths() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, id, conn) = full_archive(&root, "Europe/Warsaw");
    let msgs = db::month_messages(&conn, THREADS[0], "2024-01").unwrap();
    assert!(!msgs.is_empty());
    let mut with_media = 0;
    for m in &msgs {
        for md in &m.media {
            with_media += 1;
            assert!(md.media_id > 0, "media id must be a positive row id");
            // The UI struct carries no URI and no path at all.
            let url = ig_viewer_lib::protocol::media_url(&id, md.media_id);
            assert!(url.starts_with("igmedia://media/"));
            assert!(
                !url.contains("inbox"),
                "url must not contain the thread path"
            );
            assert!(!url.contains('.'), "url must not contain a filename");
        }
    }
    assert!(with_media > 0, "fixture has media to display");
}

#[test]
fn fts5_search_finds_fixture_content_and_respects_order() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    let hits: Vec<i64> = {
        let mut st = conn
            .prepare(
                "SELECT m.ts_ms FROM messages_fts f JOIN messages m ON m.id = f.rowid
                 WHERE messages_fts MATCH ?1 ORDER BY m.ts_ms ASC",
            )
            .unwrap();
        let v = st
            .query_map(["build"], |r| r.get(0))
            .unwrap()
            .collect::<Result<Vec<i64>, _>>()
            .unwrap();
        v
    };
    assert!(
        !hits.is_empty(),
        "search for 'build' must find fixture text"
    );
    for w in hits.windows(2) {
        assert!(w[0] <= w[1], "search results must be chronological");
    }
    // A term that does not exist returns nothing rather than everything.
    let none: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'zzzznotaterm'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(none, 0);
}

#[test]
fn jump_to_date_resolves_the_covering_month() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    // The new-year fixture is 2024-12-31T23:00:01Z -> 2025-01 in Warsaw.
    let ts = 1_735_686_001_000i64;
    assert_eq!(month_of_ts_and_check(&conn, ts), "2025-01");
    // The month boundary pair.
    assert_eq!(month_of_ts_and_check(&conn, 1_706_741_999_000), "2024-01");
    assert_eq!(month_of_ts_and_check(&conn, 1_706_742_001_000), "2024-02");
}

fn month_of_ts_and_check(conn: &rusqlite::Connection, ts: i64) -> String {
    db::month_of_ts(conn, ts).expect("a month should cover this instant")
}

#[test]
fn timezone_is_persisted_and_readable() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    assert_eq!(db::timezone(&conn).as_deref(), Some("Europe/Warsaw"));
}

#[test]
fn rebucketing_to_utc_changes_the_new_year_bucket() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    // Same instant: Warsaw says 2025-01, UTC says 2024-12.
    let ts = 1_735_686_001_000i64;
    let warsaw = db::month_of_ts(&conn, ts).unwrap();
    assert_eq!(warsaw, "2025-01");
    let utc_month = month_of("UTC", ts);
    assert_eq!(utc_month, "2024-12");
    // Rebucket in place, as set_timezone does.
    conn.execute(
        "UPDATE messages SET month = ?1 WHERE ts_ms = ?2",
        [utc_month, ts.to_string()],
    )
    .unwrap();
    assert_eq!(db::month_of_ts(&conn, ts).unwrap(), "2024-12");
    // Timestamps themselves are untouched.
    let same: i64 = conn
        .query_row(
            "SELECT ts_ms FROM messages WHERE ts_ms = ?1 LIMIT 1",
            [ts],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(same, ts);
}

#[test]
fn participants_come_back_from_the_index() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    let parts = db::thread_participants(&conn, "dev_team_7730");
    assert!(
        parts.len() >= 4,
        "group chat has several participants, got {parts:?}"
    );
    assert!(parts.iter().any(|p| p.contains("Ana O'Brien-Świątek")));
    assert!(
        parts.iter().any(|p| p.contains('/')),
        "a name with a slash is preserved"
    );
}

#[test]
fn ui_reads_leave_the_export_untouched() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let before = common::tree_digest(&root);
    let before_struct = common::tree_structure(&root);
    let (_d, _id, conn) = full_archive(&root, "Europe/Warsaw");
    for t in THREADS {
        let _ = db::list_threads(&conn).unwrap();
        let _ = db::thread_participants(&conn, t);
        for m in db::thread_months(&conn, t).unwrap() {
            let msgs = db::month_messages(&conn, t, &m.key).unwrap();
            for msg in msgs {
                for md in msg.media {
                    if md.available {
                        let uri: String = conn
                            .query_row("SELECT uri FROM media WHERE id = ?1", [md.media_id], |r| {
                                r.get(0)
                            })
                            .unwrap();
                        let _ = media::resolve(&root, &uri);
                    }
                }
            }
        }
    }
    let after = common::tree_digest(&root);
    let after_struct = common::tree_structure(&root);
    for (b, a) in before.iter().zip(&after) {
        assert_eq!(b.0, a.0);
        assert_eq!(b.1, a.1, "bytes changed for {}", b.0);
    }
    assert_eq!(before_struct, after_struct);
}
