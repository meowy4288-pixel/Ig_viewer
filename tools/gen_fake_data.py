#!/usr/bin/env python3
"""
Deterministic generator for a FAKE Instagram DM export.

Writes fake-data/export/ (the export tree) and fake-data/expected.json (ground truth).

Nothing here touches the network, and nothing real ever goes in: every name, message
and media byte is invented. Run tools/gen_fake_data.py --help for usage.

    python3 tools/gen_fake_data.py --out fake-data --seed 1337
    python3 tools/gen_fake_data.py --check      # determinism self-test
    python3 tools/gen_fake_data.py --verify     # fail if fake-data/ drifted

Canonical ordering (mirrors ig_chat_viewer.html `all.sort(...)`):
    display key = (timestamp_ms ASC, ord DESC), ord = (file number, index in file)
    because each message_N.json stores its messages newest-first, a tie at the same
    timestamp displays with the HIGHER index first.

Canonical dedupe (mirrors tools/ig_chat_rebuild.py load_messages):
    a message from file N is dropped only if its key already appeared in a
    LOWER-numbered file. Within one file, identical messages are all kept.
"""
import argparse
import hashlib
import json
import random
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

DEFAULT_SEED = 1337
DEFAULT_TZ = "Europe/Warsaw"

# ---------------------------------------------------------------- text encoding


def fix(s):
    """Undo Instagram's mojibake (UTF-8 bytes stored as latin-1). Mirrors the
    reference tools; note it is a no-op for anything not representable in latin-1,
    which is what lets a correctly-stored string survive untouched."""
    if not isinstance(s, str):
        return s
    try:
        return s.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


def mojibake(s):
    """Encode the way the real export does: UTF-8 bytes mis-read as latin-1."""
    return s.encode("utf-8").decode("latin-1")


# Text pool: all invented, plus deliberate script/accent coverage.
WORDS = [
    "ship it", "lunch?", "the build is green again", "café run at 5?",
    "naïve approach", "let's split the refactor", "ping me when free",
    "bringing snacks", "that bug was a typo", "ok, sounds good",
    "日本語もVisa", "Привет, как дела?", "مرحبا, كيف حالك؟",
    "emoji test 🎉", "family emoji 😀🎂", "double  spaces  here",
    "I'll bring the docs", "deploy window is 02:00", "ack", "ty!",
]
UNICODE_SAMPLES = [
    "café", "日本語のテキスト", "😀🎉", "Привет мир", "مرحبا بالعالم",
    "Ana O'Brien-Świątek", "naïve — dash", "plain ascii text",
]

THREADS = [
    # key, display title, participants, n_msgs, n_files
    ("mira_okonkwo_4821", "Mira Okonkwo", ["Mira Okonkwo", "Kai Lindqvist"], 141, 12),
    ("dev_team_7730", "Dev Team", ["Kai Lindqvist", "Ana O'Brien-Świątek (she/her)",
                                   "bot/worker", "Dev Team", "Ravi Menon"], 60, 4),
    ("sam_lee_9012", "Sam Lee", ["Sam Lee", "Kai Lindqvist"], 25, 2),
    ("ana_silva_5544", "Ana Silva", ["Ana Silva", "Kai Lindqvist"], 12, 1),
]

# Base instant: 2024-01-05T00:00:00Z, so the timeline crosses several months.
BASE_MS = 1704412800000
HOUR = 3600_000


class Message:
    """One logical message with a stable id. `ord` is assigned later, when the
    timeline is scattered across files, so expected.json can be derived from this
    in-memory list without re-reading the files it writes."""
    __slots__ = ("mid", "thread", "ts_ms", "sender", "content", "media", "reactions",
                 "share", "is_unsent", "call_duration", "raw_fields", "ord", "dup_of")

    def __init__(self, mid, thread, ts_ms, sender, content, media=None, reactions=None,
                 share=None, is_unsent=False, call_duration=None, raw_fields=None,
                 dup_of=None):
        self.mid = mid
        self.thread = thread
        self.ts_ms = ts_ms
        self.sender = sender
        self.content = content
        self.media = media or {}          # {"photos": [uri,...], "videos": [...], ...}
        self.reactions = reactions or []
        self.share = share
        self.is_unsent = is_unsent
        self.call_duration = call_duration
        self.raw_fields = raw_fields or []  # field names stored CORRECTLY, not mojibaked
        self.dup_of = dup_of
        self.ord = None                    # (file_no, idx_in_file), set at scatter time

    def key(self):
        """Canonical dedupe key. Reactions and share are deliberately NOT part of it."""
        uris = []
        for k in ("photos", "videos", "audio_files", "gifs", "sticker", "files"):
            for item in self.media.get(k) or []:
                uris.append(item if isinstance(item, str) else item.get("uri", ""))
        return (self.ts_ms, self.sender, self.content, tuple(uris))

    def encoded(self, field):
        """Return the on-disk form of a text field: mojibaked unless marked raw."""
        val = getattr(self, field) if field in ("content", "sender") else None
        if field in self.raw_fields:
            return val
        return mojibake(val)


