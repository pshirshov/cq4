import fcntl
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

# One CLI invocation of a fixture is a process start and one bounded request. A start took about 2 s on the JVM and up to 5.3 s
# under the native-image agent on a loaded machine (2026-10-07); six times the slowest tells a slow start from a stuck one. A fixture
# of many invocations bounds each of them by this and has no bound on their total, which would assume a quiet machine.
CLI_CASE_SECONDS = 30


def cli_case(label: str, command: list[str], cwd, environment) -> subprocess.CompletedProcess:
    """Runs one CLI invocation and prints how long it took. One that does not end within the bound fails the fixture by its label."""
    began = time.monotonic()
    try:
        result = subprocess.run(command, cwd=cwd, env=environment, capture_output=True, text=True, timeout=CLI_CASE_SECONDS)
    except subprocess.TimeoutExpired:
        raise AssertionError(f"CLI case did not end within {CLI_CASE_SECONDS} seconds: {label}") from None
    print(json.dumps({"case": label, "seconds": round(time.monotonic() - began, 2), "exit": result.returncode}), flush=True)
    return result


def waiter_slot(process: subprocess.Popen, directory: Path) -> int | None:
    """The slot the `cq wait` that `process` started holds on the session directory, or nothing while it holds none. A waiter holds a
    shared lock on the byte of its slot of the session's `waiters.lock` for as long as it runs, which refuses an exclusive one; the
    slot is the process identifier of the waiter and follows the turn byte. An installed command runs the waiter as a child of its
    launcher, so the waiter is looked for among the started process and its descendants."""
    def family(pid: int) -> list[int]:
        try:
            children = [int(child) for task in Path(f"/proc/{pid}/task").glob("*/children") for child in task.read_text().split()]
        except OSError:
            # The process ended while it was read.
            children = []
        return [pid] + [member for child in children for member in family(child)]
    with (Path(directory) / "waiters.lock").open("r+") as stream:
        for pid in family(process.pid):
            try:
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB, 1, 1 + pid)
            except OSError:
                return pid
            fcntl.lockf(stream, fcntl.LOCK_UN, 1, 1 + pid)
    return None


def guardian_binary(directory: Path, provided: str | None) -> Path:
    if provided is not None:
        binary = Path(provided)
        assert binary.is_absolute() and binary.is_file() and os.access(binary, os.X_OK), "Explicit fixture guardian must be an absolute executable"
        return binary
    binary = directory / "cq-guardian"
    source = Path(__file__).resolve().parent.parent / "host/native/guardian.c"
    subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(binary), str(source)], check=True, timeout=60)
    return binary


# A session starts a child only on the models its project's agent configuration assigns to the child's role. The fixtures whose
# children run the governing harness save, as the configuration of their project, what `cq agents init` derives from their settings
# file of one harness: its settings model is of no lineup that command knows, so every role runs the settings model of the governing harness.
AGENTS_INIT = ["agents", "init", "--save", "project", "--settings"]
ROLES = ["planner", "worker", "explorer", "reviewer"]
_RESERVED = "%?#,[]{} "


def _encoded(text: str, slash: bool) -> str:
    return "".join(f"%{ord(character):02X}" if character in _RESERVED or (slash and character == "/") else character for character in text)


def _model(setting: dict) -> str:
    # The spelling of a model name in a tier: a Pi model is written with its provider; a Codex model takes that of its settings entry.
    if setting["harness"] == "Pi":
        body = _encoded(setting["provider"], True) + "/" + _encoded(setting["model"], False)
    else:
        body = _encoded(setting["model"], True)
    if body.startswith("@"):
        body = "%40" + body[1:]
    return body[:-1] + "%3A" if body.endswith(":") else body


def role_agents(harnesses: list[dict], roles: dict[str, str]) -> str:
    """The text of an agent configuration for the harness entries of a settings file. Each harness runs its settings model in every
    tier; each role runs the standard tier of the harness `roles` names for it, by its settings name such as "Codex", and a role it
    does not name runs the governing harness."""
    assert set(roles) <= set(ROLES), roles
    lines = ["defaults:", "  roles:"]
    lines += [f"    {role}: {roles[role].lower() if role in roles else '$harness'}:@standard" for role in ROLES]
    lines.append("harnesses:")
    for setting in harnesses:
        lines += [f"  {setting['harness'].lower()}:", "    tiers:"]
        lines += [f"      {tier}: [{_model(setting)}]" for tier in ["frontier", "standard", "fast"]]
    return "\n".join(lines) + "\n"


def save_agents(call, project: dict, text: str) -> None:
    """Makes `text` the agent configuration of the project, whatever it held. `call` posts one command as an operator and returns
    its result."""
    current = call({"Agents": {"input": {"project": project, "action": {"Read": {}}}}})["Agents"]["value"]["project"]
    if current["text"] != text:
        saved = call({"Agents": {"input": {"project": project, "action": {"Replace": {
            "scope": {"Project": {}}, "expected": current["revision"], "text": text}}}}})["Agents"]["value"]["project"]
        assert saved["text"] == text and not saved["problems"], saved


def operator_call(origin: str, token: str):
    """Posts one command to the server as its operator and returns the result."""
    def call(body: dict) -> dict:
        request = urllib.request.Request(origin + "/api/call", data=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + token, "CQ-Session": str(uuid.uuid4()), "CQ-Protocol-Version": "0.1.0", "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)
    return call
