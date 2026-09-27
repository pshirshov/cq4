import concurrent.futures
import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid


SIZES = (100, 10_000, 100_000)
REPETITIONS = 5
MAX_MUTATION_STATEMENTS = 32
MAX_MUTATION_VISITS = 256
MAX_SMALL_TABLE_VISITS = 1024
MAX_MUTATION_BUFFERS = 4096
MAX_COMPLETION_VISITS = 64


class AccessFixture:
    def __init__(self, environment: dict[str, str], evidence: Path):
        self.environment = environment
        self.evidence = evidence
        self.records = []
        address = urllib.parse.urlsplit(environment["CQ_DATABASE_URL"].removeprefix("jdbc:"))
        self.psql = ["psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1", "--quiet", "--tuples-only", "--no-align",
                     "--host", address.hostname, "--port", str(address.port), "--dbname", address.path[1:],
                     "--username", environment["CQ_DATABASE_USER"]]
        self.database_environment = {**environment, "PGPASSWORD": environment["CQ_DATABASE_PASSWORD"]}

    def sql(self, command: str, variables: dict[str, str]):
        arguments = [part for key, value in variables.items() for part in ["--set", f"{key}={value}"]]
        result = subprocess.run(self.psql + arguments, input=command, env=self.database_environment,
                                text=True, capture_output=True, timeout=180)
        assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    def call(self, command: dict):
        request = urllib.request.Request(self.environment["CQ_ORIGIN"] + "/api/call",
            data=json.dumps(command).encode(), headers={"Authorization": "Bearer " + self.environment["CQ_TOKEN"],
                "Content-Type": "application/json", "CQ-Protocol-Version": "0.1.0", "CQ-Session": self.environment["CQ_SESSION"]})
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
        assert "Failed" not in result, result
        return result

    def measured(self, name: str, command: dict):
        started = time.time()
        result = self.call(command)
        finished = time.time()
        self.records.append({"name": name, "started": started, "finished": finished,
                             "elapsedMs": (finished - started) * 1000, "responseBytes": len(json.dumps(result).encode())})
        (self.evidence / "access-operations.json").write_text(json.dumps(self.records, indent=2) + "\n")
        return result

    def seed(self, project: dict, lower: int, upper: int):
        self.sql("""
BEGIN;
WITH template AS (SELECT * FROM cq_items WHERE project_id = :'project'::uuid AND ledger = 'Tasks' AND number = 3)
INSERT INTO cq_items(project_id, ledger, number, display_id, revision, schema_version, archived, status, title, narrative, search_text, body, summary)
SELECT project_id, ledger, n + 1000, 'T' || (n + 1000), revision, schema_version, archived, status, 'unrelated filler', narrative,
       ' unrelated filler padding ',
       jsonb_set(jsonb_set(jsonb_set(body, '{id,number}', to_jsonb((n + 1000)::text)), '{draft,title}', '"unrelated filler"'),
                 '{draft,labels}', jsonb_build_array('bulk' || lpad(n::text, 6, '0'))),
       jsonb_set(jsonb_set(jsonb_set(summary, '{id,number}', to_jsonb((n + 1000)::text)), '{title}', '"unrelated filler"'),
                 '{labels}', jsonb_build_array('bulk' || lpad(n::text, 6, '0')))
FROM template CROSS JOIN generate_series(:lower, :upper) n;
INSERT INTO cq_labels SELECT :'project'::uuid, 'bulk' || lpad(n::text, 6, '0'), 1 FROM generate_series(:lower, :upper) n;
INSERT INTO cq_edges SELECT :'project'::uuid, 'Tasks', n + 1000, 'RelatesTo', 'Tasks', n + 1001
FROM generate_series(:lower, :upper - 1) n;
INSERT INTO cq_history
SELECT i.project_id, i.ledger, i.number, 1, i.schema_version,
       jsonb_set(h.body, '{item,item}', i.body)
FROM cq_items i JOIN cq_history h ON h.project_id = i.project_id AND h.ledger = 'Tasks' AND h.number = 3 AND h.revision = 1
WHERE i.project_id = :'project'::uuid AND i.number BETWEEN :lower + 1000 AND :upper + 1000;
COMMIT;
""", {"project": project["value"], "lower": str(lower), "upper": str(upper)})

    def run(self):
        projects = [{"value": str(uuid.uuid4())} for _ in range(2)]
        drafts = [{**draft("needleword"), "labels": ["needle-initial"]}, draft("Prerequisite"), draft("Seed template")]
        for project in projects:
            self.call({"Initialize": {"config": {"project": project, "endpoint": self.environment["CQ_ORIGIN"], "name": "Access measurement"}}})
            self.call(change(project, [{"Create": {"draft": value}} for value in drafts]))
        project = projects[0]
        first, second = [item_id(project, number) for number in (1, 2)]
        linked = self.call(change(project, [{"Reference": {"source": first, "expectedSource": {"value": "1"}, "relation": "BlockedBy",
            "target": second, "expectedTarget": {"value": "1"}, "present": True}}]))["Changed"]["ack"]
        revision = next(item["revision"] for item in linked["items"] if item["id"] == first)
        previous = 0
        for size in SIZES:
            for scope in projects:
                self.seed(scope, previous + 1, size)
            self.sql("ANALYZE cq_items; ANALYZE cq_edges; ANALYZE cq_labels; ANALYZE cq_history;", {})
            for repetition in range(REPETITIONS):
                value = {**draft("needleword"), "labels": [f"needle{size}-{repetition}"]}
                request = change(project, [{"Replace": {"id": first, "expected": revision, "draft": value}}])
                result = self.measured(f"{size}-mutation-{repetition}", request)["Changed"]["ack"]
                assert len(result["items"]) == 1 and result["items"][0]["id"] == first
                revision = result["items"][0]["revision"]
                if repetition == 0:
                    replay = self.measured(f"{size}-retry", request)["Changed"]["ack"]
                    assert replay == result
            for name, query in [("id", "T1"), ("text", "needleword"), ("tag", f'tag:"needle{size}-4"'),
                                ("reference", "blocked-by:T2"), ("negative", "NOT unrelated"), ("page", "archived:all")]:
                result = self.measured(f"{size}-search-{name}", search(project, query))["Found"]["page"]
                assert any(item["id"] == first for item in result["items"]), (name, result)
                if name in ("id", "text", "tag", "reference"):
                    assert len(result["items"]) == 1, (name, result)
            for name, query in [("id", "id:T"), ("label", "tag:bulk")]:
                result = self.measured(f"{size}-complete-{name}", {"Read": {"input": {"project": project,
                    "selection": {"QueryComplete": {"query": query, "cursor": len(query), "limit": 20}}}}})
                assert len(result["QueryAnalyzed"]["analysis"]["suggestions"]) == 20
                assert result["QueryAnalyzed"]["analysis"]["hasMore"]
            workset = self.measured(f"{size}-graph", {"Graph": {"input": {"project": project,
                "roots": [first], "after": None, "snapshot": None, "limit": 20}}})["Workset"]["page"]
            assert workset["selectedCount"] == 1 and workset["contextCount"] == 1 and not workset["hasMore"]
            preview = self.measured(f"{size}-termination-preview", {"Read": {"input": {"project": project,
                "selection": {"Termination": {"roots": [first], "intent": "Cancel"}}}}})["Termination"]["preview"]
            assert preview["plan"]["canApply"] and len(preview["plan"]["entries"]) == 2
            assert len([entry for entry in preview["plan"]["entries"] if "Change" in entry["effect"]]) == 1
            invariant = self.sql("""SELECT count(*), count(*) FILTER (WHERE revision <> 1) FROM cq_items WHERE number > 1000;
SELECT count(*) FROM cq_history WHERE number > 1000;
SELECT count(*) FROM cq_changes;
SELECT count(*) FROM cq_history WHERE project_id = :'project'::uuid AND ledger = 'Tasks' AND number = 1;""", {"project": project["value"]})
            stage = SIZES.index(size) + 1
            assert invariant.splitlines() == [f"{size * 2}|0", str(size * 2), str(3 + stage * REPETITIONS), str(2 + stage * REPETITIONS)], invariant
            previous = size
        self.contention(projects)
        print("Actual HTTP mutation/retry/search/completion workloads passed at 100, 10000 and 100000 unrelated items per project")

    def contention(self, projects: list[dict]):
        holder = subprocess.Popen(self.psql + ["--set", "project=" + projects[0]["value"]], env=self.database_environment,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            holder.stdin.write("BEGIN; SELECT 1 FROM cq_projects WHERE project_id = :'project'::uuid FOR UPDATE;\n")
            holder.stdin.flush()
            deadline = time.monotonic() + 10
            while self.sql("SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction' AND query LIKE 'SELECT 1 FROM cq_projects%';", {}) != "1":
                assert time.monotonic() < deadline, "Project lock was not established"
                time.sleep(0.02)
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                waiting = pool.submit(self.call, search(projects[0], "T1"))
                deadline = time.monotonic() + 10
                while self.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name = 'cq-access' AND wait_event_type = 'Lock';", {}) != "1":
                    assert time.monotonic() < deadline, "Same-project read did not wait for project lock"
                    time.sleep(0.02)
                start = time.monotonic()
                independent = self.call(search(projects[1], "T1"))
                independent_ms = (time.monotonic() - start) * 1000
                assert independent["Found"]["page"]["items"] and not waiting.done()
                holder.stdin.write("COMMIT;\n\\q\n")
                holder.stdin.flush()
                assert waiting.result(timeout=10)["Found"]["page"]["items"]
            holder.wait(timeout=10)
            assert holder.returncode == 0, holder.stderr.read()
            (self.evidence / "access-contention.json").write_text(json.dumps({"sameProjectBlocked": True,
                "otherProjectCompletedWhileLocked": True, "otherProjectMs": independent_ms}, indent=2) + "\n")
        finally:
            if holder.poll() is None:
                holder.terminate()
                holder.wait(timeout=10)


def draft(title: str):
    return {"title": title, "body": "padding", "labels": ["seed"], "archived": False,
            "content": {"Task": {"status": "Ready", "acceptance": ["Measured"], "result": None, "validation": []}}, "citations": []}


def item_id(project: dict, number: int):
    return {"project": project, "ledger": "Tasks", "number": str(number)}


def change(project: dict, mutations: list[dict]):
    return {"Change": {"input": {"project": project, "change": {"request": {"value": str(uuid.uuid4())},
            "mutations": mutations, "fences": [], "reason": "Access measurement"}}}}


def search(project: dict, query: str):
    return {"Search": {"input": {"project": project, "query": query, "after": None, "snapshot": None, "limit": 20}}}


def nodes(plan: dict):
    yield plan
    for child in plan.get("Plans", []):
        yield from nodes(child)


def report(evidence: Path):
    operations = json.loads((evidence / "access-operations.json").read_text())
    plans = []
    for line in (evidence / "access.json").read_text().splitlines():
        entry = json.loads(line)
        if entry.get("application_name") == "cq-access" and " plan:\n" in entry["message"]:
            plan = json.loads(entry["message"].split(" plan:\n", 1)[1])
            assert entry["timestamp"].endswith(" UTC"), entry["timestamp"]
            timestamp = datetime.datetime.fromisoformat(entry["timestamp"].removesuffix(" UTC")).replace(tzinfo=datetime.timezone.utc).timestamp()
            plans.append((timestamp, plan))
    assert plans, "No actual application plans captured"
    reports = []
    for operation in operations:
        selected = [plan for timestamp, plan in plans if operation["started"] <= timestamp <= operation["finished"]]
        assert selected, operation
        scans = [node for entry in selected for node in nodes(entry["Plan"]) if "Scan" in node["Node Type"]]
        visits = sum((node["Actual Rows"] + node.get("Rows Removed by Filter", 0) + node.get("Rows Removed by Index Recheck", 0)) * node["Actual Loops"] for node in scans)
        buffers = sum(entry["Plan"]["Shared Hit Blocks"] + entry["Plan"]["Shared Read Blocks"] for entry in selected)
        result = {**operation, "statements": len(selected), "scanVisits": visits, "sharedBuffers": buffers,
                  "indexes": sorted({node["Index Name"] for entry in selected for node in nodes(entry["Plan"]) if "Index Name" in node})}
        reports.append(result)
        (evidence / (operation["name"] + "-plans.json")).write_text(json.dumps(selected, indent=2) + "\n")
    (evidence / "access-report.json").write_text(json.dumps(reports, indent=2) + "\n")
    mutations = [row for row in reports if "-mutation-" in row["name"]]
    assert len(mutations) == len(SIZES) * REPETITIONS
    assert len({row["statements"] for row in mutations}) == 1, mutations
    for row in mutations:
        visit_limit = MAX_SMALL_TABLE_VISITS if row["name"].startswith("100-") else MAX_MUTATION_VISITS
        assert row["statements"] <= MAX_MUTATION_STATEMENTS and row["scanVisits"] <= visit_limit and row["sharedBuffers"] <= MAX_MUTATION_BUFFERS, row
    for row in reports:
        if "-complete-" in row["name"]:
            assert row["scanVisits"] <= MAX_COMPLETION_VISITS, row
        if row["name"].endswith("-search-reference") and not row["name"].startswith("100-"):
            assert row["scanVisits"] <= MAX_MUTATION_VISITS, row
        if row["name"].endswith(("-graph", "-termination-preview")):
            visit_limit = MAX_SMALL_TABLE_VISITS if row["name"].startswith("100-") else MAX_MUTATION_VISITS
            assert row["scanVisits"] <= visit_limit and row["statements"] <= 8 and row["responseBytes"] < 4096, row
            selected = json.loads((evidence / (row["name"] + "-plans.json")).read_text())
            assert not any("SELECT body::text FROM cq_items" in plan["Query Text"] for plan in selected), row
        print(f'{row["name"]}: {row["statements"]} statements, {row["scanVisits"]} scan visits, {row["sharedBuffers"]} buffers, {row["elapsedMs"]:.1f} ms')


if __name__ == "__main__":
    mode, directory = sys.argv[1:]
    if mode == "run":
        AccessFixture(dict(os.environ), Path(directory)).run()
    elif mode == "report":
        report(Path(directory))
    else:
        raise SystemExit("Expected run or report")
