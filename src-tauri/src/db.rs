//! Per-archive SQLite index.
//!
//! One database per archive, living in the app data dir -- never inside the
//! export, which stays strictly read-only.
//!
//! FTS5 uses an external-content table over `messages` with triggers keeping it in
//! sync for insert, update and delete.

use std::path::{Path, PathBuf};

use rusqlite::{Connection, OptionalExtension};

use crate::ingest::ThreadIngest;

pub const SCHEMA_VERSION: i64 = 1;

/// Why an archive id was rejected. Never contains a caller-supplied path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IdError {
    /// Not exactly 32 lowercase hex characters.
    Malformed,
    /// Archive does not exist in `dir`.
    NotFound,
}

pub const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS threads (
    id         INTEGER PRIMARY KEY,
    key        TEXT NOT NULL UNIQUE,
    title      TEXT,
    raw_count  INTEGER NOT NULL,
    kept_count INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id           INTEGER PRIMARY KEY,
    thread_id    INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    seq          INTEGER NOT NULL,   -- raw encounter order, assigned pre-dedupe
    file_no      INTEGER NOT NULL,
    idx_in_file  INTEGER NOT NULL,
    ts_ms        INTEGER NOT NULL,
    month        TEXT NOT NULL,      -- 'YYYY-MM' in the stored timezone
    sender       TEXT NOT NULL,
    content      TEXT,
    is_unsent    INTEGER NOT NULL DEFAULT 0,
    call_duration INTEGER,
    UNIQUE(thread_id, seq)
);
CREATE INDEX IF NOT EXISTS messages_thread_ts ON messages(thread_id, ts_ms);
CREATE INDEX IF NOT EXISTS messages_month ON messages(thread_id, month);

CREATE TABLE IF NOT EXISTS media (
    id          INTEGER PRIMARY KEY,
    message_id  INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,
    media_index INTEGER NOT NULL,
    uri         TEXT NOT NULL,
    status      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS media_message ON media(message_id);

-- Reactions live in their own table: the UI renders them per-actor and search only
-- covers content, so folding them into a JSON column would conflate two uses.
CREATE TABLE IF NOT EXISTS reactions (
    id         INTEGER PRIMARY KEY,
    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    actor      TEXT NOT NULL,
    reaction   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS reactions_message ON reactions(message_id);

CREATE TABLE IF NOT EXISTS dropped (
    id         INTEGER PRIMARY KEY,
    thread_id  INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,
    file_no    INTEGER NOT NULL,
    idx_in_file INTEGER NOT NULL,
    ts_ms      INTEGER NOT NULL,
    kept_at    TEXT NOT NULL
);

CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(
    content,
    sender,
    content='messages',
    content_rowid='id',
    tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
    INSERT INTO messages_fts(rowid, content, sender) VALUES (new.id, new.content, new.sender);
END;
CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content, sender)
        VALUES ('delete', old.id, old.content, old.sender);
END;
CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
    INSERT INTO messages_fts(messages_fts, rowid, content, sender)
        VALUES ('delete', old.id, old.content, old.sender);
    INSERT INTO messages_fts(rowid, content, sender) VALUES (new.id, new.content, new.sender);
END;
"#;

/// An archive id is exactly 32 lowercase hex characters. Validated before any path
/// is built, so a hostile id cannot escape the archives directory.
pub fn validate_id(id: &str) -> Result<(), IdError> {
    if id.len() != 32
        || !id
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(IdError::Malformed);
    }
    Ok(())
}

/// Path of an archive's database, inside `dir`. Only called after `validate_id`.
pub fn db_path(dir: &Path, id: &str) -> PathBuf {
    debug_assert!(validate_id(id).is_ok(), "db_path requires a validated id");
    dir.join(format!("{id}.db"))
}

/// Open (creating if needed) an archive database with the schema applied.
pub fn open(dir: &Path, id: &str, export_root: &Path, tz: &str) -> Result<Connection, String> {
    validate_id(id).map_err(|_| "malformed-archive-id".to_string())?;
    std::fs::create_dir_all(dir).map_err(|_| "create-archives-dir".to_string())?;
    restrict(dir, 0o700);
    let path = db_path(dir, id);
    let conn = Connection::open(&path).map_err(|_| "open-db".to_string())?;
    restrict(&path, 0o600);
    conn.pragma_update(None, "journal_mode", "WAL").ok();
    conn.pragma_update(None, "foreign_keys", "ON").ok();
    conn.execute_batch(SCHEMA)
        .map_err(|e| format!("schema: {e}"))?;

    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    tx.execute(
        "INSERT OR REPLACE INTO meta(key, value) VALUES
            ('archive_id', ?1), ('export_root', ?2), ('timezone', ?3),
            ('schema_version', ?4), ('created_at', ?5)",
        rusqlite::params![
            id,
            export_root.to_string_lossy(),
            tz,
            SCHEMA_VERSION.to_string(),
            chrono::Utc::now().to_rfc3339(),
        ],
    )
    .map_err(|e| e.to_string())?;
    tx.commit().map_err(|e| e.to_string())?;
    Ok(conn)
}

/// chmod on unix; no-op elsewhere.
fn restrict(path: &Path, mode: u32) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode));
    }
    #[cfg(not(unix))]
    let _ = (path, mode);
}

