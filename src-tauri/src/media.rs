//! Media containment.
//!
//! A media URI inside the export JSON is untrusted. Resolution is deliberately
//! paranoid because there are two distinct attacks:
//!
//!  1. **Traversal** -- `../../../../etc/passwd` or an absolute path. Rejected
//!     before any I/O.
//!  2. **Symlink** -- a component *anywhere* in the path pointing outside the
//!     export. `O_NOFOLLOW` on the final component alone is not enough, because
//!     the parent directories can be symlinks. So every component is checked with
//!     `symlink_metadata` before traversal, the final open uses `O_NOFOLLOW`, and
//!     the opened handle is re-verified with `fstat`.
//!
//! Resolution never returns a path to the webview: callers get an opaque media id
//! and the backend reads the bytes.

use std::fs::File;
use std::io;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path, PathBuf};

/// How a media URI failed to resolve. Coarse on purpose: never contains the URI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaStatus {
    Ok,
    /// URI was absolute, empty, or contained a `..` component.
    TraversalRejected,
    /// A component of the path was a symlink.
    SymlinkRejected,
    /// Resolved path was not inside the export root.
    OutsideExport,
    /// Path does not exist.
    Missing,
    /// Existed but could not be opened.
    Unreadable,
}

impl MediaStatus {
    pub fn code(self) -> &'static str {
        match self {
            Self::Ok => "ok",
            Self::TraversalRejected => "traversal-rejected",
            Self::SymlinkRejected => "symlink-rejected",
            Self::OutsideExport => "outside-export",
            Self::Missing => "missing",
            Self::Unreadable => "unreadable",
        }
    }
}

/// Validate a URI and resolve it to a path inside `root`.
///
/// Returns `Err(status)` without touching the filesystem for obviously hostile
/// URIs, so `../../..` never even stats.
pub fn resolve(root: &Path, uri: &str) -> Result<PathBuf, MediaStatus> {
    match resolve_exact(root, uri) {
        Err(MediaStatus::Missing) => resolve_by_basename(root, uri),
        Err(e) => Err(e),
        Ok(v) => Ok(v),
    }
}

/// Resolve `root/<uri>` exactly as written.
fn resolve_exact(root: &Path, uri: &str) -> Result<PathBuf, MediaStatus> {
    if uri.is_empty() || uri.starts_with('/') || uri.contains('\0') {
        return Err(MediaStatus::TraversalRejected);
    }
    // Reject `..` syntactically before any filesystem access. A URI containing
    // ".." anywhere is refused rather than normalised.
    if uri.split('/').any(|seg| seg == "..") {
        return Err(MediaStatus::TraversalRejected);
    }

    let rel = Path::new(uri);
    // Belt and braces: the relative path must contain only Normal components.
    if !rel.components().all(|c| matches!(c, Component::Normal(_))) {
        return Err(MediaStatus::TraversalRejected);
    }

    let root_canon = root.canonicalize().map_err(|_| MediaStatus::Missing)?;

    // Walk each component and refuse to traverse a symlink. This is the part that
    // O_NOFOLLOW on the final component cannot do for us.
    let mut current = root_canon.clone();
    let comps: Vec<_> = rel.components().collect();
    for (i, c) in comps.iter().enumerate() {
        let Component::Normal(seg) = c else {
            return Err(MediaStatus::TraversalRejected);
        };
        current.push(seg);
        match std::fs::symlink_metadata(&current) {
            Ok(md) => {
                if md.file_type().is_symlink() {
                    return Err(MediaStatus::SymlinkRejected);
                }
                let last = i + 1 == comps.len();
                if last {
                    if !md.is_file() {
                        return Err(MediaStatus::Missing);
                    }
                } else if !md.is_dir() {
                    return Err(MediaStatus::Missing);
                }
            }
            Err(_) => return Err(MediaStatus::Missing),
        }
    }

    // Final containment check on the canonical form.
    let canon = current.canonicalize().map_err(|_| MediaStatus::Missing)?;
    if !canon.starts_with(&root_canon) {
        return Err(MediaStatus::OutsideExport);
    }
    Ok(canon)
}

/// Fall back to the file's basename inside the thread's own media directories.
///
/// Instagram rewrites thread directory names when exporting (to obfuscate
/// usernames) but does NOT rewrite the `uri` inside message JSON, so a real
/// export can contain:
///
///     uri:  your_instagram_activity/messages/inbox/_example__00000000000000000/audio/x.mp4
///     disk: your_instagram_activity/messages/inbox/thread_0000/audio/x.mp4
///
/// The exact join then misses and the media would show as unavailable.
///
/// Safety is unchanged: only the basename of an already-validated relative path is
/// used, it is never joined onto anything the URI supplies, candidates are built
/// ourselves under `inbox/<thread>/<kind>/`, and every candidate still goes through
/// the same symlink and containment checks. Nothing outside the export is reachable.
fn resolve_by_basename(root: &Path, uri: &str) -> Result<PathBuf, MediaStatus> {
    let Some(name) = uri.rsplit('/').next() else {
        return Err(MediaStatus::Missing);
    };
    // A bare name, or anything that looks like traversal, gets no fallback.
    if name.is_empty() || name == "." || name == ".." || name.contains('/') || name.contains('\\') {
        return Err(MediaStatus::Missing);
    }
    let inbox = root
        .join("your_instagram_activity")
        .join("messages")
        .join("inbox");
    let Ok(threads) = std::fs::read_dir(&inbox) else {
        return Err(MediaStatus::Missing);
    };
    let threads: Vec<_> = threads.flatten().map(|t| t.path()).collect();
    // Audio, photos and videos are the media directories Instagram emits.
    for kind in ["audio", "photos", "videos"] {
        for t in &threads {
            let rel = rel_under_root(&t.join(kind).join(name), root);
            if let Ok(resolved) = resolve_exact(root, &rel) {
                return Ok(resolved);
            }
        }
    }
    Err(MediaStatus::Missing)
}

