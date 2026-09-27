import json
import os
from pathlib import Path
import signal
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
    sandbox = arguments[arguments.index("--sandbox") + 1]
    target = Path(arguments[arguments.index("--output-last-message") + 1])

    def emit(event):
        print(json.dumps(event), flush=True)

    def endpoint(name):
        prefix = f"mcp_servers.{name}.url="
        return json.loads(next(value.removeprefix(prefix) for value in arguments if value.startswith(prefix)))

    def rpc(name, method, params):
        packet = {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": method, "params": params}
        request = urllib.request.Request(endpoint(name), data=json.dumps(packet).encode(), headers={
            "Authorization": "Bearer " + os.environ[f"CQ_MCP_{name.upper()}_TOKEN"],
            "Content-Type": "application/json", "MCP-Protocol-Version": "2025-03-26",
        })
        with urllib.request.urlopen(request, timeout=25) as response:
            return json.load(response)

    def tool(name, tool_name, value, denied=False):
        response = rpc(name, "tools/call", {"name": tool_name, "arguments": value})
        if denied:
            assert "error" in response or response["result"].get("isError"), response
            return response
        assert "error" not in response and not response["result"].get("isError"), response
        body = response["result"]["structuredContent"]
        if name == "cq_host" and tool_name == "dispatch":
            emit({"type": "fixture.dispatch", "request": value, "reply": body})
        return body

    def finish(value):
        target.write_text(json.dumps(value))
        emit({"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 20,
              "cache_write_input_tokens": 0, "output_tokens": 31, "reasoning_output_tokens": 3}})

    def poll(attempt):
        for _ in range(8):
            value = tool("cq_host", "dispatch", {"Status": {"attempt": attempt, "waitMillis": 20000}})["Status"]["value"]
            if value["phase"] not in ["Preparing", "Running", "Stopping", "Validating", "Publishing"]:
                return value
        raise AssertionError("Fixture child did not finish")

    emit({"type": "thread.started", "thread_id": str(uuid.uuid4())})
    emit({"type": "turn.started"})
    for name in ["cq", "cq_host"]:
        assert "result" in rpc(name, "initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "fixture", "version": "1"}})
    inventory = rpc("cq_host", "tools/list", {})["result"]["tools"]
    assert [value["name"] for value in inventory] == (["workspace"] if child else ["dispatch"])

    if child:
        context = data["input"]
        assignment = context["request"]
        members = [value["id"] for value in assignment["members"]]
        project = context["project"]
        tool("cq_host", "dispatch", {"Status": {"attempt": identity(), "waitMillis": 0}}, denied=True)
        tool("cq_host", "dispatch", {"Integrate": {"id": identity()}}, denied=True)
        tool("cq", "apply", {"project": project, "result": identity()}, denied=True)
        tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": [], "fences": [], "reason": "forbidden"}}, denied=True)
        tool("cq_host", "workspace", {"Read": {"path": "../outside", "offset": 0, "limit": 10}}, denied=True)
        role = next(iter(assignment["work"]))
        advertised_checks = any(branch["required"] == ["Check"] for branch in inventory[0]["inputSchema"]["oneOf"])
        assert advertised_checks == (role == "Reviewer")
        if assignment["work"] != {"Reviewer": {"mode": "Candidate"}}:
            tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 0}}, denied=True)
        proposal_fixture = context["members"][0]["item"]["draft"]["labels"] == ["proposal-fixture"]
        if proposal_fixture:
            assert sandbox == ("workspace-write" if role == "Worker" else "read-only")
            tool("cq_host", "workspace", {"Entries": {"path": ".", "after": None, "limit": 20}})
            narrative = "CHILD_ONLY_NARRATIVE " + "detail " * 800
            if role in ["Explorer", "Worker"]:
                if role == "Worker":
                    assert assignment["work"][role]["mode"] == "Probe"
                    Path("probe.txt").write_text("isolated probe evidence")
                finish({"Evidence": {"members": [{"item": item, "disposition": "Findings", "summary": narrative,
                    "evidence": [{"description": narrative, "origin": "ModelDeclared", "citations": []}],
                    "uncertainties": ["Probe observations require interpretation"], "requestedProbes": []} for item in members]}})
            else:
                draft = {**context["members"][0]["item"]["draft"], "title": "Proposed follow-up", "body": narrative}
                proposal = {"mutations": [{"Produce": {"producer": members[0], "drafts": [draft]}}], "reason": "Create follow-up"}
                if role == "Planner":
                    assert "Evidence" in context["previous"]["report"]
                    finish({"Plan": {"members": [{"item": item, "disposition": "Proposed", "summary": narrative} for item in members], "proposal": proposal}})
                elif assignment["work"][role]["mode"] == "Plan":
                    assert context["previous"]["report"]["Plan"]["proposal"] == proposal
                    finish({"Review": {"members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members], "proposal": None}})
                else:
                    assert assignment["work"][role]["mode"] == "Audit"
                    finish({"Review": {"members": [{"item": item, "verdict": "ChangesRequested", "findings": ["Follow-up required", narrative]} for item in members], "proposal": proposal}})
            return
        if "Worker" in assignment["work"]:
            assert sandbox == "workspace-write"
            Path("consumer.txt").write_text("candidate from isolated worker\n")
            if assignment["work"]["Worker"]["mode"] == "Probe":
                signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
            finish({"Work": {"members": [{"item": item, "disposition": "CandidateReady", "summary": "CHILD_ONLY_NARRATIVE " + "detail " * 1000} for item in members]}})
            if assignment["work"]["Worker"]["mode"] == "Probe":
                time.sleep(60)
            else:
                time.sleep(0.5)
        else:
            assert sandbox == "read-only" and context["previous"]["candidate"] is not None
            listing = tool("cq_host", "workspace", {"Entries": {"path": ".", "after": None, "limit": 200}})
            assert "consumer.txt" in [entry["name"] for entry in listing["Listed"]["page"]["entries"]]
            text = tool("cq_host", "workspace", {"Read": {"path": "consumer.txt", "offset": 0, "limit": 8192}})
            assert text["Text"]["page"]["text"] == "candidate from isolated worker\n"
            tool("cq_host", "workspace", {"Read": {"path": ".git", "offset": 0, "limit": 10}}, denied=True)
            assert context["previous"]["validation"] and all(value["state"] == "Passed" for value in context["previous"]["validation"])
            tool("cq_host", "workspace", {"Check": {"name": "undeclared", "waitMillis": 0}}, denied=True)
            tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 20001}}, denied=True)
            check = tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 0}})["Check"]["value"]
            job = check["job"]
            deadline = time.monotonic() + 20
            while check["phase"] != "Completed":
                assert time.monotonic() < deadline and check["phase"] not in ["Failed", "Unknown"], check
                check = tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 1000}})["Check"]["value"]
                assert check["job"] == job
            expected = "Failed" if "failed-reviewer-check" in context["members"][0]["item"]["draft"]["labels"] else "Passed"
            assert check["evidence"]["state"] == expected and check["evidence"] != context["previous"]["validation"][0]
            assert not Path("check-private").exists()
            assert tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 0}})["Check"]["value"] == check
            emit({"type": "fixture.reviewer-check", "value": check})
            finish({"Review": {"proposal": None, "members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members]}})
        return

    assert sandbox == "read-only"
    project = data["project"]["project"]
    if data["request"].startswith("workflow-denial:"):
        selection = json.loads(data["request"].split(":", 1)[1])
        members = selection["members"]
        claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {
            "id": identity(), "members": [value["id"] for value in members], "durationMillis": "180000"}}})
        request = {"request": identity(), "work": selection["work"], "harness": "Codex", "members": members,
                   "guidance": [], "artifacts": [], "previous": selection["previous"],
                   "fence": claim["Claimed"]["claim"]["fence"], "limits": data["limits"]}
        rejected = tool("cq_host", "dispatch", {"Start": {"request": request}}, denied=True)
        assert "workflow" in json.dumps(rejected).lower(), rejected
        for operation in [{"PrepareIntegration": {"id": identity(), "reviewer": identity()}},
                          {"Integrate": {"id": identity()}},
                          {"Combine": {"id": identity(), "source": identity(), "fence": request["fence"]}}]:
            rejected = tool("cq_host", "dispatch", operation, denied=True)
            if selection["integrationDenied"]:
                assert "workflow" in json.dumps(rejected).lower(), rejected
        finish({"summary": "Workflow execution limits denied admission before side effects"})
        return
    draft = {"title": "Consumer fixture", "body": "Implement consumer.txt with the specified contents", "labels": [], "archived": False,
             "content": {"Task": {"status": "Ready", "acceptance": ["Exact content verified"], "result": None, "validation": []}}, "citations": []}
    if data["request"] == "proposal-workflow":
        draft["labels"] = ["proposal-fixture"]
    if data["request"].startswith("reviewer-check-failed:"):
        draft["labels"] = ["failed-reviewer-check"]
    created = tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {"draft": draft}}], "fences": [], "reason": "Fixture task"}})
    members = created["Changed"]["ack"]["items"]
    claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [value["id"] for value in members], "durationMillis": "180000"}}})
    request = {"request": identity(), "work": {"Worker": {"mode": "Implement"}}, "harness": "Codex", "members": members,
               "guidance": [], "artifacts": [], "previous": None, "fence": claim["Claimed"]["claim"]["fence"], "limits": data["limits"]}
    if data["request"] == "proposal-workflow":
        previous = None
        results = []
        for work in [{"Explorer": {"mode": "Investigate"}}, {"Explorer": {"mode": "Research"}},
                     {"Worker": {"mode": "Probe"}}, {"Planner": {}}, {"Reviewer": {"mode": "Plan"}}, {"Reviewer": {"mode": "Audit"}}]:
            current = {**request, "request": identity(), "work": work, "previous": previous}
            started = tool("cq_host", "dispatch", {"Start": {"request": current}})["Status"]["value"]
            settled = poll(started["attempt"])
            assert settled["phase"] == "Completed" and settled["result"] and settled["usageDelivered"], settled
            results.append(settled)
            previous = settled["result"]
        assert results[2]["counts"]["evidence"] == 1 and results[2]["counts"]["ready"] == 0
        assert results[3]["counts"]["proposed"] == 1 and results[3]["next"] == "ConsiderProposal"
        assert results[4]["counts"]["accepted"] == 1
        # Both proposals share the frozen producer; applying one makes the other stale.
        handle = results[3]["result"]
        preview = tool("cq", "read", {"project": project, "selection": {"Proposal": {"id": handle}}})["Proposal"]["preview"]
        assert preview["members"] == members and preview["detailsOmitted"] and len(preview["operations"]) == 1
        apply_input = {"project": project, "result": handle}
        ack = tool("cq", "apply", apply_input)["Changed"]["ack"]
        assert tool("cq", "apply", apply_input)["Changed"]["ack"] == ack
        tool("cq", "apply", {"project": project, "result": results[5]["result"]}, denied=True)
        emit({"type": "fixture.proposal", "handle": handle, "preview": preview, "ack": ack, "statuses": results,
              "applyBytes": len(json.dumps(apply_input).encode())})
        finish({"summary": "Evidence and probe results planned and reviewed by handle; typed proposal applied once and stale follow-up rejected"})
        return
    exiting = data["request"] == "exit-with-running-child"
    if exiting:
        request["work"] = {"Worker": {"mode": "Probe"}}
    first = tool("cq_host", "dispatch", {"Start": {"request": request}})["Status"]["value"]
    if exiting:
        for _ in range(100):
            running = tool("cq_host", "dispatch", {"Status": {"attempt": first["attempt"], "waitMillis": 0}})["Status"]["value"]
            if running["process"] == "Running":
                finish({"summary": "Governing fixture exits with a live child; host owns hierarchy termination"})
                return
            time.sleep(0.05)
        raise AssertionError("Exit fixture child did not start")
    replay = tool("cq_host", "dispatch", {"Start": {"request": request}})["Status"]["value"]
    assert first["attempt"] == replay["attempt"]
    changed = {**request, "work": {"Worker": {"mode": "Probe"}}}
    tool("cq_host", "dispatch", {"Start": {"request": changed}}, denied=True)

    worker = poll(first["attempt"])
    assert worker["phase"] == "Completed" and worker["counts"]["ready"] == 1 and worker["counts"]["validationFailed"] == 0, worker
    assert worker["result"] and worker["usageDelivered"] and worker["detailsOmitted"]
    assert poll(first["attempt"]) == worker
    review_request = {**request, "request": identity(), "work": {"Reviewer": {"mode": "Candidate"}}, "previous": worker["result"]}
    review = tool("cq_host", "dispatch", {"Start": {"request": review_request}})["Status"]["value"]
    if data["request"].startswith(("reviewer-check-failed:", "reviewer-check-cancel:", "reviewer-check-hold:")):
        mode, marker = data["request"].split(":", 1)
        deadline = time.monotonic() + 30
        while not Path(marker).exists():
            assert time.monotonic() < deadline, "Reviewer check did not become live"
            time.sleep(0.05)
        if mode == "reviewer-check-failed":
            reviewed = poll(review["attempt"])
            assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == 1 and reviewed["counts"]["validationFailed"] == 1, reviewed
            assert reviewed["next"] == "Revise" and reviewed["blocker"] == "Host check consumer-content: Failed", reviewed
            finish({"summary": "Fresh host check failure remains visible despite the Accepted model verdict"})
            return
        if mode == "reviewer-check-hold":
            time.sleep(60)
            raise AssertionError("Supervisor was not killed during the check")
        tool("cq_host", "dispatch", {"Cancel": {"attempt": review["attempt"]}})
        cancelled = poll(review["attempt"])
        assert cancelled["phase"] == "Cancelled" and cancelled["result"] is None, cancelled
        emit({"type": "fixture.cancelled-reviewer-check", "status": cancelled})
        finish({"summary": "Native reviewer and its live check cancelled together"})
        return
    reviewed = poll(review["attempt"])
    assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == 1, reviewed
    if data["request"] == "integrate-reviewed-candidate":
        assert data["integrationTarget"] == "refs/heads/integration"
        operation = identity()
        prepared_request = {"PrepareIntegration": {"id": operation, "reviewer": reviewed["result"]}}
        first = tool("cq_host", "dispatch", prepared_request)["Integration"]["value"]
        assert first["phase"] in ["Preparing", "Ready"], first
        tool("cq_host", "dispatch", prepared_request)
        tool("cq_host", "dispatch", {"PrepareIntegration": {"id": operation, "reviewer": worker["result"]}}, denied=True)

        def integration():
            for _ in range(8):
                value = tool("cq_host", "dispatch", {"IntegrationStatus": {"id": operation, "waitMillis": 20000}})["Integration"]["value"]
                if value["phase"] not in ["Preparing", "Running"]:
                    return value
            raise AssertionError("Integration did not finish")

        ready = integration()
        assert ready["phase"] == "Ready" and ready["next"] == "Confirm", ready
        assert ready["preview"]["members"] == members and ready["preview"]["reviewer"] == reviewed["result"]
        tool("cq_host", "dispatch", {"Integrate": {"id": operation}})
        recorded = integration()
        assert recorded["phase"] == "Recorded" and recorded["next"] == "Complete" and recorded["blocker"] is None, recorded
        assert tool("cq_host", "dispatch", {"Integrate": {"id": operation}})["Integration"]["value"] == recorded
        assert tool("cq_host", "dispatch", prepared_request)["Integration"]["value"] == recorded
        item = tool("cq", "read", {"project": project, "selection": {"ItemDetail": {"id": members[0]["id"]}}})["Detail"]["view"]["item"]
        assert item["draft"]["content"]["Task"]["status"] == "Done", item
        for name in ["title", "body", "labels", "archived", "citations"]:
            assert item["draft"][name] == draft[name]
        assert item["draft"]["content"]["Task"]["acceptance"] == draft["content"]["Task"]["acceptance"]
        emit({"type": "fixture.integration", "recorded": recorded, "item": item})
        finish({"summary": "Reviewed candidate integrated into the configured target and task completion recorded exactly once"})
        return
    cancelled_request = {**request, "request": identity(), "work": {"Worker": {"mode": "Probe"}}}
    cancelled = tool("cq_host", "dispatch", {"Start": {"request": cancelled_request}})["Status"]["value"]
    for _ in range(100):
        running = tool("cq_host", "dispatch", {"Status": {"attempt": cancelled["attempt"], "waitMillis": 0}})["Status"]["value"]
        if running["process"] == "Running":
            break
        time.sleep(0.05)
    else:
        raise AssertionError("Cancellation fixture did not start")
    tool("cq_host", "dispatch", {"Cancel": {"attempt": cancelled["attempt"]}})
    stopped = poll(cancelled["attempt"])
    assert stopped["phase"] == "Cancelled" and stopped["result"] is None and stopped["usageDelivered"], stopped
    finish({"summary": "Worker candidate validated, reviewed by handle, cancellation and permissions verified; integration remains pending"})


if __name__ == "__main__":
    main()
