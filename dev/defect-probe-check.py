import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import runpy
import shlex
import shutil
import subprocess
import tempfile
import unittest


predicates = runpy.run_path(str(Path(__file__).with_name("defect-probe-evidence.py")))


class NativeObservationCheck(unittest.TestCase):
    """Behavioral Active Blackbox Atomic: native execution and published byte bindings."""

    def observation(self):
        program = {"path": "/evidence/probe-recorder.py", "sha256": "a" * 64}
        fixture = {"head": "fixture", "files": {"source.py": "hash"}}
        output = {"cwd": "/workspace", "before": fixture, "after": copy.deepcopy(fixture),
                  "gitDiagnostics": [{"argv": argv, "exit": 0, "stdout": "captured", "stderr": ""} for argv in [["git", "rev-parse", "HEAD"], ["git", "ls-files", "-z"]] * 2],
                  "observations": [{"name": name, "argv": ["python3", "-c", "probe"], "stdin": "", "exit": 0, "stdout": "observed", "stderr": ""}
                    for name in ["reproduction", "selection", "normalization-aggregation", "nonconforming-injection", "cli"]]}
        event = {"type": "item.completed", "item": {"id": "execution", "type": "command_execution", "status": "completed", "exit_code": 0,
            "command": "/bin/zsh -lc " + shlex.quote(shlex.join(predicates["command"](program))), "aggregated_output": json.dumps(output)}}
        return program, fixture, output, event

    def test_exact_executed_program_with_fixture_and_cwd(self):
        program, fixture, output, event = self.observation()
        proof = predicates["observations"]([event], program, fixture, Path("/workspace"))
        self.assertEqual(proof["observations"], output)
        for altered in [{**program, "path": "/other/program.py"}, {**program, "sha256": "b" * 64}]:
            with self.assertRaisesRegex(AssertionError, "exact native"):
                predicates["observations"]([event], altered, fixture, Path("/workspace"))
        event["item"]["command"] += " && echo altered-bootstrap"
        with self.assertRaisesRegex(AssertionError, "exact native"):
            predicates["observations"]([event], program, fixture, Path("/workspace"))

    def test_model_text_changed_program_failed_or_repeated_execution_cannot_establish_observations(self):
        program, fixture, _, event = self.observation()
        variants = []
        for field, value in [("type", "agent_message"), ("command", "echo fabricated"), ("exit_code", 1), ("status", "failed")]:
            altered = copy.deepcopy(event)
            altered["item"][field] = value
            variants.append([altered])
        variants.append([event, event])
        for events in variants:
            with self.assertRaises(AssertionError):
                predicates["observations"](events, program, fixture, Path("/workspace"))

    def test_other_cwd_changed_fixture_and_missing_streams_are_rejected(self):
        program, fixture, output, event = self.observation()
        for change in [lambda v: v.update(cwd="/other"), lambda v: v["after"]["files"].update({"source.py": "changed"}),
                       lambda v: v["observations"].pop(), lambda v: v["observations"][0].update(stderr=None)]:
            altered = copy.deepcopy(output)
            change(altered)
            event["item"]["aggregated_output"] = json.dumps(altered)
            with self.assertRaises(AssertionError):
                predicates["observations"]([event], program, fixture, Path("/workspace"))

    def artifacts(self, raw):
        attempt, session = {"value": "attempt"}, "session"
        values = {}
        def record(handle, media, body):
            values[handle["value"]] = {"metadata": {"id": handle, "attempt": attempt, "kind": "Transcript", "mediaType": media,
                "bytes": len(body.encode()), "codePoints": len(body), "sha256": hashlib.sha256(body.encode()).hexdigest(),
                "actor": {"role": "Collector", "session": {"value": session}}}, "body": body}
        for name, content in [("stdout", raw), ("stderr", b"")]:
            parts = []
            for index, offset in enumerate(range(0, len(content), 128 * 1024)):
                handle = predicates["native_id"](attempt, f"{name}-part-{index}")
                record(handle, "text/plain", base64.b64encode(content[offset:offset + 128 * 1024]).decode())
                parts.append(handle)
            manifest = {"encoding": "base64", "mediaType": "application/x-ndjson" if name == "stdout" else "application/octet-stream",
                        "bytes": str(len(content)), "sha256": hashlib.sha256(content).hexdigest(), "parts": parts}
            record(predicates["native_id"](attempt, name), "application/json", json.dumps(manifest))
        return values, attempt, session

    def test_paged_export_through_read_boundary_and_collector_manifest_decode(self):
        raw = ("native café\n" * 15000).encode()
        artifacts, attempt, session = self.artifacts(raw)
        def call(command):
            request = command["Read"]["input"]["selection"]
            name, args = next(iter(request.items()))
            value = artifacts[args["id"]["value"]]
            if name == "ArtifactInfo":
                return {"ArtifactInfo": {"metadata": value["metadata"]}}
            assert name == "ArtifactText"
            end = min(args["offset"] + args["limit"], len(value["body"]))
            return {"ArtifactText": {"page": {"metadata": value["metadata"], "offset": args["offset"], "next": end,
                "hasMore": end < len(value["body"]), "text": value["body"][args["offset"]:end]}}}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / session
            payload = root / "payload" / attempt["value"]
            payload.mkdir(parents=True)
            (payload / "stdout").write_bytes(raw)
            (payload / "stderr").write_bytes(b"")
            exported = predicates["export"](call, {"value": "project"}, root, attempt)
            self.assertEqual(exported, artifacts)
            self.assertEqual(predicates["decoded"](exported, attempt, "stdout", session), raw)

    def test_wrong_publisher_session_or_changed_part_is_rejected(self):
        artifacts, attempt, session = self.artifacts(b"actual native bytes")
        part = predicates["native_id"](attempt, "stdout-part-0")["value"]
        for change in [lambda v: v[part]["metadata"]["actor"].update(role="Human"),
                       lambda v: v[part]["metadata"]["actor"].update(session={"value": "other"}),
                       lambda v: v[part].update(body=base64.b64encode(b"model statement").decode())]:
            altered = copy.deepcopy(artifacts)
            change(altered)
            with self.assertRaises(AssertionError):
                predicates["decoded"](altered, attempt, "stdout", session)

    def test_actual_recorder_captures_separate_streams_without_adjudication(self):
        """Behavioral Active Blackbox Good Communication: actual Python/Git/filesystem recorder."""
        root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary) / "consumer"
            shutil.copytree(root / "fixtures/defect", workspace, ignore=shutil.ignore_patterns("__pycache__"))
            for args in [["init", "--quiet"], ["add", "."], ["-c", "user.name=CQ test", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "-m", "fixture"]]:
                subprocess.run(["git", "-C", str(workspace), *args], check=True, capture_output=True)
            program = (root / "defect-probe-capture.py").read_text()
            recorder = {"path": str(root / "defect-probe-capture.py"), "sha256": hashlib.sha256(program.encode()).hexdigest()}
            result = subprocess.run(predicates["command"](recorder), cwd=workspace, capture_output=True, text=True, timeout=60)
            self.assertEqual((result.returncode, result.stderr), (0, ""))
            output = json.loads(result.stdout)
            self.assertEqual(output, json.loads((workspace / "cq-probe-observations.json").read_text()))
            self.assertEqual(output["before"], output["after"])
            by_name = {v["name"]: v for v in output["observations"]}
            self.assertEqual(by_name["reproduction"]["exit"], 1)
            self.assertEqual(by_name["reproduction"]["stdout"], "")
            self.assertIn("FAILED (failures=1)", by_name["reproduction"]["stderr"])
            self.assertEqual(by_name["selection"]["stdout"], "['a_b', '12', 'café']\n")
            self.assertEqual(by_name["normalization-aggregation"]["stdout"], "[('a', 1), ('z', 2)]\n")
            self.assertEqual(by_name["nonconforming-injection"]["stdout"], "[('12', 1), ('a_b', 1), ('café', 1)]\n")
            self.assertEqual(by_name["cli"]["stdout"], "12\t1\na_b\t1\ncafé\t1\n")
            self.assertFalse(any("verdict" in v for v in output["observations"]))

    def test_git_diagnostics_are_retained_and_failure_is_fatal(self):
        """Behavioral Active Blackbox Good Communication: subprocess diagnostics and exit boundary."""
        root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary) / "consumer"
            shutil.copytree(root / "fixtures/defect", workspace, ignore=shutil.ignore_patterns("__pycache__"))
            for args in [["init", "--quiet"], ["add", "."], ["-c", "user.name=CQ test", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "-m", "fixture"]]:
                subprocess.run(["git", "-C", str(workspace), *args], check=True, capture_output=True)
            shim = Path(temporary) / "bin"
            shim.mkdir()
            git = shim / "git"
            git.write_text("#!/usr/bin/env python3\nimport os,sys\nprint('fixture diagnostic',file=sys.stderr,flush=True)\nos.execv(" + repr(shutil.which("git")) + ", ['git', *sys.argv[1:]])\n")
            git.chmod(0o755)
            environment = {**os.environ, "PATH": str(shim) + os.pathsep + os.environ["PATH"]}
            program = (root / "defect-probe-capture.py").read_text()
            result = subprocess.run(["python3", "-c", program], cwd=workspace, env=environment, capture_output=True, text=True, timeout=60)
            self.assertEqual((result.returncode, result.stderr), (0, ""))
            output = json.loads(result.stdout)
            self.assertEqual(len(output["gitDiagnostics"]), 4)
            self.assertTrue(all(v["stderr"] == "fixture diagnostic\n" and v["exit"] == 0 and v["argv"][0] == "git" and v["stdout"] for v in output["gitDiagnostics"]))
            git.write_text("#!/usr/bin/env python3\nimport sys\nprint('git failure',file=sys.stderr)\nsys.exit(23)\n")
            result = subprocess.run(["python3", "-c", program], cwd=workspace, env=environment, capture_output=True, text=True, timeout=60)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertIn("git failure", result.stderr)

    def test_bootstrap_rejects_unavailable_or_altered_bytes_before_execution(self):
        """Behavioral Active Blackbox Good Communication: execution-time program integrity."""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "recorder.py"
            path.write_text("print('original')\n")
            recorder = {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            command = predicates["command"](recorder)
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual((result.returncode, result.stdout, result.stderr), (0, "original\n", ""))
            path.write_text("print('altered')\n")
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertIn("AssertionError", result.stderr)
            path.unlink()
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")
            self.assertIn("FileNotFoundError", result.stderr)


if __name__ == "__main__":
    unittest.main()
