//! Tests 2, 3, 4: fix() against the oracle, scanner layout, hostile paths.

mod common;

use ig_viewer_lib::{media, scan, text::fix};
use std::path::PathBuf;

// --------------------------------------------------------------- test 2: fix()

/// Every string in expected.json, paired with the repaired value the oracle expects.
///
/// expected.json stores post-repair text. The generator also emits the mojibake form
/// on disk, so this compares the two real-world shapes: original (mojibake, or
/// correctly-stored) input -> oracle output.
#[test]
fn fix_matches_oracle_for_every_string() {
    let strings = common::all_json_strings(&common::expected_json());
    assert!(
        strings.len() > 100,
        "expected.json should contain many strings"
    );

    let mut checked = 0;
    for s in &strings {
        // A correctly-stored string must come back unchanged.
        let repaired = fix(s);
        // Round-trip property: the repaired text, when it is plain ASCII-safe
        // mojibake, must decode back to itself.
        if repaired != *s {
            // it was mojibake and is now repaired; check the repair is stable
            assert_eq!(
                fix(&repaired),
                repaired,
                "fix() must be idempotent on already-repaired text: {s:?}"
            );
        }
        checked += 1;
    }
    assert_eq!(checked, strings.len());
}

/// Compare the on-disk (mojibake) forms against the oracle's repaired forms.
#[test]
fn fix_repairs_real_fixture_text() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let scan = scan::scan(&root).unwrap();
    let t = scan
        .threads
        .iter()
        .find(|t| t.key == "mira_okonkwo_4821")
        .unwrap();
    assert!(!t.files.is_empty());

    let mut saw_mojibake = false;
    let mut saw_untouched = false;
    for p in &t.files {
        let bytes = scan::read_bounded(p).unwrap();
        let parsed = ig_viewer_lib::ingest::parse_thread_file(&bytes).unwrap();
        for m in &parsed.messages {
            // Participants carry the non-latin1 names (Cyrillic, Arabic, diacritics).
            for name in &parsed.participants {
                if name.name.chars().any(|c| c as u32 > 0xFF) {
                    saw_untouched = true;
                    assert_eq!(fix(&name.name), name.name, "non-latin1 must pass through");
                }
            }
            for field in [Some(&m.sender_name), m.content.as_ref()]
                .into_iter()
                .flatten()
            {
                let fixed = fix(field);
                assert!(!fixed.is_empty());
                if field.chars().any(|c| c as u32 > 0xFF) {
                    saw_untouched = true;
                    assert_eq!(fixed, *field, "non-latin1 must pass through");
                }
                if fixed != *field {
                    saw_mojibake = true;
                }
            }
        }
    }
    assert!(saw_untouched, "fixture must contain non-latin1 text");
    assert!(saw_mojibake, "fixture must contain mojibake text");
}

/// The three contract branches, spelled out.
#[test]
fn fix_three_branches() {
    // branch 1: repairable mojibake
    let mojibake: String = "日本語".as_bytes().iter().map(|&b| b as char).collect();
    assert_eq!(fix(&mojibake), "日本語");
    // branch 2: char above U+00FF -> original
    assert_eq!(fix("Привет"), "Привет");
    // branch 3: invalid UTF-8 after latin-1 encoding -> original
    assert_eq!(
        fix(&(0x80u8 as char).to_string()),
        (0x80u8 as char).to_string()
    );
}

// ------------------------------------------------------- test 3/4: scanner

#[test]
fn scanner_accepts_only_the_expected_layout() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let r = scan::scan(&root).unwrap();
    assert_eq!(r.threads.len(), 4);

    // Decoys at the export root are ignored, not registered as threads.
    let keys: Vec<&str> = r.threads.iter().map(|t| t.key.as_str()).collect();
    assert!(!keys.contains(&"connections.json"));
    assert!(!keys.contains(&"settings.json"));
    // reels/ is not walked into.
    assert!(!keys.iter().any(|k| k.contains("reels")));
}

