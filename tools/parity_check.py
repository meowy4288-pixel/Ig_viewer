#!/usr/bin/env python3
"""
Parity check: compare the ordered message ids produced by an implementation against
fake-data/expected.json (the oracle).

This script deliberately has NO sorting or dedupe logic of its own. It only diffs
two ordered id lists and reports the first mismatch. expected.json is the oracle: if
an implementation disagrees, the implementation is wrong -- do not "fix" expected.json
to match.

Usage:
    # compare expected.json against ig_chat_rebuild.py --dump-order output
    python3 tools/parity_check.py \\
        --expected fake-data/expected.json \\
        --actual /tmp/order.tsv --thread mira_okonkwo_4821

    # compare against a plain id-per-line file (what M3's Rust ingest will emit)
    python3 tools/parity_check.py \\
        --expected fake-data/expected.json \\
        --actual /tmp/rust-order.txt --thread mira_okonkwo_4821
"""
import argparse
import json
import sys
from pathlib import Path

def load_expected(path, thread):
    """Return (ordered ids, ord->id map). A TSV row from --dump-order carries no id,
    so ids are resolved through ord = (ts_ms, file number, index), which is unique
    per message."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    for t in data["threads"]:
        if t["key"] == thread:
            ids = [o["id"] for o in t["order"]]
            by_ord = {(str(o["ts_ms"]), str(o["ord"]["file"]), str(o["ord"]["index"])): o["id"]
                      for o in t["order"]}
            return ids, by_ord
    raise SystemExit(f"parity_check: thread {thread!r} not in {path}")


def load_actual_tsv(path):
    ids = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            raise SystemExit(f"parity_check: malformed TSV row: {line!r}")
        ids.append((parts[0], parts[1], parts[2]))
    return ids


def load_actual_ids(path):
    return [ln.strip() for ln in Path(path).read_text(encoding="utf-8").splitlines() if ln.strip()]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--expected", type=Path, default=Path("fake-data/expected.json"))
    ap.add_argument("--actual", type=Path, required=True)
    ap.add_argument("--thread", required=True)
    ap.add_argument("--format", choices=("auto", "tsv", "ids"), default="auto")
    a = ap.parse_args()

    want, by_ord = load_expected(a.expected, a.thread)
    fmt = a.format
    if fmt == "auto":
        lines = a.actual.read_text(encoding="utf-8").splitlines()
        fmt = "tsv" if lines and "\t" in lines[0] else "ids"

    if fmt == "tsv":
        got = [by_ord.get(o, f"<unknown ord {o}>") for o in load_actual_tsv(a.actual)]
    else:
        got = load_actual_ids(a.actual)

    if got == want:
        print(f"parity OK: {a.thread} ({len(want)} ids, order identical)")
        return 0

    print(f"parity FAIL: {a.thread}")
    print(f"  expected {len(want)} ids, got {len(got)}")
    n = min(len(want), len(got))
    for i in range(n):
        if want[i] != got[i]:
            print(f"  first mismatch at index {i}: expected {want[i]}, got {got[i]}")
            lo = max(0, i - 3)
            print("  context:")
            for j in range(lo, min(n, i + 4)):
                mark = ">>" if j == i else "  "
                print(f"   {mark} [{j:3}] expected={want[j]} got={got[j]}")
            break
    else:
        i = n
        print(f"  first mismatch at index {i}: expected {want[i] if i < len(want) else '<end>'}, "
              f"got {got[i] if i < len(got) else '<end>'}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
