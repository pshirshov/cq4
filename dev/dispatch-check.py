import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import uuid


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    endpoint = os.environ["CQ_ORIGIN"]
    fixture = Path("dev/dispatch-fixture.py").read_text()
    with tempfile.TemporaryDirectory(prefix="cq-dispatch-") as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Consumer base"], check=True)
        guardian = root / "cq-guardian"
        subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(guardian), "host/native/guardian.c"], check=True)
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + fixture)
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "stateRoot": str(root / "sessions"), "guardian": str(guardian),
            "evaluation": {"run": "deterministic-dispatch", "scenario": "worker-reviewer", "assessor": False},
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "90000", "heartbeatMillis": "1000",
                       "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144},
            "checks": [{"name": "consumer-content", "command": [sys.executable, "-c", "from pathlib import Path; assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'"],
                        "executionMillis": "5000", "outputBytes": 65536}],
        }))
        source = root / "request.txt"
        source.write_text("Run the worker/reviewer dispatch fixture")

        def run(arguments):
            result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
            if result.returncode:
                for path in (root / "sessions").rglob("receipt.json"):
                    print(path, path.read_text(), file=sys.stderr)
                for path in (root / "sessions").rglob("stderr"):
                    print(path, path.read_text(errors="replace")[-12000:], file=sys.stderr)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout

        run(["init", "--endpoint", endpoint])
        receipt = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        session = Path(receipt["directory"])
        children = sorted((session / "children").iterdir())
        assert len(children) == 3 and receipt["processSucceeded"] and receipt["usageDelivered"]
        statuses = [json.loads((child / "receipt.json").read_text()) for child in children]
        assert sorted(value["phase"] for value in statuses) == ["Cancelled", "Completed", "Completed"]
        native = (session / "payload" / receipt["attempt"]["value"] / "stdout").read_text()
        assert "CHILD_ONLY_NARRATIVE" not in native
        events = [json.loads(line) for line in native.splitlines()]
        traffic = [value for value in events if value.get("type") == "fixture.dispatch"]
        assert traffic and max(len(json.dumps(value["reply"]).encode()) for value in traffic) < 12288
        assert not (repository / "consumer.txt").exists(), "Worker mutated governing checkout"
        manifest = json.loads((session / "run.json").read_text())

        def api(selection):
            request = urllib.request.Request(endpoint + "/api/call", data=json.dumps({"Usage": {"input": {
                "project": manifest["project"]["project"], "selection": selection}}}).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                    "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        filter_value = {"SessionOnly": {"id": receipt["session"]}}
        before = api({"Summary": {"filter": filter_value}})
        attempts = api({"Attempts": {"filter": filter_value, "after": None, "snapshot": None, "limit": 20}})["UsageAttempts"]["page"]["entries"]
        assert len(attempts) == 4 and sum(value["attempt"]["parent"] == receipt["attempt"] for value in attempts) == 3
        assert all(value["assignment"]["evaluation"] == {"run": "deterministic-dispatch", "scenario": "worker-reviewer", "assessor": False} for value in attempts)
        assert api({"Summary": {"filter": {"EvaluationOnly": {"run": "deterministic-dispatch", "scenario": "worker-reviewer"}}}}) == before
        assert before["UsageSummary"]["report"]["attempts"]["running"] == "0"
        assert "Acknowledged 0" in run(["job", "upload", "--session", str(session)])
        (children[0] / "delivery/final/000000.ack").unlink()
        assert "Acknowledged 1" in run(["job", "upload", "--session", str(session)])
        assert api({"Summary": {"filter": filter_value}}) == before, "Child replay changed accounting or cursor"
        print(json.dumps({"session": receipt["session"], "children": statuses, "usage": before, "maxParentReplyBytes": max(len(json.dumps(value["reply"]).encode()) for value in traffic)}))
    print("Local dispatch: idempotent start, host candidate/checks, handle-only review, workspace permissions, cancellation, parent/child audit and child delivery replay passed")


if __name__ == "__main__":
    main()
