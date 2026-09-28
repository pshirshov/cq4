"""Behavioral Active Effectual / local processes: release scheduling and checkpoint admission."""
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
    values={'result.json':result,'source-sha256.json':{'fixture':'same'},'items.json':[],'histories.json':[],
            'checkpoint-handoff.json':{'Claims':{'preview':{}}},'cohort-seed.json':{},'accepted-cohort-audit.json':{},
            'dispatch-statuses.json':[{'attempt':{'value':'child'}}],'candidate-evidence.json':{},
            'attempts.json':{'UsageAttempts':{'page':{'entries':[]}}},'assessment-before.json':{'Claims':{'preview':{}}},
            'assessment-handoff.json':{'Claims':{'preview':{}}},'sessions/one/run.json':{},'sessions/one/receipt.json':{},
            'sessions/one/children/child/ticket.json':{'attempt':{'id':{'value':'child'}}},'sessions/one/journal/child.json':{}}
    for name,value in values.items():
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
            "package-check": "def verifier_sources(): return {'fixture':'same'}\n",
            "consumer-cohort.py": "def retained(path): return {}\ndef audited(*args): return {}\n",
            "process-evidence.py": "def question_checkpoint(*args): return {}\n",
            "process-assess-evidence.py": "def retained(path): return {'proof':{}}\ndef retained_assessment(*args): return {'attemptIds':[]}\n",
            "defect-evidence.py": "def checkpoint(path): return {'manifest':{'proof':{}}}\n",
            "release-report.py": "import hashlib\ndef lineage_roots(path): return {path:hashlib.sha256((path/'result.json').read_bytes()).hexdigest()}\ndef experimental_usage(*args): return {}\ndef report(path): return {'status':'release-corpus-observed'}\n",
        }
        for name, value in fixtures.items():
            (dev / name).write_text(value)
        self.release = self.root / "release"
        self.release.mkdir()
        self.answer = self.root / "answer.json"
        self.answer.write_text('{}')
        self.environment = dict(os.environ, CQ_EVIDENCE_ROOT=str(self.root / "evidence"))

    def tearDown(self):
        self.temporary.cleanup()

    def run_suite(self, arguments, environment, succeeds):
        output = subprocess.run([sys.executable, str(self.root / "dev/release-evaluate"), "--release", str(self.release), *arguments],
                                env=environment, text=True, capture_output=True, timeout=30)
        self.assertEqual(output.returncode == 0, succeeds, output.stdout + output.stderr)
        manifests = list((self.root / "evidence").glob("*/suite.json"))
        self.assertEqual(len(manifests), 1)
        return manifests[0].parent, json.loads(manifests[0].read_text())

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
