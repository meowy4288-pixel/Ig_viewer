#!/usr/bin/env python3
"""
Derived from the original ig_chat_rebuild.py (~/Documents/the_files/), which is left
untouched. Two changes were made for this project:

  * Dedupe is now per-file-number instead of one global `seen` set. A message from
    file N is dropped only if its key already appeared in a LOWER-numbered file, so
    identical messages inside ONE file are all kept.
  * Added --dump-order, which prints the canonical display order as TSV so it can be
    diffed against tools/gen_fake_data.py's expected.json (see tools/parity_check.py).

Rebuild ONE Instagram DM thread from the official JSON export.

Fixes the usual export problems:
  * messages come newest-first and split across message_1.json, message_2.json...
    -> merged, de-duplicated, sorted oldest-first by timestamp_ms
  * text is mojibake (UTF-8 read as latin-1) -> repaired (emoji, accents, etc.)
  * photos/videos/audio/stickers/shares/reactions/unsent msgs are silently dropped
    by many viewers -> all rendered, media copied next to the output

Usage:
  python ig_chat_rebuild.py THREAD_DIR --root EXPORT_ROOT --out OUT_DIR --me "Your Name" --tz Asia/Kolkata

  THREAD_DIR   e.g. EXPORT/your_instagram_activity/messages/inbox/friendname_123456789
  --root       the folder you unzipped (media paths in the JSON are relative to it)
  --out        output folder (chat.html, chat.txt and media/ are created inside)
  --me         your display name exactly as it appears in the export (right-aligns your bubbles)
  --tz         IANA timezone for displayed times (default: UTC)
  --split      one HTML page per month + index.html (use this for long chats)
"""
import argparse, json, shutil, sys, html
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


def fix(s):
    """Instagram exports UTF-8 bytes as latin-1 escapes; undo that."""
    if not isinstance(s, str):
        return s
    try:
        return s.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


def load_messages(thread_dir):
    files = sorted(thread_dir.glob("message_*.json"))
    if not files:
        sys.exit(f"No message_*.json found in {thread_dir}")
    msgs, title, participants = [], "", []
    # Keys are bucketed by file number: a message is dropped only when its key was
    # already seen in a LOWER-numbered file. Identical messages inside one file are
    # therefore all kept (the first-encountered copy in that file survives).
    seen_by_file = {}
    for f in files:
        fi = int(f.name.split("_")[1].split(".")[0])
        data = json.loads(f.read_text(encoding="utf-8"))
        title = title or fix(data.get("title", ""))
        if not participants:
            participants = [fix(p["name"]) for p in data.get("participants", [])]
        earlier = set()
        for k in seen_by_file.values():
            earlier |= k
        here = set()
        for m in data.get("messages", []):
            key = (m.get("timestamp_ms"), m.get("sender_name"), m.get("content"),
                   json.dumps(m.get("photos") or m.get("videos") or m.get("audio_files") or "", sort_keys=True))
            if key in earlier:
                continue
            here.add(key)
            msgs.append(m)
        seen_by_file[fi] = here
    # oldest first; stable sort keeps original relative order for identical timestamps
    msgs.sort(key=lambda m: m.get("timestamp_ms", 0))
    return title, participants, msgs


def dump_order(thread_dir, out_path):
    """Print the canonical display order so it can be diffed against expected.json.

    Ordering rule (same canonical rules as tools/gen_fake_data.py's expected.json):
    apply per-file-number dedupe first (identical messages within ONE file are all
    kept; a copy is dropped only if its key appeared in a LOWER-numbered file), then
    sort oldest-first by timestamp_ms with ties broken by HIGHER ord first, because
    each file stores its messages newest-first. `ord` = (file number, index in file).
    """
    files = sorted(thread_dir.glob("message_*.json"),
                   key=lambda f: int(f.name.split("_")[1].split(".")[0]))
    rows = []
    earlier = set()
    for f in files:
        # `ord` uses the file NUMBER, so message_2.json beats message_10.json.
        fi = int(f.name.split("_")[1].split(".")[0])
        data = json.loads(f.read_text(encoding="utf-8"))
        here = set()
        for idx, m in enumerate(data.get("messages", [])):
            # Same key as load_messages: reactions/share are deliberately excluded.
            key = (m.get("timestamp_ms"), m.get("sender_name"), m.get("content"),
                   json.dumps(m.get("photos") or m.get("videos") or m.get("audio_files") or "",
                              sort_keys=True))
            if key in earlier:
                continue
            here.add(key)
            rows.append({
                "ts_ms": m.get("timestamp_ms", 0),
                "ord": (fi, idx),
                "sender": fix(m.get("sender_name", "Unknown")),
                "content": fix(m.get("content") or ""),
            })
        earlier |= here
    rows.sort(key=lambda r: (r["ts_ms"], -r["ord"][0], -r["ord"][1]))
    lines = []
    for r in rows:
        lines.append("%d\t%d\t%d\t%s\t%s" % (
            r["ts_ms"], r["ord"][0], r["ord"][1],
            r["sender"].replace("\t", " "),
            r["content"].replace("\t", " ").replace("\n", "\\n")))
    Path(out_path).write_text("\n".join(lines) + "\n", encoding="utf-8")


