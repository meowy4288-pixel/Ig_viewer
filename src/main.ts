/**
 * Frontend entry point.
 *
 * Wires the UI to the Rust commands. Every value shown here arrives already
 * repaired by Rust `fix()`; the frontend never re-encodes anything, and never sees
 * a filesystem path or an export URI -- media arrives as an igmedia: URL built
 * server-side from an opaque id.
 */

import { invoke } from "@tauri-apps/api/core";
import {
  dayKey,
  type MonthBucket,
  type ThreadSummary,
  type UiMessage,
  filterThreads,
  inDisplayOrder,
  motionEnabled,
  parsePrefs,
  resolveTheme,
  type Prefs,
} from "./logic";
import { avatarChip, closeLightbox, el, renderMonth, renderSearchHit } from "./ui";

interface ArchiveState {
  id: string;
  threads: ThreadSummary[];
  threadKey: string;
  threadTitle: string;
  participants: string[];
  months: MonthBucket[];
  monthIndex: number;
  messages: UiMessage[];
  /** Distinct days present in the loaded month, ascending, in the archive timezone. */
  days: string[];
  me: string;
  timezone: string;
}

const state: ArchiveState = {
  id: "",
  threads: [],
  threadKey: "",
  threadTitle: "",
  participants: [],
  months: [],
  monthIndex: 0,
  messages: [],
  days: [],
  me: "",
  timezone: "UTC",
};

const $ = <T extends HTMLElement>(sel: string): T => {
  const e = document.querySelector<T>(sel);
  if (!e) throw new Error(`missing element ${sel}`);
  return e;
};

function status(text: string): void {
  // #status lives inside the import screen, which is hidden once an archive is
  // open, so mirror into the sidebar footer which is always visible.
  $("#status").textContent = text;
  $("#foot").textContent = text;
}

function banner(text: string): void {
  status(text);
}

// ------------------------------------------------------------- boot

async function boot(): Promise<void> {
  // Attach to the last archive if one exists; otherwise wait for a pick.
  const last = localStorage.getItem("igv:lastArchive");
  if (last && /^[0-9a-f]{32}$/.test(last)) {
    await attach(last);
  } else {
    status("Choose your unzipped Instagram export folder. Everything stays on this computer.");
  }
}


// ------------------------------------------------------------------ settings
// Persisted in localStorage: no plugin, no filesystem permission, no network.
// Unknown keys are ignored and malformed values fall back to the default so a
// corrupted entry can never leave the UI in an unusable state.

function applyPrefs(p: Prefs): void {
  const root = document.documentElement;
  const prefersLight = window.matchMedia?.("(prefers-color-scheme: light)").matches ?? false;
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  root.classList.toggle("light", resolveTheme(p.theme, prefersLight) === "light");
  root.classList.toggle("compact", p.density === "compact");
  root.dataset.anim = motionEnabled(p, reduced) ? "on" : "off";
  root.style.fontSize = `${p.fontSize}px`;
}

/** Current effective animation state, so the toggle reflects reality not just intent. */
function motionIsOn(): boolean {
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  return motionEnabled(prefs, reduced);
}

function savePrefs(): void {
  localStorage.setItem("igv:prefs", JSON.stringify(prefs));
  applyPrefs(prefs);
}

function openSettings(open: boolean): void {
  const sheet = $<HTMLElement>("#settings");
  sheet.hidden = !open;
  if (!open) return;
  // Reflect current prefs into the controls each time, so the sheet never shows
  // stale values after a change made elsewhere (e.g. the timezone toolbar).
  $<HTMLSelectElement>("#set-theme").value = prefs.theme;
  $<HTMLSelectElement>("#set-density").value = prefs.density;
  const fs = $<HTMLInputElement>("#set-fs");
  fs.value = String(prefs.fontSize);
  // Reflect what is actually applied to <html>, not merely what is stored: if the
  // two ever diverge (e.g. OS reduced-motion overriding) the control must not lie.
  $<HTMLInputElement>("#set-motion").checked = motionIsOn();
  $<HTMLInputElement>("#set-tz").value = prefs.timezone;
  $<HTMLSelectElement>("#set-theme").focus();
}

