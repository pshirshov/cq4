#!/usr/bin/env python3
"""Record public Codex hook hashes without loading authentication or approving hooks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import tempfile
import time

MAX_BYTES = 1024 * 1024
DEADLINE_SECONDS = 15


def read_hooks(project):
    path = project / ".codex/hooks.json"
    with path.open("rb") as stream:
        value = stream.read(MAX_BYTES + 1)
    if len(value) > MAX_BYTES:
        raise RuntimeError("Codex hook configuration exceeds 1 MiB")
    return value


def record(executable, version, project):
    before = read_hooks(project)
    with tempfile.TemporaryDirectory(prefix="cq-codex-hook-inspection-") as directory:
        environment = {key: value for key, value in os.environ.items() if key in ("PATH", "LANG", "LC_ALL")}
        environment.update(CODEX_HOME=directory, NO_COLOR="1")
        Path(directory, "config.toml").write_text("[projects." + json.dumps(str(project)) + ']\ntrust_level = "trusted"\n')
        installed = subprocess.run([str(executable), "--version"], env=environment, cwd=project,
                                   stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=DEADLINE_SECONDS, check=True)
        if version not in installed.stdout.decode().split():
            raise RuntimeError("Installed Codex version differs from the requested report version")
        arguments = [str(executable), "app-server"]
        process = subprocess.Popen(arguments, env=environment, cwd=project, start_new_session=True,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        buffer = b""
        total = 0
        deadline = time.monotonic() + DEADLINE_SECONDS
        try:
            for identity, method, params in (
                (1, "initialize", {"clientInfo": {"name": "cq-hook-inspection", "version": "0.1.0"}, "capabilities": {"experimentalApi": True}}),
                (2, "hooks/list", {"cwds": [str(project)]}),
            ):
                process.stdin.write((json.dumps({"id": identity, "method": method, "params": params}) + "\n").encode())
                process.stdin.flush()
                found = None
                while found is None:
                    while b"\n" in buffer:
                        line, buffer = buffer.split(b"\n", 1)
                        value = json.loads(line)
                        if value.get("id") == identity:
                            if "error" in value:
                                raise RuntimeError("Codex rejected the hook inspection request; response contents withheld")
                            found = value["result"]
                            break
                    if found is not None:
                        break
                    remaining = deadline - time.monotonic()
                    if remaining <= 0 or not select.select([process.stdout], [], [], remaining)[0]:
                        raise RuntimeError("Codex hook inspection exceeded its deadline")
                    chunk = os.read(process.stdout.fileno(), 65536)
                    if not chunk:
                        raise RuntimeError("Codex exited before completing hook inspection")
                    total += len(chunk)
                    if total > MAX_BYTES:
                        raise RuntimeError("Codex hook inspection exceeds 1 MiB")
                    buffer += chunk
                listing = found
        finally:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
        if read_hooks(project) != before:
            raise RuntimeError("Hook configuration changed during inspection; report refused")
        return {"version": version, "project": str(project), "sha256": hashlib.sha256(before).hexdigest(), "listing": listing}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executable", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    if not options.executable.is_absolute() or not options.project.is_absolute() or not options.output.is_absolute():
        parser.error("All paths must be absolute")
    if options.output.is_symlink() or options.output.exists() and not options.output.is_file():
        parser.error("Output must be a regular file or an absent path")
    report = record(options.executable, options.version, options.project)
    descriptor, temporary = tempfile.mkstemp(prefix="cq-hook-report-", dir=options.output.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(report, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, options.output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print("Recorded hook inspection; no hook approvals or credentials were changed")


if __name__ == "__main__":
    main()
