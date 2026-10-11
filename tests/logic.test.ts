/**
 * Unit tests for the pure display logic.
 *
 * Run with: npm run test:ts
 * Compiled to a temporary ESM bundle so the tests exercise the same code the app
 * ships, without a DOM.
 */

import assert from "node:assert/strict";
import { test } from "node:test";
import {
  DEFAULT_PREFS,
  filterThreads,
  motionEnabled,
  parsePrefs,
  resolveTheme,
  firstIndexAtOrAfter,
  inDisplayOrder,
  isAudioKind,
  isImageKind,
  isRenderable,
  isSafeMediaUrl,
  isVideoKind,
  isoDate,
  monthOfIsoDate,
  clampSnippet,
  splitLinks,
  splitSnippet,
  tsForIsoDate,
  withDayBreaks,
} from "../dist-test/logic.js";

const msg = (ts: number, seq: number) => ({ ts_ms: ts, seq });

test("display order is ts ASC then seq DESC", () => {
  const got = inDisplayOrder([msg(2, 0), msg(1, 5), msg(1, 1), msg(2, 9)]);
  assert.deepEqual(
    got.map((m) => [m.ts_ms, m.seq]),
    [
      [1, 5],
      [1, 1],
      [2, 9],
      [2, 0],
    ],
  );
});

test("ties put the later encounter first", () => {
  // Files store messages newest-first, so a higher seq was sent earlier.
  const got = inDisplayOrder([msg(100, 0), msg(100, 3), msg(100, 1)]);
  assert.deepEqual(
    got.map((m) => m.seq),
    [3, 1, 0],
  );
});

test("splitLinks only links http and https", () => {
  const runs = splitLinks("see https://a.example/x and javascript:alert(1) and igmedia://media/a/1");
  const hrefs = runs.filter((r) => r.href).map((r) => r.href);
  assert.deepEqual(hrefs, ["https://a.example/x"]);
  // The non-link text is preserved verbatim.
  assert.equal(runs.map((r) => r.text).join(""), "see https://a.example/x and javascript:alert(1) and igmedia://media/a/1");
});

test("splitLinks handles multiple links and empty text", () => {
  const runs = splitLinks("a https://one.example b http://two.example c");
  assert.deepEqual(
    runs.filter((r) => r.href).map((r) => r.href),
    ["https://one.example", "http://two.example"],
  );
  assert.deepEqual(splitLinks(""), [{ text: "", href: null }]);
});

test("media url safety check rejects anything but igmedia", () => {
  assert.ok(isSafeMediaUrl("igmedia://media/abc/1"));
  for (const bad of [
    "https://evil.example/x.png",
    "file:///etc/passwd",
    "data:image/png;base64,AAA",
    "tauri://localhost/x",
    "",
    null,
    42,
  ]) {
    assert.equal(isSafeMediaUrl(bad), false, `should reject ${String(bad)}`);
  }
});

test("media kind routing", () => {
  assert.ok(isImageKind("photos") && isImageKind("gifs") && isImageKind("sticker"));
  assert.ok(isVideoKind("videos"));
  assert.ok(isAudioKind("audio_files"));
  assert.equal(isImageKind("audio_files"), false);
});

test("renderable requires an available igmedia url", () => {
  assert.ok(isRenderable({ media_id: 1, kind: "photos", available: true, url: "igmedia://media/a/1" }));
  assert.equal(isRenderable({ media_id: 1, kind: "photos", available: false, url: "igmedia://media/a/1" }), false);
  assert.equal(isRenderable({ media_id: 1, kind: "photos", available: true }), false);
  assert.equal(
    isRenderable({ media_id: 1, kind: "photos", available: true, url: "https://x/y.png" }),
    false,
  );
});

test("splitSnippet separates matched terms from plain text", () => {
  const runs = splitSnippet("the [build] is [green] again");
  assert.deepEqual(runs, [
    { text: "the ", hit: false },
    { text: "build", hit: true },
    { text: " is ", hit: false },
    { text: "green", hit: true },
    { text: " again", hit: false },
  ]);
  // Reassembly loses nothing: only the markers are dropped.
  assert.equal(runs.map((r) => r.text).join(""), "the build is green again");
});

test("splitSnippet handles edge cases without throwing", () => {
  assert.deepEqual(splitSnippet(""), [], "empty snippet yields no runs");
  assert.deepEqual(splitSnippet("[all]"), [{ text: "all", hit: true }]);
  // Unbalanced marker: nothing is treated as a hit, and no text is lost.
  const dangling = splitSnippet("dangling [term");
  assert.equal(dangling.some((r) => r.hit), false);
  assert.equal(dangling.map((r) => r.text).join(""), "dangling [term");
  // Markup inside the snippet is just text; it must never become DOM.
  const nasty = splitSnippet("hi [<script>alert(1)</script>]");
  assert.equal(nasty.map((r) => r.text).join(""), "hi <script>alert(1)</script>");
  assert.ok(nasty.some((r) => r.hit && r.text.includes("<script>")));
});

