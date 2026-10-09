Project: offline desktop viewer for Instagram DM exports (JSON), built from ig_chat_viewer.html and ig_chat_rebuild.py.
Hard rules:
- Fully offline: no network requests, no telemetry, no auto-update, no CDN assets. The app must work with networking disabled.
- Never read or write outside the folder the user picks. Treat every path/URI inside the JSON as untrusted.
- Chat content is untrusted: never insert it with innerHTML; only textContent or DOM APIs. Only http(s) links are clickable.
- Minimal Tauri permissions: no shell, no broad fs scope. Explain every permission you add.
- Never commit real chat data. Tests use only generated fake data.
- Fake data lives only in fake-data/, generated with a fixed seed so runs are reproducible. Nothing real ever goes there.
- Logging must never include message text or export paths.
- Dedupe rule (canonical): a message read from file N is dropped only if its key
  (timestamp_ms, sender_name, content, media URIs) already appeared in a file with a
  LOWER file number. Within a single file identical messages are all kept. The
  first-encountered copy wins. Reactions are NOT part of the key.
- Dedupe media key: a typed Vec<String>, never a joined string, containing every URI
  from all six kinds in this fixed order -- photos, videos, audio_files, gifs,
  sticker, files -- each kind's entries in on-disk order. An `or` chain
  (photos or videos or ...) silently drops every kind after the first.
- In Instagram's schema `sticker` is a single {"uri": ...} object, not an array.
- Display order: (timestamp_ms ASC, seq DESC), where seq is the per-thread RAW
  encounter counter assigned before dedupe, so dropped messages consume a seq.
- JSON ingest cap: 32 MiB per message_N.json, enforced by a bounded read.
  Largest fixture file is 8,779 B; whole fixture export is 53,606 B.
- Archive indexes live in the app data dir (<app_data_dir>/archives/<32 hex>.db,
  dir 0700, file 0600). One DB per archive. The export folder is READ-ONLY: never
  write into it.
- The webview never receives a filesystem path: media is referenced by opaque id.
- delete_index accepts only a 32-char lowercase hex archive id; it never takes a path.
- Media is served to the webview over the custom `igmedia:` scheme as
  igmedia://media/<archive_id>/<media_row_id> -- opaque ids only. The webview never
  sees a filesystem path, an export URI or a filename.
- igmedia containment order matters: resolve (reject .. and absolute; walk EVERY
  component with symlink_metadata refusing symlinks; O_NOFOLLOW; fstat re-verify)
  FIRST, then the MIME allowlist.
- igmedia MIME allowlist is deny-by-default and excludes text/html and image/svg+xml,
  which could execute script in the app origin. Every response carries
  X-Content-Type-Options: nosniff and Cache-Control: no-store.
- The export root for a media request comes from the archive's own meta table, never
  from the request, so a forged id cannot steer resolution.
- Verify framework APIs against current Tauri 2 docs before using them.
