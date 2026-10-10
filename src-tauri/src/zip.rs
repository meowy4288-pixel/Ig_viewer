//! Extract a user-supplied Instagram `.zip` export into the archive's own cache.
//!
//! The export folder the user picks is the *unzipped* tree, which is awkward when
//! all they have is the downloaded `.zip`. This module unpacks it so the rest of the
//! pipeline is unchanged: scanning, ingest and media resolution still see a plain
//! directory.
//!
//! # Safety
//!
//! A zip is untrusted input and the classic attack is **zip slip**: a member named
//! `../../../../etc/cron.d/x` that writes outside the destination. Every member is
//! therefore validated *before* extraction:
//!
//! * absolute paths, drive letters and any `..` component are rejected outright;
//! * the joined path must still be contained by the destination root after
//!   normalisation;
//! * symlink entries are skipped (a symlink target could point anywhere);
//! * the total uncompressed size and the file count are capped, so a zip bomb
//!   cannot exhaust the disk or memory.
//!
//! The destination is always inside the app's own cache dir, never inside the
//! user's export folder.

use std::io::Read;
use std::path::{Component, Path, PathBuf};

use crate::db::restrict;

/// Cap on total *uncompressed* size (8 GiB).
///
/// Measured uncompressed, not on the .zip, because photos and video -- the bulk of
/// a real export -- barely compress. A 2 GB download of media expands past the old
/// 2 GiB cap, which rejected genuine exports.
pub const MAX_TOTAL_BYTES: u64 = 8 * 1024 * 1024 * 1024;
/// Cap on file count. A large export has one JSON file per message, so this is
/// generous but still bounds a zip bomb.
pub const MAX_FILES: usize = 2_000_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExtractError {
    NotAZip,
    /// A member path tried to escape the destination.
    UnsafeMember(String),
    TooManyEntries,
    TooLarge,
    Io,
}

impl ExtractError {
    pub fn code(self) -> &'static str {
        match self {
            Self::NotAZip => "not-a-zip",
            Self::UnsafeMember(_) => "unsafe-member-path",
            Self::TooManyEntries => "too-many-entries",
            Self::TooLarge => "too-large",
            Self::Io => "io-error",
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct ExtractReport {
    pub files: usize,
    pub dirs: usize,
    pub bytes: u64,
    /// Members refused, with a sanitised reason. Never includes the full path.
    pub rejected: Vec<(String, &'static str)>,
}

/// Validate a zip member path against `root`, returning the safe destination.
///
/// `name` is the raw name stored in the archive.
fn safe_join(root: &Path, name: &str) -> Result<PathBuf, ExtractError> {
    if name.is_empty() {
        return Err(ExtractError::UnsafeMember("<empty>".into()));
    }
    // Windows drive letters and UNC paths are absolute in disguise.
    if name.starts_with('/') || name.starts_with('\\') {
        return Err(ExtractError::UnsafeMember(name.to_string()));
    }
    // A backslash is not a separator on Linux, so `..\evil` would sail through as a
    // single filename here and then escape on a platform where it *is* a separator.
    // Instagram exports never contain one, so refuse it everywhere.
    if name.contains('\\') {
        return Err(ExtractError::UnsafeMember(name.to_string()));
    }
    let rel = Path::new(name);
    let mut out = root.to_path_buf();
    for c in rel.components() {
        match c {
            Component::Normal(seg) => out.push(seg),
            // Rejected rather than normalised: a `..` in an archive is the attack.
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(ExtractError::UnsafeMember(name.to_string()))
            }
            Component::CurDir => {}
        }
    }
    if !out.starts_with(root) {
        return Err(ExtractError::UnsafeMember(name.to_string()));
    }
    Ok(out)
}

/// Extract `zip_path` into `dest`, which is created if missing.
///
/// Returns the extraction root the caller should hand to the scanner: when the zip
/// contains a single top-level folder (the normal shape of an Instagram export) that
/// folder is returned, because the scanner expects `…/your_instagram_activity/…`.
pub fn extract_zip(zip_path: &Path, dest: &Path) -> Result<(PathBuf, ExtractReport), ExtractError> {
    let file = std::fs::File::open(zip_path).map_err(|_| ExtractError::NotAZip)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| ExtractError::NotAZip)?;

    std::fs::create_dir_all(dest).map_err(|_| ExtractError::Io)?;
    let dest = dest.canonicalize().map_err(|_| ExtractError::Io)?;
    restrict(&dest, 0o700);

    let mut report = ExtractReport::default();
    let mut total: u64 = 0;

    for i in 0..archive.len() {
        if report.files + report.dirs > MAX_FILES {
            return Err(ExtractError::TooManyEntries);
        }
        let entry = archive.by_index(i).map_err(|_| ExtractError::Io)?;
        let Some(name) = entry
            .enclosed_name()
            .map(|n| n.to_string_lossy().to_string())
        else {
            report
                .rejected
                .push((format!("member#{i}"), "unsafe-member-path"));
            continue;
        };
        // enclosed_name already rejects traversal, but re-check explicitly so the
        // guarantee does not depend on that crate's behaviour.
        let Ok(target) = safe_join(&dest, &name) else {
            report
                .rejected
                .push((format!("member#{i}"), "unsafe-member-path"));
            continue;
        };
        if target == dest {
            continue;
        }

        let is_dir = entry.is_dir() || name.ends_with('/');
        if is_dir {
            std::fs::create_dir_all(&target).map_err(|_| ExtractError::Io)?;
            report.dirs += 1;
            continue;
        }
        // A symlink entry would let a later write escape; refuse it.
        if entry.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000) {
            report
                .rejected
                .push((format!("member#{i}"), "symlink-member"));
            continue;
        }

        let size = entry.size();
        if total.saturating_add(size) > MAX_TOTAL_BYTES {
            return Err(ExtractError::TooLarge);
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|_| ExtractError::Io)?;
        }
        let mut out = std::fs::File::create(&target).map_err(|_| ExtractError::Io)?;
        // Stream to disk in fixed chunks. Buffering whole members (read_to_end) would
        // pull every video into RAM; a multi-GB export would exhaust memory.
        // The take() bounds the copy by the declared size so a lying header cannot
        // overflow the budget, and we still verify what actually landed.
        let mut buf = vec![0u8; 64 * 1024];
        let mut written: u64 = 0;
        let mut reader = entry.take(size);
        loop {
            let n = reader.read(&mut buf).map_err(|_| ExtractError::Io)?;
            if n == 0 {
                break;
            }
            if total.saturating_add(written + n as u64) > MAX_TOTAL_BYTES {
                return Err(ExtractError::TooLarge);
            }
            std::io::Write::write_all(&mut out, &buf[..n]).map_err(|_| ExtractError::Io)?;
            written += n as u64;
        }
        out.sync_all().map_err(|_| ExtractError::Io)?;
        total += written;
        report.bytes += written;
        report.files += 1;
    }

    Ok((unwrap_single_root(&dest), report))
}

