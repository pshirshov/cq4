"""Restore an incorporated failed producer and execute only its remaining Handoff closeout."""
import datetime
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import sys
import time
import urllib.parse
import uuid


def main(arguments):
    root = Path(__file__).resolve().parent.parent
    required = ["go", "initdb"] + (["java", "sbt"] if arguments.release is None else [])
    if any(shutil.which(name) is None for name in required):
        os.execvp("nix", ["nix", "develop", str(root), "-c", str(root / "dev/process-eval"), *sys.argv[1:]])
    os.chdir(root)
    failed = arguments.checkpoint.resolve(strict=True)
    arguments.release = None if arguments.release is None else arguments.release.resolve(strict=True)
    evaluation = runpy.run_path(str(root / "dev/consumer-eval"))
    support = runpy.run_path(str(root / "dev/check"))
    predicates = runpy.run_path(str(root / "dev/process-closeout-evidence.py"))
    process = runpy.run_path(str(root / "dev/process-evidence.py"))
    write, call = evaluation["write"], evaluation["call"]
    read = lambda path: json.loads(path.read_text())
    prior = predicates["producer"](failed, read)
    origin = Path(prior["manifest"]["baselineEvidence"])
    files = runpy.run_path(str(root / "dev/release-evaluate"))["evidence_files"]
    frozen = files(failed)
    for name in ["dev/consumer-spec.md", "dev/consumer-oracle.py"]:
        assert hashlib.sha256((root / name).read_bytes()).hexdigest() == read(failed / "source-sha256.json")[name], "Consumer contract changed"
    identifier = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-process-closeout"
    evidence = Path(os.environ.get("CQ_EVIDENCE_ROOT", "/srv/nvme/tmp/cq4-implementation")).resolve() / identifier
    evidence.mkdir(parents=True, mode=0o700)
    print("Evidence:", evidence, flush=True)
    write(evidence / "producer-snapshot.json", frozen)
    sources = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"]).decode().split("\0")
    write(evidence / "source-sha256.json", {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sorted(set(sources)) if name and (root / name).is_file()})
    answer_bytes = (failed / "supplied-answer.json").read_bytes()
    (evidence / "supplied-answer.json").write_bytes(answer_bytes)
    result = {"status": "running", "stage": "closeout", "implementation": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
              "baselineEvidence": str(origin), "restoredEvidence": str(failed), "baselineDumpSha256": hashlib.sha256((failed / "cq-database.dump").read_bytes()).hexdigest(),
              "candidate": prior["candidate"], "remaining": ["independent process assessment", "human acceptance"]}
    write(evidence / "result.json", result)
    checks = support["Checks"](evidence, dict(os.environ))
    try:
        assert (arguments.release is None) == (prior["manifest"].get("release") is None)
        command, guardian, result["release"] = evaluation["runtime"](checks, arguments.release, prior["manifest"].get("release"))
        settings = json.loads(json.dumps(prior["settings"]))
        settings.update(integrationTarget=None, stateRoot=str(evidence / "sessions"), guardian=str(guardian))
        for route in settings["harnesses"]:
            assert route["version"] in checks.run([route["executable"], "--version"], "version-" + route["harness"].lower(), 15)
        write(evidence / "settings.json", settings)
        consumer = evidence / "consumer"
        checks.run(["git", "clone", "--quiet", "--no-hardlinks", str(failed / "consumer"), str(consumer)], "consumer-clone", 30)
        checks.run(["git", "-C", str(consumer), "fetch", "--quiet", "origin", prior["candidate"]["value"]], "candidate-fetch", 30)
        checks.run(["git", "-C", str(consumer), "checkout", "--quiet", "--detach", prior["candidate"]["value"]], "candidate-checkout", 30)
        checks.run(["git", "-C", str(consumer), "branch", "--force", "integration", prior["candidate"]["value"]], "integration-ref", 10)
        assert (consumer / ".cq-evaluation/answer.json").read_bytes() == answer_bytes
        project = prior["run"]["project"]["project"]
        port = support["free_port"]()
        checks.environment.update(CQ_HOST="127.0.0.1", CQ_PORT=str(port), CQ_ORIGIN=f"http://127.0.0.1:{port}", CQ_TOKEN=uuid.uuid4().hex + uuid.uuid4().hex, CQ_SESSION=str(uuid.uuid4()))
        with evaluation["database"](checks, evidence, support["free_port"]()):
            parsed = urllib.parse.urlsplit(checks.environment["CQ_DATABASE_URL"].removeprefix("jdbc:"))
            checks.run(["pg_restore", "--exit-on-error", "--clean", "--if-exists", "--host", parsed.hostname, "--port", str(parsed.port), "--username", checks.environment["CQ_DATABASE_USER"], "--dbname", parsed.path[1:], str(failed / "cq-database.dump")], "database-restore", 60)
            with checks.server(command, "process-closeout-server"):
                environment = {name: value for name, value in checks.environment.items() if not name.startswith("CQ_")}
                environment["CQ_TOKEN"] = checks.environment["CQ_TOKEN"]
                with (evidence / "cq-init.log").open("w") as log:
                    subprocess.run(command + ["init", "--endpoint", checks.environment["CQ_ORIGIN"], "--project-id", project["value"], "--name", prior["run"]["project"]["name"]], cwd=consumer, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=30, check=True)

                def snapshot():
                    views, histories = [], []
                    found = call(checks.environment, {"Search": {"input": {"project": project, "query": "archived:true OR archived:false", "after": None, "snapshot": None, "limit": 100}}})["Found"]["page"]
                    assert not found["hasMore"]
                    for item in found["items"]:
                        def read_item(selection):
                            return call(checks.environment, {"Read": {"input": {"project": project, "selection": selection}}})
                        views.append(read_item({"ItemDetail": {"id": item["id"]}})["Detail"]["view"])
                        histories.append({"id": item["id"], "page": read_item({"History": {"id": item["id"], "before": {"value": str(int(item["revision"]["value"]) + 1)}, "limit": 100}})["History"]["page"]})
                    claims = call(checks.environment, {"Read": {"input": {"project": project, "selection": {"Claims": {"members": [value["item"]["id"] for value in views]}}}}})["Claims"]["preview"]
                    git = lambda ref: {"value": subprocess.check_output(["git", "-C", str(consumer), "rev-parse", ref], text=True).strip()}
                    return {"views": views, "histories": histories, "claims": claims, "git": git("HEAD"), "target": git("refs/heads/integration"),
                            "clean": not subprocess.check_output(["git", "-C", str(consumer), "status", "--porcelain", "--untracked-files=all"], text=True).strip()}

                try:
                    before = snapshot()
                    write(evidence / "before.json", before)
                    assert before["views"] == prior["views"] and before["histories"] == prior["histories"] and before["git"] == before["target"] == prior["candidate"] and before["clean"]
                    assert not before["claims"]["claims"] and not before["claims"]["integrations"], "Previous authority has not expired normally"
                    record = call(checks.environment, {"Read": {"input": {"project": project, "selection": {"Integration": {"id": prior["local"]["intent"]["id"]}}}}})["Integration"]["record"]
                    integrations = [{"local": prior["local"], "record": record}]
                    write(evidence / "producer-integrations.json", integrations)
                    checkpoint = read(failed / "checkpoint-replay.json")["checkpoint"]
                    citations = read(failed / "answer-change.json")["command"]["Change"]["input"]["change"]["mutations"][0]["Replace"]["draft"]["citations"]
                    citation, = [value for value in citations if value.get("File", {}).get("path") == ".cq-evaluation/answer.json"]
                    proof = process["integrated_resume"](checkpoint, json.loads(answer_bytes), citation, before["views"], before["histories"], prior["statuses"], prior["artifacts"], integrations, prior["run"], prior["candidate"], before["claims"], {value["name"]: value for value in settings["checks"]})
                    write(evidence / "producer-proof.json", proof)
                    selection = {"Summary": {"filter": {"EvaluationOnly": {"run": settings["evaluation"]["run"], "scenario": settings["evaluation"]["scenario"]}}}}
                    def usage():
                        return call(checks.environment, {"Usage": {"input": {"project": project, "selection": selection}}})
                    baseline_usage = usage()
                    write(evidence / "baseline-usage-summary.json", baseline_usage)
                    parts = [read(path / "usage-summary.json")["UsageSummary"]["report"] for path in [origin, failed]]
                    process["reconcile_usage"](parts, baseline_usage["UsageSummary"]["report"])
                    handoff, = [view for view in before["views"] if view["item"]["id"]["ledger"] == "Handoffs"]
                    request = ("Finish only the Handoff for this already incorporated consumer. The previous governor reached its deadline AFTER Recorded integration. "
                        "Re-read current Handoff and task records, and read the indicated durable Integration. Acquire a NEW claim for only the Handoff, at most 300000 ms, using ordinary claim rules. "
                        "Replace only this Handoff once, with that fresh fence: describe the actual combined implementation, accepted Candidate Review and passed oracle, cite their handles and exact incorporated commit, "
                        "and state that independent whole-process assessment and human acceptance remain pending. Preserve its relationships and all other records and history. "
                        "Release the new Handoff claim and return a compact report. Do not dispatch ANY children, apply historical proposals, edit tasks, implement, run checks or attempt integration. "
                        "Do not alter Git or the actual answer. Old failed session usage remains incomplete; do not claim complete accounting.\n" + json.dumps({"handoff": {"id": handoff["item"]["id"], "revision": handoff["item"]["revision"]}, "tasks": proof["tasks"], "integration": record["intent"]["id"], "candidate": prior["candidate"], "results": proof["finalChain"]}))
                    (evidence / "request.txt").write_text(request)
                    began = time.monotonic()
                    with (evidence / "governor.stdout").open("w") as out, (evidence / "governor.stderr").open("w") as err:
                        finished = subprocess.run(command + ["run", "claude", "--settings", str(evidence / "settings.json"), "--input", str(evidence / "request.txt")], cwd=consumer, env=environment, stdout=out, stderr=err, timeout=evaluation["EXECUTION_MILLIS"] / 1000 + 120)
                    result.update(exit=finished.returncode, elapsedSeconds=round(time.monotonic() - began, 3))
                    session, = (evidence / "sessions").iterdir()
                    run = read(session / "run.json")
                    statuses = [read(path) for path in (session / "children").glob("*/receipt.json")]
                    write(evidence / "dispatch-statuses.json", statuses)
                    input_id = {"value": str(uuid.UUID(bytes=hashlib.md5((run["attempt"]["id"]["value"] + ":input").encode()).digest(), version=3))}
                    artifacts = evaluation["artifacts"](checks.environment, evidence, session, statuses, [input_id])
                    governing, = [value for value in artifacts.values() if value["kind"] == "Input" and value["attempt"] == run["attempt"]["id"]]
                    after = snapshot()
                    write(evidence / "after.json", after)
                    assert finished.returncode == 0, "Closeout governor failed"
                    events = [json.loads(line) for line in (session / "payload" / run["attempt"]["id"]["value"] / "stdout").read_text().splitlines() if line.strip()]
                    result["closeout"] = predicates["closeout_change"](before, after, run, governing, events, list((session / "children").glob("*/ticket.json")), list((session / "integrations").glob("*.json")))
                    result["progress"] = process["integrated_resume"](checkpoint, json.loads(answer_bytes), citation, after["views"], after["histories"], prior["statuses"], prior["artifacts"], integrations, prior["run"], prior["candidate"], after["claims"], {value["name"]: value for value in settings["checks"]})
                    write(evidence / "combined-usage-summary.json", usage())
                finally:
                    primary_failure = sys.exc_info()[0] is not None
                    result["archiveErrors"] = evaluation["archive"](checks, evidence)
                    if result["archiveErrors"] and not primary_failure:
                        raise RuntimeError("; ".join(result["archiveErrors"]))
        assert files(failed) == frozen, "Closeout changed original evidence"
        result.update(status="integrated-candidate-passed", accounting="reconciled")
        write(evidence / "result.json", result)
        predicates["retained"](evidence)
    except BaseException as error:
        result.update(status="failed", error=str(error))
        raise
    finally:
        write(evidence / "result.json", result)
    print("CLOSEOUT-PASSED: independent process and human acceptance remain pending; evidence", evidence, flush=True)
