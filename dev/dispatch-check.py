import contextlib
import json
from fixture_runtime import guardian_binary
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    endpoint = os.environ["CQ_ORIGIN"]
    fixture = Path("dev/dispatch-fixture.py").read_text()
    retained = os.environ.get("CQ_DISPATCH_EVIDENCE")
    if retained is not None:
        Path(retained).mkdir(parents=True)
    with (contextlib.nullcontext(retained) if retained is not None else tempfile.TemporaryDirectory(prefix="cq-dispatch-")) as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Consumer base"], check=True)
        guardian = guardian_binary(root, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + fixture)
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "integrationTarget": None, "stateRoot": str(root / "sessions"), "guardian": str(guardian),
            "evaluation": {"run": "deterministic-dispatch", "scenario": "worker-reviewer", "assessor": False},
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "90000", "heartbeatMillis": "1000",
                       "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144},
            "checks": [{"name": "consumer-content", "command": [sys.executable, "-c", "from pathlib import Path; assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'; Path('check-private').write_text('isolated check')"],
                        "executionMillis": "5000", "retainedOutputBytes": 65536, "attempts": 1}],
        }))
        source = root / "request.txt"
        source.write_text("Run the worker/reviewer dispatch fixture")

        def run(arguments):
            result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=110)
            if result.returncode:
                for path in (root / "sessions").rglob("receipt.json"):
                    print(path, path.read_text(), file=sys.stderr)
                for path in (root / "sessions").rglob("stderr"):
                    print(path, path.read_text(errors="replace")[-12000:], file=sys.stderr)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout

        run(["init", "--endpoint", endpoint])
        receipt = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        session = Path(receipt["directory"])
        children = sorted((session / "children").iterdir())
        assert len(children) == 3 and receipt["processSucceeded"] and receipt["usageDelivered"]
        statuses = [json.loads((child / "receipt.json").read_text()) for child in children]
        assert sorted(value["phase"] for value in statuses) == ["Cancelled", "Completed", "Completed"]
        native = (session / "payload" / receipt["attempt"]["value"] / "stdout").read_text()
        assert "CHILD_ONLY_NARRATIVE" not in native
        events = [json.loads(line) for line in native.splitlines()]
        traffic = [value for value in events if value.get("type") == "fixture.dispatch"]
        assert traffic and max(len(json.dumps(value["reply"]).encode()) for value in traffic) < 12288
        assert not (repository / "consumer.txt").exists(), "Worker mutated governing checkout"
        manifest = json.loads((session / "run.json").read_text())

        def api(selection):
            request = urllib.request.Request(endpoint + "/api/call", data=json.dumps({"Usage": {"input": {
                "project": manifest["project"]["project"], "selection": selection}}}).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                    "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        filter_value = {"SessionOnly": {"id": receipt["session"]}}
        before = api({"Summary": {"filter": filter_value}})
        attempts = api({"Attempts": {"filter": filter_value, "after": None, "snapshot": None, "limit": 20}})["UsageAttempts"]["page"]["entries"]
        assert len(attempts) == 4 and sum(value["attempt"]["parent"] == receipt["attempt"] for value in attempts) == 3

        def phase(work):
            kind, body = next(iter(work.items()))
            return {"Explorer": "Explore", "Planner": "Plan", "Reviewer": "Review"}.get(kind) or {"Implement": "Work", "Probe": "Probe", "ResolveConflict": "Combine"}[body["mode"]]

        tickets = [json.loads((child / "ticket.json").read_text()) for child in children]
        expected = {receipt["attempt"]["value"]: "Govern", **{value["attempt"]["id"]["value"]: phase(value["request"]["work"]) for value in tickets}}
        assert {value["attempt"]["id"]["value"]: value["attempt"]["phase"] for value in attempts} == expected, attempts
        phases = api({"Phases": {"filter": filter_value}})["UsagePhases"]["report"]["phases"]
        assert {value["phase"]: value["attempts"] for value in phases} == {name: str(list(expected.values()).count(name)) for name in expected.values()}, phases
        assert all(value["running"] == "0" and value["spans"] == "0" and int(value["wallMillis"]) > 0 for value in phases), phases
        assert all(value["assignment"]["evaluation"] == {"run": "deterministic-dispatch", "scenario": "worker-reviewer", "assessor": False} for value in attempts)
        assert api({"Summary": {"filter": {"EvaluationOnly": {"run": "deterministic-dispatch", "scenario": "worker-reviewer"}}}}) == before
        assert before["UsageSummary"]["report"]["attempts"]["running"] == "0"
        assert "Acknowledged 0" in run(["job", "upload", "--session", str(session)])
        (children[0] / "delivery/final/000000.ack").unlink()
        assert "Acknowledged 1" in run(["job", "upload", "--session", str(session)])
        assert api({"Summary": {"filter": filter_value}}) == before, "Child replay changed accounting or cursor"
        print(json.dumps({"session": receipt["session"], "children": statuses, "usage": before, "maxParentReplyBytes": max(len(json.dumps(value["reply"]).encode()) for value in traffic)}))
        source.write_text("proposal-workflow")
        proposed = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        proposal_session = Path(proposed["directory"])
        proposal_native = (proposal_session / "payload" / proposed["attempt"]["value"] / "stdout").read_text()
        assert "CHILD_ONLY_NARRATIVE" not in proposal_native
        proposal_events = [json.loads(line) for line in proposal_native.splitlines()]
        proposal = next(event for event in proposal_events if event.get("type") == "fixture.proposal")
        assert len(proposal["statuses"]) == 6 and proposed["processSucceeded"] and proposed["usageDelivered"]
        assert proposal["applyBytes"] < 160
        for child in (proposal_session / "children").iterdir():
            child_result = json.loads((child / "publication.json").read_text())["result"]
            assert child_result["candidate"] is None and child_result["validation"] == []
        handle = proposal["handle"]["value"]
        human_preview = run(["proposal", "preview", handle])
        assert human_preview.startswith("Proposal ") and "Members:" in human_preview and "Produce from " in human_preview
        preview = json.loads(run(["proposal", "preview", handle, "--json"]))["Proposal"]["preview"]
        assert preview == proposal["preview"] and len(json.dumps(preview).encode()) < 2048
        forbidden = subprocess.run(command + ["proposal", "apply", handle], cwd=repository, env=environment,
                                   capture_output=True, text=True, timeout=20)
        assert forbidden.returncode != 0 and "Denied" in forbidden.stderr

        for scenario in ["cohort-assessment", "cohort-assessment-unknown", "cohort-assessment-unknown-check"]:
            source.write_text(scenario)
            assessed = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
            assessment_session = Path(assessed["directory"])
            native = (assessment_session / "payload" / assessed["attempt"]["value"] / "stdout").read_text()
            assert "PRIVATE_ASSESSMENT" not in native
            events = [json.loads(line) for line in native.splitlines()]
            result = next(value for value in events if value.get("type") == "fixture.assessment")
            assert len(result["statuses"]) == (1 if scenario.endswith("unknown-check") else 2)
            assert all(value["usageDelivered"] for value in result["statuses"])
            assert not (repository / "consumer.txt").exists()
            print(json.dumps({"scenario": scenario, "session": assessed["session"], "assessment": result}))

        def request(path, body, token):
            packet = urllib.request.Request(endpoint + path, data=json.dumps(body).encode(), headers={
                "Authorization": "Bearer " + token, "CQ-Session": str(uuid.uuid4()),
                "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
            with urllib.request.urlopen(packet, timeout=10) as response:
                return json.load(response)

        actor = {"subject": "CQ governor", "session": proposed["session"], "role": "Governor"}
        project = manifest["project"]["project"]
        def grant(value):
            return request("/api/grant", {"project": project, "actor": value, "expiresAt": str(int(time.time() * 1000) + 300000)}, environment["CQ_TOKEN"])["value"]
        owning_token = grant(actor)
        replay = subprocess.run(command + ["proposal", "apply", handle, "--json"], cwd=repository,
                                env={**environment, "CQ_TOKEN": owning_token}, capture_output=True, text=True, timeout=20)
        assert replay.returncode == 0 and json.loads(replay.stdout)["Changed"]["ack"] == proposal["ack"]
        application = {"ApplyProposal": {"input": {"project": project, "result": proposal["handle"]}}}
        for role in ["Explorer", "Planner", "Worker", "Reviewer", "Collector"]:
            denied = request("/api/call", application, grant({**actor, "role": role}))
            assert "Denied" in denied["Failed"]["fault"]
        proposal_usage = api({"Attempts": {"filter": {"SessionOnly": {"id": proposed["session"]}}, "after": None, "snapshot": None, "limit": 20}})["UsageAttempts"]["page"]["entries"]
        assert len(proposal_usage) == 7 and sum(value["attempt"]["parent"] == proposed["attempt"] for value in proposal_usage) == 6
        assert sorted(value["attempt"]["role"] for value in proposal_usage) == ["Explorer", "Explorer", "Governor", "Planner", "Reviewer", "Reviewer", "Worker"]
        assert sorted(value["attempt"]["phase"] for value in proposal_usage) == ["Explore", "Explore", "Govern", "Plan", "Probe", "Review", "Review"]
        assert not (repository / "probe.txt").exists()
        print(json.dumps({"proposalSession": str(proposal_session), "proposal": proposal, "cliReplay": True, "directRolesDenied": 5}))
        ordinary_settings = settings.read_text()
        for mode in ["failed", "cancel", "hold"]:
            marker = root / f"reviewer-check-{mode}-started"
            counter = root / f"reviewer-check-{mode}-count"
            script = (f"from pathlib import Path; import os,sys,time; counter=Path({str(counter)!r}); "
                      "n=int(counter.read_text())+1 if counter.exists() else 1; counter.write_text(str(n)); "
                      "assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n';\n"
                      f"if n == 2:\n Path({str(marker)!r}).write_text(str(os.getpid()))\n print('live-reviewer-check', flush=True)\n " +
                      ("sys.exit(1)\n" if mode == "failed" else "time.sleep(60)\n"))
            configured = json.loads(ordinary_settings)
            configured["checks"][0].update(command=[sys.executable, "-c", script], executionMillis="70000")
            settings.write_text(json.dumps(configured))
            source.write_text(f"reviewer-check-{mode}:{marker}")
            if mode != "hold":
                cancelled_receipt = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
                interrupted_session = Path(cancelled_receipt["directory"])
                review_directory = next(path for path in (interrupted_session / "children").iterdir()
                                        if json.loads((path / "ticket.json").read_text())["attempt"]["role"] == "Reviewer")
                review_status = json.loads((review_directory / "receipt.json").read_text())
                assert review_status["phase"] == ("Cancelled" if mode == "cancel" else "Completed")
                jobs = [json.loads(path.read_text()) for path in (interrupted_session / "journal").glob("*.json")]
                native_id = json.loads((review_directory / "ticket.json").read_text())["attempt"]["id"]
                check_id = json.loads((review_directory / "checks/consumer-content/ticket.json").read_text())["workspace"]["attempt"]
                owned = [job for job in jobs if job["workspace"]["attempt"] in [native_id, check_id]]
                assert len(owned) == 2 and all(job["phase"] == "Settled" for job in owned), owned
                if mode == "cancel":
                    assert all(job["target"] == "Stop" for job in owned), owned
                else:
                    assert review_status["counts"]["accepted"] == 1 and review_status["counts"]["validationFailed"] == 1 and review_status["next"] == "Revise"
            else:
                with (root / "reviewer-check-kill.stdout").open("w") as out, (root / "reviewer-check-kill.stderr").open("w") as err:
                    process = subprocess.Popen(command + ["run", "codex", "--settings", str(settings), "--input", str(source)],
                                               cwd=repository, env=environment, stdout=out, stderr=err)
                    try:
                        deadline = time.monotonic() + 40
                        while not marker.exists():
                            assert process.poll() is None, (root / "reviewer-check-kill.stderr").read_text()[-4000:]
                            assert time.monotonic() < deadline, "Live reviewer check marker missing"
                            time.sleep(0.05)
                        check_ticket = next(path for path in (root / "sessions").glob("*/children/*/checks/*/ticket.json")
                                            if json.loads(path.read_text())["check"]["command"][-1] == script)
                        review_directory = check_ticket.parents[2]
                        interrupted_session = review_directory.parents[1]
                        process.kill()
                        assert process.wait(timeout=10) == -9
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.wait(timeout=10)
                before_jobs = sorted(path.name for path in (interrupted_session / "journal").glob("*.json"))
                run(["job", "upload", "--session", str(interrupted_session)])
                assert "Acknowledged 0" in run(["job", "upload", "--session", str(interrupted_session)])
                state = json.loads((review_directory / "checks/consumer-content/result.json").read_text())
                assert state["phase"] == "Unknown" and state["evidence"]["state"] == "Unknown", state
                assert not (review_directory / "receipt.json").exists()
                assert sorted(path.name for path in (interrupted_session / "journal").glob("*.json")) == before_jobs
            assert counter.read_text() == "2", "Recovery or duplicate polling executed another check"
            check_pid = int(marker.read_text())
            deadline = time.monotonic() + 5
            while Path(f"/proc/{check_pid}/stat").exists():
                if Path(f"/proc/{check_pid}/stat").read_text().split(") ", 1)[1].startswith("Z "):
                    break
                assert time.monotonic() < deadline, "Reviewer check process survived hierarchy termination"
                time.sleep(0.05)
            print(json.dumps({"reviewerCheckInterruption": mode, "session": str(interrupted_session), "executions": 2}))
        settings.write_text(ordinary_settings)

        def counting(counter, failing):
            return (f"from pathlib import Path; import sys; counter=Path({str(counter)!r}); "
                    "n=int(counter.read_text())+1 if counter.exists() else 1; counter.write_text(str(n)); "
                    "assert Path('consumer.txt').read_text() == 'candidate from isolated worker\\n'; "
                    f"sys.exit(1 if n <= {failing} else 0)")

        def child(session_directory, role):
            return next(path for path in (session_directory / "children").iterdir()
                        if json.loads((path / "ticket.json").read_text())["attempt"]["role"] == role)

        # I19: a configured check that fails once and passes on its automatic rerun does not block the review.
        counter = root / "intermittent-check-count"
        configured = json.loads(ordinary_settings)
        configured["checks"][0].update(command=[sys.executable, "-c", counting(counter, 1)], attempts=2)
        settings.write_text(json.dumps(configured))
        source.write_text("intermittent-check")
        intermittent = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        intermittent_session = Path(intermittent["directory"])
        worked = json.loads((child(intermittent_session, "Worker") / "publication.json").read_text())
        evidence, = worked["result"]["validation"]
        assert evidence["state"] == "Passed" and len(evidence["failures"]) == 1 and evidence["artifact"] not in evidence["failures"], evidence
        receipt = json.loads((child(intermittent_session, "Worker") / "receipt.json").read_text())
        assert receipt["counts"]["validationIntermittent"] == 1 and receipt["counts"]["validationFailed"] == 0, receipt
        assert receipt["next"] == "Review" and receipt["blocker"] is None, receipt
        reviewed = json.loads((child(intermittent_session, "Reviewer") / "receipt.json").read_text())
        assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == 1 and reviewed["counts"]["validationFailed"] == 0, reviewed
        # Two worker runs (failed, then passed) and the reviewer's own run.
        assert counter.read_text() == "3", counter.read_text()
        print(json.dumps({"intermittentCheck": evidence, "session": intermittent["session"], "executions": 3}))

        # I19: a check the reviewer requests fails once and passes on its rerun, under a job and ticket of its own.
        counter = root / "intermittent-reviewer-check-count"
        script = counting(counter, 0).replace("n <= 0", "n == 2")
        configured["checks"][0].update(command=[sys.executable, "-c", script], attempts=2)
        settings.write_text(json.dumps(configured))
        source.write_text("intermittent-reviewer-check")
        rechecked = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        review_directory = child(Path(rechecked["directory"]), "Reviewer")
        runs = [review_directory / "checks/consumer-content", review_directory / "checks/consumer-content/rerun-2"]
        tickets = [json.loads((path / "ticket.json").read_text()) for path in runs]
        states = [json.loads((path / "result.json").read_text()) for path in runs]
        assert [value["evidence"]["state"] for value in states] == ["Failed", "Passed"], states
        assert tickets[0]["failures"] == [] and tickets[1]["failures"] == [states[0]["evidence"]["artifact"]] == states[1]["evidence"]["failures"], tickets
        assert tickets[0]["workspace"]["attempt"] != tickets[1]["workspace"]["attempt"] and states[1]["job"] == tickets[1]["workspace"]["attempt"]
        reviewed = json.loads((review_directory / "receipt.json").read_text())
        assert reviewed["phase"] == "Completed" and reviewed["counts"]["accepted"] == 1 and reviewed["counts"]["validationFailed"] == 0, reviewed
        assert reviewed["counts"]["validationIntermittent"] == 1 and reviewed["next"] == "ConsiderAcceptance", reviewed
        # The worker's run and the reviewer's failed and passing runs.
        assert counter.read_text() == "3", counter.read_text()
        print(json.dumps({"intermittentReviewerCheck": states[1]["evidence"], "session": rechecked["session"], "executions": 3}))
        settings.write_text(ordinary_settings)
        subprocess.run(["git", "-C", str(repository), "branch", "integration"], check=True)
        (repository / "governing.txt").write_text("staged governing work\n")
        subprocess.run(["git", "-C", str(repository), "add", "governing.txt"], check=True)
        (repository / "untracked.txt").write_text("untracked governing work\n")
        original_index = (repository / ".git/index").read_bytes()
        original_head = subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()
        configured = json.loads(settings.read_text())
        configured["integrationTarget"] = "refs/heads/integration"
        settings.write_text(json.dumps(configured))
        source.write_text("integrate-reviewed-candidate")
        integrated = json.loads(run(["run", "codex", "--settings", str(settings), "--input", str(source)]))
        integrated_session = Path(integrated["directory"])
        native = (integrated_session / "payload" / integrated["attempt"]["value"] / "stdout").read_text()
        assert "CHILD_ONLY_NARRATIVE" not in native
        events = [json.loads(line) for line in native.splitlines()]
        result = next(value for value in events if value.get("type") == "fixture.integration")
        preview = result["recorded"]["preview"]
        assert subprocess.check_output(["git", "-C", str(repository), "rev-parse", "refs/heads/integration"], text=True).strip() == preview["candidate"]["value"]
        assert subprocess.check_output(["git", "-C", str(repository), "show", "refs/heads/integration:consumer.txt"], text=True) == "candidate from isolated worker\n"
        assert subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip() == original_head
        assert (repository / ".git/index").read_bytes() == original_index
        assert (repository / "governing.txt").read_text() == "staged governing work\n"
        assert (repository / "untracked.txt").read_text() == "untracked governing work\n"
        assert not (repository / "consumer.txt").exists()
        before_jobs = {path.name: path.read_bytes() for path in (integrated_session / "journal").glob("*.json")}
        for _ in range(2):
            recovered = run(["job", "upload", "--session", str(integrated_session)])
            assert f'Integration {preview["id"]["value"]}: Recorded' in recovered
        assert before_jobs == {path.name: path.read_bytes() for path in (integrated_session / "journal").glob("*.json")}
        integration_traffic = [value for value in events if value.get("type") == "fixture.dispatch" and "Integration" in value["reply"]]
        assert integration_traffic and max(len(json.dumps(value["reply"]).encode()) for value in integration_traffic) < 4096
        print(json.dumps({"integration": result, "session": integrated["session"], "maxIntegrationReplyBytes": max(len(json.dumps(value["reply"]).encode()) for value in integration_traffic)}))
        subprocess.run(["git", "-C", str(repository), "update-ref", "refs/heads/integration", original_head, preview["candidate"]["value"]], check=True)
        subprocess.run(["git", "-C", str(repository), "switch", "integration"], check=True)
        (repository / "governing.txt").write_text("unstaged over staged governing work\n")
        preload = root / "integration-stall.so"
        subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)
        latch = root / "integration-crash"
        latch.mkdir()
        injected = {**environment, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_ROOT": str(latch), "CQ_FIXTURE_STALL_MODE": "integration-observation"}
        with (latch / "stdout").open("w") as out, (latch / "stderr").open("w") as err:
            process = subprocess.Popen(command + ["run", "codex", "--settings", str(settings), "--input", str(source)],
                                       cwd=repository, env=injected, stdout=out, stderr=err)
            try:
                deadline = time.monotonic() + 70
                while not (latch / "entered").exists():
                    assert process.poll() is None, (latch / "stderr").read_text()[-5000:]
                    assert time.monotonic() < deadline, "Integration observation latch was not reached"
                    time.sleep(0.05)
                stalled = Path((latch / "entered").read_text())
                crash_session = stalled.parent.parent
                local_path = next((crash_session / "integrations").glob("*.json"))
                local = json.loads(local_path.read_text())
                assert local["attempted"] and local["observation"] is None, local
                intent = local["intent"]
                assert subprocess.check_output(["git", "-C", str(repository), "rev-parse", intent["target"]], text=True).strip() == intent["candidate"]["value"]
                live_upload = subprocess.run(command + ["job", "upload", "--session", str(crash_session)], cwd=repository,
                                             env=environment, capture_output=True, text=True, timeout=30)
                assert live_upload.returncode != 0 and "already owned" in live_upload.stderr, live_upload.stderr
                process.kill()
                assert process.wait(timeout=10) == -9
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
                (latch / "release").touch()
        assert not (crash_session / "receipt.json").exists()
        integration_job = crash_session / "journal" / (intent["id"]["value"] + ".json")
        before_job = integration_job.read_bytes()
        before_ids = sorted(path.name for path in (crash_session / "journal").glob("*.json"))
        reflog = repository / ".git/logs/refs/heads/integration"
        before_reflog = reflog.read_bytes()

        def read(selection):
            packet = {"Read": {"input": {"project": intent["project"], "selection": selection}}}
            request = urllib.request.Request(endpoint + "/api/call", data=json.dumps(packet).encode(), headers={
                "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()),
                "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)

        assert read({"Integration": {"id": intent["id"]}})["Integration"]["record"]["resolution"] == {"Pending": {}}
        incomplete_id = str(uuid.uuid4())
        incomplete = crash_session / "combinations" / incomplete_id
        incomplete.parent.mkdir(mode=0o700)
        incomplete.mkdir(mode=0o700)
        ticket = incomplete / "ticket.json"
        ticket.write_text(json.dumps({"id": {"value": incomplete_id}, "source": intent["id"], "fence": intent["fence"]}))
        ticket.chmod(0o600)

        def recover_with_incomplete_plan():
            result = subprocess.run(command + ["job", "upload", "--session", str(crash_session)], cwd=repository,
                                    env=environment, capture_output=True, text=True, timeout=40)
            (latch / "recovery.stdout").write_text(result.stdout)
            (latch / "recovery.stderr").write_text(result.stderr)
            assert result.returncode != 0 and "Unresolved" in result.stderr, result.stdout + result.stderr
            resolution = read({"Integration": {"id": intent["id"]}})["Integration"]["record"]["resolution"]
            assert "Recorded" in resolution, f"Incomplete combination blocked independent incorporation recovery: {resolution}"
            assert ticket.exists() and not (incomplete / "plan.json").exists(), "Recovery prepared an unfrozen combination"
            return result.stdout

        recovery = recover_with_incomplete_plan()
        assert f'Integration {intent["id"]["value"]}: Recorded' in recovery
        recorded = read({"Integration": {"id": intent["id"]}})["Integration"]["record"]
        acknowledgement = recorded["resolution"]["Recorded"]["acknowledgement"]
        assert acknowledgement["request"] == intent["id"]
        assert acknowledgement["items"] == [{**member, "revision": {"value": str(int(member["revision"]["value"]) + 1)}} for member in intent["members"]]
        replay = recover_with_incomplete_plan()
        assert "Acknowledged 0" in replay and "Recorded" in replay
        assert read({"Integration": {"id": intent["id"]}})["Integration"]["record"] == recorded
        assert integration_job.read_bytes() == before_job
        assert sorted(path.name for path in (crash_session / "journal").glob("*.json")) == before_ids
        assert reflog.read_bytes() == before_reflog
        assert subprocess.check_output(["git", "-C", str(repository), "symbolic-ref", "HEAD"], text=True).strip() == "refs/heads/integration"
        assert subprocess.check_output(["git", "-C", str(repository), "show", ":governing.txt"], text=True) == "staged governing work\n"
        assert (repository / "governing.txt").read_text() == "unstaged over staged governing work\n"
        assert (repository / "untracked.txt").read_text() == "untracked governing work\n"
        assert (repository / "consumer.txt").read_text() == "candidate from isolated worker\n"
        print(json.dumps({"crashSession": str(crash_session), "killedAt": "Git incorporated, local observation uncommitted",
                          "resolution": recorded["resolution"], "newGitJobsOnRecovery": 0, "additionalRefUpdates": 0}))
    print("Local dispatch: idempotent start, host candidate/checks, handle-only review, workspace permissions, cancellation, parent/child audit, child delivery replay, reviewed integration, preserved checkout/index and reconcile-only CLI replay and actual SIGKILL incorporation recovery passed")


if __name__ == "__main__":
    main()