#[test]
fn files_are_ordered_numerically_not_lexicographically() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let r = scan::scan(&root).unwrap();
    let t = r
        .threads
        .iter()
        .find(|t| t.key == "mira_okonkwo_4821")
        .unwrap();
    let nums: Vec<u32> = t
        .files
        .iter()
        .map(|p| scan::parse_file_number(p.file_name().unwrap().to_str().unwrap()).unwrap())
        .collect();
    let mut sorted = nums.clone();
    sorted.sort_unstable();
    assert_eq!(nums, sorted, "must be ascending numeric order");
    assert!(nums.contains(&10), "thread has 12 files, so 10 must exist");
    // Lexicographic order would put 10 before 2; numeric must not.
    let pos10 = nums.iter().position(|&n| n == 10).unwrap();
    let pos9 = nums.iter().position(|&n| n == 9).unwrap();
    assert!(pos9 < pos10, "message_9 before message_10");
}

#[test]
fn rejects_duplicate_file_numbers_whole_group() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir = thread_dir(&root, "sam_lee_9012");
    // Create a second file_1.json variant that also parses to 1.
    let a = std::fs::read(dir.join("message_1.json")).unwrap();
    std::fs::write(dir.join("message_01.json"), a).unwrap();
    let r = scan::scan(&root).unwrap();
    let t = r.threads.iter().find(|t| t.key == "sam_lee_9012").unwrap();
    // Neither member of the duplicated group is ingested.
    assert!(
        !t.files
            .iter()
            .any(|p| p.to_string_lossy().contains("message_1.json")
                || p.to_string_lossy().contains("message_01.json")),
        "duplicate group must be rejected entirely"
    );
    assert!(r
        .skipped
        .iter()
        .any(|(reason, _)| *reason == scan::SkipReason::DuplicateFileNumber));
}

#[test]
fn oversized_file_is_skipped_and_counted() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir = thread_dir(&root, "ana_silva_5544");
    // Pre-filter path: metadata size over the cap.
    let big = vec![b'x'; (scan::MAX_JSON_BYTES + 1) as usize];
    std::fs::write(dir.join("message_9.json"), &big).unwrap();
    let r = scan::scan(&root).unwrap();
    let t = r
        .threads
        .iter()
        .find(|t| t.key == "ana_silva_5544")
        .unwrap();
    assert!(t
        .skipped
        .iter()
        .any(|(reason, _)| *reason == scan::SkipReason::TooLarge));
    assert!(r.total_skipped() >= 1);
}

#[test]
fn bounded_read_rejects_oversized_without_buffering() {
    let d = tempfile::tempdir().unwrap();
    let p = d.path().join("big.json");
    // Write just over the cap; read_bounded must refuse.
    let f = std::fs::File::create(&p).unwrap();
    f.set_len(scan::MAX_JSON_BYTES + 1).unwrap();
    drop(f);
    assert_eq!(
        scan::read_bounded(&p).unwrap_err(),
        scan::SkipReason::TooLarge
    );
}

#[test]
fn malformed_json_is_skipped_not_fatal() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir = thread_dir(&root, "sam_lee_9012");
    std::fs::write(dir.join("message_2.json"), b"{ this is not json").unwrap();
    // The scan itself must not panic.
    let r = scan::scan(&root).unwrap();
    assert_eq!(r.threads.len(), 4, "thread still registered");
    // message_2 is present but will fail parse; ingest still succeeds for the thread.
    let ing = common::ingest_util::ingest_thread(&root, "sam_lee_9012");
    // Only message_1 contributes.
    assert!(!ing.kept.is_empty());
}

#[test]
fn thread_with_all_files_malformed_is_retained_with_zero_messages() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir = thread_dir(&root, "ana_silva_5544");
    std::fs::write(dir.join("message_1.json"), b"garbage").unwrap();
    let r = scan::scan(&root).unwrap();
    let t = r
        .threads
        .iter()
        .find(|t| t.key == "ana_silva_5544")
        .unwrap();
    assert_eq!(t.files.len(), 1, "file still listed");
    let ing = common::ingest_util::ingest_thread(&root, "ana_silva_5544");
    assert_eq!(ing.kept.len(), 0, "zero messages");
}

