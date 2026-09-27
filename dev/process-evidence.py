"""Worked-process evidence predicates; record presence alone is insufficient."""


def question_checkpoint(views, histories, tickets, receipt, claims):
    assert receipt["processSucceeded"] and receipt["usageDelivered"] and receipt["report"] is not None, "Begin did not produce a delivered report"
    items = [view["item"] for view in views]
    assert not any(item["id"]["ledger"] in ["Tasks", "Milestones"] for item in items), "A premature task or milestone was created"
    ideas = [item for item in items if "Idea" in item["draft"]["content"]]
    goals = [item for item in items if "Goal" in item["draft"]["content"]]
    questions = [item for item in items if "Question" in item["draft"]["content"]]
    assert len(ideas) == len(goals) == len(questions) == 1, "Expected one intake, derived goal and language question"
    idea, goal, question = ideas[0], goals[0], questions[0]
    for item, producer in [(goal, idea), (question, goal)]:
        view = next(view for view in views if view["item"]["id"] == item["id"])
        assert {"relation": "DerivedFrom", "target": producer["id"]} in view["refs"], "Missing actual derivation relationship"
    for view in views:
        if view["item"]["id"] in [idea["id"], goal["id"]]:
            assert not any(ref["relation"] == "PartOf" and ref["target"]["ledger"] == "Milestones" for ref in view["refs"]), "Intake/goal acquired milestone ownership"
    content = question["draft"]["content"]["Question"]
    assert content["status"] == "Open" and content["answer"] is None and sorted(content["alternatives"]) == ["Go", "Python"], "Language was selected without an actual answer"
    summary = receipt["report"]["summary"]
    assert "Q" + question["id"]["number"] in summary and all(value in summary for value in ["Python", "Go"]), "Bounded receipt omitted the actual question or alternatives"
    assert all(ticket["request"]["work"] not in [{"Worker": {"mode": "Implement"}}, {"Worker": {"mode": "ResolveConflict"}}] for ticket in tickets), "Implementation started before the user answer"
    assert not any(item["draft"]["content"].get("Decision", {}).get("status") == "Adopted" for item in items), "A decision was adopted before the user answer"
    for item in items:
        matches = [page for page in histories if page["id"] == item["id"]]
        assert len(matches) == 1 and not matches[0]["page"]["hasMore"], "History is incomplete"
        entries = matches[0]["page"]["entries"]
        assert sorted(int(entry["item"]["item"]["revision"]["value"]) for entry in entries) == list(range(1, int(item["revision"]["value"]) + 1)), "History omitted a revision"
        assert all(entry["item"]["item"]["id"] == item["id"] for entry in entries), "History belongs to another record"
        latest = next(entry["item"] for entry in entries if entry["item"]["item"]["revision"] == item["revision"])
        assert latest == next(view for view in views if view["item"]["id"] == item["id"]), "Current item differs from final history entry"
        for entry in entries:
            historical = entry["item"]["item"]["draft"]["content"]
            if "Question" in historical:
                assert historical["Question"]["answer"] is None and historical["Question"]["status"] != "Answered", "A historical answer was fabricated before user input"
            if "Decision" in historical:
                assert historical["Decision"]["status"] != "Adopted", "A historical adopted decision predates user input"
            if item["id"]["ledger"] in ["Ideas", "Goals"]:
                assert not any(ref["relation"] == "PartOf" and ref["target"]["ledger"] == "Milestones" for ref in entry["item"]["refs"]), "Historical intake/goal acquired milestone ownership"
    assert not claims["claims"] and not claims["integrations"], "Question checkpoint retains a claim or integration hold"
    expected = sorted((item["id"]["ledger"], item["id"]["number"], item["revision"]["value"]) for item in items)
    actual = sorted((item["id"]["ledger"], item["id"]["number"], item["revision"]["value"]) for item in claims["members"])
    assert expected == actual, "Checkpoint revisions changed during export"
    return {"status": "awaiting-user-answer", "idea": {"id": idea["id"], "revision": idea["revision"]},
            "goal": {"id": goal["id"], "revision": goal["revision"]},
            "question": {"id": question["id"], "revision": question["revision"]}, "prompt": content["prompt"], "alternatives": content["alternatives"]}


def supplied_answer(checkpoint, answer):
    assert set(answer) == {"question", "answer", "verbatim", "source"}, "Answer record fields differ from the required source record"
    assert answer["question"] == checkpoint["question"], "Answer belongs to another question or revision"
    assert answer["answer"] in checkpoint["alternatives"], "Unsupported language answer"
    assert isinstance(answer["verbatim"], str) and answer["verbatim"].strip() and len(answer["verbatim"]) <= 8192, "Actual reply text is required"
    assert isinstance(answer["source"], str) and answer["source"].strip() and len(answer["source"]) <= 2000, "Actual reply source is required"
    return answer["answer"].lower()