def stub_bytes(name, kind):
    """Deterministic synthetic media. Not valid playable media, on purpose: these
    fixtures exercise byte-exact streaming and MIME handling, not playback."""
    digest = hashlib.sha256(name.encode("utf-8")).digest()
    if kind == "png":
        # 1x1 transparent PNG, literal bytes.
        return bytes.fromhex(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
            "890000000a49444154789c6300010000050001"
            "0d0a2db40000000049454e44ae426082")
    if kind in ("mp4", "m4a"):
        return b"\x00\x00\x00\x18ftyp" + kind.encode("ascii") + b" \x00\x00\x00\x00" + digest[:32]
    if kind == "svg":
        # Deliberately hostile content for M4's MIME allowlist.
        return (b'<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>')
    if kind == "html":
        return b"<html><body><script>alert('xss')</script></body></html>"
    return digest[:64]


def media_uri(thread, folder, name):
    return f"your_instagram_activity/messages/inbox/{thread}/{folder}/{name}"


def build_timeline(rng):
    """Build the logical timeline. This list is the single source of truth for
    expected.json; files are derived from it afterwards."""
    msgs = []
    counter = 0

    def add(thread, ts_ms, sender, content, **kw):
        nonlocal counter
        counter += 1
        m = Message(f"m{counter:04d}", thread, ts_ms, sender, content, **kw)
        msgs.append(m)
        return m

    # ---- large thread: media, duplicates, ties, DST, boundaries ----------------
    key, title, parts, n, nfiles = THREADS[0]
    me, them = parts[1], parts[0]
    t = BASE_MS
    idx = 0
    while len([m for m in msgs if m.thread == key]) < n:
        sender = me if idx % 3 == 0 else them
        content = WORDS[rng.randrange(len(WORDS))]
        ts = t + idx * HOUR + rng.randrange(-600_000, 600_000)
        kw = {}
        if idx % 17 == 0:
            kw["raw_fields"] = ["content"]
            content = UNICODE_SAMPLES[rng.randrange(len(UNICODE_SAMPLES))]
        if idx % 13 == 0:
            name = f"photo_{idx:04d}.png"
            kw["media"] = {"photos": [media_uri(key, "photos", name)]}
        elif idx % 23 == 0:
            name = f"video_{idx:04d}.mp4"
            kw["media"] = {"videos": [media_uri(key, "videos", name)]}
        elif idx % 29 == 0:
            name = f"voice_{idx:04d}.m4a"
            kw["media"] = {"audio_files": [media_uri(key, "audio", name)]}
        elif idx == 40:
            # referenced on disk
            name = f"present_photo_{idx}.png"
            kw["media"] = {"photos": [media_uri(key, "photos", name)]}
        elif idx == 41:
            # referenced but MISSING on disk
            name = f"deleted_{idx}.jpg"
            kw["media"] = {"photos": [media_uri(key, "photos", name)]}
        elif idx == 42:
            # bare-filename reference (exercises the basename fallback)
            name = f"bare_clip_{idx}.mp4"
            kw["media"] = {"videos": [name]}
        elif idx == 43:
            # path-traversal attempt: must never be read
            kw["media"] = {"photos": ["../../../../etc/passwd"]}
        elif idx == 44:
            # A REAL sticker. In Instagram's schema `sticker` is a single
            # {"uri": ...} object, not an array -- confirmed against
            # ig_chat_rebuild.py (st.get("uri")) and ig_chat_viewer.html
            # (m.sticker.uri). encode_message() writes it in that shape.
            kw["media"] = {"sticker": [media_uri(key, "photos", f"sticker_{idx}.png")]}
        elif idx == 45:
            kw["media"] = {"files": [media_uri(key, "files", f"doc_{idx}.pdf")]}
        elif idx == 59:
            # Multi-kind message: a photo AND a voice note AND a video in one
            # message. An `or` chain in the dedupe key would drop every kind after
            # the first, so this is the fixture that catches that bug class.
            kw["media"] = {
                "photos": [media_uri(key, "photos", f"multi_photo_{idx}.png")],
                "audio_files": [media_uri(key, "audio", f"multi_voice_{idx}.m4a")],
                "videos": [media_uri(key, "videos", f"multi_video_{idx}.mp4")],
            }
        elif idx == 60:
            # Multi-kind including a sticker, in a second combination.
            kw["media"] = {
                "gifs": [media_uri(key, "gifs", f"multi_gif_{idx}.png")],
                "photos": [media_uri(key, "photos", f"multi2_photo_{idx}.png")],
                "sticker": [media_uri(key, "photos", f"multi2_sticker_{idx}.png")],
            }
        elif idx == 47:
            kw["share"] = {"link": "https://example.invalid/a?b=c",
                           "share_text": "shared link text café"}
        elif idx == 48:
            kw["reactions"] = [{"reaction": "❤️", "actor": them}]
        elif idx == 49:
            kw["reactions"] = [{"reaction": "😂", "actor": them},
                               {"reaction": "🔥", "actor": me}]
        elif idx == 50:
            kw["is_unsent"] = True
        elif idx == 51:
            kw["is_unsent"] = True
            content = "this text stays but the message is unsent"
        elif idx == 52:
            content = ""
        elif idx == 53:
            content = "x" * 5000
        elif idx == 54:
            kw["call_duration"] = 742
        elif idx == 55:
            kw["media"] = {"photos": [media_uri(key, "photos", f"evil_{idx}.html"),
                                      media_uri(key, "photos", f"script_{idx}.svg")]}
        elif idx == 56:
            # hostile URI that escapes the thread folder
            kw["media"] = {"photos": [f"your_instagram_activity/messages/inbox/{key}/photos/../../../evil.png"]}
        add(key, ts, sender, content, **kw)
        idx += 1

    # Tied timestamps. ALL tie pairs live inside a single file (see scatter()).
    # The four are tagged so scatter() can place the whole group in one chunk.
    tie_ts = t + 3 * HOUR + 12345
    for label in ("tie A first written", "tie B second written",
                  "tie C from me", "tie D from me, same sender as C"):
        who = them if label.startswith("tie A") or label.startswith("tie B") else me
        add(key, tie_ts, who, label).dup_of = "tie-group"

    # Within-file identical pair (must NOT be deduped) + one copy in a later file
    # (which must be dropped, since it is in a HIGHER-numbered file).
    dup_ts = t + 5 * HOUR + 999
    add(key, dup_ts, them, "identical within one file").dup_of = "within-file-pair"
    add(key, dup_ts, them, "identical within one file").dup_of = "within-file-pair"
    add(key, dup_ts, them, "identical within one file").dup_of = "later-file-copy"

    # Cross-file pair differing ONLY in reactions: the key ignores reactions, so the
    # earlier-numbered file wins and the later copy is dropped.
    xf_ts = t + 7 * HOUR + 4242
    add(key, xf_ts, them, "cross file identical except reactions").dup_of = "cross-file-reactions"
    x2 = add(key, xf_ts, them, "cross file identical except reactions")
    x2.dup_of = "cross-file-reactions"
    x2.reactions = [{"reaction": "🎉", "actor": me}]

    # DST + calendar boundaries (Europe/Warsaw fixtures; verified by assert_dst).
    # Spring forward 2024-03-31 02:00->03:00: local 02:30 does not exist, so these
    # two land on 01:30 (+01) and 03:30 (+02).
    add(key, 1711845000000, them, "dst spring 01:30 CET before the gap")
    add(key, 1711848600000, me, "dst spring 03:30 CEST after the gap")
    # Fall back 2024-10-27: local 02:30 happens twice, first CEST (+02) then CET (+01).
    add(key, 1729989000000, them, "dst fall first 02:30 +0200")
    add(key, 1729992600000, me, "dst fall second 02:30 +0100")
    # Month boundary: local midnight 2024-02-01 in the fixture tz.
    add(key, 1706742000000 - 1000, them, "month boundary last second of january")
    add(key, 1706742000000 + 1000, me, "month boundary first second of february")
    add(key, 1709204400000, them, "leap day 2024-02-29")
    add(key, 1735685999000, me, "year boundary last second of 2024")
    # Local 2025-01-01 00:00:01 in Europe/Warsaw is still 2024-12-31 in UTC, so this
    # single message lands in month 2025-01 locally but 2024-12 in UTC.
    add(key, 1735686001000, them, "new year first second of 2025")

    # ---- group thread --------------------------------------------------------
    key, title, parts, n, nfiles = THREADS[1]
    t = BASE_MS + 40 * 24 * HOUR
    for i in range(n):
        sender = parts[i % len(parts)]
        add(key, t + i * 3 * HOUR, sender, WORDS[rng.randrange(len(WORDS))])

    # ---- plain 1:1 -----------------------------------------------------------
    key, title, parts, n, nfiles = THREADS[2]
    t = BASE_MS + 80 * 24 * HOUR
    for i in range(n):
        sender = me if i % 2 == 0 else parts[0]
        add(key, t + i * 5 * HOUR, sender, WORDS[rng.randrange(len(WORDS))])

    # ---- small thread with unsent/share/empty -------------------------------
    key, title, parts, n, nfiles = THREADS[3]
    t = BASE_MS + 100 * 24 * HOUR
    add(key, t, parts[0], "single file thread hello")
    add(key, t + HOUR, parts[1], "")
    add(key, t + 2 * HOUR, parts[0], "shared something",
        share={"link": "https://example.invalid/x", "share_text": "see this"})
    add(key, t + 3 * HOUR, parts[1], "never mind", is_unsent=True)
    for i in range(n - 4):
        add(key, t + (4 + i) * HOUR, parts[i % 2], WORDS[rng.randrange(len(WORDS))])

    return msgs


