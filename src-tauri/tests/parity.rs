//! Test 1: ord-based parity against fake-data/expected.json.
//!
//! Rust retains `(ts_ms, file_no, idx_in_file)` for every kept message. That tuple
//! maps to an oracle id via expected.json's `ord` field, and the ordered id
//! sequences must be identical for all four threads. expected.json is the oracle and
//! is never edited to make this pass.

mod common;

use common::ingest_util::{ingest_thread, scan_all};
use ig_viewer_lib::db;
use std::collections::{HashMap, HashSet};

const THREADS: [&str; 4] = [
    "mira_okonkwo_4821",
    "dev_team_7730",
    "sam_lee_9012",
    "ana_silva_5544",
];

/// Pull `order[].{id, ts_ms, ord.file, ord.index}` out of expected.json.
///
/// expected.json is 100 KB+ of nested JSON; rather than pull in serde_json as a
/// dev-dependency for one test, this walks the structure with a tiny scanner that
/// understands exactly the shape the generator emits.
fn oracle_order(path: &std::path::Path, thread: &str) -> Vec<(i64, i64, i64, String)> {
    let text = std::fs::read_to_string(path).expect("expected.json");
    let t = find_thread(&text, thread).expect("thread present in expected.json");

    let mut out = Vec::new();
    // Walk the "order" array of this thread object.
    let order_at = find_key(t, "\"order\"").expect("order key");
    let arr = &t[order_at..];
    let open = arr.find('[').expect("[");
    let body = &arr[open + 1..];
    for entry in split_objects(body) {
        let id = str_field(&entry, "\"id\"").expect("id");
        let ts: i64 = num_field(&entry, "\"ts_ms\"").expect("ts_ms");
        let ord = find_key(&entry, "\"ord\"").expect("ord");
        let file: i64 = num_field(&entry[ord..], "\"file\"").expect("file");
        let index: i64 = num_field(&entry[ord..], "\"index\"").expect("index");
        out.push((ts, file, index, id));
    }
    out
}

fn find_thread<'a>(text: &'a str, key: &str) -> Option<&'a str> {
    let needle = format!("\"key\": \"{key}\"");
    let at = text.find(&needle)?;
    // Walk back to the enclosing '{' of the thread object. Counting braces rather
    // than using rfind avoids landing on a nested object.
    let head = &text[..at];
    let mut depth = 0i32;
    for (i, c) in head.char_indices().rev() {
        match c {
            '}' => depth += 1,
            '{' => {
                if depth == 0 {
                    return Some(&text[i..]);
                }
                depth -= 1;
            }
            _ => {}
        }
    }
    None
}

fn find_key(hay: &str, key: &str) -> Option<usize> {
    hay.find(key)
}

fn split_objects(body: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut depth = 0usize;
    let mut start = 0usize;
    let mut in_str = false;
    let mut esc = false;
    for (i, c) in body.char_indices() {
        if esc {
            esc = false;
            continue;
        }
        match c {
            '\\' if in_str => esc = true,
            '"' => in_str = !in_str,
            '{' if !in_str => {
                if depth == 0 {
                    start = i;
                }
                depth += 1;
            }
            '}' if !in_str => {
                depth -= 1;
                if depth == 0 {
                    out.push(body[start..=i].to_string());
                    if body[i + 1..].trim_start().starts_with(']') {
                        break;
                    }
                }
            }
            _ => {}
        }
    }
    out
}

fn str_field(s: &str, key: &str) -> Option<String> {
    let at = s.find(key)? + key.len();
    let rest = &s[at..];
    let colon = rest.find(':')? + 1;
    let rest = rest[colon..].trim_start();
    let q = rest.find('"')? + 1;
    let tail = &rest[q..];
    let end = tail.find('"')?;
    Some(tail[..end].to_string())
}

fn num_field(s: &str, key: &str) -> Option<i64> {
    let at = s.find(key)? + key.len();
    let rest = &s[at..];
    let colon = rest.find(':')? + 1;
    let tail = rest[colon..].trim_start();
    let end = tail
        .find(|c: char| !c.is_ascii_digit() && c != '-')
        .unwrap_or(tail.len());
    tail[..end].parse().ok()
}

fn oracle_count(path: &std::path::Path, thread: &str, field: &str) -> usize {
    let text = std::fs::read_to_string(path).expect("expected.json");
    let t = find_thread(&text, thread).expect("thread");
    let at = find_key(t, field).expect("field") + field.len();
    let rest = &t[at..];
    let colon = rest.find(':').unwrap() + 1;
    rest[colon..]
        .trim_start()
        .split(|c: char| !c.is_ascii_digit())
        .next()
        .unwrap()
        .parse()
        .unwrap()
}

