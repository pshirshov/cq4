"""Evidence binding for inspection of an integrated worked-process candidate."""
import hashlib
import json
from pathlib import Path
import runpy
import uuid


def planning_lineage(histories, statuses, artifacts, session):
    records = []
    for status in statuses:
        if status["phase"] != "Completed" or status["result"] is None:
            continue
        handle = status["result"]
        artifact = artifacts[handle["value"]]
        result = artifact["body"]
        if "Plan" not in result["report"] or result["report"]["Plan"]["proposal"] is None:
            continue
        request = str(uuid.UUID(bytes=hashlib.md5(("cq-proposal:" + handle["value"]).encode()).digest(), version=3))
        applied = [entry for page in histories for entry in page["page"]["entries"]
                   if entry["item"]["item"]["provenance"]["request"]["value"] == request]
        reviews = []
        for other in statuses:
            if other["phase"] != "Completed" or other["result"] is None:
                continue
            value = artifacts[other["result"]["value"]]["body"]
            if value["request"]["work"] == {"Reviewer": {"mode": "Plan"}} and value["request"]["previous"] == handle:
                assert value["request"]["members"] == result["request"]["members"] and value["attempt"] != result["attempt"], "Plan review assignment differs from proposal"
                reviews.append({"result": other["result"], "verdicts": value["report"]["Review"]["members"]})
        if applied:
            assert status["usageDelivered"] and artifact["attempt"] == result["attempt"] == status["attempt"]
            assert all(entry["item"]["item"]["provenance"]["actor"]["session"] == session for entry in applied), "Proposal application reused another session"
            assert len({entry["cursor"]["value"] for entry in applied}) == 1, "Proposal application is not one ledger change"
        records.append({"proposal": handle, "request": {"value": request}, "reviews": reviews,
                        "applied": [{"id": entry["item"]["item"]["id"], "revision": entry["item"]["item"]["revision"]} for entry in applied]})
    assert any(value["applied"] for value in records), "No retained proposal application links"
    return records


def retained(directory):
    consumed = {}

    def read(path):
        data = path.read_bytes()
        consumed[str(path)] = hashlib.sha256(data).hexdigest()
        return json.loads(data)

    manifest = read(directory / "result.json")
    assert manifest["status"] == "integrated-candidate-passed" and not manifest["archiveErrors"] and manifest["accounting"] == "reconciled"
    origin = Path(manifest["baselineEvidence"])
    replay = read(directory / "checkpoint-replay.json")
    for name, digest in replay["inputsSha256"].items():
        assert hashlib.sha256((origin / name).read_bytes()).hexdigest() == digest, "Consumed question checkpoint changed"
        consumed[str(origin / name)] = digest
    initial_session, = (origin / "sessions").iterdir()
    predicates = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))
    checkpoint = predicates["question_checkpoint"](read(origin / "items.json"), read(origin / "histories.json"),
        [read(path) for path in (initial_session / "children").glob("*/ticket.json")], read(initial_session / "receipt.json"),
        read(origin / "checkpoint-handoff.json")["Claims"]["preview"])
    assert checkpoint == replay["checkpoint"]
    answer = read(directory / "supplied-answer.json")
    predicates["supplied_answer"](checkpoint, answer)
    answer_change = read(directory / "answer-change.json")
    citations = answer_change["command"]["Change"]["input"]["change"]["mutations"][0]["Replace"]["draft"]["citations"]
    citation, = [value for value in citations if value.get("File", {}).get("path") == ".cq-evaluation/answer.json"]
    session, = (directory / "sessions").iterdir()
    run = read(session / "run.json")
    views, histories = read(directory / "items.json"), read(directory / "histories.json")
    statuses, artifacts = read(directory / "dispatch-statuses.json"), read(directory / "candidate-evidence.json")
    settings, integrations = read(directory / "settings.json"), read(directory / "integrations.json")
    claims = read(directory / "checkpoint-handoff.json")["Claims"]["preview"]
    proof = predicates["integrated_resume"](checkpoint, answer, citation, views, histories, statuses, artifacts, integrations, run,
        manifest["candidate"], claims, {value["name"]: value for value in settings["checks"]})
    assert proof == manifest["progress"], "Integrated checkpoint proof changed"
    for name in ["source-sha256.json", "combined-usage-summary.json", "attempts.json"]:
        read(directory / name)
    consumed[str(directory / "cq-database.dump")] = hashlib.sha256((directory / "cq-database.dump").read_bytes()).hexdigest()
    for path in directory.glob("usage-audit-*.json"):
        read(path)
    attempts = read(origin / "attempts.json")["UsageAttempts"]["page"]["entries"] + read(directory / "attempts.json")["UsageAttempts"]["page"]["entries"]
    attempt_ids = [value["attempt"]["id"]["value"] for value in attempts]
    assert len(attempt_ids) == len(set(attempt_ids)), "Baseline reused an attempt identity"
    return {"proof": proof, "project": run["project"], "run": run, "views": views, "histories": histories, "attemptIds": attempt_ids,
            "statuses": statuses, "artifacts": artifacts, "settings": settings, "answer": answer, "integrations": integrations,
            "planning": planning_lineage(histories, statuses, artifacts, run["attempt"]["session"]), "inputsSha256": consumed}


