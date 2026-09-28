"""Evidence for a new Handoff-only session after a settled, incorporated producer."""
import hashlib
import json
from pathlib import Path
import runpy


def native_calls(events):
    calls = {}
    for event in events:
        for block in event.get("message", {}).get("content", []):
            if not isinstance(block, dict):
                continue
            if block.get("type") == "tool_use":
                calls[block["id"]] = block
            elif block.get("type") == "tool_result" and block["tool_use_id"] in calls:
                content = block["content"]
                if not isinstance(content, str):
                    content = "".join(value["text"] for value in content if value["type"] == "text")
                try:
                    reply = json.loads(content)
                except ValueError:
                    continue
                yield calls[block["tool_use_id"]], reply


def producer(directory, read):
    manifest = read(directory / "result.json")
    assert manifest["status"] == "failed" and manifest["stage"] == "resume" and not manifest["archiveErrors"], "Closeout requires a failed archived resume"
    session, = (directory / "sessions").iterdir()
    run = read(session / "run.json")
    job = read(session / "journal" / (run["attempt"]["id"]["value"] + ".json"))
    assert job["phase"] == "Settled" and job["exit"]["settled"] and not job["exit"]["hostFailure"] and job["exit"]["reason"] == "ExecutionDeadline", "Producer is not a settled deadline interruption"
    assert all(read(path)["phase"] == "Settled" for path in (session / "journal").glob("*.json")), "Producer hierarchy is not settled"
    local, = [read(path) for path in (session / "integrations").glob("*.json")]
    candidate = local["intent"]["candidate"]
    assert local["attempted"] and local["observation"] == {"Incorporated": {"target": candidate}}, "Producer has no unambiguous incorporated candidate"
    views, histories = read(directory / "items.json"), read(directory / "histories.json")
    tasks = [view["item"] for view in views if view["item"]["id"]["ledger"] == "Tasks"]
    assert len(tasks) == len(local["intent"]["members"]) == 2
    for member in local["intent"]["members"]:
        task, = [task for task in tasks if task["id"] == member["id"]]
        assert task["revision"] == {"value": str(int(member["revision"]["value"]) + 1)} and task["draft"]["content"]["Task"]["status"] == "Done", "Producer task is not its incorporated revision"
    return {"manifest": manifest, "run": run, "session": session, "local": local, "candidate": candidate,
            "views": views, "histories": histories, "statuses": read(directory / "dispatch-statuses.json"),
            "artifacts": read(directory / "candidate-evidence.json"), "settings": read(directory / "settings.json")}


