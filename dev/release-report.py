"""Release observations derived from retained CQ operational session summaries."""
from collections import defaultdict
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import runpy


def read(path):
    return json.loads(path.read_text())


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def lineage_roots(directory):
    found, active = {}, set()

    def visit(path):
        path = path.resolve(strict=True)
        assert path not in active, "Cyclic evidence lineage"
        if path in found:
            return
        active.add(path)
        manifest = read(path / "result.json")
        for field in ["baselineEvidence", "restoredEvidence"]:
            if manifest.get(field) is not None:
                visit(Path(manifest[field]))
        active.remove(path)
        found[path] = digest(path / "result.json")

    visit(directory)
    return found


def session_reports(suite, prefix):
    found, gaps = {}, []
    for attempt in suite["attempts"]:
        if not attempt["stage"].startswith(prefix):
            continue
        if "evidence" not in attempt:
            gaps.append({"stage": attempt["stage"], "invocation": attempt["id"], "reason": "No retained evidence directory"})
            continue
        directory = Path(attempt["evidence"])
        if not (directory / "result.json").is_file():
            gaps.append({"stage": attempt["stage"], "invocation": attempt["id"], "reason": "No retained result manifest; spending is unknown"})
            continue
        for root in lineage_roots(directory):
            paths = [root] if (root / "usage-summary.json").is_file() else []
            paths += [path for path in sorted(root.iterdir()) if path.is_dir() and (path / "usage-summary.json").is_file()]
            if not paths:
                gaps.append({"directory": str(root), "reason": "No operational usage export; spending is unknown"})
            for path in paths:
                found[str(path.resolve())] = path
    return list(found.values()), gaps


def experimental_usage(suite, prefix):
    directories, gaps = session_reports(suite, prefix)
    seen, entries, known = set(), [], 0
    for directory in directories:
        manifest = read(directory / "result.json")
        usage = read(directory / "usage-summary.json")["UsageSummary"]["report"]
        page = read(directory / "attempts.json")["UsageAttempts"]["page"]
        assert not page["hasMore"], "Truncated attempt export"
        ids = sorted(value["attempt"]["id"]["value"] for value in page["entries"])
        assert len(ids) == len(set(ids)) and not set(ids) & seen, "Session summaries count an attempt twice"
        seen.update(ids)
        tokens = sum(int(usage[scope]["total"]["known"]) for scope in ["direct", "shared", "unattributed"])
        known += tokens
        entries.append({"directory": str(directory), "status": manifest["status"], "attempts": ids, "knownTokens": tokens,
                        "incompleteMeters": usage["incompleteMeters"], "attemptsWithoutMeters": usage["attemptsWithoutMeters"],
                        "manifestSha256": digest(directory / "result.json"), "usageSha256": digest(directory / "usage-summary.json")})
    return {"runs": entries, "distinctAttempts": len(seen), "knownTokens": known, "gaps": gaps,
            "provenance": "Original CQ SessionSummary records, including rejected attempts; cumulative restored summaries excluded"}


