//! `igmedia:` custom protocol: serves archive media to the webview by opaque id.
//!
//! The webview NEVER receives a filesystem path or an export URI. It gets an
//! opaque id of the form `igmedia://media/<archive_id>/<media_row_id>`, and the
//! backend resolves that to bytes. Both components are validated: `archive_id` must
//! be 32 lowercase hex, and the export root comes from the archive's own `meta`
//! table rather than from the request, so a forged id cannot steer resolution
//! anywhere.
//!
//! Containment is the `media` module's job (component-wise symlink refusal,
//! `O_NOFOLLOW`, `fstat` re-verify). This layer adds the MIME allowlist, because
//! serving a correctly-located `.html` or `.svg` would still be a script-execution
//! primitive inside the app origin.
//!
//! Every response carries `X-Content-Type-Options: nosniff` and
//! `Cache-Control: no-store`.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use rusqlite::Connection;

use crate::db;
use crate::media;

/// MIME types the app is willing to render. Everything else is refused, including
/// `text/html` and `image/svg+xml`, both of which can execute script in this origin.
const ALLOWED_MIME: &[(&str, &str)] = &[
    ("png", "image/png"),
    ("jpg", "image/jpeg"),
    ("jpeg", "image/jpeg"),
    ("gif", "image/gif"),
    ("webp", "image/webp"),
    ("mp4", "video/mp4"),
    ("m4v", "video/mp4"),
    ("webm", "video/webm"),
    ("mov", "video/quicktime"),
    ("m4a", "audio/mp4"),
    ("mp3", "audio/mpeg"),
    ("aac", "audio/aac"),
    ("wav", "audio/wav"),
    ("ogg", "audio/ogg"),
    ("opus", "audio/ogg"),
];

/// Cap on a single media response. Media is streamed in ranges, so this bounds a
/// single range rather than the whole file.
pub const MAX_MEDIA_RANGE_BYTES: u64 = 32 * 1024 * 1024;

/// Why a request could not be served. Sanitised: no URI, no path, no id echo.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServeError {
    BadRequest,
    NotFound,
    /// The URI exists but resolves outside the export, through a symlink, or is
    /// missing. Deliberately one code for all of them so nothing is leaked.
    Unavailable,
    /// The file exists but its type is not renderable by the app.
    UnsupportedType,
    TooLarge,
}

impl ServeError {
    pub fn status(self) -> u16 {
        match self {
            Self::BadRequest => 400,
            Self::NotFound => 404,
            Self::Unavailable | Self::UnsupportedType => 403,
            Self::TooLarge => 413,
        }
    }
}

#[derive(Debug)]
pub struct MediaResponse {
    pub content_type: &'static str,
    pub body: Vec<u8>,
    /// Full file size, used for `Content-Range` and `Accept-Ranges`.
    pub total_len: u64,
    /// True when the body is a partial range rather than the whole file.
    pub partial: bool,
    pub range_start: u64,
}

/// MIME type for an export-relative URI, or `None` if not renderable.
pub fn mime_for(uri: &str) -> Option<&'static str> {
    let ext = uri.rsplit('.').next()?.to_ascii_lowercase();
    ALLOWED_MIME
        .iter()
        .find(|(e, _)| *e == ext)
        .map(|(_, m)| *m)
}

/// Parse a single byte range header. Returns `(start, end_inclusive)`.
///
/// Only one range is supported; multi-range and suffix-only forms are refused rather
/// than approximated.
pub fn parse_range(header: &str, total: u64) -> Option<(u64, u64)> {
    let spec = header.strip_prefix("bytes=")?.trim();
    if spec.contains(',') {
        return None; // multi-range: not supported, and partial support would be a lie
    }
    let (a, b) = spec.split_once('-')?;
    let (start, end) = match (a.is_empty(), b.is_empty()) {
        (true, true) => return None,
        // "bytes=-500": final 500 bytes
        (true, false) => {
            let n: u64 = b.parse().ok()?;
            if n == 0 {
                return None;
            }
            (total.saturating_sub(n), total - 1)
        }
        (false, true) => {
            let s: u64 = a.parse().ok()?;
            (s, total - 1)
        }
        (false, false) => {
            let s: u64 = a.parse().ok()?;
            let e: u64 = b.parse().ok()?;
            (s, e)
        }
    };
    if start >= total {
        return None;
    }
    Some((start, end.min(total - 1)))
}

