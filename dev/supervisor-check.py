import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import uuid


def main():
    launcher = sys.argv[1:]
    assert launcher, "CQ launcher command required"
    endpoint = os.environ["CQ_ORIGIN"]
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    with tempfile.TemporaryDirectory(prefix="cq-supervisor-role-") as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Consumer fixture base"], check=True)
        guardian = root / "cq-guardian"
        subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(guardian), "host/native/guardian.c"], check=True)
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + '''import json, os, pathlib, signal, sys, time
if sys.argv[1:] == ["--version"]:
    print("codex-cli 0.156.1")
    raise SystemExit(0)
assert "CQ_TOKEN" not in os.environ and "CQ_DATABASE_URL" not in os.environ
assert sys.argv[sys.argv.index("--sandbox") + 1] == "read-only"
assert sys.argv[sys.argv.index("--model") + 1] == "fixture-model"
prompt = json.loads(sys.stdin.read())["request"]
if prompt == "deadline input":
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
target = pathlib.Path(sys.argv[sys.argv.index("--output-last-message") + 1])
target.write_text(json.dumps({"summary": "Fixture governing result"} if prompt != "invalid result" else {"unexpected": True}))
for event in [
    {"type": "thread.started", "thread_id": "fixture-thread"},
    {"type": "turn.started"},
    {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 20,
        "cache_write_input_tokens": 0, "output_tokens": 31, "reasoning_output_tokens": 3}}
]:
    print(json.dumps(event), flush=True)
if prompt == "deadline input":
    time.sleep(30)
if prompt == "uncertain input":
    os.kill(os.getppid(), signal.SIGKILL)
    time.sleep(30)
''')
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "stateRoot": str(root / "sessions"), "guardian": str(guardian), "checks": [], "evaluation": None,
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "3000", "executionMillis": "15000", "heartbeatMillis": "1000",
                       "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144},
        }))
        input_file = root / "input.txt"

        def run(arguments, expected):
            result = subprocess.run(launcher + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=30)
            assert result.returncode == expected, result.stdout + result.stderr
            return result

        run(["init", "--endpoint", endpoint], 0)
        input_file.write_text("valid input")
        result = run(["run", "codex", "--settings", str(settings), "--input", str(input_file)], 0)
        receipt = json.loads(result.stdout)
        assert receipt["phase"] == "Settled" and receipt["processSucceeded"] and receipt["usageDelivered"]
        assert receipt["result"] is not None and receipt["problem"] is None
        session = Path(receipt["directory"])
        manifest = json.loads((session / "run.json").read_text())
        assert manifest["attempt"]["role"] == "Governor"
        assert not any(value.startswith("CQ_DATABASE") for value in environment)

        def api(command):
            request = urllib.request.Request(endpoint + "/api/call", data=json.dumps(command).encode(), headers={
                "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json",
            })
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        selection = {"Summary": {"filter": {"SessionOnly": {"id": receipt["session"]}}}}
        usage = api({"Usage": {"input": {"project": manifest["project"]["project"], "selection": selection}}})
        report = usage["UsageSummary"]["report"]
        assert report["unattributed"]["total"]["known"] == "131" and report["attempts"]["running"] == "0", report
        result_page = api({"Read": {"input": {"project": manifest["project"]["project"], "selection": {
            "ArtifactText": {"id": receipt["result"], "offset": 0, "limit": 8192}}}}})
        assert json.loads(result_page["ArtifactText"]["page"]["text"]) == {"summary": "Fixture governing result"}
        assert "Acknowledged 0" in run(["job", "upload", "--session", str(session)], 0).stdout
        (session / "delivery/000001.ack").unlink()
        assert "Acknowledged 1" in run(["job", "upload", "--session", str(session)], 0).stdout
        replayed = api({"Usage": {"input": {"project": manifest["project"]["project"], "selection": selection}}})
        assert replayed == usage, "Replaying acknowledged native usage changed totals or audit cursor"

        input_file.write_text("invalid result")
        invalid = run([":supervisor", "--", "codex", "--settings", str(settings), "--input", str(input_file)], 1)
        rejected = json.loads(invalid.stdout)
        assert rejected["processSucceeded"] and rejected["result"] is None and rejected["usageDelivered"] and rejected["problem"]
        stopped_settings = json.loads(settings.read_text())
        stopped_settings["limits"]["executionMillis"] = "1500"
        settings.write_text(json.dumps(stopped_settings))
        failures = []
        for prompt, phase, unknown, reason in [
            ("deadline input", "Settled", "0", "ExecutionDeadline"),
            ("uncertain input", "Uncertain", "1", "Uncertain"),
        ]:
            input_file.write_text(prompt)
            stopped = subprocess.run(launcher + ["run", "codex", "--settings", str(settings), "--input", str(input_file)],
                                     cwd=repository, env=environment, capture_output=True, text=True, timeout=30)
            stop_receipt = json.loads(stopped.stdout)
            stop_manifest = json.loads((Path(stop_receipt["directory"]) / "run.json").read_text())
            stop_usage = api({"Usage": {"input": {"project": stop_manifest["project"]["project"], "selection": {
                "Summary": {"filter": {"SessionOnly": {"id": stop_receipt["session"]}}}}}}})["UsageSummary"]["report"]
            print(json.dumps({"case": prompt, "exit": stopped.returncode, "receipt": stop_receipt, "usage": stop_usage}), flush=True)
            if not (stopped.returncode == 1 and stop_receipt["phase"] == phase and not stop_receipt["processSucceeded"]
                    and stop_receipt["result"] is None and stop_receipt["usageDelivered"]
                    and reason in (stop_receipt["problem"] or "") and stop_usage["attempts"]["unknown"] == unknown):
                failures.append(prompt)
        assert not failures, f"Incorrect native stop classification: {failures}"
        excessive = json.loads(settings.read_text())
        excessive["limits"]["startupMillis"] = "86400000"
        settings.write_text(json.dumps(excessive))
        input_file.write_text("valid input")
        denied = run(["run", "codex", "--settings", str(settings), "--input", str(input_file)], 1)
        assert "credential lifetime" in denied.stderr, denied.stderr
    print("Supervisor role: shorthand/native launch, database graph isolation, scoped environment, native result validation, artifact publication and idempotent audit delivery pass with a deterministic harness fixture")


if __name__ == "__main__":
    main()
