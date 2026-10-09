import { spawn } from "node:child_process";
import { copyFileSync, mkdirSync, readdirSync, watch } from "node:fs";
import { join } from "node:path";

const STATIC_EXT = [".html", ".css", ".svg", ".png", ".ico"];

function isStatic(name) {
  return STATIC_EXT.some((ext) => name.endsWith(ext));
}

function copyStatic() {
  mkdirSync("dist", { recursive: true });
  for (const name of readdirSync("src")) {
    if (!isStatic(name)) continue;
    try {
      copyFileSync(join("src", name), join("dist", name));
    } catch (err) {
      console.error(`copy-static: ${name}: ${err.message}`);
    }
  }
}

copyStatic();

// esbuild owns dist/main.js and resolves the bare `@tauri-apps/*` imports that a
// browser cannot fetch. tsc runs with --noEmit (see tsconfig) purely as a
// type-checker, so it can never overwrite the bundle with unbundled output.
const esbuild = spawn(
  "npx",
  [
    "--yes",
    "esbuild",
    "src/main.ts",
    "--bundle",
    "--format=esm",
    "--target=es2022",
    "--outfile=dist/main.js",
    "--sourcemap",
    "--watch",
  ],
  { stdio: "inherit", shell: process.platform === "win32" },
);

const tsc = spawn("tsc", ["--watch", "--preserveWatchOutput"], {
  stdio: "inherit",
  shell: process.platform === "win32",
});

watch("src", { persistent: true }, (_event, filename) => {
  if (filename && isStatic(filename)) copyStatic();
});

function shutdown() {
  esbuild.kill();
  tsc.kill();
  process.exit(0);
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
tsc.on("exit", (code) => process.exit(code ?? 0));