/// Resolve `media_id` within `archive_id` and read bytes.
///
/// `range_header` is a raw `Range:` header value, if any.
pub fn serve(
    archives_dir: &Path,
    archive_id: &str,
    media_id: i64,
    range_header: Option<&str>,
) -> Result<MediaResponse, ServeError> {
    db::validate_id(archive_id).map_err(|_| ServeError::BadRequest)?;
    if media_id <= 0 {
        return Err(ServeError::BadRequest);
    }

    let dbfile = db::db_path(archives_dir, archive_id);
    let conn = Connection::open(&dbfile).map_err(|_| ServeError::NotFound)?;
    conn.pragma_update(None, "query_only", "ON").ok();

    // The export root comes from the archive's own metadata, never from the request.
    let root: String = db::meta(&conn, "export_root").ok_or(ServeError::NotFound)?;

    let uri: String = conn
        .query_row("SELECT uri FROM media WHERE id = ?1", [media_id], |r| {
            r.get(0)
        })
        .map_err(|_| ServeError::NotFound)?;

    let root_path = Path::new(&root);
    // Containment FIRST, then MIME. Both refuse, but checking containment first means
    // a traversal URI is caught by the check that actually protects the filesystem
    // rather than incidentally failing an extension test.
    //
    // Every containment failure collapses to `Unavailable` so a probe cannot
    // distinguish "missing" from "outside the export".
    let path = media::resolve(root_path, &uri).map_err(|_| ServeError::Unavailable)?;
    let content_type = mime_for(&uri).ok_or(ServeError::UnsupportedType)?;
    let total_len = std::fs::metadata(&path)
        .map_err(|_| ServeError::Unavailable)?
        .len();
    if total_len == 0 {
        return Err(ServeError::Unavailable);
    }

    let (start, end, partial) = match range_header {
        None => (0, total_len - 1, false),
        Some(h) => {
            let (s, e) = parse_range(h, total_len).ok_or(ServeError::BadRequest)?;
            (s, e, true)
        }
    };
    let len = end - start + 1;
    if len > MAX_MEDIA_RANGE_BYTES {
        return Err(ServeError::TooLarge);
    }

    let mut f = media::open(root_path, &uri).map_err(|_| ServeError::Unavailable)?;
    f.seek(SeekFrom::Start(start))
        .map_err(|_| ServeError::Unavailable)?;
    let mut body = vec![0u8; len as usize];
    f.read_exact(&mut body)
        .map_err(|_| ServeError::Unavailable)?;

    Ok(MediaResponse {
        content_type,
        body,
        total_len,
        partial,
        range_start: start,
    })
}

/// Parse a media URL into `(archive_id, media_id)`.
///
/// Accepts the path Tauri hands us for either spelling of the URL:
///   * `igmedia://media/<archive>/<id>` -- `media` lands in the HOST, so the path
///     the handler receives is `/<archive>/<id>` (no `media` segment). This is the
///     form `media_url()` generates.
///   * `igmedia:///media/<archive>/<id>` -- an explicit leading `media` segment.
///
/// An earlier version only accepted the second form, so EVERY request failed to
/// parse and no media ever loaded. Both are accepted now, and anything else -- extra
/// segments, a missing part, a non-numeric id, a non-positive id -- is rejected.
pub fn parse_media_url(path: &str) -> Result<(String, i64), ServeError> {
    let p = path.trim_start_matches('/');
    let mut it = p.split('/');
    // Tolerate an explicit leading "media" segment; it is optional because the
    // usual URL form carries it in the host instead.
    if it.clone().next() == Some("media") {
        it.next();
    }
    let archive = it.next().ok_or(ServeError::BadRequest)?;
    let id = it.next().ok_or(ServeError::BadRequest)?;
    if it.next().is_some() {
        return Err(ServeError::BadRequest);
    }
    // Reject negatives and zero explicitly: `-1` and `0` parse fine as i64 but are
    // never valid media ids.
    let n: i64 = id.parse().map_err(|_| ServeError::BadRequest)?;
    if n <= 0 {
        return Err(ServeError::BadRequest);
    }
    Ok((archive.to_string(), n))
}