async function onPickZip(): Promise<void> {
  status("Waiting for the file picker\u2026");
  let raw: string | null;
  try {
    raw = await invoke<string | null>("pick_export_zip");
  } catch (err) {
    status(`Could not open the file picker (${String(err)}).`);
    return;
  }
  if (!raw) {
    status("File selection cancelled.");
    return;
  }
  status("Extracting zip\u2026");
  try {
    const res = JSON.parse(await invoke<string>("extract_export", { zipPath: raw })) as {
      archive_id: string;
      export_root: string;
      files: number;
      rejected: number;
    };
    localStorage.setItem("igv:lastArchive", res.archive_id);
    await attach(res.archive_id);
    $<HTMLInputElement>("#root").value = res.export_root;
    await doIndex(res.export_root);
    status(`Extracted ${res.files} files${res.rejected ? ` (${res.rejected} unsafe entries skipped)` : ""}.`);
  } catch (err) {
    status(`Extraction failed (${String(err)}).`);
  }
}


async function attach(id: string): Promise<void> {
  state.id = id;
  await refreshTimezone();
  await refreshThreads();
  // Only reveal the archive chrome once the archive actually resolved. A stale
  // id in localStorage used to leave an empty toolbar and a Delete button
  // pointing at an archive that no longer exists.
  const live = state.threads.length > 0;
  $("#delete").hidden = !live;
  $("#reimport").hidden = !live;
  $("#deletequick").hidden = !live;
  $("#toolbar").hidden = !live;
}

async function refreshTimezone(): Promise<void> {
  try {
    const info = JSON.parse(await invoke<string>("get_timezone", { archiveId: state.id })) as {
      timezone: string;
      system: string;
      fallback: boolean;
    };
    state.timezone = info.timezone;
    $("#foot").textContent = `Times shown in ${info.timezone}`;
    if (info.fallback) {
      // The UTC fallback must be visible, not just logged.
      banner(
        `System timezone could not be detected, so times are shown in UTC. ` +
          `Change it with the date controls if needed.`,
      );
    } else {
      banner("");
    }
  } catch {
    banner("Timezone unknown; times are shown in UTC.");
  }
}

async function doIndex(prefilledRoot?: string): Promise<void> {
  const root = (prefilledRoot ?? $<HTMLInputElement>("#root").value).trim();
  if (root === "") {
    status("Type the export folder path first, or use Choose export folder.");
    return;
  }
  // A .zip is extracted into app storage first; a folder is indexed where it lies.
  if (root.toLowerCase().endsWith(".zip")) {
    status("Extracting zip\u2026");
    try {
      const res = JSON.parse(await invoke<string>("extract_export", { zipPath: root })) as {
        archive_id: string;
        export_root: string;
        files: number;
        rejected: number;
      };
      $<HTMLInputElement>("#root").value = res.export_root;
      status(`Extracted ${res.files} files. Indexing\u2026`);
      await doIndex(res.export_root);
      status(
        `Imported ${res.files} files${res.rejected ? ` (${res.rejected} unsafe entries skipped)` : ""}.`,
      );
      return;
    } catch (err) {
      status(`Extraction failed: ${String(err)}`);
      return;
    }
  }

  status("Indexing\u2026");
  try {
    // An empty archiveId tells Rust to mint a new archive, which is what the
    // paste-a-path flow needs: no picker ran, so there is no id yet.
    const raw = await invoke<string>("index_archive", {
      archiveId: prefilledRoot ? null : state.id,
      exportRoot: root,
      timezone: null,
    });
    const summary = JSON.parse(raw) as {
      archive_id: string;
      threads: number;
      messages: number;
      skipped_files: number;
      parsed_files: number;
      complete: boolean;
    };
    if (summary.archive_id && summary.archive_id !== state.id) {
      state.id = summary.archive_id;
      localStorage.setItem("igv:lastArchive", state.id);
    }
    if (state.id !== "") {
      $("#toolbar").hidden = false;
      $("#delete").hidden = false;
      $("#reimport").hidden = false;
      $("#deletequick").hidden = false;
    }
    if (summary.threads === 0) {
      // Almost always the wrong folder. Say what was expected rather than
      // reporting an empty index and leaving the user to guess.
      status(
        `No chats found under that path. Expected to find ` +
          `your_instagram_activity/messages/inbox inside it \u2014 point at the ` +
          `folder containing your_instagram_activity, or at that folder itself.`,
      );
    } else if (summary.complete) {
      status(`Indexed ${summary.messages} messages across ${summary.threads} chats.`);
    } else {
      // Never claim a complete import when files were skipped.
      status(
        `Indexed ${summary.messages} messages across ${summary.threads} chats, ` +
          `but ${summary.skipped_files} file(s) were skipped. ` +
          `Some messages are missing \u2014 see the log for sanitised reasons.`,
      );
    }
    await refreshThreads();
  } catch (err) {
    status(`Indexing failed: ${String(err)}`);
  }
}

