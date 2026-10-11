/**
 * DOM rendering.
 *
 * Hard rule: no innerHTML anywhere. Every node is created with createElement and
 * every piece of chat-derived text is set with textContent, so a message containing
 * markup or a script tag is displayed as literal characters.
 */

import {
  type UiMessage,
  type UiMedia,
  clockTime,
  dayKey,
  dayHeading,
  isAudioKind,
  isImageKind,
  isSafeMediaUrl,
  isVideoKind,
  splitLinks,
  splitSnippet,
  withDayBreaks,
} from "./logic";

/** Create an element with optional class and text. */
export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className?: string,
  text?: string,
): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}

/** Anchor for a link run. Only http(s) ever reaches this. */
function anchor(href: string): HTMLAnchorElement {
  const a = document.createElement("a");
  a.href = href;
  a.rel = "noopener noreferrer";
  a.target = "_blank";
  return a;
}

/**
 * Append text to `parent`, turning bare http(s) URLs into links.
 *
 * textContent for the non-link runs means a message body is never interpreted as
 * markup.
 */
export function appendText(parent: HTMLElement, text: string): void {
  for (const run of splitLinks(text)) {
    if (run.href) {
      // splitLinks only yields http(s) hrefs, but re-check before trusting it.
      const u = safeHttp(run.href);
      if (u) parent.append(anchor(u));
      else parent.append(document.createTextNode(run.text));
    } else {
      parent.append(document.createTextNode(run.text));
    }
  }
}

function safeHttp(href: string): string | null {
  try {
    const u = new URL(href);
    return u.protocol === "http:" || u.protocol === "https:" ? u.href : null;
  } catch {
    return null;
  }
}

/**
 * Audio and video cannot be played yet.
 *
 * The igmedia: handler serves correct Range/206 responses, but WebKitGTK/GStreamer
 * will not load a custom URI scheme for <audio>/<video> -- only http(s). Rendering
 * the native player therefore shows a broken "Error" control. Until media transport
 * is fixed (loopback server or blob:), say so plainly instead of shipping a control
 * that cannot work. Flip this once CSP allows it.
 */
const MEDIA_PLAYBACK_AVAILABLE = false;

function player(tag: "audio" | "video", url: string): HTMLElement {
  const p = document.createElement(tag);
  p.controls = true;
  p.preload = "none";
  p.src = url;
  return p;
}

/** Honest placeholder: names the kind and says why it will not play. */
function mediaStub(kind: string): HTMLElement {
  const label = kind === "audio" ? "Voice note" : kind === "video" ? "Video" : kind;
  return el("div", "voice", `\u25C0 ${label} \u00b7 playback not available yet`);
}

/** Render one media reference. Returns null when it cannot be shown. */
export function renderMedia(m: UiMedia): HTMLElement | null {
  if (!m.available || !isSafeMediaUrl(m.url)) {
    // Sanitised: name the kind, never the URI or path.
    return el("div", "dim", `(${m.kind.replace(/_/g, " ")} not available)`);
  }
  const url = m.url as string;
  if (isImageKind(m.kind)) {
    const img = document.createElement("img");
    img.loading = "lazy";
    img.alt = m.kind === "sticker" ? "sticker" : "photo";
    img.src = url;
    img.className = m.kind === "sticker" ? "sticker" : "photo";
    if (m.kind !== "sticker") {
      // Click to inspect full-screen; the src is re-used, never re-fetched.
      img.addEventListener("click", () => openLightbox(img));
    }
    return img;
  }
  if (isAudioKind(m.kind)) {
    return MEDIA_PLAYBACK_AVAILABLE ? player("audio", url) : mediaStub("audio");
  }
  if (isVideoKind(m.kind)) {
    return MEDIA_PLAYBACK_AVAILABLE ? player("video", url) : mediaStub("video");
  }
  return el("div", "dim", `(${m.kind} cannot be previewed)`);
}

/** Show a photo full-screen in the <dialog> overlay. */
export function openLightbox(img: HTMLImageElement): void {
  const box = document.getElementById("lightbox") as HTMLDialogElement | null;
  const target = document.getElementById("lightbox-img");
  if (!box || !target) return;
  target.replaceChildren();
  const full = document.createElement("img");
  full.src = img.src;
  full.alt = img.alt;
  target.append(full);
  box.showModal();
}

/** Hide the lightbox. */
export function closeLightbox(): void {
  const box = document.getElementById("lightbox") as HTMLDialogElement | null;
  if (box && box.open) box.close();
}

