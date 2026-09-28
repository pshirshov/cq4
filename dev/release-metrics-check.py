"""Behavioral Active Blackbox / Atomic: native event traffic and role accounting."""
import copy
import json
from pathlib import Path
import runpy
import unittest


ROOT = Path(__file__).resolve().parent.parent


class ReleaseMetricsTests(unittest.TestCase):
    def setUp(self):
        self.metrics = runpy.run_path(str(ROOT / "dev/release-metrics.py"))

    def test_three_native_envelopes_preserve_dispatch_retries_errors_and_drilldowns(self):
        calls = [("one", "cq_host", "dispatch", {"StartChoice": {"choice": {"value": "choice"}, "harness": "Codex", "fence": {}}}, {"Status": {"value": {}}}),
                 ("two", "cq_host", "dispatch", {"StartChoice": {"choice": {"value": "choice"}, "harness": "Codex", "fence": {}}}, {"Failed": {"fault": {}}}),
                 ("three", "cq", "read", {"selection": {"ArtifactText": {"id": {"value": "result"}, "offset": 0, "limit": 10}}}, {"ArtifactText": {"page": {"text": "body"}}})]
        observed = []
        for harness in ["Claude", "Codex", "Pi"]:
            with self.subTest(harness=harness):
                events = []
                for identity, server, tool, args, result in calls:
                    content = [{"type": "text", "text": json.dumps(result)}]
                    if harness == "Claude":
                        events += [{"type": "assistant", "message": {"content": [{"type": "tool_use", "id": identity, "name": "mcp__" + server + "__" + tool, "input": args}]}},
                                   {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": identity, "content": content}]}}]
                    elif harness == "Codex":
                        item = {"id": identity, "type": "mcp_tool_call", "server": server, "tool": tool, "arguments": args, "result": None, "error": None}
                        events += [{"type": "item.started", "item": item}, {"type": "item.completed", "item": {**item, "result": {"content": content}}}]
                    else:
                        events += [{"type": "tool_execution_start", "toolCallId": identity, "toolName": server + "_" + tool, "args": args},
                                   {"type": "tool_execution_end", "toolCallId": identity, "result": {"content": content}, "isError": False}]
                value = self.metrics["traffic"](events, harness)
                self.assertEqual(value["calls"], {"cq_host.dispatch": 2, "cq.read": 1})
                self.assertEqual(value["repeatedDispatchRequestIds"], 1)
                self.assertEqual(value["observedToolErrors"], ["two"])
                self.assertEqual(len(value["artifactDrillDowns"]), 1)
                self.assertEqual(value["missingReplies"], [])
                observed.append(value)
        self.assertEqual(observed[0], observed[1])
        self.assertEqual(observed[1], observed[2])

    def test_role_dimensions_reconcile_to_shared_audit_without_adding_cache_subsets(self):
        zero = {name: {"known": "0", "unknown": "0", "estimated": "0"} for name in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]}
        totals = copy.deepcopy(zero)
        for name, count in [("input", 100), ("output", 10), ("total", 110), ("cacheRead", 70)]:
            totals[name]["known"] = str(count)
        totals["cacheWrite"]["unknown"] = "1"
        attempt = {"id": {"value": "a"}, "parent": {"value": "governor"}, "role": "Reviewer", "harness": "Codex"}
        assignment = {"evaluation": {"assessor": True}, "attribution": "Shared"}
        entry = {"attempt": attempt, "assignment": assignment, "meter": {"key": "meter"}, "projection": {"totals": totals}}
        summary = {"direct": zero, "shared": totals, "unattributed": zero}
        view = {"attempt": attempt, "assignment": assignment}
        result = self.metrics["meter_totals"]([entry], [view], summary)
        self.assertEqual(result["dimensions"]["hierarchy"]["child"]["total"]["known"], 110)
        self.assertEqual(result["dimensions"]["evaluation"]["independent-assessor"]["total"]["known"], 110)
        self.assertEqual(result["cache"]["cacheRead"]["knownFraction"], "7/10")
        self.assertTrue(result["cache"]["cacheRead"]["allInputsObserved"])
        self.assertFalse(result["cache"]["cacheWrite"]["allInputsObserved"])
        with self.assertRaisesRegex(AssertionError, "Duplicate"):
            self.metrics["meter_totals"]([entry, entry], [view], summary)
        with self.assertRaisesRegex(AssertionError, "disagrees"):
            self.metrics["meter_totals"]([], [view], summary)


if __name__ == "__main__":
    unittest.main()
