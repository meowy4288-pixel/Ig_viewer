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
    """Return (ordered ids, ord->id map). A TSV row from --dump-order and an ord row
    from Rust ingest both carry no oracle id, so ids are resolved through
    ord = (ts_ms, file number, index), which is unique per message.

    The map is asserted TOTAL and INJECTIVE before anything is compared: a map that
    is not a bijection means expected.json itself is inconsistent, and that must
    surface as its own error rather than as a confusing positional diff."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    for t in data["threads"]:
        if t["key"] != thread:
            continue
        by_ord, seen_ids = {}, set()
        for o in t["order"]:
            key = (o["ts_ms"], o["ord"]["file"], o["ord"]["index"])
            if key in by_ord:
                raise SystemExit(
                    f"parity_check: NOT INJECTIVE -- oracle ord {key} appears twice "
                    f"(ids {by_ord[key]} and {o['id']})")
            if o["id"] in seen_ids:
                raise SystemExit(
                    f"parity_check: NOT INJECTIVE -- oracle id {o['id']} appears twice")
            by_ord[key] = o["id"]
            seen_ids.add(o["id"])
        if len(by_ord) != len(t["order"]):
            raise SystemExit("parity_check: NOT TOTAL -- oracle order entries collapsed")
        # id -> ord, for reporting which Rust message failed to map
        id_to_ord = {v: k for k, v in by_ord.items()}
        return [o["id"] for o in t["order"]], by_ord, id_to_ord
    raise SystemExit(f"parity_check: thread {thread!r} not in {path}")


def load_actual_tsv(path):
    """Rows from ig_chat_rebuild.py --dump-order:
       ts_ms \t file \t index \t sender \t content
    Only the first three columns are the identity; the rest is human-readable and is
    deliberately ignored so message text never influences the comparison."""
    ords = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            raise SystemExit(f"parity_check: malformed TSV row: {line!r}")
        ords.append(parts[:3])
    return ords


def load_actual_ord(path):
    """Rust ingest emits one row per message: ts_ms, file_no, idx_in_file, seq.

    The oracle key is (ts_ms, file_no, idx_in_file) -- `seq` is carried for
    diagnostics but is not part of the mapping, because expected.json records ord as
    (file, index) rather than a sequence number."""
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != 4:
            raise SystemExit(
                f"parity_check: ord row must be exactly "
                f"ts_ms\\tfile_no\\tidx_in_file\\tseq, got {len(parts)} columns: {line!r}")
        try:
            rows.append(((int(parts[0]), int(parts[1]), int(parts[2])), int(parts[3])))
        except ValueError as e:
            raise SystemExit(f"parity_check: non-integer ord row: {line!r}") from e
    return rows


def load_actual_ids(path):
    return [ln.strip() for ln in Path(path).read_text(encoding="utf-8").splitlines() if ln.strip()]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--expected", type=Path, default=Path("fake-data/expected.json"))
    ap.add_argument("--actual", type=Path, required=True)
    ap.add_argument("--thread", required=True)
    ap.add_argument("--format", choices=("auto", "tsv", "ids", "ord"), default="auto")
    a = ap.parse_args()

    want, by_ord, id_to_ord = load_expected(a.expected, a.thread)
    fmt = a.format
    if fmt == "auto":
        lines = a.actual.read_text(encoding="utf-8").splitlines()
        head = lines[0].split("\t") if lines else []
        if not head:
            fmt = "ids"
        elif all(p.lstrip("-").isdigit() for p in head):
            # all-numeric: ord rows are exactly 4 numbers, tsv rows start with 3
            fmt = "ord" if len(head) == 4 else "tsv"
        else:
            fmt = "tsv"

    if fmt == "ord":
        # Rust ingest: (ts_ms, file_no, idx_in_file, seq) per message.
        rows = load_actual_ord(a.actual)
        unmapped = [(r, seq) for r, seq in rows if r not in by_ord]
        if unmapped:
            print(f"parity FAIL: {a.thread}")
            print(f"  {len(unmapped)} Rust message(s) have no oracle mapping:")
            for (ts, fno, idx), seq in unmapped[:5]:
                print(f"    ts_ms={ts} file={fno} idx={idx} seq={seq}")
            return 1
        got = [by_ord[r] for r, _ in rows]
    elif fmt == "tsv":
        # ig_chat_rebuild.py --dump-order rows are
        #   ts_ms \t file \t index \t sender \t content
        ords = [(int(p[0]), int(p[1]), int(p[2])) for p in load_actual_tsv(a.actual)]
        unmapped = [o for o in ords if o not in by_ord]
        if unmapped:
            print(f"parity FAIL: {a.thread}")
            print(f"  {len(unmapped)} row(s) have no oracle mapping, first: {unmapped[0]}")
            return 1
        got = [by_ord[o] for o in ords]
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
