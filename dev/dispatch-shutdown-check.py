import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time


def main():
    command = sys.argv[1:]
    assert command, "CQ launcher command required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    environment["CQ_TOKEN"] = os.environ["CQ_TOKEN"]
    endpoint = os.environ["CQ_ORIGIN"]
    with tempfile.TemporaryDirectory(prefix="cq-dispatch-shutdown-") as temporary:
        root = Path(temporary)
        repository = root / "consumer"
        repository.mkdir()
        subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "-c", "user.name=CQ fixture", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "Consumer base"], check=True)
        guardian = root / "cq-guardian"
        subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(guardian), "host/native/guardian.c"], check=True)
        preload = root / "stall.so"
        subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)
        executable = root / "fixture-harness"
        executable.write_text(f"#!{sys.executable}\n" + Path("dev/dispatch-fixture.py").read_text())
        executable.chmod(0o700)
        settings = root / "settings.json"
        settings.write_text(json.dumps({
            "stateRoot": str(root / "sessions"), "guardian": str(guardian), "evaluation": None,
            "harnesses": [{"harness": "Codex", "executable": str(executable), "model": "fixture-model", "provider": "fixture-provider",
                           "version": "0.156.1", "providerExtensions": [], "providerEnvironment": []}],
            "limits": {"startupMillis": "5000", "executionMillis": "60000", "heartbeatMillis": "1000",
                       "graceMillis": "100", "killMillis": "1000", "outputBytes": 262144}, "checks": [],
        }))
        source = root / "request.txt"
        source.write_text("exit-with-running-child")
        subprocess.run(command + ["init", "--endpoint", endpoint], cwd=repository, env=environment, capture_output=True, check=True, timeout=30)
        for mode in ["ticket", "input", "exit", "normal"]:
            latch = root / mode
            latch.mkdir()
            native_env = {**environment, "LD_PRELOAD": str(preload), "CQ_FIXTURE_STALL_ROOT": str(latch), "CQ_FIXTURE_STALL_MODE": mode}
            with (latch / "stdout").open("w") as out, (latch / "stderr").open("w") as err:
                process = subprocess.Popen(command + ["run", "codex", "--settings", str(settings), "--input", str(source)],
                                           cwd=repository, env=native_env, stdout=out, stderr=err)
                began = time.monotonic()
                try:
                    if mode != "normal":
                        while not (latch / "entered").exists() and time.monotonic() - began < 20 and process.poll() is None:
                            time.sleep(0.05)
                        assert (latch / "entered").exists(), (mode, "I/O stall was not reached", (latch / "stderr").read_text()[-4000:])
                        print(json.dumps({"mode": mode, "stalledFile": (latch / "entered").read_text()}), flush=True)
                    try:
                        code = process.wait(timeout=25)
                    except subprocess.TimeoutExpired:
                        raise AssertionError(f"{mode}: supervisor still alive after 25 s with I/O held; expected bounded unresolved exit")
                    assert code == (0 if mode == "normal" else 75), (mode, code, (latch / "stderr").read_text()[-4000:])
                    print(json.dumps({"mode": mode, "exit": code, "elapsedSeconds": round(time.monotonic() - began, 3)}), flush=True)
                finally:
                    (latch / "release").touch()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            if mode == "normal":
                receipt = json.loads((latch / "stdout").read_text())
                session = Path(receipt["directory"])
                child = next((session / "children").iterdir())
                child_status = json.loads((child / "receipt.json").read_text())
                assert child_status["phase"] == "Cancelled" and child_status["result"] is None and child_status["usageDelivered"], child_status
            else:
                entered = Path((latch / "entered").read_text())
                session = next(value for value in entered.parents if value.parent == root / "sessions")
                assert not (session / "receipt.json").exists(), "Forced shutdown produced a successful receipt"
                assert not list((session / "children").glob("*/receipt.json")), "Stalled child was falsely admitted"
                # Check only processes whose command contains this fixture's unique session directory.
                for _ in range(100):
                    active = []
                    for proc in Path("/proc").iterdir():
                        if proc.name.isdigit():
                            try:
                                if str(session).encode() in (proc / "cmdline").read_bytes():
                                    active.append(int(proc.name))
                            except (FileNotFoundError, PermissionError, ProcessLookupError):
                                pass
                    if not active:
                        break
                    time.sleep(0.05)
                assert not active, (mode, "Owned guardian/harness hierarchy survived shutdown", active)
                assert not list((session / "children").glob("*/receipt.json")), "Late publication occurred after releasing I/O"
    print("Supervisor shutdown: held ticket/input/governor-exit fsync exits 75; no late launch/publication; guardian drain and normal hierarchy cancellation passed")


if __name__ == "__main__":
    main()
