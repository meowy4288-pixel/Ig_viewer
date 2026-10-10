# Release verification

Recorded for the M6 packaged build. This file records **only what was actually
observed**. Items that were not exercised are listed under "Not verified" with the
reason, rather than being marked as passing.

Build under test: `src-tauri/target/release/bundle/deb/IG Chat Archive Viewer_0.1.0_amd64.deb` (3,344,148 bytes), installed with `sudo dpkg -i`.

Host: Fedora 44, KDE Plasma / Wayland, webkit2gtk 2.54.1, gtk3 3.24.52.
Fixture: `fake-data/export` (generated, seed 1337; no real Instagram data was used).

## Verified on the packaged binary

| Step | Action | Observed |
|---|---|---|
| Install | `sudo dpkg -i <deb>` | Unpacks and installs the binary to `/usr/bin/ig-viewer`. dpkg leaves the package state `iU` (see Packaging limitations). |
| Launch | `ig-viewer` | Window opens, title "IG Chat Archive Viewer", 1200x828. No panic, empty log. |
| Index | pasted `fake-data/export`, clicked **Index this folder** | Status line reported a successful index of 4 threads / 254 messages. (User-confirmed: "it works".) |

The earlier failure `Indexing failed: malformed-archive-id` on the paste-a-path flow
was a real wiring bug -- `archive_id` was only ever created by the native folder
picker, so the paste path had no archive to write into. Fixed by making
`archive_id` optional and minting one in Rust; covered by a regression test.

## Verified against the live indexed archive

Archive id `0f5de2e832eb1cf747da4e20f647c0af`, read directly from the archive DB and
exercised through the real `db::` functions.

**Index contents** -- 4 threads, 254 messages:

```
ana_silva_5544            12 msgs  Ana Silva
dev_team_7730             60 msgs  Dev Team
mira_okonkwo_4821        157 msgs  Mira Okonkwo
sam_lee_9012              25 msgs  Sam Lee
```

**Timezone is live.** The archive was indexed with the *system* zone, recorded in
`meta.timezone = Asia/Kolkata`, not the oracle's `Europe/Warsaw`. Month buckets
therefore legitimately differ from `expected.json`:

```
2024-01 148 | 2024-02 3 | 2024-03 2 | 2024-10 2 | 2025-01 2
```

vs the Europe/Warsaw oracle `149 / 2 / 2 / 2 / 1(2024-12) / 1(2025-01)`. The missing
`2024-12` bucket is the expected consequence of a +05:30 offset, and is evidence that
the stored timezone is actually applied rather than assumed.

**Search returns sender + snippet + timestamp** (the M5 blocking item), executed as
the app's FTS5 query. Multi-word query `"build green"` (AND across both terms),
5 hits, first row exactly as rendered:

```
sender : Mira Okonkwo
time   : 2024-01-05 12:37:39
snippet: the [build] is [green] again
```

The `[...]` markers are FTS5 highlight delimiters; `ui.ts` converts them to `<mark>`
elements using `textContent`/`createElement`, never `innerHTML`. A query with no hits
returns **0 rows**, not all rows (`zzzznotaterm` -> 0).

**Media serving** (from the live archive, through `protocol::serve`):

```
audio#5   200  audio/wav  2844 bytes
audio#18  200  audio/wav  2844 bytes
image#1   200  image/png   355 bytes
range     206  audio/wav  1024 bytes  start=0 total=2844  partial=true
```

Correct status, MIME, byte count and `206 Partial Content` for ranged requests.

**Fixture media is genuinely valid.** 17/17 PNGs pass signature + IEND + 96x96 +
per-chunk CRC + IDAT inflating to the exact pixel buffer; 17/17 are byte-distinct.
5/5 WAVs decode with the stdlib `wave` module and are 220-880 Hz tones with pitch
encoding the message index.

**Delete index removes the database and both sidecars:**

```
before: [ ...ef...db, ...ef...db-shm, ...ef...db-wal ]
after : []
```

Malformed ids are rejected without touching the filesystem:
`../escape`, `AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA` (uppercase), `zzz` -> all `Malformed`.

**Export untouched.** Automated tests assert the export is byte-identical *and*
structurally identical (paths, types, counts) after ingest, after media serving, and
after all UI read paths.

## Not verified on screen

These were exercised by unit/integration tests but **not witnessed in the running
UI**, because WebKitGTK does not expose its DOM to the accessibility tree on this
desktop, so the checklist could not be driven by coordinate-clicking a screen that
was intermittently captured as blank. Marking them "verified" would be a false record.

| Item | Status | Reason |
|---|---|---|
| Search row rendering in the UI | backend verified (above), DOM not eyeballed | as above |
| Month paging via the month dropdown / ◀ ▶ | data verified, clicks not witnessed | as above |
| Jump-to-date | data verified (`month_for_ts` covered by test), control not clicked | as above |
| Image rendering in the UI | bytes verified, on-screen display not witnessed | as above |
| Voice-note / video playback | **KNOWN FAILURE -- see below** | WebKitGTK limitation |
| Delete index via the UI button | backend verified, button not clicked | as above |
| UTC fallback banner | **not triggerable on this host** | system tz detection succeeded (`Asia/Kolkata`), so the fallback branch was never entered. Code path covered by unit test only. |

