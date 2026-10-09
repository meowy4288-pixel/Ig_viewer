#!/usr/bin/env node
/**
 * Compile the pure-logic modules to a temp ESM bundle so `tests/logic.test.ts` can
 * run under node's test runner without a DOM or a bundler at test time.
 *
 * Only logic.ts is compiled: it has no DOM dependencies by design, which is what
 * makes it testable in isolation.
 */
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, rmSync } from "node:fs";

const OUT = "dist-test";

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

// logic.ts is standalone; the test imports it by its compiled name.
const res = spawnSync(
  "npx",
  [
    "--yes",
    "esbuild",
    "src/logic.ts",
    "--bundle",
    "--format=esm",
    "--target=es2022",
    "--outfile=dist-test/logic.js",
  ],
  { stdio: "inherit" },
);
if (res.status !== 0) process.exit(res.status ?? 1);

// Compile the test file itself (it imports ../dist-test/logic.js, already built).
const test = spawnSync(
  "npx",
  [
    "--yes",
    "esbuild",
    "tests/logic.test.ts",
    "--bundle",
    "--format=esm",
    "--platform=node",
    "--target=es2022",
    "--outfile=dist-test/logic.test.mjs",
    "--external:node:*",
  ],
  { stdio: "inherit" },
);
if (test.status !== 0) process.exit(test.status ?? 1);

cpSync("package.json", `${OUT}/package.json`);