def integrated_resume(checkpoint, answer, answer_citation, views, histories, statuses, artifacts, integrations, run, target, claims, checks):
    from pathlib import Path
    import runpy

    evidence = runpy.run_path(str(Path(__file__).with_name("consumer-evidence.py")))
    items = [view["item"] for view in views]

    def identity(item):
        return item["project"]["value"], item["ledger"], item["number"]

    def item_for(id):
        matches = [item for item in items if item["id"] == id]
        assert len(matches) == 1, "Required process item is missing or duplicated"
        return matches[0]

    def references(id):
        return next(view["refs"] for view in views if view["item"]["id"] == id)

    all_history = []
    for view in views:
        item = view["item"]
        pages = [value["page"] for value in histories if value["id"] == item["id"]]
        assert len(pages) == 1 and not pages[0]["hasMore"], "Resume history is incomplete"
        snapshots = [entry["item"] for entry in pages[0]["entries"]]
        assert all(value["item"]["id"] == item["id"] for value in snapshots)
        assert sorted(int(value["item"]["revision"]["value"]) for value in snapshots) == list(range(1, int(item["revision"]["value"]) + 1))
        assert next(value for value in snapshots if value["item"]["revision"] == item["revision"]) == view
        all_history.extend(snapshots)
    question = item_for(checkpoint["question"]["id"])
    assert question["draft"]["content"]["Question"]["status"] == "Answered" and question["draft"]["content"]["Question"]["answer"] == answer["answer"]
    assert answer_citation in question["draft"]["citations"]
    answered_revision = str(int(checkpoint["question"]["revision"]["value"]) + 1)
    answered = next(value["item"] for value in all_history if value["item"]["id"] == question["id"] and value["item"]["revision"]["value"] == answered_revision)
    assert answered["draft"]["content"]["Question"]["answer"] == answer["answer"] and answered["provenance"]["actor"]["role"] == "Human", "Answer did not enter through the actual operator record path"
    goal = item_for(checkpoint["goal"]["id"])
    initial_goal = next(value["item"] for value in all_history if value["item"]["id"] == goal["id"] and value["item"]["revision"]["value"] == "1")
    assert initial_goal["provenance"]["actor"]["session"] != run["attempt"]["session"], "Resume reused historical governing authority"
    for snapshot in all_history:
        if snapshot["item"]["id"] == question["id"] and int(snapshot["item"]["revision"]["value"]) >= int(answered_revision):
            content = snapshot["item"]["draft"]["content"]["Question"]
            assert content["status"] == "Answered" and content["answer"] == answer["answer"] and answer_citation in snapshot["item"]["draft"]["citations"], "The supplied answer changed after recording"
        if snapshot["item"]["id"]["ledger"] in ["Ideas", "Goals"]:
            assert not any(ref["relation"] == "PartOf" and ref["target"]["ledger"] == "Milestones" for ref in snapshot["refs"]), "Resume assigned intake/goal milestone ownership"
    decisions = [item for item in items if "Decision" in item["draft"]["content"] and item["draft"]["content"]["Decision"]["status"] == "Adopted"]
    assert len(decisions) == 1 and answer_citation in decisions[0]["draft"]["citations"] and {"relation": "DerivedFrom", "target": question["id"]} in references(decisions[0]["id"]), "Decision lacks the supplied-answer evidence"
    tasks = [item for item in items if "Task" in item["draft"]["content"]]
    milestones = [item for item in items if "Milestone" in item["draft"]["content"]]
    assert len(tasks) == 2 and len(milestones) == 1, "Resume requires two task scopes under one milestone"
    members = {identity(item["id"]) for item in tasks}
    for task in tasks:
        assert task["draft"]["content"]["Task"]["status"] == "Done", "Task was not incorporated"
        assert {"relation": "DerivedFrom", "target": goal["id"]} in references(task["id"])
        assert {"relation": "PartOf", "target": milestones[0]["id"]} in references(task["id"])
        assert int(task["createdAt"]) >= int(answered["updatedAt"]), "Task predates actual answer"
    handoffs = [item for item in items if "Handoff" in item["draft"]["content"] and
                members <= {identity(ref["target"]) for ref in references(item["id"]) if ref["relation"] == "DerivedFrom"}]
    assert len(handoffs) == 1, "No single handoff is shared by both tasks"
    recorded = []
    for integration in integrations:
        record, local = integration["record"], integration["local"]
        intent = record["intent"]
        assert intent == local["intent"] and intent["owner"]["session"] == run["attempt"]["session"] and intent["target"] == "refs/heads/integration", "Integration belongs to another authority or target"
        assert set(map(identity, (member["id"] for member in intent["members"]))) <= members
        if "Recorded" in record["resolution"]:
            resolution = record["resolution"]["Recorded"]
            assert local["attempted"] and local["observation"] == {"Incorporated": {"target": resolution["observedTarget"]}}
            chain = evidence["reviewed_chain"](statuses, artifacts, checks, intent["reviewer"])
            assert chain["workerResult"] == intent["worker"] and chain["candidate"] == intent["candidate"] and chain["members"] == intent["members"]
            review = artifacts[chain["reviewResult"]["value"]]["body"]
            assert all(value["verdict"] == "Accepted" for value in review["report"]["Review"]["members"])
            fresh = next(value for value in review["validation"] if value["check"] == "consumer-oracle")
            assert artifacts[fresh["artifact"]["value"]]["attempt"] == review["attempt"], "Candidate reviewer did not execute a fresh oracle"
            for member in intent["members"]:
                ack = next(value for value in resolution["acknowledgement"]["items"] if value["id"] == member["id"])
                snapshot = next(value["item"] for value in all_history if value["item"]["id"] == ack["id"] and value["item"]["revision"] == ack["revision"])
                assert snapshot["draft"]["content"]["Task"]["status"] == "Done" and any(
                    value["origin"] == "HostObserved" and {"Artifact": {"id": intent["reviewer"]}} in value["citations"] for value in snapshot["draft"]["content"]["Task"]["validation"]), "Task completion lacks host integration evidence"
            recorded.append((record, chain))
        else:
            assert "NotApplied" in record["resolution"], "Integration is unresolved"
    assert {identity(member["id"]) for record, _ in recorded for member in record["intent"]["members"]} == members, "Some task scopes have no recorded incorporation"
    for task in tasks:
        acknowledged = [ack for record, _ in recorded for ack in record["resolution"]["Recorded"]["acknowledgement"]["items"] if ack["id"] == task["id"]]
        assert task["revision"] == max(acknowledged, key=lambda ack: int(ack["revision"]["value"]))["revision"], "Task changed after recorded integration"
    final = [(record, chain) for record, chain in recorded if record["intent"]["candidate"] == target and record["resolution"]["Recorded"]["observedTarget"] == target]
    assert len(final) == 1, "Final Git target lacks exact reviewed/recorded candidate evidence"
    assert not claims["claims"] and not claims["integrations"], "Resume retains a claim or integration hold"
    assert sorted((identity(value["id"]), value["revision"]["value"]) for value in claims["members"]) == sorted((identity(item["id"]), item["revision"]["value"]) for item in items)
    return {"candidate": target, "finalChain": final[0][1], "tasks": [{"id": item["id"], "revision": item["revision"]} for item in tasks],
            "question": {"id": question["id"], "revision": question["revision"]}, "decision": decisions[0]["id"], "handoff": handoffs[0]["id"],
            "integrations": [record["intent"]["id"] for record, _ in recorded], "planningApplicationAssessment": "pending", "independentProcessAssessment": "pending"}


