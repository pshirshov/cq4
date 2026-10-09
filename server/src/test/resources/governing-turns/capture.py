#!/usr/bin/env python3
"""Captures the hook invocations of a real Claude Code process for the governing-turn cases (a) to (g) of Task 91.

  capture.py --out DIR [--cases a,b,c,d,e,f,g] [--modes print,interactive]     against the stub provider (stub.py): no real model,
                                                                                a private empty HOME and CLAUDE_CONFIG_DIR, a wrong key
  capture.py --real --out DIR [--cases ...]                                    against the login of the invoking user: the process
                                                                                keeps its environment, no stub, no private directories

Per case DIR/<mode>-<case>/ holds: settings.json (the hook settings, passed with --settings), hooks.jsonl (every hook invocation: its
unchanged stdin and time), stub.jsonl (stub mode: the provider's request log with arrival and completion times), stdout.jsonl or
transcript.txt (the native output), stderr.txt, exit, command.txt, notes.txt. All times are time.time() * 1000 of this machine
(CLOCK_REALTIME). The prompts carry [stub:...] markers that only the stub reads; a real model sees them as text."""
import argparse, json, os, pathlib, re, select, shutil, signal, subprocess, sys, threading, time

HERE = pathlib.Path(__file__).resolve().parent
EVENTS = ["PreToolUse", "PostToolUse", "PostToolUseFailure", "PostToolBatch", "Notification", "UserPromptSubmit", "UserPromptExpansion",
          "SessionStart", "SessionEnd", "Stop", "StopFailure", "SubagentStart", "SubagentStop", "PreCompact", "PostCompact", "PreModelSwitch",
          "PostModelSwitch", "PermissionRequest", "PermissionDenied", "Setup", "TeammateIdle", "TaskCreated", "TaskCompleted", "Elicitation",
          "ElicitationResult", "ConfigChange", "WorktreeCreate", "WorktreeRemove", "InstructionsLoaded", "CwdChanged", "FileChanged",
          "DirectoryAdded", "MessageDisplay"]
PROMPTS = {
    "a": ["Reply with the single word ready. [stub:plain]"],
    "b": ["Run the shell command `sleep 2` with the Bash tool, then reply with the word done. [stub:sleep]"],
    "c": ["Reply with the single word ready. [stub:plain]"],
    "d": ["Reply with the single word one. [stub:plain]", "Reply with the single word two. [stub:plain]"],
    "e": ["Think about this for a long while, then reply with the word late. [stub:hold]"],
    "ei": ["Think about this for a long while, then reply with the word late. [stub:hold]"],
    "f": ["Start the shell command `sleep 6; echo bg-done` in the background with the Bash tool (run_in_background), then end your turn. [stub:bg]"],
    "g": ["Start one subagent with the Task tool and report what it says. [stub:agent]"],
}
WRONG_KEY = "sk-ant-api03-cq-governing-turns-capture-wrong-key-00000000000000000000000000000000000000000000000000000000-AAAAAAAA"
MODEL = "claude-opus-5-5"

def now(): return time.time() * 1000
def lines(path):
    try: return [json.loads(l) for l in open(path) if l.strip()]
    except FileNotFoundError: return []
def count(path, event): return sum(1 for r in lines(path) if r["event"] == event)
def wait_for(predicate, seconds, step=0.1):
    end = time.time() + seconds
    while time.time() < end:
        if predicate(): return True
        time.sleep(step)
    return False

class Stub:
    def __init__(self, directory):
        self.log = directory / "stub.jsonl"; portfile = directory / "stub.port"
        self.process = subprocess.Popen([sys.executable, str(HERE / "stub.py"), str(portfile), str(self.log)])
        assert wait_for(lambda: portfile.exists() and portfile.read_text().strip(), 10), "stub did not start"
        self.url = "http://127.0.0.1:" + portfile.read_text().strip()
        portfile.unlink()
    def stop(self): self.process.terminate(); self.process.wait()

def settings(directory, case):
    log = directory / "hooks.jsonl"; state = directory / "stop-block.state"
    def command(event):
        extra = f" --block-once {state}" if case == "c" and event == "Stop" else ""
        return f"{sys.executable} {HERE / 'hook.py'} {log} {event}{extra}"
    value = {"hooks": {event: [{"hooks": [{"type": "command", "command": command(event)}]}] for event in EVENTS}}
    (directory / "settings.json").write_text(json.dumps(value, indent=2) + "\n")
    return directory / "settings.json"

