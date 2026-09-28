"""Bind whole-process Audit to retained defect evidence and observed native inspection."""
import hashlib
import json
from pathlib import Path
import runpy


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def experiments(path):
    report = read(path)
    seen, known, entries = set(), 0, []
    for entry in report["runs"]:
        directory = Path(entry["directory"])
        assert digest(directory / "result.json") == entry["manifestSha256"], "Experimental manifest changed"
        assert digest(directory / "usage-summary.json") == entry["usageSha256"], "Experimental usage changed"
        manifest = read(directory / "result.json")
        usage = read(directory / "usage-summary.json")["UsageSummary"]["report"]
        ids = sorted(value["attempt"]["id"]["value"] for value in read(directory / "attempts.json")["UsageAttempts"]["page"]["entries"])
        assert ids == entry["attempts"] and len(ids) == len(set(ids)) and not set(ids) & seen, "Experimental attempts duplicated or changed"
        tokens = sum(int(usage[key]["total"]["known"]) for key in ["direct", "shared", "unattributed"])
        assert tokens == entry["knownTokens"] and manifest["status"] == entry["status"]
        assert all(usage[key] == entry[key] for key in ["incompleteMeters", "attemptsWithoutMeters"])
        seen.update(ids)
        known += tokens
        entries.append({**entry, "error": manifest.get("error"), "reverification":
            read(directory / "plan-verification.json")["reason"] if (directory / "plan-verification.json").exists() else None})
    assert len(seen) == report["distinctAttempts"] and known == report["knownTokens"]
    return {"path": str(path), "sha256": digest(path), "runs": entries, "distinctAttempts": len(seen), "knownTokens": known,
            "provenance": "Derived once from original operational SessionSummary records; rejected branches remain rejected"}


def inputs(prior, experiment_path, rubric):
    directory = Path(prior["dump"]).parent
    stages, artifacts = [], {}
    for _ in range(6):
        raw = read(directory / "result.json")
        manifest = raw if raw["status"] != "failed" else runpy.run_path(str(Path(__file__).with_name("defect-plan-replay.py")))["effective"](directory, raw)
        stages.append({"directory": str(directory), "stage": manifest["stage"], "originalStatus": raw["status"],
            "originalError": raw.get("error"), "effectiveStatus": manifest["status"], "manifestSha256": digest(directory / "result.json"),
            "dumpSha256": manifest["dumpSha256"], "proof": manifest["proof"]})
        artifacts.update(read(directory / "candidate-evidence.json"))
        if manifest["stage"] == "begin":
            break
        directory = Path(manifest["baselineEvidence"])
    assert [stage["stage"] for stage in stages] == ["upstream", "integrate", "plan", "research", "probe", "begin"]
    by_stage = {stage["stage"]: stage for stage in stages}
    integration_directory = Path(by_stage["integrate"]["directory"])
    incorporation = by_stage["integrate"]["proof"]
    handles = [by_stage[stage]["proof"]["result"] for stage in ["probe", "research"]]
    handles += [incorporation["chain"][key] for key in ["workerResult", "reviewResult"]]
    handles += [value["artifact"] for value in incorporation["chain"]["validation"]]
    history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))
    process = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    snapshot = prior["values"]["snapshot"]
    files = process["candidate_text"](Path(prior["dump"]).parent / "consumer", snapshot["git"])
    rejected = experiments(experiment_path)
    assert set(prior["attemptIds"]) <= {attempt for entry in rejected["runs"] for attempt in entry["attempts"]}, "Experiment report omits a successful-chain attempt"
    bundle = {"provenance": "Runner-exported evidence, not human judgment or new HostObserved validation", "rubric": rubric,
        "candidate": snapshot["git"], "specification": files["README.md"], "stages": list(reversed(stages)),
        "integration": read(integration_directory / "integrations.json"),
        "originalTestExecution": read(integration_directory / "original-test-execution.json"),
        "historyReferences": history["references"](snapshot["histories"]),
        "historyInstructions": "Read every history revision through CQ History, starting before latest+1 and following descending pages to revision 1.",
        "requiredWorkspaceFiles": sorted(files), "experiments": rejected, "chainUsage": prior["usage"],
        "results": {key: {name: value[name] for name in ["kind", "sha256", "attempt"]} for key, value in artifacts.items() if value["kind"] in ["Result", "Validation"]}}
    raw = {value["metadata"]["id"]["value"]: value for artifact in artifacts.values() if artifact["kind"] == "Input"
           for value in artifact["body"].get("input", {}).get("artifacts", [])}
    return {"bundle": bundle, "handles": handles, "artifacts": {handle["value"]: artifacts[handle["value"]] for handle in handles},
            "resolved": [raw[handle["value"]] for handle in handles]}