def audit_stage(expected, statuses, artifacts, ticket, run, job, governing_input, attempts, observations, snapshot):
    def identities(values):
        return sorted(json.dumps(value, sort_keys=True) for value in values)

    assert len(statuses) == 1, "Assessment must execute exactly one reviewer"
    status = statuses[0]
    assert status["phase"] == "Completed" and status["result"] is not None and status["usageDelivered"], "Audit did not deliver a completed result"
    artifact = artifacts[status["result"]["value"]]
    result = artifact["body"]
    request = result["request"]
    assert artifact["kind"] == "Result" and artifact["attempt"] == result["attempt"] == ticket["attempt"]["id"] == status["attempt"]
    assert request == ticket["request"] and request["request"] == status["request"]
    assert request["work"] == {"Reviewer": {"mode": "Audit"}} and request["harness"] == expected["harness"]
    assert request["members"] == expected["members"] and request["guidance"] == expected["guidance"], "Audit changed its exact process scope"
    assert request["previous"] == expected["previous"] and request["artifacts"] == expected["context"], "Audit omitted required evidence context"
    assert run["base"] == result["base"] == job["workspace"]["base"] == expected["candidate"], "Audit inspected another candidate"
    assert result["candidate"] is None and result["validation"] == [], "Audit supplied fictitious executable evidence"
    observed = job["exit"]
    assert job["phase"] == "Settled" and observed is not None and observed["settled"] and not observed["hostFailure"] and observed["reason"] == "Exited" and observed["code"] == 0 and observed["signal"] is None
    inputs = [value["body"] for value in artifacts.values() if value["kind"] == "Input" and value["attempt"] == result["attempt"]]
    assert len(inputs) == 1 and inputs[0]["base"] == expected["candidate"] and inputs[0]["input"]["request"] == request, "Audit execution input differs from its request/base"
    frozen = inputs[0]["input"]
    assert frozen["members"] == expected["memberViews"] and frozen["guidance"] == expected["guidanceViews"], "Audit used different record snapshots"
    bundle, = [value for value in frozen["artifacts"] if value["metadata"]["id"] == expected["bundle"]["metadata"]["id"]]
    assert bundle == expected["bundle"], "Audit did not receive the exact evidence bundle"
    assert governing_input["body"]["integrationTarget"] is None
    workflow = governing_input["body"]["workflow"]
    if expected["previous"] is None:
        assert workflow is None
    else:
        assert workflow["request"] == {"Review": {"result": expected["previous"], "mode": "Audit"}}, "Standalone review entry point was not used"
        subject = workflow["subject"]
        assert subject["result"] == expected["previous"] and subject["members"] == expected["members"] and subject["work"] == {"Reviewer": {"mode": "Audit"}}
        assert frozen["previous"] == artifacts[expected["previous"]["value"]]["body"], "Standalone review lost its exact admitted subject"
    assert governing_input["kind"] == "Input" and governing_input["attempt"] == run["attempt"]["id"]
    assert snapshot["views"] == expected["views"] and snapshot["histories"] == expected["histories"], "Read-only assessment changed the process records"
    assert snapshot["git"] == snapshot["target"] == expected["candidate"] and snapshot["clean"], "Read-only assessment changed Git"
    claims = snapshot["claims"]
    assert not claims["claims"] and not claims["integrations"]
    assert identities(claims["members"]) == identities({"id": value["item"]["id"], "revision": value["item"]["revision"]} for value in expected["views"])
    assert not snapshot["localIntegrations"], "Standalone review attempted integration"
    reports = result["report"]["Review"]["members"]
    assert identities(value["item"] for value in reports) == identities(value["id"] for value in expected["members"])
    assert result["report"]["Review"]["proposal"] is None, "Assessment unexpectedly proposed a change"
    child, governor = ticket["attempt"], run["attempt"]
    assert child["role"] == "Reviewer" and child["harness"] == expected["harness"] and child["model"] == expected["model"]
    assert governor["role"] == "Governor" and governor["parent"] is None and child["parent"] == governor["id"] and child["session"] == governor["session"]
    assert job["workspace"]["owner"] == governor["session"] and job["workspace"]["attempt"] == child["id"]
    by_attempt = {value["attempt"]["id"]["value"]: value for value in attempts}
    assert len(attempts) == len(by_attempt) == 2
    assert all(by_attempt[child["id"]["value"]][field] == ticket[field] for field in ["attempt", "assignment"])
    assert by_attempt[governor["id"]["value"]]["attempt"] == governor
    assert all(value["assignment"]["evaluation"] == expected["evaluation"] for value in attempts)
    measured = {entry["upload"]["observation"]["attempt"]["value"] for entry in observations if any(
        entry["upload"]["observation"]["counters"][name]["value"] is not None and entry["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert set(by_attempt) <= measured, "Assessment hierarchy lacks observed usage"
    return {"candidate": expected["candidate"], "members": expected["members"], "result": status["result"], "reports": reports,
            "accepted": all(value["verdict"] == "Accepted" for value in reports), "standalone": expected["previous"] is not None}
