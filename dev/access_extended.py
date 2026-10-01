import copy
import json
import time
import uuid


GRAPH_SIZES = (4, 32, 128)
INTEGRATION_SIZES = (1, 16)


def identity():
    return {"value": str(uuid.uuid4())}


def counters(value):
    return {name: {"value": str(value if name == "input" else 2 if name == "output" else 0), "measurement": "Observed"}
            for name in ["input", "output", "cacheRead", "cacheWrite", "reasoning"]}


def task(title):
    return {"title": title, "body": "ACCESS_NARRATIVE " + "x" * 4096, "labels": [], "archived": False,
            "content": {"Task": {"status": "Ready", "acceptance": ["Preserve the exact affected set"], "result": None, "validation": []}}, "citations": []}


class ExtendedAccess:
    def __init__(self, driver, projects):
        self.driver = driver
        self.scopes = {}
        for project in projects:
            owner = {"subject": "access governor", "session": {"value": driver.environment["CQ_SESSION"]}, "role": "Governor"}
            tokens = {role: driver.exchange("/api/grant", {"project": project, "actor": {**owner, "role": role},
                "expiresAt": str(int(time.time() * 1000) + 3600000)}, driver.environment["CQ_TOKEN"])["value"] for role in ["Governor", "Collector"]}
            self.scopes[project["value"]] = {"owner": owner, "tokens": tokens}
            member = {"project": project, "ledger": "Tasks", "number": "3"}
            intent = self.integration(project, [member])
            self.host(project, "/api/integration", {"Reserve": {"intent": intent}})
            settled = self.host(project, "/api/integration", {"Observe": {"id": intent["id"], "observation": {"NotApplied": {"reason": "Access seed; no Git attempted"}}}})
            self.domain(project, {"ClaimWork": {"input": {"project": project, "action": {"Release": {"fence": intent["fence"]}}}}})
            attempt = self.attempt(project, [member], "Worker", None)
            baseline = {name: {"value": "0", "measurement": "Observed"} for name in counters(0)}
            self.host(project, "/api/usage", {"Meter": {"value": {"key": "native", "attempt": attempt["id"], "scope": "Increment",
                "baseline": baseline, "baselineCost": {"amount": None, "currency": None, "basis": "Unknown", "pricingVersion": None}}}})
            sample = self.upload(attempt["id"], 1, 10, None)
            receipt = self.host(project, "/api/usage", {"Ingest": {"value": sample}})["Ingested"]["value"]
            projection = driver.sql("SELECT projection::text FROM cq_usage_meters WHERE project_id = :'project'::uuid AND attempt_id = :'attempt'::uuid AND meter = 'native';",
                {"project": project["value"], "attempt": attempt["id"]["value"]})
            self.scopes[project["value"]].update(integration=settled, attempt=attempt, sample=sample, receipt=receipt, total=12, projection=projection)

    def domain(self, project, command):
        return self.driver.exchange("/api/call", command, self.scopes[project["value"]]["tokens"]["Governor"])

    def host(self, project, path, operation):
        return self.driver.exchange(path, {"project": project, "operation": operation}, self.scopes[project["value"]]["tokens"]["Collector"])

    def observe(self, name, members, operation):
        result = self.driver.observe(name, operation)
        self.driver.records[-1]["affectedMembers"] = members
        self.driver.save()
        return result

    def effects(self, project, members):
        return json.loads(self.driver.sql("""SELECT jsonb_build_object(
'changes', (SELECT count(*) FROM cq_changes WHERE project_id = :'project'::uuid),
'history', (SELECT count(*) FROM cq_history WHERE project_id = :'project'::uuid AND ledger = 'Tasks' AND number BETWEEN :first AND :last),
'revisions', (SELECT sum(revision) FROM cq_items WHERE project_id = :'project'::uuid AND ledger = 'Tasks' AND number BETWEEN :first AND :last));""",
            {"project": project["value"], "first": members[0]["id"]["number"], "last": members[-1]["id"]["number"]}))

    def create(self, project, count, title):
        members = []
        for offset in range(0, count, 64):
            result = self.domain(project, {"Change": {"input": {"project": project, "change": {"request": identity(), "mutations": [
                {"Create": {"draft": task(title)}} for _ in range(min(64, count - offset))], "fences": [], "reason": "Access fixture"}}}})
            members.extend(result["Changed"]["ack"]["items"])
        assert all(int(member["id"]["number"]) < 1000 for member in members), "Fixture allocations overlap unrelated seeds"
        return members

    def attempt(self, project, members, role, parent):
        assignment = {"id": identity(), "project": project, "members": members, "attribution": "Direct" if len(members) == 1 else "Shared",
            "cohort": None if len(members) == 1 else str(uuid.uuid4()), "evaluation": {"run": "access", "scenario": "bounded-work", "assessor": False}}
        self.host(project, "/api/usage", {"Assign": {"value": assignment}})
        attempt = {"id": identity(), "assignment": assignment["id"], "parent": parent, "session": self.scopes[project["value"]]["owner"]["session"],
            "role": role, "harness": "Codex", "provider": "fixture", "model": "fixture", "collector": "fixture", "startedAt": "1000"}
        self.host(project, "/api/usage", {"Start": {"value": attempt}})
        return attempt

    def integration(self, project, members):
        items = [self.domain(project, {"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": member}}}}})["Detail"]["view"]["item"] for member in members]
        refs = [{"id": item["id"], "revision": item["revision"]} for item in items]
        fence = self.domain(project, {"ClaimWork": {"input": {"project": project, "action": {"Acquire": {
            "id": identity(), "members": members, "durationMillis": "300000"}}}}})["Claimed"]["claim"]["fence"]
        parent = self.attempt(project, members, "Governor", None)
        worker = self.attempt(project, members, "Worker", parent["id"])
        reviewer = self.attempt(project, members, "Reviewer", parent["id"])
        request = {"request": identity(), "work": {"Worker": {"mode": "Implement"}}, "harness": "Codex", "members": refs,
            "guidance": [], "artifacts": [], "previous": None, "fence": fence,
            "limits": {"startupMillis": "3000", "executionMillis": "10000", "heartbeatMillis": "1000", "graceMillis": "300", "killMillis": "2000", "retainedOutputBytes": 262144}}
        base, candidate = {"value": "a" * 40}, {"value": "b" * 40}

        def publish(result):
            token = self.scopes[project["value"]]["tokens"]["Collector"]
            metadata = self.driver.exchange("/api/artifact", {"project": project, "id": identity(), "attempt": result["attempt"],
                "kind": "Result", "mediaType": "application/json", "body": json.dumps(result)}, token)
            admission = self.driver.exchange("/api/admission", {"project": project, "artifact": metadata["id"], "owner": self.scopes[project["value"]]["owner"]}, token)
            assert admission["decision"] == {"Accepted": {}}
            return metadata["id"]

        worker_handle = publish({"attempt": worker["id"], "request": request, "base": base, "candidate": candidate,
            "report": {"Work": {"members": [{"item": member, "disposition": "CandidateReady", "summary": "Access fixture", "evidence": []} for member in members]}}, "validation": [],
            "evidence": {"files": [], "omitted": []}})
        reviewer_handle = publish({"attempt": reviewer["id"], "request": {**request, "request": identity(), "work": {"Reviewer": {"mode": "Candidate"}}, "previous": worker_handle},
            "base": candidate, "candidate": candidate, "report": {"Review": {"proposal": None, "members": [{"item": member, "verdict": "Accepted", "findings": []} for member in members]}}, "validation": [], "evidence": {"files": [], "omitted": []}})
        operation = identity()
        repository, target = "/access-fixture", "refs/heads/integration"
        mutations = []
        for item in items:
            changed = copy.deepcopy(item["draft"])
            changed["content"]["Task"].update(status="Done", result=f"Integrated {candidate['value']} into {target}")
            changed["content"]["Task"]["validation"].append({"description": f"Host recorded integration {operation['value']} into {target}", "origin": "HostObserved",
                "citations": [{"Commit": {"repository": repository, "hash": candidate["value"]}}, {"Artifact": {"id": worker_handle}}, {"Artifact": {"id": reviewer_handle}}]})
            mutations.append({"Replace": {"id": item["id"], "expected": item["revision"], "draft": changed}})
        change = {"request": operation, "mutations": mutations, "fences": [fence], "reason": "Integrate reviewed candidate " + operation["value"]}
        return {"id": operation, "project": project, "owner": self.scopes[project["value"]]["owner"], "repository": repository, "target": target,
            "expected": base, "candidate": candidate, "worker": worker_handle, "reviewer": reviewer_handle, "checks": [], "fence": fence, "members": refs, "change": change}

    def upload(self, attempt, position, value, supersedes):
        return {"meter": "native", "disposition": "Contribution", "detailReason": None, "observation": {"id": identity(), "attempt": attempt,
            "source": "access-native", "position": str(position), "occurredAt": "1000", "receivedAt": "0", "scope": "Increment", "counters": counters(value),
            "inputIncludesCache": False, "outputIncludesReasoning": False, "cost": {"amount": {"value": "0.01"}, "currency": "USD", "basis": "ActualBilling", "pricingVersion": None},
            "completeness": "Complete", "gaps": [], "evidence": None, "supersedes": supersedes}}

    def seed_counts(self, project):
        tables = ["cq_integrations", "cq_integration_members", "cq_usage_assignments", "cq_usage_members", "cq_usage_attempts",
            "cq_usage_meters", "cq_usage_costs", "cq_usage_records", "cq_usage_heads"]
        query = "SELECT jsonb_build_object(" + ",".join("'" + table + "',(SELECT count(*) FROM " + table + " WHERE project_id = :'project'::uuid)" for table in tables) + ");"
        return json.loads(self.driver.sql(query, {"project": project["value"]}))

    def seed(self, project, lower, upper):
        before = self.seed_counts(project)
        scope = self.scopes[project["value"]]
        self.driver.sql("""
BEGIN;
CREATE TEMP TABLE access_identities ON COMMIT DROP AS SELECT n, gen_random_uuid() assignment, gen_random_uuid() attempt,
    gen_random_uuid() observation, gen_random_uuid() integration, gen_random_uuid() pending, gen_random_uuid() detail
FROM generate_series(:lower, :upper) n;
INSERT INTO cq_integrations
SELECT project_id, i.integration, replace(body::text, :'integration', i.integration::text)::jsonb,
       replace(hold::text, :'integration', i.integration::text)::jsonb
FROM cq_integrations t CROSS JOIN access_identities i WHERE t.project_id = :'project'::uuid AND t.integration_id = :'integration'::uuid;
UPDATE cq_claims c SET released = false, expires_at = (t.body->>'reservedAt')::bigint + 1,
    body = c.body || jsonb_build_object('released', false, 'expiresAt', ((t.body->>'reservedAt')::bigint + 1)::text, 'owner', t.body#>'{intent,owner}')
FROM cq_integrations t, access_identities i
WHERE c.project_id = :'project'::uuid AND c.generation = (i.n + 1000) * 100 AND t.project_id = c.project_id AND t.integration_id = :'integration'::uuid;
INSERT INTO cq_integrations
SELECT t.project_id, i.pending,
    jsonb_set(jsonb_set(replace(replace(t.body::text, :'integration', i.pending::text), '"number": "3"', '"number": "' || (i.n + 1000)::text || '"')::jsonb,
        '{intent,fence}', c.body->'fence'), '{resolution}', '{"Pending":{}}'::jsonb) || jsonb_build_object('resolvedAt', NULL),
    replace(replace(t.hold::text, :'integration', i.pending::text), '"number": "3"', '"number": "' || (i.n + 1000)::text || '"')::jsonb
FROM cq_integrations t CROSS JOIN access_identities i JOIN cq_claims c ON c.project_id = :'project'::uuid AND c.generation = (i.n + 1000) * 100
WHERE t.project_id = :'project'::uuid AND t.integration_id = :'integration'::uuid;
INSERT INTO cq_integration_members SELECT :'project'::uuid, 'Tasks', n + 1000, pending FROM access_identities;
INSERT INTO cq_usage_assignments
SELECT project_id, i.assignment, attribution, cohort, evaluation_run, evaluation_scenario, actor, received_at,
    body || jsonb_build_object('id', jsonb_build_object('value', i.assignment::text), 'members', jsonb_build_array(
        jsonb_build_object('project', jsonb_build_object('value', :'project'), 'ledger', 'Tasks', 'number', (i.n + 1000)::text)))
FROM cq_usage_assignments t CROSS JOIN access_identities i WHERE t.project_id = :'project'::uuid AND t.assignment_id = :'assignment'::uuid;
INSERT INTO cq_usage_members SELECT :'project'::uuid, assignment, 'Tasks', n + 1000 FROM access_identities;
INSERT INTO cq_usage_attempts
SELECT project_id, i.attempt, i.assignment, parent_id, effective_outcome, session_id, actor, received_at,
    body || jsonb_build_object('id', jsonb_build_object('value', i.attempt::text), 'assignment', jsonb_build_object('value', i.assignment::text))
FROM cq_usage_attempts t CROSS JOIN access_identities i WHERE t.project_id = :'project'::uuid AND t.attempt_id = :'attempt'::uuid;
INSERT INTO cq_usage_meters
SELECT project_id, i.attempt, meter, actor, received_at, jsonb_set(body, '{attempt,value}', to_jsonb(i.attempt::text)), :'projection'::jsonb
FROM cq_usage_meters t CROSS JOIN access_identities i WHERE t.project_id = :'project'::uuid AND t.attempt_id = :'attempt'::uuid;
INSERT INTO cq_usage_costs
SELECT project_id, i.attempt, meter, currency, basis, pricing_version, :'cost'::numeric, 1
FROM cq_usage_costs t CROSS JOIN access_identities i WHERE t.project_id = :'project'::uuid AND t.attempt_id = :'attempt'::uuid;
UPDATE cq_usage_clock SET cursor = cursor + 3 * (:upper - :lower + 1) WHERE project_id = :'project'::uuid;
INSERT INTO cq_usage_records
SELECT t.project_id, i.observation, c.cursor + i.n - :lower + 1, i.attempt, meter, source, position,
    jsonb_set(jsonb_set(jsonb_set(body, '{upload,observation,id,value}', to_jsonb(i.observation::text)), '{upload,observation,attempt,value}', to_jsonb(i.attempt::text)),
        '{sequence}', to_jsonb((c.cursor + i.n - :lower + 1)::text))
FROM cq_usage_records t JOIN cq_usage_clock c USING(project_id) CROSS JOIN access_identities i
WHERE t.project_id = :'project'::uuid AND t.observation_id = :'observation'::uuid;
INSERT INTO cq_usage_heads SELECT :'project'::uuid, attempt, 'native', 1, observation FROM access_identities;
UPDATE cq_usage_clock SET cursor = cursor + :upper - :lower + 1 WHERE project_id = :'project'::uuid;
INSERT INTO cq_usage_records
SELECT t.project_id, i.detail, c.cursor + i.n - :lower + 1, t.attempt_id, meter, 'retained-history', i.n + 1000,
    jsonb_set(body, '{upload}', (body->'upload') || jsonb_build_object('disposition', 'Detail', 'detailReason', 'Retained non-contributing source evidence',
      'observation', (body#>'{upload,observation}') || jsonb_build_object('id', jsonb_build_object('value', i.detail::text), 'source', 'retained-history', 'position', (i.n + 1000)::text)))
      || jsonb_build_object('sequence', (c.cursor + i.n - :lower + 1)::text)
FROM cq_usage_records t JOIN cq_usage_clock c USING(project_id) CROSS JOIN access_identities i
WHERE t.project_id = :'project'::uuid AND t.observation_id = :'observation'::uuid;
UPDATE cq_usage_clock SET cursor = cursor + :upper - :lower + 1 WHERE project_id = :'project'::uuid;
COMMIT;
""", {"project": project["value"], "lower": str(lower), "upper": str(upper), "integration": scope["integration"]["intent"]["id"]["value"],
            "assignment": scope["attempt"]["assignment"]["value"], "attempt": scope["attempt"]["id"]["value"], "observation": scope["receipt"]["id"]["value"],
            "projection": scope["projection"], "cost": scope["sample"]["observation"]["cost"]["amount"]["value"]})
        after = self.seed_counts(project)
        for table, initial in before.items():
            multiplier = 2 if table in ["cq_integrations", "cq_usage_records"] else 1
            assert after[table] == initial + multiplier * (upper - lower + 1), (table, initial, after[table])
        assert after["cq_integration_members"] == upper
        consistent = self.driver.sql("""SELECT count(*) FROM cq_integration_members m JOIN cq_integrations i USING(project_id, integration_id)
JOIN cq_claim_members cm ON cm.project_id = m.project_id AND cm.ledger = m.ledger AND cm.number = m.item_number
JOIN cq_claims c ON c.project_id = cm.project_id AND c.claim_id = cm.claim_id
WHERE m.project_id = :'project'::uuid AND i.body->'resolution' = '{"Pending":{}}'::jsonb
AND i.body#>>'{intent,id,value}' = i.integration_id::text AND i.hold->>'id' = (i.body#>'{intent,id}')::text
AND i.hold->'members' = i.body#>'{intent,members}' AND i.body#>'{intent,fence}' = c.body->'fence'
AND (i.hold#>>'{members,0,id,number}')::bigint = m.item_number
AND NOT c.released AND c.expires_at > (i.body->>'reservedAt')::bigint;""", {"project": project["value"]})
        assert consistent == str(upper), (upper, consistent)
        self.driver.seeds.append({"project": project, "unrelatedRows": upper, "counts": after, "consistentPendingMemberships": int(consistent)})
        (self.driver.evidence / "access-seeds.json").write_text(json.dumps(self.driver.seeds, indent=2) + "\n")

    def measure(self, size, project):
        for count in GRAPH_SIZES:
            members = self.create(project, count, "Affected descendant")
            root = members[0]
            for child in members[1:]:
                ack = self.domain(project, {"Change": {"input": {"project": project, "change": {"request": identity(), "mutations": [{"Reference": {
                    "source": root["id"], "expectedSource": root["revision"], "relation": "Produces", "target": child["id"], "expectedTarget": child["revision"], "present": True}}],
                    "fences": [], "reason": "Affected closure"}}}})["Changed"]["ack"]
                root = next(member for member in ack["items"] if member["id"] == root["id"])
            prefix = f"{size}-closure-{count}"
            graph = self.observe(prefix + "-graph", count, lambda: self.domain(project, {"Graph": {"input": {"project": project,
                "roots": [root["id"]], "after": None, "snapshot": None, "limit": 200}}}))["Workset"]["page"]
            assert graph["selectedCount"] == count and not graph["hasMore"] and len(graph["entries"]) == count
            assert "ACCESS_NARRATIVE" not in json.dumps(graph)
            preview = self.observe(prefix + "-preview", count, lambda: self.domain(project, {"Read": {"input": {"project": project,
                "selection": {"Termination": {"roots": [root["id"]], "intent": "Cancel"}}}}}))["Termination"]["preview"]
            assert preview["plan"]["canApply"] and len(preview["plan"]["entries"]) == count and "ACCESS_NARRATIVE" not in json.dumps(preview)
            change = {"Change": {"input": {"project": project, "change": {"request": identity(), "mutations": [{"Terminate": {
                "roots": [root["id"]], "intent": "Cancel", "snapshot": preview["snapshot"]}}], "fences": [], "reason": "Measured termination"}}}}
            before = self.effects(project, members)
            ack = self.observe(prefix + "-apply", count, lambda: self.domain(project, change))["Changed"]["ack"]
            assert len(ack["items"]) == count
            expected = {key: value + (1 if key == "changes" else count) for key, value in before.items()}
            assert self.effects(project, members) == expected
            assert self.observe(prefix + "-retry", count, lambda: self.domain(project, change))["Changed"]["ack"] == ack
            assert self.effects(project, members) == expected
            stored = self.driver.sql("SELECT count(*), count(*) FILTER (WHERE status = 'cancelled') FROM cq_items WHERE project_id = :'project'::uuid AND ledger = 'Tasks' AND number BETWEEN :first AND :last;",
                {"project": project["value"], "first": members[0]["id"]["number"], "last": members[-1]["id"]["number"]})
            assert stored == f"{count}|{count}", stored
        for count in INTEGRATION_SIZES:
            members = self.create(project, count, "Reviewed integration")
            intent = self.integration(project, [member["id"] for member in members])
            prefix = f"{size}-integration-{count}"
            before = self.effects(project, members)
            reserved = self.observe(prefix + "-reserve", count, lambda: self.host(project, "/api/integration", {"Reserve": {"intent": intent}}))
            assert reserved["resolution"] == {"Pending": {}}
            assert self.effects(project, members) == before
            recorded = self.observe(prefix + "-record", count, lambda: self.host(project, "/api/integration", {"Observe": {"id": intent["id"], "observation": {"Incorporated": {"target": intent["candidate"]}}}}))
            assert "Recorded" in recorded["resolution"]
            expected = {key: value + (1 if key == "changes" else count) for key, value in before.items()}
            assert self.effects(project, members) == expected
            assert self.observe(prefix + "-retry", count, lambda: self.host(project, "/api/integration", {"Reserve": {"intent": intent}})) == recorded
            assert self.observe(prefix + "-read", count, lambda: self.domain(project, {"Read": {"input": {"project": project, "selection": {"Integration": {"id": intent["id"]}}}}}))["Integration"]["record"] == recorded
            assert self.effects(project, members) == expected
            self.domain(project, {"ClaimWork": {"input": {"project": project, "action": {"Release": {"fence": intent["fence"]}}}}})
        scope = self.scopes[project["value"]]
        fresh = self.upload(scope["attempt"]["id"], size + 10, 10, None)
        receipt = self.observe(f"{size}-usage-insert", 1, lambda: self.host(project, "/api/usage", {"Ingest": {"value": fresh}}))
        assert self.observe(f"{size}-usage-retry", 1, lambda: self.host(project, "/api/usage", {"Ingest": {"value": fresh}})) == receipt
        old = scope["sample"]["observation"]
        corrected = self.upload(scope["attempt"]["id"], 1, int(old["counters"]["input"]["value"]) + 1, old["id"])
        self.observe(f"{size}-usage-correct", 1, lambda: self.host(project, "/api/usage", {"Ingest": {"value": corrected}}))
        scope["sample"], scope["total"] = corrected, scope["total"] + 13
        selection = {"Summary": {"filter": {"TaskOnly": {"item": {"project": project, "ledger": "Tasks", "number": "3"}}}}}
        report = self.observe(f"{size}-usage-summary", 1, lambda: self.domain(project, {"Usage": {"input": {"project": project, "selection": selection}}}))["UsageSummary"]["report"]
        assert report["direct"]["total"]["known"] == str(scope["total"]), report
