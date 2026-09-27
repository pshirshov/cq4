import copy
import json
from pathlib import Path
import runpy
import unittest


accept = runpy.run_path(str(Path(__file__).with_name("consumer-evidence.py")))["accepted_chain"]


class ConsumerEvidenceCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: reject unlinked or inapplicable evidence."""
    def fixture(self):
        item = {"project": {"value": "project"}, "ledger": "Tasks", "number": "1"}
        members = [{"id": item, "revision": {"value": "1"}}]
        worker_id, review_id = {"value": "worker"}, {"value": "reviewer"}
        commit = {"value": "a" * 40}
        check = {"name": "consumer-oracle", "command": ["python", "oracle.py", "python"], "executionMillis": "1000", "outputBytes": 65536}
        validation = [{"check": check["name"], "state": "Passed", "artifact": {"value": "validation"}}]
        worker = {"attempt": worker_id, "request": {"request": {"value": "worker-request"}, "harness": "Codex", "members": members},
                  "candidate": commit, "report": {"Work": {"members": [{"item": item, "disposition": "CandidateReady"}]}}, "validation": validation}
        review = {"attempt": review_id, "request": {"request": {"value": "review-request"}, "harness": "Pi", "members": members, "previous": {"value": "worker-result"}},
                  "candidate": commit, "report": {"Review": {"members": [{"item": item, "verdict": "Accepted"}]}}, "validation": copy.deepcopy(validation)}
        artifacts = {"worker-result": {"kind": "Result", "attempt": worker_id, "body": worker},
                     "review-result": {"kind": "Result", "attempt": review_id, "body": review},
                     "validation": {"kind": "Validation", "attempt": worker_id, "body": {"check": check, "candidate": commit,
                         "job": {"workspace": {"base": commit}, "phase": "Settled", "exit": {"code": 0, "signal": None, "reason": "Exited", "settled": True, "hostFailure": False}}}}}
        statuses = [{"attempt": value["attempt"], "request": value["request"]["request"], "phase": "Completed", "result": {"value": handle}, "usageDelivered": True}
                    for value, handle in [(worker, "worker-result"), (review, "review-result")]]
        return json.loads(json.dumps([statuses, artifacts, {check["name"]: check}]))

    def test_exact_chain_is_accepted(self):
        statuses, artifacts, checks = self.fixture()
        result = accept(statuses, artifacts, checks)
        self.assertEqual(result["candidate"], {"value": "a" * 40})
        self.assertEqual(result["workerResult"], {"value": "worker-result"})

    def test_other_candidate_or_assignment_is_rejected(self):
        statuses, artifacts, checks = self.fixture()
        artifacts["review-result"]["body"]["candidate"] = {"value": "b" * 40}
        with self.assertRaises(AssertionError):
            accept(statuses, artifacts, checks)
        statuses, artifacts, checks = self.fixture()
        artifacts["review-result"]["body"]["report"]["Review"]["members"][0]["item"]["ledger"] = "Questions"
        with self.assertRaises(AssertionError):
            accept(statuses, artifacts, checks)

    def test_mixed_verdict_is_rejected(self):
        statuses, artifacts, checks = self.fixture()
        review = artifacts["review-result"]["body"]
        review["report"]["Review"]["members"].append({"item": review["request"]["members"][0]["id"], "verdict": "ChangesRequested"})
        with self.assertRaises(AssertionError):
            accept(statuses, artifacts, checks)

    def test_check_must_be_configured_complete_and_host_successful(self):
        for field, value in [("reason", "ExecutionDeadline"), ("settled", False), ("hostFailure", True), ("code", 1)]:
            with self.subTest(field=field):
                statuses, artifacts, checks = self.fixture()
                artifacts["validation"]["body"]["job"]["exit"][field] = value
                with self.assertRaises(AssertionError):
                    accept(statuses, artifacts, checks)
        statuses, artifacts, checks = self.fixture()
        checks["consumer-oracle"]["command"] = ["different", "oracle"]
        with self.assertRaises(AssertionError):
            accept(statuses, artifacts, checks)
        statuses, artifacts, checks = self.fixture()
        artifacts["worker-result"]["body"]["validation"] = []
        artifacts["review-result"]["body"]["validation"] = []
        with self.assertRaises(AssertionError):
            accept(statuses, artifacts, checks)


if __name__ == "__main__":
    unittest.main()
