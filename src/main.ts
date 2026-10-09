import { invoke } from "@tauri-apps/api/core";

async function boot(): Promise<void> {
  const titleEl = document.querySelector<HTMLElement>("#title");
  const bootEl = document.querySelector<HTMLElement>("#boot");
  // The visible heading is rendered by this external module via textContent
  // (never innerHTML), so a visible heading proves the script loaded.
  if (titleEl) titleEl.textContent = "IG Chat Archive Viewer";
  try {
    const info = await invoke<string>("app_info");
    if (bootEl) bootEl.textContent = `M1 scaffold OK \u2014 ${info}`;
  } catch (err) {
    const msg = `M1 scaffold: IPC unavailable (${String(err)})`;
    if (bootEl) bootEl.textContent = msg;
    console.error(msg);
  }
}

try {
  boot();
} catch (err) {
  const msg = `M1 scaffold: boot failed (${String(err)})`;
  const bootEl = document.querySelector<HTMLElement>("#boot");
  if (bootEl) bootEl.textContent = msg;
  console.error(msg);
}
