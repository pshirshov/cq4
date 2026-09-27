import collections
import json
import random
import re
import subprocess
import sys

cases = ["", "a_b 12 café", "Z z A a", "ÄAlpha ΩBeta 中Gamma", "İIıiſSKK", "o'clock x-y Foo123BAR"]
rng = random.Random(1729)
alphabet = "aAzZ09_ -'é中ΩİſK\n"
cases.extend("".join(rng.choice(alphabet) for _ in range(80)) for _ in range(20))
for index, text in enumerate(cases):
    expected = "".join(f"{word}\t{count}\n" for word, count in sorted(collections.Counter(word.lower() for word in re.findall(r"[A-Za-z]+", text)).items()))
    result = subprocess.run([sys.executable, "count_words.py"], input=text, text=True, capture_output=True, timeout=10)
    assert (result.returncode, result.stdout, result.stderr) == (0, expected, ""), {"case": index, "input": text, "expected": expected, "observed": result.stdout, "exit": result.returncode, "stderr": result.stderr}
result = subprocess.run([sys.executable, "-m", "unittest", "-v"], text=True, capture_output=True, timeout=30)
assert result.returncode == 0, result.stdout + result.stderr
print(json.dumps({"status": "passed", "cases": len(cases), "consumerTests": "passed"}))
