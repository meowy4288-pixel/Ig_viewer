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

/// Thread list for the sidebar: key, title, message count, last timestamp.
pub fn list_threads(conn: &Connection) -> Result<Vec<ThreadSummary>, String> {
    let mut st = conn
        .prepare(
            "SELECT t.key, COALESCE(t.title,''), t.kept_count,
                    COALESCE(MAX(m.ts_ms), 0)
             FROM threads t LEFT JOIN messages m ON m.thread_id = t.id
             GROUP BY t.id ORDER BY MAX(m.ts_ms) DESC",
        )
        .map_err(|e| e.to_string())?;
    let rows = st
        .query_map([], |r| {
            Ok(ThreadSummary {
                key: r.get(0)?,
                title: r.get(1)?,
                message_count: r.get::<_, i64>(2)?,
                last_ts_ms: r.get::<_, i64>(3)?,
            })
        })
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

#[derive(Debug, Clone)]
pub struct ThreadSummary {
    pub key: String,
    pub title: String,
    pub message_count: i64,
    pub last_ts_ms: i64,
}

/// One thread's participants (already repaired).
pub fn thread_participants(conn: &Connection, key: &str) -> Vec<String> {
    // Participants are not stored separately; recover distinct senders in order.
    let Ok(mut st) = conn.prepare(
        "SELECT DISTINCT m.sender FROM messages m JOIN threads t ON t.id = m.thread_id
         WHERE t.key = ?1 ORDER BY m.sender",
    ) else {
        return Vec::new();
    };
    st.query_map([key], |r| r.get::<_, String>(0))
        .map(|it| it.flatten().collect())
        .unwrap_or_default()
}

/// Month keys for a thread with their message ranges.
pub fn thread_months(conn: &Connection, key: &str) -> Result<Vec<MonthBucket>, String> {
    let mut st = conn
        .prepare(
            "SELECT m.month, COUNT(*), MIN(m.ts_ms), MAX(m.ts_ms)
             FROM messages m JOIN threads t ON t.id = m.thread_id
             WHERE t.key = ?1 GROUP BY m.month ORDER BY m.month",
        )
        .map_err(|e| e.to_string())?;
    let rows = st
        .query_map([key], |r| {
            Ok(MonthBucket {
                key: r.get(0)?,
                count: r.get::<_, i64>(1)?,
                first_ts_ms: r.get(2)?,
                last_ts_ms: r.get(3)?,
            })
        })
        .map_err(|e| e.to_string())?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())
}

#[derive(Debug, Clone)]
pub struct MonthBucket {
    pub key: String,
    pub count: i64,
    pub first_ts_ms: i64,
    pub last_ts_ms: i64,
}

/// A message as the UI needs it: repaired text plus OPAQUE media ids.
#[derive(Debug, Clone)]
pub struct UiMessage {
    pub id: i64,
    pub seq: i64,
    pub ts_ms: i64,
    pub month: String,
    pub sender: String,
    pub content: Option<String>,
    pub is_unsent: bool,
    pub call_duration: Option<i64>,
    pub media: Vec<UiMedia>,
    pub reactions: Vec<(String, String)>,
}

/// Media reference carrying an opaque id and a render hint, never a path.
#[derive(Debug, Clone)]
pub struct UiMedia {
    pub media_id: i64,
    pub kind: String,
    pub available: bool,
    pub content_type: Option<&'static str>,
}