/// Write one ingested thread.
pub fn store_thread(
    conn: &Connection,
    thread: &ThreadIngest,
    key: &str,
    bucket_month: &dyn Fn(i64) -> String,
    media_status: &dyn Fn(&str) -> String,
) -> Result<i64, String> {
    let tx = conn.unchecked_transaction().map_err(|e| e.to_string())?;
    tx.execute(
        "INSERT OR REPLACE INTO threads(key, title, raw_count, kept_count)
         VALUES (?1, ?2, ?3, ?4)",
        rusqlite::params![
            key,
            thread.title,
            thread.raw_count as i64,
            thread.kept.len() as i64
        ],
    )
    .map_err(|e| e.to_string())?;
    let thread_id = tx
        .query_row("SELECT id FROM threads WHERE key = ?1", [key], |r| {
            r.get::<_, i64>(0)
        })
        .map_err(|e| e.to_string())?;

    for m in &thread.kept {
        tx.execute(
            "INSERT INTO messages
                (thread_id, seq, file_no, idx_in_file, ts_ms, month, sender, content,
                 is_unsent, call_duration)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
            rusqlite::params![
                thread_id,
                m.seq,
                m.file_no as i64,
                m.idx_in_file as i64,
                m.ts_ms,
                bucket_month(m.ts_ms),
                m.sender,
                m.content,
                m.is_unsent as i64,
                m.call_duration,
            ],
        )
        .map_err(|e| e.to_string())?;
        let message_id = tx.last_insert_rowid();
        for r in &m.media {
            tx.execute(
                "INSERT INTO media(message_id, kind, media_index, uri, status)
                 VALUES (?1,?2,?3,?4,?5)",
                rusqlite::params![
                    message_id,
                    r.kind,
                    r.media_index as i64,
                    r.uri,
                    media_status(&r.uri),
                ],
            )
            .map_err(|e| e.to_string())?;
        }
        for (actor, reaction) in &m.reactions {
            tx.execute(
                "INSERT INTO reactions(message_id, actor, reaction) VALUES (?1,?2,?3)",
                rusqlite::params![message_id, actor, reaction],
            )
            .map_err(|e| e.to_string())?;
        }
    }

    for d in &thread.dropped {
        tx.execute(
            "INSERT INTO dropped(thread_id, seq, file_no, idx_in_file, ts_ms, kept_at)
             VALUES (?1,?2,?3,?4,?5,?6)",
            rusqlite::params![
                thread_id,
                d.seq,
                d.file_no as i64,
                d.idx_in_file as i64,
                d.ts_ms,
                d.kept_at,
            ],
        )
        .map_err(|e| e.to_string())?;
    }

    tx.commit().map_err(|e| e.to_string())?;
    Ok(thread_id)
}

