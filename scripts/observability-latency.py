#!/usr/bin/env python3
"""Summarize real backend visibility receipts. Missing receipts stay in the denominator."""
import argparse
import csv
import datetime as dt
import json
import math
import re
from collections import defaultdict

COMPONENTS = ("android", "ios", "desktop", "daemon", "relay")
FIELDS = {"component", "kind", "event_id", "occurred_at", "visible_at", "eligible"}


def timestamp(value):
    result = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("timestamps must include an explicit UTC offset")
    return result


def summarize(rows):
    samples, seen = defaultdict(list), set()
    for index, row in enumerate(rows):
        if index >= 100_000:
            raise ValueError("too many samples")
        if any(not isinstance(row.get(field), str) for field in FIELDS):
            raise ValueError("missing or invalid receipt cell")
        if row["component"] not in COMPONENTS or row["kind"] not in ("error", "log"):
            raise ValueError("unknown component or kind")
        event_id = row["event_id"]
        if not re.fullmatch(r"[0-9a-f]{32}", event_id) or event_id == "0" * 32:
            raise ValueError("invalid safe event id")
        identity = (row["component"], row["kind"], event_id)
        if identity in seen:
            raise ValueError("duplicate receipt")
        seen.add(identity)
        occurred = timestamp(row["occurred_at"])
        latency = None if not row["visible_at"] else (timestamp(row["visible_at"]) - occurred).total_seconds()
        if latency is not None and latency < 0:
            raise ValueError("receipt precedes occurrence; correct clock evidence first")
        if row["eligible"] not in ("true", "false"):
            raise ValueError("eligible must explicitly be true or false")
        samples[row["component"], row["kind"]].append((row["eligible"] == "true", latency))
    report = []
    for component in COMPONENTS:
        for kind in ("error", "log"):
            group = samples[component, kind]
            eligible = [latency for allowed, latency in group if allowed]
            received = sorted(latency for latency in eligible if latency is not None)
            # Treat missing as beyond the observation window, not zero or excluded fast successes.
            rank = math.ceil(len(eligible) * .95)
            p95 = received[rank - 1] if rank and rank <= len(received) else None
            status = ("insufficient_samples" if len(eligible) < 20 else
                      "missing_receipts" if len(received) != len(eligible) else
                      "pass" if p95 is not None and p95 < 60 else "fail")
            report.append({"component": component, "kind": kind, "eligible": len(eligible),
                           "excluded": len(group) - len(eligible), "received": len(received),
                           "missing": len(eligible) - len(received), "p95_seconds": p95,
                           "status": status})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", help="CSV with component,kind,event_id,occurred_at,visible_at,eligible")
    args = parser.parse_args()
    try:
        with open(args.csv_file, newline="", encoding="utf-8") as stream:
            reader = csv.DictReader(stream)
            if not FIELDS.issubset(reader.fieldnames or []):
                raise ValueError("missing required CSV columns")
            result = summarize(reader)
    except (OSError, ValueError, KeyError, csv.Error):
        parser.error("invalid receipt file; check schema, IDs, duplicate rows and timezone-aware timestamps")
    print(json.dumps(result, indent=2, allow_nan=False))
    return 0 if all(item["status"] == "pass" for item in result) else 1


if __name__ == "__main__":
    raise SystemExit(main())
