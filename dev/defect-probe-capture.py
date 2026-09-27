"""Portable observation recorder for the predeclared R1/H2/H3 experiments."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def fixture(diagnostics):
    def git(arguments):
        argv = ["git", *arguments]
        result = subprocess.run(argv, text=True, capture_output=True, timeout=30)
        observation = {"argv": argv, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        diagnostics.append(observation)
        if result.returncode != 0:
            raise RuntimeError(json.dumps(observation))
        return result.stdout
    head = git(["rev-parse", "HEAD"]).strip()
    names = git(["ls-files", "-z"]).split("\0")
    return {"head": head, "files": {name: hashlib.sha256(Path(name).read_bytes()).hexdigest() for name in names if name}}


def main():
    timeout_seconds = 30
    diagnostics = []
    before = fixture(diagnostics)
    probes = [
        ("reproduction", [sys.executable, "-m", "unittest", "-v"], ""),
        ("selection", [sys.executable, "-c", 'from synthetic_tokens import tokens; print(repr(tokens("a_b 12 café")))'], ""),
        ("normalization-aggregation", [sys.executable, "-c", 'import count_words; count_words.tokens=lambda _: ["Z","z","A"]; print(repr(count_words.counts("ignored")))'], ""),
        ("nonconforming-injection", [sys.executable, "-c", 'import count_words; count_words.tokens=lambda _: ["a_b","12","café"]; print(repr(count_words.counts("ignored")))'], ""),
        ("cli", [sys.executable, "count_words.py"], "a_b 12 café"),
    ]
    observations = []
    for name, argv, stdin in probes:
        result = subprocess.run(argv, input=stdin, text=True, capture_output=True, timeout=timeout_seconds)
        observations.append({"name": name, "argv": argv, "stdin": stdin, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    record = {"cwd": str(Path.cwd().resolve()), "before": before, "after": fixture(diagnostics), "observations": observations, "gitDiagnostics": diagnostics}
    text = json.dumps(record, ensure_ascii=False, sort_keys=True)
    Path("cq-probe-observations.json").write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
