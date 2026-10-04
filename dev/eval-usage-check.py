#!/usr/bin/env python3
"""Focused CLI regressions for Claude transcript usage; standard library only."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class ClaudeOuterTests(unittest.TestCase):
    def report(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "transcript.jsonl"
            transcript.write_text("".join(json.dumps(entry) + "\n" for entry in entries), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("eval-usage.py")),
                 "claude-outer", "--transcript", str(transcript)],
                capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["source"], str(transcript))
            self.assertEqual(report["basis"], "local transcript; tokens only")
            self.assertEqual(report["cost"], "unknown")
            return report

    def assistant(self, identity, usage, sidechain):
        return {"type": "assistant", "message": {"id": identity, "usage": usage}, "isSidechain": sidechain}

    def test_missing_and_null_counters_are_unknown(self):
        for usage in ({"output_tokens": 5},
                      {"input_tokens": None, "cache_read_input_tokens": None,
                       "cache_creation_input_tokens": None, "output_tokens": 5}):
            with self.subTest(usage=usage):
                report = self.report([self.assistant("missing-counter-repro", usage, False)])
                self.assertEqual(report["tokens"], {"input": None, "cacheRead": None, "cacheWrite": None, "output": 5})
                for counter in ("input", "cacheRead", "cacheWrite"):
                    self.assertEqual(report["tokenCoverage"][counter],
                                     {"observedResponses": 0, "missingResponses": 1, "partialSubtotal": None})
                self.assertEqual(report["tokenCoverage"]["output"],
                                 {"observedResponses": 1, "missingResponses": 0, "partialSubtotal": None})
                self.assertIsNone(report["largestRequestInput"])
                self.assertEqual(report["requestInputCoverage"],
                                 {"completeRequests": 0, "incompleteRequests": 1, "largestCompleteRequestInput": None})

    def test_explicit_zero_is_known(self):
        report = self.report([self.assistant("zero", {
            "input_tokens": 0, "cache_read_input_tokens": 0,
            "cache_creation_input_tokens": 0, "output_tokens": 0}, False)])
        self.assertEqual(report["tokens"], {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0})
        for coverage in report["tokenCoverage"].values():
            self.assertEqual(coverage, {"observedResponses": 1, "missingResponses": 0, "partialSubtotal": None})
        self.assertEqual(report["largestRequestInput"], 0)
        self.assertEqual(report["requestInputCoverage"],
                         {"completeRequests": 1, "incompleteRequests": 0, "largestCompleteRequestInput": 0})

    def test_mixed_counter_coverage(self):
        report = self.report([
            self.assistant("first", {"input_tokens": 7, "cache_read_input_tokens": 0,
                                     "cache_creation_input_tokens": None, "output_tokens": 5}, False),
            self.assistant("second", {"input_tokens": None, "cache_read_input_tokens": 3,
                                      "cache_creation_input_tokens": 0}, False)])
        self.assertEqual(report["tokens"], {"input": None, "cacheRead": 3, "cacheWrite": None, "output": None})
        self.assertEqual(report["tokenCoverage"], {
            "input": {"observedResponses": 1, "missingResponses": 1, "partialSubtotal": 7},
            "cacheRead": {"observedResponses": 2, "missingResponses": 0, "partialSubtotal": None},
            "cacheWrite": {"observedResponses": 1, "missingResponses": 1, "partialSubtotal": 0},
            "output": {"observedResponses": 1, "missingResponses": 1, "partialSubtotal": 5}})
        null_output = self.report([self.assistant("null-output", {"output_tokens": None}, False)])
        self.assertIsNone(null_output["tokens"]["output"])
        self.assertEqual(null_output["tokenCoverage"]["output"]["observedResponses"], 0)

    def test_request_size_coverage(self):
        complete = self.assistant("complete", {"input_tokens": 10, "cache_read_input_tokens": 20,
                                               "cache_creation_input_tokens": 3}, False)
        report = self.report([complete])
        self.assertEqual(report["largestRequestInput"], 33)
        self.assertIsNone(report["tokens"]["output"])
        report = self.report([complete, self.assistant("incomplete", {"input_tokens": 100}, False)])
        self.assertIsNone(report["largestRequestInput"])
        self.assertEqual(report["requestInputCoverage"],
                         {"completeRequests": 1, "incompleteRequests": 1, "largestCompleteRequestInput": 33})

    def test_empty_transcript(self):
        report = self.report([])
        self.assertEqual(report["responses"], 0)
        self.assertEqual(report["tokens"], {"input": None, "cacheRead": None, "cacheWrite": None, "output": None})
        for coverage in report["tokenCoverage"].values():
            self.assertEqual(coverage, {"observedResponses": 0, "missingResponses": 0, "partialSubtotal": None})
        self.assertIsNone(report["largestRequestInput"])
        self.assertEqual(report["requestInputCoverage"],
                         {"completeRequests": 0, "incompleteRequests": 0, "largestCompleteRequestInput": None})

    def test_duplicate_response_counted_once(self):
        entry = self.assistant("duplicate", {"input_tokens": 2, "cache_read_input_tokens": 3,
                                             "cache_creation_input_tokens": 4, "output_tokens": 5}, True)
        report = self.report([entry, entry])
        self.assertEqual(report["responses"], 1)
        self.assertEqual(report["sidechainResponses"], 1)
        self.assertEqual(report["tokens"], {"input": 2, "cacheRead": 3, "cacheWrite": 4, "output": 5})
        self.assertEqual(report["largestRequestInput"], 9)
        self.assertEqual(report["tokenCoverage"]["input"]["observedResponses"], 1)

    def test_unreadable_and_unidentified_reporting(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "reporting.jsonl"
            transcript.write_text("not-json\n" + json.dumps({"type": "assistant", "message": {"usage": {"output_tokens": 5}}}) + "\n", encoding="utf-8")
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("eval-usage.py")),
                                     "claude-outer", "--transcript", str(transcript)],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual(report["unreadableLines"], 1)
            self.assertEqual(report["entriesWithoutResponseId"], 1)
            self.assertEqual(report["responses"], 0)


if __name__ == "__main__":
    unittest.main()
