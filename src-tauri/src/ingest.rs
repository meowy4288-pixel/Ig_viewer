//! Parsing + canonical ordering of one thread.
//!
//! Two contracts, both matching `fake-data/expected.json`:
//!
//! **Dedupe key** = `(timestamp_ms, sender_name_raw, content_raw, Vec<String> media_uris)`
//! with all values taken BEFORE `fix()`. The URI vector concatenates six kinds in a
//! fixed order -- photos, videos, audio_files, gifs, sticker, files -- each kind's
//! entries in on-disk order. It is a typed vector, never a joined string, so no
//! delimiter inside a URI can forge a boundary.
//!
//! **Order** = `(timestamp_ms ASC, seq DESC)`. `seq` is a per-thread counter over
//! RAW encounters, assigned to every entry before dedupe (including entries that
//! are later dropped). Because each `message_N.json` stores its messages
//! newest-first and files are read in ascending N, a higher `seq` at the same
//! timestamp means a later encounter -- which in a newest-first export was sent
//! earlier -- so it displays first.

use std::collections::HashSet;

use serde::Deserialize;

use crate::text::fix;

/// Six media kinds, in the exact order their URIs enter the dedupe key.
pub const MEDIA_KINDS: [&str; 6] = [
    "photos",
    "videos",
    "audio_files",
    "gifs",
    "sticker",
    "files",
];

#[derive(Debug, Deserialize)]
pub struct ThreadFile {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub participants: Vec<Participant>,
    #[serde(default)]
    pub messages: Vec<RawMessage>,
}

#[derive(Debug, Deserialize)]
pub struct Participant {
    pub name: String,
}

#[derive(Debug, Deserialize)]
pub struct MediaItem {
    pub uri: String,
}

/// One message exactly as it appears on disk (all text still mojibaked).
#[derive(Debug, Deserialize)]
pub struct RawMessage {
    #[serde(default)]
    pub sender_name: String,
    #[serde(default)]
    pub timestamp_ms: i64,
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default)]
    pub is_unsent: Option<bool>,
    #[serde(default)]
    pub call_duration: Option<i64>,
    #[serde(default)]
    pub photos: Option<Vec<MediaItem>>,
    #[serde(default)]
    pub videos: Option<Vec<MediaItem>>,
    #[serde(default)]
    pub audio_files: Option<Vec<MediaItem>>,
    #[serde(default)]
    pub gifs: Option<Vec<MediaItem>>,
    /// In Instagram's schema `sticker` is a single object, not an array.
    #[serde(default)]
    pub sticker: Option<MediaItem>,
    #[serde(default)]
    pub files: Option<Vec<MediaItem>>,
    #[serde(default)]
    pub reactions: Option<Vec<Reaction>>,
    #[serde(default)]
    pub share: Option<Share>,
}

#[derive(Debug, Deserialize)]
pub struct Reaction {
    #[serde(default)]
    pub actor: String,
    #[serde(default)]
    pub reaction: String,
}

#[derive(Debug, Deserialize)]
pub struct Share {
    #[serde(default)]
    pub link: String,
    #[serde(default)]
    pub share_text: String,
}

/// One media reference, with the kind and its index within that kind so two entries
/// on the same message stay distinguishable.
#[derive(Debug, Clone)]
pub struct MediaRef {
    pub kind: String,
    pub media_index: usize,
    /// Raw URI. Never passed through `fix()`.
    pub uri: String,
}

/// A message that survived dedupe, with its position recorded.
#[derive(Debug, Clone)]
pub struct KeptMessage {
    pub seq: i64,
    pub file_no: u32,
    pub idx_in_file: usize,
    pub ts_ms: i64,
    pub sender: String,
    pub content: Option<String>,
    pub is_unsent: bool,
    pub call_duration: Option<i64>,
    pub media: Vec<MediaRef>,
    pub reactions: Vec<(String, String)>,
    pub share: Option<(String, String)>,
}

/// A dropped copy plus the location of the copy that won.
#[derive(Debug, Clone)]
pub struct DroppedCopy {
    pub seq: i64,
    pub file_no: u32,
    pub idx_in_file: usize,
    pub ts_ms: i64,
    pub kept_at: String,
}

#[derive(Debug, Default)]
pub struct ThreadIngest {
    pub title: String,
    pub participants: Vec<String>,
    pub kept: Vec<KeptMessage>,
    pub dropped: Vec<DroppedCopy>,
    pub raw_count: usize,
}

