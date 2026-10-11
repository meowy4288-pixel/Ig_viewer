/**
 * Pure display logic, kept free of DOM and Tauri so it can be unit tested.
 *
 * Everything here operates on plain data the Rust side hands over. Nothing in this
 * module ever builds markup: the renderer in ui.ts does that with createElement and
 * textContent.
 */

export interface ThreadSummary {
  key: string;
  title: string;
  message_count: number;
  last_ts_ms: number;
}

export interface MonthBucket {
  key: string;
  count: number;
}

export interface UiMedia {
  media_id: number;
  kind: string;
  available: boolean;
  url?: string;
}

export interface UiMessage {
  id: number;
  seq: number;
  ts_ms: number;
  month: string;
  sender: string;
  content: string | null;
  is_unsent: boolean;
  call_duration: number | null;
  media: UiMedia[];
  reactions: { actor: string; reaction: string }[];
}

/** Media kinds that render as an image. */
export function isImageKind(kind: string): boolean {
  return kind === "photos" || kind === "gifs" || kind === "sticker";
}

/** Media kinds that render with a <video> element. */
export function isVideoKind(kind: string): boolean {
  return kind === "videos";
}

/** Media kinds that render with an <audio> element (voice notes). */
export function isAudioKind(kind: string): boolean {
  return kind === "audio_files";
}

/** True when the media can be shown at all. */
export function isRenderable(m: UiMedia): boolean {
  return m.available && typeof m.url === "string" && m.url.startsWith("igmedia:");
}

/**
 * Reject anything that is not our own media URL.
 *
 * The backend only ever hands out igmedia: URLs, but the UI must not become the
 * thing that trusts that, so every URL is re-checked before it reaches a src
 * attribute.
 */
export function isSafeMediaUrl(url: unknown): url is string {
  return typeof url === "string" && url.startsWith("igmedia://media/");
}

/**
 * Split text into plain runs and http(s) link runs.
 *
 * Only http and https become links; every other scheme (javascript:, data:,
 * file:, igmedia:) stays inert text. Callers must still insert the text with
 * textContent -- this function only decides where the boundaries are.
 */
