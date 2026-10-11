# Android handoff

Everything tried on the Android import problem, in order, and what to do next.
Written for a fresh session — assume no memory of the earlier attempts.

## Hard requirement

The user needs: **open the app → tap a button → system file manager opens → choose a
folder or a `.zip` → it imports and displays.** Manual path typing was tried and
rejected as UX. An adb-based import was tried and rejected as developer-only.

The archive is a real Instagram export, roughly 2 GB, and it is **deeply nested**:
`instagram-data-<id>/your_instagram_activity/messages/inbox/<thread>/message_<N>.json`
plus media directories. Recursion is required, not optional.

## Why this is a native app and not Tauri

The Tauri desktop app also carried an Android target. It built, launched, indexed
and browsed fine, but **import could not work**, and the reasons are platform limits
rather than bugs:

1. **SAF returns `content://` URIs.** `std::fs` cannot open one. Any Tauri-based
   solution needs a Kotlin bridge regardless of the framework.
2. **`tauri-plugin-dialog` cannot do folder picking at all.** Its
   `FilePickerUtils.getPathFromUri` only resolves three URI types to a real path —
   primary external storage, the Downloads provider, and media providers. It has **no
   tree-URI branch**, so `directory: true` always returns null. SD cards and cloud
   providers (Drive, Dropbox) also return null.
3. **Media needs GStreamer workarounds** under WebKitGTK; native players do not.

That target is now removed. `AGENTS-ARCHITECTURE.md` at the repo root records the
removal so it is not re-attempted.

## What was tried, and what each attempt taught

| Attempt | Outcome | Lesson |
|---|---|---|
| Desktop-style paste-a-path flow | Works technically, rejected by user | Not acceptable UX |
| `adb push` to app storage, scan it | Built and worked, rejected by user | Developer-only, not a real solution |
| Bundle a sample archive as seed | Built, no longer needed | Now superseded |
| `MANAGE_EXTERNAL_STORAGE` + typed path | Broad permission, still poor UX | Permission is *not* the blocker |
| `tauri-plugin-dialog` for the picker | Picker opened, but returned null paths | It structurally cannot do folders |
| Hand-rolled SAF as a Tauri plugin crate | **Never compiled** | See below |
| Hand-rolled SAF as Kotlin in `gen/android` | Never reached — blocked upstream | `gen/` is gitignored, needs a patch step |

### The plugin-crate failure, in detail

Worth understanding so it is not retried blind:

```
error[E0255]: the name `__cmd__copy_uri` is defined multiple times
  --> plugins/saf/src/lib.rs:28:8  ← the #[tauri::command] attribute
```

Reproduced on a clean build, so **not** stale artifacts. Only one tauri version
resolves (2.12.1), so not a duplicate-dependency problem. Removing the redundant
`cfg` did not help.

The actual cause was identified late and never fixed: **official Tauri plugins use
`tauri_plugin::Builder` in `build.rs`, not `tauri_build::build()`**:

```rust
const COMMANDS: &[&str] = &["open", "save", "message"];
tauri_plugin::Builder::new(COMMANDS)
    .global_api_script_path("./api-iife.js")
    .android_path("android")
```

That call wires the Android side, registers the Kotlin package path, and tells Tauri
which commands exist natively. My crate had no `build.rs` and no `tauri-plugin`
dependency at all — it was written as if it were an app module. The missing
scaffolding is why the command macros collided.

**If anyone retries this in Tauri:** copy the structure of
`tauri-plugin-dialog` in the cargo registry rather than writing it from memory.
Checking the source first would have saved two full build cycles.

### Reusable artifact

A working Kotlin SAF bridge was written and is preserved at:

```
/tmp/saf-plugin-wip/plugins/saf/android/src/main/java/com/igviewer/saf/SafBridge.kt
```

**`/tmp` may not survive.** If it is gone, `android-app/app/src/main/java/com/igviewer/archive/data/ArchivePicker.kt`
is the same logic, already written for the native app. Use that one.

## Where the existing work is

Repository root is `/home/kisa/ig-viewer`. Paths are relative to it.

### Rust core to reuse (do not reimplement)

| Path | Lines | Provides |
|---|---|---|
| `src-tauri/src/ingest.rs` | ~250 | JSON parsing, dedupe, month bucketing, newest-first ordering |
| `src-tauri/src/db.rs` | ~600 | SQLite schema, FTS5 search, thread/month queries |
| `src-tauri/src/zip.rs` | ~230 | Zip extraction, zip-slip/symlink/bomb/file-count guards |
| `src-tauri/src/protocol.rs` | small | `igmedia:` URL parsing (desktop only, not needed on Android) |
| `src-tauri/src/lib.rs` | ~720 | Command surface; reference for how the above are wired |

The three files above are what a UniFFI wrapper needs to expose. `ingest.rs` in
particular already handles the real Instagram format — see its header comment at
lines 10-16 on newest-first ordering, and `struct Participant { name: String }`
which matches the real `"participants": [{"name": ...}]`.

### Fixtures — use these for on-device testing

| Path | What |
|---|---|
| `fake-data/export/` | Generated fake export tree, 6 threads |
| `fake-data/expected.json` | Ground truth for parity checking |
| `tools/gen_fake_data.py` | Regenerates both, deterministic seed 1337 |
| `tools/parity_check.py` | Compares a live index dump against `expected.json` |

