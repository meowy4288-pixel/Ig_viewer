//! Tests 7-11: FTS5, timezone/month bucketing, log sanitisation, delete-index, and
//! proof that an ingest run leaves the export untouched.

mod common;

use ig_viewer_lib::picker::FolderPicker;
use ig_viewer_lib::{db, media, picker};
use std::path::Path;

const THREADS: [&str; 4] = [
    "mira_okonkwo_4821",
    "dev_team_7730",
    "sam_lee_9012",
    "ana_silva_5544",
];

fn month_in(tz: &str, ts_ms: i64) -> String {
    use chrono::TimeZone;
    use std::str::FromStr;
    chrono_tz::Tz::from_str(tz)
        .expect("tz")
        .timestamp_millis_opt(ts_ms)
        .unwrap()
        .format("%Y-%m")
        .to_string()
}

// ---------------------------------------------------- test 7: FTS5 + sync

#[test]
fn fts5_match_returns_hits_and_follows_edits() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "e".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "Europe/Warsaw").unwrap();
    let month_of = |ts: i64| month_in("Europe/Warsaw", ts);
    let status_of = |uri: &str| match media::resolve(&root, uri) {
        Ok(_) => "ok".into(),
        Err(e) => e.code().into(),
    };
    for t in THREADS {
        let ing = common::ingest_util::ingest_thread(&root, t);
        db::store_thread(&conn, &ing, t, &month_of, &status_of).unwrap();
    }

    let hits = |q: &str| -> i64 {
        conn.query_row(
            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH ?1",
            [q],
            |r| r.get(0),
        )
        .unwrap()
    };
    // A distinctive token that exists in the fixture content.
    assert!(hits("build") > 0, "indexed content must be searchable");

    // Update: the trigger must remove the old text and index the new.
    conn.execute(
        "UPDATE messages SET content = 'zzqqxxuniquetoken' WHERE id = (SELECT MIN(id) FROM messages)",
        [],
    )
    .unwrap();
    assert!(
        hits("zzqqxxuniquetoken") == 1,
        "update must enter the index"
    );

    // Delete: the trigger must drop the row from the index.
    conn.execute(
        "DELETE FROM messages WHERE id = (SELECT MIN(id) FROM messages)",
        [],
    )
    .unwrap();
    assert!(
        hits("zzqqxxuniquetoken") == 0,
        "delete must leave the index"
    );
}

#[test]
fn reactions_are_stored_in_their_own_table() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "f".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "UTC").unwrap();
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    let expected = ing.kept.iter().map(|m| m.reactions.len()).sum::<usize>();
    db::store_thread(
        &conn,
        &ing,
        "mira_okonkwo_4821",
        &|ts| month_in("UTC", ts),
        &|_| "ok".into(),
    )
    .unwrap();
    let stored: i64 = conn
        .query_row("SELECT COUNT(*) FROM reactions", [], |r| r.get(0))
        .unwrap();
    assert_eq!(stored as usize, expected);
    assert!(stored > 0, "fixture has reactions to store");
}

#[test]
fn media_statuses_are_recorded() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "0".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "UTC").unwrap();
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    let status_of = |uri: &str| match media::resolve(&root, uri) {
        Ok(_) => "ok".into(),
        Err(e) => e.code().into(),
    };
    db::store_thread(
        &conn,
        &ing,
        "mira_okonkwo_4821",
        &|ts| month_in("UTC", ts),
        &status_of,
    )
    .unwrap();
    let count = |s: &str| -> i64 {
        conn.query_row("SELECT COUNT(*) FROM media WHERE status = ?1", [s], |r| {
            r.get(0)
        })
        .unwrap()
    };
    assert!(count("ok") > 0);
    assert!(count("traversal-rejected") > 0, "traversal URIs recorded");
    assert!(count("missing") > 0, "missing media recorded");
}

// ------------------------------------------- test 8: timezone / month keys

#[test]
fn month_bucketing_matches_the_oracle() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "1".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "Europe/Warsaw").unwrap();
    let month_of = |ts: i64| month_in("Europe/Warsaw", ts);
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    db::store_thread(&conn, &ing, "mira_okonkwo_4821", &month_of, &|_| {
        "ok".into()
    })
    .unwrap();

    // Months present, in order.
    let mut months: Vec<String> = {
        let mut st = conn
            .prepare("SELECT DISTINCT month FROM messages ORDER BY month")
            .unwrap();
        let v = st
            .query_map([], |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        v
    };
    months.sort();
    assert_eq!(
        months,
        vec!["2024-01", "2024-02", "2024-03", "2024-10", "2024-12", "2025-01"],
        "month buckets under Europe/Warsaw"
    );
}

#[test]
fn the_same_timestamp_buckets_differently_in_utc_and_local() {
    // m0159: 2024-12-31T23:00:01Z is 2024-12 in UTC but 2025-01 in Europe/Warsaw.
    let ts = 1_735_686_001_000i64;
    assert_eq!(month_in("UTC", ts), "2024-12");
    assert_eq!(month_in("Europe/Warsaw", ts), "2025-01");
}