test("clampSnippet only shortens long snippets", () => {
  assert.equal(clampSnippet("short", 20), "short");
  assert.ok(clampSnippet("x".repeat(50), 10).length <= 11);
});

test("isoDate round trip", () => {
  const d = new Date(2024, 0, 31, 23, 59, 59);
  assert.equal(isoDate(d.getTime()), "2024-01-31");
  // tsForIsoDate returns a timestamp for local midnight, not a Date.
  assert.equal(new Date(tsForIsoDate("2024-01-31")!).getDate(), 31);
  assert.equal(isoDate(tsForIsoDate("2024-01-31")!), "2024-01-31");
  assert.equal(tsForIsoDate("nonsense"), null);
  assert.equal(monthOfIsoDate("2024-02-01"), "2024-02");
  assert.equal(monthOfIsoDate("bad"), null);
});

test("firstIndexAtOrAfter finds the first message at or after a time", () => {
  const ms = [10, 20, 30, 40].map((ts) => ({ ts_ms: ts }));
  assert.equal(firstIndexAtOrAfter(ms, 25), 2);
  assert.equal(firstIndexAtOrAfter(ms, 10), 0);
  assert.equal(firstIndexAtOrAfter(ms, 99), 3, "clamps to the last message");
  assert.equal(firstIndexAtOrAfter([], 5), 0);
});

test("day breaks appear once per calendar day", () => {
  const day = (n: number) => new Date(2024, 0, n, 12).getTime();
  const got = withDayBreaks([{ ts_ms: day(1) }, { ts_ms: day(1) }, { ts_ms: day(2) }]);
  assert.deepEqual(
    got.map((g) => g.dayBreak),
    [true, false, true],
  );
});

test("thread filter matches title and key", () => {
  const threads = [
    { key: "mira_okonkwo_4821", title: "Mira Okonkwo", message_count: 157, last_ts_ms: 1 },
    { key: "dev_team_7730", title: "Dev Team", message_count: 60, last_ts_ms: 2 },
  ];
  assert.equal(filterThreads(threads, "mira").length, 1);
  assert.equal(filterThreads(threads, "dev").length, 1);
  assert.equal(filterThreads(threads, "").length, 2);
  assert.equal(filterThreads(threads, "zzz").length, 0);
});

// ------------------------------------------------------------------ prefs
test("prefs: missing storage yields defaults", () => {
  assert.deepEqual(parsePrefs(null), DEFAULT_PREFS);
});

test("prefs: animations default to on with no stored value", () => {
  assert.equal(parsePrefs(null).motion, true);
  assert.equal(motionEnabled(parsePrefs(null), false), true);
});

test("prefs: user turning animations off is respected", () => {
  const p = parsePrefs(JSON.stringify({ motion: false }));
  assert.equal(p.motion, false);
  assert.equal(motionEnabled(p, false), false);
});

test("prefs: OS reduced motion overrides the user preference", () => {
  // This is the case that made the settings toggle appear unchecked by default.
  const p = parsePrefs(null);
  assert.equal(motionEnabled(p, false), true);
  assert.equal(motionEnabled(p, true), false);
});

test("prefs: corrupted storage falls back instead of throwing", () => {
  for (const bad of ["", "not json", "[]", "null", '{"motion":"yes"}']) {
    assert.deepEqual(parsePrefs(bad), DEFAULT_PREFS, `input: ${bad}`);
  }
});

test("prefs: out-of-range and wrong-typed fields are ignored", () => {
  const p = parsePrefs(
    JSON.stringify({ fontSize: 999, theme: "neon", density: "dense", timezone: "x".repeat(500) }),
  );
  assert.equal(p.fontSize, 15);
  assert.equal(p.theme, "system");
  assert.equal(p.density, "cozy");
  assert.equal(p.timezone, "");
});

test("prefs: valid fields survive alongside invalid ones", () => {
  const p = parsePrefs(JSON.stringify({ fontSize: 18, theme: "light", motion: false }));
  assert.equal(p.fontSize, 18);
  assert.equal(p.theme, "light");
  assert.equal(p.motion, false);
});

test("prefs: theme system resolves against the OS preference", () => {
  assert.equal(resolveTheme("system", true), "light");
  assert.equal(resolveTheme("system", false), "dark");
  assert.equal(resolveTheme("light", false), "light");
  assert.equal(resolveTheme("dark", true), "dark");
});
