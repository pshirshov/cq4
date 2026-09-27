#!/usr/bin/env python3
"""Check that this Go consumer's tests detect rejection of truly empty input."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    root = Path.cwd()
    main_file = root / "main.go"
    source = main_file.read_text()
    anchor = "\tif err := writeRecords(stdout, wordRecords(input), limit); err != nil {"
    assert source.count(anchor) == 1, "The pinned consumer mutation site changed; inspect the new implementation before redefining this experiment"
    mutation = '\tif len(input) == 0 {\n\t\tfmt.Fprintln(stderr, "mutation: reject empty input")\n\t\treturn 1\n\t}\n'
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"]).decode().split("\0")
    with tempfile.TemporaryDirectory(prefix="cq-empty-input-") as temporary:
        copy = Path(temporary)
        for name in paths:
            if not name:
                continue
            relative = Path(name)
            assert not relative.is_absolute() and ".." not in relative.parts
            original = root / relative
            assert original.is_file() and not original.is_symlink()
            destination = copy / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, destination)
        commands = []
        for label in ["original", "empty-input-mutant"]:
            if label == "empty-input-mutant":
                (copy / "main.go").write_text(source.replace(anchor, mutation + anchor))
            result = subprocess.run(["go", "test", "-count=1", "./..."], cwd=copy, capture_output=True, text=True, timeout=60)
            print(f"{label}: go test -count=1 ./...; exit {result.returncode}\n{result.stdout}{result.stderr}", flush=True)
            commands.append(result)
        assert commands[0].returncode == 0, "Original consumer tests failed"
        assert commands[1].returncode != 0, "Consumer tests did not detect rejection of truly empty input"
        assert "mutation: reject empty input" in commands[1].stdout + commands[1].stderr, "Mutant tests failed for another reason"
    assert main_file.read_text() == source, "Mutation changed the candidate workspace"
    print("PASS: original tests pass and reject the isolated empty-input mutant; candidate unchanged", flush=True)


if __name__ == "__main__":
    main()
