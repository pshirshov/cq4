"""Evidence for correction of a previously incorporated consumer task."""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import subprocess


def unchanged_reopening(before, values, receipt, jobs, integrations):
    after, run = values["snapshot"], values["run"]
    assert all(after[key] == before[key] for key in ["views", "histories", "claims", "git", "target", "clean"]), "Failed reopening changed the checkpoint"
    assert after["clean"] and after["git"] == after["target"] == run["base"]
    assert not after["claims"]["claims"] and not after["claims"]["integrations"]
    assert not values["statuses"] and not after["localIntegrations"] and not integrations, "Failed reopening started children or integration"
    attempt = run["attempt"]
    assert attempt["parent"] is None and attempt["role"] == "Governor"
    assert receipt["attempt"] == attempt["id"] and receipt["session"] == attempt["session"]
    assert receipt["phase"] == "Settled" and receipt["processSucceeded"] and receipt["usageDelivered"] and receipt["problem"] is None
    job, = jobs
    assert job["workspace"]["attempt"] == attempt["id"] and job["workspace"]["owner"] == attempt["session"] and job["workspace"]["base"] == before["git"]
    observed = job["exit"]
    assert job["phase"] == "Settled" and observed["settled"] and not observed["hostFailure"] and observed["reason"] == "Exited" and observed["code"] == 0 and observed["signal"] is None
    entry, = values["attempts"]
    assert entry["attempt"] == attempt and entry["outcome"]["value"]["state"] == "Completed"
    assert values["observations"] and all(value["upload"]["observation"]["attempt"] == attempt["id"] for value in values["observations"])
    return attempt["id"]["value"]


