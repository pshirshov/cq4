import copy
import json
from pathlib import Path
import runpy
import unittest


check = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["question_checkpoint"]


class ProcessEvidenceCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: intake relationships and a real pending choice."""

    def fixture(self):
        items = [{"id": {"project": {"value": "project"}, "ledger": ledger, "number": "1"}, "revision": {"value": "1"},
                  "draft": {"content": {kind: content}}} for ledger, kind, content in [
                    ("Ideas", "Idea", {}), ("Goals", "Goal", {}),
                    ("Questions", "Question", {"status": "Open", "answer": None, "alternatives": ["Python", "Go"], "prompt": "Which language?",
                                                 "recommendation": {"alternative": 0, "reason": "The specification's examples are in Python"}})]]
        views = [{"item": item, "refs": [] if index == 0 else [{"relation": "DerivedFrom", "target": items[index - 1]["id"]}]} for index, item in enumerate(items)]
        return copy.deepcopy({"views": views, "histories": [{"id": view["item"]["id"], "page": {"hasMore": False, "entries": [{"item": copy.deepcopy(view)}]}} for view in views],
                "tickets": [], "receipt": {"processSucceeded": True, "usageDelivered": True, "report": {"summary": "Q1: Python or Go?"}},
                "claims": {"members": [{"id": item["id"], "revision": item["revision"]} for item in items], "claims": [], "integrations": []}})

    def test_intake_question_checkpoint(self):
        self.assertEqual(check(**self.fixture())["status"], "awaiting-user-answer")

    def test_answer_and_decision_cannot_be_erased_from_checkpoint_history(self):
        fixture = self.fixture()
        question = fixture["views"][2]["item"]
        earlier = copy.deepcopy(fixture["views"][2])
        earlier["item"]["draft"]["content"]["Question"].update(status="Answered", answer="Python")
        question["revision"] = {"value": "2"}
        fixture["claims"]["members"][2]["revision"] = {"value": "2"}
        fixture["histories"][2]["page"]["entries"] = [{"item": copy.deepcopy(fixture["views"][2])}, {"item": earlier}]
        with self.assertRaisesRegex(AssertionError, "historical answer"):
            check(**fixture)

    def test_reverted_adopted_decision_is_rejected(self):
        fixture = self.fixture()
        item = {"id": {"project": {"value": "project"}, "ledger": "Decisions", "number": "1"}, "revision": {"value": "2"},
                "draft": {"content": {"Decision": {"status": "Proposed"}}}}
        view = {"item": item, "refs": []}
        earlier = copy.deepcopy(view)
        earlier["item"]["revision"]["value"] = "1"
        earlier["item"]["draft"]["content"]["Decision"]["status"] = "Adopted"
        fixture["views"].append(view)
        fixture["histories"].append({"id": item["id"], "page": {"hasMore": False, "entries": [{"item": copy.deepcopy(view)}, {"item": earlier}]}})
        fixture["claims"]["members"].append({"id": item["id"], "revision": item["revision"]})
        with self.assertRaisesRegex(AssertionError, "historical adopted decision"):
            check(**fixture)

    def test_conflict_resolution_cannot_start_before_answer(self):
        fixture = self.fixture()
        fixture["tickets"].append({"request": {"work": {"Worker": {"mode": "ResolveConflict"}}}})
        with self.assertRaisesRegex(AssertionError, "Implementation started"):
            check(**fixture)

    def test_premature_task_or_milestone_is_rejected(self):
        for ledger, kind in [("Tasks", "Task"), ("Milestones", "Milestone")]:
            with self.subTest(ledger=ledger):
                fixture = self.fixture()
                item = {"id": {"project": {"value": "project"}, "ledger": ledger, "number": "1"}, "revision": {"value": "1"}, "draft": {"content": {kind: {}}}}
                fixture["views"].append({"item": item, "refs": []})
                fixture["histories"].append({"id": item["id"], "page": {"hasMore": False, "entries": [{"item": {"item": item, "refs": []}}]}})
                fixture["claims"]["members"].append({"id": item["id"], "revision": item["revision"]})
                with self.assertRaisesRegex(AssertionError, "premature task or milestone"):
                    check(**fixture)

    def test_supplied_answer_requires_exact_question_and_source_record(self):
        checkpoint = check(**self.fixture())
        validate = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["supplied_answer"]
        answer = {"question": checkpoint["question"], "answer": "Go", "verbatim": "Go", "source": "Actual user reply to the presented Q1"}
        self.assertEqual(validate(checkpoint, answer), "go")
        for key, value in [("question", {"id": "another"}), ("answer", "Rust"), ("verbatim", ""), ("source", "")]:
            with self.subTest(key=key):
                changed = copy.deepcopy(answer)
                changed[key] = value
                with self.assertRaises(AssertionError):
                    validate(checkpoint, changed)

    def resume_fixture(self):
        initial = self.fixture()
        checkpoint = check(**initial)
        pair = runpy.run_path(str(Path(__file__).with_name("consumer-cohort-check.py")))["ConsumerCohortCheck"]().fixture()
        for handle in ["worker-result", "review-result"]:
            for member in pair["artifacts"][handle]["body"]["request"]["members"]:
                member["revision"]["value"] = "2"
        session = {"value": "new-session"}
        citation = {"File": {"path": ".cq-evaluation/answer.json", "revision": "b" * 40}}
        answer = {"question": checkpoint["question"], "answer": "Python", "verbatim": "Python", "source": "Actual user reply"}
        views = copy.deepcopy(initial["views"])
        histories = copy.deepcopy(initial["histories"])
        for view in views:
            view["item"].update(createdAt="1", updatedAt="1", provenance={"actor": {"role": "Governor", "session": {"value": "old-session"}}})
        question = views[2]["item"]
        question.update(revision={"value": "2"}, updatedAt="10", provenance={"actor": {"role": "Human", "session": {"value": "human-session"}}})
        question["draft"]["content"]["Question"].update(status="Answered", answer="Python")
        question["draft"]["citations"] = [citation]
        milestone_id = {"project": {"value": "project"}, "ledger": "Milestones", "number": "1"}
        for ledger, content in [("Decisions", {"Decision": {"status": "Adopted", "choice": "Python"}}),
                                ("Milestones", {"Milestone": {"status": "Open"}}), ("Handoffs", {"Handoff": {"status": "Open"}})]:
            refs = [{"relation": "DerivedFrom", "target": question["id"]}] if ledger == "Decisions" else []
            if ledger == "Handoffs":
                refs = [{"relation": "DerivedFrom", "target": member["id"]} for member in pair["seed"]["members"]]
            views.append({"item": {"id": {"project": {"value": "project"}, "ledger": ledger, "number": "1"}, "revision": {"value": "1"},
                "createdAt": "20", "updatedAt": "20", "provenance": {"actor": {"role": "Governor", "session": session}},
                "draft": {"content": content, "citations": [citation] if ledger == "Decisions" else []}}, "refs": refs})
        host_validation = {"origin": "HostObserved", "citations": [{"Artifact": {"id": {"value": "review-result"}}}]}
        for member in pair["seed"]["members"]:
            views.append({"item": {"id": member["id"], "revision": {"value": "3"}, "createdAt": "20", "updatedAt": "30", "draft": {
                "content": {"Task": {"status": "Done", "validation": [host_validation]}}}}, "refs": [
                {"relation": "DerivedFrom", "target": views[1]["item"]["id"]}, {"relation": "PartOf", "target": milestone_id}]})
        histories = []
        for view in views:
            snapshots = []
            for revision in range(1, int(view["item"]["revision"]["value"]) + 1):
                snapshot = copy.deepcopy(view)
                item = snapshot["item"]
                item["revision"]["value"] = str(revision)
                if item["id"]["ledger"] == "Questions" and revision == 1:
                    item["draft"]["content"]["Question"].update(status="Open", answer=None)
                if item["id"]["ledger"] == "Tasks" and revision < 3:
                    item["draft"]["content"]["Task"].update(status="Ready", validation=[])
                snapshots.append({"item": snapshot})
            histories.append({"id": view["item"]["id"], "page": {"hasMore": False, "entries": snapshots}})
        task_refs = [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in views if view["item"]["id"]["ledger"] == "Tasks"]
        candidate = pair["artifacts"]["worker-result"]["body"]["candidate"]
        intent = {"id": {"value": "integration"}, "owner": {"session": session}, "target": "refs/heads/integration", "candidate": candidate,
                  "members": pair["artifacts"]["worker-result"]["body"]["request"]["members"], "worker": {"value": "worker-result"}, "reviewer": {"value": "review-result"}}
        result = {"checkpoint": checkpoint, "answer": answer, "answer_citation": citation, "views": views, "histories": histories,
                  "statuses": pair["statuses"], "artifacts": pair["artifacts"], "integrations": [{"local": {"intent": intent, "attempted": True, "observation": {"Incorporated": {"target": candidate}}},
                  "record": {"intent": intent, "resolution": {"Recorded": {"observedTarget": candidate, "acknowledgement": {"items": task_refs}}}}}],
                  "run": {"attempt": {"session": session}}, "target": candidate, "claims": {"members": [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in views], "claims": [], "integrations": []}, "checks": pair["checks"]}
        return json.loads(json.dumps(result))

    def test_resume_requires_actual_answer_shared_artifact_and_reviewed_host_integration(self):
        fixture = self.resume_fixture()
        validate = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["integrated_resume"]
        self.assertEqual(validate(**fixture)["independentProcessAssessment"], "pending")
        mutations = {
            "different target": lambda f: f.update(target={"value": "d" * 40}),
            "unrecorded effect": lambda f: f["integrations"][0]["record"].update(resolution={"Pending": {}}),
            "absent observation": lambda f: f["integrations"][0]["local"].update(observation=None),
            "stale authority": lambda f: f["run"]["attempt"].update(session={"value": "old-session"}),
            "unmetered review": lambda f: f["statuses"][1].update(usageDelivered=False),
            "missing scope": lambda f: f["views"][5]["refs"].pop(),
            "held integration": lambda f: f["claims"].update(integrations=[{"held": True}]),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(fixture)
                mutation(changed)
                with self.assertRaises(AssertionError):
                    validate(**changed)
        changed = copy.deepcopy(fixture)
        changed["artifacts"]["review-result"]["body"]["validation"] = copy.deepcopy(changed["artifacts"]["worker-result"]["body"]["validation"])
        with self.assertRaisesRegex(AssertionError, "fresh oracle"):
            validate(**changed)

    def test_resume_cannot_change_and_restore_the_actual_answer(self):
        fixture = self.resume_fixture()
        question = fixture["views"][2]
        history = fixture["histories"][2]["page"]["entries"]
        changed = copy.deepcopy(question)
        changed["item"]["revision"]["value"] = "3"
        changed["item"]["draft"]["content"]["Question"]["answer"] = "Go"
        question["item"]["revision"]["value"] = "4"
        fixture["claims"]["members"][2]["revision"]["value"] = "4"
        history.extend([{"item": changed}, {"item": copy.deepcopy(question)}])
        validate = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["integrated_resume"]
        with self.assertRaisesRegex(AssertionError, "supplied answer changed"):
            validate(**fixture)

    def test_resume_rejects_task_edits_after_recorded_incorporation(self):
        fixture = self.resume_fixture()
        task = next(view for view in fixture["views"] if view["item"]["id"]["ledger"] == "Tasks")
        task["item"]["revision"]["value"] = "4"
        task["item"]["draft"]["content"]["Task"]["acceptance"] = ["New, unreviewed requirement"]
        next(page for page in fixture["histories"] if page["id"] == task["item"]["id"])["page"]["entries"].append({"item": copy.deepcopy(task)})
        next(member for member in fixture["claims"]["members"] if member["id"] == task["item"]["id"])["revision"]["value"] = "4"
        validate = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["integrated_resume"]
        with self.assertRaisesRegex(AssertionError, "changed after recorded integration"):
            validate(**fixture)

    def test_resume_usage_reconciles_counts_gaps_and_exact_cost_groups(self):
        def report(multiplier):
            counter = {"known": str(11 * multiplier), "unknown": str(multiplier), "estimated": str(2 * multiplier)}
            bucket = {name: copy.deepcopy(counter) for name in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]}
            bucket["unknownCosts"] = str(multiplier)
            return {**{name: copy.deepcopy(bucket) for name in ["direct", "shared", "unattributed"]},
                "incompleteMeters": str(multiplier), "attemptsWithoutMeters": str(multiplier),
                "attempts": {name: str(multiplier) for name in ["running", "unknown", "withGaps"]},
                "costs": {"hasMore": False, "entries": [{"group": {"attribution": "Shared", "currency": "USD", "basis": "ProviderEstimate", "pricingVersion": None},
                    "amount": {"value": "0." + str(multiplier)}, "measurements": str(multiplier)}]}}
        validate = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["reconcile_usage"]
        parts = [report(1), report(2)]
        validate(parts, report(3))
        mutations = [lambda r: r["shared"]["total"].update(known="1"),
                     lambda r: r.update(attemptsWithoutMeters="0"),
                     lambda r: r["attempts"].update(withGaps="0"),
                     lambda r: r["costs"]["entries"][0]["amount"].update(value="0.30000000000000001"),
                     lambda r: r["costs"]["entries"][0]["group"].update(basis="Billed"),
                     lambda r: r["costs"].update(hasMore=True)]
        for mutation in mutations:
            combined = report(3)
            mutation(combined)
            with self.assertRaises(AssertionError):
                validate(parts, combined)

    def test_missing_relationship_answer_authority_or_history_is_rejected(self):
        mutations = {
            "missing derivation": lambda f: f["views"][1].update(refs=[]),
            "milestone ownership": lambda f: f["views"][0]["refs"].append({"relation": "PartOf", "target": {"ledger": "Milestones"}}),
            "invented answer": lambda f: f["views"][2]["item"]["draft"]["content"]["Question"].update(answer="Python"),
            "no recommendation": lambda f: [view["item"]["draft"]["content"]["Question"].update(recommendation=None)
                                            for view in [f["views"][2], f["histories"][2]["page"]["entries"][0]["item"]]],
            "hidden choice": lambda f: f["receipt"]["report"].update(summary="Waiting"),
            "early implementation": lambda f: f["tickets"].append({"request": {"work": {"Worker": {"mode": "Implement"}}}}),
            "lost history": lambda f: f["histories"][0]["page"].update(entries=[]),
            "different latest history": lambda f: f["histories"][0]["page"]["entries"][0]["item"]["item"]["draft"].update(title="Different"),
            "history truncated": lambda f: f["histories"][0]["page"].update(hasMore=True),
            "claim retained": lambda f: f["claims"].update(claims=[{"held": True}]),
            "stale handoff": lambda f: f["claims"].update(members=[]),
        }
        for name, mutation in mutations.items():
            with self.subTest(name=name):
                fixture = self.fixture()
                mutation(fixture)
                with self.assertRaises(AssertionError):
                    check(**fixture)


if __name__ == "__main__":
    unittest.main()
