"""Evidence binding for inspection of an integrated worked-process candidate."""
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
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
    if manifest.get("stage") == "closeout":
        return runpy.run_path(str(Path(__file__).with_name("process-closeout-evidence.py")))["retained"](directory)
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


def inspected_files(events, files):
    """Successful native Codex reads must cover exact candidate text, including pagination."""
    ranges = {path: set() for path in files}
    completed = set()
    calls = []
    for event in events:
        item = event.get("item", {})
        if event.get("type") != "item.completed" or item.get("type") != "mcp_tool_call" or item.get("server") != "cq_host" or item.get("tool") != "workspace":
            continue
        if "Read" not in item["arguments"] or item["status"] != "completed" or item["error"] is not None:
            continue
        request = item["arguments"]["Read"]
        path = request["path"]
        if path not in files:
            continue
        reply = item["result"]["structured_content"]
        assert "Text" in reply, "Successful workspace read lacks text"
        page = reply["Text"]["page"]
        offset, end = page["offset"], page["next"]
        assert page["path"] == path and offset == request["offset"] and 0 <= offset <= end <= len(files[path])
        assert end == offset + len(page["text"]) and end - offset <= request["limit"]
        assert page["text"] == files[path][offset:end], "Workspace read differs from exact candidate bytes"
        assert page["hasMore"] == (end < len(files[path])), "Workspace read has a false completion marker"
        ranges[path].update(range(offset, end))
        if not page["hasMore"]:
            completed.add(path)
        calls.append({"id": item["id"], "path": path, "offset": offset, "next": end})
    missing = [path for path in files if len(ranges[path]) != len(files[path]) or path not in completed]
    return {"complete": not missing, "missing": missing, "calls": calls,
            "files": {path: {"sha256": hashlib.sha256(text.encode()).hexdigest(), "codePoints": len(text), "readCodePoints": len(ranges[path])} for path, text in files.items()}}


def candidate_text(repository, candidate):
    paths = subprocess.check_output(["git", "-C", str(repository), "ls-tree", "-r", "--name-only", "-z", candidate["value"]]).decode().split("\0")
    return {path: subprocess.check_output(["git", "-C", str(repository), "show", candidate["value"] + ":" + path]).decode("utf-8") for path in paths if path}


def correction_ack(events, correction):
    applied, = [value for value in correction["planning"] if value["applied"]]
    calls, acknowledgements = {}, []
    reviewed = False
    for event in events:
        if event.get("type") not in ["assistant", "user"]:
            continue
        for block in event["message"]["content"]:
            if block.get("type") == "tool_use":
                calls[block["id"]] = block
                if block["name"] == "mcp__cq__apply" and block["input"].get("result") == applied["proposal"]:
                    assert reviewed, "Proposal application preceded its accepted review"
            elif block.get("type") == "tool_result" and block["tool_use_id"] in calls:
                call = calls[block["tool_use_id"]]
                content = block["content"]
                if not isinstance(content, str):
                    content = "".join(value["text"] for value in content if value["type"] == "text")
                try:
                    reply = json.loads(content)
                except ValueError:
                    continue
                if call["name"] == "mcp__cq_host__dispatch" and "Status" in reply:
                    status = reply["Status"]["value"]
                    if status["result"] == correction["review"] and status["phase"] == "Completed" and status["usageDelivered"]:
                        assert status["counts"]["accepted"] == 1 and not status["counts"]["changesRequested"] and not status["counts"]["blocked"]
                        reviewed = True
                if call["name"] == "mcp__cq__apply" and call["input"].get("result") == applied["proposal"] and "Changed" in reply:
                    ack = reply["Changed"]["ack"]
                    assert ack["request"] == applied["request"] and ack["items"] == [correction["member"]]
                    acknowledgements.append(ack)
    assert acknowledgements and all(value == acknowledgements[0] for value in acknowledgements), "Missing or inconsistent stored proposal acknowledgement"
    return acknowledgements[0]


