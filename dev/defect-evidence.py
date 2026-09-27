"""Defect workflow checkpoints: relationships and authority, not record counts alone."""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import shlex


def restored(snapshot, archived):
    states = [copy.deepcopy(value) for value in [snapshot, archived]]
    for state in states:
        del state["claims"]["snapshot"]["digest"]
    assert states[0] == states[1], "Restored checkpoint differs from its archive"


def records(snapshot):
    views, histories = snapshot["views"], snapshot["histories"]
    history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))
    history["references"](histories)
    assert all(next(entry for entry in histories if entry["id"] == view["item"]["id"])["page"]["entries"][0]["item"] == view for view in views)
    by_ledger = lambda ledger: [view for view in views if view["item"]["id"]["ledger"] == ledger]
    defect, = by_ledger("Defects")
    research, = by_ledger("Researches")
    hypotheses = by_ledger("Hypothesis")
    assert len(hypotheses) >= 3 and len(views) == 2 + len(hypotheses), "Intake requires only defect, empirical research and a branching hypothesis tree"
    def derived(view, source):
        return {"relation": "DerivedFrom", "target": source["item"]["id"]} in view["refs"]
    assert derived(research, defect), "Research must derive from the observed defect"
    parents = [view for view in hypotheses if not any(ref["relation"] == "DerivedFrom" and ref["target"]["ledger"] == "Hypothesis" for ref in view["refs"])]
    parent, = parents
    assert derived(parent, defect) or derived(parent, research)
    branches = [view for view in hypotheses if view != parent]
    assert all(derived(branch, parent) for branch in branches), "Hypothesis branches are not linked to their actual parent"
    assert all([ref["target"] for ref in branch["refs"] if ref["relation"] == "DerivedFrom" and ref["target"]["ledger"] == "Hypothesis"] == [parent["item"]["id"]] for branch in branches), "Hypothesis branch has another parent"
    assert len({view["item"]["draft"]["content"]["Hypothesis"]["claim"].strip() for view in hypotheses}) == len(hypotheses), "Hypothesis claims are duplicated"
    for page in histories:
        for entry in page["page"]["entries"]:
            view = entry["item"]
            content = view["item"]["draft"]["content"]
            assert not any(ref["relation"] == "PartOf" for ref in view["refs"]), "Intake acquired premature milestone ownership"
            if "Defect" in content:
                assert content["Defect"]["status"] == "Open" and content["Defect"]["cause"] is None and not content["Defect"]["resolution"]
            elif "Research" in content:
                assert content["Research"]["status"] == "Open" and not content["Research"]["findings"] and content["Research"]["conclusion"] is None
            else:
                assert content["Hypothesis"]["status"] == "Proposed" and content["Hypothesis"]["adjudication"] is None and not content["Hypothesis"]["evidence"], "Intake adjudicated a hypothesis before a probe"
    return {"defect": defect["item"]["id"], "research": research["item"]["id"], "parent": parent["item"]["id"],
            "branches": [view["item"]["id"] for view in branches]}


def reviewed_applications(events, planning):
    applied = {value["proposal"]["value"]: value for value in planning if value["applied"]}
    calls, observed, acknowledgements = {}, set(), {}
    canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
    for event in events:
        if event.get("type") not in ["assistant", "user"]:
            continue
        for block in event["message"]["content"]:
            if block.get("type") == "tool_use":
                calls[block["id"]] = block
                if block["name"] == "mcp__cq__apply" and block["input"].get("result", {}).get("value") in applied:
                    proposal = block["input"]["result"]["value"]
                    assert proposal in observed, "Proposal application preceded its accepted review"
            elif block.get("type") == "tool_result" and block["tool_use_id"] in calls and not block.get("is_error", False):
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
                    if status["phase"] == "Completed" and status["usageDelivered"] and status["result"] is not None:
                        for key, proposal in applied.items():
                            for review in proposal["reviews"]:
                                if review["result"] == status["result"] and review["verdicts"] and all(member["verdict"] == "Accepted" for member in review["verdicts"]):
                                    assert status["counts"]["accepted"] == len(review["verdicts"]) and not status["counts"]["changesRequested"] and not status["counts"]["blocked"]
                                    observed.add(key)
                if call["name"] == "mcp__cq__apply" and call["input"].get("result", {}).get("value") in applied and "Changed" in reply:
                    key = call["input"]["result"]["value"]
                    ack = reply["Changed"]["ack"]
                    assert ack["request"] == applied[key]["request"] and canonical(ack["items"]) == canonical(applied[key]["applied"]), "Proposal acknowledgement differs from history"
                    assert key not in acknowledgements or acknowledgements[key] == ack, "Inconsistent proposal acknowledgement"
                    acknowledgements[key] = ack
    assert applied and set(acknowledgements) == set(applied), "Missing stored proposal acknowledgement"
    return acknowledgements