// ------------------------------------------------------------- threads

async function refreshThreads(): Promise<void> {
  try {
    const raw = await invoke<string>("list_threads", { archiveId: state.id });
    const data = JSON.parse(raw) as { threads: ThreadSummary[]; timezone: string };
    state.threads = data.threads;
    renderThreadList();
    if (state.threads.length > 0) {
      await openThread(state.threads[0].key);
    }
  } catch (err) {
    status(`Could not list chats: ${String(err)}`);
  }
}

// ------------------------------------------------------------ date controls
// Year / Month / Day are three selects rather than one month list, so a day can be
// chosen directly. Everything is derived from state.months (the real month buckets
// from `GROUP BY month`, which only ever contains months that have messages) and,
// within the loaded month, from the messages themselves.

/** Distinct `YYYY-MM-DD` days present in a month's messages, ascending. */
function distinctDays(messages: UiMessage[], tz: string): string[] {
  const seen = new Set<string>();
  for (const m of messages) {
    if (Number.isNaN(new Date(m.ts_ms).getTime())) continue;
    seen.add(dayKey(m.ts_ms, tz));
  }
  return [...seen].sort();
}



function monthLabel(key: string): string {
  const [y, m] = key.split("-");
  const d = new Date(Number(y), Number(m) - 1, 1);
  return d.toLocaleDateString(undefined, { month: "long" }) + " " + y;
}

function fillDateControls(): void {
  const bucket = state.months[state.monthIndex];
  if (!bucket) return;
  const [year] = bucket.key.split("-");

  const years = [...new Set(state.months.map((m) => m.key.split("-")[0]))];
  const ys = $<HTMLSelectElement>("#years");
  ys.replaceChildren();
  for (const y of years) {
    const o = document.createElement("option");
    o.value = y;
    o.textContent = y;
    ys.append(o);
  }
  ys.value = year;

  const months = state.months.filter((m) => m.key.startsWith(year + "-"));
  const ms = $<HTMLSelectElement>("#months");
  ms.replaceChildren();
  for (const m of months) {
    const o = document.createElement("option");
    o.value = m.key;
    o.textContent = monthLabel(m.key);
    ms.append(o);
  }
  ms.value = bucket.key;

  const ds = $<HTMLSelectElement>("#days");
  ds.replaceChildren();
  if (state.days.length === 0) {
    const o = document.createElement("option");
    o.textContent = "\u2014";
    o.disabled = true;
    ds.append(o);
  } else {
    for (const d of state.days) {
      const o = document.createElement("option");
      o.value = d;
      o.textContent = new Date(`${d}T12:00:00`).toLocaleDateString(undefined, {
        day: "numeric",
        month: "short",
      });
      ds.append(o);
    }
    ds.value = state.days[0];
  }
  syncStepper();
}

/**
 * Arrows step through days that actually contain messages, crossing month and year
 * boundaries. Most days in a real export are empty, so a naive +/-1 day would keep
 * landing on nothing.
 */
function syncStepper(): void {
  const ds = state.days;
  const bucket = state.months[state.monthIndex];
  const i = ds.indexOf(`${bucket?.key ?? ""}-${$<HTMLSelectElement>("#days").value.slice(5)}`);
  const at = i < 0 ? 0 : i;
  $<HTMLButtonElement>("#prev").disabled = at <= 0;
  $<HTMLButtonElement>("#next").disabled = at >= ds.length - 1;
}

async function stepDay(dir: -1 | 1): Promise<void> {
  const ds = state.days;
  const bucket = state.months[state.monthIndex];
  if (!bucket || ds.length === 0) return;
  const cur = ds.indexOf(`${bucket.key}-${$<HTMLSelectElement>("#days").value.slice(5)}`);
  const at = (cur < 0 ? 0 : cur) + dir;
  if (at < 0 || at >= ds.length) return;

  // Crossing a month boundary means loading that month first.
  const target = ds[at];
  const targetMonth = target.slice(0, 7);
  if (targetMonth !== bucket.key) {
    const mi = state.months.findIndex((m) => m.key === targetMonth);
    if (mi < 0) return;
    await showMonth(mi);
  }
  $<HTMLSelectElement>("#days").value = target;
  syncStepper();
  scrollToDay(target);
}

