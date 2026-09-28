"""Reviewed local resolution with an explicit unperformed external-action boundary."""
import hashlib
import json
from pathlib import Path
import runpy


def scope(snapshot):
    result = {}
    for key, ledger in [("defect", "Defects"), ("goal", "Goals"), ("milestone", "Milestones"), ("task", "Tasks"), ("research", "Researches")]:
        view, = [view for view in snapshot["views"] if view["item"]["id"]["ledger"] == ledger]
        result[key] = view["item"]["id"]
    return result


def graph(before, after, incorporation, context, repository):
    support = runpy.run_path(str(Path(__file__).with_name("defect-plan-evidence.py")))
    changes = support["revisions"](before, after)
    identity = support["identity"]
    selected = scope(before)
    old = {identity(view["item"]["id"]): view for view in before["views"]}
    current = {identity(view["item"]["id"]): view for view in after["views"]}
    added = [value for key, value in current.items() if key not in old]
    assert len(added) == 4 and sorted(value["item"]["id"]["ledger"] for value in added) == ["Handoffs", "Memories", "OperatorActions", "Upstream"]
    ids = {view["item"]["id"]["ledger"]: view["item"]["id"] for view in added}
    editable = [selected[key] for key in ["defect", "goal", "milestone"]]
    review = incorporation["chain"]["reviewResult"]
    commit = {"Commit": {"repository": repository, "hash": incorporation["candidate"]["value"]}}
    input_citation = {"Artifact": {"id": context}}
    review_citation = {"Artifact": {"id": review}}
    def citations(values, expected):
        assert all(value in values for value in expected), "Closeout omitted exact incorporation citations"
    def evidence(values, required):
        assert values and all(value["origin"] == "ModelDeclared" and value["description"] and value["citations"] for value in values), "Closeout manufactured observed evidence"
        citations([citation for value in values for citation in value["citations"]], required)
    for key, previous in old.items():
        if previous["item"]["id"] not in editable:
            assert current[key] == previous, "Closeout changed a completed task or investigation record"
    for entry in changes:
        view = entry["item"]
        item, draft = view["item"]["id"], view["item"]["draft"]
        key = identity(item)
        data = draft["content"]
        if key in old:
            assert item in editable, "Closeout transiently changed the completed task or investigation"
            previous = old[key]
            original = previous["item"]["draft"]["content"]
            assert view["item"]["createdAt"] == previous["item"]["createdAt"] and draft["archived"] == previous["item"]["draft"]["archived"]
            if "Goal" in data:
                assert all(data["Goal"][field] == original["Goal"][field] for field in ["outcome", "acceptance", "scope"]), "Closeout changed the original goal contract"
                assert data["Goal"]["status"] in ["Open", "Achieved"]
                prior_refs = previous["refs"]
                assert all(ref in view["refs"] for ref in prior_refs)
                assert all(ref in prior_refs or (ref["relation"] == "Produces" and ref["target"] in ids.values()) for ref in view["refs"]), "Closeout changed goal relationships outside preparation"
            else:
                assert view["refs"] == previous["refs"], "Closeout changed original relationships"
            if "Defect" in data:
                assert all(data["Defect"][field] == original["Defect"][field] for field in ["severity", "observed", "expected", "reproduction"]), "Closeout changed the original defect contract"
                assert data["Defect"]["status"] in ["Open", "Resolved"]
                if data["Defect"]["status"] == "Resolved":
                    assert data["Defect"]["cause"]
                    evidence(data["Defect"]["resolution"], [input_citation, review_citation, commit,
                        *({"Artifact": {"id": value["artifact"]}} for value in incorporation["chain"]["validation"])])
            if "Milestone" in data:
                assert data["Milestone"]["objective"] == original["Milestone"]["objective"] and data["Milestone"]["status"] in ["Open", "Complete"]
            citations(draft["citations"], [input_citation, review_citation, commit])
        else:
            assert not draft["archived"] and view["refs"] == [{"relation": "DerivedFrom", "target": selected["goal"]}], "Preparation records must derive from the local goal outside its completed milestone"
            citations(draft["citations"], [input_citation, commit])
            if "Upstream" in data:
                value = data["Upstream"]
                assert value["status"] == "Identified" and value["component"] == "synthetic_tokens.py" and value["version"] == incorporation["scope"]["base"]["value"]
                assert value["reproduction"] == old[identity(selected["defect"])]["item"]["draft"]["content"]["Defect"]["reproduction"], "Preparation changed the original reproduction field"
                assert value["report"] is None and value["outcome"] is None, "Preparation claimed an external report or outcome"
            elif "OperatorAction" in data:
                value = data["OperatorAction"]
                assert value["status"] == "Requested" and value["confirmation"] is None and not value["observedEvidence"], "Preparation invented authorization or external completion"
                assert value["action"] and value["expectedEvidence"]
            elif "Handoff" in data:
                value = data["Handoff"]
                assert value["status"] == "Open" and value["outcome"] and (value["remaining"] or value["blockers"]), "Preparation hid pending operator work"
            else:
                value = data["Memory"]
                assert value["status"] == "Current" and value["knowledge"] and value["applicability"]
                evidence(value["evidence"], [input_citation, review_citation])
    for key, ledger, status in [("defect", "Defect", "Resolved"), ("goal", "Goal", "Achieved"), ("milestone", "Milestone", "Complete")]:
        assert current[identity(selected[key])]["item"]["draft"]["content"][ledger]["status"] == status, "Local closeout is incomplete"
    goal = current[identity(selected["goal"])]
    assert all({"relation": "Produces", "target": value} in goal["refs"] for value in ids.values())
    return {**selected, "prepared": ids, "candidate": incorporation["candidate"]}


