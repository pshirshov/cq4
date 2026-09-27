"""Two-task live consumer fixture and evidence predicates; no separate accounting."""
import json
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
    return {"goal": goal, "members": members, "drafts": drafts, "operations": operations}


def request(seed, planner, worker, reviewer):
    return ("Advance the two existing related tasks under the supplied goal through independent candidate review. "
        "Keep their exact revisions for deferred independent assessment; do not create replacement tasks or edit their acceptance. "
        "Use the goal revision as guidance in every selection: it contains the complete consumer specification and review obligations. "
        "Ask dispatch Select for Worker Implement over the two task roots. The host may first offer a compatibility Planner; "
        f"claim the complete offered group and start it on {planner}. "
        "Forward its result handle as artifact context in a fresh selection; do not read the full assessment. "
        f"Start a compatible shared Worker on {worker}; after host checks pass, select Candidate Reviewer using the exact Worker result handle and start it on {reviewer}. "
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


def accepted(chain, statuses, artifacts, tickets, attempts, observations, seed, governor, routes):
    """Strengthen an already validated accepted_chain with cohort/process evidence."""
    def identity(item):
        return item["project"]["value"], item["ledger"], int(item["number"])

    def references(values):
        return sorted((identity(value["id"]), int(value["revision"]["value"])) for value in values)

    expected = references(seed["members"])
    assert len(expected) == 2 and len(set(value[0] for value in expected)) == 2, "Cohort requires two distinct seeded tasks"
    assert references(chain["members"]) == expected, "Accepted cohort differs from exact seeded members"
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

    def execution(value, role):
        attempt = value["attempt"]["value"]
        view = by_attempt[attempt]
        ticket = tickets[attempt]
        assert view["attempt"] == ticket["attempt"] and view["assignment"] == ticket["assignment"], "Ticket differs from operational assignment"
        assert view["attempt"]["role"] == role and view["attempt"]["harness"] == routes[role], "Child used another role/harness route"
        assert ticket["request"] == value["request"] and references(value["request"]["members"]) == expected, "Execution differs from exact cohort request"
        work = {"Planner": {"Planner": {}}, "Worker": {"Worker": {"mode": "Implement"}}, "Reviewer": {"Reviewer": {"mode": "Candidate"}}}[role]
        assert value["request"]["work"] == work, "Execution used another role mode"
        assert view["assignment"]["attribution"] == "Shared" and sorted(map(identity, view["assignment"]["members"])) == [item for item, _ in expected]
        assert value["request"]["harness"] == routes[role] and ticket["selection"] is not None, "Missing selection-backed route"
        selected = artifacts[ticket["selection"]["value"]]
        assert selected["kind"] == "Selection" and selected["attempt"] == governor["id"], "Selection has another owner"
        decision = selected["body"]["decision"]
        assert decision["artifact"] == ticket["selection"]
        choices = [choice for choice in decision["choices"] if choice["id"] == value["request"]["request"]]
        assert len(choices) == 1, "Execution has no unique retained choice"
        choice = choices[0]
        assert all(choice[field] == value["request"][field] for field in ["work", "members", "guidance", "artifacts", "previous", "limits"]), "Execution differs from selected choice"
        assert choice["cohort"] is not None and choice["cohort"] == view["assignment"]["cohort"], "Execution cohort identity changed"
        inputs = [artifact["body"] for artifact in artifacts.values() if artifact["kind"] == "Input" and artifact["attempt"] == value["attempt"]]
        assert len(inputs) == 1 and inputs[0]["input"]["request"] == value["request"] and inputs[0]["base"] == value["base"], "Missing exact execution input"
        assert inputs[0]["checks"] and references([{"id": item["item"]["id"], "revision": item["item"]["revision"]} for item in inputs[0]["input"]["members"]]) == expected
        return choice, inputs[0]

    worker, reviewer = evidence(chain["workerResult"]), evidence(chain["reviewResult"])
    worker_choice, worker_input = execution(worker, "Worker")
    _, reviewer_input = execution(reviewer, "Reviewer")
    assert worker_choice["reason"] == "CompatibleAssessment", "Shared Worker lacks an applicable compatibility decision"
    assert reviewer["base"] == worker["candidate"] and reviewer_input["checks"] == worker_input["checks"]
    plans = []
    for handle in worker["request"]["artifacts"]:
        stored = artifacts[handle["value"]]
        if stored["kind"] != "Result" or "Plan" not in stored["body"]["report"]:
            continue
        plan = evidence(handle)
        _, plan_input = execution(plan, "Planner")
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
    fresh = [value for value in reviewer["validation"] if value["check"] == "consumer-oracle"]
    assert len(fresh) == 1 and artifacts[fresh[0]["artifact"]["value"]]["attempt"] == reviewer["attempt"], "Reviewer did not author a fresh oracle observation"
    return {"plannerResult": plans[0], "workerResult": chain["workerResult"], "reviewResult": chain["reviewResult"],
            "members": chain["members"], "routes": routes, "attempts": len(attempts), "meteredAttempts": len(metered)}
