import copy
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-assess-evidence.py")))


class DefectAssessmentCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: inspection and accounting evidence boundaries."""

    def test_current_views_do_not_hide_history_mutation(self):
        before = {"views": [{"current": "Done"}], "histories": [{"old": "Ready"}], "git": {"value": "candidate"}, "clean": True, "localIntegrations": []}
        predicates["unchanged"](before, copy.deepcopy(before))
        for field, value in [("histories", [{"old": "Done"}]), ("git", {"value": "other"}), ("clean", False), ("localIntegrations", [{}])]:
            with self.subTest(field=field), self.assertRaises(AssertionError):
                predicates["unchanged"](before, {**before, field: value})

    def test_rejected_experiments_are_bound_and_counted_once(self):
        with tempfile.TemporaryDirectory() as scratch:
            directory = Path(scratch)
            def write(name, value):
                path = directory / name
                path.write_text(json.dumps(value))
                return hashlib.sha256(path.read_bytes()).hexdigest()
            manifest = {"status": "failed", "error": "Missing guidance"}
            summary = {key: {"total": {"known": str(value)}} for key, value in [("direct", 40), ("shared", 10), ("unattributed", 20)]}
            summary.update(incompleteMeters="2", attemptsWithoutMeters="0")
            entry = {"directory": str(directory), "status": "failed", "attempts": ["child", "governor"], "knownTokens": 70,
                "incompleteMeters": "2", "attemptsWithoutMeters": "0", "manifestSha256": write("result.json", manifest),
                "usageSha256": write("usage-summary.json", {"UsageSummary": {"report": summary}})}
            write("attempts.json", {"UsageAttempts": {"page": {"entries": [{"attempt": {"id": {"value": name}}} for name in entry["attempts"]]}}})
            report = {"runs": [entry], "distinctAttempts": 2, "knownTokens": 70}
            write("experiments.json", report)
            actual = predicates["experiments"](directory / "experiments.json")
            self.assertEqual(actual["runs"][0]["error"], "Missing guidance")
            self.assertEqual(actual["knownTokens"], 70)
            write("experiments.json", {**report, "runs": [entry, entry], "knownTokens": 140, "distinctAttempts": 4})
            with self.assertRaisesRegex(AssertionError, "duplicated"):
                predicates["experiments"](directory / "experiments.json")
            write("experiments.json", report)
            write("result.json", {**manifest, "status": "passed"})
            with self.assertRaisesRegex(AssertionError, "manifest changed"):
                predicates["experiments"](directory / "experiments.json")

    def test_preflight_counts_json_escaping_in_materialized_artifacts(self):
        snapshot = {"views": [{"item": {"id": {"project": {"value": "project"}, "ledger": "Tasks"}}}]}
        prepared = {"handles": [], "artifacts": {}, "resolved": []}
        self.assertLess(predicates["bound"](snapshot, {}, "{}", prepared)["upperBound"], 192 * 1024)
        raw = '"' * (100 * 1024)
        self.assertLess(len(raw.encode()), 128 * 1024)
        with self.assertRaisesRegex(AssertionError, "encoded assessment"):
            predicates["bound"](snapshot, {}, raw, prepared)


if __name__ == "__main__":
    unittest.main()
