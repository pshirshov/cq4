"""Behavioral Effectual Good Communication: `cq wait` as a process over a session directory, with a host that lives and ends."""
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import uuid

HOST_GONE, NOT_A_SESSION, SEVERAL_HOSTS = 3, 4, 5
PROJECT = "00000000-0000-4000-8000-000000000001"


def unit(kind, *numbers):
    return {"kind": kind, "id": str(uuid.uuid4()), "members": [{"project": {"value": PROJECT}, "ledger": "Tasks", "number": str(number)} for number in numbers]}


class Session:
    """A session directory as a host leaves it. The lock is the one a live host holds on its journal."""
    def __init__(self, root):
        self.directory = Path(root) / str(uuid.uuid4())
        (self.directory / "journal").mkdir(parents=True)
        (self.directory / "run.json").write_text("{}")
        (self.directory / "waiters.lock").write_text("")
        self.lock = (self.directory / "journal/owner.lock").open("w")
        fcntl.lockf(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def event(self, value):
        with (self.directory / "units.jsonl").open("a") as stream:
            stream.write(json.dumps(value, separators=(",", ":")) + "\n")

    def started(self, value):
        self.event({"Started": {"unit": value}})

    def ended(self, value, phase, next_step, blocker=None):
        self.event({"Ended": {"end": {"unit": value, "phase": phase, "next": next_step, "blocker": blocker}}})

    def host_ends(self):
        self.lock.close()

    def waited(self):
        """Whether a `cq wait` runs on this session: it holds a shared lock for its lifetime, which refuses an exclusive one."""
        with (self.directory / "waiters.lock").open("r+") as stream:
            try:
                fcntl.lockf(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                return True
            fcntl.lockf(stream, fcntl.LOCK_UN)
            return False

    def record(self, checkout):
        """What an attached host leaves in the CQ directory of its checkout."""
        hosts = Path(checkout) / ".cq/hosts"
        hosts.mkdir(parents=True, exist_ok=True)
        (hosts / (self.directory.name + ".json")).write_text(json.dumps({"directory": str(self.directory), "waitCommand": None}))


def main():
    command = sys.argv[1:]

    def wait(session, *arguments, after=None, checkout=None):
        """Runs `cq wait`; `after` runs once the command has been waiting for a while, on the side of the host. Without a session it runs in `checkout`."""
        located = [] if session is None else ["--session", str(session)]
        process = subprocess.Popen(command + ["wait", *located, *arguments], cwd=checkout, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        acted = None
        if after is not None:
            def act():
                # The command is still waiting when the host acts: a wait that ended by itself would have exited by now.
                time.sleep(6)
                assert process.poll() is None, "cq wait ended before anything happened"
                after()
            acted = threading.Thread(target=act)
            acted.start()
        output, errors = process.communicate(timeout=120)
        if acted is not None:
            acted.join()
        return process.returncode, output, errors

    with tempfile.TemporaryDirectory(prefix="cq-wait-") as root:
        session = Session(root)
        code, output, errors = wait(session.directory)
        assert code == 0 and output == "No child attempt, integration, combination or revalidation of this session is active\n", (code, output, errors)
        assert wait(session.directory, "--json")[:2] == (0, '{"Idle":{}}\n')

        first, second = unit("Attempt", 1, 2), unit("Attempt", 3)
        session.started(first)
        session.started(second)
        # A receipt appears while the command waits: it returns then, names what ended and what the host still works on.
        # While it waits, the session's waiter lock says so; afterwards it does not.
        observed = []
        def end():
            observed.append(session.waited())
            session.ended(first, "Completed", "ConsiderAcceptance")
        assert not session.waited()
        code, output, errors = wait(session.directory, after=end)
        assert observed == [True] and not session.waited(), observed
        assert code == 0 and output == (f"attempt {first['id']} on T1,T2 ended: Completed, next ConsiderAcceptance\n"
                                        f"still active: attempt {second['id']} on T3\n"), (code, output, errors)
        # A named attempt that has ended answers at once; the other one is waited for by name.
        code, output, _ = wait(session.directory, "--attempt", first["id"], "--json")
        assert code == 0 and json.loads(output) == {"Ended": {"units": [{"unit": first, "phase": "Completed", "next": "ConsiderAcceptance", "blocker": None}], "active": [second], "settled": []}}, output
        code, output, _ = wait(session.directory, "--attempt", second["id"], "--attempt", first["id"])
        assert code == 0 and output.splitlines()[0].startswith(f"attempt {first['id']}"), output

        # Without a directory the command waits on the session of the one host of its checkout that runs.
        checkout = Path(root) / "checkout"
        checkout.mkdir()
        code, output, _ = wait(None, checkout=checkout)
        assert code == HOST_GONE and output.startswith("No CQ host of this checkout is running"), (code, output)
        session.record(checkout)
        ended = Session(root)
        ended.record(checkout)
        ended.host_ends()
        code, output, errors = wait(None, checkout=checkout, after=lambda: session.ended(second, "Failed", "Retry"))
        assert code == 0 and output == f"attempt {second['id']} on T3 ended: Failed, next Retry\n", (code, output, errors)
        other = Session(root)
        other.record(checkout)
        code, output, _ = wait(None, checkout=checkout)
        assert code == SEVERAL_HOSTS and "2 CQ hosts of this checkout are running" in output and str(session.directory) in output and str(other.directory) in output, (code, output)
        other.host_ends()
        session.started(second)

        integration = unit("Integration")
        session.started(integration)
        prepared = {**integration, "members": first["members"]}
        code, output, _ = wait(session.directory, "--integration", integration["id"],
                               after=lambda: session.ended(prepared, "Ready", "Confirm", "Host check verify: Failed"))
        assert code == 0 and output.splitlines()[0] == f"integration {integration['id']} on T1,T2 ended: Ready, next Confirm, blocker: Host check verify: Failed", output

        # The host goes while an attempt runs: a distinct exit code and what was left unfinished.
        code, output, errors = wait(session.directory, "--json", after=session.host_ends)
        assert code == HOST_GONE and json.loads(output) == {"HostGone": {"active": [second]}}, (code, output, errors)
        code, output, _ = wait(session.directory)
        assert code == HOST_GONE and "is not running" in output and f"attempt {second['id']} on T3" in output and f"cq job upload --session {session.directory}" in output, output

        # D164: a Question the session waits on keeps the command waiting although the host works on nothing; the end the host
        # gives to the waiter that runs ends the wait. An end the host kept for the session's turn end is reported by no waiter.
        asking = Session(root)
        question = lambda number: {"project": {"value": PROJECT}, "ledger": "Questions", "number": str(number)}
        answered = {"question": question(7), "title": "Which way", "status": "Answered", "answer": "The second\nway"}
        asking.event({"Watching": {"question": question(7)}})
        code, output, errors = wait(asking.directory, after=lambda: asking.event({"Settled": {"end": answered, "waiter": True}}))
        assert code == 0 and output == 'question Q7 "Which way" answered: The second way\n', (code, output, errors)
        asking.event({"Watching": {"question": question(8)}})
        asking.event({"Settled": {"end": {**answered, "question": question(8)}, "waiter": False}})
        assert wait(asking.directory, "--json")[:2] == (0, '{"Idle":{}}\n')
        asking.event({"Watching": {"question": question(9)}})
        withdrawn = {"question": question(9), "title": "Dropped", "status": "Withdrawn", "answer": None}
        code, output, errors = wait(asking.directory, "--json", after=lambda: asking.event({"Settled": {"end": withdrawn, "waiter": True}}))
        assert code == 0 and json.loads(output) == {"Ended": {"units": [], "active": [], "settled": [withdrawn]}}, (code, output, errors)
        asking.host_ends()

        code, output, _ = wait(root)
        assert code == NOT_A_SESSION and "is not a CQ session directory" in output, (code, output)
        code, _, errors = wait(session.directory, "--attempt", str(uuid.uuid4()))
        assert code not in (0, HOST_GONE, NOT_A_SESSION), (code, errors)
    print(json.dumps({"idle": "passed", "ends": "passed", "named": "passed", "integration": "passed", "hostGone": "passed", "notASession": "passed", "withoutDirectory": "passed", "waiterLock": "passed", "questions": "passed"}))


if __name__ == "__main__":
    main()