def bound(snapshot, publication, body, prepared):
    resolved = [{"metadata": publication, "body": body}]
    for handle, value in zip(prepared["handles"], prepared["resolved"], strict=True):
        artifact = prepared["artifacts"][handle["value"]]
        assert value["metadata"] == {key: field for key, field in artifact.items() if key != "body"}
        assert hashlib.sha256(value["body"].encode()).hexdigest() == artifact["sha256"] and json.loads(value["body"]) == artifact["body"]
        resolved.append(value)
    member_views = [view for view in snapshot["views"] if view["item"]["id"]["ledger"] == "Tasks"]
    assert len(member_views) == 1
    guidance_views = [view for view in snapshot["views"] if view not in member_views]
    encoded = json.dumps({"project": member_views[0]["item"]["id"]["project"], "members": member_views, "guidance": guidance_views,
                          "artifacts": resolved, "previous": None}, ensure_ascii=False, separators=(",", ":"))
    body_bytes = len(encoded.encode())
    result = {"bodyBytes": body_bytes, "requestByteBound": 16 * 1024, "upperBound": body_bytes + 16 * 1024 + len(',"request":'), "hostInputByteBound": 192 * 1024}
    assert result["upperBound"] <= result["hostInputByteBound"], "Fully encoded assessment input exceeds host bound"
    return result


def unchanged(before, after):
    assert after["views"] == before["views"] and after["histories"] == before["histories"], "Audit changed retained records or history"
    assert after["git"] == before["git"] and after["clean"] and not after["localIntegrations"], "Audit changed the incorporated workspace"


def assess(directory, values, settings, prior):
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    process = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    planning = runpy.run_path(str(Path(__file__).with_name("defect-plan-evidence.py")))
    core["settled"](values)
    core["hierarchy"](values, settings, [({"Reviewer": {"mode": "Audit"}}, "Reviewer", "Codex")])
    before, after = prior["values"]["snapshot"], values["snapshot"]
    unchanged(before, after)
    assert len(values["statuses"]) == 1 and values["statuses"][0]["phase"] == "Completed"
    assert next(route["model"] for route in settings["harnesses"] if route["harness"] == "Codex") == "gpt-6-astra"
    assert settings["evaluation"] == {**read(Path(prior["dump"]).parent / "settings.json")["evaluation"], "assessor": True}
    governing = values["governing_input"]["body"]
    assert governing["workflow"] is None and governing["integrationTarget"] is None and not settings["checks"]
    publication = read(directory / "bundle-publication.json")
    body = (directory / "empirical-input.json").read_text()
    bundle = json.loads(body)
    assert publication["actor"]["role"] == "Human" and publication["attempt"] == prior["values"]["run"]["attempt"]["id"]
    assert publication["sha256"] == hashlib.sha256(body.encode()).hexdigest()
    assert hashlib.sha256(bundle["rubric"].encode()).hexdigest() == read(directory / "source-sha256.json")["dev/defect-assessment.md"]
    prepared = inputs(prior, Path(bundle["experiments"]["path"]), bundle["rubric"])
    assert bundle == prepared["bundle"], "Audit evidence differs from retained process"
    assert read(directory / "input-bound.json") == bound(before, publication, body, prepared)
    status = values["statuses"][0]
    report = values["artifacts"][status["result"]["value"]]["body"]
    assert report["request"]["artifacts"] == [publication["id"], *prepared["handles"]]
    frozen, = [value["body"] for value in values["artifacts"].values() if value["kind"] == "Input" and value["attempt"] == report["attempt"]]
    planning["materialized"](report, frozen, publication, body, {key: value["body"] for key, value in prepared["artifacts"].items()}, before["git"])
    members = [view for view in before["views"] if view["item"]["id"]["ledger"] == "Tasks"]
    guidance = [view for view in before["views"] if view not in members]
    for key, views in [("members", members), ("guidance", guidance)]:
        assert frozen["input"][key] == views
        assert report["request"][key] == [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in views]
    assert report["request"]["previous"] is None and frozen["input"]["previous"] is None
    review = report["report"]["Review"]
    assert review["proposal"] is None and len(review["members"]) == 1 and review["members"][0]["item"] == members[0]["item"]["id"]
    transcript = values["session"] / "payload" / report["attempt"]["value"] / "stdout"
    events = [json.loads(line) for line in transcript.read_text().splitlines() if line.strip()]
    workspace = process["inspected_files"](events, process["candidate_text"](directory / "consumer", before["git"]))
    history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))["inspected"](events, before["histories"])
    return {"candidate": before["git"], "result": status["result"], "review": review, "workspace": workspace, "history": history,
            "accepted": review["members"][0]["verdict"] == "Accepted" and workspace["complete"] and history["complete"]}
