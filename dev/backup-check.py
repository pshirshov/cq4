"""Behavioral Active Effectual / PostgreSQL: settled-state backup and native restore."""
import hashlib
import json
from pathlib import Path
import runpy
import urllib.parse
import urllib.request
import uuid


def verify(checks, command):
    support = runpy.run_path(str(Path(__file__).with_name("consumer-eval")))
    call, write = support["call"], support["write"]
    original = checks.environment["CQ_DATABASE_URL"]
    parsed = urllib.parse.urlsplit(original.removeprefix("jdbc:"))
    assert not parsed.query, "Backup verification requires an isolated cluster"
    connection = ["--host", parsed.hostname, "--port", str(parsed.port), "--username", checks.environment["CQ_DATABASE_USER"]]
    names = ["cq_backup_" + uuid.uuid4().hex, "cq_restore_" + uuid.uuid4().hex]
    created = []
    project = None

    def sql(database, statement, name):
        output = checks.run(["psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--tuples-only", "--no-align",
                            *connection, "--dbname", database, "--command", statement], name, 60)
        return output.strip()

    def fingerprint(database, phase):
        tables = json.loads(sql(database, "SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname='public'", phase + "-tables"))
        sequences = json.loads(sql(database, "SELECT coalesce(json_agg(sequencename ORDER BY sequencename),'[]') FROM pg_sequences WHERE schemaname='public'", phase + "-sequences"))
        result = {"tables": {}, "sequences": {}}
        for name in tables:
            quoted = '"' + name.replace('"', '""') + '"'
            rows = json.loads(sql(database, f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM public.{quoted} t", phase + "-" + name))
            result["tables"][name] = {"rows": len(rows), "sha256": hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()}
        for name in sequences:
            quoted = '"' + name.replace('"', '""') + '"'
            result["sequences"][name] = json.loads(sql(database, f"SELECT json_build_object('lastValue',last_value,'isCalled',is_called) FROM public.{quoted}", phase + "-" + name))
        write(checks.evidence / (phase + ".json"), result)
        return result

    def read(selection):
        return call(checks.environment, {"Read": {"input": {"project": project, "selection": selection}}})

    def usage(selection):
        return call(checks.environment, {"Usage": {"input": {"project": project, "selection": selection}}})

    def snapshot(state):
        page = call(checks.environment, {"Search": {"input": {"project": project, "query": "archived:true OR archived:false",
                    "after": None, "snapshot": None, "limit": 100}}})["Found"]["page"]
        assert not page["hasMore"] and len(page["items"]) == 9
        items = sorted(page["items"], key=lambda item: int(item["id"]["number"]))
        claims = read({"Claims": {"members": [item["id"] for item in items]}})["Claims"]["preview"]
        assert not claims["claims"] and not claims["integrations"], "Backup has unresolved ownership"
        summary = usage({"Summary": {"filter": {"ProjectAll": {}}}})
        assert int(summary["UsageSummary"]["report"]["attempts"]["running"]) == 0
        result = {"details": [read({"ItemDetail": {"id": item["id"]}}) for item in items],
                  "histories": [read({"History": {"id": item["id"], "before": {"value": "9223372036854775807"}, "limit": 100}}) for item in items],
                  "usage": summary, "audit": usage({"Audit": {"filter": {"ProjectAll": {}}, "after": "0", "limit": 100}}),
                  "attempts": usage({"Attempts": {"filter": {"ProjectAll": {}}, "after": None, "snapshot": None, "limit": 100}}),
                  "artifact": read({"ArtifactText": {"id": state["artifact"]["id"], "offset": 0, "limit": 8192}})}
        assert result["artifact"]["ArtifactText"]["page"]["text"] == state["artifact"]["body"]
        assert result["details"][-1]["Detail"]["view"]["item"]["draft"]["archived"]
        return result

    try:
        for name in names:
            sql(parsed.path[1:], f'CREATE DATABASE "{name}"', "create-" + name)
            created.append(name)
        checks.environment["CQ_DATABASE_URL"] = f"jdbc:postgresql://{parsed.hostname}:{parsed.port}/{names[0]}"
        fixture = checks.evidence / "backup-fixture.json"
        with checks.server(command, "backup-seed"):
            checks.run(["node", "dev/restart.mjs", "seed", str(fixture)], "backup-seed", 60)
            state = json.loads(fixture.read_text())
            project = state["project"]
            call(checks.environment, {"ClaimWork": {"input": {"project": project, "action": {"Release": {"fence": state["claim"]["Claimed"]["claim"]["fence"]}}}}})
            outcome = {"request": {"value": str(uuid.uuid4())}, "attempt": state["upload"]["observation"]["attempt"],
                       "state": "Completed", "finishedAt": "3000", "gaps": [], "supersedes": None}
            request = urllib.request.Request(checks.environment["CQ_ORIGIN"] + "/api/usage", data=json.dumps({"project": project, "operation": {"Finish": {"value": outcome}}}).encode(),
                headers={"Authorization": "Bearer " + checks.environment["CQ_TOKEN"], "CQ-Session": checks.environment["CQ_SESSION"], "Content-Type": "application/json", "CQ-Protocol-Version": "0.1.0"})
            with urllib.request.urlopen(request, timeout=15) as response:
                assert response.status == 200
            item = read({"ItemDetail": {"id": state["source"]["id"]}})["Detail"]["view"]["item"]
            item["draft"]["content"]["Task"]["status"] = "Done"
            item["draft"]["archived"] = True
            call(checks.environment, {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())},
                "reason": "Retain archived item across backup", "fences": [], "mutations": [{"Replace": {"id": item["id"], "expected": item["revision"], "draft": item["draft"]}}]}}}})
            before = snapshot(state)
            write(checks.evidence / "api-before.json", before)
            support["export_meters"](checks.environment, checks.evidence, project, {"value": checks.environment["CQ_SESSION"]})
            meters = json.loads((checks.evidence / "usage-meters.json").read_text())["entries"]
            assert len(meters) == 1 and meters[0]["projection"]["totals"] == before["usage"]["UsageSummary"]["report"]["direct"]
        before_database = fingerprint(names[0], "database-before")
        dump = checks.evidence / "cq-database.dump"
        checks.run(["pg_dump", *connection, "--dbname", names[0], "--format=custom", "--file", str(dump)], "backup-export", 60)
        checks.run(["pg_restore", *connection, "--dbname", names[1], "--exit-on-error", "--no-owner", "--no-privileges", str(dump)], "backup-import", 60)
        assert fingerprint(names[1], "database-restored") == before_database, "Restore changed table contents or sequence positions"
        checks.environment["CQ_DATABASE_URL"] = f"jdbc:postgresql://{parsed.hostname}:{parsed.port}/{names[1]}"
        with checks.server(command, "backup-restored"):
            after = snapshot(state)
            write(checks.evidence / "api-after.json", after)
            assert before == after, "Restored native reads changed history, archived state, audit or artifact"
            assert call(checks.environment, state["operation"]) == state["ack"], "Restore lost idempotent acknowledgement"
            operation = state["operation"]["Change"]["input"]
            operation["change"]["request"] = {"value": str(uuid.uuid4())}
            created_item = call(checks.environment, {"Change": {"input": operation}})["Changed"]["ack"]["items"][0]
            assert created_item["id"]["number"] == "10", "Restore lost the project ledger counter"
        result = {"status": "passed", "scope": "Settled database-only backup; no supervisor journal or unresolved integration recovery claim",
                  "dumpSha256": hashlib.sha256(dump.read_bytes()).hexdigest(), "tables": len(before_database["tables"]),
                  "sequences": len(before_database["sequences"]), "apiEqual": True, "counterContinued": True, "idempotencyPreserved": True}
        write(checks.evidence / "result.json", result)
        return result
    finally:
        checks.environment["CQ_DATABASE_URL"] = original
        for name in reversed(created):
            sql(parsed.path[1:], f'DROP DATABASE "{name}"', "drop-" + name)