/// Ordered URI vector for the dedupe key. Public so tests can assert it directly.
pub fn media_uris(m: &RawMessage) -> Vec<String> {
    let mut out = Vec::new();
    for kind in MEDIA_KINDS {
        match kind {
            "photos" => push_items(&mut out, m.photos.as_deref()),
            "videos" => push_items(&mut out, m.videos.as_deref()),
            "audio_files" => push_items(&mut out, m.audio_files.as_deref()),
            "gifs" => push_items(&mut out, m.gifs.as_deref()),
            "sticker" => {
                if let Some(s) = &m.sticker {
                    out.push(s.uri.clone());
                }
            }
            "files" => push_items(&mut out, m.files.as_deref()),
            _ => unreachable!("MEDIA_KINDS is exhaustive"),
        }
    }
    out
}

fn push_items(out: &mut Vec<String>, items: Option<&[MediaItem]>) {
    if let Some(items) = items {
        for i in items {
            out.push(i.uri.clone());
        }
    }
}

/// Every media reference with its kind and index, in key order.
pub fn media_refs(m: &RawMessage) -> Vec<MediaRef> {
    let mut out = Vec::new();
    for kind in MEDIA_KINDS {
        match kind {
            "photos" => push_refs(&mut out, kind, m.photos.as_deref()),
            "videos" => push_refs(&mut out, kind, m.videos.as_deref()),
            "audio_files" => push_refs(&mut out, kind, m.audio_files.as_deref()),
            "gifs" => push_refs(&mut out, kind, m.gifs.as_deref()),
            "sticker" => {
                if let Some(s) = &m.sticker {
                    out.push(MediaRef {
                        kind: kind.to_string(),
                        media_index: 0,
                        uri: s.uri.clone(),
                    });
                }
            }
            "files" => push_refs(&mut out, kind, m.files.as_deref()),
            _ => unreachable!("MEDIA_KINDS is exhaustive"),
        }
    }
    out
}

fn push_refs(out: &mut Vec<MediaRef>, kind: &str, items: Option<&[MediaItem]>) {
    if let Some(items) = items {
        for (i, it) in items.iter().enumerate() {
            out.push(MediaRef {
                kind: kind.to_string(),
                media_index: i,
                uri: it.uri.clone(),
            });
        }
    }
}

/// Parse one file's bytes into a `ThreadFile`.
pub fn parse_thread_file(bytes: &[u8]) -> Result<ThreadFile, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "invalid-utf8".to_string())?;
    serde_json::from_str(text).map_err(|_| "malformed-json".to_string())
}

