"""Native interactive process ownership checks, without model requests."""
import fcntl
import os
from pathlib import Path
import pty
import re
import signal
import struct
import subprocess
import threading
import termios
import time


def processes(settings):
    found = []
    for directory in Path("/proc").iterdir():
        if directory.name.isdigit():
            try:
                if str(settings).encode() in (directory / "cmdline").read_bytes():
                    found.append(int(directory.name))
            except (FileNotFoundError, PermissionError, ProcessLookupError):
                pass
    return found


def lifecycle(command, repository, environment, settings, root, client):
    observations = []
    for mode in ["frozen", "dead"]:
        before = set((root / "sessions").glob("*"))
        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 140, 0, 0))
        process = subprocess.Popen(command, cwd=repository, env={**environment, "TERM": "xterm-256color"},
                                   stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
        os.close(slave)
        def drain():
            prompt = b""
            trusted = False
            bypassed = False
            with (root / (mode + ".terminal")).open("wb") as output:
                try:
                    while True:
                        value = os.read(master, 65536)
                        if not value:
                            break
                        output.write(value)
                        output.flush()
                        if b"\x1b[6n" in value:
                            os.write(master, b"\x1b[1;1R")
                        if b"\x1b]10;?" in value:
                            os.write(master, b"\x1b]10;rgb:dddd/dddd/dddd\x1b\\")
                        if b"\x1b]11;?" in value:
                            os.write(master, b"\x1b]11;rgb:1111/1111/1111\x1b\\")
                        prompt = (prompt + value)[-32768:]
                        plain = re.sub(rb"\x1b\[[0-?]*[ -/]*[@-~]", b"", prompt)
                        compact = re.sub(rb"\s+", b"", plain)
                        if not trusted and b"Yes,Itrustthisfolder" in compact:
                            time.sleep(0.5)
                            os.write(master, b"\x1b[B")
                            time.sleep(0.2)
                            os.write(master, b"\r")
                            trusted = True
                        elif not trusted and b"Trustandcontinue" in compact:
                            time.sleep(0.5)
                            os.write(master, b"\r")
                            trusted = True
                        if not bypassed and b"Yes,Iaccept" in compact and b"BypassPermissions" in compact:
                            time.sleep(0.5)
                            os.write(master, b"\x1b[B")
                            time.sleep(0.2)
                            os.write(master, b"\r")
                            bypassed = True
                except OSError:
                    pass
        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        owner = None
        try:
            deadline = time.monotonic() + 50
            session = None
            while time.monotonic() < deadline:
                current = set((root / "sessions").glob("*")) - before
                # An idle session makes no tool call, so its host records no session: the lock every host takes shows that it started.
                ready = [value for value in current if (value / "journal/owner.lock").exists()]
                if ready:
                    assert len(ready) == 1
                    session = ready[0]
                    break
                assert process.poll() is None, "Interactive harness exited before starting CQ"
                time.sleep(0.1)
            assert session is not None, "Interactive harness did not start project CQ; inspect retained terminal"
            hosts = processes(settings)
            assert len(hosts) == 1, hosts
            host = hosts[0]
            chain = []
            current = host
            while current != process.pid and current > 1:
                chain.append(current)
                current = int(Path(f"/proc/{current}/stat").read_text().rsplit(")", 1)[1].split()[1])
            assert current == process.pid, (chain, process.pid)
            owner = chain[1] if len(chain) > 1 else process.pid
            # Surviving startup plus a heartbeat proves the TUI completed MCP initialization.
            deadline = time.monotonic() + 42
            while time.monotonic() < deadline:
                assert host in processes(settings), "Interactive MCP initialization or heartbeat failed"
                time.sleep(0.2)
            started = time.monotonic()
            os.kill(owner, signal.SIGSTOP if mode == "frozen" else signal.SIGKILL)
            deadline = started + 60
            while processes(settings) and time.monotonic() < deadline:
                time.sleep(0.1)
            assert not processes(settings), f"CQ survived its {mode} interactive owner"
            elapsed = time.monotonic() - started
            if mode == "frozen":
                os.kill(owner, signal.SIGCONT)
            client(["job", "upload", "--session", str(session)], mode + "-recovery")
            client(["job", "upload", "--session", str(session)], mode + "-replay")
            assert "No session is recorded" in (root / (mode + "-replay.stdout")).read_text()
            observations.append({"mode": mode, "outerPid": process.pid, "ownerPid": owner, "hostPid": host,
                                 "chain": chain + [process.pid], "exitSeconds": round(elapsed, 3), "session": str(session)})
        finally:
            if owner is not None:
                try:
                    os.kill(owner, signal.SIGCONT)
                except ProcessLookupError:
                    pass
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
            os.close(master)
            reader.join(timeout=2)
    return observations
