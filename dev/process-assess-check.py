import copy
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest
import uuid


predicates = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))


class ProcessAssessmentCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: exact standalone inspection evidence."""

    def test_history_reference_digest_and_complete_native_pagination(self):
        history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))
        item = {"project": {"value": "project"}, "ledger": "Tasks", "number": "1"}
        entries = [{"item": {"item": {"id": item, "revision": {"value": str(revision)}, "provenance": {"reason": "café"}}, "refs": []}} for revision in [3, 2, 1]]
        histories = [{"id": item, "page": {"entries": entries, "hasMore": False}}]
        def event(name, before, offset, count):
            return {"type": "item.completed", "item": {"id": name, "type": "mcp_tool_call", "server": "cq", "tool": "read", "status": "completed", "error": None,
                "arguments": {"project": item["project"], "selection": {"History": {"id": item, "before": {"value": str(before)}, "limit": count}}},
                "result": {"structured_content": {"History": {"page": {"entries": copy.deepcopy(entries[offset:offset+count]), "hasMore": offset+count < len(entries)}}}}}}
        events = [event("first", 4, 0, 2), event("last", 2, 2, 1)]
        self.assertTrue(history["inspected"](events, histories)["complete"])
        self.assertFalse(history["inspected"](events[:1] * 2, histories)["complete"])
        self.assertFalse(history["inspected"](events[1:], histories)["complete"])
        reordered = json.loads(json.dumps(histories, sort_keys=True))
        self.assertEqual(history["references"](histories), history["references"](reordered))
        altered = copy.deepcopy(events)
        altered[0]["item"]["result"]["structured_content"]["History"]["page"]["entries"][0]["item"]["item"]["provenance"]["reason"] = "forged"
        with self.assertRaisesRegex(AssertionError, "frozen"):
            history["inspected"](altered, histories)
        altered = copy.deepcopy(events)
        altered[0]["item"]["result"]["structured_content"]["History"]["page"]["hasMore"] = False
        with self.assertRaisesRegex(AssertionError, "completion"):
            history["inspected"](altered, histories)
        altered = copy.deepcopy(events)
        altered[0]["item"]["status"] = "failed"
        self.assertFalse(history["inspected"](altered, histories)["complete"])

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

    def read_event(self, path, text, offset, end):
        return {"type": "item.completed", "item": {"id": f"{path}-{offset}", "type": "mcp_tool_call", "server": "cq_host", "tool": "workspace",
            "arguments": {"Read": {"path": path, "offset": offset, "limit": 8192}}, "status": "completed", "error": None,
            "result": {"structured_content": {"Text": {"page": {"path": path, "offset": offset, "next": end, "hasMore": end < len(text), "text": text[offset:end]}}}}}}

    def test_inspection_requires_successful_complete_exact_reads(self):
        files = {"main.go": "a🙂b", "README.md": "", ".cq-evaluation/answer.json": "Go"}
        events = [self.read_event("main.go", files["main.go"], 0, 2), self.read_event("main.go", files["main.go"], 2, 3),
                  self.read_event("README.md", "", 0, 0), self.read_event(".cq-evaluation/answer.json", "Go", 0, 2)]
        self.assertTrue(predicates["inspected_files"](events, files)["complete"])
        self.assertFalse(predicates["inspected_files"](events[1:], files)["complete"])
        self.assertFalse(predicates["inspected_files"]([], files)["complete"])
        events[0]["item"]["status"] = "failed"
        self.assertFalse(predicates["inspected_files"](events, files)["complete"])
        events[0]["item"]["status"] = "completed"
        events[0]["item"]["result"]["structured_content"]["Text"]["page"]["text"] = "ax"
        with self.assertRaisesRegex(AssertionError, "candidate bytes"):
            predicates["inspected_files"](events, files)

    def correction_fixture(self):
        item_id = {"project": {"value": "project"}, "ledger": "Handoffs", "number": "1"}
        member = {"id": item_id, "revision": {"value": "3"}}
        candidate, session, governor = {"value": "candidate"}, {"value": "session"}, {"value": "governor"}
        request_id = {"value": str(uuid.UUID(bytes=hashlib.md5(b"cq-proposal:plan").digest(), version=3))}
        before_view = {"item": {**member, "createdAt": "0", "draft": {"body": "stale"}, "provenance": {"request": {"value": "old"}, "actor": {"session": {"value": "old-session"}}}},
            "refs": [{"relation": "DerivedFrom", "target": "task"}]}
        after_view = copy.deepcopy(before_view)
        after_view["item"].update(revision={"value": "4"}, draft={"body": "observed outcome"}, provenance={"request": request_id, "actor": {"session": session}})
        before_history = {"id": item_id, "page": {"hasMore": False, "entries": [{"item": before_view, "cursor": {"value": "1"}}]}}
        after_history = copy.deepcopy(before_history)
        after_history["page"]["entries"].insert(0, {"item": after_view, "cursor": {"value": "2"}})
        before = {"views": [before_view], "histories": [before_history], "git": candidate, "target": candidate, "clean": True}
        after = {"views": [after_view], "histories": [after_history], "git": candidate, "target": candidate, "clean": True,
            "claims": {"claims": [], "integrations": []}, "localIntegrations": []}
        finding = {"value": "old-audit"}
        statuses, artifacts = [], {}
        for name, work, previous in [("plan", {"Planner": {}}, None), ("review", {"Reviewer": {"mode": "Plan"}}, {"value": "plan"})]:
            attempt = {"value": name + "-attempt"}
            request = {"request": {"value": name + "-request"}, "work": work, "members": [member], "previous": previous, "artifacts": [finding]}
            report = {"Plan": {"proposal": {"mutations": [{"Replace": {"id": item_id, "draft": after_view["item"]["draft"]}}]}}} if name == "plan" else {
                "Review": {"members": [{"item": item_id, "verdict": "Accepted", "findings": []}]}}
            artifacts[name] = {"attempt": attempt, "body": {"attempt": attempt, "request": request, "base": candidate, "candidate": None, "validation": [], "report": report}}
            statuses.append({"phase": "Completed", "result": {"value": name}, "attempt": attempt, "request": request["request"], "usageDelivered": True})
        return json.loads(json.dumps({"before": before, "after": after, "statuses": statuses, "artifacts": artifacts,
            "run": {"base": candidate, "attempt": {"id": governor, "session": session}},
            "governing_input": {"attempt": governor, "body": {"integrationTarget": None, "workflow": {"request": {"Advance": {"roots": [item_id], "through": "Plan"}}}}},
            "finding": finding}))

    def test_only_exact_reviewed_handoff_replacement_is_accepted(self):
        self.assertEqual(predicates["correction_stage"](**self.correction_fixture())["member"]["revision"], {"value": "4"})
        mutations = {
            "unreviewed application": lambda f: f["artifacts"]["review"]["body"]["report"]["Review"]["members"][0].update(verdict="ChangesRequested"),
            "reviewed different proposal": lambda f: f["artifacts"]["review"]["body"]["request"].update(previous={"value": "other"}),
            "foreign authority": lambda f: f["after"]["histories"][0]["page"]["entries"][0]["item"]["item"]["provenance"]["actor"].update(session={"value": "foreign"}),
            "stale member": lambda f: f["artifacts"]["plan"]["body"]["request"]["members"][0].update(revision={"value": "2"}),
            "changed draft": lambda f: f["artifacts"]["plan"]["body"]["report"]["Plan"]["proposal"]["mutations"][0]["Replace"]["draft"].update(body="unreviewed"),
            "rewritten history": lambda f: f["after"]["histories"][0]["page"]["entries"][1]["item"]["item"]["draft"].update(body="rewritten"),
            "missing finding": lambda f: f["artifacts"]["plan"]["body"]["request"].update(artifacts=[]),
            "foreign finding as previous": lambda f: f["artifacts"]["plan"]["body"]["request"].update(previous=f["finding"]),
            "candidate change": lambda f: f["after"].update(target={"value": "other"}),
            "worker": lambda f: f["artifacts"]["plan"]["body"]["request"].update(work={"Worker": {"mode": "Implement"}}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                fixture = self.correction_fixture()
                mutate(fixture)
                with self.assertRaises(AssertionError):
                    predicates["correction_stage"](**fixture)

    def test_application_ack_follows_exact_accepted_review(self):
        correction = predicates["correction_stage"](**self.correction_fixture())
        applied = correction["planning"][0]
        ack = {"request": applied["request"], "items": [correction["member"]], "cursor": {"value": "2"}}
        events = [
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "review-poll", "name": "mcp__cq_host__dispatch", "input": {}}]}},
            {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "review-poll", "content": json.dumps({"Status": {"value": {
                "result": correction["review"], "phase": "Completed", "usageDelivered": True, "counts": {"accepted": 1, "changesRequested": 0, "blocked": 0}}}})}]}},
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "apply", "name": "mcp__cq__apply", "input": {"result": applied["proposal"]}}]}},
            {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "apply", "content": json.dumps({"Changed": {"ack": ack}})}]}},
        ]
        self.assertEqual(predicates["correction_ack"](events, correction), ack)
        with self.assertRaisesRegex(AssertionError, "preceded"):
            predicates["correction_ack"](events[2:] + events[:2], correction)
        with self.assertRaisesRegex(AssertionError, "Missing"):
            predicates["correction_ack"](events[:-1], correction)
        ack["items"] = []
        events[-1]["message"]["content"][0]["content"] = json.dumps({"Changed": {"ack": ack}})
        with self.assertRaises(AssertionError):
            predicates["correction_ack"](events, correction)

    def test_correction_routes_bind_native_hierarchy_and_metering(self):
        fixture = self.correction_fixture()
        governor = fixture["run"]["attempt"]
        governor["parent"] = None
        evaluation = {"assessor": False, "run": "evaluation", "scenario": "worked-wordfreq"}
        settings = {"evaluation": evaluation, "harnesses": [{"harness": "Codex", "model": "planner-model"}, {"harness": "Pi", "model": "review-model"}]}
        with tempfile.TemporaryDirectory() as temporary:
            session = Path(temporary)
            values = {"run": fixture["run"], "session": session, "statuses": fixture["statuses"], "artifacts": fixture["artifacts"],
                "attempts": [{"attempt": governor, "assignment": {"evaluation": evaluation}}], "observations": []}
            for status, route in zip(values["statuses"], settings["harnesses"]):
                result = values["artifacts"][status["result"]["value"]]["body"]
                result["request"]["harness"] = route["harness"]
                attempt = {"id": status["attempt"], "role": "Planner" if route["harness"] == "Codex" else "Reviewer", **route,
                    "parent": governor["id"], "session": governor["session"]}
                ticket = {"attempt": attempt, "assignment": {"evaluation": evaluation}, "request": result["request"]}
                values["attempts"].append({"attempt": attempt, "assignment": ticket["assignment"]})
                path = session / "children" / status["attempt"]["value"] / "ticket.json"
                path.parent.mkdir(parents=True)
                path.write_text(json.dumps(ticket))
                job_path = session / "journal" / (status["attempt"]["value"] + ".json")
                job_path.parent.mkdir(exist_ok=True)
                job_path.write_text(json.dumps({"workspace": {"attempt": attempt["id"], "owner": attempt["session"], "base": fixture["run"]["base"]},
                    "phase": "Settled", "exit": {"settled": True, "code": 0, "reason": "Exited", "hostFailure": False}}))
            values["observations"] = [{"upload": {"observation": {"attempt": value["attempt"]["id"], "counters": {"input": {"value": "1", "measurement": "Observed"}}}}} for value in values["attempts"]]
            predicates["correction_routes"](values, settings)
            for field, replacement in [("harness", "Claude"), ("parent", {"value": "foreign"}), ("session", {"value": "foreign"}), ("model", "other-model")]:
                changed = json.loads(path.read_text())
                original = changed["attempt"][field]
                changed["attempt"][field] = replacement
                path.write_text(json.dumps(changed))
                with self.subTest(field=field), self.assertRaises(AssertionError):
                    predicates["correction_routes"](values, settings)
                changed["attempt"][field] = original
                path.write_text(json.dumps(changed))


class ProcessCloseoutCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: bounded closeout and historical authority."""

    def fixture(self):
        item = {"project": {"value": "project"}, "ledger": "Handoffs", "number": "1"}
        task = {**item, "ledger": "Tasks"}
        old = {"item": {"id": item, "revision": {"value": "2"}, "createdAt": "1", "draft": {"body": "Pending", "archived": False, "labels": []}}, "refs": [{"relation": "DerivedFrom", "target": task}]}
        other = {"item": {"id": task, "revision": {"value": "5"}}, "refs": []}
        histories = [{"id": view["item"]["id"], "page": {"hasMore": False, "entries": [{"item": view}]}} for view in [old, other]]
        before = {"views": [old, other], "histories": histories, "claims": {"claims": [], "integrations": []}, "git": {"value": "candidate"}, "target": {"value": "candidate"}, "clean": True}
        after = copy.deepcopy(before)
        changed = after["views"][0]
        session = {"value": "fresh-session"}
        request = {"value": "change"}
        changed["item"].update(revision={"value": "3"}, provenance={"actor": {"session": session, "role": "Governor"}, "request": request})
        changed["item"]["draft"]["body"] = "Recorded integration; independent assessment remains pending"
        after["histories"][0]["page"]["entries"] = [{"item": copy.deepcopy(changed)}, *copy.deepcopy(before["histories"][0]["page"]["entries"])]
        fence = {"claim": {"value": "fresh-claim"}, "generation": "7"}
        calls = [("mcp__cq__claim", {"action": {"Acquire": {"members": [item]}}}, {"Claimed": {"claim": {"members": [item], "owner": {"session": session}, "fence": fence}}}),
                 ("mcp__cq__change", {"change": {"request": request, "fences": [fence], "mutations": [{"Replace": {"id": item, "expected": old["item"]["revision"], "draft": changed["item"]["draft"]}}]}}, {"Changed": {"ack": {"request": request, "items": [{"id": item, "revision": {"value": "3"}}]}}}),
                 ("mcp__cq__claim", {"action": {"Release": {"fence": fence}}}, {"Released": {}})]
        events = []
        for index, (name, args, reply) in enumerate(calls):
            events += [{"message": {"content": [{"type": "tool_use", "id": str(index), "name": name, "input": args}]}},
                       {"message": {"content": [{"type": "tool_result", "tool_use_id": str(index), "content": json.dumps(reply)}]}}]
        return {"before": before, "after": after, "run": {"attempt": {"id": {"value": "governor"}, "session": session}},
                "governing_input": {"attempt": {"value": "governor"}, "body": {"integrationTarget": None}}, "events": events, "children": [], "integrations": []}

    def check(self, fixture):
        return runpy.run_path(str(Path(__file__).with_name("process-closeout-evidence.py")))["closeout_change"](**fixture)

    def test_closeout_uses_fresh_authority_and_changes_one_handoff(self):
        self.assertEqual(self.check(self.fixture())["member"]["revision"], {"value": "3"})

    def test_idempotent_native_acknowledgement_replay_is_not_a_second_mutation(self):
        fixture = self.fixture()
        fixture["events"] = fixture["events"][:2] * 2 + fixture["events"][2:4] * 2 + fixture["events"][4:]
        self.check(fixture)

    def test_closeout_rejects_expanded_scope_or_rewritten_history(self):
        mutations = {
            "child dispatch": lambda f: f["children"].append("child"),
            "new integration": lambda f: f["integrations"].append("integration"),
            "configured integration": lambda f: f["governing_input"]["body"].update(integrationTarget="refs/heads/integration"),
            "changed Git": lambda f: f["after"].update(git={"value": "another"}),
            "dirty worktree": lambda f: f["after"].update(clean=False),
            "unexpired old authority": lambda f: f["before"]["claims"]["claims"].append("old"),
            "unreleased new authority": lambda f: f["after"]["claims"]["claims"].append("new"),
            "changed task": lambda f: f["after"]["views"][1]["item"].update(revision={"value": "6"}),
            "changed relations": lambda f: f["after"]["views"][0]["refs"].clear(),
            "multiple Handoff revisions": lambda f: f["after"]["views"][0]["item"].update(revision={"value": "4"}),
            "historical authority": lambda f: f["after"]["views"][0]["item"]["provenance"]["actor"].update(session={"value": "old"}),
            "rewritten history": lambda f: f["after"]["histories"][0]["page"]["entries"].pop(),
            "missing fresh claim": lambda f: f.update(events=f["events"][2:]),
            "missing release": lambda f: f.update(events=f["events"][:-2]),
            "missing fence": lambda f: f["events"][2]["message"]["content"][0]["input"]["change"].update(fences=[]),
        }
        for name, mutate in mutations.items():
            fixture = self.fixture()
            mutate(fixture)
            with self.subTest(name=name), self.assertRaises(AssertionError):
                self.check(fixture)

    def test_only_settled_incorporated_deadline_archives_are_eligible(self):
        producer = runpy.run_path(str(Path(__file__).with_name("process-closeout-evidence.py")))["producer"]
        members = [{"id": {"ledger": "Tasks", "number": str(n)}, "revision": {"value": "3"}} for n in [1, 2]]
        values = {"result.json": {"status": "failed", "stage": "resume", "archiveErrors": []},
                  "sessions/producer/run.json": {"attempt": {"id": {"value": "governor"}}},
                  "sessions/producer/journal/governor.json": {"phase": "Settled", "exit": {"settled": True, "hostFailure": False, "reason": "ExecutionDeadline"}},
                  "sessions/producer/integrations/one.json": {"intent": {"candidate": {"value": "candidate"}, "members": members}, "attempted": True, "observation": {"Incorporated": {"target": {"value": "candidate"}}}},
                  "items.json": [{"item": {"id": m["id"], "revision": {"value": "4"}, "draft": {"content": {"Task": {"status": "Done"}}}}} for m in members],
                  "histories.json": [], "dispatch-statuses.json": [], "candidate-evidence.json": {}, "settings.json": {}}
        mutations = {
            "still running": lambda v: v["sessions/producer/journal/governor.json"].update(phase="Running"),
            "uncertain settlement": lambda v: v["sessions/producer/journal/governor.json"]["exit"].update(settled=False),
            "host failure": lambda v: v["sessions/producer/journal/governor.json"]["exit"].update(hostFailure=True),
            "another failure": lambda v: v["sessions/producer/journal/governor.json"]["exit"].update(reason="OutputLimit"),
            "incomplete archive": lambda v: v["result.json"]["archiveErrors"].append("missing"),
            "pending integration": lambda v: v["sessions/producer/integrations/one.json"].update(observation=None),
            "unattempted integration": lambda v: v["sessions/producer/integrations/one.json"].update(attempted=False),
            "later task edit": lambda v: v["items.json"][0]["item"].update(revision={"value": "5"}),
            "unfinished task": lambda v: v["items.json"][0]["item"]["draft"]["content"]["Task"].update(status="Ready"),
        }
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def store(current):
                for name, value in current.items():
                    path = directory / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(json.dumps(value))
            read = lambda path: json.loads(path.read_text())
            store(values)
            self.assertEqual(producer(directory, read)["candidate"], {"value": "candidate"})
            for name, mutate in mutations.items():
                changed = copy.deepcopy(values)
                mutate(changed)
                store(changed)
                with self.subTest(name=name), self.assertRaises(AssertionError):
                    producer(directory, read)


if __name__ == "__main__":
    unittest.main()
