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
  filterThreads,
  firstIndexAtOrAfter,
  inDisplayOrder,
  isAudioKind,
  isImageKind,
  isRenderable,
  isSafeMediaUrl,
  isVideoKind,
  isoDate,
  monthOfIsoDate,
  splitLinks,
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
