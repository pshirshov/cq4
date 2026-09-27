import copy
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
                    ("Questions", "Question", {"status": "Open", "answer": None, "alternatives": ["Python", "Go"], "prompt": "Which language?"})]]
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

    def test_missing_relationship_answer_authority_or_history_is_rejected(self):
        mutations = {
            "missing derivation": lambda f: f["views"][1].update(refs=[]),
            "milestone ownership": lambda f: f["views"][0]["refs"].append({"relation": "PartOf", "target": {"ledger": "Milestones"}}),
            "invented answer": lambda f: f["views"][2]["item"]["draft"]["content"]["Question"].update(answer="Python"),
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