## Media outcome: images render, audio and video do not

**Observed on the packaged build by the user: photos render ("different designs" per
message). Audio and video produce no playback.**

### The correction that matters

An earlier entry in this document claimed images worked. **That was wrong, twice
over:**

1. The "evidence" was a direct call to `protocol::serve()` in Rust. That bypasses
   Tauri's URI-scheme routing entirely, so it proved the *file reading* worked and
   nothing about whether a browser request ever reached the handler.
2. The thing visible on screen was the **reserved layout box of a broken image**,
   which was mistaken for a rendered photo. Zooming in showed WebKit's `?`
   placeholder.

Instrumenting the handler is what actually caught it:

```
[igmedia] ENTER path=/0f5de2e832eb1cf747da4e20f647c0af/1
[igmedia] ENTER path=/0f5de2e832eb1cf747da4e20f647c0af/3
... (14 entered, 0 served)
```

Every request failed. Cause: `parse_media_url` required the path to start with
`media/`, but `igmedia://media/<archive>/<id>` puts `media` in the **host**, so the
handler receives `/<archive>/<id>`. After the fix, photos render as the user confirms.

**Lesson recorded in AGENTS.md: verify the protocol end-to-end through the running
app. A direct `protocol::serve()` call is not a substitute.**

## Images render; audio and video do not

**Verified on the packaged build: photos DO render.** This was broken until a real
bug was found: `parse_media_url` required the path to start with `media/`, but
`igmedia://media/<archive>/<id>` puts `media` in the HOST, so the handler actually
receives `/<archive>/<id>`. Every request therefore failed to parse. Earlier claims
that images worked were based on calling `protocol::serve()` directly, which bypasses
Tauri's routing and so proved nothing. Caught by instrumenting the handler: 14
requests entered, 0 served. Fixed; the parser now accepts both shapes.

`<audio>` and `<video>` still **cannot play over a custom URI scheme on WebKitGTK.**

The `igmedia` protocol is not at fault -- it returns the correct status, MIME type,
bytes and `206` ranges (evidence above). The failure is that WebKitGTK hands
`<audio>`/`<video>` sources to **GStreamer**, whose `uridecodebin` has no URL handler
for a custom scheme:

* WebKit Bug 146351 -- "[GStreamer] Audio/video sources do not load via custom URI
  scheme"; explicitly notes it affects audio too.
* Wails issue #4412 shows the identical failure with the GStreamer error
  `No URI handler implemented for "<scheme>". (code=12)`.

WebKit's own conclusion is that support "would need a custom GStreamer source
element", i.e. a platform change, not an application change.

Possible remedies, **none taken** because each requires a CSP change:

* **A.** Fetch bytes over `igmedia:` via IPC (that path works), build a `Blob`, and
  use an object URL for the media element. Needs `media-src ... blob:`. Preserves
  every current security property; buffers the whole file in memory.
* **B.** Serve media from a loopback HTTP server, which GStreamer understands.
  Needs a `media-src http://127.0.0.1:*` entry and opens a listening socket, which
  sits badly with the offline-only rule.

## Packaging limitations (deliberate, not bugs)

* **`.deb` dependency names are Debian/Ubuntu.** It declares `libwebkit2gtk-4.1-0`
  and `libgtk-3-0`; Fedora names those libraries `webkit2gtk4.1` and `gtk3`, so dpkg
  cannot resolve them and leaves the package `iU` even though both are installed.
  Workaround: `sudo dpkg --remove ig-chat-archive-viewer`, then run
  `/usr/bin/ig-viewer`. `tauri.conf.json` is deliberately **not** edited to
  special-case Fedora, because that would break the more common Debian/Ubuntu target.
* **AppImage was never produced.** It stays in `bundle.targets`, but building it
  requires a one-time network fetch of `AppRun` and `linuxdeploy` from
  `tauri-apps/binary-releases`, which failed here on a download timeout. The `.deb`
  is the offline-buildable and offline-verified artifact.

## Dropped

* **"panic on export"** -- DROPPED. Reported without an error message, window, or
  reproduction steps, and asked for twice. This build contains **no export-writing
  code**: it reads the export and writes only its own SQLite index into the app data
  directory. There is nothing that exports, so there is no code path to fix. Recorded
  here rather than left as an open item, because an undefined symptom cannot be
  actioned and guessing at a fix would be worse than dropping it.

## Reproducing this verification

```bash
npm run build && npm run test:ts
(cd src-tauri && cargo test && cargo clippy --all-targets -- -D warnings)
python3 tools/gen_fake_data.py --verify        # fixtures match the generator
python3 tools/gen_fake_data.py --check         # generation is deterministic
npx tauri build --bundles deb
sudo dpkg -i src-tauri/target/release/bundle/deb/*.deb
ig-viewer
# then, in the UI: index fake-data/export
```
