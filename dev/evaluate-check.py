"""Behavioral Active Blackbox / local-process Communication suite acceptance gates."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
CHILD = '''#!/usr/bin/env python3
import json, os, pathlib, sys
stage = "candidate" if pathlib.Path(__file__).name == "consumer-eval" else "assessment"
root = pathlib.Path(os.environ["CQ_EVIDENCE_ROOT"])
name = sys.argv[1] if stage == "candidate" else pathlib.Path(sys.argv[1]).name.removeprefix("candidate-")
evidence = root / (stage + "-" + name)
evidence.mkdir()
status = "candidate-passed" if stage == "candidate" else "assessment-passed"
if os.environ["CQ_FIXTURE_REJECT"] == stage:
    status = "failed"
(evidence / "result.json").write_text(json.dumps({"status": status}))
print("Evidence:", evidence)
if os.environ["CQ_FIXTURE_EXIT"] == stage:
    sys.exit(1)
'''


class EvaluateTests(unittest.TestCase):
    def run_scenario(self, reject, exit_stage, expected_count, succeeds):
        with tempfile.TemporaryDirectory(prefix="cq-evaluate-check-") as temporary:
            root = Path(temporary)
            dev = root / "dev"
            dev.mkdir()
            shutil.copy(ROOT / "dev/evaluate", dev / "evaluate")
            for name in ["consumer-eval", "consumer-assess"]:
                path = dev / name
                path.write_text(CHILD)
                path.chmod(0o700)
            output = subprocess.run([sys.executable, str(dev / "evaluate"), "--suite", "first-slice"],
                                    env=dict(os.environ, CQ_EVIDENCE_ROOT=str(root / "evidence"),
                                             CQ_FIXTURE_REJECT=reject, CQ_FIXTURE_EXIT=exit_stage),
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(output.returncode == 0, succeeds, output.stdout + output.stderr)
            reports = list((root / "evidence").glob("*/suite.json"))
            self.assertEqual(len(reports), 1)
            report = json.loads(reports[0].read_text())
            self.assertEqual(report["status"], "candidates-assessed" if succeeds else "failed")
            self.assertEqual(len(list(reports[0].parent.glob("*/result.json"))), expected_count)
            if succeeds:
                self.assertEqual([(s["harness"], s["language"]) for s in report["scenarios"]],
                                 [("claude", "python"), ("codex", "go"), ("pi", "python")])
                self.assertTrue(all("candidate" in s and "assessment" in s for s in report["scenarios"]))

    def test_all_candidates_require_an_assessment(self):
        self.run_scenario("", "", 6, True)

    def test_rejected_candidate_stops_the_suite(self):
        self.run_scenario("candidate", "", 1, False)

    def test_rejected_assessment_stops_the_suite(self):
        self.run_scenario("assessment", "", 2, False)

    def test_nonzero_exit_cannot_be_hidden_by_passed_manifest(self):
        self.run_scenario("", "candidate", 1, False)


if __name__ == "__main__":
    unittest.main()
