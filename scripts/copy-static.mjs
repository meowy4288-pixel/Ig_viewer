import { copyFileSync, mkdirSync, readdirSync } from "node:fs";
import { join } from "node:path";

const STATIC_EXT = [".html", ".css", ".svg", ".png", ".ico"];

mkdirSync("dist", { recursive: true });
for (const name of readdirSync("src")) {
  if (STATIC_EXT.some((ext) => name.endsWith(ext))) {
    copyFileSync(join("src", name), join("dist", name));
  }
}
