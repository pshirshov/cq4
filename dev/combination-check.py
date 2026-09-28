import json
from fixture_runtime import guardian_binary
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid


WAIT_SECONDS = 90


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    root = Path(os.environ["CQ_COMBINATION_EVIDENCE"])
    root.mkdir(parents=True)
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    endpoint = os.environ["CQ_ORIGIN"]
    guardian = guardian_binary(root, os.environ.get("CQ_GUARDIAN_TEST_BINARY"))
    preload = root / "integration-stall.so"
    subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)
    executable = root / "fixture-harness"
    executable.write_text(f"#!{sys.executable}\nCONTROL_ROOT = {str(root)!r}\n" + Path("dev/combination-fixture.py").read_text())
    executable.chmod(0o700)
    summaries = []
    for scenario in ["clean", "conflict"]:
        case = root / scenario
        case.mkdir()
        repository = case / "repository"
        repository.mkdir()

        def git(*args):
            return subprocess.check_output(["git", "-C", str(repository), *args], text=True).strip()

        def run(arguments):
            result = subprocess.run(command + arguments, cwd=repository, env=environment, capture_output=True, text=True, timeout=40)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout

        git("init", "--quiet")
        (repository / "shared.txt").write_text("base\n")
        git("add", "shared.txt")
        git("-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "--quiet", "-m", "Initial")
        base = git("rev-parse", "HEAD")
        git("branch", "integration")
        (repository / "staged.txt").write_text("governing staged work\n")
        git("add", "staged.txt")
        (repository / "untracked.txt").write_text("governing untracked work\n")
        index = (repository / ".git/index").read_bytes()
        checks = "from pathlib import Path; names=[n for n in ['alpha','beta'] if Path(n+'.txt').exists()]; assert names; assert all(Path(n+'.txt').read_text()==n+'\\n' for n in names); assert '<<<<<<<' not in Path('shared.txt').read_text()"
        settings = case / "settings.json"
        settings.write_text(json.dumps({"integrationTarget": "refs/heads/integration", "stateRoot": str(case / "sessions"), "guardian": str(guardian),
            "evaluation": {"run": "deterministic-combination", "scenario": scenario, "assessor": False},
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "180000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "outputBytes": 262144},
            "checks": [{"name": "independent-changes", "command": [sys.executable, "-c", checks], "executionMillis": "5000", "outputBytes": 65536}]}))
        run(["init", "--endpoint", endpoint])
        processes, streams = {}, []
        latch = case / "crash"
        latch.mkdir()

        def events(actor):
            for session in (case / "sessions").glob("*"):
                manifest = session / "run.json"
                if not manifest.exists():
                    continue
                attempt = json.loads(manifest.read_text())["attempt"]["id"]["value"]
                stdout = session / "payload" / attempt / "stdout"
                if not stdout.exists():
                    continue
                text = stdout.read_text()
                values = [json.loads(line) for line in text[:text.rfind("\n") + 1].splitlines()]
                if any(value.get("type") == "fixture.prepared" and value["actor"] == actor for value in values):
                    return session, values
            return None, []

        def wait_event(actor, kind, predicate):
            deadline = time.monotonic() + WAIT_SECONDS
            while True:
                session, values = events(actor)
                matching = [value for value in values if value.get("type") == kind and predicate(value)]
                if matching:
                    return session, matching[-1]
                assert processes[actor].poll() is None, f"{actor} exited before {kind}; see {case}"
                assert time.monotonic() < deadline, f"{actor} did not reach {kind}; see {case}"
                time.sleep(0.05)

        try:
            for actor in ["alpha", "beta"]:
                source = case / (actor + ".txt")
                source.write_text(json.dumps({"actor": actor, "scenario": scenario}))
                stdout, stderr = (case / (actor + ".stdout")).open("w"), (case / (actor + ".stderr")).open("w")
                streams.extend([stdout, stderr])
                env = environment
                if actor == "beta" and scenario == "conflict":
                    env = {**environment, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_ROOT": str(latch), "CQ_FIXTURE_STALL_MODE": "integration-observation"}
                processes[actor] = subprocess.Popen(command + ["run", "codex", "--settings", str(settings), "--input", str(source)],
                    cwd=repository, env=env, stdout=stdout, stderr=stderr)
            alpha_session, alpha_ready = wait_event("alpha", "fixture.prepared", lambda _: True)
            beta_session, beta_ready = wait_event("beta", "fixture.prepared", lambda _: True)
            assert alpha_ready["integration"]["preview"]["expected"]["value"] == base
            assert beta_ready["integration"]["preview"]["expected"]["value"] == base
            (case / "alpha-initial").touch()
            _, alpha_done = wait_event("alpha", "fixture.recorded", lambda _: True)
            assert processes["alpha"].wait(timeout=20) == 0
            alpha_commit = alpha_done["integration"]["preview"]["candidate"]["value"]
            assert git("rev-parse", "refs/heads/integration") == alpha_commit
            (case / "beta-initial").touch()
            _, combined = wait_event("beta", "fixture.combined_ready", lambda value: value["round"] == 0)
            rounds = [combined]
            assert combined["integration"]["preview"]["expected"]["value"] == alpha_commit
            if scenario == "conflict":
                third = case / "third-worktree"
                git("worktree", "add", "--detach", str(third), alpha_commit)
                (third / "gamma.txt").write_text("gamma\n")
                subprocess.run(["git", "-C", str(third), "add", "gamma.txt"], check=True)
                subprocess.run(["git", "-C", str(third), "-c", "user.name=CQ fixture", "-c", "user.email=cq@localhost", "commit", "--quiet", "-m", "Further target advancement"], check=True)
                third_commit = subprocess.check_output(["git", "-C", str(third), "rev-parse", "HEAD"], text=True).strip()
                git("update-ref", "refs/heads/integration", third_commit, alpha_commit)
                (case / "beta-combined-0").touch()
                _, combined = wait_event("beta", "fixture.combined_ready", lambda value: value["round"] == 1)
                rounds.append(combined)
                assert combined["integration"]["preview"]["expected"]["value"] == third_commit
                (case / "beta-combined-1").touch()
                deadline = time.monotonic() + WAIT_SECONDS
                while not (latch / "entered").exists():
                    assert processes["beta"].poll() is None, (case / "beta.stderr").read_text()[-5000:]
                    assert time.monotonic() < deadline, "Final combined incorporation did not reach its crash latch"
                    time.sleep(0.05)
                processes["beta"].kill()
                assert processes["beta"].wait(timeout=10) == -9
                (latch / "release").touch()
            else:
                (case / "beta-combined-0").touch()
                wait_event("beta", "fixture.recorded", lambda _: True)
                assert processes["beta"].wait(timeout=20) == 0
            final = combined["integration"]["preview"]
            assert git("rev-parse", "refs/heads/integration") == final["candidate"]["value"]
            jobs = {path.name: path.read_bytes() for path in (beta_session / "journal").glob("*.json")
                if (beta_session / "integrations" / path.name).exists()}
            job_names = sorted(path.name for path in (beta_session / "journal").glob("*.json"))
            reflog = (repository / ".git/logs/refs/heads/integration").read_bytes()
            manifest = json.loads((beta_session / "run.json").read_text())

            def api(packet):
                request = urllib.request.Request(endpoint + "/api/call", data=json.dumps(packet).encode(), headers={
                    "Authorization": "Bearer " + environment["CQ_TOKEN"], "CQ-Session": str(uuid.uuid4()), "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=10) as response:
                    return json.load(response)

            def integration():
                return api({"Read": {"input": {"project": manifest["project"]["project"], "selection": {"Integration": {"id": final["id"]}}}}})["Integration"]["record"]

            if scenario == "conflict":
                assert integration()["resolution"] == {"Pending": {}}
            recovery = run(["job", "upload", "--session", str(beta_session)])
            (case / "recovery.txt").write_text(recovery)
            recorded = integration()
            assert "Recorded" in recorded["resolution"]
            assert recorded["resolution"]["Recorded"]["acknowledgement"]["items"] == [
                {**member, "revision": {"value": str(int(member["revision"]["value"]) + 1)}} for member in final["members"]]
            summary_request = {"Usage": {"input": {"project": manifest["project"]["project"], "selection": {"Summary": {
                "filter": {"SessionOnly": {"id": manifest["attempt"]["session"]}}}}}}}
            usage_before = api(summary_request)
            assert "Acknowledged 0" in run(["job", "upload", "--session", str(beta_session)])
            assert api(summary_request) == usage_before, "Frozen-plan/integration replay changed usage accounting"
            assert integration() == recorded
            assert jobs == {path.name: path.read_bytes() for path in (beta_session / "journal").glob("*.json") if path.name in jobs}
            assert sorted(path.name for path in (beta_session / "journal").glob("*.json")) == job_names
            assert (repository / ".git/logs/refs/heads/integration").read_bytes() == reflog
            for name in ["alpha", "beta"] + (["gamma"] if scenario == "conflict" else []):
                assert git("show", "refs/heads/integration:" + name + ".txt") == name
                assert not (repository / (name + ".txt")).exists()
            assert git("show", "refs/heads/integration:shared.txt") == ("alpha\nbeta" if scenario == "conflict" else "base")
            assert git("rev-parse", "HEAD") == base and (repository / ".git/index").read_bytes() == index
            assert (repository / "staged.txt").read_text() == "governing staged work\n" and (repository / "untracked.txt").read_text() == "governing untracked work\n"
            for combined_round in rounds:
                child = beta_session / "children" / combined_round["worker"]["attempt"]["value"]
                plan = json.loads((child / "combination.json").read_text())
                candidate = combined_round["integration"]["preview"]["candidate"]["value"]
                assert git("rev-list", "--parents", "-n", "1", candidate).split() == [candidate, plan["observedTarget"]["value"], plan["candidate"]["value"]]
                assert (child / "assets/merge-ready").read_text() in ["0\n", "1\n"]
                assert plan["members"] == final["members"]
            first_child = beta_session / "children" / rounds[0]["worker"]["attempt"]["value"]
            assert (first_child / "assets/merge-ready").read_text() == ("1\n" if scenario == "conflict" else "0\n")
            all_traffic = []
            for actor in ["alpha", "beta"]:
                session, values = events(actor)
                assert "CHILD_ONLY_NARRATIVE" not in json.dumps(values)
                all_traffic.extend(value for value in values if value.get("type") == "fixture.dispatch")
                run_record = json.loads((session / "run.json").read_text())
                attempts = api({"Usage": {"input": {"project": manifest["project"]["project"], "selection": {"Attempts": {
                    "filter": {"SessionOnly": {"id": run_record["attempt"]["session"]}}, "after": None, "snapshot": None, "limit": 100}}}}})["UsageAttempts"]["page"]["entries"]
                assert len(attempts) == 1 + len(list((session / "children").iterdir()))
                assert all(value["assignment"]["evaluation"]["scenario"] == scenario for value in attempts)
                for value in attempts:
                    if value["attempt"]["parent"] is not None:
                        assert value["attempt"]["parent"] == run_record["attempt"]["id"]
                        ticket = json.loads((session / "children" / value["attempt"]["id"]["value"] / "ticket.json").read_text())
                        assert value["assignment"]["members"] == [member["id"] for member in ticket["request"]["members"]]
            maximum = max(len(json.dumps(value["reply"]).encode()) for value in all_traffic)
            assert maximum < 4096
            summaries.append({"scenario": scenario, "alphaSession": str(alpha_session), "betaSession": str(beta_session),
                "combinationRounds": len(rounds), "finalIntegration": recorded, "maxParentReplyBytes": maximum,
                "crashRecovered": scenario == "conflict", "additionalGitJobsOrRefUpdatesDuringRecovery": 0})
        finally:
            for process in processes.values():
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=10)
            (latch / "release").touch()
            for stream in streams:
                stream.close()
    (root / "results.json").write_text(json.dumps(summaries, indent=2) + "\n")
    print(json.dumps(summaries))
    print("Two governors: stale target rejection, clean/conflicting combination, fresh validation/review, further advancement, SIGKILL reconciliation, audit attribution and checkout/index isolation passed")


if __name__ == "__main__":
    main()
