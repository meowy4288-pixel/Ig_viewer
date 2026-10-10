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
- igmedia is a SUBRESOURCE scheme only. on_navigation returns false for it, so the
  webview can never navigate to igmedia: or any custom scheme; it is only ever an
  <img>/<video>/<audio> src. Only http(s) anchors exist in the UI.
- Media range requests are single-range only (bytes=a-b, bytes=a-, bytes=-n).
  Multi-range returns 400 rather than a partial multipart response.
- Frontend rules: no innerHTML/outerHTML/insertAdjacentHTML/document.write anywhere;
  chat text is only ever set with textContent or createTextNode. splitLinks() in
  src/logic.ts decides link boundaries and only ever yields http(s); ui.ts
  re-validates before building an anchor.
- Pure display logic lives in src/logic.ts with no DOM or Tauri imports so it can be
  unit tested under node (`npm run test:ts`). The ordering contract
  (ts_ms ASC, seq DESC) is re-asserted there so a UI regression fails a test.
- An index that skipped any files must say so in the UI; never claim a complete
  import.
- Media range requests are single-range only (bytes=a-b, bytes=a-, bytes=-n). A
  multi-range header returns 400 rather than a partial multipart response.
- igmedia: is registered as a Tauri "local" origin, so anything loaded under that
  scheme could in principle reach IPC. on_navigation therefore returns false for
  igmedia:, so the webview can never navigate to it: it is only ever an <img>,
  <video> or <audio> src.
- KNOWN LIMITATION: thread_participants() rebuilds the "I am..." list from the
  distinct senders in the messages table rather than from the JSON `participants`
  array. Someone who never posted in a thread will not appear. The participants
  field was not persisted at ingest; re-ingesting is not worth it.
- Search snippets come from FTS5 snippet() with [ and ] as highlight markers (chosen
  so the marker can never be mistaken for markup). The snippet is untrusted chat
  content and is rendered with textContent into a <mark> element created in ui.ts.
- KNOWN LIMITATION (WebKitGTK): <audio>/<video> CANNOT play over a custom URI
  scheme. Images work (they use WebKit's resource loader, which knows custom
  schemes) but media is handed to GStreamer, whose uridecodebin has no URL handler
  for igmedia: -- WebKit Bug 146351, and Wails #4412 shows the identical failure.
  The igmedia protocol itself serves media correctly (200/206, right MIME and
  bytes), so this is a platform limitation, not an app bug. Fixing it needs either
  blob: object URLs fed over IPC, or a loopback HTTP server -- both require a CSP
  change and were NOT taken.
- PACKAGING (deliberate): the .deb declares Debian/Ubuntu dependency names
  (libwebkit2gtk-4.1-0, libgtk-3-0). On Fedora those libraries are named
  webkit2gtk4.1 and gtk3, so dpkg leaves the package in state iU even though
  everything needed is installed. Workaround: sudo dpkg --remove
  ig-chat-archive-viewer, then run /usr/bin/ig-viewer. tauri.conf.json is
  deliberately NOT edited to special-case Fedora.
- PACKAGING (deliberate): AppImage remains in bundle.targets, but building it
  needs a one-time network fetch of AppRun + linuxdeploy from tauri-apps
  binary-releases. The .deb is the offline-buildable, offline-verified artifact.
- LOOSE MEDIA FILES ARE UNSUPPORTED: only media referenced from an indexed
  message_N.json is playable. A standalone audio file (e.g. a lone .wav dropped in
  a folder) has no message row, therefore no media id, therefore nothing to serve.
  This is intentional: importing loose files would mean copying data into the
  archive or exposing filesystem paths to the webview, both of which are worse.
- FIXTURES: images are real 96x96 PNGs (stdlib zlib/struct, distinct hue + binary
  stripe pattern per message) and voice notes are real 8-bit PCM WAVs (stdlib wave,
  pitch encodes the index) so indexing and serving can be checked by eye and ear.
  Video remains an ftyp stub: producing a decodable MP4 is out of proportion, and
  the protocol path is identical for audio and video.
- Release verification procedure and its recorded result: docs/release-verification.md