def environment(args, directory, stub):
    if args.real: return dict(os.environ)
    home = directory / "home"; config = directory / "config"; home.mkdir(); config.mkdir()
    # No onboarding dialogs and no trust prompt in the private configuration; the wrong key is pre-approved.
    (config / ".claude.json").write_text(json.dumps({
        "hasCompletedOnboarding": True, "theme": "dark", "numStartups": 5, "bypassPermissionsModeAccepted": True,
        "customApiKeyResponses": {"approved": [WRONG_KEY[-20:]], "rejected": []},
        "projects": {str(directory / "work"): {"hasTrustDialogAccepted": True, "allowedTools": [], "hasCompletedProjectOnboarding": True}}}))
    return {"HOME": str(home), "CLAUDE_CONFIG_DIR": str(config), "PATH": os.environ["PATH"], "LANG": "C.UTF-8", "TERM": "xterm-256color",
            "ANTHROPIC_API_KEY": WRONG_KEY, "ANTHROPIC_BASE_URL": stub.url, "DISABLE_AUTOUPDATER": "1", "DISABLE_TELEMETRY": "1",
            "SSL_CERT_FILE": os.environ.get("SSL_CERT_FILE", "")}

def user_message(text):
    return json.dumps({"type": "user", "message": {"role": "user", "content": [{"type": "text", "text": text}]}}) + "\n"