def scatter(thread_msgs, nfiles):
    """Scatter a thread's timeline across nfiles message_N.json files, newest-first
    within each file, with deliberate overlap and non-monotonic file order.

    Returns {file_no: [Message,...]} (each list newest-first). Sets Message.ord.
    """
    n = len(thread_msgs)
    # Chunk boundaries: file 1 holds the NEWEST messages (as in a real export), so
    # chunk i covers a contiguous, non-overlapping span of the timeline.
    fixtures = [m for m in thread_msgs if m.dup_of]
    fixture_mids = {id(m) for m in fixtures}

    # Reserve the fixture messages up front so they are excluded from the plain
    # chunking, then appended to their chosen chunk afterwards. This guarantees the
    # fixtures cannot be split across files by the overlap/non-monotonic steps.
    plain = [m for m in thread_msgs if id(m) not in fixture_mids]
    pn = len(plain)
    per = (pn + nfiles - 1) // nfiles
    by_no = {}
    for i in range(nfiles):
        end = pn - i * per
        start = max(0, end - per)
        if end > start:
            by_no[i + 1] = list(plain[start:end])

    if fixtures:
        cand = sorted(by_no)
        if len(cand) < 6:
            raise AssertionError(
                f"{nfiles} files is too few to host the dedupe fixtures "
                f"({len(fixtures)} fixture messages need >= 6 slots)")
        within_pair = [m for m in fixtures if m.dup_of == "within-file-pair"]
        later_copy = [m for m in fixtures if m.dup_of == "later-file-copy"]
        xf = [m for m in fixtures if m.dup_of == "cross-file-reactions"]
        tie_group = [m for m in fixtures if m.dup_of == "tie-group"]

        # Slots are claimed BEFORE any later appending, so generated timestamps can
        # never land on a fixture timestamp inside a different file.
        if within_pair and later_copy:
            by_no[cand[1]].extend(within_pair)   # pair in a LOW file number
            by_no[cand[3]].extend(later_copy)    # lone copy in a HIGHER one
        if len(xf) >= 2:
            by_no[cand[4]].extend(xf[:1])         # winner (lower file number)
            by_no[cand[5]].extend(xf[1:])         # loser (higher file number)
        if tie_group:
            by_no[cand[0]].extend(tie_group)      # all four ties in ONE file

    # The dedupe fixtures live in specific files by design, so the structural
    # transforms below must swap only the PLAIN messages and carry each file's
    # fixtures along with it -- otherwise a fixture would be silently dropped or
    # duplicated and the dedupe rules would stop being exercised.
    def split(chunk):
        fx = [m for m in chunk if id(m) in fixture_mids]
        pl = [m for m in chunk if id(m) not in fixture_mids]
        return pl, fx

    # non-monotonic: message_6 gets a NEWER slice than message_5. Swapping the
    # plain halves gives file 5 older content and file 6 newer content, i.e. file
    # order no longer tracks time, while each file keeps its own fixtures.
    if 5 in by_no and 6 in by_no:
        p5, f5 = split(by_no[5])
        p6, f6 = split(by_no[6])
        by_no[5] = p6 + f5
        by_no[6] = p5 + f6

    # overlap: message_4 and message_5 share a window (cross-file duplicates).
    # Only plain messages are copied, so a fixture is never duplicated here.
    if 4 in by_no and 5 in by_no:
        p5, _ = split(by_no[5])
        by_no[4] = by_no[4] + p5[-6:]

    return {fno: list(reversed(chunk)) for fno, chunk in by_no.items()}


