#!/usr/bin/env python3
"""Behavioral replacement/recovery contracts over dummy and real filesystem adapters."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import shutil
import sys
import subprocess
import tempfile
import unittest


POSTGRES = "--postgres" in sys.argv
if POSTGRES:
    sys.argv.remove("--postgres")

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("update_local", Path(__file__).with_name("update-local.py"))
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


class MemoryDirectories:
    def __init__(self):
        self.packages = {}

    def exists(self, path):
        return path in self.packages

    def identity(self, path):
        return self.packages[path]

    def rename(self, source, destination):
        update.require(destination not in self.packages, "Replacement destination exists")
        self.packages[destination] = self.packages.pop(source)


class InterruptedStore:
    def __init__(self, store, interrupt):
        self.store = store
        self.interrupt = interrupt
        self.count = 0

    def exists(self, path):
        return self.store.exists(path)

    def identity(self, path):
        return self.store.identity(path)

    def rename(self, source, destination):
        self.store.rename(source, destination)
        self.count += 1
        if self.count == self.interrupt:
            raise KeyboardInterrupt("interrupt after rename")


class ReplacementContract:
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.release = self.directory / "release"
        self.candidate = self.directory / "candidate"
        self.rollback = self.directory / "rollback"
        self.store = self.make_store()
        self.old = self.create(self.release, "old")
        self.new = self.create(self.candidate, "new")

    def tearDown(self):
        self.temporary.cleanup()

    def replace(self, store, callback):
        update.replace_packages(store, self.release, self.candidate, self.rollback, self.old, self.new, callback)

    def test_installs_and_retains_old_package(self):
        self.replace(self.store, lambda: None)
        self.assertEqual(self.store.identity(self.release), self.new)
        self.assertEqual(self.store.identity(self.rollback), self.old)
        self.assertFalse(self.store.exists(self.candidate))

    def test_interrupt_after_either_rename_restores_pair(self):
        for count in (1, 2):
            with self.subTest(count=count), self.assertRaises(KeyboardInterrupt):
                self.replace(InterruptedStore(self.store, count), lambda: None)
            self.assertEqual(self.store.identity(self.release), self.old)
            self.assertEqual(self.store.identity(self.candidate), self.new)
            self.assertFalse(self.store.exists(self.rollback))

    def test_receipt_failure_restores_pair(self):
        def fail():
            raise OSError("receipt write failed")
        with self.assertRaisesRegex(OSError, "receipt"):
            self.replace(self.store, fail)
        self.assertEqual(self.store.identity(self.release), self.old)
        self.assertEqual(self.store.identity(self.candidate), self.new)

    def test_existing_rollback_refuses_without_changes(self):
        identity = self.create(self.rollback, "retained")
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            self.replace(self.store, lambda: None)
        self.assertEqual(self.store.identity(self.release), self.old)
        self.assertEqual(self.store.identity(self.candidate), self.new)
        self.assertEqual(self.store.identity(self.rollback), identity)

    def test_changed_candidate_refuses_without_changes(self):
        self.create(self.candidate, "changed")
        with self.assertRaisesRegex(RuntimeError, "changed"):
            self.replace(self.store, lambda: None)
        self.assertEqual(self.store.identity(self.release), self.old)
        self.assertFalse(self.store.exists(self.rollback))


class DummyReplacement(ReplacementContract, unittest.TestCase):
    def make_store(self):
        return MemoryDirectories()

    def create(self, path, content):
        identity = hashlib.sha256(content.encode()).hexdigest()
        self.store.packages[path] = identity
        return identity


class FilesystemReplacement(ReplacementContract, unittest.TestCase):
    def make_store(self):
        return update.Directories()

    def create(self, path, content):
        path.mkdir(exist_ok=True)
        (path / "manifest.json").write_text(content)
        return update.digest(path / "manifest.json")


class BuildCommands:
    def __init__(self):
        self.calls = []

    def run(self, arguments, label, timeout):
        self.calls.append((arguments, label))
        return ""


class LocalBuild(unittest.TestCase):
    def test_redeploy_builds_without_release_gates(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            candidate = evidence / "candidate"
            build = BuildCommands()
            update.build_candidate(build, evidence, "fixture-revision", candidate, evidence / "metadata")
            self.assertEqual([label for _, label in build.calls], ["package-local"],
                             "Local redeploy must build and smoke-check without running release qualification")
            self.assertEqual(build.calls[0][0], ["nix", "develop", "-c", "./dev/package-local",
                             "--output", str(candidate), "--evidence-root", str(evidence / "local-build"),
                             "--revision", "fixture-revision", "--metadata", str(evidence / "metadata")])


class TestCommand(unittest.TestCase):
    def test_full_validation_propagates_first_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            (fixture / "bin").mkdir()
            (fixture / "dev").mkdir()
            shutil.copy2(Path(__file__).resolve().parent.parent / "test-local.sh", fixture / "test-local.sh")
            nix = fixture / "bin/nix"
            nix.write_text('#!/bin/sh\nshift 2\nshift\nexec "$@"\n')
            nix.chmod(0o755)
            check = fixture / "dev/check"
            check.write_text('#!/bin/sh\necho "$1" >> "$CQ_TEST_CALLS"\n[ "$1" != "$CQ_TEST_FAIL" ]\n')
            check.chmod(0o755)
            calls = fixture / "calls"
            environment = {**os.environ, "PATH": str(fixture / "bin") + os.pathsep + os.environ["PATH"],
                           "CQ_TEST_CALLS": str(calls), "CQ_TEST_FAIL": "ui"}
            failed = subprocess.run([str(fixture / "test-local.sh")], env=environment, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(failed.returncode, 0, "Full validation must report a failed gate")
            self.assertEqual(calls.read_text().splitlines(), ["fast", "ui"])
            calls.unlink()
            environment["CQ_TEST_FAIL"] = "none"
            passed = subprocess.run([str(fixture / "test-local.sh")], env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(passed.returncode, 0, passed.stderr)
            self.assertEqual(calls.read_text().splitlines(), ["fast", "ui", "postgres", "native"])


class CandidateSmoke(unittest.TestCase):
    def test_source_matched_completed_native_smoke(self):
        with tempfile.TemporaryDirectory() as directory:
            smoke = Path(directory) / "smoke.json"
            smoke.write_text(json.dumps({"status": "passed"}))
            manifest = {"runtime": "native", "validation": "local-smoke", "sourceRevision": "revision",
                        "localSmokeSha256": update.digest(smoke)}
            update.verify_local_candidate(manifest, "revision", smoke)
            for changed, reason in [({"validation": "built"}, "native smoke"),
                                    ({"sourceRevision": "other"}, "revision differs"),
                                    ({"localSmokeSha256": "other"}, "evidence differs")]:
                with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, reason):
                    update.verify_local_candidate({**manifest, **changed}, "revision", smoke)
            smoke.write_text(json.dumps({"status": "failed"}))
            with self.assertRaisesRegex(RuntimeError, "did not pass"):
                update.verify_local_candidate({**manifest, "localSmokeSha256": update.digest(smoke)}, "revision", smoke)


class Compatibility(unittest.TestCase):
    def manifest(self, schema, model):
        return {"runtimeSourceSha256": {update.SCHEMA_SOURCE: schema, update.MODEL_SOURCE: model}}

    def test_exact_transition_and_unchanged_model(self):
        step = {"kind": "unchanged-data", "schema": "schema", "modelBefore": "old", "modelAfter": "new"}
        self.assertEqual(update.compatible(self.manifest("schema", "old"), self.manifest("schema", "new"), step), "schema")
        self.assertEqual(update.compatible(self.manifest("schema", "new"), self.manifest("schema", "new"), step), "schema")

    def test_unrecognized_model_or_schema_refused(self):
        step = {"kind": "unchanged-data", "schema": "schema", "modelBefore": "old", "modelAfter": "new"}
        with self.assertRaisesRegex(RuntimeError, "data update step"):
            update.compatible(self.manifest("schema", "other"), self.manifest("schema", "new"), step)
        with self.assertRaisesRegex(RuntimeError, "Schema changes"):
            update.compatible(self.manifest("old-schema", "old"), self.manifest("schema", "new"), step)

    def test_pinned_step_describes_this_tree(self):
        step = json.loads((ROOT / "dev/local-update-step.json").read_text())
        self.assertEqual(step["kind"], update.DRIVER_CYCLE_OUTCOMES)
        self.assertEqual(step["schema"], update.digest(ROOT / update.SCHEMA_SOURCE))
        self.assertEqual(step["modelAfter"], update.digest(ROOT / update.MODEL_SOURCE))
        self.assertEqual(step["sqlSha256"], update.digest(ROOT / update.STEP_SQL))
        before, after = self.manifest(step["schema"], step["modelBefore"]), self.manifest(step["schema"], step["modelAfter"])
        self.assertEqual(update.compatible(before, after, step), step["schema"])
        self.assertEqual(update.transformation(before, after, step, ROOT), (ROOT / update.STEP_SQL).read_text())

    def test_data_step_selects_its_pinned_sql(self):
        before, after = self.manifest("schema", "old"), self.manifest("schema", "new")
        step = {"kind": update.DRIVER_CYCLE_OUTCOMES, "schema": "schema", "modelBefore": "old", "modelAfter": "new",
                "sqlSha256": update.digest(ROOT / update.STEP_SQL)}
        self.assertEqual(update.transformation(before, after, step, ROOT), (ROOT / update.STEP_SQL).read_text())
        self.assertIsNone(update.transformation(after, after, step, ROOT), "An installed release of the same model needs no data step")
        self.assertIsNone(update.transformation(before, after, {**step, "kind": update.UNCHANGED_DATA}, ROOT))
        with self.assertRaisesRegex(RuntimeError, "SQL differs from its pinned step"):
            update.transformation(before, after, {**step, "sqlSha256": "other"}, ROOT)
        for changed in ({"kind": "other"}, {"modelBefore": "other"}, {"modelAfter": "other"}, {"schema": "other"}):
            with self.subTest(changed=changed), self.assertRaisesRegex(RuntimeError, "data update step"):
                update.transformation(before, after, {**step, **changed}, ROOT)


# A driver with an active cycle as the release before the driver-cycle-outcomes step stored it in cq_drivers.body: a row its
# DriverContractPostgres suite wrote, unchanged.
STORED_DRIVER = json.loads("""
{"key":{"harness":"Claude","session":"host-order"},"bind":null,"cycle":{"id":{"value":"39d405e9-f3d4-4992-9a06-965c96eb7284"},
"run":{"value":"ec04366b-21d3-4c32-a1a0-07f75498776c"},"roots":[{"ledger":"Goals","number":"1",
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"}}],"state":"Active","number":1,"created":[],
"lineage":[{"member":{"Run":{"id":{"value":"ec04366b-21d3-4c32-a1a0-07f75498776c"}}},"parent":null,"settled":false}],"resting":[],"resumed":{},
"through":"Work","prompted":[],"snapshot":{"context":[],"targets":[{"ledger":"Goals","number":"1",
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"}}],"through":"Work","workset":null,"snapshot":{"cursor":{"value":"1"},
"rootsHash":"6f038a783587b736d3fd76f205fa88d4dcb998b719380d92fc0dfe518080b2fd"},"readiness":[{"item":{"ledger":"Goals","number":"1",
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"}},"ready":true,"reasons":[]}],"advanceable":[{"item":{"id":{"ledger":"Goals","number":"1",
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"}},"title":"Goal","labels":[],"status":"Open","outcome":{"terminal":false,
"satisfiesDependency":false},"archived":false,"revision":{"value":"1"},"updatedAt":"1791193416951"},"root":true}]},
"startToken":{"value":"2f721ad6-da9c-431f-9cd9-35680f7b408b"},"resumeToken":null},"state":"On","carried":{},
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"},"stopped":null,"targets":[{"ledger":"Goals","number":"1",
"project":{"value":"52c196fe-8668-4885-8209-129cefd58b84"}}],"through":"Work","workset":null,
"attached":{"value":"194a709c-95a2-4ce5-a2e4-2ece623c6e15"},"revision":{"value":"4"},"announced":true,"stoppedAt":null,"touchedAt":"1791193419249",
"directives":1}
""")
STORED_OUTCOME = {"attempt": {"value": "5d0b3a54-3f0c-4f0e-9a43-0c1b6f0a7e11"}, "members": STORED_DRIVER["targets"], "end": "Retryable",
                  "input": "fixture-input", "fault": "fixture fault"}


def model_fields(name):
    """The field names of a data type of this tree's model, which are the keys its generated JSON decoder requires."""
    body = re.search(r"^root data " + name + r" \{(.*?)^\}", (ROOT / update.MODEL_SOURCE).read_text(), re.DOTALL | re.MULTILINE).group(1)
    return set(re.findall(r"(\w+)\s*:", body))


