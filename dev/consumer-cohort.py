"""Two-task live consumer fixture and evidence predicates; no separate accounting."""
import json
from pathlib import Path
import runpy
import uuid


def seed(call, project, language, specification):
    operations = []

    def change(mutations):
        operation = {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())},
            "mutations": mutations, "fences": [], "reason": "Live consumer cohort fixture"}}}}
        result = call(operation)["Changed"]["ack"]
        operations.append({"command": operation, "ack": result})
        return result["items"]

    scopes = [
        ("Counting and ordering", [
            "Read UTF-8 stdin, tokenize maximal ASCII [A-Za-z]+ runs, normalize ASCII lowercase, count and sort by descending count then ascending word; empty/no-word input emits nothing.",
            "Provide automated tokenization, Unicode-separator, normalization, tie-order and empty-input tests; document these rules in README.",
        ]),
        ("Command-line contract", [
            "Implement --top N for 1..1000, unknown/missing/invalid/out-of-range argument errors with exit 2, nonempty stderr and empty stdout; --help exits 0 with stdout usage and no stderr; normal success exits 0 without stderr.",
            f"Provide the specified {language} launch/test commands, automated top-limit/argument/help tests and README usage, including option prefixes and oversized numeric input.",
        ]),
    ]

    def draft(title, body, content):
        return {"title": title, "body": body, "labels": [], "archived": False, "content": content, "citations": []}

    goal_draft = draft("Word-frequency consumer", specification + "\n\nIndependent candidate review must execute the configured consumer-oracle check freshly and inspect acceptance separately for each assigned task.",
        {"Goal": {"status": "Open", "outcome": "Deliver the specified consumer", "acceptance": ["Both task scopes independently reviewed"], "scope": language}})
    drafts = [draft(title, "Implement this task's separate acceptance criteria against the complete specification in the goal guidance.",
        {"Task": {"status": "Ready", "acceptance": acceptance, "result": None, "validation": []}}) for title, acceptance in scopes]
    goal, *members = change([{"Create": {"draft": value}} for value in [goal_draft, *drafts]])
    for index, member in enumerate(members):
        linked = change([{"Reference": {"source": goal["id"], "expectedSource": goal["revision"], "relation": "Produces",
            "target": member["id"], "expectedTarget": member["revision"], "present": True}}])
        goal = next(value for value in linked if value["id"] == goal["id"])
        members[index] = next(value for value in linked if value["id"] == member["id"])
    # Implementation is admitted only for Tasks under a milestone.
    milestone, = change([{"Create": {"draft": draft("Word-frequency consumer delivery", "", {"Milestone": {"status": "Open", "objective": "Deliver the specified consumer"}})}}])
    for index, member in enumerate(members):
        linked = change([{"Reference": {"source": member["id"], "expectedSource": member["revision"], "relation": "PartOf",
            "target": milestone["id"], "expectedTarget": milestone["revision"], "present": True}}])
        milestone = next(value for value in linked if value["id"] == milestone["id"])
        members[index] = next(value for value in linked if value["id"] == member["id"])
    return {"goal": goal, "members": members, "drafts": drafts, "operations": operations}


def request(seed):
    return ("Advance the two existing related tasks under the supplied goal through independent candidate review. "
        "Keep their exact revisions for deferred independent assessment; do not create replacement tasks or edit their acceptance. "
        "Use the goal revision as guidance in every selection: it contains the complete consumer specification and review obligations. "
        "Ask dispatch Select for Worker Implement over the two task roots. The host may first offer a compatibility Planner; "
        "claim the complete offered group and start it. "
        "Forward its result handle as artifact context in a fresh selection; do not read the full assessment. "
        "Start a compatible shared Worker; after host checks pass, select Candidate Reviewer using the exact Worker result handle and start it. "
        "Use StartChoice, configured limits, compact Status and exact complete-group claims throughout. "
        "The reviewer must execute a fresh consumer-oracle check and independently accept each member. "
        "Continue correction through handles if needed; do not force a compatibility verdict or claim success after a blocker. "
        "Release claims after review without changing member revisions. Return result handles and bounded outcomes. "
        "Do not compose child prompts, copy full results or claim integration.\n" + json.dumps({"goal": seed["goal"], "members": seed["members"]}))


def assessment_handoff(seed, preview):
    expected = sorted(json.dumps(value, sort_keys=True) for value in seed["members"])
    actual = sorted(json.dumps(value, sort_keys=True) for value in preview["members"])
    assert actual == expected, "Seeded revisions changed after candidate review"
    assert not preview["claims"] and not preview["integrations"], "Cohort retains an active claim or integration hold"


