import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time


CLI_STARTUP_SECONDS = 15
HTTP_DEADLINE_WATCHDOG_SECONDS = 34
CLI_CLEANUP_SECONDS = 3


def main():
    command = sys.argv[1:]
    with tempfile.TemporaryDirectory(prefix="cq-cli-response-") as temporary:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(40)
            release = threading.Event()
            requested = threading.Event()

            def stalled_response():
                with listener.accept()[0] as connection:
                    connection.settimeout(5)
                    request = b""
                    while b"\r\n\r\n" not in request:
                        request += connection.recv(4096)
                    requested.set()
                    connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n")
                    release.wait(40)

            thread = threading.Thread(target=stalled_response, daemon=True)
            thread.start()
            endpoint = f"http://127.0.0.1:{listener.getsockname()[1]}"
            began = time.monotonic()
            process = subprocess.Popen(command + ["init", "--endpoint", endpoint], cwd=temporary,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                assert requested.wait(CLI_STARTUP_SECONDS), "CLI did not issue its HTTP request within the separate startup deadline"
                operation_started = time.monotonic()
                stdout, stderr = process.communicate(timeout=HTTP_DEADLINE_WATCHDOG_SECONDS)
                assert process.returncode == 1 and "deadline exceeded" in stderr, stdout + stderr
                print(json.dumps({"case": "bounded HTTP response", "startupSeconds": operation_started - began,
                                  "httpAndExitSeconds": time.monotonic() - operation_started}), flush=True)
            except subprocess.TimeoutExpired:
                raise AssertionError("CLI response body exceeded its 30-second deadline after HTTP headers") from None
            finally:
                release.set()
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.communicate(timeout=CLI_CLEANUP_SECONDS)
                thread.join(timeout=5)
        saved = json.loads((Path(temporary) / ".cq/project.json").read_text())
        recovered = subprocess.run(command + ["init", "--endpoint", os.environ["CQ_ORIGIN"], "--json"], cwd=temporary,
                                   capture_output=True, text=True, timeout=15)
        assert recovered.returncode == 0, recovered.stdout + recovered.stderr
        assert json.loads(recovered.stdout.splitlines()[0])["Initialized"]["project"]["id"] == saved["project"]
    print("CLI bounded stalled response body and released identity lock with stable initialization identity passed")


if __name__ == "__main__":
    main()