def driver_row(session, cycle):
    body = {**STORED_DRIVER, "key": {**STORED_DRIVER["key"], "session": session}, "cycle": cycle}
    return {"project_id": body["project"]["value"], "harness": body["key"]["harness"], "session_key": session,
            "revision": int(body["revision"]["value"]), "state": body["state"], "attached": body["attached"]["value"],
            "cycle_id": None if cycle is None else cycle["id"]["value"], "touched_at": int(body["touchedAt"]), "stopped_at": None,
            "summary": {"fixture": session}, "body": body}


# Sorted by session key, as the PostgreSQL case reads them back.
DRIVER_ROWS = [driver_row("recorded", {**STORED_DRIVER["cycle"], "outcomes": [STORED_OUTCOME], "retried": [STORED_OUTCOME]}),
               driver_row("with-cycle", STORED_DRIVER["cycle"]), driver_row("without-cycle", None)]
DRIVER_TABLE = ("CREATE TABLE cq_drivers(project_id uuid NOT NULL, harness text NOT NULL, session_key text NOT NULL, revision bigint NOT NULL, "
                "state text NOT NULL, attached uuid, cycle_id uuid, touched_at bigint NOT NULL, stopped_at bigint, summary jsonb NOT NULL, "
                "body jsonb NOT NULL, PRIMARY KEY (project_id, harness, session_key)); INSERT INTO cq_drivers SELECT * FROM "
                "jsonb_populate_recordset(NULL::cq_drivers, '" + json.dumps(DRIVER_ROWS).replace("'", "''") + "'::jsonb);")


