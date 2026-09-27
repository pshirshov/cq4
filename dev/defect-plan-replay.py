"""Explicit re-verification of the retained observation-citation false negative."""
import hashlib
import json
from pathlib import Path
import runpy


def eligible(manifest):
    assert manifest["stage"] == "plan" and manifest["status"] == "failed"
    assert manifest["error"] == "Adjudication omitted verified evidence handles", "This is not the verified citation-checker failure"
    assert manifest["exit"] == 0 and not manifest["archiveErrors"] and manifest["accounting"] == "reconciled", "Failed execution/archive/accounting cannot be reclassified"


def consumed(directory):
    paths = [directory / name for name in ["result.json", "source-sha256.json", "settings.json", "before.json", "after.json",
        "bundle-publication.json", "empirical-input.json", "dispatch-statuses.json", "candidate-evidence.json", "attempts.json",
        "usage-summary.json", "baseline-usage-summary.json", "combined-usage-summary.json", "cq-database.dump"]]
    paths += list(directory.glob("usage-audit-*.json"))
    session, = (directory / "sessions").iterdir()
    paths += [session / "run.json", session / "receipt.json"]
    for pattern in ["children/*/ticket.json", "children/*/receipt.json", "journal/*.json", "payload/*/stdout", "payload/*/stderr"]:
        paths += list(session.glob(pattern))
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(paths)}


def effective(directory, manifest):
    eligible(manifest)
    verification = json.loads((directory / "plan-verification.json").read_text())
    assert verification["inputsSha256"] == consumed(directory), "Retained plan evidence changed after re-verification"
    assert verification["originalStatus"] == "failed" and verification["reason"] == manifest["error"]
    return {**manifest, "status": "plan-passed", "proof": verification["proof"], "verification": "plan-verification.json"}


def verify(directory):
    manifest = json.loads((directory / "result.json").read_text())
    eligible(manifest)
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    target = directory / "plan-verification.json"
    if target.exists():
        return core["checkpoint"](directory)
    inputs = consumed(directory)
    prior = core["checkpoint"](Path(manifest["baselineEvidence"]))
    values = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))["stage_evidence"](directory)
    proof = runpy.run_path(str(Path(__file__).with_name("defect-plan-evidence.py")))["plan"](
        directory, values, json.loads((directory / "settings.json").read_text()), prior)
    result = core["checked"](directory, 0, {**manifest, "status": "plan-passed", "proof": proof})
    assert inputs == consumed(directory), "Plan evidence changed during re-verification"
    sources = [*Path(__file__).parent.glob("*.py"), Path(__file__).with_name("defect-eval"), Path(__file__).with_name("defect-plan.md")]
    verification = {"originalStatus": manifest["status"], "reason": manifest["error"], "inputsSha256": inputs, "proof": proof,
        "verifierSourcesSha256": {str(path.relative_to(Path(__file__).parent.parent)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(sources)},
        "provenance": "Full retained replay after correcting observation Input equivalence; original native failure and source manifest preserved"}
    with target.open("x") as output:
        output.write(json.dumps(verification, indent=2) + "\n")
    return result
