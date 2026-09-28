import copy
import json
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-integrate-evidence.py")))


class DefectIntegrationCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: integration authority and historical preservation."""

    def fixture(self):
        _, before, _ = runpy.run_path(str(Path(__file__).with_name("defect-plan-check.py")))["DefectPlanningCheck"]().fixture()
        task = before["views"][-1]["item"]["id"]
        owner = {"role": "Governor", "session": {"value": "session"}, "subject": "CQ governor"}
        for view in before["views"]:
            view["item"].update(createdAt="2026-09-28T00:00:00Z", provenance={"request": {"value": "plan"}, "actor": owner})
        before.update(git={"value": "base"}, clean=True, localIntegrations=[])
        after = copy.deepcopy(before)
        new = after["views"][-1]
        ready = {"id": task, "revision": copy.deepcopy(new["item"]["revision"])}
        new["item"].update(revision={"value": "3"}, provenance={"request": {"value": "integration"}, "actor": owner})
        new["item"]["draft"]["content"]["Task"].update(status="Done", result="incorporated", validation=[{
            "origin": "HostObserved", "citations": [{"Artifact": {"id": {"value": handle}}} for handle in ["worker", "reviewer"]]}])
        after["histories"][-1]["page"]["entries"].insert(0, {"item": copy.deepcopy(new), "cursor": {"value": "10"}})
        after["claims"] = {"claims": [], "integrations": [], "members": [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in after["views"]]}
        target = {"value": "candidate"}
        checks = [{"name": "defect-oracle"}]
        intent = {"id": {"value": "integration"}, "project": task["project"], "owner": owner, "target": "refs/heads/integration",
            "expected": before["git"], "candidate": target, "checks": checks, "members": [ready], "worker": {"value": "worker"}, "reviewer": {"value": "reviewer"},
            "change": {"request": {"value": "integration"}, "mutations": [{"Replace": {"id": task, "expected": ready["revision"], "draft": new["item"]["draft"]}}]}}
        ack = {"request": {"value": "integration"}, "cursor": {"value": "10"}, "items": [{"id": task, "revision": new["item"]["revision"]}]}
        local = {"intent": intent, "attempted": True, "observation": {"Incorporated": {"target": target}}}
        after["localIntegrations"] = [local]
        record = {"intent": intent, "resolution": {"Recorded": {"observedTarget": target, "acknowledgement": ack}}}
        return json.loads(json.dumps({"before": before, "after": after, "integration": {"record": record, "local": local}, "target": target,
            "run": {"base": before["git"], "attempt": {"session": owner["session"]}}, "checks": checks, "task": task}))

    def test_exact_ready_to_done_acknowledgement_preserves_investigation(self):
        proof = predicates["incorporated"](**self.fixture())
        self.assertEqual(proof["member"]["revision"], {"value": "2"})
        self.assertEqual(proof["done"]["revision"], {"value": "3"})

    def test_other_record_history_stale_revision_and_acknowledgement_are_rejected(self):
        mutations = {
            "other record": lambda f: f["after"]["views"][0]["item"]["draft"].update(body="changed"),
            "erased history": lambda f: f["after"]["histories"][-1]["page"]["entries"].pop(),
            "stale assignment": lambda f: f["integration"]["record"]["intent"]["members"][0].update(revision={"value": "1"}),
            "wrong candidate": lambda f: f["target"].update(value="different"),
            "unrecorded": lambda f: f["integration"]["record"].update(resolution={"Pending": {}}),
            "wrong ack": lambda f: f["integration"]["record"]["resolution"]["Recorded"]["acknowledgement"].update(cursor={"value": "11"}),
            "active claim": lambda f: f["after"]["claims"]["claims"].append({}),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                fixture = self.fixture()
                mutate(fixture)
                with self.assertRaises(AssertionError):
                    predicates["incorporated"](**fixture)

    def chain(self):
        statuses, artifacts, checks = runpy.run_path(str(Path(__file__).with_name("consumer-evidence-check.py")))["ConsumerEvidenceCheck"]().fixture()
        worker, review = [artifacts[handle]["body"] for handle in ["worker-result", "review-result"]]
        worker["request"]["harness"] = "Pi"
        review["request"]["harness"] = "Codex"
        review["report"]["Review"]["proposal"] = None
        artifacts["fresh"] = copy.deepcopy(artifacts["validation"])
        artifacts["fresh"]["attempt"] = review["attempt"]
        review["validation"][0]["artifact"] = {"value": "fresh"}
        intent = {"worker": {"value": "worker-result"}, "reviewer": {"value": "review-result"}, "candidate": worker["candidate"], "members": worker["request"]["members"]}
        return {"values": {"statuses": statuses, "artifacts": artifacts}, "settings": {"checks": list(checks.values())}, "intent": intent}

    def test_accepted_pair_requires_its_own_fresh_reviewer_check(self):
        fixture = self.chain()
        predicates["accepted_candidate"](**fixture)
        artifacts = fixture["values"]["artifacts"]
        artifacts["review-result"]["body"]["validation"] = copy.deepcopy(artifacts["worker-result"]["body"]["validation"])
        with self.assertRaisesRegex(AssertionError, "fresh checks"):
            predicates["accepted_candidate"](**fixture)
        fixture = self.chain()
        fixture["values"]["artifacts"]["review-result"]["body"]["report"]["Review"]["members"][0]["verdict"] = "ChangesRequested"
        with self.assertRaises(AssertionError):
            predicates["accepted_candidate"](**fixture)

    def test_original_tests_and_git_scope_are_preserved(self):
        """Behavioral Active Blackbox Good Communication: actual candidate Git objects."""
        fixture = Path(__file__).parent / "fixtures/defect"
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary) / "consumer"
            shutil.copytree(fixture, repository, ignore=shutil.ignore_patterns("__pycache__"))
            def git(*arguments):
                return subprocess.check_output(["git", "-C", str(repository), "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", *arguments], stderr=subprocess.PIPE).decode().strip()
            git("init", "--quiet")
            git("add", ".")
            git("commit", "--quiet", "-m", "baseline")
            base = {"value": git("rev-parse", "HEAD")}
            helper = repository / "synthetic_tokens.py"
            helper.write_text(helper.read_text().replace(r"\w+", "[A-Za-z]+"))
            git("commit", "--quiet", "-am", "repair")
            candidate = {"value": git("rev-parse", "HEAD")}
            with self.assertRaisesRegex(AssertionError, "additional regression"):
                predicates["candidate_scope"](repository, base, candidate)
            (repository / "test_regression.py").write_text("import unittest\nfrom synthetic_tokens import tokens\n\nclass Regression(unittest.TestCase):\n    def test_delimiters(self):\n        self.assertEqual(tokens('a_b12café'), ['a', 'b', 'caf'])\n")
            git("add", "test_regression.py")
            git("commit", "--quiet", "-m", "regression")
            candidate = {"value": git("rev-parse", "HEAD")}
            self.assertEqual(predicates["candidate_scope"](repository, base, candidate)["changedFiles"], ["synthetic_tokens.py", "test_regression.py"])
            import sys
            predicates["execute_original"](Path(temporary), candidate, sys.executable)
            observed = json.loads((Path(temporary) / "original-test-execution.json").read_text())
            observed["stderr"] = observed["stderr"].replace("... ok", "... skipped 'disabled'", 1)
            with self.assertRaisesRegex(AssertionError, "all execute"):
                predicates["original_execution"](observed, candidate)
            test = repository / "test_count_words.py"
            test.write_text(test.read_text().replace('self.assertEqual(counts(""), [])', 'self.assertEqual(1, 1)'))
            git("commit", "--quiet", "-am", "weaken original test")
            with self.assertRaisesRegex(AssertionError, "original regression"):
                predicates["candidate_scope"](repository, base, {"value": git("rev-parse", "HEAD")})
            (repository / "README.md").write_text("Changed specification")
            git("commit", "--quiet", "-am", "change specification")
            with self.assertRaisesRegex(AssertionError, "outside"):
                predicates["candidate_scope"](repository, base, {"value": git("rev-parse", "HEAD")})

    def test_original_test_class_cannot_be_skipped(self):
        before = (Path(__file__).parent / "fixtures/defect/test_count_words.py").read_text()
        after = before.replace("class CountingTest", "@unittest.skip('disabled original regressions')\nclass CountingTest")
        with self.assertRaisesRegex(AssertionError, "original regression"):
            predicates["original_tests"](before, after)


if __name__ == "__main__":
    unittest.main()