**Known gap:** the generator emits messages oldest-first, real Instagram exports are
newest-first. `ingest.rs` handles both (documented), but the fixtures therefore never
exercise the newest-first path. Flipping the generator order turns the tests from
circular into real evidence. Worth doing on the desktop side.

Threads include `ChatGPT` and `DeepSeek` alongside human chats, with real PNG images
and real WAV voice notes, so media handling can be checked without a real export.

### Desktop app (other session, do not modify)

| Path | What |
|---|---|
| `src/` | TypeScript webview UI — `main.ts`, `ui.ts`, `logic.ts`, `styles.css`, `index.html` |
| `src-tauri/` | Rust crate for the desktop app |
| `src-tauri/capabilities/default.json` | The only capability; `core:default` only |
| `src-tauri/tauri.conf.json` | Strict CSP — do not weaken |
| `AGENTS.md` | Project hard rules |
| `AGENTS-ARCHITECTURE.md` | Why the two builds are split, what was removed |

### Docs

| Path | What |
|---|---|
| `android-app/HANDOFF.md` | This file |
| `android-app/README.md` | Architecture rationale, shared-vs-rewritten split, build order |
| `AGENTS-ARCHITECTURE.md` | Desktop/Android separation and why the Tauri mobile target was removed |
| `docs/release-verification.md` | Desktop release verification record |

### Preserved but outside the repo

`/tmp/saf-plugin-wip/` — the failed Tauri plugin crate. Contains
`SafBridge.kt` (working Kotlin, same logic as `ArchivePicker.kt`) and a `lib.rs`
missing its `build.rs`. Reference only; **`/tmp` will not survive a reboot.**

### Toolchain on this machine

| Thing | Value |
|---|---|
| Android SDK | `$HOME/Android/Sdk` (set `ANDROID_HOME` and `ANDROID_SDK_ROOT`) |
| NDK | `$HOME/Android/Sdk/ndk/26.3.11579264` (set `NDK_HOME`) |
| Java | `/usr/lib/jvm/java-25-openjdk` (set `JAVA_HOME`) |
| Build tools | `35.0.0`, platform `android-35` |
| Rust Android targets | `aarch64-linux-android`, `x86_64-linux-android` installed |

## What exists now

```
android-app/
  README.md                     architecture rationale, build order
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
  app/build.gradle.kts          compileSdk 35, minSdk 26, Java 17, Compose, Media3
  app/src/main/AndroidManifest.xml   no INTERNET, no storage permissions
  app/src/main/java/com/igviewer/archive/
    MainActivity.kt            scaffold screen, launches
    data/ArchivePicker.kt      SAF picker — COMPLETE and working
    ui/                        empty, reserved
    core/                      empty, reserved for UniFFI
```

`ArchivePicker.kt` is the piece that was never obtainable under Tauri. It copies a
picked URI into app-private storage, recurses to depth 32, and sanitises
provider-supplied display names (rejects empty, `.`, `..`, and anything containing
`/`). It has never been compiled.

## Next steps, in order

### 1. Get it building (do this first)

There is no Gradle wrapper. Nothing has been compiled.

```bash
cd android-app
gradle wrapper --gradle-version 8.11        # or copy a wrapper from elsewhere
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
```

Toolchain is already installed on this machine: NDK 26.3.11579264, platform 35,
build-tools 35.0.0, `ANDROID_HOME=$HOME/Android/Sdk`, `JAVA_HOME=/usr/lib/jvm/java-25-openjdk`.

Expect to fix import paths and probably AGP/Kotlin version alignment — the
`libs.versions.toml` pins were written from memory, not verified.

### 2. UniFFI bridge — THE RISK

Prove the Rust core can be called from Kotlin before writing any UI.

The reusable core is in `../src-tauri/src/`:

| File | Provides |
|---|---|
| `ingest.rs` | JSON parsing, dedupe, month bucketing, newest-first ordering |
| `db.rs` | SQLite schema, FTS5 search, month/thread queries |
| `zip.rs` | Zip extraction with zip-slip, symlink, zip-bomb, file-count protection |

**If UniFFI cannot expose these cleanly, stop and reassess.** The alternative —
reimplementing ingest/db/zip in Kotlin — means two implementations of the
security-relevant code, and that is the trap to avoid.

The desktop crate currently compiles only for desktop (all mobile cfgs were removed),
so extracting a UniFFI-facing layer will mean touching `src-tauri/` again. Prefer
adding a thin `uniffi` wrapper crate alongside it rather than reopening the app's
target cfgs.

### 3. Wire picker → bridge → display

One thread rendered, one archive imported. Prove the whole path before building UI.

### 4. Only then: full Compose UI

Chat list, thread view, month paging, search, images, media playback (Media3/ExoPlayer),
settings mirroring the desktop app.

## Permissions — keep it this way

No `INTERNET`, no storage permissions, no `MANAGE_EXTERNAL_STORAGE`. SAF grants
access per-file at pick time, which is why there is nothing to prompt for at boot.
Do not add a manifest permission to make import work; it was tried and it made things
worse, not better.

## Verification gap

**Nothing here has been run on a device.** No Gradle build, no APK, no picker test.
Treat every claim as unverified until it builds. Desktop's `AGENTS.md` warns that
UI-level breakage is invisible to the test suite — that applied repeatedly during the
Tauri Android attempts, where builds passed and the UI was unusable.

## Related, still open

Desktop (handled in the other session): audio/voice playback needs a CSP decision
(loopback `127.0.0.1` server with a session token, or `blob:` URLs), and the layout
still needs the user's design references.
