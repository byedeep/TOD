#!/usr/bin/env python3
"""Inspect diagnostic JSONL or pseudonymise a copy for manual review; no event inference."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

UUID = re.compile(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
KINDS = {"capture_start", "capture_end", "snapshot", "message_candidate", "manual_marker",
         "connection", "roster_gate_closed", "diagnostic_counts"}


def read_capture(path):
    if path.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("capture exceeds 16 MiB")
    rows = []
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            try:
                row = json.loads(line)
                if row.get("schema_version") != 1 or row.get("type") not in KINDS:
                    raise ValueError("unknown diagnostic schema/type")
                if not isinstance(row.get("payload"), dict):
                    raise ValueError("missing payload")
                rows.append(row)
            except (ValueError, AttributeError) as exc:
                raise ValueError(f"invalid line {number}: {exc}") from exc
    if not rows:
        raise ValueError("empty capture")
    return rows


def summary(rows):
    seq = [r["sequence"] for r in rows if "sequence" in r]
    gaps = sum(max(0, b - a - 1) for a, b in zip([0] + seq, seq))
    endings = [r for r in rows if r["type"] == "capture_end"]
    return {
        "diagnostic_only": True,
        "records": dict(Counter(r["type"] for r in rows)),
        "sequence_gaps": gaps,
        "sequence_order_valid": all(b > a for a, b in zip(seq, seq[1:])),
        "clean_end": bool(endings) and rows[-1]["type"] == "capture_end",
        "reported_drops": sum(r["payload"].get("dropped", 0) for r in endings),
        "max_roster_size": max((len(r["payload"].get("participants", [])) for r in rows), default=0),
        "coverage": "unverified; selected diagnostic samples are not complete match history",
    }


def sanitise(rows, extra_names=()):
    names = set(extra_names)
    for row in rows:
        for person in row["payload"].get("participants", []):
            name = person.get("visible_name")
            if name:
                names.add(name)
    aliases = {name: f"Player{i:03d}" for i, name in enumerate(sorted(names), 1)}
    pattern = re.compile(r"(?<![A-Za-z0-9_])(?:" + "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True)) + r")(?![A-Za-z0-9_])") if names else None
    uuid_aliases = {}

    def replace_uuid(match):
        value = match[0].lower()
        if value not in uuid_aliases:
            uuid_aliases[value] = f"00000000-0000-4000-8000-{len(uuid_aliases)+1:012d}"
        return uuid_aliases[value]

    def walk(value):
        if isinstance(value, str):
            value = UUID.sub(replace_uuid, value)
            if pattern:
                value = "".join(part if re.fullmatch(r"§[0-9a-fk-or]", part, re.I) else pattern.sub(lambda m: aliases[m[0]], part)
                                for part in re.split(r"(§[0-9a-fk-or])", value, flags=re.I))
            value = re.sub(r"\b(?:mini|mega)\d+[A-Za-z]*\b", "server-redacted", value)
            return value
        if isinstance(value, list):
            return [walk(v) for v in value]
        if isinstance(value, dict):
            return {k: walk(v) for k, v in value.items() if k != "observed_at"}
        return value
    return walk(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["inspect", "sanitise"])
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--name", action="append", default=[], help="Additional visible name absent from sampled rosters")
    args = parser.parse_args()
    try:
        rows = read_capture(args.input)
        if args.action == "inspect":
            print(json.dumps(summary(rows), indent=2))
        else:
            if args.output is None:
                parser.error("sanitise requires --output (never overwrite the source)")
            # Exclusive creation protects both original captures and existing fixtures.
            import os
            fd = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                for row in sanitise(rows, args.name):
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
            print("Created a pseudonymised copy. Review all text for unsampled names/personal data before sharing; use --name for missing identities.")
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
