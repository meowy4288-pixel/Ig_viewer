// Copies the fake export into the Android app's bundled assets so the APK is
// usable without any import step.
//
// The Android project lives in src-tauri/gen/ and is gitignored, so this must run
// after `tauri android init` and before `tauri android build`. Wire it as:
//
//   npx tauri android init && npm run android:seed && npx tauri android build
//
// Only fake data is ever bundled: never a real export.

import { cp, mkdir, rm, readdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

// Opt-in. The bundled sample was only useful before on-device import worked; with
// manual path import a real export can be used instead, and shipping several MB of
// fake chats in a release build is pointless. Run explicitly with --seed if wanted.
if (!process.argv.includes("--seed")) {
  console.log("Skipping seed (pass --seed to bundle the sample archive).");
  process.exit(0);
}

const root = path.resolve(import.meta.dirname, "..");
const assets = path.join(
  root,
  "src-tauri/gen/android/app/src/main/assets",
);
const src = path.join(root, "fake-data/export");
const dest = path.join(assets, "seed-export");

if (!existsSync(src)) {
  console.error(`No fake export at ${src}. Run: python3 tools/gen_fake_data.py`);
  process.exit(1);
}
if (!existsSync(assets)) {
  console.error(
    `No Android project at ${assets}.\nRun: npx tauri android init`,
  );
  process.exit(1);
}

await rm(dest, { recursive: true, force: true });
await mkdir(assets, { recursive: true });
await cp(src, dest, { recursive: true });

const n = (await readdir(dest)).length;
console.log(`Seeded ${n} top-level entries into assets/seed-export`);
console.log("Rebuild the APK for this to take effect.");