#[test]
fn month_boundary_fixtures_land_in_the_expected_months() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    let find = |needle: &str| {
        ing.kept
            .iter()
            .find(|m| m.content.as_deref().unwrap_or("").starts_with(needle))
            .unwrap_or_else(|| panic!("missing fixture {needle}"))
            .ts_ms
    };
    assert_eq!(
        month_in("Europe/Warsaw", find("month boundary last second")),
        "2024-01"
    );
    assert_eq!(
        month_in("Europe/Warsaw", find("month boundary first second")),
        "2024-02"
    );
    assert_eq!(month_in("Europe/Warsaw", find("leap day")), "2024-02");
    assert_eq!(month_in("Europe/Warsaw", find("year boundary")), "2024-12");
    assert_eq!(month_in("Europe/Warsaw", find("new year")), "2025-01");
    // Both DST halves stay in 2024-03 / 2024-10 despite differing offsets.
    assert_eq!(
        month_in("Europe/Warsaw", find("dst spring 01:30")),
        "2024-03"
    );
    assert_eq!(
        month_in("Europe/Warsaw", find("dst spring 03:30")),
        "2024-03"
    );
    assert_eq!(month_in("Europe/Warsaw", find("dst fall first")), "2024-10");
    assert_eq!(
        month_in("Europe/Warsaw", find("dst fall second")),
        "2024-10"
    );
}

#[test]
fn rebucketing_changes_months_without_touching_timestamps() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "2".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "UTC").unwrap();
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    db::store_thread(&conn, &ing, "t", &|ts| month_in("UTC", ts), &|_| {
        "ok".into()
    })
    .unwrap();

    let before: Vec<i64> = {
        let mut st = conn
            .prepare("SELECT ts_ms FROM messages ORDER BY id")
            .unwrap();
        let v = st
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        v
    };
    // Rebucket to Warsaw, exactly as the set_timezone command does.
    for (id, ts) in {
        let mut st = conn.prepare("SELECT id, ts_ms FROM messages").unwrap();
        let v = st
            .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        v
    } {
        conn.execute(
            "UPDATE messages SET month = ?1 WHERE id = ?2",
            [month_in("Europe/Warsaw", ts), id.to_string()],
        )
        .unwrap();
    }
    let after: Vec<i64> = {
        let mut st = conn
            .prepare("SELECT ts_ms FROM messages ORDER BY id")
            .unwrap();
        let v = st
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        v
    };
    assert_eq!(before, after, "rebucketing must not change timestamps");
    let ny: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM messages WHERE month = '2025-01'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert!(ny > 0, "rebucketing to Warsaw produced a 2025-01 bucket");
}

// ---------------------------------------------- test 10: delete-index

#[test]
fn delete_index_removes_db_and_sidecars() {
    let d = tempfile::tempdir().unwrap();
    let id = "3".repeat(32);
    let conn = db::open(d.path(), &id, Path::new("/tmp"), "UTC").unwrap();
    conn.execute("CREATE TABLE t(x)", []).unwrap();
    drop(conn);
    let base = db::db_path(d.path(), &id);
    assert!(base.exists());
    assert!(db::delete_index(d.path(), &id).is_ok());
    assert!(!base.exists());
    assert!(!std::path::PathBuf::from(format!("{}-wal", base.display())).exists());
    assert!(!std::path::PathBuf::from(format!("{}-shm", base.display())).exists());
    assert!(!db::exists(d.path(), &id));
}

#[test]
fn delete_index_rejects_ids_that_could_escape() {
    let d = tempfile::tempdir().unwrap();
    for bad in [
        "../../etc/passwd",
        "..",
        "/etc/passwd",
        &"A".repeat(32),
        &"z".repeat(32),
        &"3".repeat(31),
        &"3".repeat(33),
        "",
    ] {
        assert_eq!(
            db::delete_index(d.path(), bad),
            Err(db::IdError::Malformed),
            "must reject {bad:?} without touching disk"
        );
    }
    // Nothing was created anywhere.
    assert_eq!(std::fs::read_dir(d.path()).unwrap().count(), 0);
}

// ------------------------------------------- test 11: export left unchanged

#[test]
fn ingest_leaves_the_export_byte_and_structure_identical() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let before_digest = common::tree_digest(&root);
    let before_structure = common::tree_structure(&root);

    let dbdir = tempfile::tempdir().unwrap();
    let id = "4".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "Europe/Warsaw").unwrap();
    let month_of = |ts: i64| month_in("Europe/Warsaw", ts);
    let status_of = |uri: &str| match media::resolve(&root, uri) {
        Ok(_) => "ok".into(),
        Err(e) => e.code().into(),
    };
    for t in THREADS {
        let ing = common::ingest_util::ingest_thread(&root, t);
        db::store_thread(&conn, &ing, t, &month_of, &status_of).unwrap();
    }
    // Full text search reads through FTS.
    let _ = conn
        .query_row(
            "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'build'",
            [],
            |r| r.get::<_, i64>(0),
        )
        .unwrap();

    let after_digest = common::tree_digest(&root);
    let after_structure = common::tree_structure(&root);
    assert_eq!(
        before_digest.len(),
        after_digest.len(),
        "file count changed"
    );
    for (b, a) in before_digest.iter().zip(&after_digest) {
        assert_eq!(b.0, a.0, "path changed");
        assert_eq!(b.1, a.1, "bytes changed for {}", b.0);
    }
    assert_eq!(
        before_structure, after_structure,
        "directory structure changed during ingest"
    );
}

// ---------------------------------------------------- test 8: picker stub

#[test]
fn folder_picker_is_substitutable() {
    let d = tempfile::tempdir().unwrap();
    let stub = picker::StubFolderPicker(Some(d.path().to_path_buf()));
    assert_eq!(stub.pick_folder("x"), Some(d.path().to_path_buf()));
    let cancelled = picker::StubFolderPicker(None);
    assert_eq!(cancelled.pick_folder("x"), None);
}
