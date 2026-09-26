import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    command = sys.argv[1:]
    environment = dict(os.environ)
    environment["CQ_ENDPOINT"] = environment["CQ_ORIGIN"]
    with tempfile.TemporaryDirectory(prefix="cq-cli-") as temporary:
        root = Path(temporary) / "consumer"
        root.mkdir()
        subprocess.run(["git", "init", "--quiet", str(root)], check=True)
        subprocess.run(["git", "-C", str(root), "-c", "user.name=CQ Test", "-c", "user.email=cq@example.invalid",
                        "commit", "--quiet", "--allow-empty", "-m", "initial"], check=True)

        def run(cwd, *args, expected=0):
            result = subprocess.run(command + list(args), cwd=cwd, env=environment, capture_output=True, text=True, timeout=20)
            assert result.returncode == expected, result.stdout + result.stderr
            return result.stdout

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: run(root, "init"), range(2)))
        first = json.loads(results[0].splitlines()[0])["Initialized"]["project"]
        second = json.loads(results[1].splitlines()[0])["Initialized"]["project"]
        assert first == second
        config = json.loads((root / ".git/cq/project.json").read_text())
        assert first["id"] == config["project"]
        worktree = Path(temporary) / "worktree"
        subprocess.run(["git", "-C", str(root), "worktree", "add", "--quiet", "-b", "isolated", str(worktree)], check=True)
        attached = json.loads(run(worktree, "init").splitlines()[0])["Initialized"]["project"]
        assert attached == first
        assert run(worktree, "web").strip() == environment["CQ_ORIGIN"]
        assert json.loads(run(root, "query"))["Found"]["page"]["items"] == []
        assert json.loads(run(root, "status"))["UsageSummary"]["report"]["direct"]["total"]["known"] == "0"
        assert json.loads(run(root, "status", "audit", "--limit", "1"))["UsageAudit"]["page"]["entries"] == []
        run(root, "init", "--project-id", "00000000-0000-0000-0000-000000000001", expected=1)
        subprocess.run(["git", "-C", str(root), "worktree", "remove", str(worktree)], check=True)
        moved = Path(temporary) / "moved"
        root.rename(moved)
        assert json.loads(run(moved, "init").splitlines()[0])["Initialized"]["project"] == first
        independent = Path(temporary) / "independent"
        independent.mkdir()
        copied = json.loads(run(independent, "init", "--project-id", config["project"]["value"]).splitlines()[0])["Initialized"]["project"]
        assert copied == first
        assert (independent / ".cq/project.json").is_file()
        renamed = json.loads(run(moved, "init", "--name", "Renamed consumer").splitlines()[0])["Initialized"]["project"]
        assert renamed["id"] == first["id"] and renamed["name"] == "Renamed consumer", renamed
        assert renamed["revision"]["value"] == "2"
        refreshed = json.loads(run(independent, "init").splitlines()[0])["Initialized"]["project"]
        assert refreshed == renamed
        assert json.loads((independent / ".cq/project.json").read_text())["name"] == renamed["name"]
    print("CLI concurrent init, worktree sharing, moved checkout, explicit reattachment, collision rejection, query and usage audit passed")


if __name__ == "__main__":
    main()