/** Hide messages outside `day` in the current month. */
function scrollToDay(day: string): void {
  for (const node of Array.from($<HTMLElement>("#msgs").children) as HTMLElement[]) {
    if (!node.classList.contains("msg")) continue;
    const d = node.dataset.day;
    if (d) node.hidden = d !== day;
  }
}

/** The "Me" control is a button in the new design, so it shows the current pick. */
function renderMeButton(): void {
  const b = $<HTMLButtonElement>("#me");
  b.textContent = state.me === "" ? "I am\u2026" : state.me;
  b.title = state.me === "" ? "Choose which participant you are" : `You are ${state.me}`;
}

/** Open the participant chooser overlay. */
async function chooseMe(): Promise<void> {
  const box = $<HTMLElement>("#whopick");
  // Nothing to ask when there is no other side: every message is trivially ours.
  if (state.participants.length < 2) {
    box.hidden = true;
    state.me = state.participants[0] ?? "";
    renderMeButton();
    return;
  }
  const btns = $<HTMLElement>("#whobtns");
  btns.replaceChildren();
  for (const who of state.participants) {
    const b = el("button", who === state.me ? "primary" : "", who);
    b.dataset.who = who;
    btns.append(b);
  }
  box.hidden = false;
}

/** "9 Oct 2026" — compact and unambiguous enough for a list row. */
function shortDate(tsMs: number): string {
  return new Date(tsMs).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

function renderThreadList(): void {
  const list = $("#list");
  list.replaceChildren();
  for (const t of filterThreads(state.threads, $<HTMLInputElement>("#filter").value)) {
    const b = el("button", "chat");
    b.type = "button";
    b.append(avatarChip(t.title));
    const meta = el("div", "meta");
    const title = el("b", "", t.title);
    title.append(el("span", "", `${t.message_count}`));
    meta.append(title, el("small", "", shortDate(t.last_ts_ms)));
    b.append(meta);
    if (t.key === state.threadKey) {
      b.classList.add("on");
      b.setAttribute("aria-current", "true");
    }
    b.addEventListener("click", () => void openThread(t.key));
    list.append(b);
  }
  $<HTMLInputElement>("#filter").disabled = state.threads.length === 0;
  if (state.threads.length === 0) {
    const q = $<HTMLInputElement>("#filter").value.trim();
    const e = el("div", "empty");
    e.append(el("div", "glyph", "\uD83D\uDCAC"));
    e.append(
      el(
        "div",
        "",
        q
          ? `No chats match \u201C${q}\u201D.`
          : "No chats indexed yet. Open an export folder or a .zip to begin.",
      ),
    );
    list.append(e);
  }
}

async function openThread(key: string): Promise<void> {
  state.threadKey = key;
  state.threadTitle = state.threads.find((t) => t.key === key)?.title ?? "";
  renderThreadList();
  const raw = await invoke<string>("get_thread", { archiveId: state.id, threadKey: key });
  const data = JSON.parse(raw) as { participants: string[]; months: MonthBucket[] };
  state.participants = data.participants;
  state.months = data.months;

  // Pre-select the last choice so it is one click, but always ask: a two-person
  // chat reads wrong until you say which side is yours, the way messaging apps do.
  const saved = localStorage.getItem(`igv:me:${key}`) ?? "";
  state.me = state.participants.includes(saved) ? saved : "";
  renderMeButton();
  void chooseMe();

  const months = $<HTMLSelectElement>("#months");
  months.replaceChildren();
  state.months.forEach((m, i) => {
    const o = document.createElement("option");
    o.value = String(i);
    o.textContent = `${m.key} (${m.count})`;
    months.append(o);
  });

  state.monthIndex = Math.max(0, state.months.length - 1);
  await showMonth(state.monthIndex, "end");
}

// ------------------------------------------------------------- months

async function showMonth(index: number, where: "start" | "end" | "focus" = "start"): Promise<void> {
  if (state.months.length === 0) {
    $("#msgs").replaceChildren(el("div", "dim", "No messages in this chat."));
    return;
  }
  const clamped = Math.max(0, Math.min(index, state.months.length - 1));
  state.monthIndex = clamped;
  const bucket = state.months[clamped];

  status(`Loading ${bucket.key}\u2026`);
  const raw = await invoke<string>("get_month", {
    archiveId: state.id,
    threadKey: state.threadKey,
    month: bucket.key,
  });
  const data = JSON.parse(raw) as { messages: UiMessage[] };
  state.messages = inDisplayOrder(data.messages);
  // Days are derived from the loaded month rather than queried separately, so
  // the stepper never offers a day that has no messages.
  state.days = distinctDays(state.messages, state.timezone);

  $("#hname").textContent = state.threadTitle;
  $("#hav").textContent = (state.threadTitle.trim()[0] ?? "?").toUpperCase();
  // Thread-level totals, so the header answers "what is this chat" rather than
  // repeating the month that is already shown in the date controls.
  const total = state.threads.find((t) => t.key === state.threadKey)?.message_count ??
    state.messages.length;
  const first = state.months[0]?.key;
  const last = state.months[state.months.length - 1]?.key;
  const range = first && last
    ? `${monthLabel(first)} \u2013 ${monthLabel(last)}`
    : bucket.key;
  $("#hsub").textContent =
    `${total} message${total === 1 ? "" : "s"} \u00b7 ${range}`;

  const box = $("#msgs");
  box.hidden = false;
  $("#welcome").hidden = true;
  $("#latestf").hidden = false;
  fillDateControls();
  box.replaceChildren(renderMonth(state.messages, state.me, state.participants.length > 2, state.timezone));
  if (where === "end") box.scrollTop = box.scrollHeight;
  else box.scrollTop = 0;
  status(`${state.messages.length} messages in ${bucket.key}.`);

  if (where === "focus") {
    const target = box.querySelector<HTMLElement>(".row.flash");
    target?.scrollIntoView({ block: "center" });
  }
}

// ------------------------------------------------------------- search

async function runSearch(query: string): Promise<void> {
  const q = query.trim();
  const box = $("#msgs");
  if (!q) {
    await showMonth(state.monthIndex);
    return;
  }
  try {
    const raw = await invoke<string>("search_messages", { archiveId: state.id, query: q });
    const data = JSON.parse(raw) as {
      hits: { id: number; ts_ms: number; sender: string; snippet: string }[];
    };
    box.replaceChildren();
    if (data.hits.length === 0) {
      box.append(el("div", "empty", "No matches"));
    }
    for (const h of data.hits) {
      const row = renderSearchHit(h.sender, h.snippet, h.ts_ms);
      row.addEventListener("click", () => void jumpToTs(h.ts_ms));
      box.append(row);
    }
    box.hidden = false;
  } catch (err) {
    status(`Search failed: ${String(err)}`);
  }
}

async function jumpToTs(ts: number): Promise<void> {
  const raw = await invoke<string>("month_for_ts", { archiveId: state.id, tsMs: ts });
  const { month } = JSON.parse(raw) as { month: string };
  const idx = state.months.findIndex((m) => m.key === month);
  if (idx >= 0) await showMonth(idx, "start");
}

// ------------------------------------------------------------- delete index

async function doDeleteIndex(): Promise<void> {
  const ok = window.confirm(
    "Delete the index for this archive?\n\nThe exported folder is not touched, and nothing is deleted from it. You can re-index later.",
  );
  if (!ok) return;
  try {
    await invoke("delete_index", { archiveId: state.id });
    localStorage.removeItem("igv:lastArchive");
    $("#toolbar").hidden = true;
    $("#delete").hidden = true;
    $("#deletequick").hidden = true;
    $("#reimport").hidden = true;
    $("#list").replaceChildren();
    $("#msgs").replaceChildren();
    $("#latestf").hidden = true;
    state.id = "";
    state.threads = [];
    // Bring the import screen back. Hiding the chrome without restoring it
    // left the app with nothing clickable at all.
    $("#welcome").hidden = false;
    $("#msgs").hidden = true;
    status("Index deleted. The export folder is untouched.");
  } catch (err) {
    status(`Could not delete the index: ${String(err)}`);
  }
}

// ------------------------------------------------------------- wiring

function wire(): void {
  $("#pickzip").addEventListener("click", () => void onPickZip());
  $("#pickzip2").addEventListener("click", () => void onPickZip());
  $("#whobtns").addEventListener("click", (e) => {
    const b = (e.target as HTMLElement).closest("button");
    if (!b) return;
    state.me = b.dataset.who ?? "";
    localStorage.setItem(`igv:me:${state.threadKey}`, state.me);
    $<HTMLElement>("#whopick").hidden = true;
    renderMeButton();
    void showMonth(state.monthIndex);
  });
  $("#latestf").addEventListener("click", () => {
    const box = $<HTMLElement>("#msgs");
    box.scrollTo({ top: box.scrollHeight, behavior: "smooth" });
  });
  $("#lightbox-close").addEventListener("click", () => closeLightbox());
  $<HTMLDialogElement>("#lightbox").addEventListener("click", (e) => {
    // A click on the backdrop (outside the panel) closes.
    if (e.target === $("#lightbox")) closeLightbox();
  });
  $("#settings-btn").addEventListener("click", (e) => {
    e.stopPropagation();
    openSettings($("#settings").hidden);
  });
  document.addEventListener("click", (e) => {
    const sheet = $("#settings");
    const gear = $("#settings-btn");
    if (sheet.hidden) return;
    const t = e.target as Node;
    if (sheet.contains(t) || gear.contains(t)) return;
    openSettings(false);
  });
  $<HTMLSelectElement>("#set-theme").addEventListener("change", (e) => {
    prefs.theme = (e.target as HTMLSelectElement).value as Prefs["theme"];
    savePrefs();
  });
  $<HTMLSelectElement>("#set-density").addEventListener("change", (e) => {
    prefs.density = (e.target as HTMLSelectElement).value as Prefs["density"];
    savePrefs();
  });
  $<HTMLInputElement>("#set-fs").addEventListener("input", (e) => {
    prefs.fontSize = Number((e.target as HTMLInputElement).value);
      savePrefs();
  });
  $<HTMLInputElement>("#set-motion").addEventListener("change", (e) => {
    prefs.motion = (e.target as HTMLInputElement).checked;
    savePrefs();
  });
  $<HTMLInputElement>("#set-tz").addEventListener("change", (e) => {
    const v = (e.target as HTMLInputElement).value.trim();
    if (v) {
      prefs.timezone = v;
      savePrefs();
      void changeTimezone();
    }
  });
  $("#index").addEventListener("click", () => void doIndex());
  $("#delete").addEventListener("click", () => void doDeleteIndex());
  // The same two actions, always reachable from the sidebar footer rather than
  // only from behind the settings popover.
  $("#deletequick").addEventListener("click", () => void doDeleteIndex());
  $("#reimport").addEventListener("click", () => {
    const w = $<HTMLElement>("#welcome");
    w.hidden = false;
    $("#msgs").hidden = true;
    $("#toolbar").hidden = true;
    $("#latestf").hidden = true;
    status("Choose a different archive folder or .zip.");
  });
  $("#filter").addEventListener("input", () => renderThreadList());
  $("#prev").addEventListener("click", () => void stepDay(-1));
  $("#next").addEventListener("click", () => void stepDay(1));
  $("#q").addEventListener("input", (e) => void runSearch((e.target as HTMLInputElement).value));
  $("#me").addEventListener("click", () => void chooseMe());
  $("#whopick").addEventListener("click", (e) => {
    if (e.target === $("#whopick")) $<HTMLElement>("#whopick").hidden = true;
  });
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      closeLightbox();
      if (!$("#whopick").hidden) $("#whopick").hidden = true;
    }
  });
  $("#years").addEventListener("change", (e) => {
    const y = (e.target as HTMLSelectElement).value;
    const mi = state.months.findIndex((m) => m.key.startsWith(y + "-"));
    if (mi >= 0) void showMonth(mi);
  });
  $("#months").addEventListener("change", (e) => {
    const mi = state.months.findIndex((m) => m.key === (e.target as HTMLSelectElement).value);
    if (mi >= 0) void showMonth(mi);
  });
  $("#days").addEventListener("change", (e) => {
    syncStepper();
    scrollToDay((e.target as HTMLSelectElement).value);
  });
  $("#first").addEventListener("click", () => void showMonth(0, "start"));
  $("#last").addEventListener("click", () =>
    void showMonth(Math.max(0, state.months.length - 1), "end"),
  );
}

async function changeTimezone(): Promise<void> {
  // The timezone lives in Settings; this recomputes month buckets for the open archive.
  const raw = prefs.timezone.trim();
  if (!raw || state.id === "") return;
  try {
    await invoke("set_timezone", { archiveId: state.id, timezone: raw });
    await refreshTimezone();
    await openThread(state.threadKey);
    status(`Month buckets recomputed for ${raw}.`);
  } catch (err) {
    status(`Could not set timezone: ${String(err)}`);
  }
}

const prefs: Prefs = parsePrefs(localStorage.getItem("igv:prefs"));
applyPrefs(prefs);

try {
  wire();
  void boot();
} catch (err) {
  status(`Startup failed: ${String(err)}`);
}
