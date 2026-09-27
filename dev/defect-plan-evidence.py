"""Reviewed empirical adjudication and the defect-derived repair graph."""
import hashlib
import json
from pathlib import Path
import runpy


def identity(value):
    return json.dumps(value, sort_keys=True)


def revisions(before, after):
    history = runpy.run_path(str(Path(__file__).with_name("process-history-evidence.py")))
    history["references"](before["histories"])
    history["references"](after["histories"])
    old = {identity(page["id"]): page["page"]["entries"] for page in before["histories"]}
    new = {identity(page["id"]): page["page"]["entries"] for page in after["histories"]}
    assert old.keys() <= new.keys(), "Planning removed an existing record"
    assert len(after["views"]) == len(new)
    assert all(new[identity(view["item"]["id"])][0]["item"] == view for view in after["views"])
    changed = []
    for key, entries in new.items():
        previous = old.get(key, [])
        assert not previous or entries[-len(previous):] == previous, "Planning rewrote prior history"
        changed.extend(entries[:len(entries) - len(previous)])
    assert changed, "Planning produced no changes"
    return changed


def graph(before, after, observations, results):
    changes = revisions(before, after)
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    intake = core["records"](before)
    views = {identity(view["item"]["id"]): view for view in after["views"]}
    old = {identity(view["item"]["id"]): view for view in before["views"]}
    def view(item):
        return views[identity(item)]
    def content(item, ledger):
        return view(item)["item"]["draft"]["content"][ledger]
    def evidence(values):
        assert values and all(value["origin"] == "ModelDeclared" and value["citations"] for value in values)
        cited = [citation["Artifact"]["id"] for value in values for citation in value["citations"] if "Artifact" in citation]
        assert any(handle in cited for handle in observations) and all(handle in cited for handle in results), "Adjudication omitted verified evidence handles"
    defect = content(intake["defect"], "Defect")
    assert defect["status"] == "Open" and not defect["resolution"], "Planning declared premature defect resolution"
    prior_defect = old[identity(intake["defect"])]["item"]["draft"]["content"]["Defect"]
    assert all(defect[field] == prior_defect[field] for field in ["severity", "observed", "expected", "reproduction"]), "Planning changed the original defect contract"
    research = content(intake["research"], "Research")
    assert research["status"] in ["Concluded", "Inconclusive"] and research["conclusion"] and research["recommendation"]
    assert research["question"] == old[identity(intake["research"])]["item"]["draft"]["content"]["Research"]["question"]
    evidence(research["findings"])
    for item in [intake["parent"], *intake["branches"]]:
        hypothesis = content(item, "Hypothesis")
        assert hypothesis["status"] in ["Supported", "Refuted", "Inconclusive"] and hypothesis["adjudication"]
        assert hypothesis["claim"] == old[identity(item)]["item"]["draft"]["content"]["Hypothesis"]["claim"], "Adjudication replaced the tested hypothesis"
        evidence(hypothesis["evidence"])
        assert view(item)["refs"] == old[identity(item)]["refs"], "Planning changed the hypothesis tree"
    added = [value for key, value in views.items() if key not in old]
    assert len(added) == 3 and sorted(value["item"]["id"]["ledger"] for value in added) == ["Goals", "Milestones", "Tasks"]
    goal, = [value for value in added if value["item"]["id"]["ledger"] == "Goals"]
    task, = [value for value in added if value["item"]["id"]["ledger"] == "Tasks"]
    milestone, = [value for value in added if value["item"]["id"]["ledger"] == "Milestones"]
    for target, source, relation in [(goal, view(intake["defect"]), "DerivedFrom"), (task, goal, "DerivedFrom"),
                                      (milestone, goal, "DerivedFrom"), (task, milestone, "PartOf")]:
        assert {"relation": relation, "target": source["item"]["id"]} in target["refs"], "Repair graph has a missing derivation or milestone link"
    for entry in changes:
        value = entry["item"]
        data = value["item"]["draft"]["content"]
        key = identity(value["item"]["id"])
        if key in old:
            original = old[key]
            previous = original["item"]["draft"]["content"]
            if "Hypothesis" in data:
                assert data["Hypothesis"]["claim"] == previous["Hypothesis"]["claim"] and value["refs"] == original["refs"], "History changed the original hypothesis or tree"
            if "Research" in data:
                assert data["Research"]["question"] == previous["Research"]["question"], "History changed the original research question"
            if "Defect" in data:
                assert all(data["Defect"][field] == previous["Defect"][field] for field in ["severity", "observed", "expected", "reproduction"]), "History changed the original defect contract"
        if "Goal" in data or "Defect" in data:
            assert not any(ref["relation"] == "PartOf" for ref in value["refs"]), "Intake or Goal acquired milestone ownership"
        if "Goal" in data:
            assert data["Goal"]["status"] == "Open" and data["Goal"]["acceptance"]
        if "Milestone" in data:
            assert data["Milestone"]["status"] == "Open"
        if "Task" in data:
            assert data["Task"]["status"] == "Ready" and data["Task"]["acceptance"] and data["Task"]["result"] is None and not data["Task"]["validation"]
        if "Defect" in data:
            assert data["Defect"]["status"] == "Open" and not data["Defect"]["resolution"]
    return {**intake, "goal": goal["item"]["id"], "task": task["item"]["id"], "milestone": milestone["item"]["id"]}


