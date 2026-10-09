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
import { el, renderMonth } from "./ui";

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

async function doIndex(): Promise<void> {
  const root = $<HTMLInputElement>("#root").value.trim();
  status("Indexing\u2026");
  try {
    const raw = await invoke<string>("index_archive", {
      archiveId: state.id,
      exportRoot: root,
      timezone: null,
    });
    const summary = JSON.parse(raw) as {
      threads: number;
      messages: number;
      skipped_files: number;
      parsed_files: number;
      complete: boolean;
    };
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
    const b = el("button", "chat");
    b.type = "button";
    b.append(el("b", "", t.title));
    b.append(el("small", "", `${t.message_count} messages`));
    if (t.key === state.threadKey) b.classList.add("on");
    b.addEventListener("click", () => void openThread(t.key));
    list.append(b);
  }
  $<HTMLInputElement>("#filter").disabled = state.threads.length === 0;
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
    const data = JSON.parse(raw) as { hits: { id: number; ts_ms: number }[] };
    box.replaceChildren();
    if (data.hits.length === 0) {
      box.append(el("div", "dim", "No matches"));
    }
    for (const h of data.hits) {
      const row = el("div");
      row.append(el("small", "", new Date(h.ts_ms).toLocaleString()));
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
  $("#tzset").addEventListener("click", () => void changeTimezone());
}

async function changeTimezone(): Promise<void> {
  const raw = $<HTMLInputElement>("#tzinput").value.trim();
  if (!raw) return;
  try {
    await invoke("set_timezone", { archiveId: state.id, timezone: raw });
    await refreshTimezone();
    await openThread(state.threadKey);
    status(`Month buckets recomputed for ${raw}.`);
  } catch (err) {
    status(`Could not set timezone: ${String(err)}`);
  }
}

try {
  wire();
  void boot();
} catch (err) {
  status(`Startup failed: ${String(err)}`);
}
