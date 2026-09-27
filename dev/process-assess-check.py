import copy
import hashlib
import json
from pathlib import Path
import runpy
import unittest
import uuid


predicates = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))


class ProcessAssessmentCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: exact standalone inspection evidence."""

    def fixture(self, standalone):
        values = runpy.run_path(str(Path(__file__).with_name("consumer-cohort-check.py")))["ConsumerCohortCheck"]().audit_fixture()
        request = values["ticket"]["request"]
        bundle = {"metadata": {"id": {"value": "bundle"}}, "body": "{\"rubric\":\"inspect actual evidence\"}"}
        previous = {"value": "pi-audit"} if standalone else None
        request.update(previous=previous, artifacts=[bundle["metadata"]["id"]])
        values["artifacts"]["audit-result"]["body"]["request"] = request
        members = [{"item": member, "refs": []} for member in request["members"]]
        guidance = [{"item": ref, "refs": []} for ref in request["guidance"]]
        frozen = values["artifacts"]["audit-input"]["body"]["input"]
        frozen.update(request=request, members=members, guidance=guidance, artifacts=[bundle], previous=None)
        workflow = None
        if standalone:
            prior = copy.deepcopy(values["artifacts"]["audit-result"])
            prior["attempt"] = prior["body"]["attempt"] = {"value": "pi-reviewer"}
            prior["body"]["request"] = {**copy.deepcopy(request), "previous": None}
            values["artifacts"]["pi-audit"] = prior
            frozen["previous"] = prior["body"]
            workflow = {"request": {"Review": {"result": previous, "mode": "Audit"}},
                "subject": {"result": previous, "work": {"Reviewer": {"mode": "Audit"}}, "members": request["members"], "candidate": None}}
        views = members + guidance
        histories = [{"id": value["item"]["id"], "page": {"entries": [{"item": value}], "hasMore": False}} for value in views]
        evaluation = {"run": "scenario", "scenario": "worked-wordfreq", "assessor": True}
        values["ticket"]["assignment"]["evaluation"] = evaluation
        values["attempts"][0]["assignment"] = values["ticket"]["assignment"]
        values["attempts"][1]["assignment"]["evaluation"] = evaluation
        values["expected"] = {"candidate": values["proof"]["candidate"], "members": request["members"], "guidance": request["guidance"],
            "memberViews": members, "guidanceViews": guidance, "bundle": bundle, "context": request["artifacts"], "harness": "Codex", "model": "gpt-6-astra",
            "previous": previous, "views": views, "histories": histories, "evaluation": evaluation}
        values["governing_input"] = {"kind": "Input", "attempt": values["run"]["attempt"]["id"], "body": {"integrationTarget": None, "workflow": workflow}}
        values["snapshot"] = {"views": views, "histories": histories, "git": values["proof"]["candidate"], "target": values["proof"]["candidate"], "clean": True,
            "claims": {"members": [value["item"] for value in views], "claims": [], "integrations": []}, "localIntegrations": []}
        for key in ["proof", "seed", "before", "after"]:
            del values[key]
        return json.loads(json.dumps(values))

    def test_precheck_and_exact_standalone_review(self):
        for standalone in [False, True]:
            with self.subTest(standalone=standalone):
                result = predicates["audit_stage"](**self.fixture(standalone))
                self.assertTrue(result["accepted"])
                self.assertEqual(result["standalone"], standalone)

    def test_completed_rejected_review_is_retained_without_acceptance(self):
        for standalone in [False, True]:
            fixture = self.fixture(standalone)
            fixture["artifacts"]["audit-result"]["body"]["report"]["Review"]["members"][0].update(verdict="ChangesRequested", findings=["A demonstrated deficiency"])
            self.assertFalse(predicates["audit_stage"](**fixture)["accepted"])

    def test_missing_bundle_base_scope_and_side_effects_fail_closed(self):
        mutations = {
            "different input base": lambda f: f["artifacts"]["audit-input"]["body"].update(base={"value": "other"}),
            "different workspace base": lambda f: f["job"]["workspace"].update(base={"value": "other"}),
            "changed bundle": lambda f: f["artifacts"]["audit-input"]["body"]["input"]["artifacts"][0].update(body="changed"),
            "omitted context": lambda f: f["artifacts"]["audit-result"]["body"]["request"].update(artifacts=[]),
            "changed snapshot": lambda f: f["snapshot"]["histories"][0]["page"].update(entries=[]),
            "wrong member input": lambda f: f["artifacts"]["audit-input"]["body"]["input"]["members"][0]["item"]["revision"].update(value="999"),
            "changed git": lambda f: f["snapshot"].update(clean=False),
            "changed integration ref": lambda f: f["snapshot"].update(target={"value": "different"}),
            "integration": lambda f: f["snapshot"].update(localIntegrations=[{"attempted": True}]),
            "retained claim": lambda f: f["snapshot"]["claims"].update(claims=[{"held": True}]),
            "absent usage": lambda f: f.update(observations=[]),
            "different model": lambda f: f["ticket"]["attempt"].update(model="other"),
            "false executable evidence": lambda f: f["artifacts"]["audit-result"]["body"].update(validation=[{"state": "Passed"}]),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                fixture = self.fixture(True)
                mutate(fixture)
                with self.assertRaises(AssertionError):
                    predicates["audit_stage"](**fixture)

    def test_standalone_requires_actual_workflow_and_exact_prior_body(self):
        for mutation in [lambda f: f["governing_input"]["body"]["workflow"]["request"]["Review"].update(result={"value": "other"}),
                         lambda f: f["governing_input"]["body"]["workflow"]["subject"].update(members=[]),
                         lambda f: f["artifacts"]["audit-input"]["body"]["input"]["previous"].update(report={"Review": {"members": []}})]:
            fixture = self.fixture(True)
            mutation(fixture)
            with self.assertRaises(AssertionError):
                predicates["audit_stage"](**fixture)

    def test_applied_changes_requested_proposal_remains_changes_requested(self):
        member = {"id": {"ledger": "Goals", "number": "1"}, "revision": {"value": "1"}}
        handle, attempt, session = {"value": "plan"}, {"value": "planner"}, {"value": "session"}
        request_id = str(uuid.UUID(bytes=hashlib.md5(b"cq-proposal:plan").digest(), version=3))
        plan = {"attempt": attempt, "request": {"work": {"Planner": {}}, "previous": None, "members": [member]}, "report": {"Plan": {"proposal": {"mutations": []}}}}
        review = {"attempt": {"value": "reviewer"}, "request": {"work": {"Reviewer": {"mode": "Plan"}}, "previous": handle, "members": [member]},
            "report": {"Review": {"members": [{"item": member["id"], "verdict": "ChangesRequested", "findings": ["Add references after allocation"]}]}}}
        artifacts = {"plan": {"attempt": attempt, "body": plan}, "review": {"body": review}}
        statuses = [{"phase": "Completed", "result": handle, "attempt": attempt, "usageDelivered": True}, {"phase": "Completed", "result": {"value": "review"}}]
        entry = {"cursor": {"value": "5"}, "item": {"item": {"id": member["id"], "revision": {"value": "2"},
            "provenance": {"request": {"value": request_id}, "actor": {"session": session}}}}}
        history = [{"page": {"entries": [entry]}}]
        lineage = predicates["planning_lineage"](history, statuses, artifacts, session)
        self.assertEqual(lineage[0]["reviews"][0]["verdicts"][0]["verdict"], "ChangesRequested")
        self.assertEqual(lineage[0]["applied"][0]["revision"], {"value": "2"})
        with self.assertRaisesRegex(AssertionError, "another session"):
            predicates["planning_lineage"](history, statuses, artifacts, {"value": "foreign"})


if __name__ == "__main__":
    unittest.main()
