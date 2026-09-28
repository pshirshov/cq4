"""Behavioral Active Effectual / controlled filesystem: retained release spending."""
import json
from pathlib import Path
import runpy
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent


class ReleaseReportTests(unittest.TestCase):
    def setUp(self):
        self.module = runpy.run_path(str(ROOT / "dev/release-report.py"))
        self.temporary = tempfile.TemporaryDirectory(prefix="cq-release-report-")
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def session(self, name, ids, restored):
        path = self.root / name
        path.mkdir()
        result = {"status": "assessment-passed", "restoredEvidence": None if restored is None else str(restored)}
        usage = {scope: {"total": {"known": str(100 * len(ids) if scope == "direct" else 0)}} for scope in ["direct", "shared", "unattributed"]}
        usage.update(incompleteMeters="0", attemptsWithoutMeters="0")
        for name, value in [("result.json", result), ("usage-summary.json", {"UsageSummary": {"report": usage}}),
                            ("attempts.json", {"UsageAttempts": {"page": {"hasMore": False, "entries": [{"attempt": {"id": {"value": value}}} for value in ids]}}})]:
            (path / name).write_text(json.dumps(value))
        return path

    def suite(self, paths):
        return {"attempts": [{"stage": "process-assess", "id": str(index), "evidence": str(path)} for index, path in enumerate(paths)]}

    def test_adopted_assessment_includes_intermediate_correction_spending(self):
        earlier = self.session("correction", ["a", "b"], None)
        final = self.session("assessment", ["c"], earlier)
        result = self.module["experimental_usage"](self.suite([final]), "")
        self.assertEqual(result["distinctAttempts"], 3)
        self.assertEqual(result["knownTokens"], 300)

    def test_registered_ancestor_is_not_counted_again(self):
        earlier = self.session("correction", ["a", "b"], None)
        final = self.session("assessment", ["c"], earlier)
        result = self.module["experimental_usage"](self.suite([earlier, final]), "")
        self.assertEqual(result["distinctAttempts"], 3)
        self.assertEqual(result["knownTokens"], 300)

    def test_distinct_exports_cannot_repeat_attempt_identity(self):
        first = self.session("first", ["same"], None)
        second = self.session("second", ["same"], None)
        with self.assertRaisesRegex(AssertionError, "twice"):
            self.module["experimental_usage"](self.suite([first, second]), "")

    def test_truncated_failed_transcript_preserves_accounting_and_marks_traffic_unavailable(self):
        path = self.session("failed", ["a"], None)
        (path / "result.json").write_text(json.dumps({"status": "failed"}))
        totals = {name: {"known": "100" if name in ["input", "total"] else "0", "unknown": "0", "estimated": "0"}
                  for name in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]}
        totals["unknownCosts"] = "1"
        zero = {name: {"known": "0", "unknown": "0", "estimated": "0"} for name in totals if name != "unknownCosts"}
        zero["unknownCosts"] = "0"
        usage = {"direct": totals, "shared": zero, "unattributed": zero, "incompleteMeters": "0", "attemptsWithoutMeters": "0", "costs": {"entries": [], "hasMore": False}}
        (path / "usage-summary.json").write_text(json.dumps({"UsageSummary": {"report": usage}}))
        (path / "usage-audit-000.json").write_text('{}')
        session = path / "sessions/one"
        payload = session / "payload/a"
        payload.mkdir(parents=True)
        (session / "run.json").write_text(json.dumps({"attempt": {"id": {"value": "a"}, "harness": "Claude"}}))
        (payload / "stdout").write_text('{"type":"assistant"')
        amendment = {"reason": "Retained evaluator correction", "beforeSources": {"instruction": "original"}, "afterSources": {"instruction": "clarified"}}
        suite = {**self.suite([path]), "selected": {}, "status": "incomplete", "sourceSha256": amendment["beforeSources"], "amendments": [amendment]}
        suite["attempts"][0]["sourceEpoch"] = 1
        (self.root / "suite.json").write_text(json.dumps(suite))
        report = self.module["report"](self.root)
        self.assertEqual(report["experiments"]["knownTokens"], 100)
        self.assertEqual(report["sourceProvenance"]["amendments"], [amendment])
        self.assertEqual(report["sourceProvenance"]["invocations"][0]["sourceEpoch"], 1)
        self.assertEqual(report["sessions"][0]["parentTraffic"]["coverage"], "unavailable")
        self.assertTrue(any("traffic" in gap["reason"] for gap in report["instrumentationGaps"]))


if __name__ == "__main__":
    unittest.main()
