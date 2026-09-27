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