def run_case(args, mode, case):
    directory = pathlib.Path(args.out) / f"{mode}-{case}"
    shutil.rmtree(directory, ignore_errors=True); (directory / "work").mkdir(parents=True)
    settings_file = settings(directory, case)
    stub = None if args.real else Stub(directory)
    env = environment(args, directory, stub)
    hooks = directory / "hooks.jsonl"
    claude = args.claude
    common = [claude, "--settings", str(settings_file), "--model", MODEL, "--allowedTools", "Bash,Task,Agent", "--permission-mode", "default"]
    notes = []
    prompts = PROMPTS[case]
    stdout_path = directory / ("stdout.jsonl" if mode == "print" else "transcript.raw")
    if mode == "print":
        stream = case in ("d", "f")
        cmd = common + ["-p", "--output-format", "stream-json", "--verbose"] + (["--input-format", "stream-json"] if stream else [prompts[0]])
        (directory / "command.txt").write_text(" ".join(cmd) + "\n")
        out = open(stdout_path, "wb"); err = open(directory / "stderr.txt", "wb")
        proc = subprocess.Popen(cmd, cwd=directory / "work", env=env, stdin=subprocess.PIPE if stream else subprocess.DEVNULL, stdout=out, stderr=err,
                                start_new_session=True)
        results = lambda: sum(1 for l in open(stdout_path, errors="replace") if '"type":"result"' in l)
        started = now()
        if case == "d":
            for index, text in enumerate(prompts):
                proc.stdin.write(user_message(text).encode()); proc.stdin.flush()
                assert wait_for(lambda: results() >= index + 1, 90), "no result"
            proc.stdin.close()
        elif case == "f":
            proc.stdin.write(user_message(prompts[0]).encode()); proc.stdin.flush()
            assert wait_for(lambda: results() >= 1, 90), "no result"
            # The input stays open: does the exit of the background command start the session again?
            woke = wait_for(lambda: results() >= 2, 45)
            notes.append(f"background command woke the session (a second result event): {woke}")
            proc.stdin.close()
        elif case in ("e", "ei"):
            held = wait_for(lambda: any(r.get("mode") == "hold" and r["phase"] == "arrive" for r in lines(stub.log)), 60)
            time.sleep(1.0)
            sig = signal.SIGINT if case == "ei" else signal.SIGTERM
            notes.append(f"signal {sig.name} sent at t_ms={now():.3f} to the process group leader pid {proc.pid} (held request seen: {held})")
            os.kill(proc.pid, sig)
        try: proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            notes.append("process still running 120 s later; killed with SIGKILL"); proc.kill(); proc.wait()
        (directory / "exit").write_text(str(proc.returncode) + "\n")
        notes.append(f"started t_ms={started:.3f} exited t_ms={now():.3f}")
        time.sleep(1.5)  # late hooks (SessionEnd)
    else:
        import pty
        cmd = common
        (directory / "command.txt").write_text(" ".join(cmd) + "\n")
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(directory / "work"); os.execvpe(cmd[0], cmd, {**env, "COLUMNS": "120", "LINES": "40"})
        import fcntl, struct, termios
        fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 40, 120, 0, 0))
        raw = open(stdout_path, "wb"); stamps = open(directory / "transcript.times", "w")
        stop_reading = threading.Event()
        def pump():
            while not stop_reading.is_set():
                ready, _, _ = select.select([fd], [], [], 0.2)
                if ready:
                    try: data = os.read(fd, 65536)
                    except OSError: return
                    if not data: return
                    raw.write(data); raw.flush(); stamps.write(f"{now():.3f} {len(data)}\n"); stamps.flush()
        pump_thread = threading.Thread(target=pump, daemon=True); pump_thread.start()
        def send(text): os.write(fd, text.encode()); time.sleep(0.4); os.write(fd, b"\r")
        ready = wait_for(lambda: count(hooks, "SessionStart") >= 1, 60) and wait_for(lambda: os.path.getsize(stdout_path) > 0, 5)
        time.sleep(3.0)  # let the input box settle
        notes.append(f"session started (SessionStart seen): {ready}")
        if case == "d":
            for index, text in enumerate(prompts):
                send(text)
                if not wait_for(lambda: count(hooks, "Stop") >= index + 1, 90): notes.append(f"Stop {index + 1} not seen within 90 s")
                time.sleep(1.5)
        elif case in ("e", "ei"):
            send(prompts[0])
            held = wait_for(lambda: any(r.get("mode") == "hold" and r["phase"] == "arrive" for r in lines(stub.log)), 60)
            time.sleep(1.0)
            notes.append(f"Escape (interrupt) sent at t_ms={now():.3f}; held request seen: {held}")
            os.write(fd, b"\x1b")
            time.sleep(4.0)
            notes.append(f"Stop invocations after the interrupt: {count(hooks, 'Stop')}")
        else:
            send(prompts[0])
            if not wait_for(lambda: count(hooks, "Stop") >= 1, 90): notes.append("Stop 1 not seen within 90 s")
            if case == "f":
                before = count(hooks, "UserPromptSubmit")
                woke = wait_for(lambda: count(hooks, "Stop") >= 2, 60)
                notes.append(f"a second Stop after the first (the background command's exit woke the session): {woke}")
            time.sleep(1.5)
        os.write(fd, b"\x15"); time.sleep(0.3); send("/exit")
        done = wait_for(lambda: os.waitpid(pid, os.WNOHANG) != (0, 0), 15)
        if not done:
            notes.append("process still running 15 s after /exit; killed with SIGKILL")
            os.kill(pid, signal.SIGKILL); os.waitpid(pid, 0)
        (directory / "exit").write_text("0 (left through /exit)\n" if done else "killed\n")
        stop_reading.set(); pump_thread.join(2); raw.close(); stamps.close()
        text = re.sub(rb"\x1b\[[0-9;?<>=]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>]", b"", pathlib.Path(stdout_path).read_bytes())
        (directory / "transcript.txt").write_text(text.decode("utf-8", "replace").replace("\r", "\n"))
    if stub: stub.stop()
    (directory / "notes.txt").write_text("\n".join(notes) + "\n")
    for leftover in ("home", "config", "work", "stop-block.state"):
        target = directory / leftover
        if target.is_dir(): shutil.rmtree(target, ignore_errors=True)
        elif target.exists(): target.unlink()
    print(f"{mode}-{case}: hooks={len(lines(hooks))} stops={count(hooks, 'Stop')} prompts={count(hooks, 'UserPromptSubmit')} notes={notes}")

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True); p.add_argument("--real", action="store_true")
    p.add_argument("--cases", default="a,b,c,d,e,ei,f,g"); p.add_argument("--modes", default="print,interactive")
    p.add_argument("--claude", default=shutil.which("claude") or "claude")
    args = p.parse_args()
    args.out = os.path.abspath(args.out)
    for mode in args.modes.split(","):
        for case in args.cases.split(","):
            run_case(args, mode, case)

if __name__ == "__main__": main()
