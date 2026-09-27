import copy
import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("process-reopening-evidence.py")))


class ProcessReopeningCheck(unittest.TestCase):
    """Behavioral Active Blackbox: exact reopening authority and isolated Git changes."""

    def test_failed_reopening_retry_requires_unchanged_state_and_one_settled_governor(self):
        attempt = {"id": {"value": "governor"}, "session": {"value": "session"}, "parent": None, "role": "Governor"}
        before = {"views": [{"body": "original"}], "histories": [{"body": "original"}], "claims": {"claims": [], "integrations": []},
                  "git": {"value": "base"}, "target": {"value": "base"}, "clean": True}
        fixture = {"before": before, "values": {"snapshot": {**copy.deepcopy(before), "localIntegrations": []}, "run": {"attempt": attempt, "base": before["git"]},
            "statuses": [], "attempts": [{"attempt": attempt, "outcome": {"value": {"state": "Completed"}}}],
            "observations": [{"upload": {"observation": {"attempt": attempt["id"]}}}]},
            "receipt": {"attempt": attempt["id"], "session": attempt["session"], "phase": "Settled", "processSucceeded": True, "usageDelivered": True, "problem": None},
            "jobs": [{"workspace": {"attempt": attempt["id"], "owner": attempt["session"], "base": before["git"]}, "phase": "Settled",
                      "exit": {"settled": True, "hostFailure": False, "reason": "Exited", "code": 0, "signal": None}}], "integrations": []}
        self.assertEqual(predicates["unchanged_reopening"](**fixture), "governor")
        mutations = {
            "changed record": lambda f: f["values"]["snapshot"]["views"][0].update(body="changed"),
            "rewritten history": lambda f: f["values"]["snapshot"]["histories"][0].update(body="changed"),
            "claim retained": lambda f: f["values"]["snapshot"]["claims"]["claims"].append({}),
            "child attempted": lambda f: f["values"]["statuses"].append({}),
            "integration attempted": lambda f: f["integrations"].append({}),
            "unsettled": lambda f: f["jobs"][0]["exit"].update(settled=False),
            "usage not delivered": lambda f: f["receipt"].update(usageDelivered=False),
            "another meter": lambda f: f["values"]["observations"][0]["upload"]["observation"].update(attempt={"value": "other"}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                changed = copy.deepcopy(fixture)
                mutate(changed)
                with self.assertRaises(AssertionError):
                    predicates["unchanged_reopening"](**changed)

    def fixture(self):
        fixture = runpy.run_path(str(Path(__file__).with_name("process-assess-check.py")))["ProcessAssessmentCheck"]().correction_fixture()
        fixture = json.loads(json.dumps(fixture).replace('"Handoffs"', '"Tasks"'))
        before, after = fixture["before"], fixture["after"]
        old, ready = before["views"][0], after["views"][0]
        old["item"]["draft"].update(title="count words", citations=[], content={"Task": {"status": "Done", "acceptance": ["Tests cover empty input"], "result": "previous", "validation": []}})
        ready["item"]["draft"].update(title="count words", citations=[], content={"Task": {"status": "Ready", "acceptance": ["Tests cover empty input"], "result": None, "validation": []}})
        before["histories"][0]["page"]["entries"][0]["item"] = copy.deepcopy(old)
        after["histories"][0]["page"]["entries"] = [{**after["histories"][0]["page"]["entries"][0], "item": copy.deepcopy(ready)}, *copy.deepcopy(before["histories"][0]["page"]["entries"])]
        fixture["artifacts"]["plan"]["body"]["report"]["Plan"]["proposal"]["mutations"][0]["Replace"]["draft"] = copy.deepcopy(ready["item"]["draft"])
        for artifact in fixture["artifacts"].values():
            artifact["kind"] = "Result"
        statuses, artifacts, checks = runpy.run_path(str(Path(__file__).with_name("consumer-evidence-check.py")))["ConsumerEvidenceCheck"]().fixture()
        checks["empty-input-sensitivity"] = {**copy.deepcopy(checks["consumer-oracle"]), "name": "empty-input-sensitivity", "command": ["python", "empty-check.py"]}
        member = {"id": old["item"]["id"], "revision": ready["item"]["revision"]}
        for handle, work, harness in [("worker-result", {"Worker": {"mode": "Implement"}}, "Pi"), ("review-result", {"Reviewer": {"mode": "Candidate"}}, "Codex")]:
            value = artifacts[handle]["body"]
            value["base"] = before["git"] if handle == "worker-result" else value["candidate"]
            value["request"].update(work=work, harness=harness, members=[member], artifacts=[fixture["finding"]])
            if handle == "worker-result":
                value["request"]["previous"] = None
            value["validation"] = []
            for name, check in checks.items():
                key = handle + "-" + name
                observation = copy.deepcopy(artifacts["validation"])
                observation["attempt"] = value["attempt"]
                observation["body"]["check"] = check
                artifacts[key] = observation
                value["validation"].append({"check": name, "state": "Passed", "artifact": {"value": key}})
        fixture["statuses"].extend(statuses)
        fixture["artifacts"].update(artifacts)
        candidate = artifacts["worker-result"]["body"]["candidate"]
        done = copy.deepcopy(ready)
        done["item"].update(revision={"value": "5"}, provenance={"request": {"value": "integration"}, "actor": {"session": fixture["run"]["attempt"]["session"]}})
        done["item"]["draft"]["content"]["Task"].update(status="Done", result="integrated", validation=[{"origin": "HostObserved", "citations": [{"Artifact": {"id": {"value": "review-result"}}}]}])
        after["views"][0] = done
        after["histories"][0]["page"]["entries"].insert(0, {"item": done, "cursor": {"value": "3"}})
        after.update(git=candidate, target=candidate)
        second = copy.deepcopy(old)
        second["item"]["id"]["number"] = "2"
        for snapshot in [before, after]:
            snapshot["views"].append(copy.deepcopy(second))
            snapshot["histories"].append({"id": second["item"]["id"], "page": {"hasMore": False, "entries": [{"item": copy.deepcopy(second)}]}})
        fixture["governing_input"]["body"]["workflow"]["request"]["Advance"]["through"] = "Integrate"
        fixture["governing_input"]["body"]["integrationTarget"] = "refs/heads/integration"
        intent = {"id": {"value": "integration"}, "owner": {"session": fixture["run"]["attempt"]["session"]}, "target": "refs/heads/integration", "candidate": candidate,
            "members": [member], "worker": {"value": "worker-result"}, "reviewer": {"value": "review-result"}}
        ack = {"request": {"value": "integration"}, "cursor": {"value": "3"}, "items": [{"id": member["id"], "revision": {"value": "5"}}]}
        local = {"intent": intent, "attempted": True, "observation": {"Incorporated": {"target": candidate}}}
        after["localIntegrations"] = [local]
        return json.loads(json.dumps({"before": before, "values": {"snapshot": after, "statuses": fixture["statuses"], "artifacts": fixture["artifacts"], "run": fixture["run"], "governing_input": fixture["governing_input"]},
            "settings": {"checks": list(checks.values())}, "integrations": [{"local": local, "record": {"intent": intent, "resolution": {"Recorded": {"observedTarget": candidate, "acknowledgement": ack}}}}], "finding": fixture["finding"]}))

    def test_reviewed_reopening_preserves_previous_acceptance_and_other_task(self):
        result = predicates["reopening_stage"](**self.fixture())
        self.assertEqual(result["member"]["revision"], {"value": "4"})
        self.assertEqual(result["done"]["revision"], {"value": "5"})

    def test_stale_authority_altered_history_and_inherited_checks_are_rejected(self):
        mutations = {
            "old worker scope": lambda f: f["values"]["artifacts"]["worker-result"]["body"]["request"]["members"][0].update(revision={"value": "3"}),
            "other task changed": lambda f: f["values"]["snapshot"]["views"][1]["item"]["draft"].update(body="changed"),
            "criteria changed": lambda f: f["values"]["snapshot"]["histories"][0]["page"]["entries"][1]["item"]["item"]["draft"]["content"]["Task"].update(acceptance=[]),
            "history rewritten": lambda f: f["values"]["snapshot"]["histories"][0]["page"]["entries"][2]["item"]["item"]["draft"].update(body="changed"),
            "unreviewed plan": lambda f: f["values"]["artifacts"]["review"]["body"]["report"]["Review"]["members"][0].update(verdict="ChangesRequested"),
            "wrong done ack": lambda f: f["integrations"][0]["record"]["resolution"]["Recorded"]["acknowledgement"]["items"][0].update(revision={"value": "6"}),
            "foreign session": lambda f: f["integrations"][0]["record"]["intent"]["owner"].update(session={"value": "other"}),
            "failed mutant check": lambda f: f["values"]["artifacts"]["review-result-empty-input-sensitivity"]["body"]["job"]["exit"].update(code=1),
            "inherited checks": lambda f: f["values"]["artifacts"]["review-result"]["body"].update(validation=copy.deepcopy(f["values"]["artifacts"]["worker-result"]["body"]["validation"])),
            "wrong worker base": lambda f: f["values"]["artifacts"]["worker-result"]["body"].update(base={"value": "different"}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                fixture = self.fixture()
                mutate(fixture)
                with self.assertRaises(AssertionError):
                    predicates["reopening_stage"](**fixture)

    def test_git_diff_and_answer_bytes_bind_the_actual_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            def git(*arguments):
                return subprocess.check_output(["git", "-C", str(repository), "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", *arguments], stderr=subprocess.PIPE).decode().strip()
            git("init", "--quiet")
            (repository / ".cq-evaluation").mkdir()
            (repository / ".cq-evaluation/answer.json").write_text('{"answer":"Go"}\n')
            (repository / "main.go").write_text("production")
            (repository / "main_test.go").write_text("tests")
            git("add", ".")
            git("commit", "--quiet", "-m", "baseline")
            before = {"value": git("rev-parse", "HEAD")}
            (repository / "main_test.go").write_text("additional empty test")
            git("commit", "--quiet", "-am", "test correction")
            after = {"value": git("rev-parse", "HEAD")}
            self.assertEqual(predicates["test_only_candidate"](repository, before, after)["changedFiles"], ["main_test.go"])
            self.assertTrue(predicates["answer_comparison"](repository, before, after)["identical"])
            (repository / ".cq-evaluation/answer.json").write_text('{ "answer": "Go" }\n')
            git("commit", "--quiet", "-am", "same semantic answer, different bytes")
            changed = {"value": git("rev-parse", "HEAD")}
            with self.assertRaisesRegex(AssertionError, "outside"):
                predicates["test_only_candidate"](repository, before, changed)
            with self.assertRaisesRegex(AssertionError, "answer bytes"):
                predicates["answer_comparison"](repository, before, changed)


if __name__ == "__main__":
    unittest.main()
