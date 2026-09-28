"""Host-controlled consumer acceptance checks, separate from model-written tests."""
from collections import Counter
import json
from pathlib import Path
import random
import re
import resource
import subprocess
import sys
import tempfile


MAX_OUTPUT = 65536
MAX_FILE = 64 * 1024 * 1024


def limits():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_FILE, MAX_FILE))


def execute(command, source, timeout):
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        result = subprocess.run(command, input=source.encode(), stdout=out, stderr=err, timeout=timeout, preexec_fn=limits)
        out.seek(0)
        err.seek(0)
        stdout, stderr = out.read(MAX_OUTPUT + 1), err.read(MAX_OUTPUT + 1)
        assert len(stdout) <= MAX_OUTPUT and len(stderr) <= MAX_OUTPUT, "Consumer command exceeded its output bound"
        return result.returncode, stdout.decode(), stderr.decode()


def expected(source, top):
    counts = Counter(value.lower() for value in re.findall(r"[A-Za-z]+", source))
    entries = sorted(counts.items(), key=lambda value: (-value[1], value[0]))
    return "".join(f"{word}\t{count}\n" for word, count in entries[:top])


def main():
    assert len(sys.argv) == 2 and sys.argv[1] in ["python", "go"], "Expected consumer language"
    language = sys.argv[1]
    with tempfile.TemporaryDirectory(prefix="cq-consumer-oracle-") as temporary:
        if language == "python":
            code, out, err = execute([sys.executable, "-m", "unittest", "discover", "-v"], "", 30)
            assert code == 0 and re.search(r"Ran [1-9][0-9]* tests?", out + err), ("consumer tests absent or failed", code, out, err)
            command = [sys.executable, "-m", "wordfreq"]
        else:
            code, out, err = execute(["go", "test", "-json", "./..."], "", 60)
            assert code == 0 and any(event.get("Action") == "pass" and "Test" in event for event in map(json.loads, out.splitlines())), ("consumer tests absent or failed", code, out, err)
            executable = str(Path(temporary) / "wordfreq")
            code, out, err = execute(["go", "build", "-o", executable, "."], "", 60)
            assert code == 0, ("consumer build failed", code, out, err)
            command = [executable]
        sources = ["", "123 -- 🙂", "Hello HELLO world", "b a C b c A", "can't snake_case C++ abc123DEF", "éclair naïve ß AΩB", "z\nx\tz\rx", "a " * 5000]
        generator = random.Random(90421)
        words = ["Alpha", "beta", "GAMMA", "a", "z", "Z", "Beta", "delta"]
        sources += [" ".join(generator.choices(words, k=generator.randrange(0, 120))) for _ in range(16)]
        count = 0
        for source in sources:
            for options, top in [([], None), (["--top", "1"], 1), (["--top", "3"], 3), (["--top", "1000"], 1000)]:
                code, out, err = execute(command + options, source, 5)
                assert (code, out, err) == (0, expected(source, top), ""), (options, source[:100], code, out, err)
                count += 1
        for options in [["--top"], ["--top", "0"], ["--top", "-1"], ["--top", "1001"], ["--top", "oops"],
                        ["--top", "9" * 5000], ["--unknown"], ["--t", "1"], ["--to", "1"], ["--h"], ["--he"], ["--hel"], ["extra"]]:
            for arguments in [options, ["--help", *options], [*options, "--help"]]:
                code, out, err = execute(command + arguments, "a", 5)
                assert code == 2 and out == "" and err.strip(), (arguments, code, out, err)
                count += 1
        code, out, err = execute(command + ["--help"], "", 5)
        assert code == 0 and "usage" in out.lower() and err == "", ("help", code, out, err)
        print(json.dumps({"language": language, "behaviorCases": count + 1, "consumerTests": "passed", "status": "passed"}))


if __name__ == "__main__":
    main()