/// Read back messages in display order: `(seq, file_no, idx_in_file, ts_ms)`.
pub fn ordered_messages(conn: &Connection, key: &str) -> Result<Vec<(i64, i64, i64, i64)>, String> {
    let mut st = conn
        .prepare(
            "SELECT m.seq, m.file_no, m.idx_in_file, m.ts_ms
             FROM messages m JOIN threads t ON t.id = m.thread_id
             WHERE t.key = ?1
             ORDER BY m.ts_ms ASC, m.seq DESC",
        )
        .map_err(|e| e.to_string())?;
    let rows = st
        .query_map([key], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

/// Delete an archive database and its WAL/SHM sidecars.
///
/// The id is validated first, so no caller-supplied path is ever touched.
pub fn delete_index(dir: &Path, id: &str) -> Result<(), IdError> {
    validate_id(id)?;
    let base = db_path(dir, id);
    let mut found = false;
    for suffix in ["", "-wal", "-shm"] {
        let p = PathBuf::from(format!("{}{suffix}", base.display()));
        match std::fs::remove_file(&p) {
            Ok(()) => found = true,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(IdError::NotFound),
        }
    }
    if found {
        Ok(())
    } else {
        Err(IdError::NotFound)
    }
}

/// Test/diagnostic helper: does an archive exist?
pub fn exists(dir: &Path, id: &str) -> bool {
    validate_id(id).is_ok() && db_path(dir, id).exists()
}

/// Meta value lookup.
pub fn meta(conn: &Connection, key: &str) -> Option<String> {
    conn.query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0))
        .optional()
        .ok()
        .flatten()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fts5_table_works_and_matches() {
        let d = tempfile::tempdir().unwrap();
        let conn = open(d.path(), &"a".repeat(32), Path::new("/tmp/x"), "UTC").unwrap();
        // messages.thread_id is a real FK, so the parent row must exist.
        conn.execute(
            "INSERT INTO threads(key, title, raw_count, kept_count) VALUES ('t','t',1,1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(thread_id, seq, file_no, idx_in_file, ts_ms, month, sender, content)
             VALUES (1, 0, 1, 0, 100, '1970-01', 'ann', 'hello distinctiveword world')",
            [],
        )
        .unwrap();
        // The AFTER INSERT trigger must have populated the FTS index.
        let hits: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'distinctiveword'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(hits, 1, "FTS5 MATCH must find the indexed row");
    }

    #[test]
    fn fts5_stays_in_sync_on_update_and_delete() {
        let d = tempfile::tempdir().unwrap();
        let conn = open(d.path(), &"b".repeat(32), Path::new("/tmp/x"), "UTC").unwrap();
        conn.execute(
            "INSERT INTO threads(key, title, raw_count, kept_count) VALUES ('t','t',1,1)",
            [],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO messages(thread_id, seq, file_no, idx_in_file, ts_ms, month, sender, content)
             VALUES (1, 0, 1, 0, 1, '1970-01', 'ann', 'firsttoken')",
            [],
        )
        .unwrap();
        conn.execute(
            "UPDATE messages SET content = 'secondtoken' WHERE id = 1",
            [],
        )
        .unwrap();
        let count = |t: &str| -> i64 {
            conn.query_row(
                &format!("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH '{t}'"),
                [],
                |r| r.get(0),
            )
            .unwrap()
        };
        assert_eq!(count("firsttoken"), 0, "old token must leave the index");
        assert_eq!(count("secondtoken"), 1, "new token must enter the index");
        conn.execute("DELETE FROM messages WHERE id = 1", [])
            .unwrap();
        assert_eq!(count("secondtoken"), 0, "delete must remove from the index");
    }

    #[test]
    fn rejects_malformed_ids() {
        for bad in [
            "",
            "short",
            &"A".repeat(32),
            &"g".repeat(32),
            &"a".repeat(31),
        ] {
            assert_eq!(
                validate_id(bad),
                Err(IdError::Malformed),
                "should reject {bad:?}"
            );
        }
        assert!(validate_id(&"0".repeat(32)).is_ok());
    }

    #[test]
    fn delete_removes_db_and_sidecars() {
        let d = tempfile::tempdir().unwrap();
        let id = "c".repeat(32);
        let conn = open(d.path(), &id, Path::new("/tmp/x"), "UTC").unwrap();
        conn.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")
            .unwrap();
        // force sidecars to exist
        conn.execute("CREATE TABLE t(x)", []).unwrap();
        drop(conn);
        let p = db_path(d.path(), &id);
        assert!(p.exists());
        assert!(delete_index(d.path(), &id).is_ok());
        assert!(!p.exists());
        assert!(!PathBuf::from(format!("{}-wal", p.display())).exists());
        assert!(!PathBuf::from(format!("{}-shm", p.display())).exists());
    }

    #[test]
    fn delete_rejects_bad_id_without_touching_disk() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(delete_index(d.path(), "../escape"), Err(IdError::Malformed));
        assert_eq!(delete_index(d.path(), "nope"), Err(IdError::Malformed));
    }
}
