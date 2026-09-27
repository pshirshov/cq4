import copy
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))


class DefectEvidenceCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: intake and reviewed application evidence."""

    def test_restoration_compares_state_without_actor_bound_digest(self):
        archived = {**self.snapshot(), "git": {"value": "base"}, "clean": True, "localIntegrations": [],
            "claims": {"members": ["member"], "claims": [], "integrations": [],
                       "snapshot": {"cursor": {"value": "4"}, "digest": "a" * 64}}}
        restored = copy.deepcopy(archived)
        restored["claims"]["snapshot"]["digest"] = "b" * 64
        predicates["restored"](restored, archived)
        mutations = [lambda v: v["claims"]["snapshot"]["cursor"].update(value="5"),
                     lambda v: v["claims"]["members"].append("other"),
                     lambda v: v["claims"]["claims"].append({"fence": "2"}),
                     lambda v: v["claims"]["integrations"].append({}),
                     lambda v: v["views"].pop(), lambda v: v["histories"].pop(),
                     lambda v: v["git"].update(value="other"), lambda v: v.update(clean=False),
                     lambda v: v["localIntegrations"].append({})]
        for mutate in mutations:
            altered = copy.deepcopy(restored)
            mutate(altered)
            with self.assertRaisesRegex(AssertionError, "Restored checkpoint"):
                predicates["restored"](altered, archived)

    def snapshot(self):
        def item(ledger, number, content):
            return {"item": {"id": {"project": {"value": "project"}, "ledger": ledger, "number": str(number)},
                             "revision": {"value": "1"}, "draft": {"content": content}}, "refs": []}
        defect = item("Defects", 1, {"Defect": {"status": "Open", "cause": None, "resolution": []}})
        research = item("Researches", 1, {"Research": {"status": "Open", "conclusion": None, "findings": []}})
        hypotheses = [item("Hypothesis", index, {"Hypothesis": {"status": "Proposed", "adjudication": None, "evidence": [], "claim": claim}})
                      for index, claim in enumerate(["Token pipeline violates the ASCII contract", "Selection admits non-ASCII characters", "Normalization introduces non-ASCII characters"], 1)]
        for target, source in [(research, defect), (hypotheses[0], research), (hypotheses[1], hypotheses[0]), (hypotheses[2], hypotheses[0])]:
            target["refs"].append({"relation": "DerivedFrom", "target": source["item"]["id"]})
        views = [defect, research, *hypotheses]
        return {"views": views, "histories": [{"id": view["item"]["id"], "page": {"entries": [{"item": view}], "hasMore": False}} for view in views]}

    def test_linked_proposed_tree(self):
        proof = predicates["records"](self.snapshot())
        self.assertEqual(proof["parent"]["number"], "1")
        self.assertEqual([value["number"] for value in proof["branches"]], ["2", "3"])

    def test_rejects_sibling_cycle_in_hypothesis_tree(self):
        snapshot = self.snapshot()
        for source, target in [(3, 4), (4, 3)]:
            snapshot["views"][source]["refs"].append({"relation": "DerivedFrom", "target": snapshot["views"][target]["item"]["id"]})
        with self.assertRaisesRegex(AssertionError, "parent"):
            predicates["records"](snapshot)

    def test_rejects_unreviewed_post_creation_content_change(self):
        snapshot = self.snapshot()
        for view in snapshot["views"]:
            view["item"]["provenance"] = {"request": {"value": "reviewed"}}
        entry = snapshot["histories"][3]
        original = copy.deepcopy(entry["page"]["entries"][0])
        entry["page"]["entries"][0]["item"]["item"]["revision"] = {"value": "2"}
        entry["page"]["entries"][0]["item"]["item"]["provenance"]["request"] = {"value": "direct"}
        entry["page"]["entries"][0]["item"]["item"]["draft"]["content"]["Hypothesis"]["claim"] = "Unreviewed replacement"
        entry["page"]["entries"].append(original)
        with self.assertRaisesRegex(AssertionError, "reviewed"):
            predicates["reviewed_records"](snapshot["histories"], [{"request": {"value": "reviewed"}}])

    def test_rejects_unlinked_research_and_branch(self):
        for index in [1, 3]:
            snapshot = self.snapshot()
            snapshot["views"][index]["refs"] = [{"relation": "DerivedFrom", "target": snapshot["views"][4]["item"]["id"]}]
            with self.assertRaises(AssertionError):
                predicates["records"](snapshot)

    def test_rejects_premature_or_erased_adjudication(self):
        for ledger, field, value in [(0, "cause", "Assumed cause"), (1, "conclusion", "Untested conclusion"),
                                     (2, "status", "Supported"), (3, "evidence", ["Invented observation"])]:
            snapshot = self.snapshot()
            historical = copy.deepcopy(snapshot["views"][ledger])
            next(iter(historical["item"]["draft"]["content"].values()))[field] = value
            snapshot["views"][ledger]["item"]["revision"] = {"value": "2"}
            snapshot["histories"][ledger]["page"]["entries"].append({"item": historical})
            with self.assertRaises(AssertionError):
                predicates["records"](snapshot)

    def test_rejects_incomplete_history_or_duplicate_claim(self):
        snapshot = self.snapshot()
        snapshot["histories"][0]["page"]["hasMore"] = True
        with self.assertRaises(AssertionError):
            predicates["records"](snapshot)
        snapshot = self.snapshot()
        snapshot["views"][4]["item"]["draft"]["content"] = copy.deepcopy(snapshot["views"][3]["item"]["draft"]["content"])
        with self.assertRaisesRegex(AssertionError, "duplicated"):
            predicates["records"](snapshot)

    def applications(self):
        items = [{"id": {"ledger": "Hypothesis", "number": str(index)}, "revision": {"value": "1"}} for index in [1, 2]]
        plan = {"proposal": {"value": "proposal"}, "request": {"value": "request"}, "applied": items,
                "reviews": [{"result": {"value": "review"}, "verdicts": [{"verdict": "Accepted"}]}]}
        status = {"Status": {"value": {"phase": "Completed", "result": {"value": "review"}, "usageDelivered": True,
                  "counts": {"accepted": 1, "changesRequested": 0, "blocked": 0}}}}
        ack = {"request": plan["request"], "items": list(reversed(items)), "cursor": {"value": "2"}}
        def pair(name, tool, args, reply):
            return [{"type": "assistant", "message": {"content": [{"type": "tool_use", "id": name, "name": tool, "input": args}]}},
                    {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": name, "content": json.dumps(reply)}]}}]
        events = pair("status", "mcp__cq_host__dispatch", {}, status) + pair("apply", "mcp__cq__apply", {"result": plan["proposal"]}, {"Changed": {"ack": ack}})
        return plan, events, ack

    def test_acknowledged_multi_record_application_after_observed_review(self):
        plan, events, ack = self.applications()
        self.assertEqual(predicates["reviewed_applications"](events + events[2:], [plan]), {"proposal": ack})

    def test_review_after_apply_or_no_actual_reply_is_rejected(self):
        plan, events, _ = self.applications()
        for reordered in [events[2:] + events[:2], [events[0], *events[2:]], events[:3]]:
            with self.assertRaises(AssertionError):
                predicates["reviewed_applications"](reordered, [plan])

    def test_rejected_or_unrelated_review_cannot_authorize_application(self):
        for field, value in [("result", {"value": "unrelated"}), ("verdicts", [{"verdict": "ChangesRequested"}])]:
            plan, events, _ = self.applications()
            plan["reviews"][0][field] = value
            with self.assertRaisesRegex(AssertionError, "preceded"):
                predicates["reviewed_applications"](events, [plan])

    def test_acknowledgement_cannot_omit_a_changed_member(self):
        plan, events, ack = self.applications()
        ack["items"].pop()
        events[-1]["message"]["content"][0]["content"] = json.dumps({"Changed": {"ack": ack}})
        with self.assertRaisesRegex(AssertionError, "history"):
            predicates["reviewed_applications"](events, [plan])

    def research(self, directory):
        before = self.snapshot()
        before.update(git={"value": "fixture"}, clean=True, localIntegrations=[],
            claims={"claims": [], "integrations": [], "members": [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in before["views"]]})
        member = before["views"][1]
        guidance = [view for view in before["views"] if view != member]
        governor = {"id": {"value": "governor"}, "session": {"value": "session"}, "role": "Governor", "harness": "Claude", "parent": None}
        child = {"id": {"value": "explorer"}, "session": governor["session"], "role": "Explorer", "harness": "Pi", "model": "configured", "parent": governor["id"]}
        prior = {"values": {"snapshot": before, "run": {"attempt": {"id": {"value": "prior"}}}},
                 "manifest": {"proof": {"result": {"value": "probe"}, "execution": {"observations": {"recorded": "actual observations"}}}}}
        body = json.dumps({"observations": prior["manifest"]["proof"]["execution"]["observations"]})
        context = {"id": {"value": "bundle"}, "attempt": {"value": "prior"}, "kind": "Input", "actor": {"role": "Human"}, "sha256": hashlib.sha256(body.encode()).hexdigest()}
        settings = {"harnesses": [{"harness": "Pi", "model": "configured"}], "evaluation": {"run": "evaluation", "scenario": "fixture", "assessor": False}}
        assignment = {"evaluation": settings["evaluation"], "members": [member["item"]["id"]]}
        request = {"request": {"value": "request"}, "harness": "Pi", "work": {"Explorer": {"mode": "Research"}},
                   "members": [{"id": member["item"]["id"], "revision": member["item"]["revision"]}], "previous": None,
                   "guidance": [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in guidance], "artifacts": [context["id"], {"value": "probe"}]}
        report = {"request": request, "attempt": child["id"], "base": before["git"], "candidate": None, "validation": [],
                  "report": {"Evidence": {"members": [{"item": member["item"]["id"], "disposition": "Findings", "evidence": [
                      {"origin": "ModelDeclared", "citations": [{"Artifact": {"id": context["id"]}}]}]}]}}}
        frozen = {"base": before["git"], "input": {"request": request, "members": [member], "guidance": guidance,
            "artifacts": [{"metadata": context, "body": body}]}}
        session = directory / "sessions" / "session"
        child_dir = session / "children" / "explorer"
        child_dir.mkdir(parents=True)
        (session / "journal").mkdir()
        def write(path, value):
            path.write_text(json.dumps(value))
        write(child_dir / "ticket.json", {"attempt": child, "assignment": assignment, "request": request})
        write(session / "journal/explorer.json", {"workspace": {"attempt": child["id"], "owner": governor["session"], "base": before["git"]},
            "phase": "Settled", "exit": {"settled": True, "code": 0, "reason": "Exited", "hostFailure": False}})
        write(session / "receipt.json", {"processSucceeded": True, "usageDelivered": True, "report": {}})
        write(directory / "bundle-publication.json", context)
        (directory / "empirical-input.json").write_text(body)
        values = {"snapshot": copy.deepcopy(before), "run": {"attempt": governor, "base": before["git"]}, "session": session,
            "attempts": [{"attempt": governor, "assignment": {"evaluation": settings["evaluation"]}}, {"attempt": child, "assignment": assignment}],
            "observations": [{"upload": {"observation": {"attempt": attempt["id"], "counters": {"input": {"value": "1", "measurement": "Observed"}, "output": {"value": "1", "measurement": "Observed"}}}}} for attempt in [governor, child]],
            "statuses": [{"attempt": child["id"], "phase": "Completed", "usageDelivered": True, "request": request["request"], "result": {"value": "result"}}],
            "artifacts": {"result": {"kind": "Result", "attempt": child["id"], "body": report},
                "input": {"kind": "Input", "attempt": child["id"], "body": frozen}, "bundle": {**context, "body": json.loads(body)}},
            "governing_input": {"body": {"integrationTarget": None, "workflow": {"request": {"Advance": {"roots": [before["views"][0]["item"]["id"]], "through": "Explore"}}}}}}
        return {"directory": directory, "values": values, "settings": settings, "prior": prior, "stage": "research"}

    def test_research_requires_exact_verified_observations_and_unchanged_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            fixture = self.research(Path(temporary))
            result = predicates["empirical"](**fixture)
            self.assertEqual(result["probe"], {"value": "probe"})
            self.assertEqual(result["observations"], {"value": "bundle"})
            mutations = {
                "changed history": lambda v: v["snapshot"]["histories"].pop(),
                "changed current record": lambda v: v["snapshot"]["views"][0]["item"]["revision"].update(value="2"),
                "retained claim": lambda v: v["snapshot"]["claims"]["claims"].append({}),
                "changed git": lambda v: v["snapshot"]["git"].update(value="other"),
                "wrong native role": lambda v: v["attempts"][1]["attempt"].update(role="Worker"),
                "missing usage": lambda v: v["observations"].pop(),
                "missing guidance": lambda v: v["artifacts"]["input"]["body"]["input"]["guidance"].pop(),
                "different materialized observations": lambda v: v["artifacts"]["input"]["body"]["input"]["artifacts"][0].update(body='{"observations":"invented"}'),
                "missing observation citation": lambda v: v["artifacts"]["result"]["body"]["report"]["Evidence"]["members"][0]["evidence"][0].update(citations=[{"File": {"path": "README.md", "revision": "fixture"}}]),
                "fabricated host observation": lambda v: v["artifacts"]["result"]["body"]["report"]["Evidence"]["members"][0]["evidence"][0].update(origin="HostObserved"),
            }
            for name, mutate in mutations.items():
                with self.subTest(name=name):
                    altered = copy.deepcopy(fixture)
                    mutate(altered["values"])
                    with self.assertRaises(AssertionError):
                        predicates["empirical"](**altered)


if __name__ == "__main__":
    unittest.main()
