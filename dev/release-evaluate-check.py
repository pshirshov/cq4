"""Behavioral Active Effectual / local processes: release scheduling and checkpoint admission."""
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
CHILD = '''#!/usr/bin/env python3
import json,os,pathlib,sys,uuid
def same_runtime(first, second):
    return first == second
if __name__ == '__main__':
    args=sys.argv[1:]; tool=pathlib.Path(__file__).name
    predecessor=None
    if tool=='consumer-eval':
        stage='cohort-'+args[0]; status='candidate-passed'
    elif tool=='consumer-assess':
        predecessor=args[-1]; stage=json.loads((pathlib.Path(predecessor)/'result.json').read_text())['fixtureStage']+'-assess'; status='assessment-passed'
    elif tool=='process-eval':
        stage='process-'+args[0]; status='awaiting-user-answer' if args[0]=='begin' else 'integrated-candidate-passed'
        if args[0]=='resume': predecessor=args[args.index('--checkpoint')+1]
    elif tool=='process-assess':
        stage='process-assess'; status='assessment-passed'; predecessor=args[-1]
    else:
        stage='defect-'+args[0]; status=args[0]+'-passed'
        if '--checkpoint' in args: predecessor=args[args.index('--checkpoint')+1]
    directory=pathlib.Path(os.environ['CQ_EVIDENCE_ROOT'])/(stage+'-'+uuid.uuid4().hex)
    directory.mkdir(parents=True)
    result={'status':status,'fixtureStage':stage,'release':{'fixture':'native'},'baselineEvidence':predecessor,
            'harness':args[0],'language':args[1] if tool=='consumer-eval' else None,'cohortAssessment':{},'checkpoint':{},'archiveErrors':[]}
    rejected=os.environ.get('FIXTURE_REJECT')==stage
    if rejected: result['status']=os.environ['FIXTURE_STATUS']
    sources=json.loads((pathlib.Path(__file__).parent.parent/'sources.json').read_text())
    if os.environ.get('FIXTURE_STALE'): sources['dev/defect-eval']='original'
    values={'result.json':result,'source-sha256.json':sources,'items.json':[],'histories.json':[],
            'checkpoint-handoff.json':{'Claims':{'preview':{}}},'cohort-seed.json':{},'accepted-cohort-audit.json':{},
            'dispatch-statuses.json':[{'attempt':{'value':'child'}}],'candidate-evidence.json':{},
            'attempts.json':{'UsageAttempts':{'page':{'entries':[]}}},'assessment-before.json':{'Claims':{'preview':{}}},
            'assessment-handoff.json':{'Claims':{'preview':{}}},'sessions/one/run.json':{},'sessions/one/receipt.json':{},
            'sessions/one/children/child/ticket.json':{'attempt':{'id':{'value':'child'}}},'sessions/one/journal/child.json':{}}
    for name,value in values.items():
        if os.environ.get('FIXTURE_NO_MANIFEST') and rejected and name=='result.json': continue
        path=directory/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value))
    print('Evidence:',directory,flush=True)
    sys.exit(int(os.environ.get('FIXTURE_EXIT','0')) if rejected else 0)
'''


class ReleaseRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cq-release-runner-")
        self.root = Path(self.temporary.name)
        dev = self.root / "dev"
        dev.mkdir()
        shutil.copy(ROOT / "dev/release-evaluate", dev / "release-evaluate")
        for name in ["consumer-eval", "consumer-assess", "process-eval", "process-assess", "defect-eval"]:
            (dev / name).write_text(CHILD)
            (dev / name).chmod(0o700)
        fixtures = {
            "package": "def load_release(path): return [], path, {'fixture':'native'}\n",
            "package-check": "import json\nfrom pathlib import Path\ndef verifier_sources(): return json.loads((Path(__file__).parent.parent/'sources.json').read_text())\n",
            "consumer-cohort.py": "def retained(path): return {}\ndef audited(*args): return {}\n",
            "process-evidence.py": "def question_checkpoint(*args): return {}\n",
            "process-assess-evidence.py": "def retained(path): return {'proof':{}}\ndef retained_assessment(*args): return {'attemptIds':[]}\n",
            "defect-evidence.py": "def checkpoint(path): return {'manifest':{'proof':{}}}\n",
            "release-report.py": "import hashlib,json\nfrom pathlib import Path\ndef lineage_roots(path):\n result=json.loads((path/'result.json').read_text()); prior=result.get('baselineEvidence')\n return {**(lineage_roots(Path(prior)) if prior else {}),path:hashlib.sha256((path/'result.json').read_bytes()).hexdigest()}\ndef experimental_usage(*args): return {}\ndef report(path): return {'status':'release-corpus-observed'}\n",
        }
        for name, value in fixtures.items():
            (dev / name).write_text(value)
        self.release = self.root / "release"
        self.release.mkdir()
        self.answer = self.root / "answer.json"
        self.answer.write_text('{}')
        (self.root / "sources.json").write_text(json.dumps({"dev/defect-eval": "original", "dev/defect-evidence.py": "predicate", "app/runtime": "native"}))
        self.environment = dict(os.environ, CQ_EVIDENCE_ROOT=str(self.root / "evidence"))

    def tearDown(self):
        self.temporary.cleanup()

    def run_suite(self, arguments, environment, succeeds):
        output = subprocess.run([sys.executable, str(self.root / "dev/release-evaluate"), "--release", str(self.release), *arguments],
                                env=environment, text=True, capture_output=True, timeout=30)
        self.assertEqual(output.returncode == 0, succeeds, output.stdout + output.stderr)
        self.output = output.stdout + output.stderr
        manifests = list((self.root / "evidence").glob("*/suite.json"))
        self.assertEqual(len(manifests), 1)
        return manifests[0].parent, json.loads(manifests[0].read_text())

    def amendment(self, suite, changes):
        before = suite["sourceSha256"]
        after = {**before, **changes}
        (self.root / "sources.json").write_text(json.dumps(after))
        review = {"decision": "accepted", "beforeSources": before, "afterSources": after,
                  "affectedStages": ["defect-probe"], "scope": "Controlled test review; unchanged oracle and runtime."}
        review_path = self.root / "review.json"
        review_path.write_text(json.dumps(review))
        failed = next(attempt for attempt in suite["attempts"] if attempt["status"] == "rejected")
        value = {"beforeSources": before, "afterSources": after, "affectedStages": review["affectedStages"],
                 "reason": "Clarify probe output constraint; preserve failed attempt.",
                 "failure": {"invocation": failed["id"], "resultSha256": failed["resultSha256"]},
                 "review": {"path": str(review_path), "sha256": hashlib.sha256(review_path.read_bytes()).hexdigest()}}
        path = self.root / "amendment.json"
        path.write_text(json.dumps(value))
        return path

    def rejected_probe(self):
        return self.run_suite([], dict(self.environment, FIXTURE_REJECT="defect-probe", FIXTURE_STATUS="failed"), True)

    def test_reviewed_amendment_preserves_accepted_routes_and_failed_attempt(self):
        directory, before = self.rejected_probe()
        amendment = self.amendment(before, {"dev/defect-eval": "clarified"})
        _, after = self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--retry", "defect-probe=Clarified output contract"], self.environment, True)
        self.assertEqual(after["sourceSha256"], before["sourceSha256"])
        self.assertEqual(after["attempts"][:9], before["attempts"])
        self.assertEqual([a["stage"] for a in after["attempts"][9:]], ["defect-" + s for s in ["probe", "research", "plan", "integrate", "upstream", "assess"]])
        self.assertTrue(all(a["sourceEpoch"] == 1 for a in after["attempts"][9:]))
        self.assertEqual(len(after["amendments"]), 1)
        _, replayed = self.run_suite(["--resume", str(directory), "--report-only"], self.environment, True)
        self.assertEqual(replayed["attempts"], after["attempts"])

    def test_amendment_rejects_unlisted_or_protected_source_changes(self):
        directory, before = self.rejected_probe()
        amendment = self.amendment(before, {"dev/defect-eval": "clarified"})
        sources = json.loads((self.root / "sources.json").read_text())
        (self.root / "sources.json").write_text(json.dumps({**sources, "unlisted": "changed"}))
        self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--report-only"], self.environment, False)
        self.assertIn("Amendment source snapshot differs", self.output)
        for protected in ["dev/defect-evidence.py", "app/runtime"]:
            with self.subTest(protected=protected):
                amendment = self.amendment(before, {protected: "changed"})
                self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--report-only"], self.environment, False)
                self.assertIn("Protected evaluation inputs changed", self.output)

    def test_historical_evidence_and_review_cannot_change_after_amendment(self):
        directory, before = self.rejected_probe()
        amendment = self.amendment(before, {"dev/defect-eval": "clarified"})
        self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--report-only"], self.environment, True)
        target = Path(before["attempts"][0]["evidence"]) / "items.json"
        original = target.read_bytes()
        target.write_text('["altered"]')
        self.run_suite(["--resume", str(directory), "--report-only"], self.environment, False)
        self.assertIn("Retained evidence changed", self.output)
        target.write_bytes(original)
        (self.root / "review.json").write_text('{}')
        self.run_suite(["--resume", str(directory), "--report-only"], self.environment, False)
        self.assertIn("Amendment review changed", self.output)

    def test_new_invocation_cannot_use_historical_sources(self):
        directory, before = self.rejected_probe()
        amendment = self.amendment(before, {"dev/defect-eval": "clarified"})
        _, after = self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--retry", "defect-probe=Controlled stale-source rejection"], dict(self.environment, FIXTURE_STALE="yes"), True)
        self.assertEqual(after["attempts"][-1]["status"], "rejected")
        self.assertIn("Stage used different source inputs", after["attempts"][-1]["error"])

    def test_nonzero_failed_invocations_freeze_evidence_even_without_manifest(self):
        for missing in [False, True]:
            with self.subTest(missing_manifest=missing):
                shutil.rmtree(self.root / "evidence", ignore_errors=True)
                environment = dict(self.environment, FIXTURE_REJECT="cohort-claude-assess", FIXTURE_STATUS="failed", FIXTURE_EXIT="1")
                if missing:
                    environment["FIXTURE_NO_MANIFEST"] = "yes"
                directory, before = self.run_suite([], environment, True)
                failed = before["attempts"][1]
                self.assertEqual(failed["status"], "rejected")
                target = Path(failed["evidence"]) / "candidate-evidence.json"
                original = target.read_bytes()
                target.write_text('{"changed":true}')
                self.run_suite(["--resume", str(directory), "--report-only"], self.environment, False)
                self.assertIn("Retained evidence changed", self.output)
                target.write_bytes(original)
                self.assertEqual(failed["provenance"], "incomplete" if missing else "verified")

    def test_adoption_preserves_registered_historical_epoch(self):
        directory, before = self.run_suite(["--answer-file", str(self.answer)],
            dict(self.environment, FIXTURE_REJECT="defect-probe", FIXTURE_STATUS="failed"), True)
        prior = next(a["evidence"] for a in before["attempts"] if a["stage"] == "process-resume")
        amendment = self.amendment(before, {"dev/defect-eval": "clarified"})
        _, after = self.run_suite(["--resume", str(directory), "--amendment", str(amendment), "--adopt", "process-resume=" + prior], self.environment, True)
        adopted = after["attempts"][-2]
        self.assertEqual(adopted["status"], "accepted")
        self.assertEqual(adopted["sourceEpoch"], 1)
        self.assertEqual(adopted["evidenceSourceEpoch"], 0)
        self.assertEqual(after["attempts"][-1]["stage"], "process-assess")
        self.assertEqual(after["attempts"][-1]["evidenceSourceEpoch"], 1)

    def test_resume_preserves_successful_stages_and_waits_for_actual_answer(self):
        directory, before = self.run_suite([], self.environment, True)
        self.assertEqual(before["pending"], ["process-resume", "process-assess"])
        self.assertEqual(len(before["attempts"]), 14)
        _, after = self.run_suite(["--resume", str(directory), "--answer-file", str(self.answer)], self.environment, True)
        self.assertEqual(after["status"], "corpus-passed")
        self.assertEqual(after["attempts"][:14], before["attempts"])
        self.assertEqual(len(after["attempts"]), 16)
        _, reported = self.run_suite(["--resume", str(directory), "--report-only"], self.environment, True)
        self.assertEqual(reported["attempts"], after["attempts"])

    def test_zero_exit_quality_rejection_retained_while_other_tracks_continue(self):
        environment = dict(self.environment, FIXTURE_REJECT="cohort-claude-assess", FIXTURE_STATUS="assessment-not-accepted")
        directory, before = self.run_suite(["--answer-file", str(self.answer)], environment, True)
        self.assertEqual(before["pending"], ["cohort-claude-assess"])
        self.assertEqual(len(before["attempts"]), 16)
        self.assertEqual(before["attempts"][1]["status"], "rejected")
        _, unchanged = self.run_suite(["--resume", str(directory)], self.environment, True)
        self.assertEqual(unchanged["attempts"], before["attempts"])
        _, after = self.run_suite(["--resume", str(directory), "--retry", "cohort-claude-assess=Controlled transport recovery"], self.environment, True)
        self.assertEqual(len(after["attempts"]), 17)
        self.assertEqual(after["attempts"][:16], before["attempts"])
        self.assertEqual(after["status"], "corpus-passed")

    def test_adopted_resume_needs_no_new_answer_and_rejects_wrong_predecessor(self):
        directory, before = self.run_suite([], self.environment, True)
        begin = next(attempt["evidence"] for attempt in before["attempts"] if attempt["stage"] == "process-begin")
        external = self.root / "external"
        external.mkdir()
        output = subprocess.run([str(self.root / "dev/process-eval"), "resume", "--checkpoint", begin],
                                env=dict(self.environment, CQ_EVIDENCE_ROOT=str(external)), capture_output=True, text=True, check=True)
        adopted = Path(output.stdout.removeprefix("Evidence: ").strip())
        _, after = self.run_suite(["--resume", str(directory), "--adopt", "process-resume=" + str(adopted)], self.environment, True)
        self.assertEqual(after["status"], "corpus-passed")
        result = json.loads((adopted / "result.json").read_text())
        result["baselineEvidence"] = str(external)
        (adopted / "result.json").write_text(json.dumps(result))
        self.run_suite(["--resume", str(directory), "--report-only"], self.environment, False)

    def test_replay_rejects_wrong_package_before_reading_evidence(self):
        stage = runpy.run_path(str(ROOT / "dev/release-evaluate"))["stages"]()[0]
        path = self.root / "wrong"
        path.mkdir()
        (path / "result.json").write_text(json.dumps({"status": "candidate-passed", "release": None}))
        with self.assertRaisesRegex(AssertionError, "different release"):
            runpy.run_path(str(ROOT / "dev/release-evaluate"))["replay"](stage, path, None, {"manifestSha256": "native"})

    def test_replay_rejects_wrong_predecessor_before_admission(self):
        helper = runpy.run_path(str(ROOT / "dev/release-evaluate"))
        stage = helper["stages"]()[1]
        identity = {"manifestSha256": "manifest", "executableSha256": "binary", "guardianSha256": "guardian"}
        path = self.root / "wrong-predecessor"
        path.mkdir()
        (path / "result.json").write_text(json.dumps({"status": "assessment-passed", "release": identity, "baselineEvidence": str(self.root / "other")}))
        with self.assertRaisesRegex(AssertionError, "different predecessor"):
            helper["replay"](stage, path, self.root / "expected", identity)


if __name__ == "__main__":
    unittest.main()
