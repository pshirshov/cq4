import copy
import json
from pathlib import Path
import runpy
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-plan-evidence.py")))


class DefectPlanningCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: evidence-based graph and complete retained history."""

    def fixture(self):
        intake = runpy.run_path(str(Path(__file__).with_name("defect-evidence-check.py")))
        before = intake["DefectEvidenceCheck"]().snapshot()
        before["views"][0]["item"]["draft"]["content"]["Defect"].update(severity="Medium", observed="bad tokens", expected="ASCII tokens", reproduction="python3 -m unittest -v")
        before["views"][1]["item"]["draft"]["content"]["Research"].update(question="Selection or normalization?", recommendation=None)
        after = copy.deepcopy(before)
        handles = [{"value": name} for name in ["observations", "probe", "research"]]
        evidence = [{"origin": "ModelDeclared", "description": "Observed at the exact fixture/input", "citations": [{"Artifact": {"id": handle}} for handle in handles]}]
        def advance(view):
            history = next(page for page in after["histories"] if page["id"] == view["item"]["id"])
            view["item"]["revision"]["value"] = str(int(view["item"]["revision"]["value"]) + 1)
            history["page"]["entries"].insert(0, {"item": copy.deepcopy(view)})
        # Freeze each historical snapshot before updating its current view.
        after["histories"] = copy.deepcopy(after["histories"])
        for view in after["views"][1:]:
            content = view["item"]["draft"]["content"]
            if "Research" in content:
                content["Research"].update(status="Concluded", findings=copy.deepcopy(evidence), conclusion="Normalization did not introduce the bad characters in this case", recommendation="Correct selection")
            else:
                content["Hypothesis"].update(status="Supported", evidence=copy.deepcopy(evidence), adjudication="Qualified by the tested input")
            advance(view)
        def create(ledger, content, parent):
            value = {"item": {"id": {"project": {"value": "project"}, "ledger": ledger, "number": "1"}, "revision": {"value": "1"}, "draft": {"content": content}},
                     "refs": [{"relation": "DerivedFrom", "target": parent["item"]["id"]}]}
            after["views"].append(value)
            after["histories"].append({"id": value["item"]["id"], "page": {"entries": [{"item": copy.deepcopy(value)}], "hasMore": False}})
            return value
        defect = after["views"][0]
        goal = create("Goals", {"Goal": {"status": "Open", "acceptance": ["Original oracle passes"]}}, defect)
        defect["refs"].append({"relation": "Produces", "target": goal["item"]["id"]})
        advance(defect)
        milestone = create("Milestones", {"Milestone": {"status": "Open"}}, goal)
        task = create("Tasks", {"Task": {"status": "Ready", "acceptance": ["Original tests pass"], "result": None, "validation": []}}, goal)
        goal["refs"] += [{"relation": "Produces", "target": item["item"]["id"]} for item in [milestone, task]]
        advance(goal)
        task["refs"].append({"relation": "PartOf", "target": milestone["item"]["id"]})
        milestone["refs"].append({"relation": "Contains", "target": task["item"]["id"]})
        advance(task)
        advance(milestone)
        return before, after, handles

    def test_truthful_supported_branches_and_derived_repair_plan(self):
        before, after, handles = self.fixture()
        proof = predicates["graph"](before, after, [handles[0]], handles[1:])
        self.assertEqual(proof["task"]["ledger"], "Tasks")
        self.assertEqual(proof["goal"]["ledger"], "Goals")
        self.assertEqual(len(predicates["revisions"](before, after)), 11)

    def test_history_erasure_and_changed_hypothesis_are_rejected(self):
        before, after, handles = self.fixture()
        after["histories"][2]["page"]["entries"][-1]["item"]["item"]["draft"]["content"]["Hypothesis"]["claim"] = "rewritten history"
        with self.assertRaisesRegex(AssertionError, "prior history"):
            predicates["graph"](before, after, [handles[0]], handles[1:])
        before, after, handles = self.fixture()
        after["views"][2]["item"]["draft"]["content"]["Hypothesis"]["claim"] = "replacement claim"
        after["histories"][2]["page"]["entries"][0]["item"] = copy.deepcopy(after["views"][2])
        with self.assertRaisesRegex(AssertionError, "tested hypothesis"):
            predicates["graph"](before, after, [handles[0]], handles[1:])

    def test_missing_links_wrong_evidence_and_premature_completion_are_rejected(self):
        changes = [lambda v: v["refs"].clear(), lambda v: v["item"]["draft"]["content"]["Task"].update(status="Done")]
        for change in changes:
            before, after, handles = self.fixture()
            task = after["views"][-1]
            change(task)
            after["histories"][-1]["page"]["entries"][0]["item"] = copy.deepcopy(task)
            with self.assertRaises(AssertionError):
                predicates["graph"](before, after, [handles[0]], handles[1:])
        for mutate in [lambda e: e.update(origin="HostObserved"), lambda e: e["citations"].pop()]:
            before, after, handles = self.fixture()
            mutate(after["views"][1]["item"]["draft"]["content"]["Research"]["findings"][0])
            after["histories"][1]["page"]["entries"][0]["item"] = copy.deepcopy(after["views"][1])
            with self.assertRaises(AssertionError):
                predicates["graph"](before, after, [handles[0]], handles[1:])

    def test_historical_milestone_ownership_or_transient_completion_is_rejected(self):
        for index, change in [(5, lambda v: v["refs"].append({"relation": "PartOf", "target": {}})),
                              (7, lambda v: v["item"]["draft"]["content"]["Task"].update(status="Done"))]:
            before, after, handles = self.fixture()
            change(after["histories"][index]["page"]["entries"][-1]["item"])
            with self.assertRaises(AssertionError):
                predicates["graph"](before, after, [handles[0]], handles[1:])

    def test_rewrite_then_restore_cannot_replace_the_tested_proposition(self):
        cases = [(4, lambda v: v["item"]["draft"]["content"]["Hypothesis"].update(claim="Normalization introduces bad tokens", status="Refuted")),
                 (4, lambda v: v["refs"].clear()),
                 (1, lambda v: v["item"]["draft"]["content"]["Research"].update(question="A different question")),
                 (0, lambda v: v["item"]["draft"]["content"]["Defect"].update(expected="Different contract"))]
        for index, mutate in cases:
            with self.subTest(index=index):
                before, after, handles = self.fixture()
                current = after["views"][index]
                intermediate = copy.deepcopy(current)
                mutate(intermediate)
                current["item"]["revision"]["value"] = "3"
                history = after["histories"][index]["page"]["entries"]
                history[0] = {"item": copy.deepcopy(current)}
                history.insert(1, {"item": intermediate})
                with self.assertRaisesRegex(AssertionError, "original"):
                    predicates["graph"](before, after, [handles[0]], handles[1:])

    def test_materialized_empirical_context_is_exact(self):
        context, body = {"id": {"value": "bundle"}}, '{"instructions":"plan"}'
        artifacts = {"probe": {"actual": "observation"}, "research": {"actual": "qualified conclusion"}}
        request = {"artifacts": [context["id"], *({"value": key} for key in artifacts)]}
        frozen = {"base": {"value": "fixture"}, "input": {"request": copy.deepcopy(request), "artifacts": [{"metadata": context, "body": body},
            *({"metadata": {"id": {"value": key}}, "body": json.dumps(value)} for key, value in artifacts.items())]}}
        report = {"request": request}
        predicates["materialized"](report, frozen, context, body, artifacts, frozen["base"])
        for change in [lambda v: v["input"]["artifacts"][0].update(body='{"instructions":"changed"}'),
                       lambda v: v["input"]["artifacts"][1].update(body='{"actual":"invented"}'),
                       lambda v: v["input"]["request"]["artifacts"].pop(), lambda v: v["base"].update(value="other")]:
            altered = copy.deepcopy(frozen)
            change(altered)
            with self.assertRaises(AssertionError):
                predicates["materialized"](report, altered, context, body, artifacts, frozen["base"])

    def test_exact_verified_observations_can_be_cited_through_the_current_input(self):
        before, after, handles = self.fixture()
        current = {"value": "current-verified-input"}
        for index in range(1, 5):
            content = after["views"][index]["item"]["draft"]["content"]
            value = content[next(iter(content))]
            for evidence in value.get("findings", value.get("evidence", [])):
                evidence["citations"][0] = {"Artifact": {"id": current}}
            after["histories"][index]["page"]["entries"][0]["item"] = copy.deepcopy(after["views"][index])
        predicates["graph"](before, after, [handles[0], current], handles[1:])
        with self.assertRaisesRegex(AssertionError, "evidence handles"):
            predicates["graph"](before, after, [handles[0], {"value": "unrelated-input"}], handles[1:])

    def test_reverification_requires_the_exact_completed_citation_failure(self):
        recovery = runpy.run_path(str(Path(__file__).with_name("defect-plan-replay.py")))
        manifest = {"stage": "plan", "status": "failed", "error": "Adjudication omitted verified evidence handles", "exit": 0,
                    "archiveErrors": [], "accounting": "reconciled"}
        recovery["eligible"](manifest)
        for field, value in [("stage", "probe"), ("status", "plan-passed"), ("error", "Unreviewed application"), ("exit", 1),
                              ("archiveErrors", ["missing dump"]), ("accounting", "missing")]:
            with self.assertRaises(AssertionError):
                recovery["eligible"]({**manifest, field: value})

    def test_reverification_rejects_changed_original_evidence(self):
        """Behavioral Active Blackbox Good Communication: immutable retained verification input."""
        recovery = runpy.run_path(str(Path(__file__).with_name("defect-plan-replay.py")))
        manifest = {"stage": "plan", "status": "failed", "error": "Adjudication omitted verified evidence handles", "exit": 0,
                    "archiveErrors": [], "accounting": "reconciled"}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            session = directory / "sessions/session"
            session.mkdir(parents=True)
            for name in ["result.json", "source-sha256.json", "settings.json", "before.json", "after.json", "bundle-publication.json",
                         "empirical-input.json", "dispatch-statuses.json", "candidate-evidence.json", "attempts.json", "usage-summary.json",
                         "baseline-usage-summary.json", "combined-usage-summary.json", "cq-database.dump", "usage-audit-0.json",
                         "sessions/session/run.json", "sessions/session/receipt.json"]:
                (directory / name).write_text("{}")
            (directory / "result.json").write_text(json.dumps(manifest))
            original = (directory / "result.json").read_bytes()
            verification = {"inputsSha256": recovery["consumed"](directory), "originalStatus": "failed", "reason": manifest["error"], "proof": {"test": "only manifest loading; full checkpoint replay is separate"}}
            (directory / "plan-verification.json").write_text(json.dumps(verification))
            effective = recovery["effective"](directory, manifest)
            self.assertEqual(effective["status"], "plan-passed")
            self.assertEqual((directory / "result.json").read_bytes(), original)
            for name in ["result.json", "cq-database.dump", "after.json", "candidate-evidence.json", "usage-audit-0.json"]:
                path = directory / name
                data = path.read_bytes()
                path.write_bytes(data + b" ")
                with self.assertRaisesRegex(AssertionError, "evidence changed"):
                    recovery["effective"](directory, manifest)
                path.write_bytes(data)


if __name__ == "__main__":
    unittest.main()
