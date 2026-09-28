import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid


def main():
    command = sys.argv[1:]
    assert command, "Launcher command is required"
    environment = {name: value for name, value in os.environ.items() if not name.startswith("CQ_")}
    with tempfile.TemporaryDirectory(prefix="cq-role-client-") as temporary:
        root = Path(temporary)
        config = root / ".cq"
        config.mkdir()
        endpoint = "http://127.0.0.1:1"
        (config / "project.json").write_text(json.dumps({"project": {"value": str(uuid.uuid4())}, "endpoint": endpoint, "name": "offline role fixture"}))

        def run(arguments, expected):
            result = subprocess.run(command + arguments, cwd=root, env=environment, capture_output=True, text=True, timeout=15)
            assert result.returncode == expected, result.stdout + result.stderr
            return result

        assert run(["web"], 0).stdout.strip() == endpoint
        assert run([":client", "web"], 0).stdout.strip() == endpoint
        assert run([":client", "--", "web"], 0).stdout.strip() == endpoint
        assert run(["--log-level-root", "error", ":client", "web"], 0).stdout.strip() == endpoint
        assert "Usage: cq" in run(["--help"], 0).stdout
        for harness in ["claude", "codex", "pi"]:
            exported = run(["commands", "export", harness, "--directory", str(root)], 0).stdout.splitlines()
            assert len(exported) == 4 and all(Path(path).is_file() for path in exported)
            assert run(["commands", "export", harness, "--directory", str(root)], 0).stdout.splitlines() == exported
        help_text = run([":help"], 0).stdout
        assert "client" in help_text and "server" in help_text and "configwriter" in help_text
        rejected = run([":client", "unsupported-command"], 1)
        assert rejected.stdout == "" and "Unknown command" in rejected.stderr
    print("Single role entrypoint: shorthand/native client commands, bootstrap help and error diagnostics pass without CQ server/database/credential configuration")


if __name__ == "__main__":
    main()