def encode_message(m):
    """Serialize one message to its on-disk (mojibaked) JSON form. URIs are never
    mojibaked."""
    d = {"sender_name": m.encoded("sender")}
    d["timestamp_ms"] = m.ts_ms
    if m.content != "" or m.is_unsent:
        d["content"] = m.encoded("content")
    for key, items in m.media.items():
        if not items:
            continue
        if key == "sticker":
            d["sticker"] = {"uri": items[0]}
        else:
            d[key] = [{"uri": u} for u in items]
    if m.share:
        d["share"] = {"link": m.share["link"], "share_text": mojibake(m.share["share_text"])}
    if m.reactions:
        d["reactions"] = [{"reaction": mojibake(r["reaction"]), "actor": mojibake(r["actor"])}
                          for r in m.reactions]
    if m.is_unsent:
        d["is_unsent"] = True
    if m.call_duration is not None:
        d["call_duration"] = m.call_duration
    return d


def canonical_order(thread_msgs, files):
    """Apply the canonical rules to the in-memory timeline: per-file-number dedupe,
    then display sort (ts ASC, ord DESC).

    `ord` is stamped HERE, on the copy that actually survives, because the overlap
    fixture places one Message object in two files and a pre-pass would leave the
    survivor labelled with the dropped copy's position."""
    kept, seen_earlier = [], set()
    kept_at = {}                        # dedupe key -> "file:idx" of the kept copy
    dropped = []
    for fno in sorted(files):
        here = set()
        for idx, m in enumerate(files[fno]):
            k = m.key()
            if k in seen_earlier:
                # Already seen in a LOWER-numbered file: record the loser and where
                # the winner lives, then move on.
                dropped.append({
                    "id": m.mid,
                    "dropped": f"file{fno}:idx{idx}",
                    "kept": kept_at[k],
                    "reason": m.dup_of or "cross-file-duplicate",
                })
                continue
            here.add(k)
            m.ord = (fno, idx)          # the copy that is kept owns its own ord
            kept_at.setdefault(k, f"file{fno}:idx{idx}")
            kept.append(m)
        seen_earlier |= here
    kept.sort(key=lambda m: (m.ts_ms, -m.ord[0], -m.ord[1]))
    return kept, dropped


