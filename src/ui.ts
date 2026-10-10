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
  if (isVideoKind(m.kind)) {
    const v = document.createElement("video");
    v.controls = true;
    v.preload = "metadata";
    v.src = url;
    return v;
  }
  if (isAudioKind(m.kind)) {
    const a = document.createElement("audio");
    a.controls = true;
    a.preload = "none";
    a.src = url;
    return a;
  }
  return el("div", "dim", `(${m.kind} cannot be previewed)`);
}

/** Show a photo full-screen. Sets src/textContent only; no markup. */
export function openLightbox(img: HTMLImageElement): void {
  const box = document.getElementById("lightbox");
  const target = document.getElementById("lightbox-img") as HTMLImageElement | null;
  if (!box || !target) return;
  target.src = img.src;
  target.alt = img.alt;
  box.hidden = false;
}

/** Hide the lightbox. */
export function closeLightbox(): void {
  const box = document.getElementById("lightbox");
  if (box) box.hidden = true;
}

/** Render one message bubble. */
export function renderMessage(m: UiMessage, me: string): HTMLElement {
  const mine = me !== "" && m.sender === me;
  const row = el("div", mine ? "row me" : "row them");
  row.dataset.messageId = String(m.id);

  const bubble = el("div", "bubble");
  if (!mine) bubble.append(el("div", "who", m.sender));

  if (m.is_unsent) {
    bubble.append(el("div", "dim", "Message unsent"));
  } else if (m.content) {
    appendText(bubble, m.content);
  }

  for (const media of m.media) {
    const node = renderMedia(media);
    if (node) bubble.append(node);
  }

  if (m.call_duration !== null && m.call_duration !== undefined) {
    bubble.append(el("div", "dim", `Call, ${m.call_duration} s`));
  }

  if (m.reactions.length > 0) {
    const rx = el("div", "react");
    for (const r of m.reactions) {
      rx.append(document.createTextNode(`${r.reaction} ${r.actor}  `));
    }
    bubble.append(rx);
  }

  const time = el("div", "time", clockTime(m.ts_ms));
  time.title = new Date(m.ts_ms).toString();
  bubble.append(time);

  row.append(bubble);
  return row;
}

/** Render a month's messages with day separators. */
export function renderMonth(messages: UiMessage[], me: string): DocumentFragment {
  const frag = document.createDocumentFragment();
  for (const m of withDayBreaks(messages)) {
    if (m.dayBreak) frag.append(el("div", "day", dayHeading(m.ts_ms)));
    frag.append(renderMessage(m, me));
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
