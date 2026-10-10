// Patches the generated Android manifest to request storage access.
//
// Needed because src-tauri/gen/ is gitignored: `tauri android init` regenerates the
// project, so this must run after init and before build.
//
//   npx tauri android init && npm run android:seed && npm run android:perms && npx tauri android build
//
// MANAGE_EXTERNAL_STORAGE is the broadest Android permission. It is what lets the app
// read an export the user names by path instead of using the scoped-storage SAF
// picker, which returns content:// URIs the ingest layer cannot open. Trade-off:
// a scary "All files access" prompt, in exchange for no per-file grants and no plugin.

import { readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const manifest = path.join(
  root,
  "src-tauri/gen/android/app/src/main/AndroidManifest.xml",
);

if (!existsSync(manifest)) {
  console.error(`No Android project at ${manifest}.\nRun: npx tauri android init`);
  process.exit(1);
}

const PERM = "android.permission.MANAGE_EXTERNAL_STORAGE";
let xml = await readFile(manifest, "utf8");

if (xml.includes(PERM)) {
  console.log("Storage permission already present; nothing to do.");
  process.exit(0);
}

// Must sit inside <manifest> but before <application> to be valid.
if (/<application/.test(xml)) {
  xml = xml.replace(
    /<application/,
    `    <uses-permission android:name="${PERM}" />\n\n    <application`,
  );
} else {
  xml = xml.replace(
    /<\/manifest>/,
    `    <uses-permission android:name="${PERM}" />\n</manifest>`,
  );
}

await writeFile(manifest, xml, "utf8");
console.log(`Added MANAGE_EXTERNAL_STORAGE to ${manifest}`);
console.log("Rebuild the APK for this to take effect.");