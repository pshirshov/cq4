import contextlib
import http.server
import json
from fixture_runtime import guardian_binary
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import uuid


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    backend = os.environ["CQ_ORIGIN"]
    retained = os.environ.get("CQ_COHORT_EVIDENCE")
    if retained is not None:
        Path(retained).mkdir(parents=True)
    with (contextlib.nullcontext(retained) if retained is not None else tempfile.TemporaryDirectory(prefix="cq-cohort-")) as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Cohort fixture"], check=True)
        guardian = guardian_binary(root, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + Path("dev/dispatch-fixture.py").read_text())
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "integrationTarget": None, "stateRoot": str(root / "sessions"), "guardian": str(guardian), "evaluation": None,
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "90000", "heartbeatMillis": "1000",
                       "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144},
            "checks": [{"name": "consumer-content", "command": [sys.executable, "-c",
                "from pathlib import Path; assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'; Path('check-private').write_text('isolated')"],
                "executionMillis": "5000", "retainedOutputBytes": 65536}],
        }))
        source = root / "request.txt"
        source.write_text("cohort-choice-ack")
        publications = []
        lost = False
        priced = False
        attempts = {}
        price_receipts = {}
        audit_session = str(uuid.uuid4())

        def price(project, attempt):
            def host(operation):
                request = urllib.request.Request(backend + "/api/usage", data=json.dumps({"project": project, "operation": operation}).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": audit_session,
                    "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=10) as response:
                    result = json.load(response)
                assert "Failed" not in result, result
                return result
            zero = {name: {"value": "0", "measurement": "Observed"} for name in ["input", "output", "cacheRead", "cacheWrite", "reasoning"]}
            meter = "synthetic-cohort-price"
            host({"Meter": {"value": {"key": meter, "attempt": attempt["id"], "scope": "Increment", "baseline": zero,
                "baselineCost": {"amount": None, "currency": None, "basis": "Unknown", "pricingVersion": None}}}})
            observation = {"id": {"value": str(uuid.uuid5(uuid.UUID(attempt["id"]["value"]), meter))}, "attempt": attempt["id"],
                "source": meter, "position": "1", "occurredAt": attempt["startedAt"], "receivedAt": "0", "scope": "Increment",
                "counters": zero, "inputIncludesCache": True, "outputIncludesReasoning": True,
                "cost": {"amount": {"value": "0.125"}, "currency": "USD", "basis": "PriceTable", "pricingVersion": "synthetic-cohort-fixture"},
                "completeness": "Complete", "gaps": [], "evidence": None, "supersedes": None}
            receipt = host({"Ingest": {"value": {"observation": observation, "meter": meter, "disposition": "Contribution", "detailReason": None}}})
            price_receipts[attempt["id"]["value"]] = {"observation": observation, "receipt": receipt}
            (root / "synthetic-prices.json").write_text(json.dumps(price_receipts, indent=2) + "\n")

        class Proxy(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.transfer(None)

            def do_POST(self):
                self.transfer(self.rfile.read(int(self.headers["Content-Length"])))

            def transfer(self, payload):
                nonlocal lost
                headers = {key: value for key, value in self.headers.items()
                           if key.lower() not in {"host", "content-length", "connection", "upgrade", "http2-settings"}}
                request = urllib.request.Request(backend + self.path, data=payload, headers=headers, method=self.command)
                try:
                    response = urllib.request.urlopen(request, timeout=10)
                except urllib.error.HTTPError as failure:
                    response = failure
                with response:
                    status, media, body = response.status, response.headers.get("Content-Type", "application/json"), response.read()
                value = json.loads(payload) if payload is not None else {}
                if self.path == "/api/usage" and status == 200:
                    operation = value["operation"]
                    if "Start" in operation:
                        attempt = operation["Start"]["value"]
                        attempts[attempt["id"]["value"]] = attempt
                    if priced and "Finish" in operation:
                        attempt = attempts[operation["Finish"]["value"]["attempt"]["value"]]
                        if attempt["parent"] is not None:
                            price(value["project"], attempt)
                if self.path == "/api/artifact" and value["kind"] == "Selection":
                    publications.append({"upload": value, "status": status, "response": json.loads(body)})
                    (root / "selection-publications.json").write_text(json.dumps(publications, indent=2) + "\n")
                    if not lost and status == 200:
                        lost = True
                        status, body = 503, b'{}'
                self.send_response(status)
                self.send_header("Content-Type", media)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        thread.start()
        try:
            endpoint = f"http://127.0.0.1:{proxy.server_port}"
            for name, arguments in [("init", ["init", "--endpoint", endpoint]),
                                    ("run", ["run", "codex", "--settings", str(settings), "--input", str(source)])]:
                result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
                (root / (name + ".stdout")).write_text(result.stdout)
                (root / (name + ".stderr")).write_text(result.stderr)
                assert result.returncode == 0, f"{name} exited {result.returncode}; inspect {root}"
            receipt = json.loads(result.stdout)
            session = Path(receipt["directory"])
            events = [json.loads(line) for line in (session / "payload" / receipt["attempt"]["value"] / "stdout").read_text().splitlines()]
            decision = next(event["value"] for event in events if event.get("type") == "fixture.selection")
            assert len(publications) == 2 and publications[0] == publications[1]
            assert decision == json.loads(publications[0]["upload"]["body"])["decision"]
            assert not list((session / "children").glob("*")), "Unused choice created a child attempt"
            assert receipt["processSucceeded"] and receipt["usageDelivered"]
            print(json.dumps({"scenario": "lost-selection-ack", "decision": decision, "session": receipt["session"]}))
            project = json.loads((repository / ".git/cq/project.json").read_text())["project"]

            def api(payload):
                request = urllib.request.Request(backend + "/api/call", data=json.dumps(payload).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                    "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=10) as response:
                    value = json.load(response)
                assert "Failed" not in value, value
                return value

            def change(mutations):
                value = api({"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())},
                    "mutations": mutations, "fences": [], "reason": "Cohort flow fixture"}}}})
                return value["Changed"]["ack"]["items"]

            for name in ["compatible", "unknown", "refresh", "mixed"]:
                unknown = name == "unknown"
                refresh = name == "refresh"
                priced = name == "mixed"
                draft = {"title": "Cohort task", "body": "Create consumer.txt", "labels": ["cohort-selected-" + name],
                         "archived": False, "content": {"Task": {"status": "Ready", "acceptance": ["Exact content verified"], "result": None, "validation": []}}, "citations": []}
                goal, *members = change([{"Create": {"draft": {**draft, "labels": [], "content": {"Goal": {
                    "status": "Open", "outcome": "Shared parser", "acceptance": ["Independent task acceptance"], "scope": "Consumer"}}}}},
                    *[{"Create": {"draft": draft}} for _ in range(2)]])
                milestone, = change([{"Create": {"draft": {**draft, "title": "Cohort milestone", "labels": [], "content": {"Milestone": {
                    "status": "Open", "objective": "Deliver the shared parser"}}}}}])
                for member in members:
                    linked = change([{"Reference": {"source": goal["id"], "expectedSource": goal["revision"], "relation": "Produces",
                        "target": member["id"], "expectedTarget": member["revision"], "present": True}}])
                    goal = next(value for value in linked if value["id"] == goal["id"])
                    member = next(value for value in linked if value["id"] == member["id"])
                    # Implementation is admitted only for Tasks under a milestone.
                    milestone = next(value for value in change([{"Reference": {"source": member["id"], "expectedSource": member["revision"], "relation": "PartOf",
                        "target": milestone["id"], "expectedTarget": milestone["revision"], "present": True}}]) if value["id"] == milestone["id"])
                source.write_text("cohort-flow:" + json.dumps({"roots": [goal["id"]], "unknown": unknown, "refresh": refresh, "mixed": priced}))
                result = subprocess.run(command + ["run", "codex", "--settings", str(settings), "--input", str(source),
                    "--workflow", "advance", "--roots", "G" + goal["id"]["number"], "--through", "review"],
                    cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
                (root / (name + ".stdout")).write_text(result.stdout)
                (root / (name + ".stderr")).write_text(result.stderr)
                assert result.returncode == 0, f"{name} exited {result.returncode}; inspect {root}"
                flow = json.loads(result.stdout)
                assert flow["processSucceeded"] and flow["usageDelivered"]
                assert not (repository / "consumer.txt").exists()
                children = list((Path(flow["directory"]) / "children").iterdir())
                assert len(children) == (5 if priced else 4 if refresh else 1 if unknown else 3)
                workers = 0
                shared = 0
                direct = 0
                for child in children:
                    ticket = json.loads((child / "ticket.json").read_text())
                    assert ticket["selection"] is not None
                    attribution = ticket["assignment"]["attribution"]
                    assert attribution in ["Shared", "Direct"]
                    assert len(ticket["assignment"]["members"]) == (2 if attribution == "Shared" else 1)
                    shared += attribution == "Shared"
                    direct += attribution == "Direct"
                    workers += "Worker" in ticket["request"]["work"]
                    assert json.loads((child / "receipt.json").read_text())["usageDelivered"]
                if refresh:
                    assert workers == 1, "Assessment refresh retried an unchanged Worker"
                assert (shared, direct) == ((3, 2) if priced else (len(children), 0))
                print(json.dumps({"scenario": name, "receipt": flow}))
            priced = False
            for blocked in [False, True]:
                name = "claimed-pool" if blocked else "fairness"
                draft = {"title": "Independent task", "body": "Inspect this independent requirement", "labels": [], "archived": False,
                         "content": {"Task": {"status": "Ready", "acceptance": ["Separate observation"], "result": None, "validation": []}}, "citations": []}
                members = change([{"Create": {"draft": draft}} for _ in range(32)])
                free = None
                if blocked:
                    free = change([{"Create": {"draft": draft}}])[0]
                    change([{"Reference": {"source": members[0]["id"], "expectedSource": members[0]["revision"], "relation": "Produces",
                        "target": free["id"], "expectedTarget": free["revision"], "present": True}}])
                    api({"ClaimWork": {"input": {"project": project, "action": {"Acquire": {"id": {"value": str(uuid.uuid4())},
                        "members": [member["id"] for member in members], "durationMillis": "180000"}}}}})
                roots = [member["id"] for member in members]
                source.write_text("cohort-fairness:" + json.dumps({"roots": roots, "blocked": blocked, "free": free}))
                result = subprocess.run(command + ["run", "codex", "--settings", str(settings), "--input", str(source), "--workflow", "advance",
                    "--roots", ",".join("T" + member["number"] for member in roots), "--through", "explore"],
                    cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
                (root / (name + ".stdout")).write_text(result.stdout)
                (root / (name + ".stderr")).write_text(result.stderr)
                assert result.returncode == 0, f"{name} exited {result.returncode}; inspect {root}"
                flow = json.loads(result.stdout)
                assert flow["processSucceeded"] and flow["usageDelivered"]
                assert not list((Path(flow["directory"]) / "children").glob("*")), "Unused choices created a child"
                print(json.dumps({"scenario": name, "receipt": flow}))
        finally:
            proxy.shutdown()
            proxy.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
