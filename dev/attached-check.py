"""Behavioral Effectual Good Communication: attached MCP, real server/Git/processes."""
import datetime
import fcntl
import json
import os
from pathlib import Path
import queue
import shlex
import signal
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from fixture_runtime import ROLES, guardian_binary, role_agents, save_agents


# The fixture runs its cases one after another in one process and bounds each of them, not their total: a case that does not end
# within this fails the fixture by its name. Under the tracing agent, where every process starts slowest, the cases took between 9 s
# and 123 s and 648 s together (2026-10-08, load 3 to 5); about five times the slowest tells a slow case from a stuck one.
CASE_SECONDS = 600
_case = {"name": None, "began": 0.0}


def _stuck(_signal, _frame):
    raise AssertionError(f"Attached fixture case did not end within {CASE_SECONDS} seconds: {_case['name']}")


def case(name):
    """Ends the case that runs, printing how long it took, and starts the case `name`; `None` starts none."""
    if _case["name"] is not None:
        print(json.dumps({"case": _case["name"], "seconds": round(time.monotonic() - _case["began"], 2)}), flush=True)
    _case.update(name=name, began=time.monotonic())
    signal.alarm(0 if name is None else CASE_SECONDS)


# Above the longest dispatch wait (120 s) and what the host allows a request besides it (30 s).
REPLY_SECONDS = 160
# Longer than the host allows a request that does not wait.
LONG_WAIT_MILLIS = 35000


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
        value = self.responses.get(timeout=REPLY_SECONDS)
        if isinstance(value, Exception):
            raise value
        assert value["id"] == self.sequence and "error" not in value, value
        return value["result"]

    def refused(self, method, params):
        self.sequence += 1
        self.send({"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params})
        value = self.responses.get(timeout=REPLY_SECONDS)
        assert not isinstance(value, Exception) and value["id"] == self.sequence and value["error"]["code"] == -32601, value

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


class Journal:
    """What a host wrote into the units file of its session directory about what the session waits on a person for."""
    def __init__(self, directory):
        self.file = Path(directory) / "units.jsonl"

    def events(self):
        return [json.loads(line) for line in self.file.read_text().splitlines()] if self.file.exists() else []

    def written(self, event):
        """Waits until the host has written `event`: it does at its next round, which comes at its claim-renewal interval."""
        deadline = time.monotonic() + 90
        while event not in self.events():
            assert time.monotonic() < deadline, (event, self.events()[-4:])
            time.sleep(0.2)


def settled_event(item, current, detail, waiter):
    """The event of an answered Question; `waiter` is the slot of the `cq wait` the host gave it to, its process identifier."""
    return {"Settled": {"end": {"item": item["id"], "title": current(item)["draft"]["title"], "status": "Answered", "detail": detail}, "waiter": waiter}}


# A host that is alive is waited for until it reaches the file sync a stall fixture latches: how long its start takes depends on the
# machine (a JVM host once had not reached it after 20 s under load). A host that has exited fails the fixture at once with what it
# wrote, and this bound only ends the wait for a host that stays alive and never arrives.
HOST_LATCH_SECONDS = 300


def latched(process, latch, name):
    """Waits until the stalled host of `process` has entered the latched file sync; `latch` is the fixture's stall directory."""
    began = time.monotonic()
    while not (latch / "entered").exists():
        exited = process.poll()
        assert exited is None, f"{name} fsync latch not reached: the host exited with {exited}: {(latch / 'stderr').read_text()[-2000:]}"
        assert time.monotonic() - began < HOST_LATCH_SECONDS, f"{name} fsync latch not reached by a live host within {HOST_LATCH_SECONDS} seconds"
        time.sleep(0.05)
    print(json.dumps({"latch": name, "seconds": round(time.monotonic() - began, 2)}), flush=True)


def main():
    signal.signal(signal.SIGALRM, _stuck)
    case("attached session")
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
    limits = {"startupMillis": "5000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144}
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
    def operator(value):
        request = urllib.request.Request(os.environ["CQ_ORIGIN"] + "/api/call", data=json.dumps(value).encode(), method="POST",
                                         headers={"Authorization": "Bearer " + os.environ["CQ_TOKEN"], "CQ-Session": os.environ["CQ_SESSION"],
                                                  "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    with (root / "host.log").open("w") as log:
        # As the generated integration starts it: with the executable `cq configure` approved for the session's wait command.
        peer = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        try:
            inventory = peer.rpc("tools/list", {})["tools"]
            assert {tool["name"] for tool in inventory} == {"session", "dispatch", "search", "read", "graph", "change", "apply", "claim", "usage"}
            context = peer.tool("session", {"Context": {}})["Context"]["value"]
            assert context["workflow"] is None and "thread metadata" in context["usageCoverage"]
            assert "Canonical argument schemas" in context["instructions"]
            # A Codex session is told to wait in a status call of the host with the longest wait, and no command of its shell is approved for it.
            assert ("call its status (Status, IntegrationStatus or CombinationStatus; repeat Revalidate) with waitMillis 120000." in context["instructions"]
                    and '`// @exec: {"yield_time_ms": 150000}`' in context["instructions"] and f"{wrapper} wait" not in context["instructions"]), context["instructions"][-1500:]
            assert not (repository / ".codex/rules").exists()
            # The waiter is approved as one command line, and the file tools for the workspaces of this state root's sessions and nothing else under it.
            workspaces = f"/{(root / 'sessions').resolve()}/*/workspaces/*/tree/**"
            assert json.loads((repository / ".claude/settings.local.json").read_text())["permissions"]["allow"] == [
                f"Bash({wrapper} wait)", f"Edit({workspaces})", f"Read({workspaces})"]
            project = context["project"]["project"]
            standing = "Governor: preserve the operator's selected scope."
            operator({"Requirements": {"input": {"project": project, "action": {"Replace": {"expected": {"value": "0"}, "text": standing}}}}})
            # The children of every session of this fixture run the Codex-shaped fixture harness, whichever harness governs.
            save_agents(operator, project, role_agents(json.loads(settings.read_text())["harnesses"], dict.fromkeys(ROLES, "Codex")))
            assert standing in peer.tool("session", {"Context": {}})["Context"]["value"]["instructions"]
            peer.tool("initialize", {}, denied=True)
            peer.tool("workspace", {"Read": {"path": ".", "offset": 0, "limit": 20}}, denied=True)
            selection = {"request": identity(), "roots": [], "work": {"Explorer": {"mode": "Investigate"}}, "guidance": [], "artifacts": [], "previous": None, "limits": limits}
            peer.tool("dispatch", {"Select": {"request": selection}}, denied=True)
            first = {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}
            activated = peer.tool("session", first)["Workflow"]["value"]
            begin_text = activated["instructions"]["Text"]["value"]
            assert standing in begin_text and activated["id"] == first["Workflow"]["id"] and set(activated) == {"id", "request", "instructions", "subject", "cycle", "mode"}, activated
            assert activated["mode"] == "Rigorous" and "Process mode of this project: Rigorous." in begin_text
            # The reply sends back nothing the session wrote in the call, and Context names the active workflow without its text.
            active = peer.tool("session", {"Context": {}})["Context"]["value"]
            assert active["workflow"] == {"id": activated["id"], "request": activated["request"], "cycle": None, "mode": "Rigorous"} and active["mode"] == "Rigorous", active
            assert begin_text not in json.dumps(peer.traffic[-1]["result"]) and "operator requirements text" not in json.dumps([activated, active])
            # The activation repeated returns its receipt as it was: the session may not have received the first reply.
            assert peer.tool("session", first)["Workflow"]["value"] == activated
            whole = peer.tool("session", {"Instructions": {}})["Instructions"]["value"]
            assert whole["context"]["instructions"] == begin_text and whole["operatorRequirements"] == first["Workflow"]["operatorRequirements"], whole
            peer.tool("session", {"Workflow": {"id": first["Workflow"]["id"], "request": {"Advance": {"roots": [], "through": "Explore"}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}, denied=True)
            draft = {"title": "Attached investigation", "body": "Investigate the fixture", "labels": ["proposal-fixture"], "archived": False,
                     "content": {"Task": {"status": "Ready", "acceptance": ["Report findings"], "result": None, "validation": []}}, "citations": []}
            created = peer.tool("change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": draft}}], "fences": [], "reason": "Attached fixture"}})["Changed"]["ack"]["items"][0]
            selection["roots"] = [created["id"]]
            choice, = peer.tool("dispatch", {"Select": {"request": selection}})["Selection"]["value"]["choices"]
            claim = peer.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [created["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            next_scope = {"Workflow": {"id": identity(), "request": {"Advance": {"roots": [created["id"]], "through": "Explore"}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}
            peer.tool("session", next_scope)
            peer.tool("dispatch", {"Select": {"request": selection}}, denied=True)
            peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "fence": claim["fence"]}}, denied=True)
            assert peer.tool("session", first)["Workflow"]["value"] == activated
            assert peer.tool("session", {"Context": {}})["Context"]["value"]["workflow"]["id"] == next_scope["Workflow"]["id"]
            selection["request"] = identity()
            choice, = peer.tool("dispatch", {"Select": {"request": selection}})["Selection"]["value"]["choices"]
            # A choice does not send back the limits its request stated; the retained selection evidence keeps them.
            assert set(choice) == {"id", "work", "members", "guidance", "artifacts", "previous", "cohort", "reason", "witness"}, choice
            retained = json.loads((Path(context["directory"]) / "selections" / (selection["request"]["value"] + ".json")).read_text())
            kept, = retained["decision"]["choices"]
            assert kept == {**choice, "limits": limits} and retained["request"] == selection, retained
            started = peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "fence": claim["fence"]}})["Status"]["value"]
            # The asynchronous child must finish before a different workflow is admitted.
            if started["phase"] in ["Preparing", "Running"]:
                peer.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}, denied=True)
            for _ in range(6):
                status = peer.tool("dispatch", {"Status": {"attempt": started["attempt"], "waitMillis": 120000}})["Status"]["value"]
                if status["phase"] not in ["Preparing", "Running", "Stopping", "Validating", "Publishing"]:
                    break
            assert status["phase"] == "Completed" and status["usageDelivered"] and status["result"], status
            # `cq wait` reads the session directory alone and reports the same end, by name and after the fact.
            waited = json.loads(cli(["wait", "--session", context["directory"], "--attempt", started["attempt"]["value"], "--json"]))["Ended"]
            ended, = waited["units"]
            assert ended == {"unit": {"kind": "Attempt", "id": started["attempt"]["value"], "members": [created["id"]]}, "phase": "Completed",
                             "next": status["next"], "blocker": None} and waited["active"] == [], waited
            assert cli(["wait", "--session", context["directory"]]) == "No child attempt, integration, combination or revalidation of this session is active\n"
            # The command the session is given names no directory: it finds the session of the one host of this checkout that runs.
            assert cli(["wait"]) == "No child attempt, integration, combination or revalidation of this session is active\n"
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
    totals = json.loads(cli(["status", "--session", context["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert totals["attempts"]["unknown"] == "1" and totals["attempts"]["running"] == "0" and totals["attemptsWithoutMeters"] == "1", totals
    print(json.dumps({"attachedSession": context["session"], "child": status, "usage": totals, "activationFence": True, "tokenFile": True, "replay": True}))

    case("session records")
    # D160: a harness that opens the connection, lists the tools and closes it has done no governing work. Its host tells the server of
    # no attempt and records no session, so nothing is left to deliver, recover or upload.
    def session_directories():
        return {path for path in (root / "sessions").iterdir()}
    def attempts(directory):
        return json.loads(cli(["status", "attempts", "--session", directory.name, "--json"]))["UsageAttempts"]["page"]["entries"]
    def entries(directory):
        return sorted(str(path.relative_to(directory)) for path in directory.rglob("*"))
    def registered():
        return [entry["attempt"]["id"] for entry in json.loads(cli(["status", "attempts", "--json"]))["UsageAttempts"]["page"]["entries"]]
    known, before_idle = session_directories(), registered()
    with (root / "idle-host.log").open("w") as log:
        idle = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        try:
            assert idle.rpc("tools/list", {})["tools"] == inventory and idle.rpc("ping", {}) == {}
            idle.refused("resources/list", {})
        finally:
            idle.close()
    assert session_directories() == known and registered() == before_idle, "A host that recorded no session left a directory or an attempt"
    # A host killed before any tool call leaves the directory with its lock; nothing is recorded in it, and the next host that records a session removes it.
    with (root / "idle-host.log").open("a") as log:
        idle = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        idle.rpc("tools/list", {})
        idle.process.kill()
        idle.process.wait(timeout=5)
    opened, = session_directories() - known
    assert attempts(opened) == [] and entries(opened) == ["journal", "journal/owner.lock", "journal/started-by"], (attempts(opened), entries(opened))
    assert cli(["job", "upload", "--session", str(opened)]) == f"No session is recorded in {opened}; there is nothing to deliver\n"
    # A session that works is recorded at its first tool call, whatever the call: here one that takes a claim. Its host is killed at once.
    member = operator({"Change": {"input": {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": draft}}], "fences": [],
                                                                         "reason": "Attached fixture"}}}})["Changed"]["ack"]["items"][0]["id"]
    def killed_after(first, denied):
        before = session_directories()
        with (root / "killed-host.log").open("a") as log:
            host = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
            try:
                reply = host.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [member], "durationMillis": "180000"}}}, denied)
                first(reply)
            finally:
                host.process.kill()
                host.process.wait(timeout=5)
        directory, = session_directories() - before
        started, = attempts(directory)
        assert started["outcome"] is None and not started["observed"] and started["attempt"]["role"] == "Governor", started
        assert {"run.json", "settings.json", "owner.json", "waiters.lock", "delivery/000000.json", "delivery/000000.ack", "journal/owner.lock"} <= set(entries(directory)), entries(directory)
        return directory
    earlier = killed_after(lambda reply: reply["Claimed"]["claim"], False)
    # The killed host's claim stands until its lease ends: the next host is refused the member. That refused call is its first, and
    # records it: its startup recovery then closes the killed session, which leaves the upload nothing to deliver.
    def recovered(reply):
        assert "Failed" in reply, reply
        began = time.monotonic()
        while not (earlier / "recovery.json").exists() or opened.exists():
            assert time.monotonic() - began < HOST_LATCH_SECONDS, "The later host did not recover the killed session"
            time.sleep(0.05)
    later = killed_after(recovered, True)
    closed, = attempts(earlier)
    assert closed["outcome"]["value"]["state"] == "Unknown" and json.loads((earlier / "recovery.json").read_text())["outcome"] == "Recovered", closed
    assert "Acknowledged 0" in cli(["job", "upload", "--session", str(earlier)])
    assert "Acknowledged 1" in cli(["job", "upload", "--session", str(later)])
    uploaded, = attempts(later)
    assert uploaded["outcome"]["value"]["state"] == "Unknown" and "Acknowledged 0" in cli(["job", "upload", "--session", str(later)]), uploaded
    assert not opened.exists() and attempts(opened) == []
    print(json.dumps({"connectionOnly": "no attempt and no session record", "killedAfterFirstCall": ["recovered", "uploaded"]}))

    def operate(mutations):
        return operator({"Change": {"input": {"project": project, "change": {"request": identity(), "mutations": mutations, "fences": [], "reason": "Question fixture"}}}})["Changed"]["ack"]["items"]
    def current(item):
        return operator({"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": item["id"]}}}}})["Detail"]["view"]["item"]

    case("driven session")
    # A driven session: the hook entry points hold the operator credential; the attached session binds, activates the issued directive and
    # writes inside its cycle. Its first out-of-set write is rejected and stops the driver.
    key = {"harness": "Codex", "session": "attached-fixture-session"}
    def control(origin, action):
        return operator({"Driver": {"input": {"project": project, "request": {"Control": {"key": key, "origin": origin, "action": action}}}}})["Driver"]["reply"]
    def members(value):
        return [(next(iter(entry["member"])), entry["settled"]) for entry in value["cycle"]["lineage"]]
    with (root / "driver-host.log").open("w") as log:
        driven = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        try:
            driven_context = driven.tool("session", {"Context": {}})["Context"]["value"]
            driven_session = driven_context["session"]
            driven_directory = Path(driven_context["directory"])
            assert driven.tool("session", {"Driver": {}}) == {"Driver": {"reply": {"Status": {"value": None}}}}
            def change(mutations, fences, denied=False):
                return driven.tool("change", {"project": project, "change": {"request": identity(), "mutations": mutations, "fences": fences, "reason": "Driven fixture"}}, denied=denied)
            target, outsider = change([{"Create": {"draft": draft}}, {"Create": {"draft": draft}}], [])["Changed"]["ack"]["items"]
            # A long attached session: more workflow activations than one drive issues directives do not exhaust the host before the drive starts.
            for _ in range(65):
                assert driven.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Undriven fixture", "token": None}})["Workflow"]["value"]["cycle"] is None
            started = control("UserPromptSubmit", {"Start": {"target": {"Inline": {"targets": [target["id"]], "through": "Explore"}}, "attached": None}})["Started"]
            assert started["status"]["state"] == "Binding" and [member["item"]["id"] for member in started["preview"]["advanceable"]] == [target["id"]], started
            driven.tool("session", {"Bind": {"token": identity()}}, denied=True)
            bound = driven.tool("session", {"Bind": {"token": started["bind"]}})["Driver"]["reply"]["Bound"]["status"]
            assert bound["state"] == "On" and bound["attached"] == driven_session, bound
            issued = control("Stop", {"Continue": {"waiting": False}})["Continue"]["directive"]
            reference = "T" + target["id"]["number"]
            words = issued["text"].split(" ")
            assert words[:6] == ["$cq-advance", "--roots", reference, "--through", "explore", "--start-token"] and len(words) == 7, issued
            advance = {"Advance": {"roots": [target["id"]], "through": "Explore"}}
            # The token travels only in Workflow.token: requirements text that carries it is refused before the token is presented, so a clean retry activates.
            leaking = {"Workflow": {"id": identity(), "request": advance, "operatorRequirements": issued["text"], "token": {"Start": {"token": {"value": words[6]}}}}}
            assert "pass the token only in Workflow.token" in json.dumps(driven.tool("session", leaking, denied=True))
            directed = {"Workflow": {"id": identity(), "request": advance, "operatorRequirements": f"Driven fixture: advance {reference} through explore", "token": {"Start": {"token": {"value": words[6]}}}}}
            run = driven.tool("session", directed)["Workflow"]["value"]
            assert run["cycle"] == issued["cycle"] and "Text" in run["instructions"], run
            assert driven.tool("session", directed)["Workflow"]["value"] == run, run
            assert driven.tool("session", {"Context": {}})["Context"]["value"]["workflow"]["cycle"] == issued["cycle"]
            driven_choice, = driven.tool("dispatch", {"Select": {"request": {**selection, "request": identity(), "roots": [target["id"]]}}})["Selection"]["value"]["choices"]
            driven_claim = driven.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [target["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            child = driven.tool("dispatch", {"StartChoice": {"choice": driven_choice["id"], "fence": driven_claim["fence"]}})["Status"]["value"]
            registered = control("StatusLine", {"Status": {}})["Status"]["value"]
            assert [name for name, _ in members(registered)] == ["Run", "Claim", "Request", "Attempt"] and registered["cycle"]["run"] == directed["Workflow"]["id"], registered
            for _ in range(6):
                child = driven.tool("dispatch", {"Status": {"attempt": child["attempt"], "waitMillis": 120000}})["Status"]["value"]
                if child["phase"] not in ["Preparing", "Running", "Stopping", "Validating", "Publishing"]:
                    break
            assert child["phase"] == "Completed", child
            deadline = time.monotonic() + 30
            # The attempt and the request of its unit are settled by two followers of the host, in either order: both are awaited.
            def unsettled():
                standing = dict(members(control("StatusLine", {"Status": {}})["Status"]["value"]))
                return standing["Attempt"] is not True or standing["Request"] is not True
            while unsettled() and time.monotonic() < deadline:
                time.sleep(0.2)
            settled = control("StatusLine", {"Status": {}})["Status"]["value"]
            assert members(settled) == [("Run", False), ("Claim", True), ("Request", True), ("Attempt", True)] and settled["activeChildren"] == 0, settled
            revised = change([{"Replace": {"id": target["id"], "expected": target["revision"], "draft": {**draft, "title": "Advanced inside the cycle"}}}], [driven_claim["fence"]])
            assert revised["Changed"]["ack"]["items"][0]["id"] == target["id"]
            assert [name for name, _ in members(control("StatusLine", {"Status": {}})["Status"]["value"])][-1] == "Change"
            rejected = change([{"Replace": {"id": outsider["id"], "expected": outsider["revision"], "draft": {**draft, "title": "Outside the workset"}}}], [], denied=True)
            assert "out-of-set change" in rejected["Failed"]["fault"]["Denied"]["message"], rejected
            unchanged = driven.tool("read", {"project": project, "selection": {"ItemDetail": {"id": outsider["id"]}}})["Detail"]["view"]["item"]
            assert unchanged["revision"] == outsider["revision"] and unchanged["draft"]["title"] == draft["title"], unchanged
            stop = control("Stop", {"Continue": {"waiting": False}})["Stop"]
            assert stop["stopped"]["reason"] == "Failure" and "out-of-set change" in stop["stopped"]["detail"] and stop["status"]["state"] == "Off", stop
            own = driven.tool("session", {"Driver": {}})["Driver"]["reply"]["Status"]["value"]
            assert own["state"] == "Off" and own["stopped"] == stop["stopped"], own
            driven.refused("cq/driver", {"Status": {"session": "attached-fixture-session"}})
            assert control("Stop", {"Continue": {"waiting": False}})["Stop"]["stopped"]["reason"] == "Off"
            change([{"Replace": {"id": outsider["id"], "expected": outsider["revision"], "draft": {**draft, "title": "Driver off: written as before"}}}], [])
            # The hook entry points as Codex runs them: the commands `cq configure` generated, the hook payload on stdin, the hook output on stdout.
            generated = json.loads((repository / ".codex/hooks.json").read_text())["hooks"]
            def hook(event, session, **fields):
                installed, = [handler["command"] for group in generated[event] for handler in group["hooks"]]
                assert shlex.split(installed) == [str(wrapper), "hook", "codex", event], installed
                payload = {"turn_id": str(uuid.uuid4()), "cwd": str(repository), "hook_event_name": event, "model": "fixture-model", "permission_mode": "default", **fields}
                if session is not None:
                    payload["session_id"] = session
                result = subprocess.run(shlex.split(installed), cwd=repository, env=env, input=json.dumps(payload), capture_output=True, text=True, timeout=60)
                assert result.returncode == 0, result
                return json.loads(result.stdout) if result.stdout else None
            hooked = "attached-fixture-hook-session"
            assert hook("UserPromptSubmit", hooked, prompt="Reply with exactly: HELLO") is None
            assert hook("Stop", hooked, stop_hook_active=False, last_assistant_message="HELLO") is None
            assert "rejected: Drive targets are empty" in hook("UserPromptSubmit", hooked, prompt="$cq-drive through=explore")["systemMessage"]
            # Without its fixture label the task's Probe child keeps running until it is cancelled.
            change([{"Replace": {"id": target["id"], "expected": revised["Changed"]["ack"]["items"][0]["revision"], "draft": {**draft, "labels": [], "title": "Probed while driven"}}}], [driven_claim["fence"]])
            driving = hook("UserPromptSubmit", hooked, prompt=f"$cq-drive {reference} through=explore")
            offered = driving["hookSpecificOutput"]["additionalContext"]
            assert driving["hookSpecificOutput"]["hookEventName"] == "UserPromptSubmit" and offered.startswith(f"CQ driver drive-start: CQ driver binding: {reference} through explore"), driving
            printed, = [line for line in offered.splitlines() if '{"Bind":' in line]
            hook_bound = driven.tool("session", json.loads(printed[printed.index('{"Bind":'):]))["Driver"]["reply"]["Bound"]["status"]
            assert hook_bound["state"] == "On" and hook_bound["key"] == {"harness": "Codex", "session": hooked} and hook_bound["attached"] == driven_session, hook_bound
            blocked = hook("Stop", hooked, stop_hook_active=False, last_assistant_message="Bound.")
            assert blocked["decision"] == "block" and blocked["reason"].splitlines()[-1].startswith(f"$cq-advance --roots {reference} --through explore --start-token "), blocked
            # The hook, as installed, finds this session's host through the checkout: a stop while a child runs is blocked with the order
            # to wait in a status call.
            started_token = blocked["reason"].splitlines()[-1].split(" ")[-1]
            driven.tool("session", {"Workflow": {"id": identity(), "request": advance, "operatorRequirements": "", "token": {"Start": {"token": {"value": started_token}}}}})
            def probed():
                choice, = driven.tool("dispatch", {"Select": {"request": {**selection, "request": identity(), "roots": [target["id"]], "work": {"Worker": {"mode": "Probe"}}}}})["Selection"]["value"]["choices"]
                started = driven.tool("dispatch", {"StartChoice": {"choice": choice["id"], "fence": driven_claim["fence"]}})["Status"]["value"]
                # The unit's start is in the session's event file when the starting call has returned: a waiter started from then on finds it.
                events = [json.loads(line) for line in (driven_directory / "units.jsonl").read_text().splitlines()]
                assert events[-1] == {"Started": {"unit": {"kind": "Attempt", "id": started["attempt"]["value"], "members": [target["id"]]}}}, events
                return started
            def watched(arguments):
                """Starts a `cq wait` and returns it once it watches the driven session: it holds a shared lock on the byte of its own
                slot of the session's `waiters.lock` for as long as it runs, which refuses an exclusive one, and reads the session's
                units in the turn in which it takes it. The slot is the process identifier of the waiter and follows the turn byte;
                the lock of another waiter says nothing about this one. An installed command runs the waiter as a child of its
                launcher, so the waiter is looked for among the started process and its descendants; its slot is left in `slot`."""
                process = subprocess.Popen([str(wrapper), "wait"] + arguments, cwd=repository, env=env, stdout=subprocess.PIPE, text=True)
                def family(pid):
                    try:
                        children = [int(child) for task in Path(f"/proc/{pid}/task").glob("*/children") for child in task.read_text().split()]
                    except OSError:
                        # The process ended while it was read.
                        children = []
                    return [pid] + [member for child in children for member in family(child)]
                with (driven_directory / "waiters.lock").open("r+") as stream:
                    while True:
                        assert process.poll() is None, ("The wait command ended while the child ran", process.communicate()[0])
                        for pid in family(process.pid):
                            try:
                                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 1 + pid)
                            except OSError:
                                process.slot = pid
                                return process
                            fcntl.lockf(stream, fcntl.LOCK_UN, 1, 1 + pid)
                        time.sleep(0.05)
            probe = probed()
            ordered = hook("Stop", hooked, stop_hook_active=False, last_assistant_message="Started.")
            assert ordered is not None and ordered.get("decision") == "block" and ordered["reason"].startswith(
                f"CQ driver: work of this session still runs (attempt {probe['attempt']['value']} on {reference}). Do not end your turn"), ordered
            assert "with the CQ dispatch tool (Status, IntegrationStatus or CombinationStatus) with waitMillis 120000" in ordered["reason"] and f"{wrapper} wait" not in ordered["reason"], ordered
            # The checkout's wait command still waits on this session without being told its directory, for a harness that uses it.
            waiter = watched([])
            driven.tool("dispatch", {"Cancel": {"attempt": probe["attempt"]}})
            reported, _ = waiter.communicate(timeout=60)
            assert waiter.returncode == 0 and reported.startswith(f"attempt {probe['attempt']['value']} on {reference} ended: "), (waiter.returncode, reported)
            assert hook("UserPromptSubmit", hooked, prompt="$cq-park")["systemMessage"] == f"CQ driver park: CQ driver parked: {reference} through explore"
            assert hook("Stop", hooked, stop_hook_active=True, last_assistant_message="Parked.") is None
            assert hook("Stop", None, stop_hook_active=False) == {"systemMessage": "CQ Stop hook error: Driver session key is missing; no default session is used"}

            case("settled questions of a driven session")
            # D164: a session learns that the operator settled what it waits on. The host reads what the session waits on at its
            # claim-renewal interval, so each step that depends on it waits for the host's next round.
            question = {"title": "Which way", "body": "Fixture question", "labels": [], "archived": False, "citations": [],
                        "content": {"Question": {"status": "Open", "prompt": "Which way?", "context": "Fixture", "alternatives": [], "recommendation": None, "answer": None}}}
            def answer(item, text):
                held = current(item)
                operate([{"Replace": {"id": item["id"], "expected": held["revision"], "draft": {**held["draft"], "content": {"Question": {**held["draft"]["content"]["Question"], "status": "Answered", "answer": text}}}}}])
            said = "CQ: a person settled what this session waits on:"
            act = "Read each of them with the CQ read tool (ItemDetail) and act on it before you end your turn."
            journal = Journal(driven_directory)
            def read(item):
                driven.tool("read", {"project": project, "selection": {"ItemDetail": {"id": item["id"]}}})
            # A drive on a Task that a Question of the operator gates stops for user input at once, and rests.
            gate, gated = operate([{"Create": {"draft": {**question, "title": "Gate"}}}, {"Create": {"draft": {**draft, "labels": [], "title": "Gated task"}}}])
            operate([{"Reference": {"source": gated["id"], "expectedSource": gated["revision"], "relation": "BlockedBy", "target": gate["id"], "expectedTarget": gate["revision"], "present": True}}])
            asking, gated_reference, gate_reference = "attached-fixture-question-session", "T" + gated["id"]["number"], "Q" + gate["id"]["number"]
            offered = hook("UserPromptSubmit", asking, prompt=f"$cq-drive {gated_reference} through=explore")["hookSpecificOutput"]["additionalContext"]
            printed, = [line for line in offered.splitlines() if '{"Bind":' in line]
            driven.tool("session", json.loads(printed[printed.index('{"Bind":'):]))
            rested = hook("Stop", asking, stop_hook_active=False, last_assistant_message="Bound.")
            awaiting = f"Awaiting the user on {gate_reference}; the driver never answers questions or infers approval"
            assert rested == {"systemMessage": f"CQ driver stopped (user input required): {awaiting}"}, rested
            # The session of a resting drive does not answer what the drive waits for.
            refused = driven.tool("change", {"project": project, "change": {"request": identity(), "fences": [], "reason": "Driven fixture", "mutations": [
                {"Replace": {"id": gate["id"], "expected": current(gate)["revision"], "draft": {**current(gate)["draft"],
                             "content": {"Question": {**question["content"]["Question"], "status": "Answered", "answer": "The session's own"}}}}}]}}, denied=True)
            assert "The CQ driver never answers Questions" in json.dumps(refused), refused
            # The session records a Question of its own and advances the gated Task: at the host's next round it waits on both, and a
            # stop with nothing new is allowed.
            own, = change([{"Create": {"draft": question}}], [])["Changed"]["ack"]["items"]
            driven.tool("session", {"Workflow": {"id": identity(), "request": {"Advance": {"roots": [gated["id"]], "through": "Explore"}}, "operatorRequirements": "Question fixture", "token": None}})
            journal.written({"Watching": {"item": gate["id"]}})
            journal.written({"Watching": {"item": own["id"]}})
            assert hook("Stop", asking, stop_hook_active=False, last_assistant_message="Asked.") is None
            # Answered with no waiter running: the host's next round writes the end, and the next stop says it once.
            answer(own, "The second\n\"way\"")
            journal.written(settled_event(own, current, "The second\n\"way\"", None))
            told = hook("Stop", asking, stop_hook_active=False, last_assistant_message="Done.")
            assert told == {"decision": "block", "reason": "\n".join([said, f'- question Q{own["id"]["number"]} "Which way" answered: "The second \\"way\\""', act])}, told
            assert hook("Stop", asking, stop_hook_active=True, last_assistant_message="Again.") is None
            read(own)
            assert journal.events()[-1] == {"Released": {"item": own["id"]}}, journal.events()[-2:]
            case("an end given to one of two waiters")
            # Answered while two `cq wait` run on the session: both wait although the host works on nothing, the host gives the end to
            # one of them, which reports it and ends, and the other stays. The session, once it has read it, is told by no stop.
            waited, = change([{"Create": {"draft": {**question, "title": "Waited for"}}}], [])["Changed"]["ack"]["items"]
            waiters = [watched([]), watched([])]
            answer(waited, "Yes")
            given, kept = sorted(waiters, key=lambda process: process.slot)
            reported, _ = given.communicate(timeout=90)
            assert given.returncode == 0 and reported == f'question Q{waited["id"]["number"]} "Waited for" answered: "Yes"\n', (given.returncode, reported)
            assert settled_event(waited, current, "Yes", str(given.slot)) in journal.events()
            time.sleep(2)
            assert kept.poll() is None, ("The waiter the end was not given to ended as well", kept.communicate()[0])
            kept.terminate()
            kept.wait(timeout=30)
            # Unread, it is said once more at the next turn end, and by a waiter that starts later; read, by nothing.
            reminded = hook("Stop", asking, stop_hook_active=False, last_assistant_message="Woken.")
            assert reminded["reason"].splitlines()[1] == f'- question Q{waited["id"]["number"]} "Waited for" answered: "Yes"', reminded
            again = subprocess.run([str(wrapper), "wait"], cwd=repository, env=env, capture_output=True, text=True, timeout=60)
            assert (again.returncode, again.stdout) == (0, reported), again
            read(waited)
            assert hook("Stop", asking, stop_hook_active=False, last_assistant_message="Read.") is None
            # The answer the drive rested on: one stop announces it and carries the start directive of the drive, which is on again.
            answer(gate, "This way")
            journal.written(settled_event(gate, current, "This way", None))
            continued = hook("Stop", asking, stop_hook_active=False, last_assistant_message="Done.")["reason"].splitlines()
            assert continued[:3] == [said, f'- question {gate_reference} "Gate" answered: "This way"', act] and "CQ driver: the user settled what this drive waited for; it continues" in continued, continued
            assert continued[-1].startswith(f"$cq-advance --roots {gated_reference} --through explore --start-token "), continued
            assert hook("UserPromptSubmit", asking, prompt="$cq-park")["systemMessage"] == f"CQ driver park: CQ driver parked: {gated_reference} through explore"
            status_line = json.loads((repository / ".claude/settings.local.json").read_text())["statusLine"]["command"]
            shown = subprocess.run(shlex.split(status_line), cwd=repository, env=env, input=json.dumps({"session_id": "attached-fixture-status"}), capture_output=True, text=True, timeout=60)
            assert shlex.split(status_line) == [str(wrapper), "hook", "claude", "StatusLine"] and shown.returncode == 0 and shown.stdout == "CQ driver off\n", shown
        finally:
            driven.close()
    print(json.dumps({"drivenSession": driven_session, "cycle": issued["cycle"], "lineage": members(settled), "stop": stop["stopped"]}))

    case("settled question of a session that never drove")
    # D164: a session no driver ever knew. The hook finds it by the host that the process it descends from started, which is this
    # fixture, the owner of the host, and gives its ends to the harness session that found the host first. A hook that does not
    # descend from the owner, and one with another harness session identifier, say nothing and let the stop through.
    with (root / "undriven-host.log").open("w") as log:
        alone = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        try:
            alone_journal = Journal(alone.tool("session", {"Context": {}})["Context"]["value"]["directory"])
            undriven = "attached-fixture-undriven-session"
            asked, = alone.tool("change", {"project": project, "change": {"request": identity(), "fences": [], "reason": "Undriven fixture",
                                           "mutations": [{"Create": {"draft": {**question, "title": "Asked without a drive"}}}]}})["Changed"]["ack"]["items"]
            alone_journal.written({"Watching": {"item": asked["id"]}})
            assert hook("Stop", undriven, stop_hook_active=False, last_assistant_message="Asked.") is None
            answer(asked, "Go on")
            alone_journal.written(settled_event(asked, current, "Go on", None))
            starter = json.loads((Path(alone_journal.file).parent / "journal" / "started-by").read_text())["pid"]
            if os.environ.get("CQ_ATTACHED_LAUNCHER") is not None:
                # The hook finds the host of a session that never drove by the process that started the host: the hook's own harness,
                # when the harness starts `cq host` itself. Under the launcher of the installed check that process is the launcher.
                assert starter != os.getpid(), starter
                print(json.dumps({"stopHookOfUndrivenSession": "not exercised: the check's launcher stands between the owner and the host, so the host's starter is the launcher"}), flush=True)
            else:
                assert starter == os.getpid(), starter
                payload = {"session_id": undriven, "turn_id": str(uuid.uuid4()), "cwd": str(repository), "hook_event_name": "Stop", "model": "fixture-model",
                           "permission_mode": "default", "stop_hook_active": False}
                (root / "orphan-input.json").write_text(json.dumps(payload))
                # The hook's parent is a shell whose own parent has ended, so its ancestors are that shell and whatever adopts orphans.
                stop_command, = [handler["command"] for group in generated["Stop"] for handler in group["hooks"]]
                subprocess.run(["sh", "-c", f"({stop_command} < {shlex.quote(str(root / 'orphan-input.json'))} > {shlex.quote(str(root / 'orphan-output'))}; "
                                            f"echo $? > {shlex.quote(str(root / 'orphan-exit'))}) &"], cwd=repository, env=env, check=True, timeout=60)
                deadline = time.monotonic() + 60
                while not (root / "orphan-exit").exists() or not (root / "orphan-exit").read_text().endswith("\n"):
                    assert time.monotonic() < deadline, "The hook without the harness among its ancestors did not end"
                    time.sleep(0.1)
                assert ((root / "orphan-exit").read_text(), (root / "orphan-output").read_text()) == ("0\n", ""), (root / "orphan-output").read_text()
                assert hook("Stop", "attached-fixture-another-session-id", stop_hook_active=False, last_assistant_message="Done.") is None
                alone_told = hook("Stop", undriven, stop_hook_active=False, last_assistant_message="Done.")
                assert alone_told == {"decision": "block", "reason": "\n".join([said, f'- question Q{asked["id"]["number"]} "Asked without a drive" answered: "Go on"', act])}, alone_told
                assert hook("Stop", undriven, stop_hook_active=True, last_assistant_message="Read.") is None
        finally:
            alone.close()

    case("Pi session")
    with (root / "pi-host.log").open("w") as log:
        pi = Peer(command + ["host", "pi"], repository, env, log)
        try:
            pi_context = pi.tool("session", {"Context": {}})["Context"]["value"]
            # A Pi session starts no waiter: its extension asks the host for the session directory and waits itself.
            assert "CQ sends you a message that begins `CQ:` when a unit ends" in pi_context["instructions"] and " wait --session " not in pi_context["instructions"]
            assert pi.rpc("cq/session", {}) == {"directory": pi_context["directory"]}
            # The Pi extension passes on the text block alone, so it carries the payload.
            delivered = pi.rpc("tools/call", {"name": "session", "arguments": {"Driver": {}}})
            text, = [part["text"] for part in delivered["content"]]
            assert json.loads(text) == delivered["structuredContent"] == {"Driver": {"reply": {"Status": {"value": None}}}}, delivered
            sample = {"sequence": "1", "session": "fixture-native-pi", "turn": "1", "provider": "fixture-provider", "model": "fixture-model",
                      "timestamp": "1000", "responseId": "response-1", "stopReason": "stop", "input": "10", "output": "3", "cacheRead": "2",
                      "cacheWrite": "0", "reasoning": None, "totalTokens": "15", "costUSD": {"value": "0.001"}}
            pi.rpc("cq/piUsage", sample)
            pi.rpc("cq/piUsage", sample)
            # The Pi extension drives through its attached host, which supplies this attached session at drive-start.
            pi_key = "fixture-native-pi"
            assert pi.rpc("cq/driver", {"Status": {"session": pi_key}}) == {"Status": {"value": None}}
            assert "Invalid" in pi.rpc("cq/driver", {"Start": {"session": pi_key, "input": "through=explore"}})["Failed"]["fault"]
            assert "Invalid" in pi.rpc("cq/driver", {"Start": {"session": "", "input": reference + " through=explore"}})["Failed"]["fault"]
            pi_started = pi.rpc("cq/driver", {"Start": {"session": pi_key, "input": reference + " through=explore"}})["Started"]
            assert pi_started["bind"] is None and pi_started["status"]["state"] == "On" and pi_started["status"]["attached"] == pi_context["session"], pi_started
            pi_directive = pi.rpc("cq/driver", {"Continue": {"session": pi_key, "waiting": False}})["Continue"]["directive"]
            assert pi_directive["text"].startswith("/cq:advance --roots " + reference + " --through explore --start-token "), pi_directive
            assert pi.tool("session", {"Driver": {}})["Driver"]["reply"]["Status"]["value"]["cycle"]["id"] == pi_directive["cycle"]
            pi_parked = pi.rpc("cq/driver", {"Park": {"session": pi_key}})["Parked"]
            assert pi_parked["status"]["state"] == "Off" and pi_parked["status"]["stopped"]["reason"] == "Parked", pi_parked
            assert pi.rpc("cq/driver", {"Continue": {"session": pi_key, "waiting": False}})["Stop"]["stopped"]["reason"] == "Off"
        finally:
            pi.close()
    pi_totals = json.loads(cli(["status", "--session", pi_context["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert pi_totals["unattributed"]["total"]["known"] == "15" and pi_totals["incompleteMeters"] == "1", pi_totals
    assert "Acknowledged 0" in cli(["job", "upload", "--session", pi_context["directory"]])
    print(json.dumps({"attachedPiUsage": "deduplicated-partial", "integrationExports": ["claude", "codex", "pi"]}))
    case("Codex usage")

    codex_home = root / "codex-home"
    native_day = codex_home / "sessions/2026/09/28"
    native_day.mkdir(parents=True)
    thread = "01a0ea30-6d5b-7581-8c72-83117c61ac2d"
    turn = "01a0ea30-6ddb-7c62-997e-64936096283b"
    rollout = native_day / f"rollout-fixture-{thread}.jsonl"
    def append_native(value):
        with rollout.open("a") as stream:
            stream.write(json.dumps(value) + "\n")
    append_native({"type": "session_meta", "payload": {"id": thread, "cli_version": "0.156.1", "model_provider": "openai"}})
    append_native({"type": "turn_context", "payload": {"turn_id": turn, "model": "fixture-model"}})
    metadata = {"threadId": thread, "x-codex-turn-metadata": {"thread_id": thread, "codex_version": "0.156.1"}}
    with (root / "codex-usage-host.log").open("w") as log:
        observer = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, {**env, "CODEX_HOME": str(codex_home)}, log)
        try:
            response = observer.rpc("tools/call", {"name": "session", "arguments": {"Context": {}}, "_meta": metadata})
            assert not response["isError"], response
            # Codex receives the payload once, as structured content; the text block only points to it.
            assert response["content"] == [{"type": "text", "text": "The result is in structuredContent."}], response["content"]
            observed = response["structuredContent"]["Context"]["value"]
            assert thread in observed["usageCoverage"], observed["usageCoverage"]
            sample = {"type": "token_usage_record", "ordinal": 1, "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      "payload": {"thread_id": thread, "turn_id": turn, "response_id": "fixture-response", "usage": {
                          "input_tokens": 100, "cached_input_tokens": 20, "cache_write_input_tokens": 0,
                          "output_tokens": 30, "reasoning_output_tokens": 8, "total_tokens": 130},
                          "turn_token_usage": {"input_tokens": 90000}, "thread_token_usage": {"input_tokens": 90000}}}
            append_native(sample)
            append_native({**sample, "ordinal": 2})
        finally:
            observer.close()
    observed_directory = Path(observed["directory"])
    assert len(list((observed_directory / "codex-usage/samples").glob("*/sample.json"))) == 1
    observed_totals = json.loads(cli(["status", "--session", observed["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert observed_totals["unattributed"]["total"]["known"] == "130" and observed_totals["incompleteMeters"] == "1", observed_totals
    assert observed_totals["attemptsWithoutMeters"] == "0" and observed_totals["attempts"]["unknown"] == "1", observed_totals
    ack, = (observed_directory / "codex-usage/samples").glob("*/delivery/final/*.ack")
    ack.unlink()
    assert "Acknowledged 1" in cli(["job", "upload", "--session", str(observed_directory)])
    assert "Acknowledged 0" in cli(["job", "upload", "--session", str(observed_directory)])
    after = json.loads(cli(["status", "--session", observed["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert after == observed_totals
    print(json.dumps({"attachedCodexUsage": "native-metadata-correlated-deduplicated-replayed", "usage": after}))
    case("ephemeral Codex")

    with (root / "codex-ephemeral-host.log").open("w") as log:
        ephemeral = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, {**env, "CODEX_HOME": str(root / "missing-native-home")}, log)
        try:
            response = ephemeral.rpc("tools/call", {"name": "session", "arguments": {"Context": {}}, "_meta": metadata})
            assert not response["isError"], response
            assert "directory unavailable" in response["structuredContent"]["Context"]["value"]["usageCoverage"]
        finally:
            ephemeral.close()
    print(json.dumps({"ephemeralCodex": "CQ-available-usage-explicitly-unavailable"}))
    case("disconnect with a running child")

    with (root / "closing-host.log").open("w") as log:
        closing = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, env, log)
        try:
            closing_context = closing.tool("session", {"Context": {}})["Context"]["value"]
            closing.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}})
            created = closing.tool("change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": {**draft, "labels": []}}}], "fences": [], "reason": "Owned child shutdown"}})["Changed"]["ack"]["items"][0]
            selected, = closing.tool("dispatch", {"Select": {"request": {**selection, "request": identity(), "roots": [created["id"]], "work": {"Worker": {"mode": "Probe"}}}}})["Selection"]["value"]["choices"]
            owned = closing.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [created["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            running = closing.tool("dispatch", {"StartChoice": {"choice": selected["id"], "fence": owned["fence"]}})["Status"]["value"]
            deadline = time.monotonic() + 20
            while running["process"] != "Running" and time.monotonic() < deadline:
                running = closing.tool("dispatch", {"Status": {"attempt": running["attempt"], "waitMillis": 100}})["Status"]["value"]
            assert running["process"] == "Running" and int(running["quietMillis"]) >= 0, running
            # A wait longer than the deadline of a request that does not wait is served in full: the child runs on, and the host with it.
            began = time.monotonic()
            waited = closing.tool("dispatch", {"Status": {"attempt": running["attempt"], "waitMillis": LONG_WAIT_MILLIS}})["Status"]["value"]
            assert time.monotonic() - began >= LONG_WAIT_MILLIS / 1000 and waited["process"] == "Running" and waited["next"] == "Wait", waited
            closing.tool("dispatch", {"Status": {"attempt": running["attempt"], "waitMillis": 120001}}, denied=True)
            assert closing.process.poll() is None, "Attached host ended during a status wait"
        finally:
            closing.close()
    child_directory = Path(closing_context["directory"]) / "children" / running["attempt"]["value"]
    stopped = json.loads((child_directory / "receipt.json").read_text())
    assert stopped["phase"] == "Cancelled" and stopped["process"] == "Settled" and stopped["result"] is None and stopped["usageDelivered"], stopped
    assert stopped["quietMillis"] is None, stopped
    assert stopped["workspace"]["admission"] == "Quarantined" and Path(stopped["workspace"]["directory"]).is_dir(), stopped
    print(json.dumps({"disconnectWithRunningChild": "cancelled-and-accounted"}))
    case("initial stall after EOF")

    preload = root / "stall.so"
    subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)
    latch = root / "initial-stall"
    latch.mkdir()
    native_env = {**env, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_MODE": "attached-initial", "CQ_FIXTURE_STALL_ROOT": str(latch)}
    with (latch / "stderr").open("w") as log:
        stalled = subprocess.Popen(command + ["host", "codex", "--executable", str(wrapper)], cwd=repository, env=native_env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log)
        try:
            # The host records its session at the first tool call; the fixture reads neither reply.
            for request in [{"id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "attached-fixture", "version": "1"}}},
                            {"id": 2, "method": "tools/call", "params": {"name": "session", "arguments": {"Context": {}}}}]:
                stalled.stdin.write((json.dumps({"jsonrpc": "2.0", **request}) + "\n").encode())
            stalled.stdin.flush()
            latched(stalled, latch, "Initial")
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
    case("operation stall with a healthy owner")

    latch = root / "operation-stall"
    latch.mkdir()
    native_env = {**env, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_MODE": "attached-selection", "CQ_FIXTURE_STALL_ROOT": str(latch)}
    with (latch / "stderr").open("w") as log:
        stalled = Peer(command + ["host", "codex", "--executable", str(wrapper)], repository, native_env, log)
        try:
            stalled.tool("session", next_scope)
            stalled.send({"jsonrpc": "2.0", "id": 99, "method": "tools/call", "params": {"name": "dispatch", "arguments": {"Select": {"request": {**selection, "request": identity()}}}}})
            latched(stalled.process, latch, "Operation")
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
    case(None)


if __name__ == "__main__":
    main()
