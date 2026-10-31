#!/usr/bin/env python3
"""
Generate a nightly export CSV for demos and load tests.

  python3 scripts/generate_snapshot.py 200000 samples/full.generated.csv
  python3 scripts/generate_snapshot.py 200000 samples/truncated.generated.csv --cut 120000

--cut N writes only the first N rows and leaves the last line half-written,
which is what a broken export looks like.
"""
import argparse

HEADER = "employee_id,version,name,email,phone,manager_id,office_code,shift_code"


def row(i: int, version: int) -> str:
    emp = f"E{i:06d}"
    office = "BLR1" if i % 3 else "HYD1"
    shift = ["DAY", "EVENING", "NIGHT"][i % 3]
    manager = f"E{max(i // 50, 1):06d}" if i > 50 else ""
    return f"{emp},{version},Employee {i},e{i}@acme.example,+9198{i:08d},{manager},{office},{shift}"


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("rows", type=int)
    p.add_argument("out")
    p.add_argument("--version", type=int, default=1)
    p.add_argument("--cut", type=int, default=None, help="truncate after this many rows")
    a = p.parse_args()

    limit = a.cut if a.cut is not None else a.rows
    with open(a.out, "w", encoding="utf-8") as f:
        f.write(HEADER + "\n")
        for i in range(1, limit + 1):
            f.write(row(i, a.version) + "\n")
        if a.cut is not None:
            f.write(row(limit + 1, a.version)[:20])  # half a line, no newline
    print(f"wrote {limit} rows to {a.out}" + (" (truncated)" if a.cut else ""))


if __name__ == "__main__":
    main()