#[test]
fn ord_parity_all_four_threads() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let expected = common::expected_json();
    let scanned = scan_all(&root);
    assert_eq!(scanned.threads.len(), 4, "fixture has 4 threads");
    assert_eq!(scanned.total_skipped(), 0, "fixture is clean");

    for thread in THREADS {
        let oracle = oracle_order(&expected, thread);
        let got = ingest_thread(&root, thread);

        // The ord -> id map must be TOTAL and INJECTIVE before anything is compared.
        let mut map: HashMap<(i64, i64, i64), &str> = HashMap::new();
        let mut seen_ids: HashSet<&str> = HashSet::new();
        for (ts, file, idx, id) in &oracle {
            assert!(
                map.insert((*ts, *file, *idx), id.as_str()).is_none(),
                "{thread}: NOT INJECTIVE, ord ({ts},{file},{idx}) appears twice"
            );
            assert!(
                seen_ids.insert(id.as_str()),
                "{thread}: NOT INJECTIVE, id {id} twice"
            );
        }
        assert_eq!(map.len(), oracle.len(), "{thread}: NOT TOTAL");

        // Rust messages map to ords; none may be missing.
        let mut rust_ids = Vec::with_capacity(got.kept.len());
        for m in &got.kept {
            let key = (m.ts_ms, m.file_no as i64, m.idx_in_file as i64);
            match map.get(&key) {
                Some(id) => rust_ids.push((*id).to_string()),
                None => panic!(
                    "{thread}: Rust message has no oracle mapping: ts={} file={} idx={}",
                    key.0, key.1, key.2
                ),
            }
        }
        let oracle_ids: Vec<String> = oracle.iter().map(|(_, _, _, id)| id.clone()).collect();
        assert_eq!(
            rust_ids.len(),
            oracle_ids.len(),
            "{thread}: length differs (rust {} vs oracle {})",
            rust_ids.len(),
            oracle_ids.len()
        );
        for (i, (r, o)) in rust_ids.iter().zip(&oracle_ids).enumerate() {
            assert_eq!(r, o, "{thread}: first mismatch at index {i}");
        }
    }
}

#[test]
fn message_counts_match_the_oracle() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let expected = common::expected_json();
    for thread in THREADS {
        let got = ingest_thread(&root, thread);
        assert_eq!(
            got.kept.len(),
            oracle_count(&expected, thread, "\"message_count\""),
            "{thread}: kept count"
        );
        assert_eq!(
            got.raw_count,
            oracle_count(&expected, thread, "\"raw_count\""),
            "{thread}: raw count"
        );
        assert_eq!(
            got.raw_count - got.kept.len(),
            got.dropped.len(),
            "{thread}: raw - kept == dropped"
        );
    }
}

#[test]
fn drop_locations_match_the_oracle() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let expected = common::expected_json();
    let thread = "mira_okonkwo_4821";
    let got = ingest_thread(&root, thread);

    // Expected: m0148 file4:idx6 -> file2:idx0, six overlap copies, m0150 file6:idx0.
    let text = std::fs::read_to_string(&expected).unwrap();
    let t = find_thread(&text, thread).unwrap();
    let a = find_key(t, "\"dropped\"").unwrap();
    let dropped_json = &t[a..];
    let mut rust: Vec<(String, String, String)> = got
        .dropped
        .iter()
        .map(|d| {
            (
                d.kept_at.clone(),
                format!("file{}:idx{}", d.file_no, d.idx_in_file),
                String::new(),
            )
        })
        .collect();

    // Every Rust drop must appear in the oracle's dropped list.
    let oracle_pairs: Vec<String> = split_objects(dropped_json)
        .iter()
        .map(|e| {
            format!(
                "{} -> {}",
                str_field(e, "\"dropped\"").unwrap_or_default(),
                str_field(e, "\"kept\"").unwrap_or_default()
            )
        })
        .collect();
    for (kept_at, dropped_at, _) in &mut rust {
        *dropped_at = format!("{dropped_at} -> {kept_at}");
    }
    assert_eq!(rust.len(), oracle_pairs.len(), "drop count");
    for (i, pair) in rust.iter().enumerate() {
        assert_eq!(pair.1, oracle_pairs[i], "drop #{i}");
    }
}

#[test]
fn db_round_trip_preserves_order() {
    // Same data through SQLite: the stored order must equal the in-memory order.
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dbdir = tempfile::tempdir().unwrap();
    let id = "d".repeat(32);
    let conn = db::open(dbdir.path(), &id, &root, "Europe/Warsaw").unwrap();
    use chrono::TimeZone;
    let month_of = |ts: i64| {
        use chrono_tz::Tz;
        Tz::Europe__Warsaw
            .timestamp_millis_opt(ts)
            .unwrap()
            .format("%Y-%m")
            .to_string()
    };
    let status_of = |uri: &str| match ig_viewer_lib::media::resolve(&root, uri) {
        Ok(_) => "ok".to_string(),
        Err(e) => e.code().to_string(),
    };
    for thread in THREADS {
        let ing = ingest_thread(&root, thread);
        db::store_thread(&conn, &ing, thread, &month_of, &status_of).unwrap();
    }
    for thread in THREADS {
        let rows = db::ordered_messages(&conn, thread).unwrap();
        let ing = ingest_thread(&root, thread);
        assert_eq!(rows.len(), ing.kept.len(), "{thread}");
        for (row, m) in rows.iter().zip(&ing.kept) {
            assert_eq!(row.0, m.seq, "{thread}: seq");
            assert_eq!(row.1, m.file_no as i64, "{thread}: file_no");
            assert_eq!(row.2, m.idx_in_file as i64, "{thread}: idx");
            assert_eq!(row.3, m.ts_ms, "{thread}: ts");
        }
    }
    // Months were bucketed in Europe/Warsaw.
    let month: String = conn
        .query_row(
            "SELECT month FROM messages ORDER BY ts_ms DESC LIMIT 1",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(month, "2025-01", "last message is 2025-01 in Europe/Warsaw");
}