class DriverCycleOutcomes(unittest.TestCase):
    def test_fixture_is_the_stored_shape_before_the_step(self):
        self.assertEqual(set(STORED_DRIVER), model_fields("DriverRecord"))
        self.assertEqual(set(STORED_DRIVER["cycle"]), model_fields("CycleRecord") - set(update.CYCLE_OUTCOME_FIELDS))

    def test_stored_cycle_gains_the_fields_the_decoder_requires(self):
        old = driver_row("with-cycle", STORED_DRIVER["cycle"])
        new = update.with_cycle_outcomes(old)
        self.assertEqual(set(new["body"]["cycle"]), model_fields("CycleRecord"))
        self.assertEqual([new["body"]["cycle"][name] for name in update.CYCLE_OUTCOME_FIELDS], [[], []])
        self.assertEqual({key: value for key, value in new["body"]["cycle"].items() if key not in update.CYCLE_OUTCOME_FIELDS}, old["body"]["cycle"])
        self.assertEqual({**new, "body": {**new["body"], "cycle": old["body"]["cycle"]}}, old, "Nothing outside the cycle changes")
        self.assertEqual(update.with_cycle_outcomes(new), new, "The step is idempotent")

    def test_driver_without_cycle_and_recorded_outcomes_are_kept(self):
        idle = driver_row("without-cycle", None)
        self.assertEqual(update.with_cycle_outcomes(idle), idle)
        recorded = driver_row("recorded", {**STORED_DRIVER["cycle"], "outcomes": [STORED_OUTCOME], "retried": [STORED_OUTCOME]})
        self.assertEqual(update.with_cycle_outcomes(recorded), recorded)
        partial = driver_row("partial", {**STORED_DRIVER["cycle"], "outcomes": [STORED_OUTCOME]})
        self.assertEqual(update.with_cycle_outcomes(partial)["body"]["cycle"], {**partial["body"]["cycle"], "retried": []})


