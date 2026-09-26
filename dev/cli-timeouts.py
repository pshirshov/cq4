import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import threading


def main():
    command = sys.argv[1:]
    with tempfile.TemporaryDirectory(prefix="cq-cli-response-") as temporary:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(40)
            release = threading.Event()

            def stalled_response():
                with listener.accept()[0] as connection:
                    connection.settimeout(5)
                    request = b""
                    while b"\r\n\r\n" not in request:
                        request += connection.recv(4096)
                    connection.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n")
                    release.wait(40)

            thread = threading.Thread(target=stalled_response, daemon=True)
            thread.start()
            endpoint = f"http://127.0.0.1:{listener.getsockname()[1]}"
            process = subprocess.Popen(command + ["init", "--endpoint", endpoint], cwd=temporary,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                stdout, stderr = process.communicate(timeout=34)
                assert process.returncode == 1 and "deadline exceeded" in stderr, stdout + stderr
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise AssertionError("CLI response body exceeded its 30-second deadline after HTTP headers") from None
            finally:
                release.set()
                thread.join(timeout=5)
        saved = json.loads((Path(temporary) / ".cq/project.json").read_text())
        recovered = subprocess.run(command + ["init", "--endpoint", os.environ["CQ_ORIGIN"]], cwd=temporary,
                                   capture_output=True, text=True, timeout=15)
        assert recovered.returncode == 0, recovered.stdout + recovered.stderr
        assert json.loads(recovered.stdout.splitlines()[0])["Initialized"]["project"]["id"] == saved["project"]
    print("CLI bounded stalled response body and released identity lock with stable initialization identity passed")


if __name__ == "__main__":
    main()
