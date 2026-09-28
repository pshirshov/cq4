"""Bind the empirical repair to fresh validation, exact incorporation and retained history."""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import subprocess


def original_tests(before, after):
    assert before == after, "Repair removed or rewrote the original regression test module"


def original_execution(observation, candidate):
    assert observation["candidate"] == candidate and observation["command"][1:] == ["-m", "unittest", "-v", "test_count_words"]
    assert observation["exit"] == 0 and observation["stdout"] == ""
    lines = observation["stderr"].splitlines()
    expected = [f"{name} (test_count_words.CountingTest.{name}) ... ok" for name in
                ["test_ascii_delimiters", "test_cli", "test_count_sort_and_case", "test_empty"]]
    assert lines[:4] == expected and any(line.startswith("Ran 4 tests in ") for line in lines) and lines[-1] == "OK", "Original tests did not all execute successfully"


def execute_original(directory, candidate, executable):
    checkout = directory / "original-tests"
    subprocess.run(["git", "clone", "--quiet", "--no-checkout", "--no-hardlinks", str(directory / "consumer"), str(checkout)], check=True, capture_output=True, timeout=30)
    subprocess.run(["git", "-C", str(checkout), "checkout", "--quiet", "--detach", candidate["value"]], check=True, capture_output=True, timeout=30)
    observed = {"value": subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()}
    assert observed == candidate
    command = [executable, "-m", "unittest", "-v", "test_count_words"]
    result = subprocess.run(command, cwd=checkout, text=True, capture_output=True, timeout=30)
    observation = {"provenance": "Runner execution at the recorded candidate; not a CQ Collector artifact", "candidate": observed,
                   "command": command, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    (directory / "original-test-execution.json").write_text(json.dumps(observation, indent=2) + "\n")
    original_execution(observation, candidate)


def candidate_scope(repository, base, candidate):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repository), *args])
    git("merge-base", "--is-ancestor", base["value"], candidate["value"])
    paths = sorted(value for value in git("diff", "--name-only", "-z", base["value"], candidate["value"]).decode().split("\0") if value)
    assert "synthetic_tokens.py" in paths and all(path == "synthetic_tokens.py" or
        ("/" not in path and path.startswith("test_") and path.endswith(".py")) for path in paths), "Repair changed files outside the helper and tests"
    preserved = {}
    for path in ["README.md", "count_words.py"]:
        old, new = [git("show", commit["value"] + ":" + path) for commit in [base, candidate]]
        assert old == new, "Repair changed the specification or consumer CLI"
        preserved[path] = hashlib.sha256(new).hexdigest()
    original_tests(*(git("show", commit["value"] + ":test_count_words.py").decode() for commit in [base, candidate]))
    assert any(path.startswith("test_") and path != "test_count_words.py" for path in paths), "Repair omitted additional regression tests"
    return {"base": base, "candidate": candidate, "changedFiles": paths, "preservedSha256": preserved, "originalTestsPreserved": True}


