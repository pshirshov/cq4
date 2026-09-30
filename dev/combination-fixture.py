import json
import os
from pathlib import Path
import sys
import time
import urllib.request
import uuid


def identity():
    return {"value": str(uuid.uuid4())}


def main():
    if sys.argv[1:] == ["--version"]:
        print("codex-cli 0.156.1")
        return
    assert "CQ_TOKEN" not in os.environ and "CQ_DATABASE_URL" not in os.environ
    arguments = sys.argv[1:]
    data = json.load(sys.stdin)
    child = "input" in data
    target = Path(arguments[arguments.index("--output-last-message") + 1])
    sandbox = arguments[arguments.index("--sandbox") + 1]

    def emit(event):
        print(json.dumps(event), flush=True)

    def rpc(name, method, params):
        prefix = f"mcp_servers.{name}.url="
        endpoint = json.loads(next(value.removeprefix(prefix) for value in arguments if value.startswith(prefix)))
        request = urllib.request.Request(endpoint, data=json.dumps({"jsonrpc": "2.0", "id": str(uuid.uuid4()),
            "method": method, "params": params}).encode(), headers={"Authorization": "Bearer " + os.environ[f"CQ_MCP_{name.upper()}_TOKEN"],
            "Content-Type": "application/json", "MCP-Protocol-Version": "2025-03-26"})
        with urllib.request.urlopen(request, timeout=25) as response:
            return json.load(response)

    def tool(name, operation, value, denied=False):
        reply = rpc(name, "tools/call", {"name": operation, "arguments": value})
        if denied:
            assert "error" in reply or reply["result"].get("isError"), reply
            return
        assert "error" not in reply and not reply["result"].get("isError"), reply
        body = reply["result"]["structuredContent"]
        if name == "cq_host" and operation == "dispatch":
            emit({"type": "fixture.dispatch", "request": value, "reply": body})
        return body

    def finish(value):
        target.write_text(json.dumps(value))
        emit({"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 20,
            "cache_write_input_tokens": 0, "output_tokens": 31, "reasoning_output_tokens": 3}})

    emit({"type": "thread.started", "thread_id": str(uuid.uuid4())})
    emit({"type": "turn.started"})
    for name in ["cq", "cq_host"]:
        assert "result" in rpc(name, "initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "combination-fixture", "version": "1"}})
    if child:
        context = data["input"]
        request = context["request"]
        task = json.loads(context["members"][0]["item"]["draft"]["body"])
        actor, scenario = task["actor"], task["scenario"]
        member = request["members"][0]["id"]
        if "Worker" in request["work"]:
            assert sandbox == "workspace-write"
            if request["work"]["Worker"]["mode"] == "ResolveConflict":
                assert len(context["artifacts"]) == 1 and context["artifacts"][0]["metadata"]["kind"] == "Combination"
                offset, report = 0, ""
                while True:
                    page = tool("cq_host", "workspace", {"MergeReport": {"offset": offset, "limit": 64}})["Text"]["page"]
                    report += page["text"]
                    if not page["hasMore"]:
                        break
                    assert page["next"] > offset
                    offset = page["next"]
                emit({"type": "fixture.merge_report", "text": report})
                assert Path("alpha.txt").read_text() == "alpha\n" and Path("beta.txt").read_text() == "beta\n"
                if scenario == "conflict":
                    Path("shared.txt").write_text("alpha\nbeta\n")
            else:
                tool("cq_host", "workspace", {"MergeReport": {"offset": 0, "limit": 64}}, denied=True)
                Path(actor + ".txt").write_text(actor + "\n")
                if scenario == "conflict":
                    Path("shared.txt").write_text(actor + "\n")
            finish({"Work": {"members": [{"item": member, "disposition": "CandidateReady", "summary": "CHILD_ONLY_NARRATIVE " + "details " * 500, "evidence": []}]}})
        else:
            assert sandbox == "read-only" and not context["artifacts"]
            previous = context["previous"]
            assert previous["validation"] and all(check["state"] == "Passed" for check in previous["validation"])
            combined = previous["request"]["work"]["Worker"]["mode"] == "ResolveConflict"
            for name in (["alpha", "beta"] if combined else [actor]):
                page = tool("cq_host", "workspace", {"Read": {"path": name + ".txt", "offset": 0, "limit": 64}})["Text"]["page"]
                assert page["text"] == name + "\n"
            if scenario == "conflict":
                page = tool("cq_host", "workspace", {"Read": {"path": "shared.txt", "offset": 0, "limit": 64}})["Text"]["page"]
                assert page["text"] == ("alpha\nbeta\n" if combined else actor + "\n")
            finish({"Review": {"proposal": None, "members": [{"item": member, "verdict": "Accepted", "findings": []}]}})
        return

    assert sandbox == "read-only"
    task = json.loads(data["request"])
    actor, scenario = task["actor"], task["scenario"]
    project = data["project"]["project"]
    control = Path(CONTROL_ROOT) / scenario

    def wait(name):
        deadline = time.monotonic() + 90
        while not (control / name).exists():
            assert time.monotonic() < deadline, name
            time.sleep(0.05)

    def dispatch(operation):
        return tool("cq_host", "dispatch", operation)

    def poll(tag, key, value, result_tag, active):
        for _ in range(10):
            state = dispatch({tag: {key: value, "waitMillis": 20000}})[result_tag]["value"]
            if state["phase"] not in active:
                return state
        raise AssertionError(f"{tag} did not settle")

    draft = {"title": actor + " combination fixture", "body": json.dumps(task), "labels": [], "archived": False,
        "content": {"Task": {"status": "Ready", "acceptance": ["Both independent changes survive"], "result": None, "validation": []}}, "citations": []}
    members = tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": draft}}],
        "fences": [], "reason": "Combination fixture"}})["Changed"]["ack"]["items"]
    claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {"id": identity(),
        "members": [member["id"] for member in members], "durationMillis": "180000"}}})["Claimed"]["claim"]
    base_request = {"harness": "Codex", "members": members, "guidance": [], "artifacts": [], "previous": None,
        "fence": claim["fence"], "limits": data["limits"]}

    def child_result(work, previous, artifacts):
        request = {**base_request, "request": identity(), "work": work, "previous": previous, "artifacts": artifacts}
        started = dispatch({"Start": {"request": request}})["Status"]["value"]
        result = poll("Status", "attempt", started["attempt"], "Status", ["Preparing", "Running", "Stopping", "Validating", "Publishing"])
        assert result["phase"] == "Completed" and result["result"] and result["usageDelivered"], result
        assert result["counts"]["accepted" if "Reviewer" in work else "ready"] == 1, result
        assert result["counts"]["validationFailed"] == 0, result
        return result

    def prepare(review):
        operation = identity()
        dispatch({"PrepareIntegration": {"id": operation, "reviewer": review["result"]}})
        ready = poll("IntegrationStatus", "id", operation, "Integration", ["Preparing", "Running"])
        assert ready["phase"] == "Ready", ready
        return ready

    def integrate(ready):
        dispatch({"Integrate": {"id": ready["id"]}})
        return poll("IntegrationStatus", "id", ready["id"], "Integration", ["Preparing", "Running"])

    worker = child_result({"Worker": {"mode": "Implement"}}, None, [])
    review = child_result({"Reviewer": {"mode": "Candidate"}}, worker["result"], [])
    prepared = prepare(review)
    emit({"type": "fixture.prepared", "actor": actor, "integration": prepared})
    wait(actor + "-initial")
    result = integrate(prepared)
    if actor == "alpha":
        assert result["phase"] == "Recorded", result
    else:
        rounds = 2 if scenario == "conflict" else 1
        for index in range(rounds):
            assert result["phase"] == "NotApplied", result
            ticket = {"id": identity(), "source": result["id"], "fence": claim["fence"]}
            dispatch({"Combine": ticket})
            ready = poll("CombinationStatus", "id", ticket["id"], "Combination", ["Preparing"])
            assert ready["phase"] == "Ready", ready
            assert dispatch({"Combine": ticket})["Combination"]["value"] == ready
            tool("cq_host", "dispatch", {"Combine": {**ticket, "source": identity()}}, denied=True)
            preview = ready["preview"]
            assert preview["members"] == members and preview["fence"] == claim["fence"]
            worker = child_result({"Worker": {"mode": "ResolveConflict"}}, preview["worker"], [preview["plan"]])
            review = child_result({"Reviewer": {"mode": "Candidate"}}, worker["result"], [])
            prepared = prepare(review)
            emit({"type": "fixture.combined_ready", "round": index, "plan": preview, "worker": worker, "review": review, "integration": prepared})
            wait("beta-combined-" + str(index))
            result = integrate(prepared)
        assert result["phase"] == "Recorded", result
    emit({"type": "fixture.recorded", "actor": actor, "integration": result})
    finish({"summary": "Independent changes integrated and task completion recorded"})


if __name__ == "__main__":
    main()