def write_export(out_root, msgs, files_by_thread, tz):
    exp = out_root / "export"
    if exp.exists():
        shutil.rmtree(exp)
    exp.mkdir(parents=True)

    # decoys at the export root: a scanner globbing **/*.json must ignore these
    (exp / "connections.json").write_text(json.dumps(
        {"connections": [{"username": "mira_okonkwo_4821"}]}, indent=2), encoding="utf-8")
    (exp / "settings.json").write_text(json.dumps(
        {"setting": "not_a_thread"}, indent=2), encoding="utf-8")

    inbox = exp / "your_instagram_activity" / "messages" / "inbox"
    for key, title, parts, _n, _nf in THREADS:
        tdir = inbox / key
        tdir.mkdir(parents=True, exist_ok=True)
        (tdir / "photos").mkdir(exist_ok=True)
        (tdir / "videos").mkdir(exist_ok=True)
        (tdir / "audio").mkdir(exist_ok=True)
        (tdir / "gifs").mkdir(exist_ok=True)
        (tdir / "files").mkdir(exist_ok=True)
        files = files_by_thread[key]
        for fno, items in files.items():
            # files are newest-first already (scatter reversed each chunk)
            payload = {
                "title": mojibake(title),
                "participants": [{"name": mojibake(p)} for p in parts],
                "messages": [encode_message(m) for m in items],
            }
            (tdir / f"message_{fno}.json").write_text(
                json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=True), encoding="utf-8")

    # Media stubs. Only URIs that resolve INSIDE the thread folder are written, so a
    # traversal URI (which must be rejected at ingest time) creates no stray file.
    written = set()
    for m in msgs:
        for folder, items in m.media.items():
            for mi, uri in enumerate(items):
                if not isinstance(uri, str) or "/" not in uri:
                    continue            # bare filename: nothing to write
                path = (exp / uri).resolve()
                tdir = (inbox / m.thread).resolve()
                try:
                    path.relative_to(tdir)
                except ValueError:
                    continue            # escapes the thread folder: never materialised
                name = uri.split("/")[-1]
                if name in written:
                    continue
                # Fixtures named deleted_* are deliberately NEVER written, so the
                # "referenced but absent from the export" case is real rather than
                # asserted-but-actually-present.
                if name.startswith("deleted_"):
                    continue
                written.add(name)
                path.parent.mkdir(parents=True, exist_ok=True)
                ext = name.rsplit(".", 1)[-1]
                kind = {"png": "png", "jpg": "png", "mp4": "mp4", "m4a": "m4a",
                        "svg": "svg", "html": "html"}.get(ext, "bin")
                path.write_bytes(stub_bytes(name, kind))
    return exp


def build_expected(msgs, files_by_thread, tzname):
    tz = ZoneInfo(tzname)
    threads_out = []
    for key, title, parts, _n, nfiles in THREADS:
        tmsgs = [m for m in msgs if m.thread == key]
        files = files_by_thread[key]
        kept, dropped = canonical_order(tmsgs, files)

        months = {}
        for m in kept:
            local = datetime.fromtimestamp(m.ts_ms / 1000, tz)
            mk = f"{local.year:04d}-{local.month:02d}"
            e = months.setdefault(mk, {"key": mk, "count": 0, "first_ts_ms": m.ts_ms, "last_ts_ms": m.ts_ms})
            e["count"] += 1
            e["last_ts_ms"] = m.ts_ms

        order = []
        for m in kept:
            utc = datetime.fromtimestamp(m.ts_ms / 1000, timezone.utc)
            local = datetime.fromtimestamp(m.ts_ms / 1000, tz)
            order.append({
                "id": m.mid,
                "ts_ms": m.ts_ms,
                "ord": {"file": m.ord[0], "index": m.ord[1]},
                "sender": m.sender,
                "content": m.content,
                "utc": utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "local": local.strftime("%Y-%m-%d %H:%M:%S%z"),
                "month": f"{local.year:04d}-{local.month:02d}",
            })

        # tie pairs, computed from the in-memory data
        ties = {}
        for m in kept:
            ties.setdefault(m.ts_ms, []).append(m)
        tie_pairs = []
        for ts, group in sorted(ties.items()):
            if len(group) < 2:
                continue
            files_involved = sorted({g.ord[0] for g in group})
            tie_pairs.append({
                "ts_ms": ts,
                "files": files_involved,
                "expected_display_ids": [g.mid for g in group],
            })

        raw_count = sum(len(v) for v in files.values())

        # `dropped` comes straight out of canonical_order(), which recorded every
        # discarded copy together with the location of the copy that won. The
        # raw-vs-kept arithmetic is asserted below so the two can never drift.
        assert raw_count - len(kept) == len(dropped), (
            f"{key}: raw {raw_count} - kept {len(kept)} != {len(dropped)} dropped entries")

        # Media / hostile-path evidence, read straight off the in-memory records.
        missing_media, traversal_rejected, bare_filename, path_only, hostile = [], [], [], [], []
        for m in tmsgs:
            for folder, items in m.media.items():
                for mi, uri in enumerate(items):
                    # Keyed by (id, media index) so one message holding two hostile
                    # entries keeps them as two separate records.
                    ref = {"id": m.mid, "media_index": mi, "uri": uri}
                    name = uri.split("/")[-1]
                    if uri.startswith("..") or "/../" in uri:
                        traversal_rejected.append(dict(ref))
                        continue
                    if "/" not in uri:
                        bare_filename.append(dict(ref, note="resolved by basename fallback"))
                        continue
                    ext = name.rsplit(".", 1)[-1].lower()
                    if ext in ("html", "svg"):
                        hostile.append(dict(ref, note="must be rejected by M4's MIME allowlist"))
                    if ext in ("jpg", "jpeg") and "deleted" in name:
                        missing_media.append(dict(ref, note="referenced but not written to disk"))

        # Calendar fixtures, recorded so M3 can assert month bucketing directly.
        calendar = [
            {"id": o["id"], "utc": o["utc"], "local": o["local"], "month": o["month"],
             "fixture": "leap-day" if o["content"].startswith("leap day")
             else "year-boundary" if o["content"].startswith("year boundary")
             else "new-year" if o["content"].startswith("new year")
             else "month-boundary" if o["content"].startswith("month boundary")
             else "dst"}
            for o in order
            if o["content"].startswith(("dst ", "month boundary", "leap day", "year boundary"))
        ]

        threads_out.append({
            "assertions": {
                "dropped": dropped,
                "within_file_dupes_kept": [
                    {"id": o["id"], "ord": o["ord"]}
                    for o in order if o["content"] == "identical within one file"],
                "missing_media": missing_media,
                "traversal_rejected": traversal_rejected,
                "bare_filename_media": bare_filename,
                "hostile_media": hostile,
                "calendar_fixtures": calendar,
            },
            "key": key,
            "title": title,
            "participants": parts,
            "file_count": len(files),
            "raw_count": raw_count,
            "message_count": len(kept),
            "months": [months[k] for k in sorted(months)],
            "order": order,
            "tie_pairs": tie_pairs,
        })

    return {
        "seed": None,           # filled by main()
        "tz": tzname,
        "rules": {
            "ord": "ord = (file number, index within that file); each message_N.json stores messages newest-first",
            "display_sort": "(timestamp_ms ASC, ord DESC) - for equal timestamps the higher index within a file displays first",
            "dedupe": ("a message from file N is dropped only if its key (timestamp_ms, sender_name, "
                       "content, media URIs) already appeared in a LOWER-numbered file; identical "
                       "messages within ONE file are all kept; first-encountered copy wins; reactions "
                       "are not part of the key"),
        },
        "threads": threads_out,
    }


