"""Native command observations bound to their Collector transcript and fixture."""
import base64
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import uuid


def command(recorder):
    bootstrap = ("import hashlib,pathlib; p=pathlib.Path(" + repr(recorder["path"]) + ").read_bytes(); "
        "assert hashlib.sha256(p).hexdigest()==" + repr(recorder["sha256"]) + "; exec(compile(p," + repr(recorder["path"]) + ",'exec'))")
    return ["python3", "-c", bootstrap]


def native_id(attempt, name):
    return {"value": str(uuid.UUID(bytes=hashlib.md5((attempt["value"] + ":" + name).encode()).digest(), version=3))}


def export(call, project, session, attempt):
    artifacts = {}
    for name in ["stdout", "stderr"]:
        pending = [native_id(attempt, name)]
        while pending:
            handle = pending.pop()
            if handle["value"] in artifacts:
                continue
            def read(selection):
                return call({"Read": {"input": {"project": project, "selection": selection}}})
            metadata = read({"ArtifactInfo": {"id": handle}})["ArtifactInfo"]["metadata"]
            assert metadata["id"] == handle and metadata["attempt"] == attempt and metadata["kind"] == "Transcript" and metadata["bytes"] <= 256 * 1024
            parts, offset = [], 0
            for _ in range(33):
                page = read({"ArtifactText": {"id": handle, "offset": offset, "limit": 8192}})["ArtifactText"]["page"]
                assert page["metadata"] == metadata and page["offset"] == offset and page["next"] == offset + len(page["text"])
                parts.append(page["text"])
                if not page["hasMore"]:
                    break
                assert page["next"] > offset
                offset = page["next"]
            else:
                raise AssertionError("Native artifact page bound exceeded")
            text = "".join(parts)
            assert len(text) == metadata["codePoints"] and len(text.encode()) == metadata["bytes"] and hashlib.sha256(text.encode()).hexdigest() == metadata["sha256"]
            artifacts[handle["value"]] = {"metadata": metadata, "body": text}
            if metadata["mediaType"] == "application/json":
                pending.extend(json.loads(text)["parts"])
        assert decoded(artifacts, attempt, name, session.name) == (session / "payload" / attempt["value"] / name).read_bytes()
    return artifacts


def decoded(artifacts, attempt, name, session):
    manifest_id = native_id(attempt, name)
    manifest = artifacts[manifest_id["value"]]
    assert manifest["metadata"]["id"] == manifest_id and manifest["metadata"]["mediaType"] == "application/json"
    value = json.loads(manifest["body"])
    assert value["encoding"] == "base64" and 0 <= int(value["bytes"]) <= 32 * 1024 * 1024
    assert value["mediaType"] == ("application/x-ndjson" if name == "stdout" else "application/octet-stream")
    assert len(value["parts"]) <= 256
    content = []
    for index, handle in enumerate(value["parts"]):
        assert handle == native_id(attempt, f"{name}-part-{index}")
        part = artifacts[handle["value"]]
        assert part["metadata"]["id"] == handle and part["metadata"]["mediaType"] == "text/plain"
        content.append(base64.b64decode(part["body"], validate=True))
    for artifact in [manifest, *(artifacts[handle["value"]] for handle in value["parts"])]:
        metadata = artifact["metadata"]
        assert metadata["kind"] == "Transcript" and metadata["attempt"] == attempt
        assert metadata["actor"]["role"] == "Collector" and metadata["actor"]["session"] == {"value": session}
        assert metadata["sha256"] == hashlib.sha256(artifact["body"].encode()).hexdigest()
        assert metadata["bytes"] == len(artifact["body"].encode()) and metadata["codePoints"] == len(artifact["body"])
    raw = b"".join(content)
    assert len(raw) == int(value["bytes"]) and hashlib.sha256(raw).hexdigest() == value["sha256"]
    return raw


def observations(events, recorder, fixture, workspace):
    executed = []
    for event in events:
        item = event.get("item", {})
        if event.get("type") != "item.completed" or item.get("type") != "command_execution":
            continue
        argv = shlex.split(item["command"])
        if len(argv) == 3 and argv[1] in ["-c", "-lc"] and Path(argv[0]).name in ["bash", "zsh", "sh"]:
            argv = shlex.split(argv[2])
        if argv != command(recorder):
            continue
        assert item["status"] == "completed" and item["exit_code"] == 0, "Observation recorder did not complete successfully"
        output = json.loads(item["aggregated_output"])
        assert output["cwd"] == str(workspace.resolve()), "Probe ran outside its assigned workspace"
        assert output["before"] == output["after"] == fixture, "Probe changed or inspected another fixture"
        diagnostics = output["gitDiagnostics"]
        assert [value["argv"] for value in diagnostics] == [["git", "rev-parse", "HEAD"], ["git", "ls-files", "-z"]] * 2
        assert all(value["exit"] == 0 and isinstance(value["stdout"], str) and isinstance(value["stderr"], str) for value in diagnostics)
        names = [value["name"] for value in output["observations"]]
        assert names == ["reproduction", "selection", "normalization-aggregation", "nonconforming-injection", "cli"]
        assert all(isinstance(value["exit"], int) and isinstance(value["stdout"], str) and isinstance(value["stderr"], str) and
                   isinstance(value["stdin"], str) and value["argv"] for value in output["observations"])
        executed.append({"commandId": item["id"], "command": item["command"], "observations": output})
    assert len(executed) == 1, "Missing or repeated exact native observation program"
    return executed[0]


def fixture(repository, base):
    names = subprocess.check_output(["git", "-C", str(repository), "ls-tree", "-r", "--name-only", "-z", base["value"]], text=True).split("\0")
    return {"head": base["value"], "files": {name: hashlib.sha256(subprocess.check_output(
        ["git", "-C", str(repository), "show", base["value"] + ":" + name])).hexdigest() for name in names if name}}