def correction_routes(values, settings):
    run, session = values["run"], values["session"]
    by_attempt = {value["attempt"]["id"]["value"]: value for value in values["attempts"]}
    assert len(by_attempt) == len(values["attempts"]) == len(values["statuses"]) + 1
    assert by_attempt[run["attempt"]["id"]["value"]]["attempt"] == run["attempt"] and run["attempt"]["parent"] is None
    for status in values["statuses"]:
        path = session / "children" / status["attempt"]["value"] / "ticket.json"
        ticket = json.loads(path.read_text())
        child = ticket["attempt"]
        result = values["artifacts"][status["result"]["value"]]["body"]
        work = result["request"]["work"]
        routes = [({"Planner": {}}, "Planner", "Codex"), ({"Reviewer": {"mode": "Plan"}}, "Reviewer", "Pi"),
                  ({"Worker": {"mode": "Implement"}}, "Worker", "Pi"), ({"Reviewer": {"mode": "Candidate"}}, "Reviewer", "Codex")]
        role, harness = next((role, harness) for allowed, role, harness in routes if work == allowed)
        assert child["id"] == status["attempt"] and child["role"] == role and child["harness"] == result["request"]["harness"] == harness
        assert child["model"] == next(value["model"] for value in settings["harnesses"] if value["harness"] == harness)
        assert child["parent"] == run["attempt"]["id"] and child["session"] == run["attempt"]["session"]
        assert by_attempt[child["id"]["value"]]["attempt"] == child and by_attempt[child["id"]["value"]]["assignment"] == ticket["assignment"]
        assert result["request"] == ticket["request"]
        job = json.loads((session / "journal" / (child["id"]["value"] + ".json")).read_text())
        base = result["candidate"] if work == {"Reviewer": {"mode": "Candidate"}} else run["base"]
        assert job["workspace"]["attempt"] == child["id"] and job["workspace"]["owner"] == child["session"] and job["workspace"]["base"] == result["base"] == base
        assert job["phase"] == "Settled" and job["exit"]["settled"] and job["exit"]["code"] == 0 and job["exit"]["reason"] == "Exited" and not job["exit"]["hostFailure"]
    assert all(value["assignment"]["evaluation"] == settings["evaluation"] for value in values["attempts"])
    measured = {value["upload"]["observation"]["attempt"]["value"] for value in values["observations"] if any(
        value["upload"]["observation"]["counters"][name]["value"] is not None and value["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert set(by_attempt) <= measured, "Correction hierarchy lacks observed usage"


def correction_stage(before, after, statuses, artifacts, run, governing_input, finding):
    handoff, = [value for value in before["views"] if value["item"]["id"]["ledger"] == "Handoffs"]
    member = {"id": handoff["item"]["id"], "revision": handoff["item"]["revision"]}
    assert governing_input["body"]["workflow"]["request"] == {"Advance": {"roots": [member["id"]], "through": "Plan"}}
    assert governing_input["attempt"] == run["attempt"]["id"] and governing_input["body"]["integrationTarget"] is None
    assert run["base"] == before["git"] == before["target"] == after["git"] == after["target"] and before["clean"] and after["clean"]
    assert not after["claims"]["claims"] and not after["claims"]["integrations"] and not after["localIntegrations"]
    assert len(statuses) >= 2
    for status in statuses:
        assert status["phase"] == "Completed" and status["result"] is not None and status["usageDelivered"]
        artifact = artifacts[status["result"]["value"]]
        value = artifact["body"]
        request = value["request"]
        assert artifact["attempt"] == value["attempt"] == status["attempt"]
        assert request["members"] == [member] and request["request"] == status["request"]
        assert value["base"] == before["git"] and value["candidate"] is None and not value["validation"]
        assert request["work"] in [{"Planner": {}}, {"Reviewer": {"mode": "Plan"}}], "Correction launched work outside planning"
        if request["work"] == {"Planner": {}}:
            assert finding in request["artifacts"], "Planner did not receive the independent finding"
            assert request["previous"] != finding, "Foreign-scope finding used as mutation authority"
    lineage = planning_lineage(after["histories"], statuses, artifacts, run["attempt"]["session"])
    applied, = [value for value in lineage if value["applied"]]
    new_member, = applied["applied"]
    assert new_member == {"id": member["id"], "revision": {"value": str(int(member["revision"]["value"]) + 1)}}
    accepted = [review for review in applied["reviews"] if len(review["verdicts"]) == 1 and review["verdicts"][0]["item"] == member["id"] and review["verdicts"][0]["verdict"] == "Accepted"]
    assert accepted, "Applied correction lacks an Accepted review of the exact proposal"
    replacement = next(value for value in after["views"] if value["item"]["id"] == member["id"])
    proposal = artifacts[applied["proposal"]["value"]]["body"]["report"]["Plan"]["proposal"]
    assert proposal["mutations"] == [{"Replace": {"id": member["id"], "draft": replacement["item"]["draft"]}}], "Applied draft differs from the reviewed proposal"
    assert len(before["views"]) == len(after["views"]) and len(before["histories"]) == len(after["histories"])
    for old, new in zip(before["views"], after["views"]):
        if old != handoff:
            assert new == old, "Correction changed another record"
        else:
            assert new["item"]["id"] == new_member["id"] and new["item"]["revision"] == new_member["revision"]
            assert new["refs"] == old["refs"] and new["item"]["createdAt"] == old["item"]["createdAt"]
    for old, new in zip(before["histories"], after["histories"]):
        assert not old["page"]["hasMore"] and not new["page"]["hasMore"]
        if old["id"] != member["id"]:
            assert new == old, "Correction changed unrelated history"
        else:
            assert new["id"] == old["id"] and new["page"]["entries"][1:] == old["page"]["entries"], "Correction rewrote history"
            assert new["page"]["entries"][0]["item"] == next(value for value in after["views"] if value["item"]["id"] == member["id"])
    return {"member": new_member, "planning": lineage, "review": accepted[-1]["result"], "finding": finding}


def stage_evidence(directory):
    def read(path):
        return json.loads(path.read_text())
    session, = (directory / "sessions").iterdir()
    run = read(session / "run.json")
    artifacts = read(directory / "candidate-evidence.json")
    governing, = [value for value in artifacts.values() if value["kind"] == "Input" and value["attempt"] == run["attempt"]["id"]]
    return {"statuses": read(directory / "dispatch-statuses.json"), "artifacts": artifacts, "run": run,
            "governing_input": governing, "attempts": read(directory / "attempts.json")["UsageAttempts"]["page"]["entries"],
            "observations": [value for path in directory.glob("usage-audit-*.json") for value in read(path)["UsageAudit"]["page"]["entries"]],
            "snapshot": read(directory / "after.json"), "session": session}


def retained_assessment(directory, baseline, origin, depth):
    assert depth < 16, "Assessment continuation chain exceeds its bound"
    consumed = dict(baseline["inputsSha256"])

    def read(path):
        data = path.read_bytes()
        consumed[str(path)] = hashlib.sha256(data).hexdigest()
        return json.loads(data)

    manifest = read(directory / "result.json")
    assert manifest["status"] in ["assessment-passed", "assessment-not-accepted", "failed"]
    assert Path(manifest["baselineEvidence"]) == origin
    prior = retained_assessment(Path(manifest["restoredEvidence"]), baseline, origin, depth + 1) if manifest.get("restoredEvidence") is not None else None
    if prior is not None:
        consumed.update(prior["inputsSha256"])
    before = read(directory / "before.json")
    assert before["views"] == (baseline["views"] if prior is None else prior["snapshot"]["views"])
    assert before["histories"] == (baseline["histories"] if prior is None else prior["snapshot"]["histories"])
    ids = set(baseline["attemptIds"] if prior is None else prior["attemptIds"])
    parts = [read(origin / "combined-usage-summary.json")["UsageSummary"]["report"]] if prior is None else list(prior["usageParts"])
    current = before
    context = [baseline["proof"]["finalChain"][key] for key in ["workerResult", "reviewResult"]] if prior is None else list(prior["context"])
    integrations = list(baseline["integrations"] if prior is None else prior["integrations"])
    reopening = runpy.run_path(str(Path(__file__).with_name("process-reopening-evidence.py")))
    completed_prefix = manifest["status"] == "failed" and [stage["stage"] for stage in manifest["stages"]] == ["reopening", "correction"]
    if manifest["status"] == "failed" and not completed_prefix:
        assert prior is not None and [stage["stage"] for stage in manifest["stages"]] == ["reopening"], "Only an unchanged failed reopening can be retried"
        stage_dir = directory / "reopening"
        saved = read(stage_dir / "result.json")
        assert not saved["archiveErrors"] and saved["exit"] == 0 and not read(stage_dir / "archive.json")["errors"]
        for path in stage_dir.rglob("*"):
            if path.is_file():
                consumed[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        values = stage_evidence(stage_dir)
        session = values["session"]
        assert not list((session / "children").glob("*/ticket.json")) and not list((session / "integrations").glob("*.json"))
        attempt = reopening["unchanged_reopening"](before, values, read(session / "receipt.json"),
            [read(path) for path in (session / "journal").glob("*.json")], read(stage_dir / "integrations.json"))
        assert attempt not in ids, "Failed continuation reused an attempt"
        assert manifest["candidate"] == before["git"] == prior["snapshot"]["git"]
        parts.append(read(stage_dir / "usage-summary.json")["UsageSummary"]["report"])
        return {**prior, "snapshot": values["snapshot"], "usageParts": parts, "attemptIds": sorted(ids | {attempt}),
                "dump": stage_dir / "cq-database.dump", "inputsSha256": consumed}
    assert completed_prefix or manifest["accounting"] == "reconciled"
    reopened = None
    if not completed_prefix:
        bundle_text = (directory / "assessment-bundle.json").read_text()
        metadata = read(directory / "bundle-publication.json")
        assert metadata["sha256"] == hashlib.sha256(bundle_text.encode()).hexdigest()
    previous = None
    correction = None
    reconcile = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["reconcile_usage"]
    for stage in manifest["stages"]:
        stage_dir = directory / stage["stage"]
        for path in stage_dir.rglob("*.json"):
            consumed[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        values = stage_evidence(stage_dir)
        settings = read(stage_dir / "settings.json")
        saved = read(stage_dir / "result.json")
        assert not saved["archiveErrors"] and saved["exit"] == 0 and saved["accounting"] == "reconciled"
        if completed_prefix:
            assert saved == stage and saved["status"] == stage["stage"] + "-passed"
        if stage["stage"] == "reopening":
            assert prior is not None and reopened is None and correction is None and previous is None
            added = read(stage_dir / "integrations.json")
            reopened = reopening["reopening_stage"](current, values, settings, added, prior["result"])
            assert reopened == saved["reopening"]
            correction_routes(values, settings)
            transcript = values["session"] / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout"
            consumed[str(transcript)] = hashlib.sha256(transcript.read_bytes()).hexdigest()
            assert correction_ack([json.loads(line) for line in transcript.read_text().splitlines() if line.strip()], reopened) == saved["acknowledgement"]
            assert reopening["test_only_candidate"](directory / "consumer", current["git"], reopened["candidate"]) == saved["git"]
            checkout = read(stage_dir / "incorporation-before-checkout.json")
            assert checkout["git"] == current["git"] and checkout["target"] == reopened["candidate"] and checkout["clean"]
            current = values["snapshot"]
            context.extend(reopened["chain"][key] for key in ["workerResult", "reviewResult"])
            integrations.extend(added)
        elif stage["stage"] == "correction":
            assert prior is not None and correction is None and previous is None
            correction = correction_stage(current, values["snapshot"], values["statuses"], values["artifacts"], values["run"], values["governing_input"], prior["result"])
            assert saved["correction"] == correction
            correction_routes(values, settings)
            transcript = values["session"] / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout"
            consumed[str(transcript)] = hashlib.sha256(transcript.read_bytes()).hexdigest()
            assert correction_ack([json.loads(line) for line in transcript.read_text().splitlines() if line.strip()], correction) == saved["acknowledgement"]
            current = values["snapshot"]
        else:
            session = values.pop("session")
            ticket_path, = (session / "children").glob("*/ticket.json")
            ticket = read(ticket_path)
            job = read(session / "journal" / (ticket["attempt"]["id"]["value"] + ".json"))
            members = [{"id": value["item"]["id"], "revision": value["item"]["revision"]} for value in current["views"] if value["item"]["id"]["ledger"] == "Tasks"]
            member_views = [value for value in current["views"] if value["item"]["id"] in [member["id"] for member in members]]
            guidance_views = [value for value in current["views"] if value not in member_views]
            harness = "Pi" if stage["stage"] == "precheck" else "Codex"
            expected = {"candidate": current["git"], "members": members,
                "guidance": [{"id": value["item"]["id"], "revision": value["item"]["revision"]} for value in guidance_views],
                "memberViews": member_views, "guidanceViews": guidance_views, "bundle": {"metadata": metadata, "body": bundle_text},
                "context": [metadata["id"], *context],
                "harness": harness, "model": next(value["model"] for value in settings["harnesses"] if value["harness"] == harness),
                "previous": previous, "views": current["views"], "histories": current["histories"], "evaluation": settings["evaluation"]}
            assessment = audit_stage(expected, ticket=ticket, job=job, **values)
            assert assessment == saved["assessment"]
            if stage["stage"] == "standalone":
                if "inspectionComplete" in saved:
                    transcript = session / "payload" / ticket["attempt"]["id"]["value"] / "stdout"
                    consumed[str(transcript)] = hashlib.sha256(transcript.read_bytes()).hexdigest()
                    inspection = inspected_files([json.loads(line) for line in transcript.read_text().splitlines() if line.strip()], candidate_text(directory / "consumer", current["git"]))
                    assert saved["inspectionComplete"] == inspection["complete"], "Retained inspection claim differs from native read coverage"
                    assert read(stage_dir / "workspace-inspection.json") == {"candidate": current["git"], "attempt": ticket["attempt"]["id"], **inspection}
                    passed = assessment["accepted"] and inspection["complete"]
                    if "historyReferences" in json.loads(bundle_text):
                        history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))["inspected"](
                            [json.loads(line) for line in transcript.read_text().splitlines() if line.strip()], current["histories"])
                        assert read(stage_dir / "history-inspection.json") == history and saved["historyInspectionComplete"] == history["complete"]
                        passed = passed and history["complete"]
                else:
                    assert not assessment["accepted"], "Pre-coverage evidence cannot establish inspected acceptance"
                    passed = False
                assert saved["status"] == manifest["status"] == ("assessment-passed" if passed else "assessment-not-accepted")
            previous = assessment["result"]
        new_ids = [value["attempt"]["id"]["value"] for value in values["attempts"]]
        assert len(set(new_ids)) == len(new_ids) and not ids.intersection(new_ids), "Continuation reused an attempt"
        ids.update(new_ids)
        parts.append(read(stage_dir / "usage-summary.json")["UsageSummary"]["report"])
        combined = read(stage_dir / "combined-usage-summary.json")
        reconcile(parts, combined["UsageSummary"]["report"])
    prefix = (["reopening"] if reopened is not None else []) + (["correction"] if correction is not None else [])
    assert [stage["stage"] for stage in manifest["stages"]] == prefix + ([] if completed_prefix else ["precheck", "standalone"])
    assert manifest["candidate"] == current["git"]
    if completed_prefix:
        assert reopened is not None and correction is not None and not (directory / "precheck").exists() and not (directory / "standalone").exists()
        dump = directory / "correction/cq-database.dump"
        consumed[str(dump)] = hashlib.sha256(dump.read_bytes()).hexdigest()
        return {**prior, "snapshot": current, "usage": combined, "usageParts": [combined["UsageSummary"]["report"]],
                "attemptIds": sorted(ids), "dump": dump, "inputsSha256": consumed, "context": context, "integrations": integrations,
                "reopening": reopened, "correction": correction, "completedCorrectionPrefix": True}
    bundle_value = json.loads(bundle_text)
    if "answerByteComparison" in bundle_value:
        question, = [view for view in baseline["views"] if view["item"]["id"]["ledger"] == "Questions"]
        citation = next(value["File"] for value in question["item"]["draft"]["citations"] if "File" in value and value["File"]["path"] == ".cq-evaluation/answer.json")
        comparison = reopening["answer_comparison"](directory / "consumer", {"value": citation["revision"]}, current["git"])
        assert read(directory / "answer-byte-comparison.json") == comparison == bundle_value["answerByteComparison"]
    if "historyReferences" in bundle_value:
        assert bundle_value["historyReferences"] == runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))["references"](current["histories"])
    else:
        assert bundle_value["histories"] == current["histories"]
    assert bundle_value["candidate"] == current["git"] and bundle_value["integrations"] == integrations
    dump = directory / "standalone/cq-database.dump"
    consumed[str(dump)] = hashlib.sha256(dump.read_bytes()).hexdigest()
    consumed[str(directory / "assessment-bundle.json")] = hashlib.sha256(bundle_text.encode()).hexdigest()
    return {"snapshot": current, "usage": combined, "usageParts": [combined["UsageSummary"]["report"]], "attemptIds": sorted(ids), "dump": dump, "result": previous,
            "assessment": assessment, "inputsSha256": consumed, "context": context, "integrations": integrations,
            "reopening": reopened if reopened is not None else (None if prior is None else prior.get("reopening")),
            "correction": correction if correction is not None else (None if prior is None else prior.get("correction"))}