def answer_comparison(repository, original_commit, candidate):
    records = []
    for commit in [original_commit, candidate]:
        path = ".cq-evaluation/answer.json"
        command = ["git", "-C", str(repository), "rev-parse", commit["value"] + ":" + path]
        blob = subprocess.check_output(command, text=True).strip()
        read = ["git", "-C", str(repository), "show", commit["value"] + ":" + path]
        data = subprocess.check_output(read)
        records.append({"commit": commit, "path": path, "blobCommand": command, "blob": blob, "readCommand": read,
                        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    assert all(records[0][key] == records[1][key] for key in ["blob", "bytes", "sha256"]), "The original answer bytes changed"
    return {"provenance": "Runner-measured Git command outputs; not a CQ Collector artifact or human acceptance", "original": records[0], "candidate": records[1], "identical": True}


def test_only_candidate(repository, before, candidate):
    subprocess.run(["git", "-C", str(repository), "merge-base", "--is-ancestor", before["value"], candidate["value"]], check=True)
    changed = subprocess.check_output(["git", "-C", str(repository), "diff", "--name-only", "-z", before["value"], candidate["value"]]).decode().split("\0")
    paths = sorted(path for path in changed if path)
    assert paths and set(paths) <= {"main_test.go", "wordfreq_test.go"}, "Reopening changed files outside the designated consumer tests"
    assert subprocess.check_output(["git", "-C", str(repository), "ls-tree", "-r", "--name-only", before["value"]]) == subprocess.check_output([
        "git", "-C", str(repository), "ls-tree", "-r", "--name-only", candidate["value"]]), "Reopening added or removed files"
    return {"base": before, "candidate": candidate, "changedFiles": paths, "ancestor": True, "productionAndAnswerUnchanged": True}


def reopening_stage(before, values, settings, integrations, finding):
    support = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    evidence = runpy.run_path(str(Path(__file__).with_name("consumer-evidence.py")))
    after, statuses, artifacts, run = values["snapshot"], values["statuses"], values["artifacts"], values["run"]
    old, = [view for view in before["views"] if view["item"]["id"]["ledger"] == "Tasks" and view["item"]["id"]["number"] == "1"]
    member = {"id": old["item"]["id"], "revision": old["item"]["revision"]}
    workflow = values["governing_input"]["body"]
    assert workflow["workflow"]["request"] == {"Advance": {"roots": [member["id"]], "through": "Integrate"}} and workflow["integrationTarget"] == "refs/heads/integration"
    assert run["base"] == before["git"] == before["target"] and before["clean"]
    assert after["git"] == after["target"] and after["clean"] and after["git"] != before["git"]
    assert not after["claims"]["claims"] and not after["claims"]["integrations"]
    assert len(before["views"]) == len(after["views"]) and len(before["histories"]) == len(after["histories"])
    new = next(view for view in after["views"] if view["item"]["id"] == member["id"])
    history = next(page for page in after["histories"] if page["id"] == member["id"])
    old_history = next(page for page in before["histories"] if page["id"] == member["id"])
    assert not history["page"]["hasMore"] and not old_history["page"]["hasMore"]
    done, reopened = history["page"]["entries"][:2]
    assert history["page"]["entries"][2:] == old_history["page"]["entries"], "Reopening rewrote the completed task history"
    ready = reopened["item"]
    ready_member = {"id": member["id"], "revision": {"value": str(int(member["revision"]["value"]) + 1)}}
    done_member = {"id": member["id"], "revision": {"value": str(int(member["revision"]["value"]) + 2)}}
    assert ready["item"]["id"] == member["id"] and ready["item"]["revision"] == ready_member["revision"]
    assert done["item"] == new and new["item"]["revision"] == done_member["revision"]
    assert ready["refs"] == new["refs"] == old["refs"] and ready["item"]["createdAt"] == new["item"]["createdAt"] == old["item"]["createdAt"]
    content = lambda view: view["item"]["draft"]["content"]["Task"]
    assert content(old)["status"] == content(new)["status"] == "Done" and content(ready)["status"] == "Ready"
    assert content(old)["acceptance"] == content(ready)["acceptance"] == content(new)["acceptance"], "Reopening changed the original acceptance criteria"
    original_draft, ready_draft = copy.deepcopy(old["item"]["draft"]), copy.deepcopy(ready["item"]["draft"])
    for draft in [original_draft, ready_draft]:
        del draft["body"], draft["citations"]
        for field in ["status", "result", "validation"]:
            del draft["content"]["Task"][field]
    assert original_draft == ready_draft, "Reopening changed fields outside status/completion/correction context"
    for previous, current in zip(before["views"], after["views"]):
        assert previous["item"]["id"] == current["item"]["id"]
        if previous != old:
            assert previous == current, "Reopening changed another record"
    for previous, current in zip(before["histories"], after["histories"]):
        if previous["id"] != member["id"]:
            assert previous == current, "Reopening changed another record's history"
    plans = []
    for status in statuses:
        assert status["phase"] == "Completed" and status["result"] is not None and status["usageDelivered"]
        result = artifacts[status["result"]["value"]]["body"]
        request = result["request"]
        if request["work"] in [{"Planner": {}}, {"Reviewer": {"mode": "Plan"}}]:
            assert request["members"] == [member] and result["base"] == before["git"]
            plans.append(status)
            if request["work"] == {"Planner": {}}:
                assert finding in request["artifacts"] and request["previous"] != finding
        else:
            assert request["work"] in [{"Worker": {"mode": "Implement"}}, {"Reviewer": {"mode": "Candidate"}}]
            assert request["members"] == [ready_member], "Execution used stale completed task revisions"
    lineage = support["planning_lineage"](after["histories"], plans, artifacts, run["attempt"]["session"])
    applied, = [value for value in lineage if value["applied"]]
    assert applied["applied"] == [ready_member]
    proposal = artifacts[applied["proposal"]["value"]]["body"]["report"]["Plan"]["proposal"]
    assert proposal["mutations"] == [{"Replace": {"id": member["id"], "draft": ready["item"]["draft"]}}]
    accepted = [value for value in applied["reviews"] if len(value["verdicts"]) == 1 and value["verdicts"][0]["item"] == member["id"] and value["verdicts"][0]["verdict"] == "Accepted"]
    assert accepted, "Reopening lacks an accepted exact Plan review"
    integration, = integrations
    record, local = integration["record"], integration["local"]
    assert after["localIntegrations"] == [local], "Exported integration differs from the local observation"
    intent = record["intent"]
    assert intent == local["intent"] and intent["owner"]["session"] == run["attempt"]["session"] and intent["target"] == "refs/heads/integration"
    assert "Recorded" in record["resolution"], "Reopening integration was not recorded"
    resolution = record["resolution"]["Recorded"]
    assert local["attempted"] and local["observation"] == {"Incorporated": {"target": after["target"]}}
    assert intent["members"] == [ready_member] and intent["candidate"] == resolution["observedTarget"] == after["target"]
    assert resolution["acknowledgement"]["items"] == [done_member]
    assert resolution["acknowledgement"]["request"] == new["item"]["provenance"]["request"] and resolution["acknowledgement"]["cursor"] == done["cursor"]
    assert new["item"]["provenance"]["actor"]["session"] == run["attempt"]["session"]
    chain = evidence["reviewed_chain"](statuses, artifacts, {value["name"]: value for value in settings["checks"]}, intent["reviewer"])
    assert chain["workerResult"] == intent["worker"] and chain["members"] == [ready_member] and chain["candidate"] == after["target"]
    worker, review = [artifacts[chain[key]["value"]]["body"] for key in ["workerResult", "reviewResult"]]
    assert worker["base"] == before["git"] and finding in worker["request"]["artifacts"] and worker["request"]["previous"] != finding
    assert all(value["verdict"] == "Accepted" for value in review["report"]["Review"]["members"])
    assert set(value["check"] for value in review["validation"]) == {"consumer-oracle", "empty-input-sensitivity"}
    assert all(artifacts[value["artifact"]["value"]]["attempt"] == review["attempt"] for value in review["validation"]), "Reviewer did not execute both fresh checks"
    assert any(value["origin"] == "HostObserved" and {"Artifact": {"id": intent["reviewer"]}} in value["citations"] for value in content(new)["validation"])
    return {"member": ready_member, "done": done_member, "planning": lineage, "review": accepted[-1]["result"], "finding": finding,
            "candidate": after["target"], "chain": chain, "integration": intent["id"], "integrationAcknowledgement": resolution["acknowledgement"]}
