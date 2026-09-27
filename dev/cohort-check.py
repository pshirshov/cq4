import contextlib
import http.server
import json
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
                       "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144},
            "checks": [{"name": "consumer-content", "command": [sys.executable, "-c",
                "from pathlib import Path; assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'; Path('check-private').write_text('isolated')"],
                "executionMillis": "5000", "outputBytes": 65536}],
        }))
        source = root / "request.txt"
        source.write_text("cohort-choice-ack")
        publications = []
        lost = False

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

            def change(mutations):
                payload = {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())},
                    "mutations": mutations, "fences": [], "reason": "Cohort flow fixture"}}}}
                request = urllib.request.Request(backend + "/api/call", data=json.dumps(payload).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                    "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=10) as response:
                    value = json.load(response)
                assert "Failed" not in value, value
                return value["Changed"]["ack"]["items"]

            for unknown in [False, True]:
                draft = {"title": "Cohort task", "body": "Create consumer.txt", "labels": ["cohort-selected-unknown" if unknown else "cohort-selected"],
                         "archived": False, "content": {"Task": {"status": "Ready", "acceptance": ["Exact content verified"], "result": None, "validation": []}}, "citations": []}
                goal, *members = change([{"Create": {"draft": {**draft, "labels": [], "content": {"Goal": {
                    "status": "Open", "outcome": "Shared parser", "acceptance": ["Independent task acceptance"], "scope": "Consumer"}}}}},
                    *[{"Create": {"draft": draft}} for _ in range(2)]])
                for member in members:
                    linked = change([{"Reference": {"source": goal["id"], "expectedSource": goal["revision"], "relation": "Produces",
                        "target": member["id"], "expectedTarget": member["revision"], "present": True}}])
                    goal = next(value for value in linked if value["id"] == goal["id"])
                source.write_text("cohort-flow:" + json.dumps({"roots": [goal["id"]], "unknown": unknown}))
                result = subprocess.run(command + ["run", "codex", "--settings", str(settings), "--input", str(source),
                    "--workflow", "advance", "--roots", "G" + goal["id"]["number"], "--through", "review"],
                    cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
                name = "unknown" if unknown else "compatible"
                (root / (name + ".stdout")).write_text(result.stdout)
                (root / (name + ".stderr")).write_text(result.stderr)
                assert result.returncode == 0, f"{name} exited {result.returncode}; inspect {root}"
                flow = json.loads(result.stdout)
                assert flow["processSucceeded"] and flow["usageDelivered"]
                assert not (repository / "consumer.txt").exists()
                for child in (Path(flow["directory"]) / "children").iterdir():
                    ticket = json.loads((child / "ticket.json").read_text())
                    assert ticket["selection"] is not None and ticket["assignment"]["attribution"] == "Shared"
                    assert len(ticket["assignment"]["members"]) == 2
                print(json.dumps({"scenario": name, "receipt": flow}))
        finally:
            proxy.shutdown()
            proxy.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    main()