def accepted(chain, statuses, artifacts, tickets, attempts, observations, seed, governor, routes, checks, base):
    """Prove shared execution and any correction frontier; whole-scope Audit remains separate."""
    predicates = runpy.run_path(str(Path(__file__).with_name("consumer-evidence.py")))
    def identity(item):
        return item["project"]["value"], item["ledger"], int(item["number"])

    def references(values):
        return sorted((identity(value["id"]), int(value["revision"]["value"])) for value in values)

    expected = references(seed["members"])
    assert len(expected) == 2 and len(set(value[0] for value in expected)) == 2, "Cohort requires two distinct seeded tasks"
    assert chain == predicates["accepted_chain"](statuses, artifacts, checks), "Final accepted chain changed"
    by_attempt = {value["attempt"]["id"]["value"]: value for value in attempts}
    assert len(by_attempt) == len(attempts) and governor["id"]["value"] in by_attempt, "Missing or duplicate governing attempt"
    assert by_attempt[governor["id"]["value"]]["attempt"] == governor and governor["role"] == "Governor" and governor["parent"] is None
    metered = {value["upload"]["observation"]["attempt"]["value"] for value in observations}
    assert set(by_attempt) <= metered, "A required hierarchy attempt has no usage observation"
    measured = {value["upload"]["observation"]["attempt"]["value"] for value in observations
                if any(value["upload"]["observation"]["counters"][name]["value"] is not None and
                       value["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert set(by_attempt) <= measured, "A required hierarchy attempt has no observed input/output counter"
    for view in attempts:
        attempt = view["attempt"]
        assert attempt["session"] == governor["session"] and attempt["harness"] in routes.values(), "Unexpected attempt session or harness"
        if attempt["id"] != governor["id"]:
            assert attempt["parent"] == governor["id"], "Child attempt has another governing parent"
    assert governor["harness"] == routes["Governor"], "Governing route differs from configuration"

    def evidence(handle):
        value = artifacts[handle["value"]]
        assert value["kind"] == "Result" and value["body"]["attempt"] == value["attempt"]
        return value["body"]

    def execution(value, role, required):
        attempt = value["attempt"]["value"]
        view = by_attempt[attempt]
        ticket = tickets[attempt]
        assert view["attempt"] == ticket["attempt"] and view["assignment"] == ticket["assignment"], "Ticket differs from operational assignment"
        assert view["attempt"]["role"] == role and view["attempt"]["harness"] == routes[role], "Child used another role/harness route"
        assert ticket["request"] == value["request"] and references(value["request"]["members"]) == required, "Execution differs from exact cohort request"
        work = {"Planner": {"Planner": {}}, "Worker": {"Worker": {"mode": "Implement"}}, "Reviewer": {"Reviewer": {"mode": "Candidate"}}}[role]
        assert value["request"]["work"] == work, "Execution used another role mode"
        attribution = "Shared" if len(required) > 1 else "Direct"
        assert view["assignment"]["attribution"] == attribution and sorted(map(identity, view["assignment"]["members"])) == [item for item, _ in required]
        assert value["request"]["harness"] == routes[role] and ticket["selection"] is not None, "Missing selection-backed route"
        selected = artifacts[ticket["selection"]["value"]]
        assert selected["kind"] == "Selection" and selected["attempt"] == governor["id"], "Selection has another owner"
        decision = selected["body"]["decision"]
        assert decision["artifact"] == ticket["selection"]
        choices = [choice for choice in decision["choices"] if choice["id"] == value["request"]["request"]]
        assert len(choices) == 1, "Execution has no unique retained choice"
        choice = choices[0]
        assert all(choice[field] == value["request"][field] for field in ["work", "members", "guidance", "artifacts", "previous", "limits"]), "Execution differs from selected choice"
        assert (choice["cohort"] is not None) == (len(required) > 1) and choice["cohort"] == view["assignment"]["cohort"], "Execution cohort identity changed"
        inputs = [artifact["body"] for artifact in artifacts.values() if artifact["kind"] == "Input" and artifact["attempt"] == value["attempt"]]
        assert len(inputs) == 1 and inputs[0]["input"]["request"] == value["request"] and inputs[0]["base"] == value["base"], "Missing exact execution input"
        assert inputs[0]["checks"] and references([{"id": item["item"]["id"], "revision": item["item"]["revision"]} for item in inputs[0]["input"]["members"]]) == required
        return choice, inputs[0]

    lineage = []
    current = chain
    seen = set()
    while True:
        handle = current["reviewResult"]["value"]
        assert handle not in seen, "Cyclic correction lineage"
        seen.add(handle)
        lineage.append(current)
        required = references(current["members"])
        assert required and set(required) <= set(expected), "Correction changed seeded identities or revisions"
        worker, reviewer = evidence(current["workerResult"]), evidence(current["reviewResult"])
        worker_choice, worker_input = execution(worker, "Worker", required)
        _, reviewer_input = execution(reviewer, "Reviewer", required)
        assert reviewer["base"] == worker["candidate"] and reviewer_input["checks"] == worker_input["checks"] == list(checks.values())
        fresh = [value for value in reviewer["validation"] if value["check"] == "consumer-oracle"]
        assert len(fresh) == 1 and artifacts[fresh[0]["artifact"]["value"]]["attempt"] == reviewer["attempt"], "Reviewer did not author a fresh oracle observation"
        if worker_choice["reason"] == "CompatibleAssessment":
            assert required == expected and worker["base"] == base, "Shared Worker changed original members or base"
            break
        previous = worker["request"]["previous"]
        if previous is not None:
            prior = predicates["reviewed_chain"](statuses, artifacts, checks, previous)
            assert prior["members"] == current["members"] and worker["base"] == prior["candidate"], "Correction changed assignment or candidate base"
            assert worker_choice["reason"] == "ExactPrevious", "Correction lacks an exact previous choice"
        else:
            assert worker_choice["reason"] == "FreshFromBase" and worker["base"] == base, "Split lacks explicit fresh-base selection"
            contexts = worker["request"]["artifacts"]
            candidates = [pair for pair in predicates["reviewed_chains"](statuses, artifacts, checks)
                          if pair["reviewResult"] in contexts and pair["workerResult"] in contexts and
                          set(required) < set(references(pair["members"]))]
            assert len(candidates) == 1, "Split lacks unique original Worker and Review context"
            prior = candidates[0]
        reports = evidence(prior["reviewResult"])["report"]["Review"]["members"]
        pending = {identity(value["item"]) for value in reports if value["verdict"] == "ChangesRequested"}
        # A continuation keeps the whole group on its candidate, accepted members included (D113); a fresh split takes exactly the rejected members.
        corrected = {item for item, _ in required}
        assert pending and (pending <= corrected if previous is not None else pending == corrected), "Correction does not cover exactly the rejected members"
        assert all(value["verdict"] in ["Accepted", "ChangesRequested"] for value in reports), "Blocked member has no completed correction"
        current = prior
    plans = []
    for handle in worker["request"]["artifacts"]:
        stored = artifacts[handle["value"]]
        if stored["kind"] != "Result" or "Plan" not in stored["body"]["report"]:
            continue
        plan = evidence(handle)
        _, plan_input = execution(plan, "Planner", expected)
        assert plan_input["base"] == worker_input["base"] and plan_input["checks"] == worker_input["checks"], "Assessment base/check inventory changed"
        groups = [group for group in plan["report"]["Plan"]["assessments"] if references([member["member"] for member in group["members"]]) == expected]
        assert len(groups) == 1 and groups[0]["compatibility"] == "Compatible", "No exact compatible assessment"
        for member in groups[0]["members"]:
            index = next(index for index, reference in enumerate(seed["members"]) if reference["id"] == member["member"]["id"])
            expected_indices = list(range(len(seed["drafts"][index]["content"]["Task"]["acceptance"])))
            assert sorted(value["criterion"] for value in member["acceptance"]) == expected_indices, "Incomplete member acceptance mapping"
            assert all(value["checks"] == ["consumer-oracle"] and value["inspection"].strip() for value in member["acceptance"])
        matching = [status for status in statuses if status["attempt"] == plan["attempt"] and status["result"] == handle]
        assert len(matching) == 1 and matching[0]["phase"] == "Completed" and matching[0]["usageDelivered"]
        plans.append(handle)
    assert len(plans) == 1, "Worker requires one qualifying Planner assessment"
    return {"plannerResult": plans[0], "workerResult": current["workerResult"], "reviewResult": current["reviewResult"],
            "finalWorkerResult": chain["workerResult"], "finalReviewResult": chain["reviewResult"], "candidate": chain["candidate"],
            "lineage": list(reversed(lineage)), "members": seed["members"], "finalMembers": chain["members"],
            "independentAssessment": "pending", "routes": routes, "attempts": len(attempts), "meteredAttempts": len(metered)}


def retained(directory):
    """Replay retained process evidence without modifying its original manifest."""
    def read(path):
        return json.loads(path.read_text())

    sessions = list((directory / "sessions").iterdir())
    assert len(sessions) == 1, "Expected one retained cohort session"
    session = sessions[0]
    run = read(session / "run.json")
    result, settings = read(directory / "result.json"), read(directory / "settings.json")
    assert result["cohort"] and result["exit"] == 0 and not result["archiveErrors"], "Incomplete native cohort execution/archive"
    assert result["status"] == "candidate-passed" or (result["status"] == "failed" and result["error"] == "Accepted cohort differs from exact seeded members"), "Unresolved evaluation failure"
    chain = read(directory / "accepted-chain.json")
    assert chain == result["acceptedChain"], "Retained candidate chain changed"
    tickets = {value["attempt"]["id"]["value"]: value for path in (session / "children").glob("*/ticket.json") if (value := read(path))}
    observations = [entry for path in sorted(directory.glob("usage-audit-*.json")) for entry in read(path)["UsageAudit"]["page"]["entries"]]
    routes = {role: result[field].capitalize() for role, field in [("Governor", "harness"), ("Planner", "planner"), ("Worker", "worker"), ("Reviewer", "reviewer")]}
    return accepted(chain, read(directory / "dispatch-statuses.json"), read(directory / "candidate-evidence.json"), tickets,
                    read(directory / "attempts.json")["UsageAttempts"]["page"]["entries"], observations, read(directory / "cohort-seed.json"),
                    run["attempt"], routes, {value["name"]: value for value in settings["checks"]}, run["base"])


def audited(proof, seed, statuses, artifacts, ticket, run, job, attempts, observations, before, after):
    """Whole-scope inspection acceptance; executable evidence belongs to the Candidate Review."""
    assessment_handoff(seed, before)
    assessment_handoff(seed, after)
    assert len(statuses) == 1, "Expected one whole-scope Audit"
    status = statuses[0]
    assert status["phase"] == "Completed" and status["usageDelivered"] and status["result"] is not None
    artifact = artifacts[status["result"]["value"]]
    result = artifact["body"]
    assert artifact["kind"] == "Result" and artifact["attempt"] == result["attempt"] == status["attempt"] == ticket["attempt"]["id"]
    request = result["request"]
    assert request == ticket["request"] and request["request"] == status["request"]
    assert request["work"] == {"Reviewer": {"mode": "Audit"}} and request["harness"] == "Codex"
    assert sorted(map(lambda value: json.dumps(value, sort_keys=True), request["members"])) == sorted(map(lambda value: json.dumps(value, sort_keys=True), seed["members"]))
    assert request["previous"] is None and request["guidance"] == [seed["goal"]]
    required = [proof[field] for field in ["plannerResult", "workerResult", "reviewResult", "finalWorkerResult", "finalReviewResult"]]
    assert all(handle in request["artifacts"] for handle in required), "Audit omitted original or final context"
    assert result["base"] == run["base"] == job["workspace"]["base"] == proof["candidate"], "Audit inspected another candidate"
    assert job["workspace"]["attempt"] == result["attempt"] and job["workspace"]["owner"] == run["attempt"]["session"] and job["workspace"]["repository"] == run["repository"]
    observed = job["exit"]
    assert job["phase"] == "Settled" and observed is not None and observed["settled"] and not observed["hostFailure"] and observed["reason"] == "Exited" and observed["code"] == 0 and observed["signal"] is None
    assert result["candidate"] is None and result["validation"] == [], "Audit falsely supplied executable evidence"
    inputs = [value["body"] for value in artifacts.values() if value["kind"] == "Input" and value["attempt"] == result["attempt"]]
    assert len(inputs) == 1 and inputs[0]["base"] == proof["candidate"] and inputs[0]["input"]["request"] == request
    frozen = [{"id": value["item"]["id"], "revision": value["item"]["revision"]} for value in inputs[0]["input"]["members"]]
    assessment_handoff(seed, {"members": frozen, "claims": [], "integrations": []})
    reports = result["report"]["Review"]["members"]
    identities = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
    assert identities(value["item"] for value in reports) == identities(value["id"] for value in seed["members"])
    assert all(value["verdict"] == "Accepted" for value in reports) and result["report"]["Review"]["proposal"] is None, "Whole-scope Audit did not accept both tasks"
    child, governor = ticket["attempt"], run["attempt"]
    assert child["role"] == "Reviewer" and child["harness"] == "Codex" and child["model"] == "gpt-6-astra"
    assert governor["role"] == "Governor" and governor["parent"] is None and child["parent"] == governor["id"] and child["session"] == governor["session"]
    assert ticket["assignment"]["attribution"] == "Shared" and identities(ticket["assignment"]["members"]) == identities(value["id"] for value in seed["members"])
    by_attempt = {value["attempt"]["id"]["value"]: value for value in attempts}
    assert len(attempts) == len(by_attempt) == 2 and all(by_attempt[child["id"]["value"]][field] == ticket[field] for field in ["attempt", "assignment"])
    assert by_attempt[governor["id"]["value"]]["attempt"] == governor
    measured = {entry["upload"]["observation"]["attempt"]["value"] for entry in observations if any(
        entry["upload"]["observation"]["counters"][name]["value"] is not None and entry["upload"]["observation"]["counters"][name]["measurement"] == "Observed" for name in ["input", "output"])}
    assert set(by_attempt) <= measured, "Audit hierarchy lacks observed usage"
    return {"candidate": proof["candidate"], "members": seed["members"], "auditResult": status["result"],
            "executableWitness": proof["finalReviewResult"], "inspectionWitness": status["result"], "wholeScopeAccepted": True}