/** Arch-shaped avatar used in the sidebar and chat header. */
export function avatarChip(name: string): HTMLElement {
  return el("div", "arch", (name.trim()[0] ?? "?").toUpperCase());
}

/** Only this many leading bubbles animate; see .msg.enter in styles.css. */
const STAGGER_CAP = 12;

export function renderMessage(
  m: UiMessage,
  me: string,
  grouped: boolean,
  hasNext: boolean,
  showSender: boolean,
  index: number,
  tz: string,
): HTMLElement {
  const mine = me !== "" && m.sender === me;
  const cls = ["msg"];
  if (mine) cls.push("me");
  if (grouped) cls.push("g-prev");
  if (hasNext) cls.push("g-next");
  if (m.is_unsent) cls.push("unsent");
  if (index < STAGGER_CAP) cls.push("enter");

  const bubble = el("div", cls.join(" "));
  if (index < STAGGER_CAP) bubble.style.setProperty("--i", String(index));
  bubble.dataset.messageId = String(m.id);
  bubble.dataset.day = dayKey(m.ts_ms, tz);

  if (!mine && !grouped && showSender) bubble.append(el("span", "sender", m.sender));

  if (m.is_unsent) {
    bubble.append(document.createTextNode("Message unsent"));
  } else if (m.content) {
    appendText(bubble, m.content);
  }

  for (const media of m.media) {
    const node = renderMedia(media);
    if (node) bubble.append(node);
  }

  if (m.reactions.length > 0) {
    const rx = el("div", "react");
    for (const r of m.reactions) rx.append(el("span", "", `${r.reaction} ${r.actor}`));
    bubble.append(rx);
  }

  // Timestamp only on the last bubble of a run, so a group reads as one block.
  if (!hasNext) {
    const time = el("time", "", clockTime(m.ts_ms));
    time.dateTime = new Date(m.ts_ms).toISOString();
    time.title = new Date(m.ts_ms).toString();
    bubble.append(time);
  }
  return bubble;
}

/** True when a message can take part in sender grouping. */
function groupable(m: UiMessage): boolean {
  return !m.is_unsent && m.call_duration == null && m.media.length === 0;
}

/** Render a month's messages with day separators and sender grouping. */
export function renderMonth(
  messages: UiMessage[],
  me: string,
  showSenders: boolean,
  tz: string,
): DocumentFragment {
  const frag = document.createDocumentFragment();
  // Mirrors GROUP_WINDOW_MIN in the design.
  const GROUP_WINDOW_MS = 5 * 60 * 1000;
  let prev: UiMessage | null = null;
  let index = 0;

  const rows = withDayBreaks(messages);
  for (let i = 0; i < rows.length; i++) {
    const m = rows[i] as UiMessage & { dayBreak?: boolean };
    if (m.dayBreak) {
      frag.append(el("div", "day", dayHeading(m.ts_ms)));
      prev = null;
      index = 0;
      continue;
    }
    const next = rows[i + 1] as (UiMessage & { dayBreak?: boolean }) | undefined;
    const joined =
      next !== undefined && !next.dayBreak && groupable(m) && groupable(next) &&
      m.sender === next.sender && next.ts_ms - m.ts_ms < GROUP_WINDOW_MS;
    const grouped =
      prev !== null && groupable(prev) && groupable(m) && prev.sender === m.sender &&
      m.ts_ms - prev.ts_ms < GROUP_WINDOW_MS;
    frag.append(renderMessage(m, me, grouped, joined, showSenders, index, tz));
    index += 1;
    prev = m;
  }
  return frag;
}

/**
 * Render one search result: sender, snippet, timestamp.
 *
 * The snippet is chat content, so every run goes in with textContent and the
 * highlighted runs are wrapped in a `mark` element created here. Never innerHTML.
 */
export function renderSearchHit(sender: string, snippet: string, tsMs: number): HTMLElement {
  const row = el("div", "hit");
  row.append(el("small", "", `${sender} \u00b7 ${new Date(tsMs).toLocaleString()}`));

  const body = el("div", "snip");
  if (snippet === "") {
    body.textContent = "(no text)";
  } else {
    for (const run of splitSnippet(snippet)) {
      if (run.hit) {
        const m = document.createElement("mark");
        m.textContent = run.text;
        body.append(m);
      } else {
        body.append(document.createTextNode(run.text));
      }
    }
  }
  row.append(body);
  return row;
}
