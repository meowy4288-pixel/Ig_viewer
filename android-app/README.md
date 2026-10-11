# Android app (native)

Native Kotlin/Compose app for viewing Instagram DM archives on Android.

## Why this exists separately from the Tauri app

The desktop app in the repo root is Tauri (Rust core + webview UI) and it stays
that way — it is working and well tested.

The earlier Android build was a Tauri mobile target. It builds, launches, indexes
and browses, but **import does not work**, and the reason is structural rather
than a bug:

- Android's Storage Access Framework returns opaque `content://` URIs. `std::fs`
  cannot open one, so every archive needs a Kotlin bridge regardless.
- `tauri-plugin-dialog`'s `getPathFromUri` returns null for SD cards and cloud
  providers, and it has no tree-URI branch, so folder picking cannot work through
  it at all.
- `<audio>`/`<video>` need GStreamer workarounds under WebKitGTK; native players
  handle this natively.

Fighting a native-first platform through a webview wrapper cost more than writing
the Android app directly. So: **Tauri for desktop, native Kotlin here.**

## What is shared vs rewritten

Shared — the Rust core stays the source of truth for everything non-UI:

| Concern | Where |
|---|---|
| Ingest, dedupe, month bucketing | `src-tauri/src/ingest.rs` (via UniFFI) |
| SQLite schema, FTS5 search | `src-tauri/src/db.rs` (via UniFFI) |
| Zip extraction + hardening | `src-tauri/src/zip.rs` (via UniFFI) |
| Fake fixtures | `fake-data/` (shared) |

Native here — only what is genuinely platform-specific:

| Concern | Module |
|---|---|
| SAF picker (folder + zip) | `data/ArchivePicker.kt` |
| Compose UI | `ui/` |
| Media playback | `ui/` (ExoPlayer/MediaPlayer) |
| Theme, density, text size | `ui/theme/` |

## Status

Scaffolding only. Nothing here builds or runs yet.

## Build order

1. Gradle setup, app installs and launches
2. UniFFI bridge exposing the Rust core; prove one archive indexes on device
3. SAF folder + zip picker feeding the bridge
4. Chat list, thread view, month paging
5. Search, images, media playback
6. Settings mirroring the desktop app

Step 2 is the real risk and the reason for doing this as a spike first: if the
Rust core cannot be shared cleanly through UniFFI, the cost model changes and it
is worth knowing before writing a full Compose UI.

## Building

```bash
cd android-app
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
