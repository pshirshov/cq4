#!/usr/bin/env python3
import http.server
import json
from fixture_runtime import guardian_binary
import os
from pathlib import Path
import subprocess
import sys
import threading
from types import SimpleNamespace
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parent.parent


def reproduce(checks, command, scenario):
    evidence = checks.evidence
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = checks.environment["CQ_TOKEN"]
    repository = evidence / "consumer"
    repository.mkdir()
    subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
    subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                    "commit", "--quiet", "--allow-empty", "-m", "Admission reproduction"], check=True)
    guardian = guardian_binary(evidence, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
    fixture = (ROOT / "dev/dispatch-fixture.py").read_text()
    boundary = '    worker = poll(first["attempt"])\n'
    assert fixture.count(boundary) == 1
    if scenario == "running-loss":
        fixture = fixture.replace('    exiting = data["request"] == "exit-with-running-child"',
            '    request["work"] = {"Worker": {"mode": "Probe"}}\n    exiting = False')
        fixture = fixture.replace('changed = {**request, "work": {"Worker": {"mode": "Probe"}}}',
            'changed = {**request, "work": {"Worker": {"mode": "Implement"}}}')
        fixture = fixture.replace(boundary, '''    for _ in range(100):
        running = tool("cq_host", "dispatch", {"Status": {"attempt": first["attempt"], "waitMillis": 0}})["Status"]["value"]
        if running["process"] == "Running":
            break
        time.sleep(0.05)
    else:
        raise AssertionError("Claim-loss child did not start")
    released = tool("cq", "claim", {"project": project, "action": {"Release": {"fence": request["fence"]}}})
    assert released["Claimed"]["claim"]["released"]
    started = time.monotonic()
    worker = poll(first["attempt"])
    elapsed = time.monotonic() - started
    assert worker["phase"] == "Cancelled" and worker["process"] == "Settled", worker
    assert worker["result"] is None and worker["usageDelivered"], worker
    assert elapsed <= 40, elapsed
    emit({"type": "fixture.admission", "status": worker, "release": released, "settlementSeconds": elapsed, "boundSeconds": 40})
    finish({"summary": "Claim loss cancelled the running child within the measured bound"})
    return
''')
    else:
        fixture = fixture.replace(boundary, boundary + '''    emit({"type": "fixture.admission", "status": worker})
    finish({"summary": "Retained child admission observation"})
    return
''')
    executable = evidence / "fixture-harness"
    executable.write_text(f"#!{sys.executable}\n" + fixture)
    executable.chmod(0o700)
    settings = evidence / "settings.json"
    settings.write_text(json.dumps({
        "integrationTarget": None, "stateRoot": str(evidence / "sessions"), "guardian": str(guardian),
        "evaluation": {"run": "claim-admission-reproduction", "scenario": scenario, "assessor": False},
        "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                       "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
        "limits": {"startupMillis": "5000", "executionMillis": "60000", "heartbeatMillis": "1000",
                   "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144},
        "checks": [{"name": "consumer-content", "command": [sys.executable, "-c",
            "from pathlib import Path; assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'"],
            "executionMillis": "5000", "outputBytes": 65536}],
    }))
    request_file = evidence / "request.txt"
    request_file.write_text("Observe claim loss at result publication")
    backend = checks.environment["CQ_ORIGIN"]
    renewal = None
    observation = None
    decision = None
    lose_acknowledgement = scenario in {"accepted-ack", "rejected-ack"}

    def forward(path, payload, headers, method):
        forwarded = {name: value for name, value in headers.items()
                     if name.lower() not in {"host", "content-length", "connection", "upgrade", "http2-settings"}}
        request = urllib.request.Request(backend + path, data=payload, headers=forwarded, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=10)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            return response.status, response.headers.get("Content-Type", "application/json"), response.read()

    class Proxy(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            self.transfer(None)

        def do_POST(self):
            self.transfer(self.rfile.read(int(self.headers["Content-Length"])))

        def transfer(self, payload):
            nonlocal renewal, observation, decision
            value = json.loads(payload) if payload is not None else {}
            if self.path == "/api/call" and "ClaimWork" in value and "Renew" in value["ClaimWork"]["input"]["action"]:
                renewal = (value["ClaimWork"]["input"], dict(self.headers))
            if self.path == "/api/artifact" and value["kind"] == "Result" and observation is None and scenario in {"release-before", "rejected-ack"}:
                body = json.loads(value["body"])
                if "request" in body and "fence" in body["request"]:
                    assert renewal is not None
                    latest, credentials = renewal
                    assert latest["action"]["Renew"]["fence"] == body["request"]["fence"]
                    release = {"ClaimWork": {"input": {"project": value["project"],
                        "action": {"Release": {"fence": body["request"]["fence"]}}}}}
                    status, _, released = forward("/api/call", json.dumps(release).encode(), credentials, "POST")
                    receipt = json.loads(released)
                    assert status == 200 and receipt["Claimed"]["claim"]["released"], receipt
                    observation = {"boundary": "claim release committed before result artifact upload",
                                   "attempt": value["attempt"], "release": receipt}
                    (evidence / "release.json").write_text(json.dumps(observation, indent=2) + "\n")
            status, media, body = forward(self.path, payload, self.headers, self.command)
            if self.path == "/api/admission" and lose_acknowledgement and status == 200:
                current = json.loads(body)
                assert decision is None or current == decision
                decision = current
                status, body = 503, b'{}'
            self.send_response(status)
            self.send_header("Content-Type", media)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    endpoint = f"http://127.0.0.1:{proxy.server_port}"
    try:
        for name, arguments in [("init", ["init", "--endpoint", endpoint]),
                                ("run", ["run", "codex", "--settings", str(settings), "--input", str(request_file)])]:
            result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=100)
            (evidence / f"{name}.stdout").write_text(result.stdout)
            (evidence / f"{name}.stderr").write_text(result.stderr)
            assert result.returncode == 0, f"{name} exited {result.returncode}; inspect retained stdout/stderr"
        governing = json.loads(result.stdout)
        session = Path(governing["directory"])
        native = [json.loads(line) for line in (session / "payload" / governing["attempt"]["value"] / "stdout").read_text().splitlines()]
        event = next(event for event in native if event.get("type") == "fixture.admission")
        child = event["status"]
        child_path = session / "children" / child["attempt"]["value"]
        if scenario == "running-loss":
            job = json.loads((session / "journal" / (child["attempt"]["value"] + ".json")).read_text())
            workspace = json.loads((session / "workspaces" / child["attempt"]["value"] / "workspace.json").read_text())
            assert job["phase"] == "Settled" and job["exit"]["settled"] and job["exit"]["reason"] == "Cancelled", job
            assert workspace["admission"] == "Quarantined", workspace
            assert "Work claim refresh failed" in child["blocker"], child
            assert not (child_path / "candidate.json").exists()
            observation = {"release": event["release"], "settlementSeconds": event["settlementSeconds"],
                "boundSeconds": event["boundSeconds"], "job": job, "workspace": workspace}
        elif scenario in {"accepted-ack", "rejected-ack"}:
            assert decision is not None and child["phase"] == "PublicationPending" and child["result"] is None, child
            assert not (child_path / "receipt.json").exists()
            before = child
            assert renewal is not None
            latest, credentials = renewal
            release = {"ClaimWork": {"input": {"project": latest["project"],
                "action": {"Release": {"fence": latest["action"]["Renew"]["fence"]}}}}}
            status, _, released = forward("/api/call", json.dumps(release).encode(), credentials, "POST")
            assert status == 200 and json.loads(released)["Claimed"]["claim"]["released"]
            if scenario == "rejected-ack":
                acquire = {"ClaimWork": {"input": {"project": latest["project"], "action": {"Acquire": {
                    "id": {"value": str(uuid.uuid4())}, "members": [item["id"] for item in decision["members"]], "durationMillis": "300000"}}}}}
                status, _, fresh = forward("/api/call", json.dumps(acquire).encode(), credentials, "POST")
                assert status == 200 and "Claimed" in json.loads(fresh)
            output = session / "payload" / child["attempt"]["value"] / "stdout"
            output.write_text(output.read_text().replace('"input_tokens": 100', '"input_tokens": 900'))
            lose_acknowledgement = False
            replay = subprocess.run(command + ["job", "upload", "--session", str(session)], cwd=repository,
                env=environment, capture_output=True, text=True, timeout=60)
            (evidence / "recovery.stdout").write_text(replay.stdout)
            (evidence / "recovery.stderr").write_text(replay.stderr)
            assert replay.returncode == 0, replay.stdout + replay.stderr
            child = json.loads((child_path / "receipt.json").read_text())
            assert child["usageDelivered"] and child["phase"] == ("Completed" if scenario == "accepted-ack" else "Failed"), child
            assert (child["result"] is not None) == (scenario == "accepted-ack")
            again = subprocess.run(command + ["job", "upload", "--session", str(session)], cwd=repository,
                env=environment, capture_output=True, text=True, timeout=60)
            assert again.returncode == 0 and "Acknowledged 0" in again.stdout, again.stdout + again.stderr
            assert json.loads((child_path / "receipt.json").read_text()) == child
            observation = {"pending": before, "decision": decision}
        else:
            assert observation is not None and child["attempt"] == observation["attempt"]
            assert child["phase"] != "Completed" and child["result"] is None, "Released claim admitted a late child result"
        assert child["usageDelivered"]
        assert renewal is not None
        usage = {"Usage": {"input": {"project": renewal[0]["project"],
            "selection": {"Summary": {"filter": {"SessionOnly": {"id": governing["session"]}}}}}}}
        credentials = {"Authorization": "Bearer " + environment["CQ_TOKEN"], "Content-Type": "application/json",
            "CQ-Protocol-Version": "0.1.0", "CQ-Session": governing["session"]["value"]}
        status, _, audit = forward("/api/call", json.dumps(usage).encode(), credentials, "POST")
        audit = json.loads(audit)
        assert status == 200 and audit["UsageSummary"]["report"]["direct"]["total"]["known"] == "131", audit
        (evidence / "observed.json").write_text(json.dumps({"boundary": observation, "child": child, "governor": governing, "audit": audit}, indent=2) + "\n")
        print(json.dumps({"scenario": scenario, "childPhase": child["phase"], "result": child["result"],
            "usageDelivered": child["usageDelivered"], "taskTokens": "131"}), flush=True)
    finally:
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    evidence = Path(os.environ["CQ_ADMISSION_EVIDENCE"])
    for scenario in ["release-before", "accepted-ack", "rejected-ack", "running-loss"]:
        directory = evidence / scenario
        directory.mkdir(parents=True)
        reproduce(SimpleNamespace(evidence=directory, environment=dict(os.environ)), command, scenario)


if __name__ == "__main__":
    main()
