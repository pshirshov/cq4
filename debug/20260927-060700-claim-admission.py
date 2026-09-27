#!/usr/bin/env python3
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parent.parent


def reproduce(checks, command):
    evidence = checks.evidence
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = checks.environment["CQ_TOKEN"]
    repository = evidence / "consumer"
    repository.mkdir()
    subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
    subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                    "commit", "--quiet", "--allow-empty", "-m", "Admission reproduction"], check=True)
    guardian = evidence / "cq-guardian"
    subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(guardian), "host/native/guardian.c"], check=True)
    fixture = (ROOT / "dev/dispatch-fixture.py").read_text()
    boundary = '    worker = poll(first["attempt"])\n'
    assert fixture.count(boundary) == 1
    fixture = fixture.replace(boundary, boundary + '''    emit({"type": "fixture.admission", "status": worker})
    finish({"summary": "Retained child admission observation"})
    return
''')
    executable = evidence / "fixture-harness"
    executable.write_text(f"#!{sys.executable}\n" + fixture)
    executable.chmod(0o700)
    settings = evidence / "settings.json"
    settings.write_text(json.dumps({
        "stateRoot": str(evidence / "sessions"), "guardian": str(guardian),
        "evaluation": {"run": "claim-admission-reproduction", "scenario": "release-before-result-publication", "assessor": False},
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
            nonlocal renewal, observation
            value = json.loads(payload) if payload is not None else {}
            if self.path == "/api/call" and "ClaimWork" in value and "Renew" in value["ClaimWork"]["input"]["action"]:
                renewal = (value["ClaimWork"]["input"], dict(self.headers))
            if self.path == "/api/artifact" and value["kind"] == "Result" and observation is None:
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
        statuses = [json.loads(path.read_text()) for path in (session / "children").glob("*/receipt.json")]
        assert len(statuses) == 1 and observation is not None, "Publication boundary was not reached"
        child = statuses[0]
        assert child["attempt"] == observation["attempt"] and child["usageDelivered"]
        (evidence / "observed.json").write_text(json.dumps({"release": observation, "child": child, "governor": governing}, indent=2) + "\n")
        print(json.dumps({"releaseCommitted": True, "childPhase": child["phase"], "result": child["result"],
                          "usageDelivered": child["usageDelivered"]}), flush=True)
        assert child["phase"] != "Completed" and child["result"] is None, "Released claim admitted a late child result"
    finally:
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)


def main():
    os.chdir(ROOT)
    module = runpy.run_path(str(ROOT / "dev/check"), run_name="cq_checks")
    identifier = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-claim-admission-repro"
    evidence = Path(os.environ["CQ_EVIDENCE_ROOT"]) / identifier
    evidence.mkdir()
    print(f"Evidence: {evidence}", flush=True)
    files = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"]).decode().split("\0")
    (evidence / "source-sha256.json").write_text(json.dumps({name: hashlib.sha256(Path(name).read_bytes()).hexdigest()
        for name in files if name and Path(name).is_file()}, indent=2) + "\n")
    checks = module["Checks"](evidence, dict(os.environ))
    classpath = checks.classpath()
    command = ["java", "-cp", classpath, "cq.server.Main"]
    port = module["free_port"]()
    checks.environment.update(CQ_HOST="127.0.0.1", CQ_PORT=str(port), CQ_ORIGIN=f"http://127.0.0.1:{port}",
                              CQ_TOKEN=uuid.uuid4().hex, CQ_SESSION=str(uuid.uuid4()))
    with checks.database([]):
        with checks.server(command, "admission"):
            reproduce(checks, command)


if __name__ == "__main__":
    main()