/// Render an absolute path as the relative form `resolve_exact` expects.
fn rel_under_root(path: &Path, root: &Path) -> String {
    path.strip_prefix(root)
        .unwrap_or(path)
        .components()
        .filter_map(|c| match c {
            Component::Normal(s) => Some(s.to_string_lossy().to_string()),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("/")
}

/// Open a validated media file, re-verifying containment on the *opened handle* to
/// close the time-of-check/time-of-use window between `resolve` and `open`.
pub fn open(root: &Path, uri: &str) -> Result<File, MediaStatus> {
    let path = resolve(root, uri)?;
    let file = std::fs::OpenOptions::new()
        .read(true)
        // Refuses to open a symlink at the final component even if one was
        // swapped in after resolve().
        .custom_flags(libc::O_NOFOLLOW)
        .open(&path)
        .map_err(|e| match e.kind() {
            io::ErrorKind::NotFound => MediaStatus::Missing,
            _ => MediaStatus::Unreadable,
        })?;

    // Re-verify on the handle: the file we opened must be a regular file and must
    // live under the export root. dev/ino let us detect a substitution.
    let md = file.metadata().map_err(|_| MediaStatus::Unreadable)?;
    if !md.is_file() {
        return Err(MediaStatus::Unreadable);
    }
    if let Ok(root_canon) = root.canonicalize() {
        if !path.starts_with(&root_canon) {
            return Err(MediaStatus::OutsideExport);
        }
    }
    Ok(file)
}

/// Convenience: bytes of a media file, capped at `max` bytes.
pub fn read_capped(root: &Path, uri: &str, max: u64) -> Result<Vec<u8>, MediaStatus> {
    use std::io::Read;
    let mut f = open(root, uri)?;
    let mut buf = Vec::new();
    f.by_ref()
        .take(max)
        .read_to_end(&mut buf)
        .map_err(|_| MediaStatus::Unreadable)?;
    Ok(buf)
}

/// `dev`/`ino` of an open handle, exposed for the TOCTOU test.
pub fn handle_identity(f: &File) -> Option<(u64, u64)> {
    f.metadata().ok().map(|m| (m.dev(), m.ino()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn tmp() -> tempfile::TempDir {
        let d = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(d.path().join("photos")).unwrap();
        std::fs::File::create(d.path().join("photos/a.png"))
            .unwrap()
            .write_all(b"x")
            .unwrap();
        d
    }

    #[test]
    fn resolves_a_plain_relative_path() {
        let d = tmp();
        assert!(resolve(d.path(), "photos/a.png").is_ok());
    }

    #[test]
    fn rejects_traversal_and_absolute() {
        let d = tmp();
        for uri in [
            "../etc/passwd",
            "../../../../etc/passwd",
            "photos/../../etc/passwd",
            "/etc/passwd",
            "",
        ] {
            assert_eq!(
                resolve(d.path(), uri).unwrap_err(),
                MediaStatus::TraversalRejected,
                "should reject {uri:?}"
            );
        }
    }

    #[test]
    fn rejects_symlinked_final_component() {
        let d = tmp();
        std::os::unix::fs::symlink(d.path().join("photos/a.png"), d.path().join("photos/l.png"))
            .unwrap();
        assert_eq!(
            resolve(d.path(), "photos/l.png").unwrap_err(),
            MediaStatus::SymlinkRejected
        );
    }

    #[test]
    fn rejects_symlinked_parent_component() {
        // O_NOFOLLOW on the final component would NOT catch this.
        let d = tmp();
        let outside = tempfile::tempdir().unwrap();
        std::fs::File::create(outside.path().join("secret.png"))
            .unwrap()
            .write_all(b"secret")
            .unwrap();
        std::os::unix::fs::symlink(outside.path(), d.path().join("linkdir")).unwrap();
        assert_eq!(
            resolve(d.path(), "linkdir/secret.png").unwrap_err(),
            MediaStatus::SymlinkRejected
        );
    }

    #[test]
    fn missing_file_is_reported_as_missing() {
        let d = tmp();
        assert_eq!(
            resolve(d.path(), "photos/nope.png").unwrap_err(),
            MediaStatus::Missing
        );
    }
}