def assert_dst(tzname):
    """Independently verify the DST / boundary fixtures land where we claim, using
    zoneinfo directly rather than trusting the generator's own arithmetic."""
    tz = ZoneInfo(tzname)
    checks = [
        # Spring forward 2024-03-31 02:00->03:00: local 02:30 never happens.
        # 2024-03-31T00:30Z is 01:30 CET (+01), still before the gap.
        (1711845000000, "2024-03-31 01:30:00+0100"),
        # 2024-03-31T01:30Z is already 03:30 CEST (+02) -- 02:00-02:59 does not exist.
        (1711848600000, "2024-03-31 03:30:00+0200"),
        # Fall back 2024-10-27: local 02:30 happens TWICE, first CEST (+02) then CET (+01).
        (1729989000000, "2024-10-27 02:30:00+0200"),
        (1729992600000, "2024-10-27 02:30:00+0100"),
    ]
    for ts, want in checks:
        got = datetime.fromtimestamp(ts / 1000, tz).strftime("%Y-%m-%d %H:%M:%S%z")
        assert got == want, f"DST fixture {ts}: expected {want}, got {got}"

    # Prove the nonexistent spring local time really is unrepresentable: no UTC
    # instant on 2024-03-31 renders as local 02:xx.
    seen = set()
    for h in range(0, 24):
        ts = datetime(2024, 3, 31, h, 30, tzinfo=timezone.utc).timestamp() * 1000
        seen.add(datetime.fromtimestamp(ts / 1000, tz).strftime("%H:%M"))
    assert "02:30" not in seen, f"spring-forward gap is broken: {sorted(seen)}"
    # And on the fall-back day local 02:30 appears with two different offsets.
    offs = set()
    for h in range(0, 24):
        ts = datetime(2024, 10, 27, h, 30, tzinfo=timezone.utc).timestamp() * 1000
        lt = datetime.fromtimestamp(ts / 1000, tz)
        if lt.strftime("%H:%M") == "02:30":
            offs.add(lt.strftime("%z"))
    assert offs == {"+0200", "+0100"}, f"fall-back should double 02:30, saw {offs}"

    # Month boundary: local midnight 2024-02-01 in this tz. The two messages sit 2s
    # either side of it, so they must fall in different month buckets.
    b0 = datetime.fromtimestamp((1706742000000 - 1000) / 1000, tz)
    b1 = datetime.fromtimestamp((1706742000000 + 1000) / 1000, tz)
    assert b0.strftime("%Y-%m-%d %H:%M:%S") == "2024-01-31 23:59:59", b0
    assert b1.strftime("%Y-%m-%d %H:%M:%S") == "2024-02-01 00:00:01", b1
    assert (b0.year, b0.month) == (2024, 1), b0
    assert (b1.year, b1.month) == (2024, 2), b1
    # Leap day
    assert datetime.fromtimestamp(1709204400000 / 1000, tz).strftime("%m-%d %H:%M") == "02-29 12:00"
    print(f"  dst/boundary assertions ok ({tzname})")


def assert_mojibake(timeline):
    """Every string we mojibake must satisfy fix(mojibake(s)) == s, and every string
    stored raw must survive fix() untouched."""
    checked = 0
    for m in timeline:
        for s in (m.sender, m.content):
            if not s:
                continue
            if "content" in m.raw_fields or "sender" in m.raw_fields:
                assert fix(s) == s, f"{m.mid}: raw field must survive fix(): {s!r}"
            else:
                assert fix(mojibake(s)) == s, f"{m.mid}: roundtrip failed for {s!r}"
            checked += 1
        for r in m.reactions:
            assert fix(mojibake(r["reaction"])) == r["reaction"]
            assert fix(mojibake(r["actor"])) == r["actor"]
            checked += 2
        if m.share:
            assert fix(mojibake(m.share["share_text"])) == m.share["share_text"]
            checked += 1
    for s in UNICODE_SAMPLES:
        assert fix(mojibake(s)) == s, f"unicode sample roundtrip failed: {s!r}"
        assert fix(s) == s, f"unicode sample must survive fix(): {s!r}"
        checked += 1
    print(f"  mojibake assertions ok ({checked} strings)")