/// Messages of one month in display order: (ts_ms ASC, seq DESC).
pub fn month_messages(conn: &Connection, key: &str, month: &str) -> Result<Vec<UiMessage>, String> {
    let mut st = conn
        .prepare(
            "SELECT m.id, m.seq, m.ts_ms, m.month, m.sender, m.content, m.is_unsent,
                    m.call_duration
             FROM messages m JOIN threads t ON t.id = m.thread_id
             WHERE t.key = ?1 AND m.month = ?2
             ORDER BY m.ts_ms ASC, m.seq DESC",
        )
        .map_err(|e| e.to_string())?;
    let mut base = st
        .query_map([key, month], |r| {
            Ok(UiMessage {
                id: r.get(0)?,
                seq: r.get(1)?,
                ts_ms: r.get(2)?,
                month: r.get(3)?,
                sender: r.get(4)?,
                content: r.get(5)?,
                is_unsent: r.get::<_, i64>(6)? != 0,
                call_duration: r.get(7)?,
                media: Vec::new(),
                reactions: Vec::new(),
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<UiMessage>, _>>()
        .map_err(|e| e.to_string())?;
    drop(st);

    let mut by_id: std::collections::HashMap<i64, usize> = std::collections::HashMap::new();
    for (i, m) in base.iter().enumerate() {
        by_id.insert(m.id, i);
    }

    if let Ok(mut ms) = conn.prepare(
        "SELECT m.message_id, m.id, m.kind, m.status
         FROM media m JOIN messages msg ON msg.id = m.message_id
         JOIN threads t ON t.id = msg.thread_id WHERE t.key = ?1",
    ) {
        let list = ms
            .query_map([key], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, i64>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, String>(3)?,
                ))
            })
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        for (msg_id, media_id, kind, status) in list {
            if let Some(i) = by_id.get(&msg_id) {
                base[*i].media.push(UiMedia {
                    media_id,
                    kind,
                    available: status == "ok",
                    content_type: None,
                });
            }
        }
    }
    if let Ok(mut rs) = conn.prepare(
        "SELECT r.message_id, r.actor, r.reaction
         FROM reactions r JOIN messages m ON m.id = r.message_id
         JOIN threads t ON t.id = m.thread_id WHERE t.key = ?1",
    ) {
        let list = rs
            .query_map([key], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                ))
            })
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        for (msg_id, actor, reaction) in list {
            if let Some(i) = by_id.get(&msg_id) {
                base[*i].reactions.push((actor, reaction));
            }
        }
    }
    Ok(base)
}

/// Month key containing `ts_ms`, using the archive's stored timezone.
pub fn month_of_ts(conn: &Connection, ts_ms: i64) -> Option<String> {
    // Resolve via the stored month column: pick the bucket whose range covers it.
    let mut st = conn
        .prepare(
            "SELECT DISTINCT month FROM messages WHERE ts_ms <= ?1
             ORDER BY ts_ms DESC LIMIT 1",
        )
        .ok()?;
    st.query_row([ts_ms], |r| r.get(0)).ok()
}

/// The archive's stored timezone name.
pub fn timezone(conn: &Connection) -> Option<String> {
    meta(conn, "timezone")
}

/// One search hit: which message, who sent it, when, and a snippet around the match.
#[derive(Debug, Clone)]
pub struct SearchHit {
    pub id: i64,
    pub ts_ms: i64,
    pub sender: String,
    /// Snippet with FTS5 `<b>` markers around the matched terms.
    pub snippet: String,
}

/// Full-text search returning sender + snippet, chronological, capped.
///
/// `snippet()` highlights matches with the column's own term list, so a
/// multi-word query highlights every term that actually matched. Messages with empty
/// content (media-only or unsent) are skipped rather than returned blank.
pub fn search(conn: &Connection, query: &str, limit: i64) -> Result<Vec<SearchHit>, String> {
    if query.trim().is_empty() {
        return Ok(Vec::new());
    }
    let mut st = conn
        .prepare(
            "SELECT m.id, m.ts_ms, m.sender,
                    snippet(messages_fts, 0, '[', ']', '\u{2026}', 12)
             FROM messages_fts f
             JOIN messages m ON m.id = f.rowid
             WHERE messages_fts MATCH ?1 AND m.content IS NOT NULL AND m.content <> ''
             ORDER BY m.ts_ms ASC LIMIT ?2",
        )
        .map_err(|e| e.to_string())?;
    let rows = st
        .query_map(rusqlite::params![query, limit], |r| {
            Ok(SearchHit {
                id: r.get(0)?,
                ts_ms: r.get(1)?,
                sender: r.get(2)?,
                snippet: r.get::<_, Option<String>>(3)?.unwrap_or_default(),
            })
        })
        .map_err(|e| e.to_string())?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    Ok(rows)
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
