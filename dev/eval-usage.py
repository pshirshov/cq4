#!/usr/bin/env python3
"""Usage figures for a driven evaluation run; see docs/evaluation-protocol.md.

child         Usage summary and phase report of one evaluation {run, scenario}, read from the CQ server.
claude-outer  Token counts of a governing Claude Code session, summed from its local transcript.
"""
import argparse
import json
from pathlib import Path
import subprocess
import urllib.request
import uuid

PROTOCOL_VERSION = "0.1.0"
RESPONSE_BYTES = 2 * 1024 * 1024
TIMEOUT_SECONDS = 15
TRANSCRIPT_COUNTERS = {
    "input": "input_tokens",
    "cacheRead": "cache_read_input_tokens",
    "cacheWrite": "cache_creation_input_tokens",
    "output": "output_tokens",
}


def child(arguments):
    common = subprocess.check_output(["git", "-C", str(arguments.project_dir), "rev-parse", "--path-format=absolute", "--git-common-dir"], text=True).strip()
    config = json.loads((Path(common) / "cq/project.json").read_text())
    token = arguments.token_file.read_text().strip()
    session = str(uuid.uuid4())
    usage_filter = {"EvaluationOnly": {"run": arguments.run, "scenario": arguments.scenario}}

    def call(selection):
        body = {"Usage": {"input": {"project": config["project"], "selection": selection}}}
        request = urllib.request.Request(config["endpoint"] + "/api/call", data=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + token, "CQ-Session": session,
            "CQ-Protocol-Version": PROTOCOL_VERSION, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = response.read(RESPONSE_BYTES + 1)
        if len(payload) > RESPONSE_BYTES:
            raise SystemExit("Usage response exceeded its byte bound")
        result = json.loads(payload)
        if "Failed" in result:
            raise SystemExit(json.dumps(result))
        return result

    return {"filter": usage_filter,
            "summary": call({"Summary": {"filter": usage_filter}})["UsageSummary"]["report"],
            "phases": call({"Phases": {"filter": usage_filter}})["UsagePhases"]["report"]}


def claude_outer(arguments):
    # Claude Code writes one transcript entry per content block of a response, each with the response's usage.
    responses = {}
    unreadable = 0
    with arguments.transcript.open(encoding="utf-8", errors="replace") as lines:
        for line in lines:
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                unreadable += 1
                continue
            message = entry.get("message")
            if entry.get("type") == "assistant" and isinstance(message, dict) and isinstance(message.get("usage"), dict):
                responses[message["id"]] = (message["usage"], bool(entry.get("isSidechain")))
    totals = {name: sum(usage.get(field) or 0 for usage, _ in responses.values()) for name, field in TRANSCRIPT_COUNTERS.items()}
    requests = [sum(usage.get(field) or 0 for name, field in TRANSCRIPT_COUNTERS.items() if name != "output") for usage, _ in responses.values()]
    return {"source": str(arguments.transcript), "basis": "local transcript; tokens only",
            "responses": len(responses), "sidechainResponses": sum(1 for _, side in responses.values() if side),
            "unreadableLines": unreadable, "tokens": totals,
            "largestRequestInput": max(requests) if requests else None, "cost": "unknown"}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    modes = parser.add_subparsers(dest="mode", required=True)
    child_parser = modes.add_parser("child")
    child_parser.add_argument("--project-dir", type=Path, required=True, help="Consumer checkout initialised with cq init")
    child_parser.add_argument("--token-file", type=Path, required=True, help="Operator token file of the evaluation server")
    child_parser.add_argument("--run", required=True)
    child_parser.add_argument("--scenario", required=True)
    child_parser.set_defaults(action=child)
    outer_parser = modes.add_parser("claude-outer")
    outer_parser.add_argument("--transcript", type=Path, required=True, help="~/.claude/projects/<project>/<session>.jsonl")
    outer_parser.set_defaults(action=claude_outer)
    arguments = parser.parse_args()
    print(json.dumps(arguments.action(arguments), indent=2))


if __name__ == "__main__":
    main()
