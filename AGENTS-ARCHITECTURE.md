# Architecture

Two separate applications live in this repository. They do not share a build.

## Desktop — Tauri (repository root)

```
src/            TypeScript UI (webview)
src-tauri/      Rust core: ingest, SQLite/FTS5, zip, igmedia: protocol
fake-data/      deterministic fixtures used by the test suites
```

The only supported targets are desktop (Linux/macOS/Windows).

Android was previously built from this crate as a Tauri mobile target. That is
**removed**. It built and browsed, but import could not work: SAF returns
`content://` URIs that `std::fs` cannot open, `tauri-plugin-dialog` has no
tree-URI branch so folder picking is impossible through it, and `<audio>`/`<video>`
need GStreamer workarounds under WebKitGTK. Those are platform limits, not bugs.

Consequences of the removal, so they are not re-added by accident:

- `rfd` is an unconditional dependency again; no `target_os` cfgs remain
- `tauri-plugin-dialog` is gone; the Rust `pick_export_*` commands are the only picker
- `capabilities/mobile.json` is gone; `default.json` remains (`core:default` only)
- `src-tauri/gen/` is gone, and so are `scripts/android-seed.mjs` and the
  `import_from_app_dir` / `seed_demo_archive` commands
- The APK on the v0.1.0 release is the last Tauri-Android build and is superseded

## Android — native Kotlin (`android-app/`)

```
android-app/app/src/main/java/com/igviewer/archive/
  data/    SAF picker: copies a content:// URI into app storage
  ui/      Compose screens
  core/    reserved for the UniFFI bridge to the Rust core
```

Nothing here builds yet. See `android-app/README.md` for the shared-vs-rewritten
split and the build order.

The intended split is that **the Rust core stays the source of truth** for ingest,
dedupe, SQLite and zip (reached through UniFFI), and only the UI, picker and media
playback are native. Rewriting the core in Kotlin would be the mistake to avoid.

## Permissions

Neither app requests broad storage access. Desktop uses rfd's native dialog;
Android uses SAF, which grants per-file access at pick time.
