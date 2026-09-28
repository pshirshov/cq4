import copy
from pathlib import Path
import runpy
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-upstream-evidence.py")))


class DefectUpstreamCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: truthful local closure and unperformed external action."""

    def fixture(self):
        repair = runpy.run_path(str(Path(__file__).with_name("defect-integrate-check.py")))["DefectIntegrationCheck"]().fixture()
        before = repair["after"]
        scope = predicates["scope"](before)
        for view in before["views"]:
            draft = view["item"]["draft"]
            draft.update(archived=False, citations=[], body="Original scope")
            if "Goal" in draft["content"]:
                draft["content"]["Goal"].update(outcome="Restore original behavior", scope="Local helper correction and tests")
            if "Milestone" in draft["content"]:
                draft["content"]["Milestone"].update(objective="Local repair")
            page = next(page for page in before["histories"] if page["id"] == view["item"]["id"])
            page["page"]["entries"][0]["item"] = copy.deepcopy(view)
        context = {"value": "incorporation-input"}
        incorporation = {"candidate": {"value": "repaired"}, "scope": {"base": {"value": "defective"}},
                         "chain": {"reviewResult": {"value": "review"}, "validation": [{"artifact": {"value": name}} for name in ["worker-check", "review-check"]]}}
        citations = [{"Artifact": {"id": context}}, {"Artifact": {"id": {"value": "review"}}},
                     {"Commit": {"repository": "/original/consumer", "hash": "repaired"}},
                     *({"Artifact": {"id": value["artifact"]}} for value in incorporation["chain"]["validation"])]
        evidence = [{"origin": "ModelDeclared", "description": "Qualified by original fixture and passing candidate", "citations": copy.deepcopy(citations)}]
        after = copy.deepcopy(before)
        def advance(view):
            view["item"]["revision"]["value"] = str(int(view["item"]["revision"]["value"]) + 1)
            page = next(page for page in after["histories"] if page["id"] == view["item"]["id"])
            page["page"]["entries"].insert(0, {"item": copy.deepcopy(view)})
        for ledger, key, status in [("Defect", "defect", "Resolved"), ("Goal", "goal", "Achieved"), ("Milestone", "milestone", "Complete")]:
            view = next(view for view in after["views"] if view["item"]["id"] == scope[key])
            view["item"]["draft"]["content"][ledger]["status"] = status
            view["item"]["draft"]["citations"] = copy.deepcopy(citations)
            if ledger == "Defect":
                view["item"]["draft"]["content"][ledger].update(cause="Selection", resolution=copy.deepcopy(evidence))
            advance(view)
        contents = [
            ("Upstream", "Upstream", {"status": "Identified", "component": "synthetic_tokens.py", "version": "defective", "reproduction": "python3 -m unittest -v", "report": None, "outcome": None}),
            ("OperatorActions", "OperatorAction", {"status": "Requested", "action": "Identify any actual endpoint", "expectedEvidence": "Human decision then observed report URL", "confirmation": None, "observedEvidence": []}),
            ("Handoffs", "Handoff", {"status": "Open", "outcome": "Local repair complete; report prepared", "remaining": ["No external submission"], "blockers": ["No endpoint"]}),
            ("Memories", "Memory", {"status": "Current", "knowledge": "ASCII token contract", "applicability": "Explicit ASCII words only", "evidence": copy.deepcopy(evidence)})]
        goal = next(view for view in after["views"] if view["item"]["id"] == scope["goal"])
        for ledger, tag, value in contents:
            item = {"project": scope["goal"]["project"], "ledger": ledger, "number": "1"}
            view = {"item": {"id": item, "revision": {"value": "1"}, "draft": {"archived": False, "content": {tag: value}, "citations": copy.deepcopy(citations)}},
                    "refs": [{"relation": "DerivedFrom", "target": scope["goal"]}]}
            after["views"].append(view)
            after["histories"].append({"id": item, "page": {"hasMore": False, "entries": [{"item": copy.deepcopy(view)}]}})
            goal["refs"].append({"relation": "Produces", "target": item})
        advance(goal)
        return {"before": before, "after": after, "incorporation": incorporation, "context": context, "repository": "/original/consumer"}

    def test_local_resolution_and_preparation_are_distinct(self):
        result = predicates["graph"](**self.fixture())
        self.assertEqual(result["candidate"], {"value": "repaired"})
        self.assertEqual(set(result["prepared"]), {"Upstream", "OperatorActions", "Handoffs", "Memories"})

    def test_external_claims_wrong_version_and_missing_citations_are_rejected(self):
        mutations = [
            ("OperatorActions", lambda d: d["content"]["OperatorAction"].update(confirmation="User agreed")),
            ("OperatorActions", lambda d: d["content"]["OperatorAction"].update(observedEvidence=[{}])),
            ("Upstream", lambda d: d["content"]["Upstream"].update(report={"address": "https://invented.invalid"})),
            ("Upstream", lambda d: d["content"]["Upstream"].update(version="another fixture")),
            ("Memories", lambda d: d["content"]["Memory"]["evidence"][0].update(origin="HostObserved")),
            ("Defects", lambda d: d.update(citations=[])),
            ("Goals", lambda d: d["content"]["Goal"].update(acceptance=["Different contract"]))]
        for ledger, mutate in mutations:
            with self.subTest(ledger=ledger):
                fixture = self.fixture()
                view = next(view for view in fixture["after"]["views"] if view["item"]["id"]["ledger"] == ledger)
                mutate(view["item"]["draft"])
                page = next(page for page in fixture["after"]["histories"] if page["id"] == view["item"]["id"])
                page["page"]["entries"][0]["item"] = copy.deepcopy(view)
                with self.assertRaises(AssertionError):
                    predicates["graph"](**fixture)

    def test_transient_false_confirmation_or_investigation_rewrite_is_rejected(self):
        for ledger, mutate in [("OperatorActions", lambda d: d["content"]["OperatorAction"].update(confirmation="invented")),
                               ("Hypothesis", lambda d: d["content"]["Hypothesis"].update(claim="new claim"))]:
            with self.subTest(ledger=ledger):
                fixture = self.fixture()
                view = next(view for view in fixture["after"]["views"] if view["item"]["id"]["ledger"] == ledger)
                page = next(page for page in fixture["after"]["histories"] if page["id"] == view["item"]["id"])
                intermediate = copy.deepcopy(view)
                mutate(intermediate["item"]["draft"])
                revision = int(view["item"]["revision"]["value"])
                intermediate["item"]["revision"]["value"] = str(revision + 1)
                view["item"]["revision"]["value"] = str(revision + 2)
                page["page"]["entries"][:0] = [{"item": copy.deepcopy(view)}, {"item": intermediate}]
                with self.assertRaises(AssertionError):
                    predicates["graph"](**fixture)

    def test_continuation_switches_only_the_candidate_and_local_session_effects(self):
        core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
        fixture = self.fixture()
        prior = {"values": {"snapshot": fixture["before"]}, "manifest": {"stage": "integrate", "proof": fixture["incorporation"]}}
        actual = core["continuation"](prior)
        self.assertEqual(actual["git"], {"value": "repaired"})
        self.assertEqual(actual["localIntegrations"], [])
        for field in ["views", "histories", "claims", "clean"]:
            self.assertEqual(actual[field], fixture["before"][field])
        self.assertNotEqual(prior["values"]["snapshot"]["localIntegrations"], [])


if __name__ == "__main__":
    unittest.main()