def assert_calendar_fixtures(timeline, tzname):
    """The year boundary, leap day and the tz-vs-UTC month split must exist, or a
    later edit could drop them silently. Kept as hard assertions, not comments."""
    texts = {m.content for m in timeline}
    required = {
        "leap day 2024-02-29": "leap day",
        "year boundary last second of 2024": "year boundary",
        "new year first second of 2025": "new year",
        "dst spring 01:30 CET before the gap": "dst spring",
        "dst fall first 02:30 +0200": "dst fall",
    }
    for label, needle in required.items():
        assert any(t.startswith(needle) for t in texts), f"missing fixture: {label}"
    # exactly one of each
    for needle in ("leap day", "year boundary", "new year"):
        assert sum(1 for t in texts if t.startswith(needle)) == 1, needle

    # The new-year fixture must be 2025-01 in this tz and 2024-12 in UTC.
    tz = ZoneInfo(tzname)
    ny = [m for m in timeline if m.content.startswith("new year")]
    assert len(ny) == 1, "new-year fixture missing"
    m = ny[0]
    local = datetime.fromtimestamp(m.ts_ms / 1000, tz)
    utc = datetime.fromtimestamp(m.ts_ms / 1000, timezone.utc)
    assert m.ts_ms == 1735686001000, m.ts_ms
    assert local.strftime("%Y-%m-%d %H:%M:%S") == "2025-01-01 00:00:01", local
    assert utc.strftime("%Y-%m-%d %H:%M:%S") == "2024-12-31 23:00:01", utc
    print(f"  new-year split: local month {local:%Y-%m} vs UTC month {utc:%Y-%m} ({tzname})")
    print("  calendar fixtures present (leap day, year boundary, new year, DST pairs)")


def assert_no_cross_file_ties(timeline, files_by_thread):
    """A timestamp may appear in more than one file ONLY if those entries are
    duplicates of one another (same dedupe key) -- that is the overlap fixture.
    Two DISTINCT messages sharing a timestamp across files would make the display
    tie-break ambiguous, so that is a generator bug."""
    for key, files in files_by_thread.items():
        ts_keys = {}
        for fno, items in files.items():
            for m in items:
                ts_keys.setdefault(m.ts_ms, {}).setdefault(m.key(), set()).add(fno)
        bad = []
        for ts, bykey in ts_keys.items():
            distinct_files = set()
            for _k, fs in bykey.items():
                distinct_files |= fs
            if len(distinct_files) > 1 and len(bykey) > 1:
                bad.append(ts)
        assert not bad, f"{key}: distinct messages share a timestamp across files: {bad[:3]}"
    print("  no cross-file ties between distinct messages")


def write_expected(path, expected):
    path.write_text(json.dumps(expected, indent=2, ensure_ascii=True, sort_keys=False) + "\n",
                    encoding="utf-8")


def tree_digest(root):
    """sha256 over every file plus its relative path, for determinism checks."""
    root = Path(root)
    if root.is_file():
        # A single file (e.g. expected.json): hash its contents.
        return hashlib.sha256(root.read_bytes()).hexdigest()
    if not root.exists():
        return "<missing>"
    h = hashlib.sha256()
    files = sorted(p for p in root.rglob("*") if p.is_file())
    if not files:
        return "<empty>"
    for p in files:
        h.update(str(p.relative_to(root)).encode("utf-8"))
        h.update(b"\0")
        h.update(hashlib.sha256(p.read_bytes()).digest())
    return h.hexdigest()


def generate(out_dir, seed, tzname):
    rng = random.Random(seed)
    timeline = build_timeline(rng)

    files_by_thread = {}
    for key, _title, _parts, _n, nfiles in THREADS:
        tmsgs = [m for m in timeline if m.thread == key]
        files = scatter(tmsgs, nfiles)
        # assign ord = (file_no, index_in_file)
        for fno, items in files.items():
            for idx, m in enumerate(items):
                m.ord = (fno, idx)
        files_by_thread[key] = files

    # canonical_order re-stamps ord on the surviving copy, so run it before
    # expected.json is built.
    for key, _title, _parts, _n, _nfiles in THREADS:
        tmsgs = [m for m in timeline if m.thread == key]
        canonical_order(tmsgs, files_by_thread[key])

    assert_mojibake(timeline)
    assert_calendar_fixtures(timeline, tzname)
    assert_no_cross_file_ties(timeline, files_by_thread)
    assert_dst(tzname)

    exp = write_export(out_dir, timeline, files_by_thread, tzname)
    expected = build_expected(timeline, files_by_thread, tzname)
    expected["seed"] = seed
    write_expected(out_dir / "expected.json", expected)
    return exp, expected


def cmd_check(out_dir, seed, tzname):
    """Generate twice into temp dirs and compare digests."""
    digs = []
    for i in range(2):
        tmp = Path(tempfile.mkdtemp(prefix="iggen-"))
        try:
            generate(tmp, seed, tzname)
            digs.append((tree_digest(tmp / "export"), tree_digest(tmp / "expected.json")))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    if digs[0] != digs[1]:
        print("FAIL: two runs differ")
        print("  run1:", digs[0])
        print("  run2:", digs[1])
        return 1
    print(f"determinism ok: {digs[0][0][:16]} (export), {digs[0][1][:16]} (expected.json)")
    return 0