def reconcile_usage(parts, combined):
    import json
    from fractions import Fraction

    for attribution in ["direct", "shared", "unattributed"]:
        for counter in ["input", "output", "cacheRead", "cacheWrite", "reasoning", "total"]:
            for quantity in ["known", "unknown", "estimated"]:
                assert int(combined[attribution][counter][quantity]) == sum(int(part[attribution][counter][quantity]) for part in parts), "Stage usage was lost or counted twice"
        assert int(combined[attribution]["unknownCosts"]) == sum(int(part[attribution]["unknownCosts"]) for part in parts), "Unknown costs were lost or counted twice"
    for counter in ["incompleteMeters", "attemptsWithoutMeters"]:
        assert int(combined[counter]) == sum(int(part[counter]) for part in parts), "Meter coverage was lost or counted twice"
    for state in ["running", "unknown", "withGaps"]:
        assert int(combined["attempts"][state]) == sum(int(part["attempts"][state]) for part in parts), "Attempt coverage was lost or counted twice"

    def costs(reports):
        totals = {}
        for report in reports:
            assert not report["costs"]["hasMore"], "Cost reconciliation requires complete cost groups"
            for entry in report["costs"]["entries"]:
                key = json.dumps(entry["group"], sort_keys=True)
                amount, measurements = totals.get(key, (Fraction(0), 0))
                totals[key] = (amount + Fraction(entry["amount"]["value"]), measurements + int(entry["measurements"]))
        return totals

    assert costs([combined]) == costs(parts), "Cost groups were lost or counted twice"
