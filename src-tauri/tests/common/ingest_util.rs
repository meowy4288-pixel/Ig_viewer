//! Ingest one thread from a temp copy of the fixture export and apply the canonical
//! rules, without touching a database. Kept here so both the parity test and the
//! ordering/dedupe tests exercise exactly the same path the app uses.

use std::path::Path;

use ig_viewer_lib::{ingest, scan};

/// All threads from `root`, in scan order.
pub fn scan_all(root: &Path) -> scan::ScanResult {
    scan::scan(root).expect("scan")
}

/// Parse and ingest a single thread directory.
pub fn ingest_thread(root: &Path, thread_key: &str) -> ingest::ThreadIngest {
    let inbox = root
        .join("your_instagram_activity/messages/inbox")
        .join(thread_key);
    let scan = scan::scan(root).expect("scan");
    let ts = scan
        .threads
        .iter()
        .find(|t| t.key == thread_key)
        .unwrap_or_else(|| panic!("thread {thread_key} not scanned"));

    let mut files: Vec<(u32, Vec<ingest::RawMessage>)> = Vec::new();
    let mut title = String::new();
    let mut participants: Vec<String> = Vec::new();
    let mut paths = ts.files.clone();
    paths.sort_by_key(|p| {
        std::fs::canonicalize(p)
            .ok()
            .and_then(|c| {
                std::fs::read_dir(c.parent().map(|d| d.to_path_buf()).unwrap_or_default()).ok()
            })
            .map(|_| ())
            .unwrap_or(());
        p.file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| scan::parse_file_number(n).ok())
            .unwrap_or(u32::MAX)
    });

    for p in &paths {
        let bytes = scan::read_bounded(p).expect("bounded read");
        let parsed = match ingest::parse_thread_file(&bytes) {
            Ok(p) => p,
            Err(_) => continue,
        };
        if title.is_empty() {
            title = parsed.title.clone();
            participants = parsed.participants.iter().map(|p| p.name.clone()).collect();
        }
        let no = p
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|n| scan::parse_file_number(n).ok())
            .unwrap_or(0);
        files.push((no, parsed.messages));
    }
    files.sort_by_key(|(n, _)| *n);
    let _ = inbox;
    ingest::ingest_thread(&title, &participants, &files)
}