/// Build the URL the webview uses. Opaque: no path, no filename.
pub fn media_url(archive_id: &str, media_id: i64) -> String {
    format!("igmedia://media/{archive_id}/{media_id}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mime_allowlist_rejects_script_types() {
        assert_eq!(mime_for("photos/a.png"), Some("image/png"));
        assert_eq!(mime_for("videos/a.MP4"), Some("video/mp4"));
        assert_eq!(mime_for("audio/a.m4a"), Some("audio/mp4"));
        // The hostile fixtures must never be served.
        assert_eq!(mime_for("photos/evil_55.html"), None);
        assert_eq!(mime_for("photos/script_55.svg"), None);
        assert_eq!(mime_for("files/a.pdf"), None);
        assert_eq!(mime_for("noextension"), None);
        assert_eq!(mime_for("a.php"), None);
    }

    #[test]
    fn range_parsing() {
        assert_eq!(parse_range("bytes=0-99", 1000), Some((0, 99)));
        assert_eq!(parse_range("bytes=500-", 1000), Some((500, 999)));
        assert_eq!(parse_range("bytes=-100", 1000), Some((900, 999)));
        // clamped to the file end
        assert_eq!(parse_range("bytes=900-5000", 1000), Some((900, 999)));
        // out of range / malformed / multi-range all refused
        assert_eq!(parse_range("bytes=1000-", 1000), None);
        assert_eq!(parse_range("bytes=abc-def", 1000), None);
        assert_eq!(parse_range("bytes=0-10,20-30", 1000), None);
        assert_eq!(parse_range("items=0-10", 1000), None);
        assert_eq!(parse_range("-", 1000), None);
    }

    #[test]
    fn url_parsing_accepts_the_form_the_handler_actually_receives() {
        // igmedia://media/<archive>/<id> puts "media" in the HOST, so Tauri hands
        // the handler a path with NO leading media segment. Regression: this form
        // used to be rejected, so no media ever loaded.
        let id = "0f5de2e832eb1cf747da4e20f647c0af";
        let url = media_url(id, 18);
        assert_eq!(url, format!("igmedia://media/{id}/18"));
        let path = format!("/{id}/18"); // what request.uri().path() yields
        assert_eq!(parse_media_url(&path), Ok((id.to_string(), 18)));

        // The explicit-segment form must keep working too.
        assert_eq!(
            parse_media_url(&format!("/media/{id}/18")),
            Ok((id.to_string(), 18))
        );
    }

    #[test]
    fn url_parsing_is_strict() {
        assert_eq!(
            parse_media_url("/media/abc123/42"),
            Ok(("abc123".to_string(), 42))
        );
        assert_eq!(
            parse_media_url(&format!("media/{}/7", "a".repeat(32))),
            Ok(("a".repeat(32), 7))
        );
        // extra segments, missing parts, wrong prefix, bad id
        assert!(parse_media_url("/media/abc/1/2").is_err());
        assert!(parse_media_url("/media/abc").is_err());
        assert!(parse_media_url("/other/abc/1").is_err());
        assert!(parse_media_url("/media/abc/notanumber").is_err());
        assert!(parse_media_url("/media/abc/-1").is_err());
    }

    #[test]
    fn generated_url_round_trips() {
        let id = "f".repeat(32);
        let url = media_url(&id, 99);
        assert_eq!(url, format!("igmedia://media/{id}/99"));
        let parsed = parse_media_url(url.trim_start_matches("igmedia://"));
        assert_eq!(parsed, Ok((id, 99)));
        // The URL must not leak anything about the file.
        assert!(!url.contains('.'));
        // igmedia://media/<32 hex>/<id> -> exactly 4 separators, all fixed by the route
        assert_eq!(url.matches('/').count(), 4);
    }
}