class PostgreSQLInstall(unittest.TestCase):
    def test_backup_and_install_preserve_database(self):
        if not POSTGRES:
            self.skipTest("Run with --postgres for the disposable PostgreSQL adapter")
        if any(shutil.which(name) is None for name in ("initdb", "pg_ctl", "psql", "pg_dump", "pg_restore")):
            self.skipTest("PostgreSQL commands unavailable")
        if os.getuid() == 0:
            self.skipTest("initdb requires an ordinary user")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / "postgres"
            evidence = root / "evidence"
            evidence.mkdir()
            password = "disposable-test-password"
            (root / "database-password").write_text(password)
            subprocess.run(["initdb", "-D", str(data), "-U", "cq", "--auth=trust", "--no-locale"], check=True, stdout=subprocess.DEVNULL)
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            subprocess.run(["pg_ctl", "-D", str(data), "-l", str(root / "setup.log"), "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
            try:
                subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "CREATE TABLE cq_schema_migrations(version integer PRIMARY KEY, checksum text); INSERT INTO cq_schema_migrations VALUES (1,'schema'); CREATE TABLE cq_claims(released boolean, expires_at bigint); CREATE TABLE cq_usage_attempts(effective_outcome text, parent_id uuid, body jsonb); CREATE TABLE cq_integrations(body jsonb); CREATE TABLE cq_fixture(value text); INSERT INTO cq_fixture VALUES ('retained'); " + DRIVER_TABLE + " INSERT INTO cq_usage_attempts(body) SELECT '{\"role\":\"Governor\",\"collector\":\"CQ attached session; outer usage unavailable\"}'::jsonb FROM generate_series(1,20);"], check=True, stdout=subprocess.DEVNULL)
            finally:
                subprocess.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
            release, candidate, rollback = (root / name for name in ("release", "candidate", "rollback"))
            def fixture(path, content):
                path.mkdir()
                (path / "bin").mkdir()
                files = {}
                for name in ("bin/cq", "bin/cq-guardian", "runtime.nar", "runtime-paths.txt"):
                    (path / name).write_text(content)
                    (path / name).chmod(0o700)
                    files[name] = update.digest(path / name)
                (path / "manifest.json").write_text(json.dumps({"modelVersion": "0.1.0", "platform": "x86_64-linux", "filesSha256": files}))
            for path, content in ((release, "old"), (candidate, "new")):
                fixture(path, content)
            receipt = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
            update.install(root, release, candidate, rollback, evidence, receipt, "schema", update.Commands(root, evidence, dict(os.environ)), None)
            self.assertEqual(receipt["status"], "installed")
            self.assertFalse((data / "postmaster.pid").exists())
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(rollback / "manifest.json"), receipt["oldManifest"])
            dump = subprocess.check_output(["pg_restore", "-f", "-", receipt["backup"]], text=True)
            self.assertIn("retained", dump)
            self.assertEqual(dump.count("CQ attached session; outer usage unavailable"), 20)
            self.assertEqual(receipt["backupSha256"], update.digest(Path(receipt["backup"])))
            self.assertEqual(receipt["unsettledWork"], {"claims": 0, "managedAttempts": 0,
                             "pendingIntegrations": 0, "incompleteAttachedGovernors": 20})
            blocked_candidate = root / "blocked-candidate"
            fixture(blocked_candidate, "blocked")
            cases = [
                ("claims", "INSERT INTO cq_claims VALUES (false,9223372036854775807)", "DELETE FROM cq_claims"),
                ("managedAttempts", "INSERT INTO cq_usage_attempts(body) VALUES ('{\"role\":\"Worker\",\"collector\":\"CQ native collector 0.1.0\"}')", "DELETE FROM cq_usage_attempts WHERE body->>'role'='Worker'"),
                ("managedAttempts", "INSERT INTO cq_usage_attempts(body) VALUES ('{\"role\":\"Governor\",\"collector\":\"CQ native collector 0.1.0\"}')", "DELETE FROM cq_usage_attempts WHERE body->>'collector'='CQ native collector 0.1.0'"),
                ("managedAttempts", "INSERT INTO cq_usage_attempts(body) VALUES ('{}')", "DELETE FROM cq_usage_attempts WHERE body='{}'"),
                ("managedAttempts", "INSERT INTO cq_usage_attempts(parent_id,body) VALUES ('00000000-0000-0000-0000-000000000001','{\"role\":\"Governor\",\"collector\":\"CQ attached session; outer usage unavailable\"}')", "DELETE FROM cq_usage_attempts WHERE parent_id IS NOT NULL"),
                ("pendingIntegrations", "INSERT INTO cq_integrations VALUES ('{\"resolution\":{\"Pending\":{}}}')", "DELETE FROM cq_integrations"),
            ]
            def execute(statement):
                subprocess.run(["pg_ctl", "-D", str(data), "-l", str(root / "setup.log"), "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
                try:
                    subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", statement], check=True, stdout=subprocess.DEVNULL)
                finally:
                    subprocess.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
            for category, insertion, deletion in cases:
                with self.subTest(category=category, insertion=insertion):
                    execute(insertion)
                    blocked = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(blocked_candidate / "manifest.json"), "status": "candidate-verified"}
                    with self.assertRaisesRegex(RuntimeError, "Reconcile active claims"):
                        update.install(root, release, blocked_candidate, root / "blocked-rollback", evidence,
                                       blocked, "schema", update.Commands(root, evidence, dict(os.environ)), None)
                    self.assertEqual(blocked["unsettledWork"][category], 1)
                    self.assertEqual(blocked["status"], "rolled-back")
                    self.assertEqual(update.digest(release / "manifest.json"), receipt["newManifest"])
                    self.assertEqual(update.digest(blocked_candidate / "manifest.json"), blocked["newManifest"])
                    self.assertFalse((root / "blocked-rollback").exists())
                    self.assertFalse((root / ".cq-update-recovery.json").exists())
                    self.assertFalse((data / "postmaster.pid").exists())
                    execute(deletion)
            # A mismatched database refuses without changing either package and clears a verified recovery marker.
            candidate.mkdir()
            (candidate / "manifest.json").write_text("third")
            next_receipt = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
            with self.assertRaisesRegex(RuntimeError, "Database schema differs"):
                update.install(root, release, candidate, root / "next-rollback", evidence, next_receipt, "different", update.Commands(root, evidence, dict(os.environ)), None)
            self.assertEqual(next_receipt["status"], "rolled-back")
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(release / "manifest.json"), receipt["newManifest"])
            shutil.rmtree(candidate)
            fixture(candidate, "third")
            changed = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
            (candidate / "bin/cq").write_text("modified after candidate verification")
            with self.assertRaisesRegex(RuntimeError, "Package file differs"):
                update.install(root, release, candidate, root / "changed-rollback", evidence, changed, "schema",
                               update.Commands(root, evidence, dict(os.environ)), None)
            self.assertEqual(changed["status"], "rolled-back")
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(release / "manifest.json"), receipt["newManifest"])

            # The data step: installs without one have left the stored drivers as the earlier release wrote them.
            def stored():
                subprocess.run(["pg_ctl", "-D", str(data), "-l", str(root / "setup.log"), "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
                try:
                    rows = subprocess.check_output(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                                                    "SELECT json_build_object('drivers', (SELECT json_agg(to_jsonb(t) ORDER BY session_key) FROM cq_drivers t), 'fixture', (SELECT json_agg(value) FROM cq_fixture))"], text=True)
                finally:
                    subprocess.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
                return json.loads(rows)
            sql = (ROOT / update.STEP_SQL).read_text()
            original = stored()
            self.assertEqual(original, {"drivers": DRIVER_ROWS, "fixture": ["retained"]})
            installed = update.digest(release / "manifest.json")
            failures = [
                ("Package file differs", sql, True),
                ("changed driver rows beyond adding empty outcome fields", "UPDATE cq_drivers SET touched_at = touched_at + 1 WHERE session_key = 'without-cycle'; " + sql, False),
                ("changed a table other than cq_drivers", sql + " UPDATE cq_fixture SET value = 'changed';", False),
                ("data-update failed", "BEGIN; UPDATE cq_drivers SET body = jsonb_set(body, '{cycle,outcomes}', '[]'); SELECT 1/0; COMMIT;", False),
            ]
            for index, (reason, statement, modified) in enumerate(failures):
                with self.subTest(reason=reason):
                    failing = root / f"failing-candidate-{index}"
                    fixture(failing, f"failing {index}")
                    refused = {"oldManifest": installed, "newManifest": update.digest(failing / "manifest.json"), "status": "candidate-verified"}
                    if modified:
                        (failing / "bin/cq").write_text("modified after candidate verification")
                    with self.assertRaisesRegex(RuntimeError, reason):
                        update.install(root, release, failing, root / f"failing-rollback-{index}", evidence, refused, "schema",
                                       update.Commands(root, evidence, dict(os.environ)), statement)
                    self.assertEqual(refused["status"], "rolled-back")
                    self.assertTrue(refused["databaseRestored"])
                    self.assertEqual(json.loads((evidence / "data-before.json").read_text()), json.loads((evidence / "data-restored.json").read_text()))
                    self.assertFalse((root / ".cq-update-recovery.json").exists())
                    self.assertFalse((data / "postmaster.pid").exists())
                    self.assertEqual(update.digest(release / "manifest.json"), installed)
                    self.assertEqual(stored(), original, "A failed data step leaves the database as the backup holds it")
            expected = [update.with_cycle_outcomes(row) for row in DRIVER_ROWS]
            for attempt, transformed in (("first", 1), ("repeated", 0)):
                with self.subTest(attempt=attempt):
                    stepped = root / f"stepped-candidate-{attempt}"
                    fixture(stepped, f"stepped {attempt}")
                    applied = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(stepped / "manifest.json"), "status": "candidate-verified"}
                    update.install(root, release, stepped, root / f"stepped-rollback-{attempt}", evidence, applied, "schema",
                                   update.Commands(root, evidence, dict(os.environ)), sql)
                    self.assertEqual(applied["status"], "installed")
                    self.assertEqual((applied["dataStep"], applied["drivers"], applied["driversTransformed"], applied["otherDataUnchanged"]),
                                     (update.DRIVER_CYCLE_OUTCOMES, len(DRIVER_ROWS), transformed, True))
                    self.assertEqual(update.digest(release / "manifest.json"), applied["newManifest"])
                    self.assertEqual(stored(), {"drivers": expected, "fixture": ["retained"]})
            by_session = {row["session_key"]: row["body"]["cycle"] for row in expected}
            self.assertEqual(set(by_session["with-cycle"]), model_fields("CycleRecord"))
            self.assertEqual((by_session["with-cycle"]["outcomes"], by_session["with-cycle"]["retried"]), ([], []))
            self.assertIsNone(by_session["without-cycle"])
            self.assertEqual((by_session["recorded"]["outcomes"], by_session["recorded"]["retried"]), ([STORED_OUTCOME], [STORED_OUTCOME]))

            # A restore that fails leaves the recovery marker and the previous package, and still stops the database. The step adds a
            # view, which the restore cannot drop a table under, and changes that table, which the verification refuses.
            unrestorable = root / "unrestorable-candidate"
            fixture(unrestorable, "unrestorable")
            stranded = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(unrestorable / "manifest.json"), "status": "candidate-verified"}
            try:
                with self.assertRaisesRegex(RuntimeError, "database-rollback failed"):
                    update.install(root, release, unrestorable, root / "unrestorable-rollback", evidence, stranded, "schema", update.Commands(root, evidence, dict(os.environ)),
                                   "BEGIN; CREATE VIEW cq_dependent AS SELECT value FROM cq_fixture; UPDATE cq_fixture SET value = 'changed'; COMMIT;")
                self.assertFalse((data / "postmaster.pid").exists(), "A failed restore must not leave the database running")
            finally:
                if (data / "postmaster.pid").exists():
                    subprocess.run(["pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
            self.assertEqual(stranded["status"], "recovery-required")
            self.assertNotIn("databaseRestored", stranded)
            self.assertTrue((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(release / "manifest.json"), stranded["oldManifest"])
            self.assertEqual(update.digest(unrestorable / "manifest.json"), stranded["newManifest"])
            # The restore ran in one transaction: the database is as the failed step left it, not partly dropped.
            self.assertEqual(stored(), {"drivers": expected, "fixture": ["changed"]})



if __name__ == "__main__":
    unittest.main()
