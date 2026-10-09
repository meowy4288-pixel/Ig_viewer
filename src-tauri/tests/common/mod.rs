//! Test helpers: temp copies of the fake export, never real Instagram data.
//!
//! This module is compiled into every test binary, and each binary uses a subset of
//! it, so unused helpers are expected rather than a bug.
#![allow(dead_code)]

pub mod ingest_util;

use std::path::{Path, PathBuf};

/// Repo root: the crate lives at `<repo>/src-tauri`.
pub fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("src-tauri has a parent")
        .to_path_buf()
}

/// `fake-data/export`, the fixture export root.
pub fn fixture_export() -> PathBuf {
    repo_root().join("fake-data/export")
}

/// `fake-data/expected.json`.
pub fn expected_json() -> PathBuf {
    repo_root().join("fake-data/expected.json")
}

/// Copy the fixture export into a temp dir so a test can mutate it freely.
///
/// Real exports are never touched: this is always a copy of generated fake data.
pub fn temp_export_copy() -> tempfile::TempDir {
    let dir = tempfile::tempdir().expect("tempdir");
    copy_tree(&fixture_export(), &dir.path().join("export"));
    dir
}

fn copy_tree(src: &Path, dst: &Path) {
    std::fs::create_dir_all(dst).expect("mkdir");
    for e in std::fs::read_dir(src).expect("read_dir").flatten() {
        let p = e.path();
        let name = e.file_name();
        let target = dst.join(&name);
        if p.is_dir() {
            copy_tree(&p, &target);
        } else {
            std::fs::copy(&p, &target).expect("copy");
        }
    }
}

/// Recursive digest of every file's bytes, keyed by relative path.
///
/// Used to prove an ingest run did not modify the export.
pub fn tree_digest(root: &Path) -> Vec<(String, Vec<u8>)> {
    let mut out = Vec::new();
    walk(root, root, &mut out);
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

fn walk(root: &Path, dir: &Path, out: &mut Vec<(String, Vec<u8>)>) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            walk(root, &p, out);
        } else {
            let rel = p.strip_prefix(root).unwrap().to_string_lossy().to_string();
            out.push((rel, std::fs::read(&p).unwrap_or_default()));
        }
    }
}

/// Directory structure only: every entry's relative path and whether it is a dir.
/// Catches files added or removed during ingest, which a byte digest alone would.
pub fn tree_structure(root: &Path) -> Vec<(String, bool)> {
    let mut out = Vec::new();
    walk_structure(root, root, &mut out);
    out.sort();
    out
}

fn walk_structure(root: &Path, dir: &Path, out: &mut Vec<(String, bool)>) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    for e in rd.flatten() {
        let p = e.path();
        let rel = p.strip_prefix(root).unwrap().to_string_lossy().to_string();
        if p.is_dir() {
            out.push((rel, true));
            walk_structure(root, &p, out);
        } else {
            out.push((rel, false));
        }
    }
}

/// Minimal JSON string-array reader for expected.json (no serde_json dependency in
/// tests beyond what the crate already has). Returns every `"key": "value"` string
/// found anywhere in the file, which is what the fix() test needs.
pub fn all_json_strings(path: &Path) -> Vec<String> {
    let text = std::fs::read_to_string(path).expect("read expected.json");
    let mut out = Vec::new();
    let bytes: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == '"' {
            let start = i + 1;
            let mut j = start;
            let mut buf = String::new();
            while j < bytes.len() && bytes[j] != '"' {
                if bytes[j] == '\\' && j + 1 < bytes.len() {
                    let n = bytes[j + 1];
                    match n {
                        'u' => {
                            let hex: String =
                                bytes[j + 2..(j + 6).min(bytes.len())].iter().collect();
                            if let Ok(cp) = u32::from_str_radix(&hex, 16) {
                                if let Some(c) = char::from_u32(cp) {
                                    buf.push(c);
                                }
                                j += 6;
                                continue;
                            }
                        }
                        'n' => buf.push('\n'),
                        't' => buf.push('\t'),
                        'r' => buf.push('\r'),
                        '\\' => buf.push('\\'),
                        '"' => buf.push('"'),
                        '/' => buf.push('/'),
                        other => buf.push(other),
                    }
                    j += 2;
                    continue;
                }
                buf.push(bytes[j]);
                j += 1;
            }
            if j < bytes.len() {
                out.push(buf);
                i = j + 1;
                continue;
            }
        }
        i += 1;
    }
    out
}
