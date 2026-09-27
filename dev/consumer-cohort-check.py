import copy
import json
from pathlib import Path
import runpy
import unittest


support = runpy.run_path(str(Path(__file__).with_name("consumer-evidence-check.py")))
cohort = runpy.run_path(str(Path(__file__).with_name("consumer-cohort.py")))


class ConsumerCohortCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: require linked multi-item execution evidence."""

    def fixture(self):
        statuses, artifacts, checks = support["ConsumerEvidenceCheck"]().fixture()
        worker = artifacts["worker-result"]["body"]
        reviewer = artifacts["review-result"]["body"]
        first = worker["request"]["members"][0]
        second = copy.deepcopy(first)
        second["id"]["number"] = "2"
        members = [first, second]
        seed = {"members": copy.deepcopy(members), "drafts": [
            {"content": {"Task": {"acceptance": ["Acceptance one", "Acceptance two"]}}} for _ in members]}
        routes = {"Governor": "Claude", "Planner": "Codex", "Worker": "Claude", "Reviewer": "Pi"}
        governor = {"id": {"value": "governor"}, "session": {"value": "session"}, "parent": None, "role": "Governor", "harness": "Claude"}
        plan = {"attempt": {"value": "planner"}, "base": {"value": "b" * 40}, "candidate": None, "validation": [],
            "request": {"request": {"value": "plan-request"}, "harness": "Codex", "members": members},
            "report": {"Plan": {"members": [{"item": member["id"], "disposition": "Assessed"} for member in members],
                "proposal": None, "assessments": [{"compatibility": "Compatible", "members": [
                    {"member": member, "acceptance": [{"criterion": index, "checks": ["consumer-oracle"], "inspection": "Inspect this scope"} for index in range(2)]}
                    for member in members]}]}}}
        artifacts["plan-result"] = {"kind": "Result", "attempt": plan["attempt"], "body": plan}
        statuses.append({"attempt": plan["attempt"], "request": plan["request"]["request"], "phase": "Completed", "result": {"value": "plan-result"}, "usageDelivered": True})
        worker["request"]["members"] = reviewer["request"]["members"] = members
        worker["report"]["Work"]["members"] = [{"item": member["id"], "disposition": "CandidateReady"} for member in members]
        reviewer["report"]["Review"]["members"] = [{"item": member["id"], "verdict": "Accepted"} for member in members]
        worker["request"]["harness"] = "Claude"
        worker["base"], reviewer["base"] = plan["base"], worker["candidate"]
        validation = copy.deepcopy(artifacts["validation"])
        validation["attempt"] = reviewer["attempt"]
        artifacts["review-validation"] = validation
        reviewer["validation"][0]["artifact"] = {"value": "review-validation"}
        tickets = {}
        attempts = [{"attempt": governor, "assignment": {"members": [], "attribution": "Unattributed"}}]
        for role, value in [("Planner", plan), ("Worker", worker), ("Reviewer", reviewer)]:
            name = value["attempt"]["value"]
            request = value["request"]
            request.update(work={role: {} if role == "Planner" else {"mode": "Implement" if role == "Worker" else "Candidate"}},
                artifacts=[{"value": "plan-result"}] if role == "Worker" else [], guidance=[], limits={"executionMillis": "1000"},
                previous={"value": "worker-result"} if role == "Reviewer" else None)
            attempt = {"id": value["attempt"], "session": governor["session"], "parent": governor["id"], "role": role, "harness": routes[role]}
            assignment = {"members": [member["id"] for member in members], "attribution": "Shared", "cohort": name + "-cohort"}
            ticket = {"attempt": attempt, "assignment": assignment, "request": request, "selection": {"value": name + "-selection"}}
            tickets[name] = ticket
            attempts.append({"attempt": attempt, "assignment": assignment})
            choice = {field: request[field] for field in ["work", "members", "guidance", "artifacts", "previous", "limits"]}
            choice.update(id=request["request"], cohort=assignment["cohort"], reason="CompatibleAssessment" if role == "Worker" else "ExactPrevious")
            artifacts[name + "-selection"] = {"kind": "Selection", "attempt": governor["id"], "body": {"decision": {
                "artifact": ticket["selection"], "choices": [choice]}}}
            artifacts[name + "-input"] = {"kind": "Input", "attempt": value["attempt"], "body": {
                "base": value["base"], "checks": list(checks.values()), "input": {"request": request, "members": [{"item": member} for member in members]}}}
        observations = [{"upload": {"observation": {"attempt": view["attempt"]["id"], "counters": {
            "input": {"value": "100", "measurement": "Observed"}, "output": {"value": "10", "measurement": "Observed"}}}}} for view in attempts]
        return json.loads(json.dumps({"statuses": statuses, "artifacts": artifacts, "checks": checks, "tickets": tickets,
            "attempts": attempts, "observations": observations, "seed": seed, "governor": governor, "routes": routes}))

    def accept(self, fixture):
        chain = support["accept"](fixture["statuses"], fixture["artifacts"], fixture["checks"])
        return cohort["accepted"](chain, **{key: value for key, value in fixture.items() if key != "checks"})

    def test_exact_two_member_chain_passes_with_real_parent_routes_and_fresh_check(self):
        fixture = self.fixture()
        accepted = self.accept(fixture)
        self.assertEqual(accepted["members"], fixture["seed"]["members"])
        self.assertEqual(accepted["meteredAttempts"], 4)

    def test_estimated_only_usage_cannot_establish_observed_coverage(self):
        fixture = self.fixture()
        for entry in fixture["observations"]:
            for counter in entry["upload"]["observation"]["counters"].values():
                counter["measurement"] = "Estimated"
        with self.assertRaises(AssertionError):
            self.accept(fixture)

    def test_assessment_handoff_requires_current_revisions_and_no_active_holds(self):
        seed = self.fixture()["seed"]
        preview = {"members": copy.deepcopy(seed["members"]), "claims": [], "integrations": []}
        cohort["assessment_handoff"](seed, preview)
        for failure in ["revision", "claim", "integration"]:
            with self.subTest(failure=failure):
                changed = copy.deepcopy(preview)
                if failure == "revision":
                    changed["members"][0]["revision"]["value"] = "2"
                else:
                    changed["claims" if failure == "claim" else "integrations"] = [{"retained": failure}]
                with self.assertRaises(AssertionError):
                    cohort["assessment_handoff"](seed, changed)

    def test_identity_choice_assessment_and_meter_gaps_are_rejected(self):
        def missing_meter(f):
            f["observations"].pop()

        def missing_counter(f):
            f["observations"][0]["upload"]["observation"]["counters"] = {"input": {"value": None}, "output": {"value": None}}

        def inherited_check(f):
            f["artifacts"]["review-result"]["body"]["validation"] = copy.deepcopy(f["artifacts"]["worker-result"]["body"]["validation"])

        def missing_choice(f):
            f["artifacts"]["worker-selection"]["body"]["decision"]["choices"] = []

        def missing_mapping(f):
            f["artifacts"]["plan-result"]["body"]["report"]["Plan"]["assessments"][0]["members"][0]["acceptance"].pop()

        mutations = {
            "different seed": lambda f: f["seed"]["members"][0]["id"].update(number="999"),
            "stale revision": lambda f: f["seed"]["members"][0]["revision"].update(value="2"),
            "foreign parent": lambda f: f["attempts"][1]["attempt"].update(parent={"value": "foreign"}),
            "wrong route": lambda f: f["attempts"][1]["attempt"].update(harness="Pi"),
            "missing choice": missing_choice,
            "different selection": lambda f: f["artifacts"]["worker-selection"]["body"]["decision"]["choices"][0].update(previous={"value": "other"}),
            "unknown assessment": lambda f: f["artifacts"]["plan-result"]["body"]["report"]["Plan"]["assessments"][0].update(compatibility="Unknown"),
            "different base": lambda f: f["artifacts"]["planner-input"]["body"].update(base={"value": "c" * 40}),
            "incomplete mapping": missing_mapping,
            "inherited check": inherited_check,
            "false check author": lambda f: f["artifacts"]["review-validation"].update(attempt={"value": "worker"}),
            "mixed outcomes": lambda f: f["artifacts"]["review-result"]["body"]["report"]["Review"]["members"][0].update(verdict="ChangesRequested"),
            "missing meter": missing_meter,
            "unknown counters": missing_counter,
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                fixture = self.fixture()
                mutation(fixture)
                with self.assertRaises(AssertionError):
                    self.accept(fixture)


if __name__ == "__main__":
    unittest.main()
