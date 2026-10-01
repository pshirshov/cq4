"""Behavioral Effectual Good Communication: attached MCP, real server/Git/processes."""
import datetime
import json
import os
from pathlib import Path
import queue
import shlex
import subprocess
import sys
import threading
import time
import urllib.request
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

    def refused(self, method, params):
        self.sequence += 1
        self.send({"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params})
        value = self.responses.get(timeout=40)
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
    limits = {"startupMillis": "5000", "executionMillis": "60000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144}
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
            assert context["workflow"] is None and "thread metadata" in context["usageCoverage"]
            assert "Canonical argument schemas" in context["instructions"]
            project = context["project"]["project"]
            peer.tool("initialize", {}, denied=True)
            peer.tool("workspace", {"Read": {"path": ".", "offset": 0, "limit": 20}}, denied=True)
            selection = {"request": identity(), "roots": [], "work": {"Explorer": {"mode": "Investigate"}}, "guidance": [], "artifacts": [], "previous": None, "limits": limits}
            peer.tool("dispatch", {"Select": {"request": selection}}, denied=True)
            first = {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}
            activated = peer.tool("session", first)
            assert peer.tool("session", first) == activated
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
            peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": claim["fence"]}}, denied=True)
            assert peer.tool("session", first) == activated
            assert peer.tool("session", {"Context": {}})["Context"]["value"]["workflow"]["id"] == next_scope["Workflow"]["id"]
            selection["request"] = identity()
            choice, = peer.tool("dispatch", {"Select": {"request": selection}})["Selection"]["value"]["choices"]
            started = peer.tool("dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": claim["fence"]}})["Status"]["value"]
            # The asynchronous child must finish before a different workflow is admitted.
            if started["phase"] in ["Preparing", "Running"]:
                peer.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}}, denied=True)
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
    totals = json.loads(cli(["status", "--session", context["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert totals["attempts"]["unknown"] == "1" and totals["attempts"]["running"] == "0" and totals["attemptsWithoutMeters"] == "1", totals
    print(json.dumps({"attachedSession": context["session"], "child": status, "usage": totals, "activationFence": True, "tokenFile": True, "replay": True}))

    # A driven session: the hook entry points hold the operator credential; the attached session binds, activates the issued directive and
    # writes inside its cycle. Its first out-of-set write is rejected and stops the driver.
    def operator(value):
        request = urllib.request.Request(os.environ["CQ_ORIGIN"] + "/api/call", data=json.dumps(value).encode(), method="POST",
                                         headers={"Authorization": "Bearer " + os.environ["CQ_TOKEN"], "CQ-Session": os.environ["CQ_SESSION"],
                                                  "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    key = {"harness": "Codex", "session": "attached-fixture-session"}
    def control(origin, action):
        return operator({"Driver": {"input": {"project": project, "request": {"Control": {"key": key, "origin": origin, "action": action}}}}})["Driver"]["reply"]
    def members(value):
        return [(next(iter(entry["member"])), entry["settled"]) for entry in value["cycle"]["lineage"]]
    with (root / "driver-host.log").open("w") as log:
        driven = Peer(command + ["host", "codex"], repository, env, log)
        try:
            driven_session = driven.tool("session", {"Context": {}})["Context"]["value"]["session"]
            assert driven.tool("session", {"Driver": {}}) == {"Driver": {"reply": {"Status": {"value": None}}}}
            def change(mutations, fences, denied=False):
                return driven.tool("change", {"project": project, "change": {"request": identity(), "mutations": mutations, "fences": fences, "reason": "Driven fixture"}}, denied=denied)
            target, outsider = change([{"Create": {"draft": draft}}, {"Create": {"draft": draft}}], [])["Changed"]["ack"]["items"]
            started = control("UserPromptSubmit", {"Start": {"target": {"Inline": {"targets": [target["id"]], "through": "Explore"}}, "attached": None}})["Started"]
            assert started["status"]["state"] == "Binding" and [member["item"]["id"] for member in started["preview"]["advanceable"]] == [target["id"]], started
            driven.tool("session", {"Bind": {"token": identity()}}, denied=True)
            bound = driven.tool("session", {"Bind": {"token": started["bind"]}})["Driver"]["reply"]["Bound"]["status"]
            assert bound["state"] == "On" and bound["attached"] == driven_session, bound
            issued = control("Stop", {"Continue": {}})["Continue"]["directive"]
            reference = "T" + target["id"]["number"]
            words = issued["text"].split(" ")
            assert words[:6] == ["$cq-advance", "--roots", reference, "--through", "explore", "--start-token"] and len(words) == 7, issued
            advance = {"Advance": {"roots": [target["id"]], "through": "Explore"}}
            # The token travels only in Workflow.token: requirements text that carries it is refused before the token is presented, so a clean retry activates.
            leaking = {"Workflow": {"id": identity(), "request": advance, "operatorRequirements": issued["text"], "token": {"Start": {"token": {"value": words[6]}}}}}
            assert "pass the token only in Workflow.token" in json.dumps(driven.tool("session", leaking, denied=True))
            directed = {"Workflow": {"id": identity(), "request": advance, "operatorRequirements": f"Driven fixture: advance {reference} through explore", "token": {"Start": {"token": {"value": words[6]}}}}}
            run = driven.tool("session", directed)["Workflow"]["value"]
            assert run["cycle"] == issued["cycle"] and driven.tool("session", directed)["Workflow"]["value"] == run, run
            assert driven.tool("session", {"Context": {}})["Context"]["value"]["workflow"]["cycle"] == issued["cycle"]
            driven_choice, = driven.tool("dispatch", {"Select": {"request": {**selection, "request": identity(), "roots": [target["id"]]}}})["Selection"]["value"]["choices"]
            driven_claim = driven.tool("claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [target["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            child = driven.tool("dispatch", {"StartChoice": {"choice": driven_choice["id"], "harness": "Codex", "fence": driven_claim["fence"]}})["Status"]["value"]
            registered = control("StatusLine", {"Status": {}})["Status"]["value"]
            assert [name for name, _ in members(registered)] == ["Run", "Claim", "Request", "Attempt"] and registered["cycle"]["run"] == directed["Workflow"]["id"], registered
            for _ in range(6):
                child = driven.tool("dispatch", {"Status": {"attempt": child["attempt"], "waitMillis": 20000}})["Status"]["value"]
                if child["phase"] not in ["Preparing", "Running", "Stopping", "Validating", "Publishing"]:
                    break
            assert child["phase"] == "Completed", child
            deadline = time.monotonic() + 30
            while dict(members(control("StatusLine", {"Status": {}})["Status"]["value"]))["Attempt"] is not True and time.monotonic() < deadline:
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
            stop = control("Stop", {"Continue": {}})["Stop"]
            assert stop["stopped"]["reason"] == "Failure" and "out-of-set change" in stop["stopped"]["detail"] and stop["status"]["state"] == "Off", stop
            own = driven.tool("session", {"Driver": {}})["Driver"]["reply"]["Status"]["value"]
            assert own["state"] == "Off" and own["stopped"] == stop["stopped"], own
            driven.refused("cq/driver", {"Status": {"session": "attached-fixture-session"}})
            assert control("Stop", {"Continue": {}})["Stop"]["stopped"]["reason"] == "Off"
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
            driving = hook("UserPromptSubmit", hooked, prompt=f"$cq-drive {reference} through=explore")
            offered = driving["hookSpecificOutput"]["additionalContext"]
            assert driving["hookSpecificOutput"]["hookEventName"] == "UserPromptSubmit" and offered.startswith(f"CQ driver drive-start: CQ driver binding: {reference} through explore"), driving
            printed, = [line for line in offered.splitlines() if '{"Bind":' in line]
            hook_bound = driven.tool("session", json.loads(printed[printed.index('{"Bind":'):]))["Driver"]["reply"]["Bound"]["status"]
            assert hook_bound["state"] == "On" and hook_bound["key"] == {"harness": "Codex", "session": hooked} and hook_bound["attached"] == driven_session, hook_bound
            blocked = hook("Stop", hooked, stop_hook_active=False, last_assistant_message="Bound.")
            assert blocked["decision"] == "block" and blocked["reason"].splitlines()[-1].startswith(f"$cq-advance --roots {reference} --through explore --start-token "), blocked
            assert hook("UserPromptSubmit", hooked, prompt="$cq-park")["systemMessage"] == f"CQ driver park: CQ driver parked: {reference} through explore"
            assert hook("Stop", hooked, stop_hook_active=True, last_assistant_message="Parked.") is None
            assert hook("Stop", None, stop_hook_active=False) == {"systemMessage": "CQ Stop hook error: Driver session key is missing; no default session is used"}
            status_line = json.loads((repository / ".claude/settings.local.json").read_text())["statusLine"]["command"]
            shown = subprocess.run(shlex.split(status_line), cwd=repository, env=env, input=json.dumps({"session_id": "attached-fixture-status"}), capture_output=True, text=True, timeout=60)
            assert shlex.split(status_line) == [str(wrapper), "hook", "claude", "StatusLine"] and shown.returncode == 0 and shown.stdout == "CQ driver off\n", shown
        finally:
            driven.close()
    print(json.dumps({"drivenSession": driven_session, "cycle": issued["cycle"], "lineage": members(settled), "stop": stop["stopped"]}))

    with (root / "pi-host.log").open("w") as log:
        pi = Peer(command + ["host", "pi"], repository, env, log)
        try:
            pi_context = pi.tool("session", {"Context": {}})["Context"]["value"]
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
            pi_directive = pi.rpc("cq/driver", {"Continue": {"session": pi_key}})["Continue"]["directive"]
            assert pi_directive["text"].startswith("/cq:advance --roots " + reference + " --through explore --start-token "), pi_directive
            assert pi.tool("session", {"Driver": {}})["Driver"]["reply"]["Status"]["value"]["cycle"]["id"] == pi_directive["cycle"]
            pi_parked = pi.rpc("cq/driver", {"Park": {"session": pi_key}})["Parked"]
            assert pi_parked["status"]["state"] == "Off" and pi_parked["status"]["stopped"]["reason"] == "Parked", pi_parked
            assert pi.rpc("cq/driver", {"Continue": {"session": pi_key}})["Stop"]["stopped"]["reason"] == "Off"
        finally:
            pi.close()
    pi_totals = json.loads(cli(["status", "--session", pi_context["session"]["value"], "--json"]))["UsageSummary"]["report"]
    assert pi_totals["unattributed"]["total"]["known"] == "15" and pi_totals["incompleteMeters"] == "1", pi_totals
    assert "Acknowledged 0" in cli(["job", "upload", "--session", pi_context["directory"]])
    print(json.dumps({"attachedPiUsage": "deduplicated-partial", "integrationExports": ["claude", "codex", "pi"]}))

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
        observer = Peer(command + ["host", "codex"], repository, {**env, "CODEX_HOME": str(codex_home)}, log)
        try:
            response = observer.rpc("tools/call", {"name": "session", "arguments": {"Context": {}}, "_meta": metadata})
            assert not response["isError"], response
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

    with (root / "codex-ephemeral-host.log").open("w") as log:
        ephemeral = Peer(command + ["host", "codex"], repository, {**env, "CODEX_HOME": str(root / "missing-native-home")}, log)
        try:
            response = ephemeral.rpc("tools/call", {"name": "session", "arguments": {"Context": {}}, "_meta": metadata})
            assert not response["isError"], response
            assert "directory unavailable" in response["structuredContent"]["Context"]["value"]["usageCoverage"]
        finally:
            ephemeral.close()
    print(json.dumps({"ephemeralCodex": "CQ-available-usage-explicitly-unavailable"}))

    with (root / "closing-host.log").open("w") as log:
        closing = Peer(command + ["host", "codex"], repository, env, log)
        try:
            closing_context = closing.tool("session", {"Context": {}})["Context"]["value"]
            closing.tool("session", {"Workflow": {"id": identity(), "request": {"Begin": {"roots": []}}, "operatorRequirements": "Attached fixture: operator requirements text", "token": None}})
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
    assert stopped["workspace"]["admission"] == "Quarantined" and Path(stopped["workspace"]["directory"]).is_dir(), stopped
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
