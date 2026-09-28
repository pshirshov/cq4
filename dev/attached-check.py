"""Behavioral Effectual Good Communication: attached MCP, real server/Git/processes."""
import json
import os
from pathlib import Path
import queue
import shlex
import subprocess
import sys
import threading
import time
import uuid
from fixture_runtime import guardian_binary


def identity():
    return {"value": str(uuid.uuid4())}


class Peer:
    def __init__(self, command, directory, environment, log):
        self.process = subprocess.Popen(command, cwd=directory, env=environment, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=log)
        self.responses = queue.Queue()
        self.lock = threading.Lock()
        self.sequence = 0
        self.traffic = []
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()
        self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "attached-fixture", "version": "1"}})
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def send(self, value):
        with self.lock:
            self.process.stdin.write((json.dumps(value) + "\n").encode())
            self.process.stdin.flush()

    def read(self):
        try:
            while True:
                line = self.process.stdout.readline(2097153)
                if not line:
                    raise EOFError("CQ closed stdout")
                assert len(line) <= 2097152 and line.endswith(b"\n"), "Unbounded MCP frame"
                value = json.loads(line)
                if value.get("method") == "ping":
                    self.send({"jsonrpc": "2.0", "id": value["id"], "result": {}})
                else:
                    self.responses.put(value)
        except Exception as error:
            self.responses.put(error)

    def rpc(self, method, params):
        self.sequence += 1
        self.send({"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params})
        value = self.responses.get(timeout=40)
        if isinstance(value, Exception):
            raise value
        assert value["id"] == self.sequence and "error" not in value, value
        return value["result"]

    def tool(self, name, arguments, denied=False):
        value = self.rpc("tools/call", {"name": name, "arguments": arguments})
        assert value["isError"] == denied, value
        body = value["structuredContent"]
        self.traffic.append({"name": name, "arguments": arguments, "result": body})
        return body

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            assert self.process.wait(timeout=20) == 0
        self.reader.join(timeout=2)


def main():
    command = sys.argv[1:]
    root = Path(os.environ["CQ_ATTACHED_EVIDENCE"])
    root.mkdir(parents=True)
    repository = root / "consumer"
    repository.mkdir()
    subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
    subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                    "commit", "--quiet", "--allow-empty", "-m", "Consumer base"], check=True)
    token = root / "credential"
    token.write_text(os.environ["CQ_TOKEN"] + "\n")
    token.chmod(0o600)
    env = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    env["CQ_TOKEN_FILE"] = str(token)
    guardian = guardian_binary(root, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
    native = root / "fixture-harness"
    native.write_text(f"#!{sys.executable}\n" + Path("dev/dispatch-fixture.py").read_text().replace('print("codex-cli 0.156.1")', 'print("fixture 0.156.1 2.1.280 0.87.1")'))
    native.chmod(0o700)
    limits = {"startupMillis": "5000", "executionMillis": "60000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144}
    settings = root / "settings.json"
    settings.write_text(json.dumps({"integrationTarget": None, "stateRoot": str(root / "sessions"), "guardian": str(guardian), "checks": [], "evaluation": None,
        "harnesses": [{"harness": name, "executable": str(native), "model": "fixture-model", "provider": provider, "version": version, "providerExtensions": [], "providerEnvironment": []}
                      for name, provider, version in [("Codex", "fixture-provider", "0.156.1"), ("Claude", "anthropic", "2.1.280"), ("Pi", "fixture-provider", "0.87.1")]], "limits": limits}))
    env["CQ_SETTINGS"] = str(settings)
    def cli(arguments):
        result = subprocess.run(command + arguments, cwd=repository, env=env, capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, result.stderr + result.stdout
        return result.stdout
    cli(["init", "--endpoint", os.environ["CQ_ORIGIN"]])
    wrapper = root / "cq-fixture-entrypoint"
    wrapper.write_text("#!/bin/sh\nexec " + shlex.join(command) + ' "$@"\n')
    wrapper.chmod(0o700)
    for harness in ["claude", "codex", "pi"]:
        cli(["configure", harness, "--settings", str(settings), "--executable", str(wrapper)])
    with (root / "host.log").open("w") as log:
        peer = Peer(command + ["host", "codex"], repository, env, log)
        try:
            inventory = peer.rpc("tools/list", {})["tools"]
            assert {tool["name"] for tool in inventory} == {"session", "dispatch", "search", "read", "graph", "change", "apply", "claim", "usage"}
            context = peer.tool("session", {"Context": {}})["Context"]["value"]
            assert context["workflow"] is None and "unobserved" in context["usageCoverage"]
            assert "Canonical argument schemas" in context["instructions"]
            project = context["project"]["project"]
            peer.tool("initialize", {}, denied=True)
            peer.tool("workspace", {"Read": {"path": ".", "offset": 0, "limit": 20}}, denied=True)
            selection = {"request": identity(), "roots": [], "work": {"Explorer": {"mode": "Investigate"}}, "guidance": [], "artifacts": [], "previous": None, "limits": limits}
            peer.tool("dispatch", {"Select": {"request": selection}}, denied=True)
            first = {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}}}
            activated = peer.tool("session", first)
            assert peer.tool("session", first) == activated
            peer.tool("session", {"Workflow": {"id": first["Workflow"]["id"], "request": {"Advance": {"roots": [], "through": "Explore"}}}}, denied=True)
            draft = {"title": "Attached investigation", "body": "Investigate the fixture", "labels": ["proposal-fixture"], "archived": False,
                     "content": {"Task": {"status": "Ready", "acceptance": ["Report findings"], "result": None, "validation": []}}, "citations": []}
            created = peer.tool("change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": draft}}], "fences": [], "reason": "Attached fixture"}})["Changed"]["ack"]["items"][0]
            selection["roots"] = [created["id"]]
            choice, = peer.tool("dispatch", {"Select": {"request": selection}})["Selection"]["value"]["choices"]
            claim = peer.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [created["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            next_scope = {"Workflow": {"id": identity(), "request": {"Advance": {"roots": [created["id"]], "through": "Explore"}}}}
            peer.tool("session", next_scope)
            peer.tool("dispatch", {"Select": {"request": selection}}, denied=True)
            peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": claim["fence"]}}, denied=True)
            assert peer.tool("session", first) == activated
            assert peer.tool("session", {"Context": {}})["Context"]["value"]["workflow"]["id"] == next_scope["Workflow"]["id"]
            selection["request"] = identity()
            choice, = peer.tool("dispatch", {"Select": {"request": selection}})["Selection"]["value"]["choices"]
            started = peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": claim["fence"]}})["Status"]["value"]
            # The asynchronous child must finish before a different workflow is admitted.
            if started["phase"] in ["Preparing", "Running"]:
                peer.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}}}, denied=True)
            for _ in range(6):
                status = peer.tool("dispatch", {"Status": {"attempt": started["attempt"], "waitMillis": 20000}})["Status"]["value"]
                if status["phase"] not in ["Preparing", "Running", "Stopping", "Validating", "Publishing"]:
                    break
            assert status["phase"] == "Completed" and status["usageDelivered"] and status["result"], status
            assert "CHILD_ONLY_NARRATIVE" not in json.dumps(peer.traffic)
            session = Path(context["directory"])
            assert not list((session / "payload").glob(context["attempt"]["value"] + "/*")), "Attached session launched a Governor"
        finally:
            peer.close()
    (root / "traffic.json").write_text(json.dumps(peer.traffic, indent=2) + "\n")
    manifest = json.loads((session / "run.json").read_text())
    assert manifest["ownership"] == "Attached" and manifest["attempt"]["provider"] == "unobserved-interactive-provider"
    assert "Acknowledged 0" in cli(["job", "upload", "--session", str(session)])
    (session / "delivery/final/000000.ack").unlink()
    assert "Acknowledged 1" in cli(["job", "upload", "--session", str(session)])
    totals = json.loads(cli(["status", "--session", context["session"]["value"]]))["UsageSummary"]["report"]
    assert totals["attempts"]["unknown"] == "1" and totals["attempts"]["running"] == "0" and totals["attemptsWithoutMeters"] == "1", totals
    print(json.dumps({"attachedSession": context["session"], "child": status, "usage": totals, "activationFence": True, "tokenFile": True, "replay": True}))

    with (root / "pi-host.log").open("w") as log:
        pi = Peer(command + ["host", "pi"], repository, env, log)
        try:
            pi_context = pi.tool("session", {"Context": {}})["Context"]["value"]
            sample = {"sequence": "1", "session": "fixture-native-pi", "turn": "1", "provider": "fixture-provider", "model": "fixture-model",
                      "timestamp": "1000", "responseId": "response-1", "stopReason": "stop", "input": "10", "output": "3", "cacheRead": "2",
                      "cacheWrite": "0", "reasoning": None, "totalTokens": "15", "costUSD": {"value": "0.001"}}
            pi.rpc("cq/piUsage", sample)
            pi.rpc("cq/piUsage", sample)
        finally:
            pi.close()
    pi_totals = json.loads(cli(["status", "--session", pi_context["session"]["value"]]))["UsageSummary"]["report"]
    assert pi_totals["unattributed"]["total"]["known"] == "15" and pi_totals["incompleteMeters"] == "1", pi_totals
    assert "Acknowledged 0" in cli(["job", "upload", "--session", pi_context["directory"]])
    print(json.dumps({"attachedPiUsage": "deduplicated-partial", "integrationExports": ["claude", "codex", "pi"]}))

    with (root / "closing-host.log").open("w") as log:
        closing = Peer(command + ["host", "codex"], repository, env, log)
        try:
            closing_context = closing.tool("session", {"Context": {}})["Context"]["value"]
            closing.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}}})
            created = closing.tool("change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": {**draft, "labels": []}}}], "fences": [], "reason": "Owned child shutdown"}})["Changed"]["ack"]["items"][0]
            selected, = closing.tool("dispatch", {"Select": {"request": {**selection, "request": identity(), "roots": [created["id"]], "work": {"Worker": {"mode": "Probe"}}}}})["Selection"]["value"]["choices"]
            owned = closing.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [created["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            running = closing.tool("dispatch", {"StartChoice": {"choice": selected["id"], "harness": "Codex", "fence": owned["fence"]}})["Status"]["value"]
            deadline = time.monotonic() + 20
            while running["process"] != "Running" and time.monotonic() < deadline:
                running = closing.tool("dispatch", {"Status": {"attempt": running["attempt"], "waitMillis": 100}})["Status"]["value"]
            assert running["process"] == "Running", running
        finally:
            closing.close()
    child_directory = Path(closing_context["directory"]) / "children" / running["attempt"]["value"]
    stopped = json.loads((child_directory / "receipt.json").read_text())
    assert stopped["phase"] == "Cancelled" and stopped["process"] == "Settled" and stopped["result"] is None and stopped["usageDelivered"], stopped
    print(json.dumps({"disconnectWithRunningChild": "cancelled-and-accounted"}))

    preload = root / "stall.so"
    subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)
    latch = root / "initial-stall"
    latch.mkdir()
    native_env = {**env, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_MODE": "attached-initial", "CQ_FIXTURE_STALL_ROOT": str(latch)}
    with (latch / "stderr").open("w") as log:
        stalled = subprocess.Popen(command + ["host", "codex"], cwd=repository, env=native_env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log)
        try:
            deadline = time.monotonic() + 20
            while not (latch / "entered").exists() and time.monotonic() < deadline and stalled.poll() is None:
                time.sleep(0.05)
            assert (latch / "entered").exists(), "Initial fsync latch not reached"
            stalled.stdin.close()
            try:
                assert stalled.wait(timeout=16) == 75
            except subprocess.TimeoutExpired:
                raise AssertionError("Attached host survived EOF with initial fsync stalled beyond the 13-second drain deadline")
        finally:
            (latch / "release").touch()
            if stalled.poll() is None:
                stalled.kill()
            stalled.wait(timeout=5)
    print(json.dumps({"initialStallAfterEOF": "bounded-unresolved-exit"}))

    latch = root / "operation-stall"
    latch.mkdir()
    native_env = {**env, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_MODE": "attached-selection", "CQ_FIXTURE_STALL_ROOT": str(latch)}
    with (latch / "stderr").open("w") as log:
        stalled = Peer(command + ["host", "codex"], repository, native_env, log)
        try:
            stalled.tool("session", next_scope)
            stalled.send({"jsonrpc": "2.0", "id": 99, "method": "tools/call", "params": {"name": "dispatch", "arguments": {"Select": {"request": {**selection, "request": identity()}}}}})
            deadline = time.monotonic() + 20
            while not (latch / "entered").exists() and time.monotonic() < deadline and stalled.process.poll() is None:
                time.sleep(0.05)
            assert (latch / "entered").exists(), "Operation fsync latch not reached"
            try:
                assert stalled.process.wait(timeout=46) == 75
            except subprocess.TimeoutExpired:
                raise AssertionError("Attached host survived a stalled MCP operation beyond request+drain deadlines while heartbeat remained healthy")
        finally:
            (latch / "release").touch()
            if stalled.process.poll() is None:
                stalled.process.kill()
            stalled.process.wait(timeout=5)
    print(json.dumps({"operationStallWithHealthyOwner": "bounded-unresolved-exit"}))


if __name__ == "__main__":
    main()