def closeout_change(before, after, run, governing_input, events, children, integrations):
    assert not children and not integrations, "Closeout dispatched children or attempted integration"
    assert governing_input["attempt"] == run["attempt"]["id"] and governing_input["body"]["integrationTarget"] is None
    assert before["git"] == before["target"] == after["git"] == after["target"] and before["clean"] and after["clean"], "Closeout changed Git"
    assert not before["claims"]["claims"] and not before["claims"]["integrations"], "Closeout requires ordinary expiry of previous authority"
    assert not after["claims"]["claims"] and not after["claims"]["integrations"], "Closeout retained authority"
    assert len(before["views"]) == len(after["views"]) and len(before["histories"]) == len(after["histories"])
    handoff, = [view for view in before["views"] if view["item"]["id"]["ledger"] == "Handoffs"]
    member = handoff["item"]["id"]
    current, = [view for view in after["views"] if view["item"]["id"] == member]
    assert current["refs"] == handoff["refs"] and current["item"]["createdAt"] == handoff["item"]["createdAt"], "Closeout changed Handoff relationships"
    assert current["item"]["revision"] == {"value": str(int(handoff["item"]["revision"]["value"]) + 1)}, "Closeout requires exactly one Handoff revision"
    actor = current["item"]["provenance"]["actor"]
    assert actor["session"] == run["attempt"]["session"] and actor["role"] == "Governor", "Closeout reused historical authority"
    for old in before["views"]:
        new, = [view for view in after["views"] if view["item"]["id"] == old["item"]["id"]]
        if old != handoff:
            assert new == old, "Closeout changed an unrelated record"
    for old in before["histories"]:
        new, = [history for history in after["histories"] if history["id"] == old["id"]]
        assert not old["page"]["hasMore"] and not new["page"]["hasMore"]
        if old["id"] == member:
            assert new["page"]["entries"][1:] == old["page"]["entries"] and new["page"]["entries"][0]["item"] == current, "Closeout rewrote history"
        else:
            assert new == old, "Closeout changed unrelated history"
    acquired, changed, released = None, False, False
    for call, reply in native_calls(events):
        if call["name"] == "mcp__cq__claim" and "Acquire" in call["input"]["action"] and "Claimed" in reply:
            lease = reply["Claimed"]["claim"]
            assert (acquired is None or acquired == lease["fence"]) and lease["members"] == [member] and lease["owner"]["session"] == run["attempt"]["session"], "Closeout acquired another scope"
            acquired = lease["fence"]
        if call["name"] == "mcp__cq__change" and "Changed" in reply:
            change = call["input"]["change"]
            assert acquired is not None and change["fences"] == [acquired], "Closeout mutation lacks its fresh fence"
            assert change["mutations"] == [{"Replace": {"id": member, "expected": handoff["item"]["revision"], "draft": current["item"]["draft"]}}]
            assert reply["Changed"]["ack"]["request"] == current["item"]["provenance"]["request"]
            changed = True
        if call["name"] == "mcp__cq__claim" and "Release" in call["input"]["action"] and "Failed" not in reply:
            assert changed and call["input"]["action"]["Release"]["fence"] == acquired
            released = True
    assert acquired is not None and changed and released, "Closeout lacks fresh claim, mutation or release evidence"
    return {"member": {"id": member, "revision": current["item"]["revision"]}, "session": run["attempt"]["session"], "fence": acquired}


