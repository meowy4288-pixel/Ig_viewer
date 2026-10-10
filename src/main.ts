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
  type MonthBucket,
  type ThreadSummary,
  type UiMessage,
  filterThreads,
  firstIndexAtOrAfter,
  inDisplayOrder,
  monthOfIsoDate,
  tsForIsoDate,
} from "./logic";
import { avatarChip, closeLightbox, el, renderMonth, renderSearchHit } from "./ui";

interface ArchiveState {
  id: string;
  threads: ThreadSummary[];
  threadKey: string;
  participants: string[];
  months: MonthBucket[];
  monthIndex: number;
  messages: UiMessage[];
  me: string;
  timezone: string;
}

const state: ArchiveState = {
  id: "",
  threads: [],
  threadKey: "",
  participants: [],
  months: [],
  monthIndex: 0,
  messages: [],
  me: "",
  timezone: "UTC",
};

const $ = <T extends HTMLElement>(sel: string): T => {
  const e = document.querySelector<T>(sel);
  if (!e) throw new Error(`missing element ${sel}`);
  return e;
};

function status(text: string): void {
  $("#status").textContent = text;
}

function banner(text: string): void {
  const b = $("#fallback");
  b.textContent = text;
  b.hidden = text === "";
}

// ------------------------------------------------------------- boot

async function boot(): Promise<void> {
  $("#title").textContent = "IG Chat Archive Viewer";

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

interface Prefs {
  theme: "dark" | "light" | "system";
  fontSize: number;
  density: "cozy" | "compact";
  motion: boolean;
  timezone: string;
}

const DEFAULT_PREFS: Prefs = {
  theme: "system",
  fontSize: 15,
  density: "cozy",
  motion: true,
  timezone: "",
};

function loadPrefs(): Prefs {
  const base = { ...DEFAULT_PREFS };
  try {
    const raw = localStorage.getItem("igv:prefs");
    if (!raw) return base;
    const p = JSON.parse(raw) as Partial<Prefs>;
    if (p.theme === "dark" || p.theme === "light" || p.theme === "system") base.theme = p.theme;
    if (typeof p.fontSize === "number" && p.fontSize >= 13 && p.fontSize <= 20) {
      base.fontSize = Math.round(p.fontSize);
    }
    if (p.density === "cozy" || p.density === "compact") base.density = p.density;
    if (typeof p.motion === "boolean") base.motion = p.motion;
    if (typeof p.timezone === "string" && p.timezone.length <= 64) base.timezone = p.timezone;
  } catch {
    /* corrupted prefs: fall back to defaults rather than failing to boot */
  }
  return base;
}

function applyPrefs(p: Prefs): void {
  const root = document.documentElement;
  // Android cannot open a native folder dialog yet; offer the push-import instead.
  const isAndroid = /Android/i.test(navigator.userAgent);
  document.documentElement.classList.toggle("android", isAndroid);
  document.documentElement.classList.toggle("no-native-picker", isAndroid);
  // Animations are off if the user turned them off *or* the OS asks for reduced
  // motion. The settings toggle shows the effective value.
  const prefersLight = window.matchMedia?.("(prefers-color-scheme: light)").matches ?? false;
  const theme = p.theme === "system" ? (prefersLight ? "light" : "dark") : p.theme;
  root.classList.toggle("light", theme === "light");
  root.classList.toggle("compact", p.density === "compact");
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  root.dataset.motion = p.motion && !reduced ? "on" : "off";
  root.style.fontSize = `${p.fontSize}px`;
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
  $<HTMLOutputElement>("#set-fs-out").textContent = `${prefs.fontSize}px`;
  // Reflect what is actually applied to <html>, not merely what is stored: if the
  // two ever diverge (e.g. OS reduced-motion overriding) the control must not lie.
  $<HTMLInputElement>("#set-motion").checked =
    document.documentElement.dataset.motion === "on";
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
    await doIndex();
    status(`Extracted ${res.files} files${res.rejected ? ` (${res.rejected} unsafe entries skipped)` : ""}.`);
  } catch (err) {
    status(`Extraction failed (${String(err)}).`);
  }
}

// Android ships a fake export inside the APK so the app is usable on a device
// with no import step. Copy it out and index it the first time we run.
async function seedOnFirstRun(): Promise<void> {
  if (!/Android/i.test(navigator.userAgent)) return;
  try {
    const root = await invoke<string | null>("seed_demo_archive");
    if (root) {
      await doIndex(root);
      status("Loaded the bundled sample archive.");
    }
  } catch (err) {
    status(`Sample archive unavailable: ${String(err)}`);
  }
}