def incorporated(before, after, integration, target, run, checks, task):
    old = next(view for view in before["views"] if view["item"]["id"] == task)
    new = next(view for view in after["views"] if view["item"]["id"] == task)
    member = {"id": task, "revision": old["item"]["revision"]}
    done_member = {"id": task, "revision": {"value": str(int(member["revision"]["value"]) + 1)}}
    assert len(before["views"]) == len(after["views"]) and len(before["histories"]) == len(after["histories"])
    assert [view for view in before["views"] if view != old] == [view for view in after["views"] if view != new], "Integration changed another record"
    for previous, current in zip(before["histories"], after["histories"]):
        assert previous["id"] == current["id"] and not previous["page"]["hasMore"] and not current["page"]["hasMore"]
        if previous["id"] == task:
            assert current["page"]["entries"][1:] == previous["page"]["entries"], "Integration changed prior task history"
            done = current["page"]["entries"][0]
            assert done["item"] == new
        else:
            assert previous == current, "Integration changed another record's history"
    content = lambda view: view["item"]["draft"]["content"]["Task"]
    assert content(old)["status"] == "Ready" and content(old)["result"] is None and not content(old)["validation"]
    assert content(new)["status"] == "Done" and content(new)["result"] and content(new)["validation"]
    drafts = [copy.deepcopy(view["item"]["draft"]) for view in [old, new]]
    for draft in drafts:
        for field in ["status", "result", "validation"]:
            del draft["content"]["Task"][field]
    assert drafts[0] == drafts[1], "Integration changed the reviewed task contract"
    assert new["item"]["revision"] == done_member["revision"] and old["refs"] == new["refs"] and old["item"]["createdAt"] == new["item"]["createdAt"]
    record, local = integration["record"], integration["local"]
    intent = record["intent"]
    assert after["localIntegrations"] == [local] and local["intent"] == intent
    assert intent["owner"]["role"] == "Governor" and intent["owner"]["session"] == run["attempt"]["session"]
    assert intent["project"] == task["project"] and intent["target"] == "refs/heads/integration" and intent["checks"] == checks
    assert intent["expected"] == before["git"] == run["base"] and intent["members"] == [member]
    assert intent["candidate"] == target and target != before["git"]
    assert local["attempted"] and local["observation"] == {"Incorporated": {"target": target}}
    assert "Recorded" in record["resolution"], "Integration is not durably recorded"
    resolution = record["resolution"]["Recorded"]
    ack = resolution["acknowledgement"]
    assert resolution["observedTarget"] == target and ack["items"] == [done_member] and ack["cursor"] == done["cursor"]
    assert ack["request"] == intent["change"]["request"] == new["item"]["provenance"]["request"]
    assert intent["change"]["mutations"] == [{"Replace": {"id": task, "expected": member["revision"], "draft": new["item"]["draft"]}}]
    assert new["item"]["provenance"]["actor"] == intent["owner"]
    citations = [{"Artifact": {"id": intent[key]}} for key in ["worker", "reviewer"]]
    assert any(value["origin"] == "HostObserved" and all(citation in value["citations"] for citation in citations) for value in content(new)["validation"])
    assert after["git"] == before["git"] and after["clean"] and not after["claims"]["claims"] and not after["claims"]["integrations"]
    canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
    assert canonical(after["claims"]["members"]) == canonical({"id": view["item"]["id"], "revision": view["item"]["revision"]} for view in after["views"])
    return {"member": member, "done": done_member, "integration": intent["id"], "acknowledgement": ack, "candidate": target}


def accepted_candidate(values, settings, intent):
    support = runpy.run_path(str(Path(__file__).with_name("consumer-evidence.py")))
    chain = support["reviewed_chain"](values["statuses"], values["artifacts"], {value["name"]: value for value in settings["checks"]}, intent["reviewer"])
    assert chain["workerResult"] == intent["worker"] and chain["candidate"] == intent["candidate"] and chain["members"] == intent["members"]
    assert chain["workerHarness"] == "Pi" and chain["reviewerHarness"] == "Codex"
    review = values["artifacts"][chain["reviewResult"]["value"]]["body"]
    assert review["report"]["Review"]["proposal"] is None and all(member["verdict"] == "Accepted" for member in review["report"]["Review"]["members"])
    assert all(values["artifacts"][value["artifact"]["value"]]["attempt"] == review["attempt"] for value in review["validation"]), "Reviewer did not execute fresh checks"
    return chain