def retained(directory):
    consumed = {}

    def read(path):
        data = path.read_bytes()
        consumed[str(path)] = hashlib.sha256(data).hexdigest()
        return json.loads(data)

    manifest = read(directory / "result.json")
    assert manifest["status"] == "integrated-candidate-passed" and manifest["stage"] == "closeout" and manifest["exit"] == 0 and not manifest["archiveErrors"] and manifest["accounting"] == "reconciled"
    origin = Path(manifest["baselineEvidence"])
    failed = Path(manifest["restoredEvidence"])
    frozen = read(directory / "producer-snapshot.json")
    files = runpy.run_path(str(Path(__file__).with_name("release-evaluate")))["evidence_files"]
    assert frozen == files(failed), "Failed producer archive changed"
    consumed.update({str(failed / name): digest for name, digest in frozen.items()})
    prior = producer(failed, read)
    assert Path(prior["manifest"]["baselineEvidence"]) == origin
    assert manifest["baselineDumpSha256"] == frozen["cq-database.dump"], "Closeout restored a different database archive"
    replay = read(failed / "checkpoint-replay.json")
    for name, digest in replay["inputsSha256"].items():
        assert hashlib.sha256((origin / name).read_bytes()).hexdigest() == digest, "Original question checkpoint changed"
        consumed[str(origin / name)] = digest
    assert runpy.run_path(str(Path(__file__).with_name("consumer-eval")))["same_runtime"](manifest["release"], prior["manifest"]["release"])
    helper = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    values = helper["stage_evidence"](directory)
    session = values["session"]
    before = read(directory / "before.json")
    assert before["views"] == prior["views"] and before["histories"] == prior["histories"] and before["git"] == prior["candidate"]
    assert values["run"]["attempt"]["session"] != prior["run"]["attempt"]["session"], "Closeout reused producer session"
    job = read(session / "journal" / (values["run"]["attempt"]["id"]["value"] + ".json"))
    assert job["phase"] == "Settled" and job["exit"]["settled"] and not job["exit"]["hostFailure"] and job["exit"]["reason"] == "Exited" and job["exit"]["code"] == 0
    transcript = session / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout"
    events = [json.loads(line) for line in transcript.read_text().splitlines() if line.strip()]
    consumed[str(transcript)] = hashlib.sha256(transcript.read_bytes()).hexdigest()
    closeout = closeout_change(before, values["snapshot"], values["run"], values["governing_input"], events,
        list((session / "children").glob("*/ticket.json")), list((session / "integrations").glob("*.json")))
    assert closeout == manifest["closeout"] and not values["statuses"]
    predicates = runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))
    initial, = (origin / "sessions").iterdir()
    checkpoint = predicates["question_checkpoint"](read(origin / "items.json"), read(origin / "histories.json"),
        [read(path) for path in (initial / "children").glob("*/ticket.json")], read(initial / "receipt.json"), read(origin / "checkpoint-handoff.json")["Claims"]["preview"])
    assert checkpoint == replay["checkpoint"]
    answer = read(directory / "supplied-answer.json")
    assert (directory / "supplied-answer.json").read_bytes() == (failed / "supplied-answer.json").read_bytes()
    predicates["supplied_answer"](checkpoint, answer)
    citations = read(failed / "answer-change.json")["command"]["Change"]["input"]["change"]["mutations"][0]["Replace"]["draft"]["citations"]
    citation, = [value for value in citations if value.get("File", {}).get("path") == ".cq-evaluation/answer.json"]
    integrations = read(directory / "producer-integrations.json")
    assert [value["local"] for value in integrations] == [prior["local"]]
    proof = predicates["integrated_resume"](checkpoint, answer, citation, values["snapshot"]["views"], values["snapshot"]["histories"],
        prior["statuses"], prior["artifacts"], integrations, prior["run"], prior["candidate"], values["snapshot"]["claims"], {value["name"]: value for value in prior["settings"]["checks"]})
    assert proof == manifest["progress"] and proof["candidate"] == manifest["candidate"]
    parts = [read(path / "usage-summary.json")["UsageSummary"]["report"] for path in [origin, failed, directory]]
    predicates["reconcile_usage"](parts, read(directory / "combined-usage-summary.json")["UsageSummary"]["report"])
    attempts = [attempt for path in [origin, failed, directory] for attempt in read(path / "attempts.json")["UsageAttempts"]["page"]["entries"]]
    ids = [value["attempt"]["id"]["value"] for value in attempts]
    assert len(ids) == len(set(ids)), "Closeout duplicated historical usage"
    current, = values["attempts"]
    assert current["attempt"] == values["run"]["attempt"] and not current["assignment"]["evaluation"]["assessor"]
    observed = {entry["upload"]["observation"]["attempt"]["value"] for entry in values["observations"] if all(
        entry["upload"]["observation"]["counters"][name]["value"] is not None and entry["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert current["attempt"]["id"]["value"] in observed, "Closeout lacks required observed usage"
    for path in directory.rglob("*"):
        if path.is_file():
            consumed[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"proof": proof, "project": prior["run"]["project"], "run": prior["run"], "views": values["snapshot"]["views"], "histories": values["snapshot"]["histories"],
            "attemptIds": ids, "statuses": prior["statuses"], "artifacts": prior["artifacts"], "settings": prior["settings"], "answer": answer,
            "integrations": integrations, "planning": helper["planning_lineage"](prior["histories"], prior["statuses"], prior["artifacts"], prior["run"]["attempt"]["session"]),
            "inputsSha256": consumed, "closeout": {"proof": closeout, "failedProducer": str(failed), "failedReceipt": read(prior["session"] / "receipt.json"), "receipt": read(session / "receipt.json")}}