def materialized(report, frozen, context, body, expected_artifacts, base):
    handles = [{"value": key} for key in expected_artifacts]
    assert all(handle in report["request"]["artifacts"] for handle in [context["id"], *handles]), "Planning/review omitted empirical context"
    assert frozen["input"]["request"] == report["request"] and frozen["base"] == base
    assert {"metadata": context, "body": body} in frozen["input"]["artifacts"], "Planning/review did not receive the exact instructions/observations"
    for handle in handles:
        value, = [value for value in frozen["input"]["artifacts"] if value["metadata"]["id"] == handle]
        assert json.loads(value["body"]) == expected_artifacts[handle["value"]], "Planning/review received different empirical evidence"


def plan(directory, values, settings, prior):
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    core["settled"](values)
    core["hierarchy"](values, settings, [({"Planner": {}}, "Planner", "Codex"), ({"Reviewer": {"mode": "Plan"}}, "Reviewer", "Pi")])
    before, after = prior["values"]["snapshot"], values["snapshot"]
    handles = [prior["manifest"]["proof"][key] for key in ["observations", "probe", "result"]]
    workflow = values["governing_input"]["body"]
    assert workflow["workflow"]["request"] == {"Advance": {"roots": [core["records"](before)["defect"]], "through": "Plan"}}
    assert workflow["integrationTarget"] is None and values["run"]["base"] == before["git"]
    context = json.loads((directory / "bundle-publication.json").read_text())
    body = (directory / "empirical-input.json").read_text()
    bundle = json.loads(body)
    assert context["actor"]["role"] == "Human" and context["attempt"] == prior["values"]["run"]["attempt"]["id"]
    assert context["sha256"] == hashlib.sha256(body.encode()).hexdigest()
    published = values["artifacts"][context["id"]["value"]]
    assert published["kind"] == "Input" and published["attempt"] == context["attempt"] and published["sha256"] == context["sha256"] and published["body"] == bundle
    observed = prior["values"]["artifacts"][handles[0]["value"]]["body"]
    assert bundle["observations"] == observed["observations"] and bundle["nativeTranscript"] == observed["nativeTranscript"]
    assert bundle["investigation"] == before["views"], "Planning lost the original investigation scope"
    assert bundle["probeResult"] == handles[1] and bundle["researchResult"] == handles[2] and bundle["fixture"] == before["git"]
    source_hash = json.loads((directory / "source-sha256.json").read_text())["dev/defect-plan.md"]
    assert hashlib.sha256(bundle["instructions"].encode()).hexdigest() == source_hash
    for status in values["statuses"]:
        if status["phase"] != "Completed":
            continue
        report = values["artifacts"][status["result"]["value"]]["body"]
        frozen, = [value["body"] for value in values["artifacts"].values() if value["kind"] == "Input" and value["attempt"] == report["attempt"]]
        materialized(report, frozen, context, body, {handle["value"]: prior["values"]["artifacts"][handle["value"]]["body"] for handle in handles}, before["git"])
    proof = graph(before, after, [handles[0], context["id"]], handles[1:])
    support = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    lineage = support["planning_lineage"](after["histories"], values["statuses"], values["artifacts"], values["run"]["attempt"]["session"])
    applied = [value for value in lineage if value["applied"]]
    events = [json.loads(line) for line in (values["session"] / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout").read_text().splitlines() if line.strip()]
    acknowledgements = core["reviewed_applications"](events, applied)
    requests = [value["request"] for value in applied]
    assert all(entry["item"]["item"]["provenance"]["request"] in requests for entry in revisions(before, after)), "Planning bypassed accepted proposal application"
    return {**proof, "planning": applied, "acknowledgements": acknowledgements, "evidence": handles}
