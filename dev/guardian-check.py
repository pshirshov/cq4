#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


class GuardianChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="guardian-", dir=Path(__file__).resolve().parent.parent / ".work")
        self.directory = Path(self.temporary.name)
        self.input = self.directory / "input"
        self.input.write_text("prompt λ\n")
        self.children = []
        self.ignore_child_exit = False
        self.ignore_termination = False
        self.inherited_descriptors = ()
        self.control_input = subprocess.PIPE

    def tearDown(self):
        for child in self.children:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)
        self.temporary.cleanup()

    def launch(self, command, run_ms, heartbeat_ms, output_bytes):
        binary = os.environ["CQ_GUARDIAN_TEST_BINARY"]
        def signal_policy():
            if self.ignore_child_exit:
                signal.signal(signal.SIGCHLD, signal.SIG_IGN)
            if self.ignore_termination:
                signal.signal(signal.SIGTERM, signal.SIG_IGN)
        process = subprocess.Popen([binary, "500", str(run_ms), str(heartbeat_ms), "100", "1000", str(output_bytes),
                                    str(self.input), str(self.directory / "stdout"), str(self.directory / "stderr"), "--", *command],
                                   stdin=self.control_input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                   preexec_fn=signal_policy, pass_fds=self.inherited_descriptors)
        self.children.append(process)
        return process

    def result(self, process, control):
        output, errors = process.communicate(control, timeout=5)
        self.assertEqual(process.returncode, 0, (output, errors))
        self.assertEqual(errors, "")
        terminal = output.strip().splitlines()[-1].split()
        self.assertEqual(terminal[0], "EXIT", output)
        self.assertEqual(terminal[-2:], ["1", "0"], output)
        return terminal

    def wait_until_exited(self, process):
        process.wait(timeout=5)
        return self.result(process, None)

    def capture(self, path, content, limit, output=subprocess.PIPE):
        return subprocess.run([os.environ["CQ_GUARDIAN_TEST_BINARY"], "--capture", str(path), str(limit)],
                              input=content, stdout=output, stderr=subprocess.PIPE, timeout=5)

    def test_bounded_capture_preserves_bytes_and_fails_on_overflow(self):
        content = b"merge report \xce\xbb\x00\xff\n"
        for suffix, data, limit, expected in [("empty", b"", 100, 0), ("exact", content, len(content), 0),
                                               ("overflow", content * 1000, len(content), 2)]:
            path = self.directory / suffix
            path.touch(mode=0o600)
            result = self.capture(path, data, limit)
            self.assertEqual(result.returncode, expected, result.stderr)
            self.assertEqual(path.read_bytes(), data[:limit])
            self.assertEqual(result.stdout, data[:limit])

    def test_capture_rejects_unsafe_or_reused_files_and_invalid_limits(self):
        missing = self.directory / "missing"
        result = self.capture(missing, b"payload", 10)
        self.assertEqual(result.returncode, 2)
        self.assertFalse(missing.exists())
        source = self.directory / "source"
        source.touch(mode=0o600)
        link = self.directory / "link"
        link.symlink_to(source)
        self.assertEqual(self.capture(link, b"payload", 10).returncode, 2)
        hardlink = self.directory / "hardlink"
        os.link(source, hardlink)
        self.assertEqual(self.capture(source, b"payload", 10).returncode, 2)
        hardlink.unlink()
        for limit in ["0", "-1", "invalid", "67108865"]:
            self.assertEqual(self.capture(source, b"payload", limit).returncode, 2)
            self.assertEqual(source.read_bytes(), b"")
        source.chmod(0o644)
        self.assertEqual(self.capture(source, b"payload", 10).returncode, 2)
        source.chmod(0o600)
        source.write_bytes(b"retained")
        self.assertEqual(self.capture(source, b"replacement", 100).returncode, 2)
        self.assertEqual(source.read_bytes(), b"retained")
        self.assertEqual(self.capture(self.directory, b"payload", 10).returncode, 2)

    def test_capture_refuses_success_when_output_delivery_fails(self):
        path = self.directory / "diagnostics"
        path.touch(mode=0o600)
        with open("/dev/full", "wb") as full:
            result = self.capture(path, b"diagnostics", 100, output=full)
        self.assertEqual(result.returncode, 2)
        self.assertIn(b"capture failed", result.stderr)

    def test_separate_streams_and_prompt(self):
        process = self.launch([sys.executable, "-c", "import sys; print(sys.stdin.read(), end=''); sys.stderr.write('error λ\\n')"], 2000, 3000, 100000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["0", "0", "Exited"])
        self.assertEqual((self.directory / "stdout").read_text(), "prompt λ\n")
        self.assertEqual((self.directory / "stderr").read_text(), "error λ\n")

    def test_nonzero_and_launch_failure_are_separate(self):
        process = self.launch(["/cq-does-not-exist"], 2000, 3000, 100000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["127", "0", "LaunchFailed"])

    def test_nonzero_child_exit(self):
        process = self.launch([sys.executable, "-c", "raise SystemExit(7)"], 2000, 3000, 100000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["7", "0", "Exited"])

    def test_inherited_signal_policy_does_not_discard_exit_identity(self):
        self.ignore_child_exit = True
        process = self.launch([sys.executable, "-c", "raise SystemExit(7)"], 2000, 3000, 100000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["7", "0", "Exited"])

    def test_ignored_termination_signal_does_not_leak_to_command(self):
        self.ignore_termination = True
        process = self.launch(["sleep", "30"], 3000, 3000, 10000)
        self.assertTrue(process.stdout.readline().startswith("START "))
        terminal = self.result(process, "C")
        self.assertEqual(terminal[1:4], ["-1", "15", "Cancelled"])

    def test_inherited_descriptor_is_closed_before_command_exec(self):
        with self.input.open('rb') as sentinel:
            self.inherited_descriptors = (sentinel.fileno(),)
            script = """import os, sys
try:
    os.read(int(sys.argv[1]), 1)
    print('leaked')
except OSError:
    print('closed')
"""
            process = self.launch([sys.executable, "-c", script, str(sentinel.fileno())], 2000, 3000, 10000)
            self.wait_until_exited(process)
            self.assertEqual((self.directory / 'stdout').read_text(), 'closed\n')

    def test_inherited_control_writer_cannot_conceal_owner_exit(self):
        read_end, write_end = os.pipe()
        try:
            self.control_input = read_end
            self.inherited_descriptors = (write_end,)
            process = self.launch(["sleep", "30"], 3000, 2000, 10000)
            self.assertTrue(process.stdout.readline().startswith("START "))
        finally:
            os.close(read_end)
            os.close(write_end)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[3], "OwnerExited")

    def test_nonregular_input_is_rejected_before_launch_without_blocking(self):
        self.input.unlink()
        os.mkfifo(self.input)
        process = self.launch([sys.executable, "-c", "raise SystemExit(0)"], 500, 500, 10000)
        output, errors = process.communicate("", timeout=2)
        self.assertEqual(process.returncode, 2, (output, errors))
        self.assertNotIn("START", output)

    def test_both_pipes_are_drained_without_backpressure_deadlock(self):
        process = self.launch([sys.executable, "-c", "import os; [(os.write(1,b'o'*8192),os.write(2,b'e'*8192)) for _ in range(128)]"], 3000, 4000, 2000000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["0", "0", "Exited"])
        self.assertEqual([len((self.directory / name).read_bytes()) for name in ["stdout", "stderr"]], [1048576, 1048576])

    def test_disk_safety_ceiling_above_any_retention_bound_is_accepted(self):
        process = self.launch([sys.executable, "-c", "import os; os.write(1, b'done')"], 2000, 3000, 1024 * 1024 * 1024)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["0", "0", "Exited"])
        self.assertEqual((self.directory / "stdout").read_bytes(), b"done")

    def test_output_limit_preserves_bounded_prefix(self):
        process = self.launch([sys.executable, "-c", "import os; [os.write(1,b'x'*8192) for _ in range(1000)]"], 2000, 3000, 1024)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[3], "OutputLimit")
        self.assertEqual((self.directory / "stdout").stat().st_size, 1024)

    def test_silent_process_execution_deadline(self):
        process = self.launch([sys.executable, "-c", "import time; time.sleep(30)"], 200, 3000, 10000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[3], "ExecutionDeadline")

    def test_run_ms_zero_is_no_execution_deadline(self):
        process = self.launch([sys.executable, "-c", "import time; time.sleep(1.5); print('done')"], 0, 3000, 10000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[1:4], ["0", "0", "Exited"])
        self.assertEqual((self.directory / "stdout").read_text(), "done\n")

    def test_malformed_execution_deadline_is_rejected_before_launch(self):
        for value in ["-1", "00", "invalid", ""]:
            process = self.launch([sys.executable, "-c", "raise SystemExit(0)"], value, 3000, 10000)
            output, errors = process.communicate("", timeout=2)
            self.assertEqual(process.returncode, 2, (value, output, errors))
            self.assertNotIn("START", output)
            for name in ["stdout", "stderr"]:
                (self.directory / name).unlink(missing_ok=True)

    def test_frozen_owner_heartbeat_deadline(self):
        process = self.launch([sys.executable, "-c", "import time; time.sleep(30)"], 3000, 200, 10000)
        terminal = self.wait_until_exited(process)
        self.assertEqual(terminal[3], "HeartbeatLost")

    def test_owner_pipe_exit(self):
        process = self.launch([sys.executable, "-c", "import time; time.sleep(30)"], 3000, 3000, 10000)
        terminal = self.result(process, "")
        self.assertEqual(terminal[3], "OwnerExited")

    def test_explicit_cancellation(self):
        process = self.launch([sys.executable, "-c", "import time; time.sleep(30)"], 3000, 3000, 10000)
        terminal = self.result(process, "C")
        self.assertEqual(terminal[3], "Cancelled")

    def test_detached_descendant_is_adopted_and_reaped_after_root_exit(self):
        pid_file = self.directory / "detached.pid"
        gate = self.directory / "exit-root"
        script = """import os, signal, sys, time
child = os.fork()
if child == 0:
    os.setsid()
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    with open(sys.argv[1], 'w') as out:
        out.write(str(os.getpid()))
    time.sleep(30)
else:
    while not os.path.exists(sys.argv[2]):
        time.sleep(0.005)
"""
        process = self.launch([sys.executable, "-c", script, str(pid_file), str(gate)], 3000, 3000, 10000)
        retained = None
        try:
            deadline = time.monotonic() + 1
            while not pid_file.exists() and time.monotonic() < deadline:
                time.sleep(0.005)
            self.assertTrue(pid_file.exists())
            retained = os.pidfd_open(int(pid_file.read_text()))
            gate.touch()
            terminal = self.wait_until_exited(process)
            self.assertEqual(terminal[3], "Exited")
            with self.assertRaises(ProcessLookupError):
                signal.pidfd_send_signal(retained, 0)
        finally:
            if retained is not None:
                try:
                    signal.pidfd_send_signal(retained, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                os.close(retained)

    def test_actual_owner_sigkill_closes_control_pipe_and_settles_child(self):
        lifecycle = self.directory / "lifecycle"
        guardian_pid = self.directory / "guardian.pid"
        script = """import subprocess, sys, time
from pathlib import Path
with open(sys.argv[2], 'w') as output, open(sys.argv[2] + '.errors', 'w') as errors:
    child = subprocess.Popen([sys.argv[1], '500', '3000', '3000', '100', '1000', '10000', *sys.argv[4:7], '--', sys.executable, '-c', 'import time; time.sleep(30)'], stdin=subprocess.PIPE, stdout=output, stderr=errors)
    Path(sys.argv[3]).write_text(str(child.pid))
    time.sleep(30)
"""
        owner = subprocess.Popen([sys.executable, "-c", script, os.environ["CQ_GUARDIAN_TEST_BINARY"], str(lifecycle), str(guardian_pid),
                                  str(self.input), str(self.directory / "stdout"), str(self.directory / "stderr")])
        self.children.append(owner)
        retained = None
        try:
            deadline = time.monotonic() + 2
            while (not lifecycle.exists() or "START" not in lifecycle.read_text()) and time.monotonic() < deadline:
                time.sleep(0.005)
            self.assertIn("START", lifecycle.read_text())
            retained = os.pidfd_open(int(guardian_pid.read_text()))
            owner.kill()
            owner.wait(timeout=2)
            deadline = time.monotonic() + 2
            while "EXIT" not in lifecycle.read_text() and time.monotonic() < deadline:
                time.sleep(0.005)
            terminal = lifecycle.read_text().strip().splitlines()[-1].split()
            self.assertEqual(terminal[0], "EXIT")
            self.assertEqual(terminal[3], "OwnerExited")
            self.assertEqual(terminal[-2:], ["1", "0"])
        finally:
            if retained is not None:
                try:
                    signal.pidfd_send_signal(retained, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                os.close(retained)


if __name__ == "__main__":
    unittest.main()
