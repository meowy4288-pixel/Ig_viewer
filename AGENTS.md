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
- Verify framework APIs against current Tauri 2 docs before using them.
