import hashlib
import json
from decimal import Decimal
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
        labels = context["members"][0]["item"]["draft"]["labels"]
        if labels == ["traffic-growth"]:
            artifact, = context["artifacts"]
            scale = len(artifact["body"])
            assert scale in [32, 60000] and artifact["body"] == "PRIVATE_GROWTH_INPUT:".ljust(scale, "x")
            narrative = "PRIVATE_GROWTH_RESULT:" + "x" * (1 if scale == 32 else 7000)
            evidence = {"Evidence": {"members": [{"item": item, "disposition": "Findings", "summary": narrative,
                "evidence": [{"origin": "ModelDeclared", "description": narrative, "citations": []} for _ in range(4)],
                "uncertainties": [], "requestedProbes": []} for item in members]}}
            assert sandbox == "read-only"
            if role == "Explorer":
                assert assignment["work"] == {"Explorer": {"mode": "Investigate"}} and context["previous"] is None
                finish(evidence)
            else:
                assert assignment["work"] == {"Reviewer": {"mode": "Audit"}} and context["previous"]["report"] == evidence
                finish({"Review": {"proposal": None, "members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members]}})
            return
        assessment_flow = labels and labels[0].startswith("cohort-selected") and (role == "Planner" or assignment["work"] == {"Reviewer": {"mode": "Plan"}})
        if (labels and labels[0].startswith("cohort-assessment")) or assessment_flow:
            assert sandbox == "read-only" and len(members) == 2
            if role == "Planner":
                check = "not-configured" if labels == ["cohort-assessment-unknown-check"] else data["checks"][0]["name"]
                assessment = {"compatibility": "Unknown" if labels in [["cohort-assessment-unknown"], ["cohort-selected-unknown"]] else "Compatible",
                              "objective": "PRIVATE_ASSESSMENT " * 400, "dependencies": "No intra-group dependencies",
                              "interference": "Separate acceptance observations required", "members": [
                    {"member": member, "acceptance": [{"criterion": 0, "checks": [check], "inspection": "Inspect each task result"}]}
                    for member in assignment["members"]]}
                finish({"Plan": {"members": [{"item": item, "disposition": "Assessed", "summary": "Compatibility assessed"} for item in members],
                                 "proposal": None, "assessments": [assessment]}})
            else:
                assert assignment["work"] == {"Reviewer": {"mode": "Plan"}}
                assert context["previous"]["report"]["Plan"]["proposal"] is None
                assert len(context["previous"]["report"]["Plan"]["assessments"][0]["members"]) == 2
                finish({"Review": {"members": [{"item": item, "verdict": "Accepted", "findings": []} for item in members], "proposal": None}})
            return
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
                    finish({"Plan": {"members": [{"item": item, "disposition": "Proposed", "summary": narrative} for item in members], "proposal": proposal, "assessments": []}})
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
            disposition = "Failed" if labels and labels[0].startswith("cohort-selected-refresh") else "CandidateReady"
            finish({"Work": {"members": [{"item": item, "disposition": disposition, "summary": "CHILD_ONLY_NARRATIVE " + "detail " * 1000, "evidence": []} for item in members]}})
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
            jobs = {check["job"]["value"]}
            deadline = time.monotonic() + 20
            while check["phase"] != "Completed":
                assert time.monotonic() < deadline and check["phase"] not in ["Failed", "Unknown"], check
                check = tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 1000}})["Check"]["value"]
                jobs.add(check["job"]["value"])
            # The job changes only when the host reruns a failed run (I19); one request never starts a second check by itself.
            assert len(jobs) <= 1 + len(check["evidence"]["failures"]) and (len(jobs) == 1) == (check["evidence"]["failures"] == []), check
            expected = "Failed" if "failed-reviewer-check" in context["members"][0]["item"]["draft"]["labels"] else "Passed"
            assert check["evidence"]["state"] == expected and check["evidence"] != context["previous"]["validation"][0]
            assert not Path("check-private").exists()
            assert tool("cq_host", "workspace", {"Check": {"name": "consumer-content", "waitMillis": 0}})["Check"]["value"] == check
            emit({"type": "fixture.reviewer-check", "value": check})
            mixed = labels == ["cohort-selected-mixed"] and len(members) > 1
            finish({"Review": {"proposal": None, "members": [{"item": item,
                "verdict": "ChangesRequested" if mixed and index == 0 else "Accepted",
                "findings": ["Correct this member separately"] if mixed and index == 0 else []} for index, item in enumerate(members)]}})
        return

    assert sandbox == "read-only"
    project = data["project"]["project"]
    if data["request"].startswith("workflow-assets:"):
        scenario = json.loads(data["request"].split(":", 1)[1])
        workflow = data["workflow"]
        assert hashlib.sha256(workflow["instructions"].encode()).hexdigest() == scenario["instructionsSha256"]
        assert list(workflow["request"]) == [scenario["name"].capitalize()]
        result = None
        if scenario["name"] == "begin":
            member = scenario["member"]
            selected = tool("cq_host", "dispatch", {"Select": {"request": {"request": identity(), "roots": [member["id"]],
                "work": {"Explorer": {"mode": "Investigate"}}, "guidance": [], "artifacts": [], "previous": None,
                "limits": data["limits"]}}})["Selection"]["value"]
            choice, = selected["choices"]
            assert choice["members"] == [member]
            claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {
                "id": identity(), "members": [member["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            started = tool("cq_host", "dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": claim["fence"]}})["Status"]["value"]
            settled = poll(started["attempt"])
            assert settled["phase"] == "Completed" and settled["result"] and settled["usageDelivered"], settled
            # A completed child's workspace is released as soon as its result is published (D96).
            assert settled["workspace"] == {"admission": "Removed", "directory": None}, settled
            result = settled["result"]
            tool("cq", "claim", {"project": project, "action": {"Release": {"fence": claim["fence"]}}})
        if scenario["name"] == "review":
            assert workflow["subject"]["result"] == scenario["subject"]
            assert workflow["subject"]["members"] == [scenario["member"]]
        emit({"type": "fixture.workflow", "name": scenario["name"], "instructionsSha256": scenario["instructionsSha256"], "result": result})
        finish({"summary": "Installed workflow instructions and current admitted subject verified"})
        return
    if data["request"].startswith("traffic-growth:"):
        scenario = json.loads(data["request"].split(":", 1)[1])
        members = scenario["members"]
        claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {
            "id": identity(), "members": [member["id"] for member in members], "durationMillis": "180000"}}})["Claimed"]["claim"]
        rounds = []
        for handle in scenario["artifacts"]:
            request = {"request": identity(), "work": {"Explorer": {"mode": "Investigate"}}, "harness": "Codex", "members": members,
                "guidance": [], "artifacts": [handle], "previous": None, "fence": claim["fence"], "limits": data["limits"]}
            started = tool("cq_host", "dispatch", {"Start": {"request": request}})["Status"]["value"]
            explored = poll(started["attempt"])
            assert explored["phase"] == "Completed" and explored["counts"]["evidence"] == len(members) and explored["usageDelivered"], explored
            chained = {**request, "request": identity(), "work": {"Reviewer": {"mode": "Audit"}}, "previous": explored["result"]}
            reviewed = tool("cq_host", "dispatch", {"Start": {"request": chained}})["Status"]["value"]
            reviewed = poll(reviewed["attempt"])
            assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == len(members) and reviewed["usageDelivered"], reviewed
            rounds.append({"input": handle, "explorer": explored, "reviewer": reviewed})
        tool("cq", "claim", {"project": project, "action": {"Release": {"fence": claim["fence"]}}})
        emit({"type": "fixture.growth", "rounds": rounds})
        finish({"summary": "Same members and roles completed small and large handle-only chains"})
        return
    if data["request"].startswith("cohort-fairness:"):
        scenario = json.loads(data["request"].split(":", 1)[1])
        selection = {"roots": scenario["roots"], "work": {"Explorer": {"mode": "Investigate"}},
                     "guidance": [], "artifacts": [], "previous": None, "limits": data["limits"]}

        def select():
            return tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity()}}})["Selection"]["value"]

        first = select()
        assert first["counts"]["inspected"] == 32
        if scenario["blocked"]:
            assert first["choices"] == [] and first["counts"]["unexamined"] == 1 and first["counts"]["excluded"] == 32, first
            advanced = select()
            assert len(advanced["choices"]) == 1 and advanced["choices"][0]["members"][0]["id"] == scenario["free"]["id"], advanced
            assert advanced["counts"]["inspected"] == 32 and advanced["counts"]["unexamined"] == 1
            emit({"type": "fixture.cohort-claimed-pool", "first": first, "advanced": advanced})
            finish({"summary": "Selection advanced beyond 32 foreign-claimed candidates to the unclaimed descendant"})
            return

        def offered(decision):
            assert len(decision["choices"]) == 8 and all(len(value["members"]) == 1 for value in decision["choices"]), decision
            return [value["members"][0]["id"]["number"] for value in decision["choices"]]

        rounds = [offered(first)]
        assert first["counts"]["selected"] == 32 and first["counts"]["unexamined"] == 0
        root = tool("cq", "read", {"project": project, "selection": {"ItemDetail": {"id": scenario["roots"][0]}}})["Detail"]["view"]["item"]
        created = tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": [{"Create": {
            "draft": {**root["draft"], "title": "New arrival"}}}], "fences": [], "reason": "New independent descendant"}})["Changed"]["ack"]["items"][0]
        tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": [{"Reference": {
            "source": root["id"], "expectedSource": root["revision"], "relation": "Produces", "target": created["id"],
            "expectedTarget": created["revision"], "present": True}}], "fences": [], "reason": "Select the new descendant"}})
        for _ in range(3):
            decision = select()
            values = offered(decision)
            assert decision["counts"]["selected"] == 33 and decision["counts"]["unexamined"] == 1
            assert not set(values).intersection(value for previous in rounds for value in previous)
            assert created["id"]["number"] not in values, "New arrival displaced equally eligible older work"
            rounds.append(values)
        assert {value for previous in rounds for value in previous} == {value["number"] for value in scenario["roots"]}
        following = offered(select())
        assert following[0] == created["id"]["number"], "Unreturned arrival was starved by previously offered work"
        emit({"type": "fixture.cohort-fairness", "rounds": rounds, "following": following, "arrival": created["id"]})
        finish({"summary": "All 32 older singletons were offered before repeats; the new descendant was offered next"})
        return
    if data["request"].startswith("cohort-flow:"):
        scenario = json.loads(data["request"].split(":", 1)[1])
        selection = {"request": identity(), "roots": scenario["roots"], "work": {"Worker": {"mode": "Implement"}},
                     "guidance": [], "artifacts": [], "previous": None, "limits": data["limits"]}
        first = tool("cq_host", "dispatch", {"Select": {"request": selection}})["Selection"]["value"]
        assert len(first["choices"]) == 1 and first["choices"][0]["work"] == {"Planner": {}}, first
        choice = first["choices"][0]
        assert len(choice["members"]) == 2 and choice["reason"] == "AssessmentRequired", choice
        claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {
            "id": identity(), "members": [member["id"] for member in choice["members"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
        fence = claim["fence"]
        direct = {"request": choice["id"], "work": choice["work"], "harness": "Codex", "members": choice["members"],
                  "guidance": [], "artifacts": [], "previous": None, "fence": fence, "limits": data["limits"]}
        tool("cq_host", "dispatch", {"Start": {"request": direct}}, denied=True)
        tool("cq_host", "dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": {"claim": identity(), "generation": "1"}}}, denied=True)
        tool("cq_host", "dispatch", {"StartChoice": {"choice": choice["id"], "harness": "Codex", "fence": fence, "members": []}}, denied=True)

        def start(selected):
            command = {"StartChoice": {"choice": selected["id"], "harness": "Codex", "fence": fence}}
            value = tool("cq_host", "dispatch", command)["Status"]["value"]
            assert tool("cq_host", "dispatch", command)["Status"]["value"]["attempt"] == value["attempt"]
            return poll(value["attempt"])

        planned = start(choice)
        assert planned["phase"] == "Completed" and planned["counts"]["assessed"] == 2, planned
        unchanged = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity()}}})["Selection"]["value"]
        assert unchanged["choices"] == [] and unchanged["counts"]["excluded"] == 2, unchanged
        assessed = {**selection, "request": identity(), "artifacts": [planned["result"]]}
        selected = tool("cq_host", "dispatch", {"Select": {"request": assessed}})["Selection"]["value"]
        if scenario["unknown"]:
            assert len(selected["choices"]) == 2 and all(len(value["members"]) == 1 for value in selected["choices"]), selected
            exact = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity(), "previous": planned["result"]}}})["Selection"]["value"]
            assert not any(value["work"] == {"Worker": {"mode": "Implement"}} and len(value["members"]) > 1 for value in exact["choices"]), exact
            finish({"summary": "Unknown compatibility split the automatic implementation choices"})
            return
        assert len(selected["choices"]) == 1 and selected["choices"][0]["reason"] == "CompatibleAssessment", selected
        worker_choice = selected["choices"][0]
        worked = start(worker_choice)
        assert worked["phase"] == "Completed" and worked["counts"]["failed" if scenario["refresh"] else "ready"] == 2, worked
        if scenario["refresh"]:
            refreshed = []
            for round_number in [1, 2]:
                mutations = []
                for member in choice["members"] if round_number == 1 else choice["members"][:1]:
                    view = tool("cq", "read", {"project": project, "selection": {"ItemDetail": {"id": member["id"]}}})["Detail"]["view"]
                    item = view["item"]
                    mutations.append({"Replace": {"id": item["id"], "expected": item["revision"], "draft": {
                        **item["draft"], "labels": ["cohort-selected-refresh-" + str(round_number)]}}})
                tool("cq", "change", {"project": project, "change": {"request": identity(), "mutations": mutations,
                    "fences": [fence], "reason": "Cosmetic revision requires current assessment"}})
                inputs = {**selection, "request": identity(), "artifacts": [planned["result"], worked["result"]]}
                decision = tool("cq_host", "dispatch", {"Select": {"request": inputs}})["Selection"]["value"]
                assert len(decision["choices"]) == 1, decision
                refresh_choice = decision["choices"][0]
                assert refresh_choice["work"] == {"Planner": {}} and refresh_choice["reason"] == "AssessmentRequired"
                assert len(refresh_choice["members"]) == 2
                planned = start(refresh_choice)
                assert planned["phase"] == "Completed" and planned["counts"]["assessed"] == 2, planned
                repeated = tool("cq_host", "dispatch", {"Select": {"request": {**inputs, "request": identity()}}})["Selection"]["value"]
                assert repeated["choices"] == [], repeated
                deferred = tool("cq_host", "dispatch", {"Select": {"request": {**inputs, "request": identity(),
                    "artifacts": [planned["result"], worked["result"]]}}})["Selection"]["value"]
                assert deferred["choices"] == [] and deferred["counts"]["excluded"] == 2, deferred
                refreshed.append(planned)
            emit({"type": "fixture.cohort-refresh", "worker": worked, "assessments": refreshed})
            finish({"summary": "Two exact revision assessments refreshed without retrying unchanged unsuccessful work"})
            return
        review = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity(),
            "work": {"Reviewer": {"mode": "Candidate"}}, "previous": worked["result"]}}})["Selection"]["value"]
        assert len(review["choices"]) == 1 and review["choices"][0]["members"] == choice["members"], review
        reviewed = start(review["choices"][0])
        assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == (1 if scenario["mixed"] else 2), reviewed
        if scenario["mixed"]:
            assert reviewed["counts"]["changesRequested"] == 1

            def snapshot(filter_value):
                def usage(selection):
                    return tool("cq", "usage", {"project": project, "selection": selection})
                summary = usage({"Summary": {"filter": filter_value}})["UsageSummary"]["report"]
                assignments = usage({"Attempts": {"filter": filter_value, "after": None, "snapshot": None, "limit": 20}})["UsageAttempts"]["page"]
                audit = usage({"Audit": {"filter": filter_value, "after": "0", "limit": 100}})["UsageAudit"]["page"]
                assert not assignments["hasMore"] and not audit["hasMore"] and not summary["costs"]["hasMore"]
                del summary["cursor"]
                del summary["costs"]["cursor"]
                return {"summary": summary, "attempts": assignments["entries"], "audit": audit["entries"]}

            def cost(snapshot, attribution):
                entries = snapshot["summary"]["costs"]["entries"]
                assert all(entry["group"]["pricingVersion"] == "synthetic-cohort-fixture" for entry in entries)
                return sum((Decimal(entry["amount"]["value"]) for entry in entries if entry["group"]["attribution"] == attribution), Decimal(0))

            original_filter = {"CohortOnly": {"execution": worker_choice["cohort"]}}
            task_filter = {"TaskOnly": {"item": choice["members"][0]["id"]}}
            original = snapshot(original_filter)
            before = snapshot(task_filter)
            assert cost(original, "Shared") == Decimal("0.125") and cost(before, "Shared") == Decimal("0.375")
            exact = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity(),
                "artifacts": [planned["result"]], "previous": reviewed["result"]}}})["Selection"]["value"]
            assert exact["choices"] == [] and exact["counts"]["excluded"] == 2, exact
            fresh = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity(),
                "artifacts": [planned["result"], reviewed["result"]]}}})["Selection"]["value"]
            assert len(fresh["choices"]) == 1 and fresh["choices"][0]["members"] == choice["members"][:1], fresh
            assert fresh["choices"][0]["reason"] == "FreshFromBase" and fresh["choices"][0]["previous"] is None
            tool("cq", "claim", {"project": project, "action": {"Release": {"fence": fence}}})
            claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {"id": identity(),
                "members": [choice["members"][0]["id"]], "durationMillis": "180000"}}})["Claimed"]["claim"]
            fence = claim["fence"]
            corrected = start(fresh["choices"][0])
            assert corrected["phase"] == "Completed" and corrected["counts"]["ready"] == 1, corrected
            independent = tool("cq_host", "dispatch", {"Select": {"request": {**selection, "request": identity(),
                "work": {"Reviewer": {"mode": "Candidate"}}, "previous": corrected["result"]}}})["Selection"]["value"]
            assert len(independent["choices"]) == 1 and independent["choices"][0]["members"] == choice["members"][:1]
            accepted = start(independent["choices"][0])
            assert accepted["phase"] == "Completed" and accepted["counts"]["accepted"] == 1, accepted
            after = snapshot(task_filter)
            historical = snapshot(original_filter)
            assert historical == original, "Split changed earlier shared accounting, membership or outcomes"
            assert after["summary"]["shared"] == before["summary"]["shared"]
            assert cost(after, "Shared") == Decimal("0.375") and cost(after, "Direct") == Decimal("0.250")
            original_attempts = {value["attempt"]["id"]["value"]: value for value in before["attempts"]}
            assert {value["attempt"]["id"]["value"]: value for value in after["attempts"] if value["attempt"]["id"]["value"] in original_attempts} == original_attempts
            emit({"type": "fixture.cohort-split", "original": original, "historical": historical,
                  "before": before, "after": after, "corrected": corrected, "reviewed": accepted})
            finish({"summary": "Mixed outcomes split by handle without rewriting historical assignments, usage or exact costs"})
            return
        emit({"type": "fixture.cohort", "planner": planned, "worker": worked, "reviewer": reviewed})
        finish({"summary": "Automatic whole-group Planner, Worker, validation and independent candidate review completed by handles"})
        return
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
    if data["request"] == "traffic-growth-bootstrap":
        draft["labels"] = ["traffic-growth"]
    if data["request"].startswith("reviewer-check-failed:"):
        draft["labels"] = ["failed-reviewer-check"]
    cohort = data["request"].startswith("cohort-assessment")
    if cohort:
        draft["labels"] = [data["request"]]
    created = tool("cq", "change", {"project": project, "change": {"request": identity(),
        "mutations": [{"Create": {"draft": draft}} for _ in range(2 if cohort else 1)], "fences": [], "reason": "Fixture task"}})
    members = created["Changed"]["ack"]["items"]
    if data["request"] == "traffic-growth-bootstrap":
        emit({"type": "fixture.growth-seed", "members": members})
        finish({"summary": "Registered fixture artifact owner and unchanged task"})
        return
    claim = tool("cq", "claim", {"project": project, "action": {"Acquire": {"id": identity(), "members": [value["id"] for value in members], "durationMillis": "180000"}}})
    request = {"request": identity(), "work": {"Worker": {"mode": "Implement"}}, "harness": "Codex", "members": members,
               "guidance": [], "artifacts": [], "previous": None, "fence": claim["Claimed"]["claim"]["fence"], "limits": data["limits"]}
    if data["request"] == "cohort-choice-ack":
        selection = {"request": identity(), "roots": [member["id"] for member in members], "work": request["work"],
                     "guidance": [], "artifacts": [], "previous": None, "limits": data["limits"]}
        tool("cq_host", "dispatch", {"Select": {"request": selection}}, denied=True)
        selected = tool("cq_host", "dispatch", {"Select": {"request": selection}})["Selection"]["value"]
        assert selected == tool("cq_host", "dispatch", {"Select": {"request": selection}})["Selection"]["value"]
        assert len(selected["choices"]) == 1 and selected["choices"][0]["members"] == members, selected
        emit({"type": "fixture.selection", "value": selected})
        finish({"summary": "Selection publication acknowledgement replay preserved exact choice identity"})
        return
    if cohort:
        planned = tool("cq_host", "dispatch", {"Start": {"request": {**request, "work": {"Planner": {}}}}})["Status"]["value"]
        planned = poll(planned["attempt"])
        if data["request"] == "cohort-assessment-unknown-check":
            assert planned["phase"] == "Failed" and planned["result"] is None and "unconfigured check" in planned["blocker"], planned
            emit({"type": "fixture.assessment", "statuses": [planned]})
            finish({"summary": "Unconfigured assessment checks were rejected"})
            return
        assert planned["phase"] == "Completed" and planned["counts"]["assessed"] == 2 and planned["next"] == "ConsiderGrouping", planned
        assert planned["counts"]["ready"] == 0 and planned["counts"]["accepted"] == 0
        tool("cq", "apply", {"project": project, "result": planned["result"]}, denied=True)
        reviewed = tool("cq_host", "dispatch", {"Start": {"request": {**request, "request": identity(),
            "work": {"Reviewer": {"mode": "Plan"}}, "previous": planned["result"]}}})["Status"]["value"]
        reviewed = poll(reviewed["attempt"])
        assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == 2, reviewed
        emit({"type": "fixture.assessment", "statuses": [planned, reviewed]})
        finish({"summary": "Compatibility-only plan reviewed by handle; no ledger proposal applied"})
        return
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
    assert worker["counts"]["validationIntermittent"] == (1 if data["request"] == "intermittent-check" else 0) and worker["next"] == "Review", worker
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
    if data["request"] in ["intermittent-check", "intermittent-reviewer-check"]:
        assert reviewed["counts"]["validationFailed"] == 0 and reviewed["next"] == "ConsiderAcceptance", reviewed
        # The reviewer's own observation replaces the inherited one, so only a rerun of the reviewer's check counts here.
        assert reviewed["counts"]["validationIntermittent"] == (1 if data["request"] == "intermittent-reviewer-check" else 0), reviewed
        finish({"summary": "A check that failed once passed on its rerun; the candidate was reviewed without a worker pass"})
        return
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
    assert stopped["workspace"]["admission"] == "Quarantined" and Path(stopped["workspace"]["directory"]).is_dir(), stopped
    finish({"summary": "Worker candidate validated, reviewed by handle, cancellation and permissions verified; integration remains pending"})


if __name__ == "__main__":
    main()
