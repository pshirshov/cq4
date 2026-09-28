import concurrent.futures
import json
import os
import shlex
import signal
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

CLI_STARTUP_SECONDS = 15
GIT_DEADLINE_WATCHDOG_SECONDS = 12
CLI_CLEANUP_SECONDS = 3


def main():
    command = sys.argv[1:]
    environment = dict(os.environ)
    environment["CQ_ENDPOINT"] = environment["CQ_ORIGIN"]
    with tempfile.TemporaryDirectory(prefix="cq-cli-") as temporary:
        fake_bin = Path(temporary) / "silent-git"
        fake_bin.mkdir()
        fake_git = fake_bin / "git"
        git_started = Path(temporary) / "git-started"
        fake_git.write_text(f"#!/bin/sh\n: > {shlex.quote(str(git_started))}\nexec sleep 30\n")
        fake_git.chmod(0o700)
        timeout_environment = {**environment, "PATH": str(fake_bin) + os.pathsep + environment["PATH"]}
        began = time.monotonic()
        silent = subprocess.Popen(command + ["web"], cwd=temporary, env=timeout_environment,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        try:
            while not git_started.exists() and silent.poll() is None and time.monotonic() - began < CLI_STARTUP_SECONDS:
                time.sleep(0.01)
            if not git_started.exists():
                raise AssertionError("CLI did not start its Git lookup within the separate startup deadline")
            operation_started = time.monotonic()
            stdout, stderr = silent.communicate(timeout=GIT_DEADLINE_WATCHDOG_SECONDS)
            assert silent.returncode == 1 and "deadline exceeded" in stderr, stdout + stderr
            print(json.dumps({"case": "bounded Git lookup", "startupSeconds": operation_started - began,
                              "gitAndExitSeconds": time.monotonic() - operation_started}), flush=True)
        except subprocess.TimeoutExpired:
            raise AssertionError("CLI Git lookup exceeded its 10-second deadline while waiting for stdout")
        finally:
            try:
                os.killpg(silent.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            silent.communicate(timeout=CLI_CLEANUP_SECONDS)
        root = Path(temporary) / "consumer"
        root.mkdir()
        subprocess.run(["git", "init", "--quiet", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=CQ Test", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "initial"], check=True)

        def run(cwd, *args, expected=0):
            result = subprocess.run(command + list(args), cwd=cwd, env=environment, capture_output=True, text=True, timeout=20)
            assert result.returncode == expected, result.stdout + result.stderr
            return result.stdout

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: run(root, "init"), range(2)))
        first = json.loads(results[0].splitlines()[0])["Initialized"]["project"]
        second = json.loads(results[1].splitlines()[0])["Initialized"]["project"]
        assert first == second
        config = json.loads((root / ".git/cq/project.json").read_text())
        assert first["id"] == config["project"]
        worktree = Path(temporary) / "worktree"
        subprocess.run(["git", "-C", str(root), "worktree", "add", "--quiet", "-b", "isolated", str(worktree)], check=True)
        attached = json.loads(run(worktree, "init").splitlines()[0])["Initialized"]["project"]
        assert attached == first
        assert run(worktree, "web").strip() == environment["CQ_ORIGIN"]
        assert json.loads(run(root, "query"))["Found"]["page"]["items"] == []
        assert json.loads(run(root, "query", "--query", 'ledger:Tasks ("retry deadline" OR status:Ready) archived:all'))["Found"]["page"]["items"] == []
        run(root, "query", "--query", "alpha AND", expected=1)
        completed = json.loads(run(root, "query", "--query", "status:Re", "--complete", "9"))["QueryAnalyzed"]["analysis"]
        assert any(value["text"] == "ready" and value["span"] == {"start": 7, "end": 9} for value in completed["suggestions"])
        run(root, "query", "--complete", "999", expected=1)
        run(root, "query", "--complete", "0", "--after", "T1", expected=1)
        assert json.loads(run(root, "query", "--roots", ""))["Workset"]["page"]["entries"] == []
        draft = {"title": "CLI workset", "body": "Context", "labels": [], "archived": False,
                 "content": {"Task": {"status": "Ready", "acceptance": ["Visible"], "result": None, "validation": []}}, "citations": []}
        command_body = {"Change": {"input": {"project": config["project"], "change": {"request": {"value": str(uuid.uuid4())},
            "mutations": [{"Create": {"draft": draft}}, {"Create": {"draft": draft}}], "fences": [], "reason": "CLI workset"}}}}
        seed = urllib.request.Request(environment["CQ_ORIGIN"] + "/api/call", data=json.dumps(command_body).encode(), headers={
            "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": environment["CQ_SESSION"],
            "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
        with urllib.request.urlopen(seed, timeout=10) as response:
            assert "Changed" in json.load(response)
        workset = json.loads(run(root, "query", "--roots", "T1,T2", "--limit", "1"))["Workset"]["page"]
        assert workset["selectedCount"] == 2 and workset["hasMore"] and workset["entries"][0]["root"]
        continued = json.loads(run(root, "query", "--roots", "T2,T1", "--after", "T1", "--snapshot", json.dumps(workset["snapshot"])))["Workset"]["page"]
        assert [value["item"]["id"]["number"] for value in continued["entries"]] == ["2"] and not continued["hasMore"]
        run(root, "query", "--roots", "T1", "--query", "T1", expected=1)
        assert json.loads(run(root, "status"))["UsageSummary"]["report"]["direct"]["total"]["known"] == "0"
        assert json.loads(run(root, "status", "audit", "--limit", "1"))["UsageAudit"]["page"]["entries"] == []
        assert json.loads(run(root, "status", "attempts", "--limit", "1"))["UsageAttempts"]["page"]["entries"] == []
        assert json.loads(run(root, "status", "costs", "--limit", "1"))["UsageCosts"]["page"]["entries"] == []
        run(root, "status", "outcomes", "--attempt", "00000000-0000-0000-0000-000000000001", expected=1)
        run(root, "init", "--project-id", "00000000-0000-0000-0000-000000000001", expected=1)
        subprocess.run(["git", "-C", str(root), "worktree", "remove", str(worktree)], check=True)
        moved = Path(temporary) / "moved"
        root.rename(moved)
        assert json.loads(run(moved, "init").splitlines()[0])["Initialized"]["project"] == first
        independent = Path(temporary) / "independent"
        independent.mkdir()
        copied = json.loads(run(independent, "init", "--project-id", config["project"]["value"]).splitlines()[0])["Initialized"]["project"]
        assert copied == first
        assert (independent / ".cq/project.json").is_file()
        renamed = json.loads(run(moved, "init", "--name", "Renamed consumer").splitlines()[0])["Initialized"]["project"]
        assert renamed["id"] == first["id"] and renamed["name"] == "Renamed consumer", renamed
        assert renamed["revision"]["value"] == "2"
        refreshed = json.loads(run(independent, "init").splitlines()[0])["Initialized"]["project"]
        assert refreshed == renamed
        assert json.loads((independent / ".cq/project.json").read_text())["name"] == renamed["name"]
    print("CLI concurrent init, worktree sharing, moved checkout, explicit reattachment, collision rejection, query and usage audit passed")


if __name__ == "__main__":
    main()