async function onPickAppDir(): Promise<void> {
  status("Looking for a pushed .zip or export folder\u2026");
  try {
    const res = JSON.parse(await invoke<string>("import_from_app_dir")) as {
      kind: string;
      export_root: string;
      files?: number;
    };
    state.id = "";
    await doIndex(res.export_root);
    status(
      res.kind === "zip"
        ? `Extracted ${res.files} files.`
        : "Indexing the pushed folder.",
    );
  } catch (err) {
    const e = String(err);
    status(
      e.includes("nothing-found")
        ? "No .zip or your_instagram_activity folder found. Push one with: adb push <path> /sdcard/Android/data/com.ig.viewer.app/files/"
        : `Import failed (${e}).`,
    );
  }
}

async function onPick(): Promise<void> {
  status("Waiting for the folder picker\u2026");
  let raw: string | null;
  try {
    raw = await invoke<string | null>("pick_export_folder");
  } catch (err) {
    status(`Could not open the folder picker (${String(err)}).`);
    return;
  }
  if (!raw) {
    status("Folder selection cancelled.");
    return;
  }
  const { archive_id: id } = JSON.parse(raw) as { archive_id: string };
  localStorage.setItem("igv:lastArchive", id);
  await attach(id);
  await doIndex();
}

async function attach(id: string): Promise<void> {
  state.id = id;
  $("#delete").hidden = false;
  $("#toolbar").hidden = false;
  await refreshTimezone();
  await refreshThreads();
}