def find_media(uri, root, thread_dir):
    p = root / uri
    if p.exists():
        return p
    # some exports use a different prefix; fall back to filename inside the thread folder
    for sub in ("photos", "videos", "audio", "files", "gifs", ""):
        q = thread_dir / sub / Path(uri).name
        if q.exists():
            return q
    return None


def copy_media(uri, root, thread_dir, out_media):
    src = find_media(uri, root, thread_dir)
    if not src:
        return None, False
    out_media.mkdir(parents=True, exist_ok=True)
    dst = out_media / src.name
    if not dst.exists():
        shutil.copy2(src, dst)
    return f"media/{src.name}", True


def render(title, msgs, root, thread_dir, out, me, tz, split=False):
    out_media = out / "media"
    pages, txt_parts = {}, []   # pages: "YYYY-MM" -> list of html chunks
    html_parts = None
    last_day = None
    missing = 0

    for m in msgs:
        ts = datetime.fromtimestamp(m.get("timestamp_ms", 0) / 1000, tz)
        day = ts.strftime("%A, %d %B %Y")
        html_parts = pages.setdefault(ts.strftime("%Y-%m") if split else "all", [])
        if day != last_day:
            last_day = day
            html_parts.append(f'<div class="day">{day}</div>')
            txt_parts.append(f"\n===== {day} =====")
        sender = fix(m.get("sender_name", "Unknown"))
        mine = (sender == me)
        clock = ts.strftime("%H:%M")
        body_html, body_txt = [], []

        if m.get("is_unsent"):
            body_html.append("<i>Message unsent</i>")
            body_txt.append("[unsent message]")

        content = fix(m.get("content"))
        if content and not m.get("is_unsent"):
            body_html.append(html.escape(content).replace("\n", "<br>"))
            body_txt.append(content)

        for key, tag in (("photos", "img"), ("videos", "video"), ("gifs", "img"),
                         ("audio_files", "audio")):
            for item in m.get(key, []) or []:
                uri = item.get("uri", "")
                rel, ok = copy_media(uri, root, thread_dir, out_media)
                if not ok:
                    missing += 1
                    body_html.append(f"<i>[{key[:-1]} not found in export: {html.escape(Path(uri).name)}]</i>")
                    body_txt.append(f"[{key[:-1]} missing: {Path(uri).name}]")
                    continue
                if tag == "img":
                    body_html.append(f'<a href="{rel}"><img src="{rel}"></a>')
                elif tag == "video":
                    body_html.append(f'<video controls src="{rel}"></video>')
                else:
                    body_html.append(f'<audio controls src="{rel}"></audio>')
                body_txt.append(f"[{key[:-1]}: {rel}]")

        st = m.get("sticker")
        if st and st.get("uri"):
            rel, ok = copy_media(st["uri"], root, thread_dir, out_media)
            if ok:
                body_html.append(f'<img class="sticker" src="{rel}">')
            body_txt.append("[sticker]")

        sh = m.get("share")
        if sh:
            link = sh.get("link", "")
            text = fix(sh.get("share_text", ""))
            body_html.append(f'Shared: <a href="{html.escape(link)}">{html.escape(link) or "link"}</a>'
                             + (f"<br>{html.escape(text)}" if text else ""))
            body_txt.append(f"[shared] {link} {text}".strip())

        if not body_html:
            body_html.append("<i>(no content in export)</i>")
            body_txt.append("(no content in export)")

        reacts = m.get("reactions") or []
        react_html = ""
        if reacts:
            r = " ".join(f"{fix(x.get('reaction',''))} <small>{html.escape(fix(x.get('actor','')))}</small>"
                         for x in reacts)
            react_html = f'<div class="react">{r}</div>'
            body_txt.append("  reactions: " + ", ".join(
                f"{fix(x.get('reaction',''))} ({fix(x.get('actor',''))})" for x in reacts))

        cls = "me" if mine else "them"
        html_parts.append(
            f'<div class="row {cls}"><div class="bubble"><div class="who">{html.escape(sender)}</div>'
            f'{"<br>".join(body_html)}{react_html}<div class="time">{clock}</div></div></div>')
        txt_parts.append(f"[{ts.strftime('%Y-%m-%d %H:%M')}] {sender}: " + " | ".join(body_txt))

    css = """
    body{font-family:system-ui,sans-serif;background:#111;color:#eee;max-width:720px;margin:0 auto;padding:12px}
    h1{font-size:1.2rem;text-align:center}
    .day{text-align:center;color:#999;margin:18px 0 8px;font-size:.85rem}
    .row{display:flex;margin:4px 0}.row.me{justify-content:flex-end}
    .bubble{max-width:75%;padding:8px 12px;border-radius:16px;background:#262626;word-wrap:break-word}
    .me .bubble{background:#3797f0}
    .who{font-size:.7rem;opacity:.7}.time{font-size:.65rem;opacity:.6;text-align:right;margin-top:2px}
    .react{margin-top:4px}img,video{max-width:100%;border-radius:10px;margin-top:4px}
    img.sticker{max-width:120px}a{color:#9cf}
    """
    def wrap(t, body, nav=""):
        return (f"<!doctype html><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
                f"<title>{html.escape(t)}</title><style>{css}</style><h1>{html.escape(t)}</h1>{nav}"
                + "\n".join(body))

    if split:
        keys = sorted(pages)
        for i, k in enumerate(keys):
            prev = f'<a href="chat-{keys[i-1]}.html">&larr; {keys[i-1]}</a>' if i else ""
            nxt = f'<a href="chat-{keys[i+1]}.html">{keys[i+1]} &rarr;</a>' if i < len(keys) - 1 else ""
            nav = (f'<div class="day">{prev} &nbsp; <a href="index.html">index</a> &nbsp; {nxt}</div>')
            (out / f"chat-{k}.html").write_text(wrap(f"{title} - {k}", pages[k], nav), encoding="utf-8")
        links = "".join(f'<div class="day"><a href="chat-{k}.html">{k}</a></div>' for k in keys)
        (out / "index.html").write_text(wrap(title, [links]), encoding="utf-8")
    else:
        (out / "chat.html").write_text(wrap(title, pages.get("all", [])), encoding="utf-8")
    (out / "chat.txt").write_text("\n".join(txt_parts), encoding="utf-8")
    return missing


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("thread_dir", type=Path)
    ap.add_argument("--root", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--me", default="")
    ap.add_argument("--tz", default="UTC")
    ap.add_argument("--split", action="store_true",
                    help="write one HTML page per month plus index.html (recommended for long chats)")
    ap.add_argument("--dump-order", metavar="TSV",
                    help="write canonical display order as TSV and exit (no HTML/chat.txt)")
    a = ap.parse_args()

    title, participants, msgs = load_messages(a.thread_dir)
    if a.dump_order:
        dump_order(a.thread_dir, a.dump_order)
        print(f"{len(msgs)} messages -> {a.dump_order}")
        return
    a.out.mkdir(parents=True, exist_ok=True)
    missing = render(title or a.thread_dir.name, msgs, a.root, a.thread_dir, a.out, a.me, ZoneInfo(a.tz), a.split)
    print(f"{len(msgs)} messages -> {a.out}")
    print(f"first: {datetime.fromtimestamp(msgs[0]['timestamp_ms']/1000, ZoneInfo(a.tz))}" if msgs else "empty")
    print(f"last:  {datetime.fromtimestamp(msgs[-1]['timestamp_ms']/1000, ZoneInfo(a.tz))}" if msgs else "")
    if missing:
        print(f"WARNING: {missing} media file(s) referenced in the JSON were not in the export")


if __name__ == "__main__":
    main()
