"""Behavioral Active Blackbox Good Communication: connected narrative growth."""
import hashlib
import json
from fixture_runtime import guardian_binary
import os
from pathlib import Path
import subprocess
import sys
import urllib.request
import uuid


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    root = Path(os.environ["CQ_GROWTH_EVIDENCE"])
    root.mkdir(parents=True)
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    repository = root / "consumer"
    subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
    subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                    "commit", "--quiet", "--allow-empty", "-m", "Traffic fixture"], check=True)
    base = subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()
    guardian = guardian_binary(root, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
    executable = root / "fixture-harness"
    executable.write_text(f"#!{sys.executable}\n" + Path("dev/dispatch-fixture.py").read_text())
    executable.chmod(0o700)
    settings = root / "settings.json"
    settings.write_text(json.dumps({"integrationTarget": None, "stateRoot": str(root / "sessions"), "guardian": str(guardian),
        "evaluation": {"run": "deterministic-growth", "scenario": "fixed-member-pair", "assessor": False},
        "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
            "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
        "limits": {"startupMillis": "5000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144}, "checks": []}))

    def run(arguments, name):
        result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
        (root / (name + ".stdout")).write_text(result.stdout)
        (root / (name + ".stderr")).write_text(result.stderr)
        assert result.returncode == 0, result.stdout + result.stderr
        return result.stdout

    endpoint = os.environ["CQ_ORIGIN"]
    run(["init", "--endpoint", endpoint], "init")
    source = root / "request.txt"
    source.write_text("traffic-growth-bootstrap")
    first = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)], "bootstrap"))
    assert first["processSucceeded"] and first["usageDelivered"]
    project = json.loads((repository / ".git/cq/project.json").read_text())["project"]

    def api(path, body):
        request = urllib.request.Request(endpoint + path, data=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()), "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=15) as response:
            value = json.load(response)
        assert "Failed" not in value, value
        return value

    def read(selection):
        return api("/api/call", {"Read": {"input": {"project": project, "selection": selection}}})

    native = Path(first["directory"]) / "payload" / first["attempt"]["value"] / "stdout"
    members = next(value["members"] for value in map(json.loads, native.read_text().splitlines()) if value.get("type") == "fixture.growth-seed")
    item, = members
    before = read({"History": {"id": item["id"], "before": {"value": "2"}, "limit": 100}})
    publications = []
    for size in [32, 60000]:
        body = "PRIVATE_GROWTH_INPUT:".ljust(size, "x")
        metadata = api("/api/artifact", {"project": project, "id": {"value": str(uuid.uuid4())}, "attempt": first["attempt"],
            "kind": "Input", "mediaType": "text/plain", "body": body})
        assert metadata["bytes"] == size and metadata["sha256"] == hashlib.sha256(body.encode()).hexdigest()
        publications.append(metadata)
    source.write_text("traffic-growth:" + json.dumps({"members": members, "artifacts": [value["id"] for value in publications]}))
    receipt = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)], "growth"))
    assert receipt["processSucceeded"] and receipt["usageDelivered"]
    session = Path(receipt["directory"])
    native = session / "payload" / receipt["attempt"]["value"] / "stdout"
    text = native.read_text()
    assert "PRIVATE_GROWTH_INPUT:" not in text and "PRIVATE_GROWTH_RESULT:" not in text, "Governor received private narratives"
    events = [json.loads(line) for line in text.splitlines()]
    rounds = next(event["rounds"] for event in events if event.get("type") == "fixture.growth")
    assert [value["input"] for value in rounds] == [value["id"] for value in publications]
    traffic = [event for event in events if event.get("type") == "fixture.dispatch"]
    size = lambda value: len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode())
    measured = []
    for value in rounds:
        observed = {"input": value["input"]}
        for role in ["explorer", "reviewer"]:
            status = value[role]
            child = session / "children" / status["attempt"]["value"]
            ticket = json.loads((child / "ticket.json").read_text())
            assert ticket["request"]["members"] == members and ticket["request"]["artifacts"] == [value["input"]]
            assert ticket["request"]["previous"] == (None if role == "explorer" else value["explorer"]["result"])
            publication = json.loads((child / "publication.json").read_text())
            dispatched = next(event for event in traffic if event["request"].get("Start", {}).get("request", {}).get("request") == ticket["request"]["request"])
            assert any(event["reply"] == {"Status": {"value": status}} for event in traffic)
            job = json.loads((session / "journal" / (status["attempt"]["value"] + ".json")).read_text())
            payload = session / "payload" / status["attempt"]["value"] / "input"
            observed[role] = {"requestBytes": size(dispatched["request"]), "launchReplyBytes": size(dispatched["reply"]),
                "terminalReplyBytes": size({"Status": {"value": status}}), "childInputBytes": payload.stat().st_size,
                "resultBytes": size(publication["result"]), "attempt": status["attempt"], "request": ticket["request"],
                "pollingCalls": sum(event["request"].get("Status", {}).get("attempt") == status["attempt"] for event in traffic)}
            assert job["phase"] == "Settled" and job["workspace"]["base"]["value"] == base and publication["result"]["candidate"] is None
        measured.append(observed)
    small, large = measured
    for role in ["explorer", "reviewer"]:
        assert all(small[role][field] == large[role][field] for field in ["requestBytes", "terminalReplyBytes"]), "Narrative growth changed parent dispatch size"
        assert large[role]["childInputBytes"] > small[role]["childInputBytes"] + 50000
    assert large["explorer"]["resultBytes"] > small["explorer"]["resultBytes"] + 30000
    assert all(size(event["request"]) < 16 * 1024 and size(event["reply"]) < 12 * 1024 for event in traffic)
    assert read({"History": {"id": item["id"], "before": {"value": "2"}, "limit": 100}}) == before
    assert read({"ItemDetail": {"id": item["id"]}})["Detail"]["view"]["item"]["revision"] == item["revision"]
    claims = read({"Claims": {"members": [item["id"]]}})["Claims"]["preview"]
    assert not claims["claims"] and not claims["integrations"]
    assert subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip() == base
    assert not subprocess.check_output(["git", "-C", str(repository), "status", "--porcelain"]).strip()
    usage = api("/api/call", {"Usage": {"input": {"project": project, "selection": {"Summary": {"filter": {"SessionOnly": {"id": receipt["session"]}}}}}}})
    assert usage["UsageSummary"]["report"]["attempts"]["running"] == "0"
    report = {"scope": "Connected actual supervisor/MCP/PostgreSQL with deterministic harness; synthetic token observations, no efficiency conclusion",
        "encoding": "Compact UTF-8 JSON structured dispatch arguments/replies, excluding MCP envelopes, duplicated text and tokenization; polling counts may vary",
        "session": str(session), "members": members, "publications": publications, "rounds": measured, "traffic": traffic,
        "nativeSha256": hashlib.sha256(native.read_bytes()).hexdigest(), "usage": usage}
    (root / "growth.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"rounds": measured, "dispatchCalls": len(traffic), "claimsReleased": True}))


if __name__ == "__main__":
    main()