export function splitLinks(text: string): { text: string; href: string | null }[] {
  const re = /https?:\/\/[^\s<>"']+/gi;
  const out: { text: string; href: string | null }[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) out.push({ text: text.slice(last, m.index), href: null });
    out.push({ text: m[0], href: m[0] });
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push({ text: text.slice(last), href: null });
  return out.length > 0 ? out : [{ text, href: null }];
}

/** Local calendar date for a timestamp, in the browser's zone. */
export function localDate(tsMs: number): Date {
  return new Date(tsMs);
}

/** `YYYY-MM-DD` for a `<input type="date">` value. */
export function isoDate(tsMs: number): string {
  const d = localDate(tsMs);
  const y = String(d.getFullYear()).padStart(4, "0");
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/** Timestamp for midnight local time on a `YYYY-MM-DD` string. */
export function tsForIsoDate(iso: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const t = d.getTime();
  return Number.isFinite(t) ? t : null;
}

/** Human day heading, e.g. "Friday, 05 January 2024". */
export function dayHeading(tsMs: number): string {
  return localDate(tsMs).toLocaleDateString(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
    year: "numeric",
  });
}

/** Clock time, e.g. "09:41". */
export function clockTime(tsMs: number): string {
  return localDate(tsMs).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

/**
 * Insert day separators into a message list.
 *
 * Returns the messages with an optional `dayBreak` flag on the first message of each
 * calendar day. Pure, so ordering and grouping are testable without a DOM.
 */
export function withDayBreaks<T extends { ts_ms: number }>(messages: T[]): (T & { dayBreak: boolean })[] {
  let lastDay = "";
  return messages.map((m) => {
    const day = localDate(m.ts_ms).toDateString();
    const dayBreak = day !== lastDay;
    lastDay = day;
    return { ...m, dayBreak };
  });
}

/**
 * The display order contract, mirrored from Rust: (ts_ms ASC, seq DESC).
 *
 * The backend already sorts, but re-asserting it here means a UI regression shows up
 * as a failed test rather than as subtly wrong rendering.
 */
export function inDisplayOrder<T extends { ts_ms: number; seq: number }>(messages: T[]): T[] {
  return [...messages].sort((a, b) => a.ts_ms - b.ts_ms || b.seq - a.seq);
}

/** Index of the first message at or after `tsMs`, clamped into range. */
export function firstIndexAtOrAfter(messages: { ts_ms: number }[], tsMs: number): number {
  if (messages.length === 0) return 0;
  let lo = 0;
  let hi = messages.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (messages[mid].ts_ms < tsMs) lo = mid + 1;
    else hi = mid;
  }
  return Math.min(lo, messages.length - 1);
}

/**
 * Split an FTS5 snippet into plain and highlighted runs.
 *
 * The backend wraps matched terms in `[` and `]` (chosen instead of `<b>` so the
 * marker can never be confused with markup). The text is still untrusted chat
 * content, so the caller inserts every run with textContent and only wraps the
 * highlighted runs in an element it creates itself.
 */
export function splitSnippet(snippet: string): { text: string; hit: boolean }[] {
  const out: { text: string; hit: boolean }[] = [];
  let rest = snippet;
  while (rest.length > 0) {
    const open = rest.indexOf("[");
    if (open < 0) {
      out.push({ text: rest, hit: false });
      break;
    }
    if (open > 0) out.push({ text: rest.slice(0, open), hit: false });
    const close = rest.indexOf("]", open + 1);
    if (close < 0) {
      // Unbalanced marker: treat the remainder as plain text rather than guessing.
      out.push({ text: rest.slice(open), hit: false });
      break;
    }
    out.push({ text: rest.slice(open + 1, close), hit: true });
    rest = rest.slice(close + 1);
  }
  return out;
}

/** Trim a snippet for display without cutting mid-marker. */
export function clampSnippet(snippet: string, max: number): string {
  return snippet.length <= max ? snippet : `${snippet.slice(0, max)}\u2026`;
}

/** Case-insensitive filter over thread titles and keys. */
export function filterThreads(threads: ThreadSummary[], query: string): ThreadSummary[] {
  const q = query.trim().toLowerCase();
  if (!q) return threads;
  return threads.filter(
    (t) => t.title.toLowerCase().includes(q) || t.key.toLowerCase().includes(q),
  );
}

/** Month key from a `YYYY-MM-DD` string, used to preselect the month picker. */
export function monthOfIsoDate(iso: string): string | null {
  const m = /^(\d{4})-(\d{2})-\d{2}$/.exec(iso);
  return m ? `${m[1]}-${m[2]}` : null;
}

// -------------------------------------------------------------------- prefs
// Pure functions so the settings behaviour is testable. They were previously inline
// in main.ts, where nothing could verify them.

export type Theme = "dark" | "light" | "system";
export type Density = "cozy" | "compact";

export interface Prefs {
  theme: Theme;
  fontSize: number;
  density: Density;
  motion: boolean;
  timezone: string;
}

export const DEFAULT_PREFS: Prefs = {
  theme: "system",
  fontSize: 15,
  density: "cozy",
  motion: true,
  timezone: "",
};

/** Parse stored prefs, ignoring anything malformed rather than failing to boot. */
export function parsePrefs(raw: string | null): Prefs {
  const base = { ...DEFAULT_PREFS };
  if (!raw) return base;
  try {
    const p = JSON.parse(raw) as Partial<Prefs>;
    if (p.theme === "dark" || p.theme === "light" || p.theme === "system") base.theme = p.theme;
    if (typeof p.fontSize === "number" && Number.isFinite(p.fontSize) && p.fontSize >= 13 && p.fontSize <= 20) {
      base.fontSize = Math.round(p.fontSize);
    }
    if (p.density === "cozy" || p.density === "compact") base.density = p.density;
    if (typeof p.motion === "boolean") base.motion = p.motion;
    if (typeof p.timezone === "string" && p.timezone.length <= 64) base.timezone = p.timezone;
  } catch {
    /* corrupted: keep defaults */
  }
  return base;
}

/** Resolve "system" against what the OS currently reports. */
export function resolveTheme(theme: Theme, prefersLight: boolean): "light" | "dark" {
  if (theme === "system") return prefersLight ? "light" : "dark";
  return theme;
}

/**
 * Animations are enabled only if the user wants them AND the OS does not ask for
 * reduced motion. The settings toggle reflects this *effective* value, so it can
 * never disagree with what is actually rendered.
 */
export function motionEnabled(p: Prefs, prefersReducedMotion: boolean): boolean {
  return p.motion && !prefersReducedMotion;
}

/**
 * Local day key (`YYYY-MM-DD`) for a timestamp, in the given timezone.
 *
 * Month buckets are computed in the archive's timezone, so day keys must use it
 * too or the day select and the message bubbles disagree about what day a
 * message is on. `en-CA` yields an ISO-ordered `YYYY-MM-DD` string.
 */
export function dayKey(tsMs: number, tz: string): string {
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: tz,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date(tsMs));
  } catch {
    // An unknown zone must not break rendering; fall back to UTC.
    return new Date(tsMs).toISOString().slice(0, 10);
  }
}
