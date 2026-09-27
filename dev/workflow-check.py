import contextlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import uuid


def identity():
    return {"value": str(uuid.uuid4())}


def main():
    launcher = sys.argv[1:]
    assert launcher, "CQ launcher required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    endpoint = os.environ["CQ_ORIGIN"]
    retained = os.environ.get("CQ_WORKFLOW_EVIDENCE")
    if retained is not None:
        Path(retained).mkdir(parents=True)
    with (contextlib.nullcontext(retained) if retained else tempfile.TemporaryDirectory(prefix="cq-workflow-")) as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Workflow fixture"], check=True)
        guardian = root / "cq-guardian"
        subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(guardian), "host/native/guardian.c"], check=True)
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + Path("dev/dispatch-fixture.py").read_text())
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "integrationTarget": None, "stateRoot": str(root / "sessions"), "guardian": str(guardian), "evaluation": None,
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "90000", "heartbeatMillis": "1000",
                       "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144}, "checks": [],
        }))
        source = root / "request.txt"

        def run(arguments):
            result = subprocess.run(launcher + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
            if result.returncode:
                for path in (root / "sessions").rglob("stderr"):
                    print(path, path.read_text(errors="replace")[-12000:], file=sys.stderr)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout

        run(["init", "--endpoint", endpoint])
        project = json.loads((repository / ".git/cq/project.json").read_text())["project"]

        def api(command):
            request = urllib.request.Request(endpoint + "/api/call", data=json.dumps(command).encode(), headers={
                "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=10) as response:
                value = json.load(response)
            assert "Failed" not in value, value
            return value

        def change(mutations):
            return api({"Change": {"input": {"project": project, "change": {
                "request": identity(), "mutations": mutations, "fences": [], "reason": "Workflow boundary fixture"}}}})["Changed"]["ack"]["items"]

        def task():
            return change([{"Create": {"draft": {"title": "Workflow task", "body": "Selected work", "labels": [], "archived": False,
                "content": {"Task": {"status": "Ready", "acceptance": ["Verified"], "result": None, "validation": []}}, "citations": []}}}])[0]

        def denied(name, member, roots, phase, work):
            source.write_text("workflow-denial:" + json.dumps({"members": [member], "work": work, "previous": None,
                                                              "integrationDenied": phase != "integrate"}))
            receipt = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source),
                                      "--workflow", "advance", "--roots", roots, "--through", phase]))
            session = Path(receipt["directory"])
            assert receipt["problem"] is None and receipt["usageDelivered"]
            for path in ["children", "integration-requests", "integrations", "combinations"]:
                assert not list((session / path).glob("*")), (name, path)
            assert len(list((session / "journal").glob("*.json"))) == 1, "Workflow denial launched another job"
            print(json.dumps({"scenario": name, "receipt": receipt}))

        member = task()
        denied("phase", member, "T" + member["id"]["number"], "explore", {"Worker": {"mode": "Implement"}})
        selected, sibling = task(), task()
        milestone = change([{"Create": {"draft": {"title": "Context milestone", "body": "Organization", "labels": [], "archived": False,
            "content": {"Milestone": {"status": "Open", "objective": "Organize work"}}, "citations": []}}}])[0]
        for item in [selected, sibling]:
            changed = change([{"Reference": {"source": item["id"], "expectedSource": item["revision"], "relation": "PartOf",
                "target": milestone["id"], "expectedTarget": milestone["revision"], "present": True}}])
            milestone = next(value for value in changed if value["id"] == milestone["id"])
        sibling = api({"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": sibling["id"]}}}}})["Detail"]["view"]["item"]
        denied("contextual-milestone-sibling", {"id": sibling["id"], "revision": sibling["revision"]},
               "T" + selected["id"]["number"], "plan", {"Planner": {}})


if __name__ == "__main__":
    main()
