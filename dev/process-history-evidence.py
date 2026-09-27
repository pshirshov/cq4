"""Exact history references and observed native reviewer read coverage."""
import hashlib
import json


def identity(item):
    return json.dumps(item, sort_keys=True, separators=(",", ":"))


def references(histories):
    result = []
    for history in histories:
        entries = history["page"]["entries"]
        assert entries and not history["page"]["hasMore"]
        revisions = [int(entry["item"]["item"]["revision"]["value"]) for entry in entries]
        assert revisions == list(range(revisions[0], 0, -1))
        assert all(entry["item"]["item"]["id"] == history["id"] for entry in entries)
        canonical = json.dumps(entries, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
        result.append({"id": history["id"], "latest": entries[0]["item"]["item"]["revision"], "count": len(entries),
                       "sha256": hashlib.sha256(canonical.encode("utf-8")).hexdigest()})
    assert len({identity(ref["id"]) for ref in result}) == len(result)
    return result


def inspected(events, histories):
    expected = {identity(value["id"]): value["page"]["entries"] for value in histories}
    refs = references(histories)
    observed = {key: set() for key in expected}
    calls = []
    for event in events:
        item = event.get("item", {})
        if (event.get("type") != "item.completed" or item.get("type") != "mcp_tool_call" or
                item.get("server") != "cq" or item.get("tool") != "read" or item["status"] != "completed" or item["error"] is not None):
            continue
        args = item["arguments"]
        if "History" not in args["selection"]:
            continue
        request = args["selection"]["History"]
        key = identity(request["id"])
        if key not in expected:
            continue
        assert args["project"] == request["id"]["project"]
        reply = item["result"]["structured_content"]
        if "Failed" in reply:
            continue
        page = reply["History"]["page"]
        available = [entry for entry in expected[key] if int(entry["item"]["item"]["revision"]["value"]) < int(request["before"]["value"])]
        entries = page["entries"]
        assert len(entries) <= request["limit"] and entries == available[:len(entries)], "Native history differs from the frozen revisions, references or provenance"
        assert page["hasMore"] == (len(entries) < len(available)), "Native history has a false completion marker"
        observed[key].update(entry["item"]["item"]["revision"]["value"] for entry in entries)
        calls.append({"call": item["id"], "id": request["id"], "before": request["before"], "revisions": [entry["item"]["item"]["revision"] for entry in entries]})
    missing = [ref["id"] for ref in refs if len(observed[identity(ref["id"])]) != ref["count"]]
    return {"complete": not missing, "missing": missing, "references": refs, "calls": calls}