def hierarchy(values, settings, routes):
    governor, session = values["run"]["attempt"], values["session"]
    attempts = {value["attempt"]["id"]["value"]: value for value in values["attempts"]}
    statuses = {value["attempt"]["value"]: value for value in values["statuses"]}
    tickets = [json.loads(path.read_text()) for path in (session / "children").glob("*/ticket.json")]
    assert len(attempts) == len(values["attempts"]) == len(statuses) + 1 == len(tickets) + 1
    assert len(statuses) == len(values["statuses"]) and attempts[governor["id"]["value"]]["attempt"] == governor
    assert governor["role"] == "Governor" and governor["parent"] is None and governor["harness"] == "Claude"
    for ticket in tickets:
        child, request = ticket["attempt"], ticket["request"]
        status = statuses[child["id"]["value"]]
        matches = [(role, harness) for work, role, harness in routes if work == request["work"]]
        assert len(matches) == 1, "Checkpoint executed an unauthorized role or mode"
        role, harness = matches[0]
        assert child["role"] == role and child["harness"] == request["harness"] == harness
        assert child["model"] == next(value["model"] for value in settings["harnesses"] if value["harness"] == harness)
        assert child["parent"] == governor["id"] and child["session"] == governor["session"]
        assert all(attempts[child["id"]["value"]][field] == ticket[field] for field in ["attempt", "assignment"])
        assert status["request"] == request["request"] and status["usageDelivered"]
        assert ticket["assignment"]["members"] == [member["id"] for member in request["members"]]
        if status["phase"] == "Completed":
            artifact = values["artifacts"][status["result"]["value"]]
            result = artifact["body"]
            assert artifact["kind"] == "Result" and artifact["attempt"] == result["attempt"] == child["id"]
            assert result["request"] == request and result["base"] == values["run"]["base"] and result["candidate"] is None and not result["validation"]
            job = json.loads((session / "journal" / (child["id"]["value"] + ".json")).read_text())
            assert job["workspace"]["attempt"] == child["id"] and job["workspace"]["owner"] == child["session"] and job["workspace"]["base"] == result["base"]
            assert job["phase"] == "Settled" and job["exit"]["settled"] and job["exit"]["code"] == 0 and job["exit"]["reason"] == "Exited" and not job["exit"]["hostFailure"]
        else:
            assert status["phase"] in ["Failed", "Cancelled"] and status["result"] is None
    assert all(value["assignment"]["evaluation"] == settings["evaluation"] for value in values["attempts"])
    measured = {value["upload"]["observation"]["attempt"]["value"] for value in values["observations"] if any(
        value["upload"]["observation"]["counters"][name]["value"] is not None and value["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert set(attempts) <= measured, "Checkpoint hierarchy lacks observed usage"


def reviewed_records(histories, applied):
    revisions = [entry["item"]["item"] for page in histories for entry in page["page"]["entries"]]
    requests = [proposal["request"] for proposal in applied]
    assert all(item["id"]["ledger"] == "Defects" or item["provenance"]["request"] in requests for item in revisions), "Investigation records bypassed reviewed planning"


def settled(values):
    snapshot = values["snapshot"]
    receipt = json.loads((values["session"] / "receipt.json").read_text())
    assert receipt["processSucceeded"] and receipt["usageDelivered"] and receipt["report"] is not None
    assert snapshot["git"] == values["run"]["base"] and snapshot["clean"] and not snapshot["localIntegrations"]
    assert not snapshot["claims"]["claims"] and not snapshot["claims"]["integrations"]
    canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
    assert canonical(snapshot["claims"]["members"]) == canonical({"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in snapshot["views"])


def begin(values, settings):
    snapshot = values["snapshot"]
    proof = records(snapshot)
    histories = snapshot["histories"]
    hierarchy(values, settings, [({"Explorer": {"mode": "Investigate"}}, "Explorer", "Codex"), ({"Planner": {}}, "Planner", "Codex"),
                                ({"Reviewer": {"mode": "Plan"}}, "Reviewer", "Pi")])
    settled(values)
    workflow = values["governing_input"]["body"]["workflow"]["request"]
    assert workflow == {"Begin": {"roots": []}} and values["governing_input"]["body"]["integrationTarget"] is None
    allowed = [{"Explorer": {"mode": "Investigate"}}, {"Planner": {}}, {"Reviewer": {"mode": "Plan"}}]
    executed = [values["artifacts"][status["result"]["value"]]["body"] for status in values["statuses"] if status["phase"] == "Completed"]
    assert executed and all(value["request"]["work"] in allowed for value in executed)
    assert any(value["request"]["work"] == allowed[0] for value in executed), "No native investigation informed the proposed hypotheses"
    explorations = [status["result"] for status in values["statuses"] if status["phase"] == "Completed" and values["artifacts"][status["result"]["value"]]["body"]["request"]["work"] == allowed[0]]
    assert any(value["request"]["work"] == allowed[1] and any(handle in value["request"]["artifacts"] or handle == value["request"]["previous"] for handle in explorations) for value in executed), "Planning did not consume investigation evidence"
    lineage = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))["planning_lineage"](
        histories, values["statuses"], values["artifacts"], values["run"]["attempt"]["session"])
    applied = [value for value in lineage if value["applied"]]
    assert applied and all(any(review["verdicts"] and all(member["verdict"] == "Accepted" for member in review["verdicts"]) for review in proposal["reviews"]) for proposal in applied), "Investigation plan lacks an accepted independent review"
    reviewed_records(histories, applied)
    events = [json.loads(line) for line in (values["session"] / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout").read_text().splitlines() if line.strip()]
    return {**proof, "planning": applied, "acknowledgements": reviewed_applications(events, applied)}


def empirical(directory, values, settings, prior, stage):
    before = prior["values"]["snapshot"]
    snapshot = values["snapshot"]
    assert snapshot["views"] == before["views"] and snapshot["histories"] == before["histories"], "Empirical execution mutated ledger state or history"
    settled(values)
    work, role, harness = ({"Worker": {"mode": "Probe"}}, "Worker", "Codex") if stage == "probe" else ({"Explorer": {"mode": "Research"}}, "Explorer", "Pi")
    hierarchy(values, settings, [(work, role, harness)])
    workflow = values["governing_input"]["body"]["workflow"]["request"]
    intake = records(before)
    assert workflow == {"Advance": {"roots": [intake["defect"]], "through": "Explore"}}
    assert values["governing_input"]["body"]["integrationTarget"] is None
    completed = [status for status in values["statuses"] if status["phase"] == "Completed"]
    status, = completed
    report = values["artifacts"][status["result"]["value"]]["body"]
    expected_ids = intake["branches"] if stage == "probe" else [intake["research"]]
    expected = [{"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in before["views"] if view["item"]["id"] in expected_ids]
    canonical = lambda entries: sorted(json.dumps(value, sort_keys=True) for value in entries)
    assert canonical(report["request"]["members"]) == canonical(expected), "Empirical assignment differs from the recorded hypotheses/research"
    context = json.loads((directory / "bundle-publication.json").read_text())
    input_artifact = values["artifacts"][context["id"]["value"]]
    body = (directory / "empirical-input.json").read_text()
    assert input_artifact["body"] == json.loads(body) and input_artifact["sha256"] == hashlib.sha256(body.encode()).hexdigest() == context["sha256"]
    assert context["actor"]["role"] == "Human" and context["attempt"] == prior["values"]["run"]["attempt"]["id"]
    inputs = [artifact["body"] for artifact in values["artifacts"].values() if artifact["kind"] == "Input" and artifact["attempt"] == report["attempt"]]
    frozen, = inputs
    assert frozen["base"] == values["run"]["base"] == before["git"] and frozen["input"]["request"] == report["request"]
    assert context["id"] in report["request"]["artifacts"] and {"metadata": context, "body": body} in frozen["input"]["artifacts"]
    assert canonical(frozen["input"]["members"]) == canonical(view for view in before["views"] if view["item"]["id"] in expected_ids)
    expected_guidance = [view for view in before["views"] if view["item"]["id"] not in expected_ids]
    assert canonical(frozen["input"]["guidance"]) == canonical(expected_guidance), "Empirical execution omitted required recorded context"
    members = report["report"]["Evidence"]["members"]
    assert canonical(member["item"] for member in members) == canonical(expected_ids)
    assert all(member["disposition"] in ["Findings", "Inconclusive"] and member["evidence"] for member in members)
    assert all(evidence["origin"] == "ModelDeclared" and evidence["citations"] for member in members for evidence in member["evidence"])
    if stage == "research":
        previous = prior["manifest"]["proof"]["result"]
        assert previous in report["request"]["artifacts"] or report["request"]["previous"] == previous
        assert json.loads(body)["observations"] == prior["manifest"]["proof"]["execution"]["observations"]
        assert all(any({"Artifact": {"id": context["id"]}} in evidence["citations"] for evidence in member["evidence"]) for member in members), "Research omitted the verified observation input"
        return {"result": status["result"], "members": expected, "probe": previous, "observations": context["id"]}
    native = runpy.run_path(str(Path(__file__).with_name("defect-probe-evidence.py")))
    artifacts = json.loads((directory / "native-artifacts.json").read_text())
    stdout = native["decoded"](artifacts, report["attempt"], "stdout", values["session"].name)
    stderr = native["decoded"](artifacts, report["attempt"], "stderr", values["session"].name)
    payload = values["session"] / "payload" / report["attempt"]["value"]
    assert stdout == (payload / "stdout").read_bytes() and stderr == (payload / "stderr").read_bytes()
    fixture = native["fixture"](directory / "consumer", before["git"])
    workspace = json.loads((values["session"] / "workspaces" / report["attempt"]["value"] / "workspace.json").read_text())
    assert workspace["spec"]["attempt"] == report["attempt"] and workspace["spec"]["owner"] == values["run"]["attempt"]["session"] and workspace["spec"]["base"] == before["git"]
    tree = Path(workspace["directory"])
    recorder = json.loads(body)["recorder"]
    assert recorder["path"] == str(directory / "probe-recorder.py")
    assert hashlib.sha256(Path(recorder["path"]).read_bytes()).hexdigest() == recorder["sha256"] == json.loads((directory / "source-sha256.json").read_text())["dev/defect-probe-capture.py"]
    assert json.loads(body)["command"] == shlex.join(native["command"](recorder))
    execution = native["observations"]([json.loads(line) for line in stdout.decode().splitlines() if line.strip()], recorder, fixture, tree)
    assert execution["observations"] == json.loads((tree / "cq-probe-observations.json").read_text())
    assert native["fixture"](tree, before["git"]) == fixture
    import subprocess
    assert subprocess.check_output(["git", "-C", str(tree), "rev-parse", "HEAD"], text=True).strip() == before["git"]["value"]
    assert not subprocess.check_output(["git", "-C", str(tree), "diff", "HEAD", "--name-only"], text=True).strip(), "Probe modified tracked fixture files"
    assert subprocess.check_output(["git", "-C", str(tree), "ls-files", "--others", "--exclude-standard"], text=True).splitlines() == ["cq-probe-observations.json"], "Probe left unexpected untracked files"
    return {"result": status["result"], "members": expected, "execution": execution,
            "transcript": native["native_id"](report["attempt"], "stdout"), "fixture": fixture}


def checkpoint(directory):
    return retained(directory, 0)


def retained(directory, depth):
    assert depth < 8, "Defect checkpoint chain exceeds its bound"
    read = lambda path: json.loads(path.read_text())
    manifest = read(directory / "result.json")
    assert manifest["status"] in ["begin-passed", "probe-passed", "research-passed"] and not manifest["archiveErrors"] and manifest["accounting"] == "reconciled"
    support = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    values = support["stage_evidence"](directory)
    prior = retained(Path(manifest["baselineEvidence"]), depth + 1) if manifest["stage"] != "begin" else None
    settings = read(directory / "settings.json")
    if prior is None:
        assert begin(values, settings) == manifest["proof"]
    else:
        assert manifest["baselineDumpSha256"] == prior["dumpSha256"]
        restored(read(directory / "before.json"), prior["values"]["snapshot"])
        assert empirical(directory, values, settings, prior, manifest["stage"]) == manifest["proof"]
    usage_parts = [] if prior is None else [prior["usage"]]
    usage_parts.append(read(directory / "usage-summary.json")["UsageSummary"]["report"])
    combined = read(directory / "combined-usage-summary.json")["UsageSummary"]["report"]
    runpy.run_path(str(Path(__file__).with_name("process-evidence.py")))["reconcile_usage"](
        usage_parts, combined)
    ids = [value["attempt"]["id"]["value"] for value in values["attempts"]]
    if prior is not None:
        ids += prior["attemptIds"]
    assert len(ids) == len(set(ids)), "Continuation reused an attempt"
    digest = hashlib.sha256((directory / "cq-database.dump").read_bytes()).hexdigest()
    assert digest == manifest["dumpSha256"] and values["run"]["base"] == manifest["base"]
    return {"values": values, "manifest": manifest, "dump": directory / "cq-database.dump",
            "dumpSha256": digest, "usage": combined, "attemptIds": ids}
