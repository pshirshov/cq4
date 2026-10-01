"""Behavioral Active Blackbox Good Communication: operator/automation CLI contract."""
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request
import uuid


def main():
    command = sys.argv[1:]
    evidence = Path(os.environ["CQ_CLI_EVIDENCE"])
    evidence.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="cq-output-") as temporary:
        root = Path(temporary)

        def run(name, args, expected, diagnostic):
            result = subprocess.run(command + args, cwd=root, env=os.environ, capture_output=True, text=True, timeout=30)
            (evidence / (name + ".stdout")).write_text(result.stdout)
            (evidence / (name + ".stderr")).write_text(result.stderr)
            assert result.returncode == expected, result.stdout + result.stderr
            if expected == 0:
                assert ("phase=early" in result.stderr) == diagnostic, result.stderr
                assert "No `logger` section" not in result.stderr
            results.append({"case": name, "exit": result.returncode})
            return result

        overview = run("help", ["--help"], 0, False).stdout
        assert "Usage:" in overview and "Operator commands" in overview and "automation entrypoints" in overview and "--json" in overview
        for topic in ["init", "query", "status", "proposal", "web", "configure", "commands", "serve", "run", "host", "hook", "job"]:
            result = run("help-" + topic, [topic, "--help"], 0, False)
            assert result.stdout.startswith("Usage: cq " + topic), result.stdout
        assert not list(root.iterdir()), "Help must not create project or session state"
        run("diagnostics", ["--diagnostics", "--help"], 0, True)
        run("unknown-command", ["does-not-exist"], 1, False)
        initialized = run("human-init", ["init", "--name", "Readable project"], 0, False).stdout
        assert initialized.startswith("Project: Readable project (") and "Configuration:" in initialized
        encoded = json.loads(run("json-init", ["init", "--json"], 0, False).stdout)
        project = encoded["Initialized"]["project"]["id"]
        assert "Readable project" in initialized
        headers = {"Authorization": "Bearer " + os.environ["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                   "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"}

        def call(path, value):
            request = urllib.request.Request(os.environ["CQ_ORIGIN"] + path, data=json.dumps(value).encode(), headers=headers)
            with urllib.request.urlopen(request, timeout=15) as response:
                assert response.status == 200
                result = json.load(response)
                assert "Failed" not in result, result
                return result

        draft = {"title": "Readable task", "body": "", "labels": [], "archived": False, "citations": [],
                 "content": {"Task": {"status": "Ready", "acceptance": ["Readable output"], "result": None, "validation": []}}}
        call("/api/call", {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())}, "fences": [],
             "reason": "CLI output fixture", "mutations": [{"Create": {"draft": draft}}, {"Create": {"draft": {**draft, "title": "Next task"}}}]}}}})
        page = run("human-query", ["query", "--limit", "1"], 0, False).stdout
        assert "ID" in page and "Readable task" in page and "--after 'T1' --snapshot" in page and "\"Found\"" not in page
        found = json.loads(run("json-query", ["query", "--json", "--limit", "1"], 0, False).stdout)["Found"]["page"]
        assert len(found["items"]) == 1 and found["hasMore"]
        continuation = run("human-query-next", ["query", "--after", "T1", "--snapshot", found["cursor"]["value"]], 0, False).stdout
        assert "Next task" in continuation and "Readable task" not in continuation
        assert "Kind" in run("human-completion", ["query", "--query", "ledger:t", "--complete", "8"], 0, False).stdout
        assert "Ready" in run("human-workset", ["query", "--roots", "T1"], 0, False).stdout
        summary = run("human-status", ["status"], 0, False).stdout
        assert summary.startswith("Usage — tokens") and "Unknown costs" in summary and "Shared" in summary and "\"UsageSummary\"" not in summary
        assert json.loads(run("json-status", ["status", "--json"], 0, False).stdout)["UsageSummary"]["report"]["direct"]["total"]["known"] == "0"
        for mode in ["phases", "audit", "costs", "attempts"]:
            assert "No entries." in run("human-status-" + mode, ["status", mode], 0, False).stdout
            json.loads(run("json-status-" + mode, ["status", mode, "--json"], 0, False).stdout)
        identity = lambda: {"value": str(uuid.uuid4())}
        counter = lambda value: {"value": str(value), "measurement": "Observed"}
        counters = lambda value: {"input": counter(value), "output": counter(0), "cacheRead": counter(0), "cacheWrite": counter(0), "reasoning": counter(0)}
        unknown_cost = {"amount": None, "currency": None, "basis": "Unknown", "pricingVersion": None}
        assignment = {"id": identity(), "project": project, "members": [{"project": project, "ledger": "Tasks", "number": "1"}],
                      "attribution": "Direct", "cohort": None, "evaluation": None}
        attempt = {"id": identity(), "assignment": assignment["id"], "parent": None, "session": identity(), "role": "Worker",
                   "harness": "Codex", "provider": "fixture", "model": "controlled-model", "collector": "cli-output", "startedAt": "1000", "phase": "Work"}
        meter = {"key": "cli", "attempt": attempt["id"], "scope": "Increment", "baseline": counters(0), "baselineCost": unknown_cost}
        observation = {"id": identity(), "attempt": attempt["id"], "source": "cli-fixture", "position": "1", "occurredAt": "2000", "receivedAt": "0", "scope": "Increment",
                       "counters": {**counters(101), "output": counter(7), "cacheRead": counter(20), "cacheWrite": {"value": None, "measurement": "Unsupported"}, "reasoning": counter(2)},
                       "inputIncludesCache": True, "outputIncludesReasoning": True,
                       "cost": {"amount": {"value": "0.125"}, "currency": "USD", "basis": "ProviderEstimate", "pricingVersion": None},
                       "completeness": "Partial", "gaps": ["Deliberate fixture gap"], "evidence": None, "supersedes": None}
        outcome = {"request": identity(), "attempt": attempt["id"], "state": "Completed", "finishedAt": "3000", "gaps": ["Auxiliary calls unobserved"], "supersedes": None}
        for operation in [{"Assign": {"value": assignment}}, {"Start": {"value": attempt}}, {"Meter": {"value": meter}},
                          {"Ingest": {"value": {"observation": observation, "meter": "cli", "disposition": "Contribution", "detailReason": None}}}, {"Finish": {"value": outcome}}]:
            call("/api/usage", {"project": project, "operation": operation})
        summary = run("human-status-measured", ["status", "--task", "T1"], 0, False).stdout
        assert all(value in summary for value in ["101", "108", "unknown", "0.125", "ProviderEstimate"]), summary
        phases = run("human-phases-measured", ["status", "phases", "--session", attempt["session"]["value"]], 0, False).stdout
        assert [line.split()[:5] for line in phases.splitlines() if line.startswith("Work ")][0] == ["Work", "1", "0", "0", "0:00:02"], phases
        measured = json.loads(run("json-phases-measured", ["status", "phases", "--task", "T1", "--json"], 0, False).stdout)["UsagePhases"]["report"]["phases"]
        assert [(entry["phase"], entry["attempts"], entry["wallMillis"], entry["totals"]["total"]["known"]) for entry in measured] == [("Work", "1", "2000", "108")], measured
        audit = run("human-audit-measured", ["status", "audit"], 0, False).stdout
        assert all(value in audit for value in ["cli-fixture", "101", "Partial", "Deliberate fixture gap"]), audit
        attempts = run("human-attempts-measured", ["status", "attempts"], 0, False).stdout
        assert all(value in attempts for value in ["controlled-model", "Completed", "T1", "Auxiliary calls unobserved"]), attempts
        outcomes = run("human-outcomes", ["status", "outcomes", "--attempt", attempt["id"]["value"]], 0, False).stdout
        assert "Completed" in outcomes and "Auxiliary calls unobserved" in outcomes
        assert len(json.loads(run("json-outcomes", ["status", "outcomes", "--attempt", attempt["id"]["value"], "--json"], 0, False).stdout)["UsageOutcomes"]["page"]["entries"]) == 1
        for position in range(2, 203):
            priced = {**observation, "id": identity(), "position": str(position), "cost": {**observation["cost"], "pricingVersion": f"fixture-{position:03d}"}}
            call("/api/usage", {"project": project, "operation": {"Ingest": {"value": {"observation": priced, "meter": "cli", "disposition": "Contribution", "detailReason": None}}}})
        cost_summary = run("human-cost-continuation", ["status", "--task", "T1"], 0, False).stdout
        continuation = next(line for line in cost_summary.splitlines() if line.startswith("More available."))
        assert "cq status costs" in continuation and "--task" in continuation, continuation
        copied = shlex.split(continuation.split("cq ", 1)[1])
        assert copied[copied.index("--task") + 1] == "T1"
        following = run("human-cost-continued", copied, 0, False).stdout
        assert "ProviderEstimate" in following and "No entries." not in following
        assert run("human-web", ["web"], 0, False).stdout.strip() == os.environ["CQ_ORIGIN"]
        assert json.loads(run("json-web", ["web", "--json"], 0, False).stdout)["endpoint"] == os.environ["CQ_ORIGIN"]
        malformed = run("invalid-query", ["query", "--query", "alpha AND", "--json"], 1, False)
        assert malformed.stdout == "" and "QuerySyntax" in malformed.stderr
        repeated = run("repeated-json", ["status", "--json", "--json"], 1, False)
        assert repeated.stdout == "" and "Repeated --json" in repeated.stderr
        literal = run("literal-json", ["query", "--query", "--json", "--json"], 0, False)
        assert json.loads(literal.stdout)["Found"]["page"]["items"] == []
    (evidence / "cli-output-results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("CLI help, command help, logging, human/JSON output, continuation, literal option values and failure streams passed")


if __name__ == "__main__":
    main()
