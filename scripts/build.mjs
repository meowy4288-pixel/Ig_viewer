import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";

const STATIC_EXT = [".html", ".css", ".svg", ".png", ".ico"];

function isStatic(name) {
  return STATIC_EXT.some((ext) => name.endsWith(ext));
}

function run(cmd, args) {
  const res = spawnSync(cmd, args, { stdio: "inherit", shell: process.platform === "win32" });
  if (res.status !== 0) process.exit(res.status ?? 1);
}

// Start from a clean dist so a stale unbundled main.js can never survive.
rmSync("dist", { recursive: true, force: true });
mkdirSync("dist", { recursive: true });

// tsc is a type-checker only (tsconfig has noEmit). esbuild produces the single
// bundled dist/main.js the webview loads.
run("tsc");
run("npx", [
  "esbuild",
  "src/main.ts",
  "--bundle",
  "--format=esm",
  "--target=es2022",
  "--outfile=dist/main.js",
  "--sourcemap",
]);

for (const name of readdirSync("src")) {
  if (!isStatic(name)) continue;
  copyFileSync(join("src", name), join("dist", name));
}

// Guard: the bundle must be self-contained. A bare/non-relative specifier means
// esbuild did not inline a dependency (e.g. `@tauri-apps/api`), which the
// webview cannot resolve at runtime -- the page then silently stays on
// "Starting...". Fail the build instead of shipping a broken bundle.
const bundle = readFileSync("dist/main.js", "utf8");
const offenders = [...bundle.matchAll(/(?:^|[\s;}])(?:import|export)[^'"]*?from\s*["']([^"']+)["']/g)]
  .map((m) => m[1])
  .filter((spec) => !spec.startsWith("./") && !spec.startsWith("../") && !spec.startsWith("/"));

if (offenders.length > 0) {
  console.error(
    `build: dist/main.js has non-relative import(s): ${offenders.join(", ")}\n` +
      "  These cannot be resolved by the webview. Check the esbuild --bundle step.",
  );
  process.exit(1);
}