def report_assertions(expected):
    """Print the dedupe-rule evidence recorded in expected.json: which copies were
    dropped, which identical pairs were KEPT, and the tie display order."""
    for t in expected["threads"]:
        ids = [o["id"] for o in t["order"]]
        counts = {}
        for i in ids:
            counts[i] = counts.get(i, 0) + 1
        print(f"  {t['key']}: {t['raw_count']} raw -> {t['message_count']} kept "
              f"({t['raw_count'] - t['message_count']} dropped) in {t['file_count']} files")

    big = expected["threads"][0]
    byid = {o["id"]: o for o in big["order"]}

    # (a) within-file identical pair must BOTH survive
    print("\n  within-file identical pair (must be KEPT twice):")
    for o in big["order"]:
        if o["content"] == "identical within one file":
            print(f"    id={o['id']} ts={o['ts_ms']} ord=file{o['ord']['file']}:idx{o['ord']['index']}")
    kept = sum(1 for o in big["order"] if o["content"] == "identical within one file")
    print(f"    -> {kept} kept (expected 2: one in the pair's file, "
          f"the lone copy in a HIGHER-numbered file is dropped)")
    assert kept == 2, "within-file identical pair was not kept intact"

    # every dropped copy, with the location of the copy that won
    a = big["assertions"]
    print("\n  every dropped copy (dropped -> kept):")
    for d in a["dropped"]:
        print(f"    id={d['id']}  {d['dropped']:12} -> {d['kept']:12} {d['reason']}")
    n_overlap = sum(1 for d in a["dropped"] if d["reason"] == "cross-file-duplicate")
    print(f"    -> {len(a['dropped'])} dropped = {n_overlap} overlap duplicates "
          f"+ {len(a['dropped']) - n_overlap} rule fixtures; "
          f"raw {big['raw_count']} - kept {big['message_count']} == {len(a['dropped'])}")
    assert big["raw_count"] - big["message_count"] == len(a["dropped"]), \
        "raw - kept != number of dropped copies"

    # (b) cross-file pair differing only in reactions: lower file number wins
    print("\n  cross-file pair differing only in reactions (lower file wins):")
    xf = [o for o in big["order"] if o["content"] == "cross file identical except reactions"]
    for o in xf:
        print(f"    id={o['id']} ord=file{o['ord']['file']}:idx{o['ord']['index']}")
    print(f"    -> {len(xf)} kept (expected 1)")
    assert len(xf) == 1, "cross-file reactions duplicate should be deduped to 1"

    # tie display order: higher index within the file first
    # missing / hostile / traversal evidence
    a = big["assertions"]
    print("\n  media edge cases (keyed by id + media_index):")
    for m in a["missing_media"]:
        print(f"    missing   id={m['id']} [{m['media_index']}] {m['note']}")
    for m in a["traversal_rejected"]:
        print(f"    traversal id={m['id']} [{m['media_index']}] uri={m['uri']}")
    for m in a["bare_filename_media"]:
        print(f"    bare-name id={m['id']} [{m['media_index']}] {m['uri']}")
    for m in a["hostile_media"]:
        print(f"    hostile   id={m['id']} [{m['media_index']}] {m['uri'].rsplit('/', 1)[-1]}")
    assert a["missing_media"] and a["traversal_rejected"] and a["bare_filename_media"] \
        and a["hostile_media"], "media edge-case fixtures missing"

    print("\n  tie pairs (display = higher index in file first):")
    for tie in big["tie_pairs"]:
        same_file = len(set(tie["files"])) == 1
        print(f"    ts={tie['ts_ms']} files={tie['files']} "
              f"{'single-file OK' if same_file else '*** CROSS-FILE ***'}")
        for mid in tie["expected_display_ids"]:
            o = byid[mid]
            print(f"      {mid} ord=file{o['ord']['file']}:idx{o['ord']['index']} "
                  f"sender={o['sender']!r} content={o['content'][:28]!r}")
        idxs = [byid[m]["ord"]["index"] for m in tie["expected_display_ids"]]
        assert idxs == sorted(idxs, reverse=True), \
            f"tie order wrong: {idxs} should be descending within one file"
    print("\n  dedupe-rule assertions OK")


def cmd_verify(out_dir, seed, tzname):
    """Regenerate into a temp dir and compare against what is committed on disk."""
    tmp = Path(tempfile.mkdtemp(prefix="iggen-"))
    try:
        generate(tmp, seed, tzname)
        bad = []
        for rel in ("export", "expected.json"):
            a, b = tmp / rel, out_dir / rel
            if tree_digest(a) != (tree_digest(b) if b.exists() else None):
                bad.append(rel)
        if bad:
            print("FAIL: fake-data/ has drifted from the generator for:", ", ".join(bad))
            print("      run: python3 tools/gen_fake_data.py --out fake-data --seed", seed)
            return 1
        print("verify ok: fake-data/ matches the generator")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=Path("fake-data"))
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--tz", default=DEFAULT_TZ, help="IANA tz for month keys and local strings")
    ap.add_argument("--check", action="store_true", help="generate twice and compare digests")
    ap.add_argument("--verify", action="store_true", help="fail if --out has drifted from the generator")
    a = ap.parse_args()

    if a.check:
        return cmd_check(a.out, a.seed, a.tz)
    if a.verify:
        return cmd_verify(a.out, a.seed, a.tz)

    exp, expected = generate(a.out, a.seed, a.tz)
    total = sum(t["message_count"] for t in expected["threads"])
    raw = sum(t["raw_count"] for t in expected["threads"])
    print(f"wrote {exp} (seed={a.seed}, tz={a.tz})")
    print(f"  {len(expected['threads'])} threads, {raw} raw messages, {total} after dedupe")
    print(f"wrote {a.out / 'expected.json'}")
    print("\ndedupe-rule assertions:")
    report_assertions(expected)
    return 0


if __name__ == "__main__":
    sys.exit(main())