def upstream(directory, values, settings, prior):
    core = runpy.run_path(str(Path(__file__).with_name("defect-evidence.py")))
    planning = runpy.run_path(str(Path(__file__).with_name("defect-plan-evidence.py")))
    core["settled"](values)
    core["hierarchy"](values, settings, [({"Planner": {}}, "Planner", "Codex"), ({"Reviewer": {"mode": "Plan"}}, "Reviewer", "Pi")])
    before, after = core["continuation"](prior), values["snapshot"]
    ids = scope(before)
    workflow = values["governing_input"]["body"]
    assert workflow["workflow"]["request"] == {"Upstream": {"roots": [ids["defect"]], "action": "Prepare"}} and workflow["integrationTarget"] is None
    assert values["run"]["base"] == before["git"] == prior["manifest"]["proof"]["candidate"] and not settings["checks"]
    context = json.loads((directory / "bundle-publication.json").read_text())
    body = (directory / "empirical-input.json").read_text()
    bundle = json.loads(body)
    assert context["actor"]["role"] == "Human" and context["attempt"] == prior["values"]["run"]["attempt"]["id"]
    assert context["sha256"] == hashlib.sha256(body.encode()).hexdigest()
    artifact = values["artifacts"][context["id"]["value"]]
    assert artifact["body"] == bundle and artifact["sha256"] == context["sha256"] and artifact["kind"] == "Input" and artifact["attempt"] == context["attempt"]
    incorporation = prior["manifest"]["proof"]
    assert bundle["incorporation"] == incorporation and bundle["investigation"] == before["views"] and bundle["fixture"] == before["git"]
    assert bundle["integration"] == json.loads((Path(prior["dump"]).parent / "integrations.json").read_text())
    sources = json.loads((directory / "source-sha256.json").read_text())
    assert hashlib.sha256(bundle["instructions"].encode()).hexdigest() == sources["dev/defect-upstream.md"]
    handles = [incorporation["chain"]["workerResult"], incorporation["chain"]["reviewResult"], *(value["artifact"] for value in incorporation["chain"]["validation"])]
    originals = {planning["identity"](view["item"]["id"]) for view in before["views"]}
    history = {(planning["identity"](page["id"]), entry["item"]["item"]["revision"]["value"]): entry["item"] for page in after["histories"] for entry in page["page"]["entries"]}
    for status in values["statuses"]:
        if status["phase"] != "Completed":
            continue
        report = values["artifacts"][status["result"]["value"]]["body"]
        frozen, = [value["body"] for value in values["artifacts"].values() if value["kind"] == "Input" and value["attempt"] == report["attempt"]]
        planning["materialized"](report, frozen, context, body, {handle["value"]: prior["values"]["artifacts"][handle["value"]]["body"] for handle in handles}, before["git"])
        for field in ["members", "guidance"]:
            expected = [history[(planning["identity"](member["id"]), member["revision"]["value"])] for member in report["request"][field]]
            assert frozen["input"][field] == expected, "Preparation used different record snapshots"
        assigned = {planning["identity"](member["id"]) for member in report["request"]["members"]}
        guidance = {planning["identity"](member["id"]) for member in report["request"]["guidance"]}
        assert originals - assigned <= guidance, "Preparation omitted original investigation guidance"
    proof = graph(before, after, incorporation, context["id"], str(Path(prior["dump"]).parent / "consumer"))
    support = runpy.run_path(str(Path(__file__).with_name("process-assess-evidence.py")))
    lineage = support["planning_lineage"](after["histories"], values["statuses"], values["artifacts"], values["run"]["attempt"]["session"])
    applied = [value for value in lineage if value["applied"]]
    events = [json.loads(line) for line in (values["session"] / "payload" / values["run"]["attempt"]["id"]["value"] / "stdout").read_text().splitlines() if line.strip()]
    acknowledgements = core["reviewed_applications"](events, applied)
    assert all(entry["item"]["item"]["provenance"]["request"] in [value["request"] for value in applied] for entry in planning["revisions"](before, after)), "Preparation bypassed accepted proposal application"
    return {**proof, "planning": applied, "acknowledgements": acknowledgements, "input": context["id"]}
