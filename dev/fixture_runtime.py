import os
from pathlib import Path
import subprocess


def guardian_binary(directory: Path, provided: str | None) -> Path:
    if provided is not None:
        binary = Path(provided)
        assert binary.is_absolute() and binary.is_file() and os.access(binary, os.X_OK), "Explicit fixture guardian must be an absolute executable"
        return binary
    binary = directory / "cq-guardian"
    source = Path(__file__).resolve().parent.parent / "host/native/guardian.c"
    subprocess.run(["gcc", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-o", str(binary), str(source)], check=True, timeout=60)
    return binary
