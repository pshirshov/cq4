"""Behavioral Active Good Communication: actual project archive CLI/HTTP/PostgreSQL."""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import struct
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile


def endpoint_arguments(environment):
    # A saved project.json above the working directory would otherwise win over CQ_ORIGIN (D111).
    return ["--endpoint", environment["CQ_ORIGIN"]]


def run_cli(command, args, environment, cwd, timeout=60):
    return subprocess.run([*command, *args, *endpoint_arguments(environment)], env=environment, cwd=cwd, text=True, capture_output=True, timeout=timeout)


def start_backup(command, project, destination, environment, cwd, stdout, stderr):
    return subprocess.Popen([*command, "backup", project, str(destination), "--json", *endpoint_arguments(environment)],
                            cwd=cwd, env=environment, stdout=stdout, stderr=stderr)


def verify(checks, command, name):
    support = runpy.run_path(str(Path(__file__).with_name("consumer-eval")))
    call, write = support["call"], support["write"]
    original = checks.environment["CQ_DATABASE_URL"]
    parsed = urllib.parse.urlsplit(original.removeprefix("jdbc:"))
    connection = ["--host", parsed.hostname, "--port", str(parsed.port), "--username", checks.environment["CQ_DATABASE_USER"]]
    names = ["cq_archive_" + uuid.uuid4().hex, "cq_restore_" + uuid.uuid4().hex, "cq_uncertain_" + uuid.uuid4().hex]
    out = checks.evidence / f"{name}-project-archives"
    out.mkdir()
    project = None
    commands = []

    def sql(database, text):
        result = subprocess.run(["psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--tuples-only", "--no-align",
                                 *connection, "--dbname", database, "--command", text], env=checks.environment, text=True, capture_output=True, timeout=30)
        assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    def cli(label, args, expected=0):
        result = run_cli(command, args, checks.environment, out)
        (out / (label + ".stdout")).write_text(result.stdout)
        (out / (label + ".stderr")).write_text(result.stderr)
        commands.append({"label": label, "args": args, "exit": result.returncode})
        write(out / "commands.json", commands)
        assert result.returncode == expected, (label, result.stdout, result.stderr)
        return result

    def post(path, body, token=None):
        payload = body if isinstance(body, bytes) else json.dumps(body).encode()
        request = urllib.request.Request(checks.environment["CQ_ORIGIN"] + path, data=payload,
            headers={"Authorization": "Bearer " + (token or checks.environment["CQ_TOKEN"]), "CQ-Session": checks.environment["CQ_SESSION"],
                     "Content-Type": "application/json", "CQ-Protocol-Version": "0.1.0"})
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as response:
            return response.code, json.load(response)

    def fingerprint(database):
        tables = json.loads(sql(database, "SELECT json_agg(table_name ORDER BY table_name) FROM information_schema.columns WHERE table_schema='public' AND column_name='project_id'"))
        return {table: json.loads(sql(database, f"SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb) FROM {table} t WHERE project_id='{project['value']}'")) for table in tables}

    def modified(name, mutate):
        with zipfile.ZipFile(archive) as zipped:
            entries = [(info.filename, zipped.read(info)) for info in zipped.infolist()]
        manifest = json.loads(entries[0][1])
        mutate(manifest, entries)
        entries[0] = ("manifest.json", json.dumps(manifest).encode())
        target = out / (name + ".cqbackup")
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as zipped:
            for path, body in entries:
                zipped.writestr(path, body)
        return target

    created = []
    try:
        for database in names:
            sql(parsed.path[1:], f'CREATE DATABASE "{database}"')
            created.append(database)
        checks.environment["CQ_DATABASE_URL"] = f"jdbc:postgresql://{parsed.hostname}:{parsed.port}/{names[0]}"
        fixture = out / "fixture.json"
        archive = out / "project.cqbackup"
        with checks.server(command, f"{name}-archive-source"):
            checks.run(["node", "dev/restart.mjs", "seed", str(fixture)], f"{name}-archive-seed", 60)
            state = json.loads(fixture.read_text()); project = state["project"]
            args = ["backup", project["value"], str(archive)]
            failure = cli("active-claim", args, 1)
            assert "active claims" in failure.stderr and not archive.exists()
            call(checks.environment, {"ClaimWork": {"input": {"project": project, "action": {"Release": {"fence": state["claim"]["Claimed"]["claim"]["fence"]}}}}})
            failure = cli("running-attempt", args, 1)
            assert "running attempts" in failure.stderr
            outcome = {"request": {"value": str(uuid.uuid4())}, "attempt": state["upload"]["observation"]["attempt"],
                       "state": "Completed", "finishedAt": "3000", "gaps": [], "supersedes": None}
            assert post("/api/usage", {"project": project, "operation": {"Finish": {"value": outcome}}})[0] == 200
            attempt = call(checks.environment, {"Usage": {"input": {"project": project, "selection": {"Attempts": {
                "filter": {"ProjectAll": {}}, "after": None, "snapshot": None, "limit": 10}}}}})["UsageAttempts"]["page"]["entries"][0]["attempt"]
            span = {"id": {"value": str(uuid.uuid4())}, "assignment": attempt["assignment"], "session": attempt["session"], "phase": "Check",
                    "startedAt": "3000", "finishedAt": "3700", "state": "Failed"}
            assert post("/api/usage", {"project": project, "operation": {"Span": {"value": span}}}) == (200, {"Spanned": {"value": span}})
            pending_id = str(uuid.uuid4())
            sql(names[0], f"INSERT INTO cq_integrations(project_id,integration_id,body,hold) VALUES ('{project['value']}','{pending_id}', '{{\"resolution\":{{\"Pending\":{{}}}}}}'::jsonb, '{{}}'::jsonb)")
            failure = cli("pending-integration", args, 1)
            assert "pending integrations" in failure.stderr
            sql(names[0], f"DELETE FROM cq_integrations WHERE integration_id='{pending_id}'")
            archived = call(checks.environment, {"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": state["target"]["id"]}}}}})["Detail"]["view"]["item"]
            archived["draft"]["archived"] = True
            archived["draft"]["content"]["Task"]["status"] = "Done"
            archived["draft"]["labels"] = ["retained label"]
            call(checks.environment, {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())}, "reason": "Archive coverage",
                "fences": [], "mutations": [{"Replace": {"id": archived["id"], "expected": archived["revision"], "draft": archived["draft"]}}]}}}})
            priced = copy.deepcopy(state["upload"])
            priced["observation"].update(id={"value": str(uuid.uuid4())}, position="2",
                cost={"amount": {"value": "0.125"}, "currency": "USD", "basis": "ProviderEstimate", "pricingVersion": "archive-fixture"})
            priced_response = post("/api/usage", {"project": project, "operation": {"Ingest": {"value": priced}}})
            write(out / "priced-response.json", priced_response)
            assert priced_response[0] == 200, priced_response
            stored = call(checks.environment, {"Workset": {"input": {"project": project, "action": {"Create": {"targets": [state["target"]["id"]], "through": "Work"}}}}})
            assert stored["WorksetStored"]["workset"]["targets"] == [state["target"]["id"]], stored
            standing = call(checks.environment, {"Requirements": {"input": {"project": project, "action": {"Replace": {"expected": {"value": "0"}, "text": "Archived standing requirement"}}}}})
            assert standing["Requirements"]["value"]["revision"] == {"value": "1"}, standing
            moded = call(checks.environment, {"Mode": {"input": {"project": project, "action": {"Replace": {"expected": {"value": "0"}, "mode": "CrossCutting", "selfReviewWithoutChecks": False}}}}})
            assert moded["Mode"]["value"]["revision"] == {"value": "1"} and moded["Mode"]["value"]["mode"] == "CrossCutting", moded
            other = {"value": str(uuid.uuid4())}
            call(checks.environment, {"Initialize": {"config": {"project": other, "endpoint": checks.environment["CQ_ORIGIN"], "name": "Excluded project"}}})
            before = fingerprint(names[0]); write(out / "before.json", before)
            phases = call(checks.environment, {"Usage": {"input": {"project": project, "selection": {"Phases": {"filter": {"ProjectAll": {}}}}}}})
            assert [(value["spans"], value["wallMillis"]) for value in phases["UsagePhases"]["report"]["phases"] if value["phase"] == "Check"] == [("1", "700")], phases
            assert len(before["cq_usage_spans"]) == 1 and before["cq_usage_spans"][0]["span_id"] == span["id"]["value"], before["cq_usage_spans"]
            manifest = json.loads(cli("backup", args + ["--json"]).stdout)
            assert len(manifest["entries"]) == len(before) == 27 and len(before["cq_worksets"]) == 1
            assert sorted((row["body"] for row in before["cq_project_settings"]), key=json.dumps) == [
                {"Mode": {"value": "CrossCutting", "selfReviewWithoutChecks": False}}, {"Requirements": {"text": "Archived standing requirement"}}], before["cq_project_settings"]
            assert archive.stat().st_mode & 0o077 == 0, "Archive must not expose operator data to other users"
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            assert "already exists" in cli("no-clobber", args, 1).stderr
            assert hashlib.sha256(archive.read_bytes()).hexdigest() == digest
            assert manifest["project"] == project
            with zipfile.ZipFile(archive) as zipped:
                assert set(before) == {name.removesuffix(".copy") for name in zipped.namelist() if name != "manifest.json"}

            # Hold a later COPY while a ledger edit commits: every archived table must retain the earlier snapshot.
            locker = subprocess.Popen(["psql", "--no-psqlrc", "--quiet", "--tuples-only", "--no-align", *connection, "--dbname", names[0]],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=checks.environment, text=True)
            simultaneous = None
            try:
                locker.stdin.write("BEGIN; LOCK TABLE cq_artifacts IN ACCESS EXCLUSIVE MODE; SELECT 'locked';\n"); locker.stdin.flush()
                assert locker.stdout.readline().strip() == "locked"
                with (out / "concurrent.stdout").open("w") as stdout, (out / "concurrent.stderr").open("w") as stderr:
                    concurrent = out / "concurrent.cqbackup"
                    simultaneous = start_backup(command, project["value"], concurrent, checks.environment, out, stdout, stderr)
                    deadline = time.monotonic() + 20
                    while sql(names[0], "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'COPY (SELECT%FROM cq_artifacts%' AND pid <> pg_backend_pid()") == "0":
                        assert time.monotonic() < deadline and simultaneous.poll() is None, "Backup did not reach controlled snapshot boundary"
                        time.sleep(0.05)
                    item = call(checks.environment, {"Read": {"input": {"project": project, "selection": {"ItemDetail": {"id": state["source"]["id"]}}}}})["Detail"]["view"]["item"]
                    item["draft"]["title"] = "Changed during backup"
                    item["draft"]["archived"] = True
                    item["draft"]["content"]["Task"]["status"] = "Done"
                    call(checks.environment, {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())}, "reason": "Snapshot isolation",
                        "fences": [], "mutations": [{"Replace": {"id": item["id"], "expected": item["revision"], "draft": item["draft"]}}]}}}})
                    locker.stdin.write("COMMIT;\n\\q\n"); locker.stdin.flush()
                    assert simultaneous.wait(timeout=30) == 0
                    with zipfile.ZipFile(archive) as first, zipfile.ZipFile(concurrent) as second:
                        assert all(first.read(name) == second.read(name) for name in first.namelist() if name != "manifest.json"), "Backup mixed snapshots"
            finally:
                if simultaneous is not None and simultaneous.poll() is None:
                    simultaneous.kill(); simultaneous.wait()
                if locker.poll() is None:
                    locker.stdin.close(); locker.wait(timeout=10)

            grant = {"project": project, "actor": {"subject": "archive-denial", "session": {"value": str(uuid.uuid4())}, "role": "Governor"},
                     "expiresAt": str(int(time.time() * 1000) + 60000)}
            scoped = post("/api/grant", grant)[1]["value"]
            assert post("/api/restore", b"not an archive", scoped)[0] == 401
            request = urllib.request.Request(checks.environment["CQ_ORIGIN"] + "/api/backup/" + project["value"], headers={"Authorization": "Bearer " + scoped, "CQ-Protocol-Version": "0.1.0"})
            try:
                urllib.request.urlopen(request, timeout=10)
                raise AssertionError("Scoped backup was allowed")
            except urllib.error.HTTPError as response:
                assert response.code == 401

        checks.environment["CQ_DATABASE_URL"] = f"jdbc:postgresql://{parsed.hostname}:{parsed.port}/{names[1]}"
        with checks.server(command, f"{name}-archive-target"):
            def reject(name, mutate, expected):
                target = modified(name, mutate)
                result = cli(name, ["restore", str(target)], 1)
                assert expected in result.stderr, result.stderr
                assert sql(names[1], "SELECT count(*) FROM cq_projects") == "0", "Failed restore left a project behind"

            def nonterminal_current(m, entries):
                index = next(i for i, (name, _) in enumerate(entries) if name == "cq_items.copy")
                data = entries[index][1]; output = bytearray(data[:19]); offset = 19; changed = 0
                while True:
                    fields = struct.unpack_from(">h", data, offset)[0]; offset += 2
                    output.extend(struct.pack(">h", fields))
                    if fields == -1:
                        break
                    for _ in range(fields):
                        length = struct.unpack_from(">i", data, offset)[0]; offset += 4
                        value = data[offset:offset + max(length, 0)]; offset += max(length, 0)
                        if value.startswith(b'\x01{'):
                            document = json.loads(value[1:])
                            if "draft" in document and document["draft"]["archived"]:
                                document["draft"]["content"]["Task"]["status"] = "Ready"
                                value = b'\x01' + json.dumps(document).encode(); changed += 1; length = len(value)
                        output.extend(struct.pack(">i", length)); output.extend(value)
                assert changed == 1 and offset == len(data)
                entries[index] = (entries[index][0], bytes(output))
                m["entries"][index - 1].update(bytes=str(len(output)), sha256=hashlib.sha256(output).hexdigest())
            reject("nonterminal-current", nonterminal_current, "Only terminal or settled items may be archived")

            reject("schema-mismatch", lambda m, e: m.update(schemaSha256="0" * 64), "current CQ schema")
            reject("major-mismatch", lambda m, e: m.update(postgresMajor=0), "PostgreSQL major")
            reject("checksum-mismatch", lambda m, e: m["entries"][-1].update(sha256="0" * 64), "integrity check")
            reject("count-mismatch", lambda m, e: m["entries"][0].update(rows="2"), "integrity check")
            reject("missing-table", lambda m, e: e.pop(), "Missing or unexpected")
            reject("extra-table", lambda m, e: e.append(("extra.copy", b"")), "extra archive entry")
            reject("traversal", lambda m, e: e.__setitem__(1, ("../outside", e[1][1])), "Missing or unexpected")
            reject("declared-oversize", lambda m, e: m["entries"][0].update(bytes=str(513 * 1024 * 1024)), "Invalid archive entry")
            def foreign(m, entries):
                data = entries[1][1].replace(uuid.UUID(project["value"]).bytes, uuid.uuid4().bytes)
                entries[1] = (entries[1][0], data)
                m["entries"][0]["sha256"] = hashlib.sha256(data).hexdigest()
            reject("foreign-project", foreign, "another project's data")
            def invalid_edge(m, entries):
                index = next(i for i, (name, _) in enumerate(entries) if name == "cq_edges.copy")
                data = bytearray(entries[index][1])
                offset = 19
                fields = struct.unpack_from(">h", data, offset)[0]; offset += 2
                assert fields == 6
                for field in range(fields):
                    length = struct.unpack_from(">i", data, offset)[0]; offset += 4
                    if field == 5:
                        assert length == 8
                        struct.pack_into(">q", data, offset, 999999)
                    offset += max(length, 0)
                entries[index] = (entries[index][0], bytes(data))
                m["entries"][index - 1]["sha256"] = hashlib.sha256(data).hexdigest()
            reject("foreign-key-rollback", invalid_edge, "23503")
            def active_attempt(m, entries):
                index = next(i for i, (name, _) in enumerate(entries) if name == "cq_usage_attempts.copy")
                data = bytearray(entries[index][1]); offset = 19
                fields = struct.unpack_from(">h", data, offset)[0]; offset += 2
                assert fields == 9
                for field in range(fields):
                    length = struct.unpack_from(">i", data, offset)[0]
                    if field == 4:
                        assert length > 0
                        data[offset:offset + 4 + length] = struct.pack(">i", -1)
                        break
                    offset += 4 + max(length, 0)
                entries[index] = (entries[index][0], bytes(data))
                m["entries"][index - 1].update(bytes=str(len(data)), sha256=hashlib.sha256(data).hexdigest())
            reject("restore-active-attempt", active_attempt, "running attempts")
            restored = json.loads(cli("restore", ["restore", str(archive), "--json"]).stdout)
            assert restored == manifest
            after = fingerprint(names[1]); write(out / "after.json", after)
            assert after == before, "Project archive changed stored records or projections"
            assert sql(names[1], "SELECT cursor FROM cq_catalogue_clock") == "1", "Restore did not invalidate project catalogue"
            assert "already exists" in cli("collision", ["restore", str(archive)], 1).stderr
            assert fingerprint(names[1]) == before, "Collision mutated existing data"
            assert call(checks.environment, state["operation"]) == state["ack"], "Request acknowledgement changed"
            assert post("/api/usage", {"project": project, "operation": {"Ingest": {"value": state["upload"]}}})[1] == state["receipt"]
            assert call(checks.environment, {"Usage": {"input": {"project": project, "selection": {"Phases": {"filter": {"ProjectAll": {}}}}}}}) == phases
            assert post("/api/usage", {"project": project, "operation": {"Span": {"value": span}}})[1] == {"Spanned": {"value": span}}
            assert fingerprint(names[1])["cq_usage_spans"] == before["cq_usage_spans"], "Span replay changed the restored audit"
            assert post("/api/artifact", state["artifact"])[1] == state["artifactMetadata"]
            operation = copy.deepcopy(state["operation"])
            operation["Change"]["input"]["change"]["request"] = {"value": str(uuid.uuid4())}
            assert call(checks.environment, operation)["Changed"]["ack"]["items"][0]["id"]["number"] == "10"
            assert "Saved project" in cli("human-backup", ["backup", project["value"], str(out / "human.cqbackup")]).stdout
        proxy_class = runpy.run_path(str(Path(__file__).with_name("archive-commit-proxy.py")))["CommitProxy"]
        with proxy_class((parsed.hostname, parsed.port)) as proxy:
            checks.environment["CQ_DATABASE_URL"] = f"jdbc:postgresql://127.0.0.1:{proxy.port}/{names[2]}?sslmode=disable&gssEncMode=disable"
            with checks.server(command, f"{name}-archive-uncertain"):
                proxy.armed.set()
                uncertain = cli("lost-commit-ack", ["restore", str(archive)], 1)
                assert proxy.dropped.is_set(), "COMMIT acknowledgement was not intercepted"
                assert fingerprint(names[2]) == before, "Server did not actually commit the project"
                write(out / "lost-commit-observation.json", {"commitAcknowledgementDropped": True, "projectCommitted": True, "stderr": uncertain.stderr})
                assert "no restore was committed" not in uncertain.stderr, "Committed restore was falsely reported as rolled back"
                assert "verify" in uncertain.stderr.lower() and "retry" in uncertain.stderr.lower(), uncertain.stderr
        result = {"status": "passed", "tables": 27, "snapshotConsistent": True, "recordsEqual": True,
                  "collisionRefused": True, "counterContinued": True, "authorizationEnforced": True}
        write(out / "result.json", result)
        return result
    finally:
        checks.environment["CQ_DATABASE_URL"] = original
        for database in reversed(created):
            sql(parsed.path[1:], f'DROP DATABASE "{database}"')
