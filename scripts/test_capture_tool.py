import unittest
from capture_tool import sanitise, summary

class CaptureToolTest(unittest.TestCase):
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

if __name__ == "__main__":
    unittest.main()
