#!/usr/bin/env python3
"""Inspect diagnostic JSONL or pseudonymise a copy for manual review; review candidate coverage; never infer verified match events."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import time

UUID = re.compile(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b")
KINDS = {"capture_start", "capture_end", "snapshot", "message_candidate", "manual_marker",
         "connection", "roster_gate_closed", "diagnostic_counts", "automation_marker",
         "tracker_event", "tracker_snapshot"}


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


FORMAT = re.compile(r"§[0-9a-fk-or]", re.I)
PLAYER = r"[A-Za-z0-9_]{1,16}"
COSMETIC_BY = re.compile(
    rf"(?P<victim>{PLAYER}) was [A-Za-z][A-Za-z '\-]{{0,159}} by "
    rf"(?P<killer>{PLAYER})\.(?P<final> FINAL KILL!)?", re.I)


def cosmetic_by_candidate(text):
    """Transient name extraction for diagnostic review, not verified attribution."""
    match = COSMETIC_BY.fullmatch(FORMAT.sub("", text).strip())
    if not match:
        return None
    return {"victim": match["victim"], "killer": match["killer"],
            "final": bool(match["final"])}


# Reviewed diagnostic wordings only. "Your" is retained as a message category;
# this report does not guess its team or build persistent player kill histories.
BED_TARGET = r"(?:Red|Blue|Green|Yellow|Aqua|White|Pink|Gray|Your)"
KILL_WORDING = r"(?:was (?:killed|slain) by |was knocked into the void by |slipped into void for )"
CANDIDATES = {
    "start": re.compile(r"(?:The game starts in .+|Protect your bed.*)", re.I),
    "bed_break": re.compile(r"BED DESTRUCTION > " + BED_TARGET + r" Bed (?:was destroyed by |has left the game after seeing )" + PLAYER + r"!", re.I),
    "ordinary_credited_kill": re.compile(PLAYER + " " + KILL_WORDING + PLAYER + r"\.", re.I),
    "void_death": re.compile(PLAYER + r" fell into the void\.", re.I),
    "uncredited_final_void_death": re.compile(PLAYER + r" fell into the void\. FINAL KILL!", re.I),
    "final": re.compile(PLAYER + r" .+ FINAL KILL!", re.I),
    "result": re.compile(r"(?:VICTORY!|GAME OVER!)", re.I),
    "end_leaderboard": re.compile(r"[123](?:st|nd|rd) Killer - .+ - [0-9]+", re.I),
}


def review(rows):
    """Name-free evidence references; counts are candidate messages, not game counters."""
    report = summary(rows)
    evidence = {kind: [] for kind in CANDIDATES}
    unmatched = []
    cosmetic_fallback = []
    sampled_names = set()
    for row in rows:
        if row["type"] == "snapshot":
            sampled_names.update(p["visible_name"].lower()
                                 for p in row["payload"].get("participants", [])
                                 if isinstance(p.get("visible_name"), str))
        if row["type"] != "message_candidate":
            continue
        text = FORMAT.sub("", row["payload"].get("formatted_text", "")).strip()
        ref = {"sequence": row.get("sequence"), "elapsed_ms": row.get("elapsed_ms")}
        matches = []
        if ":" not in text and not text.startswith("["):
            matches = [kind for kind, pattern in CANDIDATES.items() if pattern.fullmatch(text)]
        for kind in matches:
            evidence[kind].append(ref)
        cosmetic = cosmetic_by_candidate(text)
        # Known ordinary templates already have evidence above. Unknown finals
        # can appear here and in the final category, never in ordinary kills.
        ordinary_text = text[:-len(" FINAL KILL!")] if cosmetic and cosmetic["final"] else text
        fallback = cosmetic and not CANDIDATES["ordinary_credited_kill"].fullmatch(ordinary_text)
        if fallback:
            cosmetic_fallback.append(dict(
                ref, final_marker=cosmetic["final"],
                roster_check=("both_seen" if all(cosmetic[role].lower() in sampled_names
                                                for role in ("victim", "killer"))
                              else "not_both_seen" if sampled_names else "unavailable")))
        if not matches and not fallback:
            unmatched.append(ref)
    missing = [kind for kind in ("start", "bed_break", "ordinary_credited_kill", "final") if not evidence[kind]]
    if not evidence["result"] and not evidence["end_leaderboard"]:
        missing.append("result_or_end_leaderboard")
    if not report["max_roster_size"]:
        missing.append("roster_samples")
    issues = []
    if not report["clean_end"]:
        issues.append("Missing clean footer: still recording or interrupted")
    if report["sequence_gaps"] or not report["sequence_order_valid"]:
        issues.append("Missing or out-of-order samples")
    if report["reported_drops"]:
        issues.append("Writer reported dropped samples")
    endings = [r["payload"] for r in rows if r["type"] == "capture_end"]
    if any(p.get("reason") != "stopped" for p in endings):
        issues.append("Capture ended at a limit or with an abnormal reason")
    if any(p.get("written") != len(rows) - 1 for p in endings):
        issues.append("Footer record count does not match file")
    report.update({
        "manually_selected_modes": sorted({r["payload"]["manually_selected_mode"] for r in rows
                                            if "manually_selected_mode" in r["payload"]}),
        "candidate_counts": {kind: len(refs) for kind, refs in evidence.items()},
        "candidate_evidence": evidence,
        "uncredited_void_deaths": {
            "total": len(evidence["void_death"]) + len(evidence["uncredited_final_void_death"]),
            "non_final": len(evidence["void_death"]),
            "final": len(evidence["uncredited_final_void_death"]),
            "interpretation": "Observed fell-into-the-void messages without a credited killer; "
                "not proof of fault. Final void deaths also appear in final candidate evidence "
                "and award no kill to another player. Counts cover all observed players in this capture.",
        },
        "unclassified_candidate_evidence": unmatched,
        "cosmetic_fallback_evidence": cosmetic_fallback,
        "cosmetic_fallback_interpretation": "Unverified player-was-wording-by-player shapes; "
            "not ordinary-kill counts. Roster checks use names sampled earlier in this capture, "
            "not confirmed account identities. Final markers may also appear in final evidence.",
        "missing_examples": missing,
        "recording_issues": issues,
        "automation_evidence": [dict(r["payload"], sequence=r.get("sequence")) for r in rows
                                if r["type"] == "automation_marker"],
        "needs_manual_check": ["Match start/spawn coverage", "Roster completeness and team assignments",
                               "Visible events versus retained candidates", "Spectator/reconnect continuity"],
        "interpretation": "Missing means no recognised example, not that the event never happened. "
                          "Candidates and automatic boundaries are unverified; this is not a match-completeness verdict.",
    })
    return report


def review_path(path, watch=False):
    if not path.exists():
        raise ValueError("capture path does not exist")
    seen = {}
    pending_errors = {}
    failed = False
    while True:
        files = sorted(path.glob("capture-*.jsonl")) if path.is_dir() else [path]
        for file in files:
            try:
                stat = file.stat()
                signature = (stat.st_mtime_ns, stat.st_size)
                if watch and seen.get(file) == signature:
                    continue
                rows = read_capture(file)
                if watch and rows[-1]["type"] != "capture_end":
                    continue  # Wait for asynchronous writer drain before reporting.
                result = {"file": str(file), **review(rows)}
                seen[file] = signature
                failed = failed or bool(result["recording_issues"])
                print(json.dumps(result, indent=2), flush=True)
            except (OSError, ValueError, KeyError, TypeError) as exc:
                # A line may be half-written; only report a stable error on the next poll.
                error = str(exc)
                if watch and pending_errors.get(file) != error:
                    pending_errors[file] = error
                    continue
                if not watch or seen.get(file) != ("error", error):
                    print(json.dumps({"file": str(file), "error": error}), flush=True)
                seen[file] = ("error", error)
                failed = True
        if not watch:
            if not files:
                print("No captures found.")
            return int(failed)
        time.sleep(3)


def sanitise(rows, extra_names=()):
    names = set(extra_names)
    for row in rows:
        payload = row["payload"]
        if row["type"] == "tracker_event":
            names.update(payload[key] for key in ("actor", "subject") if payload.get(key))
        tracking = payload.get("tracking", {}) if row["type"] == "snapshot" else payload if row["type"] == "tracker_snapshot" else {}
        names.update(tracking.get("players", {}))
        for person in row["payload"].get("participants", []):
            name = person.get("visible_name")
            if name:
                names.add(name)
    names = {name.lower() for name in names}
    aliases = {name: f"Player{i:03d}" for i, name in enumerate(sorted(names), 1)}
    pattern = re.compile(r"(?<![A-Za-z0-9_])(?:" + "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True)) + r")(?![A-Za-z0-9_])", re.I) if names else None
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
                value = "".join(part if re.fullmatch(r"§[0-9a-fk-or]", part, re.I) else pattern.sub(lambda m: aliases[m[0].lower()], part)
                                for part in re.split(r"(§[0-9a-fk-or])", value, flags=re.I))
            value = re.sub(r"\b(?:mini|mega)\d+[A-Za-z]*\b", "server-redacted", value)
            return value
        if isinstance(value, list):
            return [walk(v) for v in value]
        if isinstance(value, dict):
            # Tracker player names are map keys. Keep schema keys unchanged.
            return {k: ({aliases.get(name.lower(), name): walk(counts) for name, counts in v.items()}
                        if k == "players" and isinstance(v, dict) else walk(v))
                    for k, v in value.items() if k != "observed_at"}
        return value
    return walk(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["inspect", "sanitise", "review"])
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--watch", action="store_true", help="With review, print each completed capture as it changes; Ctrl+C stops")
    parser.add_argument("--name", action="append", default=[], help="Additional visible name absent from sampled rosters")
    args = parser.parse_args()
    if args.watch and args.action != "review":
        parser.error("--watch requires review")
    try:
        if args.action == "review":
            return review_path(args.input, args.watch)
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
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