def integrate(directory, values, settings, prior):
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    core["hierarchy"](values, settings, [({"Worker": {"mode": "Implement"}}, "Worker", "Pi"),
        ({"Reviewer": {"mode": "Candidate"}}, "Reviewer", "Codex"), ({"Planner": {}}, "Planner", "Codex")])
    before, after = prior["values"]["snapshot"], values["snapshot"]
    task = prior["manifest"]["proof"]["task"]
    workflow = values["governing_input"]["body"]
    assert workflow["workflow"]["request"] == {"Advance": {"roots": [task], "through": "Integrate"}}
    assert workflow["integrationTarget"] == settings["integrationTarget"] == "refs/heads/integration"
    receipt = json.loads((values["session"] / "receipt.json").read_text())
    assert receipt["processSucceeded"] and receipt["usageDelivered"] and receipt["report"] is not None
    integration, = json.loads((directory / "integrations.json").read_text())
    target = json.loads((directory / "target.json").read_text())
    proof = incorporated(before, after, integration, target, values["run"], settings["checks"], task)
    original_execution(json.loads((directory / "original-test-execution.json").read_text()), target)
    intent = integration["record"]["intent"]
    assert intent["repository"] == str(directory / "consumer")
    assert subprocess.check_output(["git", "-C", str(directory / "consumer"), "rev-parse", "refs/heads/integration"], text=True).strip() == target["value"]
    hashes = json.loads((directory / "source-sha256.json").read_text())
    check, = settings["checks"]
    assert check["name"] == "defect-oracle" and len(check["command"]) == 2
    assert hashlib.sha256(Path(check["command"][1]).read_bytes()).hexdigest() == hashes["dev/defect-oracle.py"]
    context = json.loads((directory / "bundle-publication.json").read_text())
    body = (directory / "empirical-input.json").read_text()
    bundle = json.loads(body)
    assert context["actor"]["role"] == "Human" and context["attempt"] == prior["values"]["run"]["attempt"]["id"]
    assert context["sha256"] == hashlib.sha256(body.encode()).hexdigest()
    artifact = values["artifacts"][context["id"]["value"]]
    assert artifact["body"] == bundle and artifact["sha256"] == context["sha256"] and artifact["kind"] == "Input" and artifact["attempt"] == context["attempt"]
    assert bundle["investigation"] == before["views"] and bundle["fixture"] == before["git"] and bundle["task"] == task
    assert hashlib.sha256(bundle["instructions"].encode()).hexdigest() == hashes["dev/defect-integrate.md"]
    canonical = lambda entries: sorted(json.dumps(value, sort_keys=True) for value in entries)
    completed = {status["result"]["value"]: values["artifacts"][status["result"]["value"]]["body"] for status in values["statuses"] if status["phase"] == "Completed"}
    for report in completed.values():
        request = report["request"]
        assert request["members"] == [proof["member"]], "Execution used another task or stale revisions"
        frozen, = [value["body"] for value in values["artifacts"].values() if value["kind"] == "Input" and value["attempt"] == report["attempt"]]
        assert frozen["base"] == report["base"] and frozen["input"]["request"] == request
        assert context["id"] in request["artifacts"] and {"metadata": context, "body": body} in frozen["input"]["artifacts"]
        assert canonical(frozen["input"]["members"]) == canonical(view for view in before["views"] if view["item"]["id"] == task)
        assert canonical(frozen["input"]["guidance"]) == canonical(view for view in before["views"] if view["item"]["id"] != task)
        if request["work"] == {"Planner": {}}:
            assert report["report"]["Plan"]["proposal"] is None and report["report"]["Plan"]["assessments"], "Planner produced an unauthorized ledger proposal"
        elif request["work"] == {"Worker": {"mode": "Implement"}}:
            if request["previous"] is None:
                assert report["base"] == before["git"]
            else:
                previous = completed[request["previous"]["value"]]
                assert previous["request"]["work"] == {"Reviewer": {"mode": "Candidate"}} and report["base"] == previous["candidate"]
                assert any(member["verdict"] == "ChangesRequested" for member in previous["report"]["Review"]["members"]), "Correction did not consume a rejected review"
        else:
            previous = completed[request["previous"]["value"]]
            assert previous["request"]["work"] == {"Worker": {"mode": "Implement"}} and report["base"] == report["candidate"] == previous["candidate"]
    return {**proof, "chain": accepted_candidate(values, settings, intent), "scope": candidate_scope(directory / "consumer", before["git"], target),
            "input": context["id"]}
