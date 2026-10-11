//! Export scanner: finds threads and their `message_N.json` files.
//!
//! The layout is fixed and enumerated level by level -- never recursively:
//!
//!   <root>/your_instagram_activity/messages/inbox/<thread>/message_<N>.json
//!
//! Anything else in the export (`connections.json`, `settings.json`, `reels/`,
//! `exported_items/`, ...) is ignored without being opened. The export is treated
//! as strictly read-only.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// Maximum size of a single `message_N.json`. Enforced with a bounded read, not
/// just a metadata check.
///
/// Justification: the largest legitimate file in our fixture set is 8,779 bytes
/// (`mira_okonkwo_4821/message_8.json`) and the whole fixture export is 53,606
/// bytes, so 32 MiB leaves roughly 3800x headroom over real fixture content while
/// still bounding memory for a hostile or corrupt archive.
pub const MAX_JSON_BYTES: u64 = 32 * 1024 * 1024;

/// Sanitised reason a candidate file was not ingested. Deliberately coarse: no
/// message text, sender, title or path ever appears in a reason.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkipReason {
    /// Filename did not match `^message_<digits>.json$`.
    NotAMessageFile,
    /// The parsed number overflowed u32.
    FileNumberOverflow,
    /// Another file in the same thread parsed to the same number. The WHOLE group
    /// is rejected: picking a winner would make ingest depend on directory
    /// enumeration order, which is filesystem-dependent.
    DuplicateFileNumber,
    /// Entry was a symlink; symlinks are never followed.
    Symlink,
    /// File exceeded `MAX_JSON_BYTES`.
    TooLarge,
    /// Could not read the file.
    IoError,
    /// File was not valid UTF-8.
    InvalidUtf8,
    /// File was not valid JSON.
    MalformedJson,
    /// JSON parsed but did not match the expected schema.
    InvalidSchema,
    /// Thread directory could not be read.
    ThreadUnreadable,
}

impl SkipReason {
    pub fn code(self) -> &'static str {
        match self {
            Self::NotAMessageFile => "not-a-message-file",
            Self::FileNumberOverflow => "file-number-overflow",
            Self::DuplicateFileNumber => "duplicate-file-number",
            Self::Symlink => "symlink",
            Self::TooLarge => "too-large",
            Self::IoError => "io-error",
            Self::InvalidUtf8 => "invalid-utf8",
            Self::MalformedJson => "malformed-json",
            Self::InvalidSchema => "invalid-schema",
            Self::ThreadUnreadable => "thread-unreadable",
        }
    }
}

#[derive(Debug, Clone)]
pub struct ThreadScan {
    /// Directory name, used as the thread key.
    pub key: String,
    /// `message_N.json` paths in ascending N. Empty when every file was skipped.
    pub files: Vec<PathBuf>,
    /// `(reason, count)` for this thread only.
    pub skipped: Vec<(SkipReason, usize)>,
}

#[derive(Debug, Clone, Default)]
pub struct ScanResult {
    pub threads: Vec<ThreadScan>,
    /// `(reason, count)` across the whole scan.
    pub skipped: Vec<(SkipReason, usize)>,
}

impl ScanResult {
    pub fn total_skipped(&self) -> usize {
        self.skipped.iter().map(|(_, n)| n).sum()
    }
}

/// Extract `N` from a `message_N.json` filename, rejecting anything that is not
/// exactly `^message_([0-9]+)\.json$` (case-sensitive, no extra suffixes).
pub fn parse_file_number(name: &str) -> Result<u32, SkipReason> {
    let rest = name
        .strip_prefix("message_")
        .and_then(|r| r.strip_suffix(".json"))
        .ok_or(SkipReason::NotAMessageFile)?;
    if rest.is_empty() || !rest.bytes().all(|b| b.is_ascii_digit()) {
        return Err(SkipReason::NotAMessageFile);
    }
    rest.parse::<u32>()
        .map_err(|_| SkipReason::FileNumberOverflow)
}

/// Resolve the inbox, accepting either the export root or the `your_instagram_activity`
/// folder itself.
///
/// Instagram's own instructions tell users to point at the top of the download, but
/// people just as often select the folder that visibly contains `messages/`. Failing
/// silently in that case is confusing, so both are accepted and normalised to one.
///
/// `<root>/your_instagram_activity/messages/inbox`
fn inbox_dir(root: &Path) -> PathBuf {
    let inner = root.join("your_instagram_activity").join("messages").join("inbox");
    if inner.is_dir() {
        return inner;
    }
    // root may already BE the your_instagram_activity folder, or an export whose
    // folder is named differently.
    for candidate in [
        root.join("messages").join("inbox"),
        root.join("your_instagram_activity").join("messages").join("inbox"),
    ] {
        if candidate.is_dir() {
            return candidate;
        }
    }
    inner
}

/// True if the path is a symlink. Never follows it.
fn is_symlink(path: &Path) -> bool {
    fs::symlink_metadata(path)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
}

