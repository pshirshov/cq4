"""Native traffic observations and operational meter totals for release evaluations."""
from collections import Counter
from fractions import Fraction
import json


def encoded(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode())


def traffic(events, harness):
    assert harness in ["Claude", "Codex", "Pi"]
    calls, replies = {}, {}

    def call(identity, name, arguments):
        value = {"name": name, "arguments": arguments}
        assert identity not in calls or calls[identity] == value, "Native tool identity changed"
        calls[identity] = value

    def reply(identity, content, failed):
        body = content if isinstance(content, str) else "".join(block["text"] for block in content if block["type"] == "text")
        replies[identity] = {"body": body, "failed": failed}

    for event in events:
        kind = event.get("type")
        if harness == "Claude" and kind in ["assistant", "user"]:
            for block in event["message"]["content"]:
                if block.get("type") == "tool_use":
                    call(block["id"], block["name"].removeprefix("mcp__").replace("__", "."), block["input"])
                elif block.get("type") == "tool_result":
                    reply(block["tool_use_id"], block["content"], block.get("is_error", False))
        elif harness == "Codex" and kind in ["item.started", "item.updated", "item.completed"]:
            item = event["item"]
            if item["type"] == "mcp_tool_call":
                call(item["id"], item["server"] + "." + item["tool"], item["arguments"])
                if kind == "item.completed":
                    if item["result"] is not None:
                        reply(item["id"], item["result"]["content"], item["error"] is not None or item["result"].get("isError", False))
                    else:
                        reply(item["id"], json.dumps(item["error"]), True)
        elif harness == "Pi" and kind == "tool_execution_start":
            name = event["toolName"]
            for prefix in ["cq_host_", "cq_"]:
                if name.startswith(prefix):
                    name = prefix[:-1] + "." + name.removeprefix(prefix)
                    break
            call(event["toolCallId"], name, event["args"])
        elif harness == "Pi" and kind == "tool_execution_end":
            reply(event["toolCallId"], event["result"]["content"], event["isError"])
    dispatch, reads, requests, errors = [], [], [], []
    for identity, value in calls.items():
        observed = replies.get(identity)
        body = None if observed is None else observed["body"]
        try:
            result = json.loads(body) if body is not None else {}
        except ValueError:
            result = {}
        if observed is not None and (observed["failed"] or isinstance(result, dict) and "Failed" in result):
            errors.append(identity)
        args = value["arguments"]
        if value["name"] == "cq.read" and "ArtifactText" in args["selection"]:
            reads.append({"call": identity, "request": args["selection"]["ArtifactText"], "replyBytes": None if body is None else len(body.encode())})
        if value["name"] == "cq_host.dispatch":
            dispatch.append({"call": identity, "operation": next(iter(args)), "argumentBytes": encoded(args), "replyBytes": None if body is None else len(body.encode())})
            for operation in ["Select", "Start", "StartChoice"]:
                if operation in args:
                    identity = args[operation]["choice"] if operation == "StartChoice" else args[operation]["work" if operation == "Start" else "request"]["request"]
                    requests.append(json.dumps([operation, identity], sort_keys=True))
    return {"calls": dict(Counter(value["name"] for value in calls.values())), "observedToolErrors": errors,
            "missingReplies": sorted(calls.keys() - replies.keys()), "orphanReplies": sorted(replies.keys() - calls.keys()),
            "dispatch": dispatch, "artifactDrillDowns": reads, "repeatedDispatchRequestIds": sum(count - 1 for count in Counter(requests).values()),
            "limits": "Counts observed tool calls and repeated explicit request IDs; provider retries and implicit retries are not inferred. Codex counts exposed MCP calls.",
            "payloadBasis": "Compact UTF-8 arguments and native text replies; excludes envelopes and duplicate structured content; not tokenization"}


def meter_totals(entries, attempts, summary):
    dimensions = {"hierarchy": {}, "evaluation": {}, "harness": {}, "role": {}}
    attribution = {key: {} for key in ["direct", "shared", "unattributed"]}
    by_attempt = {view["attempt"]["id"]["value"]: view for view in attempts}
    seen = set()

    def add(target, totals):
        for counter in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]:
            quantities = target.setdefault(counter, {"known": 0, "unknown": 0, "estimated": 0})
            for quantity in quantities:
                quantities[quantity] += int(totals[counter][quantity])

    for entry in entries:
        attempt, assignment = entry["attempt"], entry["assignment"]
        identity = attempt["id"]["value"]
        assert by_attempt[identity]["attempt"] == attempt and by_attempt[identity]["assignment"] == assignment
        key = (identity, entry["meter"]["key"])
        assert key not in seen, "Duplicate operational meter"
        seen.add(key)
        tags = {"hierarchy": "governor" if attempt["parent"] is None else "child",
                "evaluation": "independent-assessor" if assignment["evaluation"] is not None and assignment["evaluation"]["assessor"] else "producer",
                "harness": attempt["harness"], "role": attempt["role"]}
        totals = entry["projection"]["totals"]
        for dimension, tag in tags.items():
            add(dimensions[dimension].setdefault(tag, {}), totals)
        add(attribution[assignment["attribution"].lower()], totals)
    for scope, totals in attribution.items():
        for counter in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]:
            for quantity in ["known", "unknown", "estimated"]:
                observed = totals[counter][quantity] if totals else 0
                assert observed == int(summary[scope][counter][quantity]), "Meter export disagrees with operational summary"
    cache = {}
    for name in ["cacheRead", "cacheWrite"]:
        numerator = sum(int(entry["projection"]["totals"][name]["known"]) for entry in entries)
        denominator = sum(int(entry["projection"]["totals"]["input"]["known"]) for entry in entries)
        complete = all(int(entry["projection"]["totals"][counter][quantity]) == 0 for entry in entries
                       for counter in [name, "input"] for quantity in ["unknown", "estimated"])
        cache[name] = {"knownFraction": str(Fraction(numerator, denominator)) if denominator else None,
                       "allInputsObserved": complete, "basis": "Known cache subset / known inclusive input; incomplete coverage is a partial ratio"}
    return {"dimensions": dimensions, "cache": cache, "attemptsWithoutExportedMeters": sorted(by_attempt.keys() - {identity for identity, _ in seen})}
