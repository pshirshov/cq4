"""Behavioral Active Effectual / local processes: installed lifecycle regression."""
import json
import os
from pathlib import Path
import runpy
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parent.parent


class InstalledProcessTests(unittest.TestCase):
    def test_stopped_wrapper_is_reaped_after_reported_cleanup_timeout(self):
        process_type = runpy.run_path(str(ROOT / "dev/package-check"))["WrappedProcess"]
        with tempfile.TemporaryDirectory(prefix="cq-wrapper-timeout-") as temporary:
            directory = Path(temporary)
            evidence = directory / "process.json"
            command = [shutil.which("bwrap"), "--die-with-parent", "--bind", "/", "/", "--dev-bind", "/dev", "/dev",
                       sys.executable, "-c", "import time; time.sleep(60)"]
            with (directory / "output.log").open("w") as log:
                process = process_type(command, dict(os.environ), log, evidence, 5, 1)
                try:
                    os.kill(process.wrapper.pid, signal.SIGSTOP)
                    with self.assertRaises(subprocess.TimeoutExpired):
                        process.close()
                    self.assertEqual(process.wrapper.poll(), -signal.SIGKILL)
                    result = json.loads(evidence.read_text())
                    self.assertTrue(result["childExited"])
                    self.assertTrue(result["cleanupTimeout"])
                    self.assertEqual(result["wrapperExit"], -signal.SIGKILL)
                finally:
                    if process.wrapper.poll() is None:
                        process.wrapper.kill()
                        process.wrapper.wait(timeout=5)

    def test_signals_reach_application_and_wait_settles_both_processes(self):
        process_type = runpy.run_path(str(ROOT / "dev/package-check"))["WrappedProcess"]
        wrapper = shutil.which("bwrap")
        self.assertIsNotNone(wrapper)
        for action, expected, handled in [("terminate", 0, True), ("kill", -9, False), ("exit137", 137, False)]:
            with self.subTest(action=action), tempfile.TemporaryDirectory(prefix="cq-wrapper-check-") as temporary:
                directory = Path(temporary)
                ready, stopped, finish = [directory / name for name in ["ready", "stopped", "finish"]]
                script = ("import pathlib,signal,sys,time\n"
                          f"def stop(*args):\n pathlib.Path({str(stopped)!r}).write_text('handled'); sys.exit(0)\n"
                          "signal.signal(signal.SIGTERM, stop)\n"
                          f"pathlib.Path({str(ready)!r}).write_text('ready')\n"
                          f"while not pathlib.Path({str(finish)!r}).exists(): time.sleep(0.01)\n"
                          "sys.exit(137)\n")
                command = [wrapper, "--die-with-parent", "--bind", "/", "/", "--dev-bind", "/dev", "/dev", sys.executable, "-c", script]
                evidence = directory / "process.json"
                with (directory / "output.log").open("w") as log:
                    process = process_type(command, dict(os.environ), log, evidence, 5, 5)
                    try:
                        deadline = time.monotonic() + 5
                        while not ready.exists():
                            self.assertIsNone(process.poll())
                            self.assertLess(time.monotonic(), deadline)
                            time.sleep(0.01)
                        if action == "exit137":
                            finish.touch()
                        else:
                            getattr(process, action)()
                        self.assertEqual(process.wait(5), expected)
                        self.assertEqual(process.poll(), expected)
                        self.assertTrue(process.child_exited())
                        self.assertEqual(stopped.exists(), handled)
                    finally:
                        process.close()
                result = json.loads(evidence.read_text())
                self.assertEqual(result["wrapperExit"], 0 if handled else 137)
                self.assertTrue(result["childExited"])


if __name__ == "__main__":
    unittest.main()
