import contextlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import uuid


def main():
    command = sys.argv[1:]
    assert command
    retained = os.environ.get("CQ_CHECKOUT_EVIDENCE")
    if retained is not None:
        Path(retained).mkdir(parents=True)
    with (contextlib.nullcontext(retained) if retained is not None else tempfile.TemporaryDirectory(prefix="cq-checkout-")) as temporary:
        root = Path(temporary)
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("CQ_", "GIT_"))}
        environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null", LC_ALL="C")
        preload = root / "stall.so"
        subprocess.run(["gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-o", str(preload), "dev/shutdown-stall.c", "-ldl"], check=True)

        def git(repo, *args):
            result = subprocess.run(["git", *args], cwd=repo, env=environment, text=True, capture_output=True, timeout=15)
            assert result.returncode == 0, result.stderr
            return result.stdout.strip()

        for mode in ["success", "checkout-index", "checkout-completed"]:
            case = root / mode
            case.mkdir()
            repo = case / "repository"
            repo.mkdir()
            git(repo, "init", "-b", "main")
            git(repo, "config", "user.name", "CQ fixture")
            git(repo, "config", "user.email", "cq@localhost")
            for name in ["changed", "deleted", "dirty"]:
                (repo / name).write_text("base\n")
            git(repo, "add", ".")
            git(repo, "commit", "-m", "base")
            base = git(repo, "rev-parse", "HEAD")
            worker = case / "worker"
            git(repo, "worktree", "add", "--detach", str(worker), base)
            (worker / "changed").write_text("candidate\n")
            (worker / "deleted").unlink()
            (worker / "tab\tnewline\nfile").write_text("unusual path\n")
            (worker / "link").symlink_to("changed")
            (worker / "nested").mkdir()
            (worker / "nested/new").write_text("nested candidate\n")
            git(worker, "add", ".")
            git(worker, "commit", "-m", "candidate")
            candidate = git(worker, "rev-parse", "HEAD")
            (repo / "dirty").write_text("staged\n")
            git(repo, "add", "dirty")
            (repo / "dirty").write_text("unstaged over staged\n")
            (repo / "nested").mkdir()
            (repo / "nested/local").write_text("unrelated local\n")
            (repo / ".git/info/exclude").write_text("nested/local\n")
            original_index = (repo / ".git/index").read_bytes()
            identity = {"value": str(uuid.uuid4())}
            directory = case / "checkouts" / identity["value"]
            directory.mkdir(parents=True, mode=0o700)
            intent = {"id": identity, "project": {"value": str(uuid.uuid4())},
                      "owner": {"subject": "fixture", "session": {"value": str(uuid.uuid4())}, "role": "Governor"},
                      "repository": str(repo), "target": "refs/heads/main", "expected": {"value": base}, "candidate": {"value": candidate},
                      "worker": {"value": str(uuid.uuid4())}, "reviewer": {"value": str(uuid.uuid4())}, "checks": [],
                      "fence": {"claim": {"value": str(uuid.uuid4())}, "generation": "1"}, "members": [],
                      "change": {"request": identity, "mutations": [], "fences": [], "reason": "Fixture"}}
            source = directory / "intent.json"
            source.write_text(json.dumps({"intent": intent, "attached": True}))
            injected = environment if mode == "success" else {**environment, "LD_PRELOAD": str(preload),
                         "CQ_FIXTURE_STALL_ROOT": str(case), "CQ_FIXTURE_STALL_MODE": mode}
            with (case / "stdout").open("w") as out, (case / "stderr").open("w") as err:
                process = subprocess.Popen(command + [":checkout", "--", str(source)], cwd=repo, env=injected,
                                           stdout=out, stderr=err, start_new_session=True)
                try:
                    if mode == "success":
                        assert process.wait(timeout=60) == 0, (case / "stderr").read_text()
                    else:
                        deadline = time.monotonic() + 60
                        while not (case / "entered").exists():
                            assert process.poll() is None, (case / "stderr").read_text()
                            assert time.monotonic() < deadline, "Checkout latch not reached"
                            time.sleep(0.02)
                        os.killpg(process.pid, signal.SIGKILL)
                        assert process.wait(timeout=10) == -signal.SIGKILL
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=10)
            assert git(repo, "symbolic-ref", "HEAD") == "refs/heads/main"
            assert (repo / "dirty").read_text() == "unstaged over staged\n"
            assert git(repo, "show", ":dirty") == "staged"
            assert (repo / "changed").read_text() == "candidate\n"
            assert not (repo / "deleted").exists()
            assert (repo / "link").is_symlink()
            assert (repo / "tab\tnewline\nfile").read_text() == "unusual path\n"
            assert (repo / "nested/new").read_text() == "nested candidate\n"
            assert (repo / "nested/local").read_text() == "unrelated local\n"
            if mode == "success":
                assert git(repo, "rev-parse", "HEAD") == candidate
                assert (directory / "completed.json").is_file()
                assert not (repo / ".git/index.lock").exists()
            else:
                assert (repo / ".git/index.lock").read_text() == f"CQ checkout {identity['value']}\n{directory}\n"
                assert (directory / "started.json").is_file() and not (directory / "refused.json").exists()
                assert (directory / "index-before").read_bytes() == original_index
                assert git(repo, "rev-parse", "HEAD") == (base if mode == "checkout-index" else candidate)
                assert (directory / "completed.json").exists() == (mode == "checkout-completed")
                if mode == "checkout-index":
                    assert (repo / ".git/index").read_bytes() == original_index
                before = (repo / ".git/index").read_bytes()
                replay = subprocess.run(command + [":checkout", "--", str(source)], cwd=repo, env=environment,
                                        text=True, capture_output=True, timeout=60)
                assert replay.returncode != 0 and (repo / ".git/index").read_bytes() == before
                assert not (directory / "refused.json").exists(), "Retry must not classify an interrupted effect as refused"
            (case / "result.json").write_text(json.dumps({"mode": mode, "passed": True}) + "\n")
        print("Attached checkout: dirty layers, deletion, symlink, unusual names, and actual interruption before index publication/after ref commit passed")


if __name__ == "__main__":
    main()
