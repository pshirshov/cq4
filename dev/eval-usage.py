#!/usr/bin/env python3
"""Usage figures for a driven evaluation run; see docs/evaluation-protocol.md.

child         Usage summary and phase report of one evaluation {run, scenario}, read from the CQ server.
claude-outer  Token counts of a governing Claude Code session, summed from its local transcript.
"""
import argparse
from dataclasses import asdict, dataclass
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


@dataclass(frozen=True)
class CounterCoverage:
    observedResponses: int
    missingResponses: int
    partialSubtotal: int | None


@dataclass(frozen=True)
class CounterMeasurement:
    total: int | None
    coverage: CounterCoverage


@dataclass(frozen=True)
class RequestInputCoverage:
    completeRequests: int
    incompleteRequests: int
    largestCompleteRequestInput: int | None


def measure_counter(usages: list[dict[str, int | None]], field: str) -> CounterMeasurement:
    observed = [usage[field] for usage in usages if usage.get(field) is not None]
    missing = len(usages) - len(observed)
    subtotal = sum(observed) if observed else None
    return CounterMeasurement(
        total=subtotal if missing == 0 else None,
        coverage=CounterCoverage(len(observed), missing, subtotal if missing else None))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def child(arguments):
    common = subprocess.check_output(["git", "-C", str(arguments.project_dir), "rev-parse", "--path-format=absolute", "--git-common-dir"], text=True).strip()
    config = json.loads((Path(common) / "cq/project.json").read_text())
    token = arguments.token_file.read_text().strip()
    session = str(uuid.uuid4())
    usage_filter = {"EvaluationOnly": {"run": arguments.run, "scenario": arguments.scenario}}
    # The operator token goes to the endpoint the project file names and nowhere else: no proxy, and a redirect is an HTTP error.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)

    def call(selection):
        body = {"Usage": {"input": {"project": config["project"], "selection": selection}}}
        request = urllib.request.Request(config["endpoint"] + "/api/call", data=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + token, "CQ-Session": session,
            "CQ-Protocol-Version": PROTOCOL_VERSION, "Content-Type": "application/json"})
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
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
    unidentified = 0
    with arguments.transcript.open(encoding="utf-8", errors="replace") as lines:
        for line in lines:
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                unreadable += 1
                continue
            message = entry.get("message")
            if entry.get("type") == "assistant" and isinstance(message, dict) and isinstance(message.get("usage"), dict):
                # Entries are merged by response identity; one without it cannot be counted once and is reported instead.
                if not isinstance(message.get("id"), str):
                    unidentified += 1
                    continue
                responses[message["id"]] = (message["usage"], bool(entry.get("isSidechain")))
    usages = [usage for usage, _ in responses.values()]
    counters = {name: measure_counter(usages, field) for name, field in TRANSCRIPT_COUNTERS.items()}
    input_fields = tuple(field for name, field in TRANSCRIPT_COUNTERS.items() if name != "output")
    # Only requests with all three input constituents have a known size.
    requests = [sum(usage[field] for field in input_fields) for usage in usages
                if all(usage.get(field) is not None for field in input_fields)]
    request_coverage = RequestInputCoverage(
        len(requests), len(usages) - len(requests), max(requests) if requests else None)
    return {"source": str(arguments.transcript), "basis": "local transcript; tokens only",
            "responses": len(responses), "sidechainResponses": sum(1 for _, side in responses.values() if side),
            "unreadableLines": unreadable, "entriesWithoutResponseId": unidentified,
            "tokens": {name: counter.total for name, counter in counters.items()},
            "tokenCoverage": {name: asdict(counter.coverage) for name, counter in counters.items()},
            "largestRequestInput": request_coverage.largestCompleteRequestInput if request_coverage.incompleteRequests == 0 else None,
            "requestInputCoverage": asdict(request_coverage), "cost": "unknown"}


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
