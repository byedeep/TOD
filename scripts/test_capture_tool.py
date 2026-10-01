import unittest
from capture_tool import sanitise, summary, review, review_path, cosmetic_by_candidate, read_capture
import contextlib
import io
import json
from pathlib import Path
import tempfile

class CaptureToolTest(unittest.TestCase):
    def test_tracker_records_remain_readable_and_redact_names_without_a_roster(self):
        rows = [
            {"type": "tracker_event", "payload": {"actor": "ExampleActor", "subject": "ExampleVictim"}},
            {"type": "snapshot", "payload": {"tracking": {"players": {"exampleactor": {"regular_kills": 2}}}}},
            {"type": "tracker_snapshot", "payload": {"players": {"examplevictim": {"final_deaths": 1}}}},
            {"type": "message_candidate", "payload": {"formatted_text": "§aExampleVictim §7was killed by §cEXAMPLEACTOR§7."}},
        ]
        for row in rows:
            row["schema_version"] = 1
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "capture-test.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows))
            self.assertEqual(read_capture(path), rows)
        clean = sanitise(rows)
        self.assertNotIn("exampleactor", json.dumps(clean).lower())
        self.assertNotIn("examplevictim", json.dumps(clean).lower())
        actor = clean[0]["payload"]["actor"]
        self.assertIn(actor, clean[1]["payload"]["tracking"]["players"])
        self.assertIn("regular_kills", clean[1]["payload"]["tracking"]["players"][actor])

    def test_uncredited_void_counter_excludes_credited_kills_and_chat(self):
        texts = [
            "§aPlayerA §7fell into the void.",
            "PlayerA fell into the void.",  # Two observations, not one deduplicated string.
            "§cPlayerB §7fell into the void.§b FINAL KILL!",
            "PlayerC was knocked into the void by PlayerA.",
            "PlayerC slipped into void for PlayerA.",
            "PlayerC was knocked into the void by PlayerA. FINAL KILL!",
            "PlayerC: PlayerA fell into the void.",
            "Party > PlayerA fell into the void.",
            "[MVP+] PlayerA fell into the void.",
            "PlayerA fell into the void. extra text",
            "PlayerA fell to their death.",
        ]
        rows = [{"type": "message_candidate", "sequence": i + 1,
                 "payload": {"formatted_text": text}} for i, text in enumerate(texts)]
        report = review(rows)
        counter = report["uncredited_void_deaths"]
        self.assertEqual((counter["total"], counter["non_final"], counter["final"]), (3, 2, 1))
        self.assertEqual(report["candidate_counts"]["ordinary_credited_kill"], 2)
        self.assertEqual(report["candidate_counts"]["final"], 2)
        self.assertEqual(report["candidate_evidence"]["uncredited_final_void_death"][0]["sequence"], 3)
        self.assertNotIn("PlayerA", json.dumps(report))
        empty = review([])["uncredited_void_deaths"]
        self.assertEqual((empty["total"], empty["non_final"], empty["final"]), (0, 0, 0))

    def test_cosmetic_fallback_extracts_names_but_does_not_award_kills(self):
        self.assertEqual(cosmetic_by_candidate("§aPlayerA §7was oinked by §cPlayerB§7."),
                         {"victim": "PlayerA", "killer": "PlayerB", "final": False})
        for text in ["PlayerC: PlayerA was oinked by PlayerB.",
                     "Party > PlayerA was oinked by PlayerB.",
                     "[MVP+] PlayerA was oinked by PlayerB.",
                     "From PlayerC: PlayerA was oinked by PlayerB.",
                     "I saw PlayerA was oinked by PlayerB.",
                     "PlayerA was oinked by PlayerB. extra text",
                     "PlayerA was oinked by PlayerNameTooLong17.",
                     "PlayerA was oinked. hello by PlayerB.",
                     "PlayerA was " + "x" * 161 + " by PlayerB.",
                     "PlayerA fell into the void."]:
            self.assertIsNone(cosmetic_by_candidate(text), text)
        rows = [{"type": "message_candidate", "sequence": 1, "payload": {
            "formatted_text": "PlayerA was oinked by PlayerB."}},
            {"type": "snapshot", "sequence": 2, "payload": {"participants": [
                {"visible_name": "playera"}, {"visible_name": "PlayerB"}]}},
            {"type": "message_candidate", "sequence": 3, "payload": {
                "formatted_text": "PlayerA was oinked by PlayerB. FINAL KILL!"}},
            {"type": "message_candidate", "sequence": 4, "payload": {
                "formatted_text": "PlayerC was given the cold shoulder by PlayerB."}},
            {"type": "message_candidate", "sequence": 5, "payload": {
                "formatted_text": "PlayerA was killed by PlayerB."}},
            {"type": "message_candidate", "sequence": 6, "payload": {
                "formatted_text": "PlayerA was killed by PlayerB. FINAL KILL!"}}]
        report = review(rows)
        self.assertEqual(report["candidate_counts"]["ordinary_credited_kill"], 1)
        self.assertEqual(report["candidate_counts"]["final"], 2)
        fallback = report["cosmetic_fallback_evidence"]
        self.assertEqual([r["roster_check"] for r in fallback],
                         ["unavailable", "both_seen", "not_both_seen"])
        self.assertEqual([r["final_marker"] for r in fallback], [False, True, False])
        self.assertNotIn("PlayerA", json.dumps(report))

    def test_reviewed_bed_and_kill_variants_keep_finals_separate(self):
        # Anonymised templates from capture 542ced7f and its client log.
        texts = [
            "§bBED DESTRUCTION > Blue Bed was destroyed by PlayerA!",
            "BED DESTRUCTION > Yellow Bed has left the game after seeing PlayerB!",
            "BED DESTRUCTION > Your Bed was destroyed by PlayerA!",
            "PlayerC was knocked into the void by PlayerA.",
            "PlayerA was killed by PlayerC.",
            "§aPlayerD §7slipped into void for §cPlayerE§7.",
            "PlayerA slipped into void for PlayerE.",
            "PlayerA slipped into void for PlayerE.",  # Separate observations, not deduplicated text.
            "PlayerC was knocked into the void by PlayerA. FINAL KILL!",
            "PlayerA slipped into void for PlayerE. FINAL KILL!",
            "PlayerC had a small brain moment while fighting PlayerB. FINAL KILL!",
            "PlayerC was not able to block clutch against PlayerB. FINAL KILL!",
            "PlayerC was locked outside during a snow storm by PlayerD. FINAL KILL!",
            "PlayerC: PlayerA slipped into void for PlayerE.",
            "Party > BED DESTRUCTION > Your Bed was destroyed by PlayerA!",
            "BED DESTRUCTION > Your Bed was destroyed by PlayerA! extra text",
            "PlayerA slipped into void for PlayerE. extra text",
        ]
        rows = [{"type": "message_candidate", "sequence": i + 1,
                 "payload": {"formatted_text": text}} for i, text in enumerate(texts)]
        report = review(rows)
        self.assertEqual(report["candidate_counts"]["bed_break"], 3)
        self.assertEqual(report["candidate_counts"]["ordinary_credited_kill"], 5)
        self.assertEqual(report["candidate_counts"]["final"], 5)
        self.assertEqual(len(report["unclassified_candidate_evidence"]), 4)
        self.assertNotIn("PlayerA", json.dumps(report))

    def test_names_uuids_and_timestamps_removed_but_formatting_preserved(self):
        rows = [{"type": "snapshot", "observed_at": "2026-09-25T12:00:00Z", "payload": {
            "participants": [{"visible_name": "Alice", "supplied_uuid": "12345678-1234-1234-1234-123456789012"}],
            "text": "§cAlice was killed by Bob. FINAL KILL! mini123A"}}]
        clean = sanitise(rows, ["Bob"])
        self.assertNotIn("observed_at", clean[0])
        self.assertEqual(clean[0]["payload"]["text"], "§cPlayer001 was killed by Player002. FINAL KILL! server-redacted")
        self.assertNotIn("12345678", str(clean))
        self.assertEqual(rows[0]["payload"]["participants"][0]["visible_name"], "Alice")

    def test_crash_and_gap_are_visible(self):
        report = summary([{"type": "capture_start", "sequence": 1, "payload": {}},
                          {"type": "snapshot", "sequence": 4, "payload": {}}])
        self.assertFalse(report["clean_end"])
        self.assertEqual(report["sequence_gaps"], 2)

    def test_review_distinguishes_finals_deaths_and_leaderboard(self):
        texts = ["§aAlice fell into the void.", "Bob was killed by Alice. FINAL KILL!",
                 "1st Killer - Alice - 9", "Bob was killed by Alice.",
                 "BED DESTRUCTION > Blue Bed was destroyed by Alice!",
                 "Alice: Bob was killed by Alice.", "Unknown cosmetic variant"]
        rows = [{"type": "message_candidate", "sequence": i + 1,
                 "payload": {"formatted_text": text}} for i, text in enumerate(texts)]
        rows.append({"type": "capture_end", "payload": {"written": len(texts), "dropped": 0, "reason": "stopped"}})
        report = review(rows)
        self.assertEqual(report["candidate_counts"]["ordinary_credited_kill"], 1)
        self.assertEqual(report["candidate_counts"]["void_death"], 1)
        self.assertEqual(report["candidate_counts"]["final"], 1)
        self.assertEqual(report["candidate_counts"]["bed_break"], 1)
        self.assertEqual(report["candidate_counts"]["end_leaderboard"], 1)
        self.assertEqual(report["candidate_counts"]["result"], 0)
        self.assertIn("start", report["missing_examples"])
        self.assertIn("roster_samples", report["missing_examples"])
        self.assertEqual(len(report["unclassified_candidate_evidence"]), 2)
        self.assertNotIn("Alice", json.dumps(report))
        self.assertIn("unverified", report["coverage"])

    def test_limits_drops_and_partial_automation_are_visible(self):
        rows = [{"type": "automation_marker", "sequence": 1,
                 "payload": {"label": "auto-start", "evidence": "active-sidebar-partial-start"}},
                {"type": "snapshot", "sequence": 3, "payload": {"participants": [{}, {}]}},
                {"type": "capture_end", "payload": {"written": 2, "dropped": 1, "reason": "stopped-time-limit"}}]
        report = review(rows)
        self.assertEqual(len(report["recording_issues"]), 3)
        self.assertEqual(report["automation_evidence"][0]["evidence"], "active-sidebar-partial-start")
        self.assertIn("start", report["missing_examples"])
        self.assertNotIn("roster_samples", report["missing_examples"])

    def test_directory_review_continues_after_corrupt_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "capture-a.jsonl").write_text('{"partial":')
            (root / "capture-b.jsonl").write_text(json.dumps({"schema_version": 1,
                "type": "capture_start", "sequence": 1, "payload": {}}) + "\n")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.assertEqual(review_path(root), 1)
            self.assertIn("capture-a.jsonl", output.getvalue())
            self.assertIn("capture-b.jsonl", output.getvalue())
            self.assertIn("still recording or interrupted", output.getvalue())

if __name__ == "__main__":
    unittest.main()