/// Read at most `MAX_JSON_BYTES` (+1 to detect overflow) from a file.
///
/// Returns `Err(TooLarge)` without buffering the whole file, so a hostile 10 GB
/// archive cannot exhaust memory.
pub fn read_bounded(path: &Path) -> Result<Vec<u8>, SkipReason> {
    use std::io::Read;
    let mut f = fs::File::open(path).map_err(|_| SkipReason::IoError)?;
    let mut buf = Vec::new();
    let cap = MAX_JSON_BYTES as usize + 1;
    (&mut f)
        .take(cap as u64)
        .read_to_end(&mut buf)
        .map_err(|_| SkipReason::IoError)?;
    if buf.len() as u64 > MAX_JSON_BYTES {
        return Err(SkipReason::TooLarge);
    }
    Ok(buf)
}

/// Scan an export root for threads and their message files.
///
/// Enumerates exactly four directory levels and never descends further, so
/// `reels/`, `exported_items/` and decoy JSON files are not opened.
pub fn scan(root: &Path) -> io::Result<ScanResult> {
    let mut result = ScanResult::default();
    let inbox = inbox_dir(root);
    if !inbox.is_dir() || is_symlink(&inbox) {
        return Ok(result);
    }

    let thread_dirs = match fs::read_dir(&inbox) {
        Ok(rd) => rd,
        Err(_) => return Ok(result),
    };

    for entry in thread_dirs.flatten() {
        let dir = entry.path();
        let key = match dir.file_name().and_then(|n| n.to_str()) {
            Some(k) if !k.is_empty() => k.to_string(),
            _ => continue,
        };
        if is_symlink(&dir) {
            bump(&mut result.skipped, SkipReason::Symlink);
            continue;
        }
        if !dir.is_dir() {
            continue;
        }

        let mut scan = ThreadScan {
            key,
            files: Vec::new(),
            skipped: Vec::new(),
        };

        let rd = match fs::read_dir(&dir) {
            Ok(rd) => rd,
            Err(_) => {
                bump(&mut scan.skipped, SkipReason::ThreadUnreadable);
                bump(&mut result.skipped, SkipReason::ThreadUnreadable);
                result.threads.push(scan);
                continue;
            }
        };

        // Group by parsed file number so a duplicate group can be rejected whole.
        let mut by_number: BTreeMap<u32, PathBuf> = BTreeMap::new();
        let mut duplicates: Vec<u32> = Vec::new();

        for e in rd.flatten() {
            let p = e.path();
            let name = match p.file_name().and_then(|n| n.to_str()) {
                Some(n) => n,
                None => continue,
            };
            match parse_file_number(name) {
                Ok(n) => {
                    if is_symlink(&p) {
                        bump(&mut scan.skipped, SkipReason::Symlink);
                        continue;
                    }
                    if !p.is_file() {
                        continue;
                    }
                    // Size is a cheap pre-filter only; read_bounded() is what
                    // actually enforces the cap without buffering the whole file.
                    match p.metadata() {
                        Ok(md) if md.len() > MAX_JSON_BYTES => {
                            bump(&mut scan.skipped, SkipReason::TooLarge);
                            continue;
                        }
                        Ok(_) => {}
                        Err(_) => {
                            bump(&mut scan.skipped, SkipReason::IoError);
                            continue;
                        }
                    }
                    if by_number.insert(n, p).is_some() {
                        duplicates.push(n);
                    }
                }
                Err(SkipReason::NotAMessageFile) => continue, // not ours; ignore
                Err(r) => bump(&mut scan.skipped, r),
            }
        }

        // Reject every member of a duplicated number group: choosing one would make
        // the result depend on directory enumeration order.
        for n in duplicates {
            if by_number.remove(&n).is_some() {
                bump(&mut scan.skipped, SkipReason::DuplicateFileNumber);
                bump(&mut result.skipped, SkipReason::DuplicateFileNumber);
            }
        }

        // BTreeMap iteration is ascending N, so message_10 follows message_9.
        scan.files = by_number.into_values().collect();

        for (reason, n) in &scan.skipped {
            for _ in 0..*n {
                bump(&mut result.skipped, *reason);
            }
        }
        // A directory with no matching filenames is not a thread at all.
        if scan.files.is_empty() && scan.skipped.is_empty() {
            continue;
        }
        result.threads.push(scan);
    }

    result.threads.sort_by(|a, b| a.key.cmp(&b.key));
    Ok(result)
}

fn bump(v: &mut Vec<(SkipReason, usize)>, r: SkipReason) {
    for e in v.iter_mut() {
        if e.0 == r {
            e.1 += 1;
            return;
        }
    }
    v.push((r, 1));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_valid_names() {
        assert_eq!(parse_file_number("message_1.json"), Ok(1));
        assert_eq!(parse_file_number("message_10.json"), Ok(10));
        assert_eq!(parse_file_number("message_0.json"), Ok(0));
    }

    #[test]
    fn rejects_invalid_names() {
        for n in [
            "message_.json",
            "message_x.json",
            "message_1.JSON",
            "Message_1.json",
            "message_1.json.bak",
            "message_-1.json",
            "message_1_2.json",
            "connections.json",
            "message_4294967296.json", // u32 overflow
        ] {
            assert!(parse_file_number(n).is_err(), "should reject {n}");
        }
        assert_eq!(
            parse_file_number("message_4294967296.json"),
            Err(SkipReason::FileNumberOverflow)
        );
    }
}