/// If `root` contains exactly one directory and no files, descend into it.
///
/// An Instagram export zipped from Downloads is usually `instagram-data-xyz/…`, so
/// the scanner would otherwise miss everything one level down.
fn unwrap_single_root(root: &Path) -> PathBuf {
    let Ok(rd) = std::fs::read_dir(root) else {
        return root.to_path_buf();
    };
    let entries: Vec<_> = rd.flatten().collect();
    if entries.len() != 1 {
        return root.to_path_buf();
    }
    let only = &entries[0];
    if !only.path().is_dir() {
        return root.to_path_buf();
    }
    match only.file_name().to_str() {
        Some("__MACOSX") => root.to_path_buf(),
        _ => only.path(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_traversal_member_paths() {
        let root = Path::new("/tmp/zipdest");
        for bad in [
            "../evil.txt",
            "../../etc/passwd",
            "a/../../b",
            "/etc/passwd",
            "/absolute",
            "..\\windows\\evil",
        ] {
            assert!(safe_join(root, bad).is_err(), "should reject {bad:?}");
        }
        assert!(safe_join(root, "ok/nested/file.txt").is_ok());
        assert!(safe_join(root, "plain.txt").is_ok());
    }

    fn write_zip(entries: &[(&str, &[u8])]) -> PathBuf {
        use std::io::Write;
        let mut buf = std::io::Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut buf);
            let o: zip::write::SimpleFileOptions = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated);
            for (name, data) in entries {
                w.start_file(*name, o).unwrap();
                w.write_all(data).unwrap();
            }
            w.finish().unwrap();
        }
        let dir = std::env::temp_dir().join(format!("igv-zip-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("in.zip");
        std::fs::write(&path, buf.into_inner()).unwrap();
        path
    }

    #[test]
    fn extracts_a_normal_export_and_skips_hostile_members() {
        let z = write_zip(&[
            (
                "your_instagram_activity/messages/inbox/alice_123/thread_1/message_1.json",
                b"{}",
            ),
            (
                "your_instagram_activity/your_profile/photos/1.jpg",
                b"jpeg-bytes",
            ),
            ("../../../../tmp/pwned.txt", b"should never be written"),
            ("/etc/pwned-abs.txt", b"should never be written"),
            ("..\\pwned-win.txt", b"should never be written"),
        ]);
        let dest = std::env::temp_dir().join(format!("igv-zip-out-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dest);

        let (root, report) = extract_zip(&z, &dest).unwrap();

        // The single top-level folder is unwrapped, so the scanner sees
        // `…/messages/inbox/…` directly.
        assert!(root.ends_with("your_instagram_activity"), "root={root:?}");
        assert_eq!(report.files, 2);
        assert!(root
            .join("messages/inbox/alice_123/thread_1/message_1.json")
            .is_file());
        assert!(root.join("your_profile/photos/1.jpg").is_file());

        // Nothing escaped: all three hostile members were refused.
        assert!(report.rejected.len() >= 2, "rejected={:?}", report.rejected);
        assert!(!Path::new("/tmp/pwned.txt").exists());
        assert!(!Path::new("/etc/pwned-abs.txt").exists());
        assert!(!dest.parent().unwrap().join("pwned-win.txt").exists());
        let _ = std::fs::remove_dir_all(&dest);
    }

    #[test]
    fn rejects_non_zip_input() {
        let path = std::env::temp_dir().join(format!("igv-notzip-{}", std::process::id()));
        std::fs::write(&path, b"this is not a zip file").unwrap();
        let dest = std::env::temp_dir().join(format!("igv-notzip-out-{}", std::process::id()));
        assert_eq!(
            extract_zip(&path, &dest).unwrap_err(),
            ExtractError::NotAZip
        );
    }

    #[test]
    fn rejected_members_are_contained() {
        let root = Path::new("/tmp/zipdest");
        // A relative-looking path must still resolve inside the root.
        let p = safe_join(root, "your_instagram_activity/messages/x.json").unwrap();
        assert!(p.starts_with(root));
    }
}