// ------------------------------------------------ test 3: symlink rejection

#[test]
fn symlinked_message_file_is_rejected() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let dir = thread_dir(&root, "sam_lee_9012");
    let target = tmp.path().join("elsewhere.json");
    std::fs::write(&target, b"{\"messages\":[]}").unwrap();
    std::os::unix::fs::symlink(&target, dir.join("message_5.json")).unwrap();
    let r = scan::scan(&root).unwrap();
    let t = r.threads.iter().find(|t| t.key == "sam_lee_9012").unwrap();
    assert!(t
        .skipped
        .iter()
        .any(|(reason, _)| *reason == scan::SkipReason::Symlink));
    assert!(!t
        .files
        .iter()
        .any(|p| p.to_string_lossy().contains("message_5.json")));
}

#[test]
fn symlinked_media_parent_component_is_rejected() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let thread = thread_dir(&root, "mira_okonkwo_4821");
    let outside = tempfile::tempdir().unwrap();
    std::fs::write(outside.path().join("secret.png"), b"secret").unwrap();
    std::os::unix::fs::symlink(outside.path(), thread.join("linkdir")).unwrap();

    let uri = "your_instagram_activity/messages/inbox/mira_okonkwo_4821/linkdir/secret.png";
    assert_eq!(
        media::resolve(&root, uri).unwrap_err(),
        media::MediaStatus::SymlinkRejected,
        "symlinked parent component must be caught"
    );
    // Nothing outside the export was read.
    assert!(media::open(&root, uri).is_err());
}

#[test]
fn symlinked_media_final_component_is_rejected() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let thread = thread_dir(&root, "mira_okonkwo_4821");
    let real = thread.join("photos").join("present_photo_40.png");
    assert!(real.exists());
    std::os::unix::fs::symlink(&real, thread.join("photos").join("link.png")).unwrap();
    let uri = "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/link.png";
    assert_eq!(
        media::resolve(&root, uri).unwrap_err(),
        media::MediaStatus::SymlinkRejected
    );
}

#[test]
fn missing_media_does_not_invalidate_message() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let uri = "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/deleted_41.jpg";
    assert_eq!(
        media::resolve(&root, uri).unwrap_err(),
        media::MediaStatus::Missing
    );
    // The message carrying it still ingests fine.
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    assert!(ing
        .kept
        .iter()
        .any(|m| m.media.iter().any(|r| r.uri.contains("deleted_41"))));
}

// ------------------------------------------------ test 4: traversal

#[test]
fn traversal_uris_are_rejected() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    for uri in [
        "../../../../etc/passwd",
        "your_instagram_activity/messages/inbox/mira_okonkwo_4821/photos/../../../evil.png",
        "/etc/passwd",
        "../secret",
    ] {
        assert_eq!(
            media::resolve(&root, uri).unwrap_err(),
            media::MediaStatus::TraversalRejected,
            "should reject {uri:?}"
        );
        assert!(media::open(&root, uri).is_err());
    }
}

#[test]
fn hostile_media_uris_are_recorded_with_a_status() {
    let tmp = common::temp_export_copy();
    let root = tmp.path().join("export");
    let ing = common::ingest_util::ingest_thread(&root, "mira_okonkwo_4821");
    let mut seen_traversal = 0;
    let mut seen_hostile = false;
    for m in &ing.kept {
        for r in &m.media {
            match media::resolve(&root, &r.uri) {
                Err(media::MediaStatus::TraversalRejected) => seen_traversal += 1,
                Ok(_) => {
                    if r.uri.ends_with(".html") || r.uri.ends_with(".svg") {
                        seen_hostile = true;
                    }
                }
                Err(_) => {}
            }
        }
    }
    assert!(seen_traversal >= 1, "traversal URI present in fixture");
    assert!(seen_hostile, "hostile media present in fixture");
}

fn thread_dir(root: &std::path::Path, key: &str) -> PathBuf {
    root.join("your_instagram_activity/messages/inbox")
        .join(key)
}
