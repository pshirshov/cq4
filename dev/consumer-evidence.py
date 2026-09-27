"""Acceptance of a linked worker/reviewer candidate using host artifact evidence."""


def reviewed_chains(statuses, artifacts, checks):
    assert checks, "Consumer acceptance requires configured host checks"

    def identities(values):
        return sorted((value["project"]["value"], value["ledger"], int(value["number"])) for value in values)

    def result(status):
        artifact = artifacts[status["result"]["value"]]
        assert artifact["kind"] == "Result" and artifact["attempt"] == status["attempt"], "Result artifact ownership mismatch"
        value = artifact["body"]
        assert value["attempt"] == status["attempt"] and value["request"]["request"] == status["request"], "Result/status identity mismatch"
        return value

    for status in statuses:
        if status["phase"] != "Completed" or status["result"] is None or not status["usageDelivered"]:
            continue
        review = result(status)
        if "Review" not in review["report"]:
            continue
        members = review["request"]["members"]
        reports = review["report"]["Review"]["members"]
        if not reports or len(reports) != len(members):
            continue
        if identities(value["item"] for value in reports) != identities(value["id"] for value in members):
            continue
        previous = review["request"]["previous"]
        workers = [entry for entry in statuses if entry["phase"] == "Completed" and entry["result"] == previous and entry["usageDelivered"]]
        if len(workers) != 1:
            continue
        worker = result(workers[0])
        if worker["candidate"] is None or review["candidate"] != worker["candidate"] or members != worker["request"]["members"]:
            continue
        if "Work" not in worker["report"] or any(value["disposition"] != "CandidateReady" for value in worker["report"]["Work"]["members"]):
            continue
        if identities(value["item"] for value in worker["report"]["Work"]["members"]) != identities(value["id"] for value in members):
            continue
        if any(sorted(value["check"] for value in result["validation"]) != sorted(checks) for result in [worker, review]):
            continue
        inherited = {value["check"]: value for value in worker["validation"]}
        applicable = [(value, worker["attempt"]) for value in worker["validation"]]
        applicable.extend((value, review["attempt"]) for value in review["validation"] if value != inherited[value["check"]])
        valid = True
        for evidence, author in applicable:
            artifact = artifacts[evidence["artifact"]["value"]]
            observation = artifact["body"]
            job = observation["job"]
            observed = job["exit"]
            valid = valid and (evidence["state"] == "Passed" and artifact["kind"] == "Validation" and artifact["attempt"] == author and
                              observation["check"] == checks[evidence["check"]] and observation["candidate"] == worker["candidate"] and
                              job["workspace"]["base"] == worker["candidate"] and job["phase"] == "Settled" and observed is not None and
                              observed["settled"] and not observed["hostFailure"] and observed["reason"] == "Exited" and observed["code"] == 0 and observed["signal"] is None)
        if valid:
            yield {"worker": worker["attempt"], "reviewer": review["attempt"], "candidate": worker["candidate"], "members": members,
                    "workerHarness": worker["request"]["harness"], "reviewerHarness": review["request"]["harness"],
                    "workerResult": previous, "reviewResult": status["result"], "validation": [value for value, _ in applicable]}


def reviewed_chain(statuses, artifacts, checks, review):
    matches = [chain for chain in reviewed_chains(statuses, artifacts, checks) if chain["reviewResult"] == review]
    assert len(matches) == 1, "No unique host-validated candidate pair for review handle"
    return matches[0]


def accepted_chain(statuses, artifacts, checks):
    for chain in reviewed_chains(statuses, artifacts, checks):
        reports = artifacts[chain["reviewResult"]["value"]]["body"]["report"]["Review"]["members"]
        if all(value["verdict"] == "Accepted" for value in reports):
            return chain
    raise AssertionError("No fully accepted review linked to the same host-validated worker candidate and assignment")


def corrected_chain(statuses, artifacts, checks, baseline, rejected_review, worker_harness, reviewer_harness):
    chain = accepted_chain(statuses, artifacts, checks)
    worker = artifacts[chain["workerResult"]["value"]]["body"]
    assert chain["worker"] != baseline["worker"] and chain["workerHarness"] == worker_harness and chain["reviewerHarness"] == reviewer_harness, "Correction did not produce the configured new worker/reviewer chain"
    assert worker["request"]["previous"] == rejected_review, "Correction did not consume the rejected review handle"
    assert worker["request"]["members"] == baseline["members"], "Correction changed the original assignment revisions"
    assert worker["base"] == baseline["candidate"], "Correction did not start from the rejected candidate"
    return chain