def report(directory):
    suite = read(directory / "suite.json")
    experiments = experimental_usage(suite, "")
    counters = {scope: {counter: {quantity: 0 for quantity in ["known", "unknown", "estimated"]}
                       for counter in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]}
                for scope in ["direct", "shared", "unattributed"]}
    costs = defaultdict(Fraction)
    unknown_costs, partial, absent = 0, 0, 0
    gaps = list(experiments["gaps"])
    consumed, sessions = {}, []
    metrics = runpy.run_path(str(Path(__file__).with_name("release-metrics.py")))
    for entry in experiments["runs"]:
        path = Path(entry["directory"])
        usage = read(path / "usage-summary.json")["UsageSummary"]["report"]
        for scope, fields in counters.items():
            for counter, quantities in fields.items():
                for quantity in quantities:
                    quantities[quantity] += int(usage[scope][counter][quantity])
            unknown_costs += int(usage[scope]["unknownCosts"])
        partial += int(usage["incompleteMeters"])
        absent += int(usage["attemptsWithoutMeters"])
        assert not usage["costs"]["hasMore"], "Truncated cost groups"
        for value in usage["costs"]["entries"]:
            costs[json.dumps(value["group"], sort_keys=True)] += Fraction(value["amount"]["value"])
        if int(usage["attemptsWithoutMeters"]) or any(int(usage[scope][counter][quantity])
                for scope in counters for counter in ["input", "output"] for quantity in ["unknown", "estimated"]):
            gaps.append({"directory": str(path), "reason": "Required observed input/output instrumentation incomplete"})
        audits = sorted(path.glob("usage-audit-*.json"))
        assert audits, "Operational audit export missing"
        for source in [path / "usage-summary.json", path / "attempts.json", path / "result.json", *audits]:
            consumed[str(source)] = digest(source)
        session, = (path / "sessions").iterdir()
        run = read(session / "run.json")
        transcript = session / "payload" / run["attempt"]["id"]["value"] / "stdout"
        consumed[str(transcript)] = digest(transcript)
        meters = path / "usage-meters.json"
        breakdown = None
        if meters.is_file():
            consumed[str(meters)] = digest(meters)
            breakdown = metrics["meter_totals"](read(meters)["entries"], read(path / "attempts.json")["UsageAttempts"]["page"]["entries"], usage)
        else:
            gaps.append({"directory": str(path), "reason": "No operational meter export for parent/child/assessor accounting"})
        try:
            parent_traffic = {"coverage": "observed", **metrics["traffic"]([json.loads(line) for line in transcript.read_text().splitlines() if line.strip()], run["attempt"]["harness"])}
        except (ValueError, KeyError, TypeError, AssertionError) as error:
            if entry["status"] not in ["failed", "assessment-not-accepted", "assess-not-accepted"]:
                raise
            parent_traffic = {"coverage": "unavailable", "reason": type(error).__name__ + ": " + str(error)}
            gaps.append({"directory": str(path), "reason": "Failed-attempt traffic cannot be completely decoded; accounting retained", "error": parent_traffic["reason"]})
        sessions.append({"directory": str(path), "governorHarness": run["attempt"]["harness"],
                         "governorTranscriptBytes": transcript.stat().st_size,
                         "operationalBreakdown": breakdown, "parentTraffic": parent_traffic,
                         "knownTokens": entry["knownTokens"], "status": entry["status"]})
    chosen = {attempt["stage"]: attempt for attempt in suite["attempts"] if suite["selected"].get(attempt["stage"]) == attempt["id"]}
    routes = set()
    for name in ["cohort-claude", "cohort-codex", "cohort-pi"]:
        if name in chosen:
            observed = chosen[name]["proof"]["routes"]
            routes.update((observed["Governor"], observed[role]) for role in ["Planner", "Worker", "Reviewer"])
    expected = {(parent, child) for parent in ["Claude", "Codex", "Pi"] for child in ["Claude", "Codex", "Pi"]}
    accepted = [name for name in ["cohort-claude-assess", "cohort-codex-assess", "cohort-pi-assess", "process-assess", "defect-assess"] if name in chosen]
    complete = suite["status"] == "corpus-passed" and routes == expected and not gaps
    value = {"status": "release-corpus-observed" if complete else "incomplete", "suiteSha256": digest(directory / "suite.json"),
             "scope": "Packaged observations across different tasks/routes; no matched savings or billing conclusion",
             "experiments": experiments, "countersByAttribution": counters,
             "partialCosts": [{"group": json.loads(key), "exactRationalAmount": str(amount)} for key, amount in sorted(costs.items())],
             "unknownCosts": unknown_costs, "incompleteMeters": partial, "attemptsWithoutMeters": absent,
             "routes": sorted(routes), "missingRoutes": sorted(expected - routes), "acceptedTracks": accepted,
             "allKnownTokensPerAcceptedTrack": str(Fraction(experiments["knownTokens"], len(accepted))) if accepted else None,
             "instrumentationGaps": gaps, "sessions": sessions, "inputsSha256": consumed,
             "formulas": {"knownTokens": "Sum direct/shared/unattributed total.known; cache/reasoning subsets are not added",
                          "perAccepted": "All retained attempts including rejected branches / independently accepted tracks; heterogeneous descriptive ratio",
                          "transcriptBytes": "Raw governing stdout file bytes; includes native envelopes and differs by harness; not prompt tokens"},
             "elapsedSecondsByInvocation": [{"id": attempt["id"], "stage": attempt["stage"], "seconds": attempt.get("elapsedSeconds")} for attempt in suite["attempts"]]}
    (directory / "usage-report.json").write_text(json.dumps(value, indent=2) + "\n")
    return value
