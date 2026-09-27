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
        return cohort["accepted"](chain, base=fixture["artifacts"]["plan-result"]["body"]["base"], **fixture)

    def split_fixture(self):
        fixture = self.fixture()
        correction = self.fixture()
        names = ["worker", "reviewer", "worker-result", "review-result", "worker-request", "review-request", "validation", "review-validation",
                 "worker-selection", "reviewer-selection", "worker-input", "reviewer-input"]
        rename = {name: "corrected-" + name for name in names}
        rename["a" * 40] = "c" * 40

        def renamed(value):
            if isinstance(value, dict):
                return {key: renamed(member) for key, member in value.items()}
            if isinstance(value, list):
                return [renamed(member) for member in value]
            return rename.get(value, value) if isinstance(value, str) else value

        correction = renamed(correction)
        for key in ["artifacts", "tickets"]:
            correction[key] = {rename.get(name, name): value for name, value in correction[key].items()}
        member = correction["seed"]["members"][1]
        for role, name, handle in [("Worker", "corrected-worker", "corrected-worker-result"), ("Reviewer", "corrected-reviewer", "corrected-review-result")]:
            result = correction["artifacts"][handle]["body"]
            result["request"]["members"] = [copy.deepcopy(member)]
            if role == "Worker":
                result["request"]["artifacts"] = [{"value": "worker-result"}, {"value": "review-result"}]
            key = "Work" if role == "Worker" else "Review"
            result["report"][key]["members"] = [result["report"][key]["members"][1]]
            ticket = correction["tickets"][name]
            ticket["request"] = copy.deepcopy(result["request"])
            ticket["assignment"].update(members=[member["id"]], attribution="Direct", cohort=None)
            view = next(value for value in correction["attempts"] if value["attempt"]["id"]["value"] == name)
            view["assignment"] = copy.deepcopy(ticket["assignment"])
            choice = correction["artifacts"][name + "-selection"]["body"]["decision"]["choices"][0]
            choice.update({key: copy.deepcopy(result["request"][key]) for key in ["members", "artifacts"]})
            choice.update(cohort=None, reason="FreshFromBase" if role == "Worker" else "ExactPrevious")
            execution = correction["artifacts"][name + "-input"]["body"]
            execution["input"] = {"request": copy.deepcopy(result["request"]), "members": [{"item": copy.deepcopy(member)}]}
        fixture["artifacts"]["review-result"]["body"]["report"]["Review"]["members"][1]["verdict"] = "ChangesRequested"
        fixture["artifacts"].update({key: value for key, value in correction["artifacts"].items() if key.startswith("corrected-")})
        fixture["statuses"].extend(value for value in correction["statuses"] if value["attempt"]["value"].startswith("corrected-"))
        fixture["tickets"].update({key: value for key, value in correction["tickets"].items() if key.startswith("corrected-")})
        fixture["attempts"].extend(value for value in correction["attempts"] if value["attempt"]["id"]["value"].startswith("corrected-"))
        fixture["observations"].extend(value for value in correction["observations"] if value["upload"]["observation"]["attempt"]["value"].startswith("corrected-"))
        return fixture

    def test_split_requires_original_process_and_fresh_final_evidence(self):
        fixture = self.split_fixture()
        proof = self.accept(fixture)
        self.assertEqual(len(proof["lineage"]), 2)
        self.assertEqual(len(proof["members"]), 2)
        self.assertEqual(len(proof["finalMembers"]), 1)
        self.assertEqual(proof["independentAssessment"], "pending")
        mutations = {
            "missing original worker": lambda f: f["artifacts"]["corrected-worker-result"]["body"]["request"].update(artifacts=[{"value": "review-result"}]),
            "wrong fresh base": lambda f: f["artifacts"]["corrected-worker-result"]["body"].update(base={"value": "d" * 40}),
            "wrong split reason": lambda f: f["artifacts"]["corrected-worker-selection"]["body"]["decision"]["choices"][0].update(reason="ExactPrevious"),
            "wrong attribution": lambda f: f["attempts"][-1]["assignment"].update(attribution="Shared"),
            "no rejected member": lambda f: f["artifacts"]["review-result"]["body"]["report"]["Review"]["members"][1].update(verdict="Blocked"),
            "missing final oracle": lambda f: f["artifacts"]["corrected-review-result"]["body"].update(validation=[]),
            "old final oracle": lambda f: f["artifacts"]["corrected-review-validation"]["body"].update(candidate={"value": "a" * 40}),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(fixture)
                mutation(changed)
                with self.assertRaises(AssertionError):
                    self.accept(changed)

    def test_repeated_singleton_correction_preserves_real_previous_review_and_base(self):
        fixture = self.split_fixture()

        def renamed(value):
            if isinstance(value, dict):
                return {key: renamed(member) for key, member in value.items()}
            if isinstance(value, list):
                return [renamed(member) for member in value]
            if isinstance(value, str):
                return value.replace("corrected-", "retry-") if value.startswith("corrected-") else ("d" * 40 if value == "c" * 40 else value)
            return value

        retry = renamed(fixture)
        for key in ["artifacts", "tickets"]:
            retry[key] = {name.replace("corrected-", "retry-"): value for name, value in retry[key].items()}
        worker = retry["artifacts"]["retry-worker-result"]["body"]
        worker.update(base={"value": "c" * 40})
        worker["request"].update(previous={"value": "corrected-review-result"}, artifacts=[])
        retry["tickets"]["retry-worker"]["request"] = copy.deepcopy(worker["request"])
        execution = retry["artifacts"]["retry-worker-input"]["body"]
        execution.update(base=worker["base"])
        execution["input"]["request"] = copy.deepcopy(worker["request"])
        choice = retry["artifacts"]["retry-worker-selection"]["body"]["decision"]["choices"][0]
        choice.update(previous=worker["request"]["previous"], artifacts=[], reason="ExactPrevious")
        fixture["artifacts"]["corrected-review-result"]["body"]["report"]["Review"]["members"][0]["verdict"] = "ChangesRequested"
        for key in ["artifacts", "tickets"]:
            fixture[key].update({name: value for name, value in retry[key].items() if name.startswith("retry-")})
        fixture["statuses"].extend(value for value in retry["statuses"] if value["attempt"]["value"].startswith("retry-"))
        fixture["attempts"].extend(value for value in retry["attempts"] if value["attempt"]["id"]["value"].startswith("retry-"))
        fixture["observations"].extend(value for value in retry["observations"] if value["upload"]["observation"]["attempt"]["value"].startswith("retry-"))
        self.assertEqual(len(self.accept(fixture)["lineage"]), 3)
        changed = copy.deepcopy(fixture)
        for body in [changed["artifacts"]["retry-worker-result"]["body"], changed["artifacts"]["retry-worker-input"]["body"]]:
            body["base"] = {"value": "b" * 40}
        with self.assertRaisesRegex(AssertionError, "Correction changed assignment or candidate base"):
            self.accept(changed)

    def audit_fixture(self):
        fixture = self.split_fixture()
        proof = self.accept(fixture)
        seed = fixture["seed"]
        seed["goal"] = {"id": {"project": {"value": "project"}, "ledger": "Goals", "number": "1"}, "revision": {"value": "1"}}
        governor = {"id": {"value": "audit-governor"}, "session": {"value": "audit-session"}, "role": "Governor", "parent": None}
        attempt = {"id": {"value": "auditor"}, "session": governor["session"], "parent": governor["id"], "role": "Reviewer", "harness": "Codex", "model": "gpt-6-astra"}
        request = {"request": {"value": "audit-request"}, "work": {"Reviewer": {"mode": "Audit"}}, "harness": "Codex", "members": seed["members"],
                   "previous": None, "guidance": [seed["goal"]], "artifacts": [proof[field] for field in ["plannerResult", "workerResult", "reviewResult", "finalWorkerResult", "finalReviewResult"]]}
        result = {"attempt": attempt["id"], "request": request, "base": proof["candidate"], "candidate": None, "validation": [],
                  "report": {"Review": {"proposal": None, "members": [{"item": value["id"], "verdict": "Accepted"} for value in seed["members"]]}}}
        assignment = {"attribution": "Shared", "members": [value["id"] for value in seed["members"]]}
        preview = {"members": seed["members"], "claims": [], "integrations": []}
        observations = [{"upload": {"observation": {"attempt": value["id"], "counters": {"input": {"value": "5", "measurement": "Observed"}, "output": {"value": None}}}}} for value in [attempt, governor]]
        values = {"proof": proof, "seed": seed, "statuses": [{"attempt": attempt["id"], "request": request["request"], "phase": "Completed", "usageDelivered": True, "result": {"value": "audit-result"}}],
                  "artifacts": {"audit-result": {"kind": "Result", "attempt": attempt["id"], "body": result},
                    "audit-input": {"kind": "Input", "attempt": attempt["id"], "body": {"base": proof["candidate"], "input": {"request": request, "members": [{"item": member} for member in seed["members"]]}}}},
                  "ticket": {"attempt": attempt, "assignment": assignment, "request": request},
                  "run": {"attempt": governor, "base": proof["candidate"], "repository": "/consumer"},
                  "job": {"workspace": {"base": proof["candidate"], "attempt": attempt["id"], "owner": governor["session"], "repository": "/consumer"},
                          "phase": "Settled", "exit": {"settled": True, "hostFailure": False, "reason": "Exited", "code": 0, "signal": None}},
                  "attempts": [{"attempt": attempt, "assignment": assignment}, {"attempt": governor, "assignment": {}}],
                  "observations": observations, "before": preview, "after": preview}
        return json.loads(json.dumps(values))

    def test_whole_scope_audit_requires_final_base_exact_members_and_observed_usage(self):
        fixture = self.audit_fixture()
        result = cohort["audited"](**fixture)
        self.assertTrue(result["wholeScopeAccepted"])
        mutations = {
            "wrong base": lambda f: f["job"]["workspace"].update(base={"value": "a" * 40}),
            "stale revision": lambda f: f["after"]["members"][0]["revision"].update(value="2"),
            "rejected member": lambda f: f["artifacts"]["audit-result"]["body"]["report"]["Review"]["members"][0].update(verdict="ChangesRequested"),
            "missing member": lambda f: f["artifacts"]["audit-result"]["body"]["report"]["Review"]["members"].pop(),
            "wrong parent": lambda f: f["ticket"]["attempt"].update(parent={"value": "other"}),
            "missing usage": lambda f: f["observations"].pop(),
            "claim retained": lambda f: f["after"].update(claims=[{"value": "held"}]),
            "stale frozen member": lambda f: f["artifacts"]["audit-input"]["body"]["input"]["members"][0]["item"]["revision"].update(value="2"),
            "unsettled": lambda f: f["job"]["exit"].update(settled=False),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(fixture)
                mutation(changed)
                with self.assertRaises(AssertionError):
                    cohort["audited"](**changed)

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
