"""Focused check for D111: dev/project-archive-check.py must send every CLI archive call to the check's CQ_ORIGIN.

Usage: python3 dev/project-archive-endpoint-check.py <CLI command...>

A saved endpoint in <git common dir>/cq/project.json of a linked worktree competes with CQ_ORIGIN. Two local
listeners stand in for that saved endpoint (decoy) and for the check's server (origin); the test asserts on which
one each CLI invocation contacted. No PostgreSQL and no CQ server are needed.
"""
import http.server
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import threading
import time
import uuid

CLI_SECONDS = 60
GIT_SECONDS = 20


class Listener:
    def __init__(self, name):
        self.name = name
        self.requests = []
        self.lock = threading.Lock()
        listener = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def handle_request(self):
                length = int(self.headers.get("Content-Length") or 0)
                if length:
                    self.rfile.read(length)
                with listener.lock:
                    listener.requests.append({"method": self.command, "path": self.path, "authorization": self.headers.get("Authorization")})
                body = b'{"refused":"endpoint check listener"}'
                self.send_response(503)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = handle_request

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.origin = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *failure):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def take(self):
        with self.lock:
            taken, self.requests = self.requests, []
        return taken


def git(*args):
    subprocess.run(["git", *args], check=True, timeout=GIT_SECONDS, capture_output=True, text=True)


def main():
    command = sys.argv[1:]
    if not command:
        raise SystemExit("Usage: python3 dev/project-archive-endpoint-check.py <CLI command...>")
    module = runpy.run_path(str(Path(__file__).with_name("project-archive-check.py")))
    run_cli, start_backup = module["run_cli"], module["start_backup"]
    project = str(uuid.uuid4())
    token = uuid.uuid4().hex + uuid.uuid4().hex
    failures = []
    with tempfile.TemporaryDirectory(prefix="cq-archive-endpoint-") as temporary, Listener("decoy") as decoy, Listener("origin") as origin:
        root = Path(temporary) / "repository"
        git("init", "--quiet", str(root))
        git("-C", str(root), "-c", "user.name=CQ Test", "-c", "user.email=cq@example.invalid", "commit", "--quiet", "--allow-empty", "-m", "initial")
        worktree = Path(temporary) / "linked"
        git("-C", str(root), "worktree", "add", "--quiet", "--detach", str(worktree))
        common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=worktree,
                                check=True, timeout=GIT_SECONDS, capture_output=True, text=True).stdout.strip()
        saved = Path(common) / "cq"
        saved.mkdir()
        (saved / "project.json").write_text(json.dumps({"project": {"value": project}, "endpoint": decoy.origin, "name": "decoy"}))
        # Evidence directory nested in the linked worktree, as dev/check places it.
        out = worktree / ".work/evidence/check-project-archives"
        out.mkdir(parents=True)
        environment = dict(os.environ)
        environment.pop("CQ_ENDPOINT", None)
        environment.pop("CQ_TOKEN_FILE", None)
        environment.update(CQ_ORIGIN=origin.origin, CQ_TOKEN=token, CQ_SESSION=str(uuid.uuid4()))

        def judge(case, stdout, stderr, exit_code):
            contacted_decoy, contacted_origin = decoy.take(), origin.take()
            backups = [r for r in contacted_origin if r["method"] == "GET" and r["path"] == f"/api/backup/{project}"]
            passed = not contacted_decoy and any(r["authorization"] == "Bearer " + token for r in backups)
            record = {"case": case, "passed": passed, "cliExit": exit_code, "decoyRequests": len(contacted_decoy),
                      "decoyPaths": [r["path"] for r in contacted_decoy], "originRequests": len(contacted_origin),
                      "originBackupWithToken": any(r["authorization"] == "Bearer " + token for r in backups)}
            print(json.dumps(record), flush=True)
            if not passed:
                failures.append(case)
                print(f"{case}: CLI stderr tail: {stderr[-600:]}", file=sys.stderr, flush=True)

        began = time.monotonic()
        result = run_cli(command, ["backup", project, str(out / "helper.cqbackup")], environment, out, timeout=CLI_SECONDS)
        judge("cli-helper", result.stdout, result.stderr, result.returncode)

        with (out / "concurrent.stdout").open("w+") as stdout, (out / "concurrent.stderr").open("w+") as stderr:
            process = start_backup(command, project, out / "concurrent.cqbackup", environment, out, stdout, stderr)
            try:
                exit_code = process.wait(timeout=CLI_SECONDS)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)
            stdout.seek(0); stderr.seek(0)
            judge("concurrent-backup", stdout.read(), stderr.read(), exit_code)
        print(json.dumps({"seconds": round(time.monotonic() - began, 2)}), flush=True)
    if failures:
        raise SystemExit("Archive CLI did not use CQ_ORIGIN over the saved endpoint: " + ", ".join(failures))
    print("PASS: project archive CLI calls use CQ_ORIGIN", flush=True)


if __name__ == "__main__":
    main()
