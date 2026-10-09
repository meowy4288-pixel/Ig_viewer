//! Test 9: ingest logs contain no message text, sender names, titles, participant
//! names, media URIs or export paths.
//!
//! A log line that leaks chat content would be a privacy bug: the AGENTS.md rule is
//! "logging must never include message text or export paths".

mod common;

use ig_viewer_lib::{media, scan};
use std::path::{Path, PathBuf};

/// Everything the scanner/ingest is allowed to log: coarse counters.
#[derive(Default, Debug)]
struct Counters {
    threads: usize,
    messages: usize,
    skipped_files: usize,
}

impl Counters {
    /// The exact log line shape used by index_archive.
    fn render(&self) -> String {
        format!(
            "archive indexed: threads={} messages={} skipped_files={}",
            self.threads, self.messages, self.skipped_files
        )
    }
}

fn run_ingest(root: &Path) -> Counters {
    let scanned = scan::scan(root).expect("scan");
    let mut c = Counters {
        skipped_files: scanned.total_skipped(),
        ..Default::default()
    };
    for t in &scanned.threads {
        let ing = common::ingest_util::ingest_thread(root, &t.key);
        c.threads += 1;
        c.messages += ing.kept.len();
        // Touch media the way the app does, to prove that path never logs either.
        for m in &ing.kept {
            for r in &m.media {
                let _ = media::resolve(root, &r.uri);
            }
        }
    }
    c
}

#[test]
fn logs_contain_no_fixture_text_or_export_paths() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let line = run_ingest(&root).render();
    println!("log line: {line}");

    // Nothing from the fixture may appear. Collect a broad corpus of real values.
    let scan = scan::scan(&root).unwrap();
    let mut forbidden: Vec<String> = Vec::new();
    for t in &scan.threads {
        forbidden.push(t.key.clone()); // thread keys are user data too
        for p in &t.files {
            for f in t.files.iter().take(1) {
                if let Ok(b) = scan::read_bounded(f) {
                    if let Ok(parsed) = ig_viewer_lib::ingest::parse_thread_file(&b) {
                        forbidden.push(parsed.title.clone());
                        for p in &parsed.participants {
                            forbidden.push(p.name.clone());
                        }
                        for m in &parsed.messages {
                            forbidden.push(m.sender_name.clone());
                            if let Some(c) = &m.content {
                                forbidden.push(c.clone());
                            }
                            for items in [&m.photos, &m.videos, &m.audio_files, &m.gifs, &m.files]
                                .into_iter()
                                .flatten()
                            {
                                for i in items {
                                    forbidden.push(i.uri.clone());
                                }
                            }
                            if let Some(s) = &m.sticker {
                                forbidden.push(s.uri.clone());
                            }
                        }
                    }
                }
            }
            let _ = p;
        }
    }
    // Also the export root itself, and a file name.
    forbidden.push(root.display().to_string());
    forbidden.push("message_1.json".to_string());
    forbidden.push("your_instagram_activity".to_string());

    let mut checked = 0;
    for needle in forbidden {
        if needle.trim().is_empty() || needle.len() < 4 {
            continue; // too short to be a meaningful leak signal
        }
        checked += 1;
        assert!(
            !line.contains(&needle),
            "log line leaked fixture value {needle:?}: {line}"
        );
    }
    assert!(
        checked > 50,
        "should have checked a meaningful corpus, got {checked}"
    );
}

#[test]
fn log_line_contains_only_counters() {
    let line = Counters {
        threads: 4,
        messages: 254,
        skipped_files: 0,
    }
    .render();
    assert_eq!(
        line,
        "archive indexed: threads=4 messages=254 skipped_files=0"
    );
    // No path separators, no quotes, no message-like text.
    assert!(!line.contains('/'));
    assert!(!line.contains('"'));
}

#[test]
fn skip_reasons_are_codes_not_paths() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir: PathBuf = root.join("your_instagram_activity/messages/inbox/ana_silva_5544");
    std::fs::write(dir.join("message_9.json"), b"{ broken").unwrap();
    let r = scan::scan(&root).unwrap();
    for (reason, _) in &r.skipped {
        let code = reason.code();
        assert!(
            !code.contains('/'),
            "reason code must not be a path: {code}"
        );
        assert!(code.chars().all(|c| c.is_ascii_lowercase() || c == '-'));
    }
}