/// Apply the canonical dedupe and display order to one thread.
///
/// `files` must already be in ascending file number; each entry is
/// `(file_no, parsed messages in on-disk order)`.
pub fn ingest_thread(
    title: &str,
    participants: &[String],
    files: &[(u32, Vec<RawMessage>)],
) -> ThreadIngest {
    let mut out = ThreadIngest {
        title: fix(title),
        participants: participants.iter().map(|p| fix(p)).collect(),
        ..Default::default()
    };

    let mut seen_earlier: HashSet<(i64, String, Option<String>, Vec<String>)> = HashSet::new();
    // Where each key was first kept, so a dropped copy can point at the winner.
    let mut kept_at: std::collections::HashMap<(i64, String, Option<String>, Vec<String>), String> =
        std::collections::HashMap::new();

    let mut seq: i64 = 0;
    for (file_no, messages) in files {
        // Keys introduced by THIS file, merged only after the file completes, so
        // identical messages inside one file are all kept.
        let mut here: HashSet<(i64, String, Option<String>, Vec<String>)> = HashSet::new();
        for (idx, m) in messages.iter().enumerate() {
            let key = (
                m.timestamp_ms,
                m.sender_name.clone(),
                m.content.clone(),
                media_uris(m),
            );
            out.raw_count += 1;
            if seen_earlier.contains(&key) {
                let kept_at = kept_at.get(&key).cloned().unwrap_or_default();
                out.dropped.push(DroppedCopy {
                    seq,
                    file_no: *file_no,
                    idx_in_file: idx,
                    ts_ms: m.timestamp_ms,
                    kept_at,
                });
                seq += 1;
                continue;
            }
            here.insert(key.clone());
            // First-encountered copy wins: do NOT overwrite an existing entry, or a
            // later same-file duplicate would move the recorded winner.
            kept_at
                .entry(key)
                .or_insert_with(|| format!("file{}:idx{}", file_no, idx));
            out.kept.push(KeptMessage {
                seq,
                file_no: *file_no,
                idx_in_file: idx,
                ts_ms: m.timestamp_ms,
                sender: fix(&m.sender_name),
                content: m.content.as_deref().map(fix),
                is_unsent: m.is_unsent.unwrap_or(false),
                call_duration: m.call_duration,
                media: media_refs(m),
                reactions: m
                    .reactions
                    .as_deref()
                    .unwrap_or(&[])
                    .iter()
                    .map(|r| (fix(&r.actor), fix(&r.reaction)))
                    .collect(),
                share: m
                    .share
                    .as_ref()
                    .map(|s| (s.link.clone(), fix(&s.share_text))),
            });
            seq += 1;
        }
        seen_earlier.extend(here);
    }

    // Display order: oldest first; ties break on higher seq first.
    out.kept
        .sort_by(|a, b| a.ts_ms.cmp(&b.ts_ms).then(b.seq.cmp(&a.seq)));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(ts: i64, sender: &str, content: Option<&str>) -> RawMessage {
        RawMessage {
            sender_name: sender.into(),
            timestamp_ms: ts,
            content: content.map(str::to_string),
            is_unsent: None,
            call_duration: None,
            photos: None,
            videos: None,
            audio_files: None,
            gifs: None,
            sticker: None,
            files: None,
            reactions: None,
            share: None,
        }
    }

    #[test]
    fn media_uris_uses_all_six_kinds_in_order() {
        let mut m = msg(1, "a", Some("c"));
        m.photos = Some(vec![MediaItem { uri: "p1".into() }]);
        m.videos = Some(vec![MediaItem { uri: "v1".into() }]);
        m.audio_files = Some(vec![MediaItem { uri: "a1".into() }]);
        m.gifs = Some(vec![MediaItem { uri: "g1".into() }]);
        m.sticker = Some(MediaItem { uri: "s1".into() });
        m.files = Some(vec![MediaItem { uri: "f1".into() }]);
        assert_eq!(media_uris(&m), vec!["p1", "v1", "a1", "g1", "s1", "f1"]);
    }

    #[test]
    fn same_file_duplicates_are_all_kept() {
        let files = vec![(1u32, vec![msg(10, "a", Some("x")), msg(10, "a", Some("x"))])];
        let t = ingest_thread("", &[], &files);
        assert_eq!(
            t.kept.len(),
            2,
            "identical messages in ONE file both survive"
        );
        assert!(t.dropped.is_empty());
    }

    #[test]
    fn cross_file_duplicate_is_dropped_keeping_lower_file() {
        let files = vec![
            (2u32, vec![msg(10, "a", Some("x"))]),
            (4u32, vec![msg(10, "a", Some("x"))]),
        ];
        let t = ingest_thread("", &[], &files);
        assert_eq!(t.kept.len(), 1);
        assert_eq!(t.dropped.len(), 1);
        assert_eq!(t.dropped[0].file_no, 4);
        assert_eq!(t.dropped[0].kept_at, "file2:idx0");
    }

    #[test]
    fn ties_display_higher_seq_first() {
        // File 1 stores newest-first, so idx0 is the later encounter.
        let files = vec![(
            1u32,
            vec![msg(5, "a", Some("first")), msg(5, "b", Some("second"))],
        )];
        let t = ingest_thread("", &[], &files);
        assert_eq!(t.kept.len(), 2);
        assert_eq!(t.kept[0].seq, 1, "higher seq displays first");
        assert_eq!(t.kept[0].content.as_deref(), Some("second"));
    }

    #[test]
    fn reactions_are_not_part_of_the_key() {
        let mut a = msg(10, "a", Some("x"));
        a.reactions = Some(vec![Reaction {
            actor: "z".into(),
            reaction: "❤️".into(),
        }]);
        let b = msg(10, "a", Some("x")); // no reactions
        let t = ingest_thread("", &[], &[(1, vec![a]), (2, vec![b])]);
        assert_eq!(
            t.kept.len(),
            1,
            "differing reactions must not split the key"
        );
        assert_eq!(t.dropped.len(), 1);
    }
}