async function refreshTimezone(): Promise<void> {
  try {
    const info = JSON.parse(await invoke<string>("get_timezone", { archiveId: state.id })) as {
      timezone: string;
      system: string;
      fallback: boolean;
    };
    state.timezone = info.timezone;
    $("#tz").textContent = `Times shown in ${info.timezone}`;
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
      $("#toolbar").hidden = false;
      $("#delete").hidden = false;
    }
    if (summary.complete) {
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

function renderThreadList(): void {
  const list = $("#list");
  list.replaceChildren();
  for (const t of filterThreads(state.threads, $<HTMLInputElement>("#filter").value)) {
    const b = el("button", "thread");
    b.type = "button";
    b.append(avatarChip(t.title));
    const body = el("div", "body");
    body.append(el("div", "t", t.title));
    body.append(el("div", "s", `${t.message_count} message${t.message_count === 1 ? "" : "s"}`));
    b.append(body);
    if (t.key === state.threadKey) {
      b.classList.add("sel");
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
  renderThreadList();
  const raw = await invoke<string>("get_thread", { archiveId: state.id, threadKey: key });
  const data = JSON.parse(raw) as { participants: string[]; months: MonthBucket[] };
  state.participants = data.participants;
  state.months = data.months;

  const meSel = $<HTMLSelectElement>("#me");
  meSel.replaceChildren();
  const none = document.createElement("option");
  none.value = "";
  none.textContent = "I am\u2026";
  meSel.append(none);
  for (const p of state.participants) {
    const o = document.createElement("option");
    o.value = p;
    o.textContent = p;
    meSel.append(o);
  }
  const saved = localStorage.getItem(`igv:me:${key}`) ?? "";
  state.me = state.participants.includes(saved) ? saved : "";
  meSel.value = state.me;

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
  $<HTMLSelectElement>("#months").value = String(clamped);
  $<HTMLInputElement>("#date").value = "";

  status(`Loading ${bucket.key}\u2026`);
  const raw = await invoke<string>("get_month", {
    archiveId: state.id,
    threadKey: state.threadKey,
    month: bucket.key,
  });
  const data = JSON.parse(raw) as { messages: UiMessage[] };
  state.messages = inDisplayOrder(data.messages);

  const box = $("#msgs");
  box.replaceChildren(renderMonth(state.messages, state.me));
  if (where === "end") box.scrollTop = box.scrollHeight;
  else box.scrollTop = 0;
  status(`${state.messages.length} messages in ${bucket.key}.`);

  if (where === "focus") {
    const target = box.querySelector<HTMLElement>(".row.flash");
    target?.scrollIntoView({ block: "center" });
  }
}

async function jumpToDate(iso: string): Promise<void> {
  const ts = tsForIsoDate(iso);
  if (ts === null) {
    status("Pick a valid date.");
    return;
  }
  const month = monthOfIsoDate(iso);
  let idx = state.months.findIndex((m) => m.key === month);
  if (idx < 0) {
    // The month may not be in the current thread's list; ask the backend which
    // bucket covers that instant.
    const raw = await invoke<string>("month_for_ts", { archiveId: state.id, tsMs: ts });
    const { month: resolved } = JSON.parse(raw) as { month: string };
    idx = state.months.findIndex((m) => m.key === resolved);
  }
  if (idx < 0) {
    status("No messages around that date in this chat.");
    return;
  }
  await showMonth(idx, "start");
  // Land on the first message at or after the requested day.
  const box = $("#msgs");
  const idxInMonth = firstIndexAtOrAfter(state.messages, ts);
  const rows = box.querySelectorAll<HTMLElement>(".row");
  rows.forEach((r) => r.classList.remove("flash"));
  rows[idxInMonth]?.classList.add("flash");
  rows[idxInMonth]?.scrollIntoView({ block: "center" });
}

// ------------------------------------------------------------- search

async function runSearch(query: string): Promise<void> {
  const q = query.trim();
  const box = $("#results");
  if (!q) {
    box.hidden = true;
    box.replaceChildren();
    return;
  }
  try {
    const raw = await invoke<string>("search_messages", { archiveId: state.id, query: q });
    const data = JSON.parse(raw) as {
      hits: { id: number; ts_ms: number; sender: string; snippet: string }[];
    };
    box.replaceChildren();
    if (data.hits.length === 0) {
      box.append(el("div", "dim", "No matches"));
    }
    for (const h of data.hits) {
      const row = renderSearchHit(h.sender, h.snippet, h.ts_ms);
      row.addEventListener("click", () => void jumpToTs(h.ts_ms));
      box.append(row);
    }
    box.hidden = false;
  } catch (err) {
    status(`Search failed: ${String(err)}`);
    box.hidden = true;
  }
}

async function jumpToTs(ts: number): Promise<void> {
  const raw = await invoke<string>("month_for_ts", { archiveId: state.id, tsMs: ts });
  const { month } = JSON.parse(raw) as { month: string };
  const idx = state.months.findIndex((m) => m.key === month);
  if (idx >= 0) {
    $("#results").hidden = true;
    await showMonth(idx, "start");
  }
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
    $("#list").replaceChildren();
    $("#msgs").replaceChildren();
    state.id = "";
    state.threads = [];
    status("Index deleted. The export folder is untouched.");
  } catch (err) {
    status(`Could not delete the index: ${String(err)}`);
  }
}

// ------------------------------------------------------------- wiring

function wire(): void {
  $("#pick").addEventListener("click", () => void onPick());
  $("#pickzip").addEventListener("click", () => void onPickZip());
  $("#picksd").addEventListener("click", () => void onPickAppDir());
  void seedOnFirstRun();
  $("#settings-btn").addEventListener("click", () => openSettings(true));
  $("#settings-close").addEventListener("click", () => openSettings(false));
  $("#settings").addEventListener("click", (e) => {
    if (e.target === $("#settings")) openSettings(false);
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
    $<HTMLOutputElement>("#set-fs-out").textContent = `${prefs.fontSize}px`;
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
  $("#filter").addEventListener("input", () => renderThreadList());
  $("#prev").addEventListener("click", () => void showMonth(state.monthIndex - 1));
  $("#next").addEventListener("click", () => void showMonth(state.monthIndex + 1));
  $("#first").addEventListener("click", () => void showMonth(0));
  $("#last").addEventListener("click", () =>
    void showMonth(Math.max(0, state.months.length - 1), "end"),
  );
  $("#months").addEventListener("change", (e) =>
    void showMonth(Number((e.target as HTMLSelectElement).value)),
  );
  $("#date").addEventListener("change", (e) =>
    void jumpToDate((e.target as HTMLInputElement).value),
  );
  $("#q").addEventListener("input", (e) => void runSearch((e.target as HTMLInputElement).value));
  $("#me").addEventListener("change", (e) => {
    const v = (e.target as HTMLSelectElement).value;
    state.me = v;
    localStorage.setItem(`igv:me:${state.threadKey}`, v);
    void showMonth(state.monthIndex);
  });
  $("#tzset").addEventListener("click", () => openSettings(true));
  // Click anywhere on the overlay to dismiss; Escape works too.
  $("#lightbox").addEventListener("click", () => closeLightbox());
  window.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeLightbox();
  });
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

const prefs = loadPrefs();
applyPrefs(prefs);

try {
  wire();
  void boot();
} catch (err) {
  status(`Startup failed: ${String(err)}`);
}
