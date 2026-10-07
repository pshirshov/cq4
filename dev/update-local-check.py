#!/usr/bin/env python3
"""Behavioral replacement/recovery contracts over dummy and real filesystem adapters."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
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
        self.assertIsNone(update.data_step(self.manifest("schema", "old"), self.manifest("schema", "new"), step, ROOT))

    STEP = {"kind": "installation-settings-and-attempt-effort", "schemaBefore": "old-schema", "schemaAfter": "schema", "modelBefore": "old", "modelAfter": "new"}

    def test_schema_transition_only_as_the_step_names_it(self):
        # The hash returned is the one the database holds before the replacement.
        self.assertEqual(update.compatible(self.manifest("old-schema", "old"), self.manifest("schema", "new"), self.STEP), "old-schema")
        # The same package again changes nothing and needs no step, whatever step the tree pins.
        self.assertEqual(update.compatible(self.manifest("schema", "new"), self.manifest("schema", "new"), self.STEP), "schema")
        self.assertIsNone(update.data_step(self.manifest("schema", "new"), self.manifest("schema", "new"), self.STEP, ROOT))
        for before, after in [(("other-schema", "old"), ("schema", "new")), (("old-schema", "other"), ("schema", "new")),
                              (("old-schema", "old"), ("other-schema", "new")), (("old-schema", "old"), ("schema", "other")),
                              (("schema", "new"), ("old-schema", "old"))]:
            with self.subTest(before=before, after=after), self.assertRaisesRegex(RuntimeError, "Schema changes"):
                update.compatible(self.manifest(*before), self.manifest(*after), self.STEP)
        # A later model change on the new schema is not the pinned step's transition.
        with self.assertRaisesRegex(RuntimeError, "data update step"):
            update.compatible(self.manifest("schema", "new"), self.manifest("schema", "newer"), self.STEP)

    def test_pinned_step_describes_this_tree(self):
        step = json.loads((ROOT / "dev/local-update-step.json").read_text())
        schema = (ROOT / update.SCHEMA_SOURCE).read_text()
        sql = (ROOT / update.STEP_SQL).read_text()
        self.assertEqual(step["kind"], update.INSTALLATION_SETTINGS_AND_ATTEMPT_EFFORT)
        self.assertEqual(step["schemaAfter"], update.digest(ROOT / update.SCHEMA_SOURCE))
        self.assertEqual(step["modelAfter"], update.digest(ROOT / update.MODEL_SOURCE))
        self.assertEqual(step["sqlSha256"], update.digest(ROOT / update.STEP_SQL))
        before, after = self.manifest(step["schemaBefore"], step["modelBefore"]), self.manifest(step["schemaAfter"], step["modelAfter"])
        self.assertEqual(update.compatible(before, after, step), step["schemaBefore"])
        planned = update.data_step(before, after, step, ROOT)
        self.assertEqual((planned.sql, planned.schema_after, planned.schema_source), (sql, step["schemaAfter"], ROOT / update.SCHEMA_SOURCE))
        # The table the SQL creates is the one the schema declares, and the schema without it is the one the step starts from.
        start = schema.index(f"CREATE TABLE {update.INSTALLATION_SETTINGS} (")
        declared = schema[start:schema.index(");\n", start) + 3]
        self.assertIn(declared.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ", 1), sql)
        self.assertEqual(hashlib.sha256(schema.replace(declared, "").encode()).hexdigest(), step["schemaBefore"])
        self.assertIn(f"SET checksum = '{step['schemaAfter']}' WHERE version = 1 AND checksum = '{step['schemaBefore']}'", sql)
        tampered = {**step, "sqlSha256": "0" * 64}
        with self.assertRaisesRegex(RuntimeError, "SQL differs from its pinned step"):
            update.data_step(before, after, tampered, ROOT)

    def test_effort_added_only_where_lacking(self):
        lacking = {"attempt_id": "a", "body": {"model": "m"}}
        stated = {"attempt_id": "b", "body": {"model": "m", "effort": "High"}}
        self.assertEqual(update.with_effort(lacking), {"attempt_id": "a", "body": {"model": "m", "effort": None}})
        self.assertEqual(update.with_effort(stated), stated)
        self.assertEqual(update.with_effort(update.with_effort(lacking)), update.with_effort(lacking))

    def test_unknown_step_kind_refused(self):
        step = {"kind": "other", "schema": "schema", "modelBefore": "old", "modelAfter": "new"}
        with self.assertRaisesRegex(RuntimeError, "data update step"):
            update.compatible(self.manifest("schema", "old"), self.manifest("schema", "new"), step)


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
                subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "CREATE TABLE cq_schema_migrations(version integer PRIMARY KEY, checksum text); INSERT INTO cq_schema_migrations VALUES (1,'schema'); CREATE TABLE cq_claims(released boolean, expires_at bigint); CREATE TABLE cq_usage_attempts(effective_outcome text, parent_id uuid, body jsonb); CREATE TABLE cq_integrations(body jsonb); CREATE TABLE cq_fixture(value text); INSERT INTO cq_fixture VALUES ('retained'); INSERT INTO cq_usage_attempts(body) SELECT '{\"role\":\"Governor\",\"collector\":\"CQ attached session; outer usage unavailable\"}'::jsonb FROM generate_series(1,20);"], check=True, stdout=subprocess.DEVNULL)
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

            # SQL applied after the backup: the installs without it have left the stored rows as they were.
            def stored():
                subprocess.run(["pg_ctl", "-D", str(data), "-l", str(root / "setup.log"), "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
                try:
                    rows = subprocess.check_output(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                                                    "SELECT json_agg(value) FROM cq_fixture"], text=True)
                finally:
                    subprocess.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
                return json.loads(rows)
            sql = "BEGIN; UPDATE cq_fixture SET value = 'transformed'; COMMIT;"
            self.assertEqual(stored(), ["retained"])
            installed = update.digest(release / "manifest.json")
            failures = [
                ("Package file differs", sql, True),
                ("data-update failed", "BEGIN; UPDATE cq_fixture SET value = 'partial'; SELECT 1/0; COMMIT;", False),
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
                                       update.Commands(root, evidence, dict(os.environ)), update.Sql(statement))
                    self.assertEqual(refused["status"], "rolled-back")
                    self.assertTrue(refused["databaseRestored"])
                    self.assertEqual(json.loads((evidence / "data-before.json").read_text()), json.loads((evidence / "data-restored.json").read_text()))
                    self.assertFalse((root / ".cq-update-recovery.json").exists())
                    self.assertFalse((data / "postmaster.pid").exists())
                    self.assertEqual(update.digest(release / "manifest.json"), installed)
                    self.assertEqual(stored(), ["retained"], "Failed SQL or a failed replacement after it leaves the database as the backup holds it")
            for attempt, changed in (("first", ["cq_fixture"]), ("repeated", [])):
                with self.subTest(attempt=attempt):
                    stepped = root / f"stepped-candidate-{attempt}"
                    fixture(stepped, f"stepped {attempt}")
                    applied = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(stepped / "manifest.json"), "status": "candidate-verified"}
                    update.install(root, release, stepped, root / f"stepped-rollback-{attempt}", evidence, applied, "schema",
                                   update.Commands(root, evidence, dict(os.environ)), update.Sql(sql))
                    self.assertEqual(applied["status"], "installed")
                    self.assertEqual(applied["dataChanged"], changed)
                    self.assertEqual(update.digest(release / "manifest.json"), applied["newManifest"])
                    self.assertEqual(stored(), ["transformed"])

            # A restore that fails leaves the recovery marker and the previous package, and still stops the database. The SQL adds a
            # view, which the restore cannot drop a table under, and the replacement after it fails.
            unrestorable = root / "unrestorable-candidate"
            fixture(unrestorable, "unrestorable")
            stranded = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(unrestorable / "manifest.json"), "status": "candidate-verified"}
            (unrestorable / "bin/cq").write_text("modified after candidate verification")
            try:
                with self.assertRaisesRegex(RuntimeError, "database-rollback failed"):
                    update.install(root, release, unrestorable, root / "unrestorable-rollback", evidence, stranded, "schema", update.Commands(root, evidence, dict(os.environ)),
                                   update.Sql("BEGIN; CREATE VIEW cq_dependent AS SELECT value FROM cq_fixture; UPDATE cq_fixture SET value = 'changed'; COMMIT;"))
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
            self.assertEqual(stored(), ["changed"])


    def test_pinned_step_creates_the_table_and_adds_effort(self):
        if not POSTGRES:
            self.skipTest("Run with --postgres for the disposable PostgreSQL adapter")
        step = json.loads((ROOT / "dev/local-update-step.json").read_text())
        schema = (ROOT / update.SCHEMA_SOURCE).read_text()
        start = schema.index(f"CREATE TABLE {update.INSTALLATION_SETTINGS} (")
        earlier = schema.replace(schema[start:schema.index(");\n", start) + 3], "")
        pinned = (ROOT / update.STEP_SQL).read_text()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = root / "postgres"
            evidence = root / "evidence"
            evidence.mkdir()
            (root / "database-password").write_text("disposable-test-password")
            (root / "earlier.sql").write_text(earlier)
            subprocess.run(["initdb", "-D", str(data), "-U", "cq", "--auth=trust", "--no-locale"], check=True, stdout=subprocess.DEVNULL)
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                port = listener.getsockname()[1]
            def running(arguments):
                subprocess.run(["pg_ctl", "-D", str(data), "-l", str(root / "setup.log"), "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "start"], check=True, stdout=subprocess.DEVNULL)
                try:
                    return subprocess.check_output(["psql", "--no-psqlrc", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"] + arguments, text=True)
                finally:
                    subprocess.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "stop"], check=True, stdout=subprocess.DEVNULL)
            # A database of the release the step starts from, with attempts as that release stored them: no effort.
            project, assignment = "00000000-0000-0000-0000-00000000000a", "00000000-0000-0000-0000-00000000000b"
            attempts = [("00000000-0000-0000-0000-000000000001", "NULL", "NULL", {"role": "Governor", "collector": update.ATTACHED_GOVERNOR_COLLECTOR, "model": "outer"}),
                        ("00000000-0000-0000-0000-000000000002", "'00000000-0000-0000-0000-000000000001'", "'{\"value\":{\"state\":\"Completed\"}}'", {"role": "Worker", "collector": "native", "model": "a model", "provider": "p"}),
                        ("00000000-0000-0000-0000-000000000003", "'00000000-0000-0000-0000-000000000001'", "'{\"value\":{\"state\":\"Failed\"}}'", {"role": "Reviewer", "collector": "native", "model": "m", "nested": {"effort": "inner"}})]
            running(["-c", "CREATE TABLE cq_schema_migrations (version integer PRIMARY KEY, checksum text NOT NULL)", "-c", f"INSERT INTO cq_schema_migrations VALUES (1, '{step['schemaBefore']}')",
                     "-f", str(root / "earlier.sql"),
                     # As an earlier update left it: a column added to an existing table stands after the columns the schema file declares later.
                     "-c", "ALTER TABLE cq_items DROP COLUMN severity",
                     "-c", "ALTER TABLE cq_items ADD COLUMN severity text CHECK (severity IN ('Critical', 'High', 'Medium', 'Low'))",
                     "-c", f"INSERT INTO cq_projects(project_id, body) VALUES ('{project}', '{{}}')",
                     "-c", f"INSERT INTO cq_usage_assignments(project_id, assignment_id, attribution, actor, received_at, body) VALUES ('{project}', '{assignment}', 'Unattributed', '{{}}', 1, '{{}}')"] +
                    [argument for identity, parent, outcome, body in attempts for argument in ("-c",
                        f"INSERT INTO cq_usage_attempts(project_id, attempt_id, assignment_id, parent_id, effective_outcome, session_id, actor, received_at, body) "
                        f"VALUES ('{project}', '{identity}', '{assignment}', {parent}, {outcome}, '{project}', '{{}}', 1, '{json.dumps(body)}')")])
            def stored():
                return json.loads(running(["-c", "SELECT json_build_object('checksum', (SELECT checksum FROM cq_schema_migrations), "
                    "'tables', (SELECT json_agg(tablename ORDER BY tablename) FROM pg_tables WHERE schemaname = 'public'), "
                    "'databases', (SELECT json_agg(datname ORDER BY datname) FROM pg_database WHERE NOT datistemplate), "
                    "'attempts', (SELECT json_agg(to_jsonb(t) ORDER BY attempt_id) FROM cq_usage_attempts t), "
                    "'projects', (SELECT json_agg(to_jsonb(t)) FROM cq_projects t), 'assignments', (SELECT json_agg(to_jsonb(t)) FROM cq_usage_assignments t))"]))
            initial = stored()
            self.assertNotIn(update.INSTALLATION_SETTINGS, initial["tables"])
            self.assertEqual([attempt["body"] for attempt in initial["attempts"]], [body for _, _, _, body in attempts])
            release = root / "release"
            def fixture(path, content):
                path.mkdir()
                (path / "bin").mkdir()
                files = {}
                for name in ("bin/cq", "bin/cq-guardian", "runtime.nar", "runtime-paths.txt"):
                    (path / name).write_text(content)
                    (path / name).chmod(0o700)
                    files[name] = update.digest(path / name)
                (path / "manifest.json").write_text(json.dumps({"modelVersion": "0.1.0", "platform": "x86_64-linux", "filesSha256": files}))
            fixture(release, "old")
            installed = update.digest(release / "manifest.json")
            def attempt(name, sql, modified):
                candidate = root / f"candidate-{name}"
                fixture(candidate, name)
                receipt = {"oldManifest": installed, "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
                if modified:
                    (candidate / "bin/cq").write_text("modified after candidate verification")
                planned = update.InstallationSettingsAndAttemptEffort(sql, step["schemaAfter"], ROOT / update.SCHEMA_SOURCE)
                return receipt, lambda: update.install(root, release, candidate, root / f"rollback-{name}", evidence, receipt, step["schemaBefore"],
                                                       update.Commands(root, evidence, dict(os.environ)), planned)
            # Whatever fails after the SQL ran, the database is again the backup's: the created table is gone with the rest.
            effort = "UPDATE cq_usage_attempts SET body = body || '{\"effort\": null}'::jsonb WHERE NOT jsonb_exists(body, 'effort');\n"
            checksum = f"UPDATE cq_schema_migrations SET checksum = '{step['schemaAfter']}'"
            self.assertIn(effort, pinned)
            failures = [
                ("replacement", "Package file differs", pinned, True),
                ("no-effort", "left a stored attempt without its effort", pinned.replace(effort, ""), False),
                ("no-checksum", "did not record the schema of the new package", pinned.replace(checksum, checksum.replace("SET checksum = '", "SET checksum = 'x")), False),
                ("other-attempt-change", "changed attempt rows beyond adding a null effort", pinned.replace(effort, effort + "UPDATE cq_usage_attempts SET received_at = 2;\n"), False),
                ("other-table", "changed a table other than", pinned.replace(effort, effort + "UPDATE cq_projects SET body = '{\"changed\": true}';\n"), False),
                ("other-structure", "structure differs from the schema of the new package", pinned.replace("  body jsonb NOT NULL\n);", "  body jsonb\n);"), False),
                ("setting-written", "wrote an installation setting", pinned.replace(effort, effort + "INSERT INTO cq_installation_settings VALUES ('Agents', 1, '{}', 1, '{}');\n"), False),
            ]
            for name, reason, sql, modified in failures:
                with self.subTest(failure=name):
                    self.assertTrue(modified or sql != pinned, "The variant differs from the pinned SQL")
                    refused, run = attempt(name, sql, modified)
                    with self.assertRaisesRegex(RuntimeError, reason):
                        run()
                    self.assertEqual((refused["status"], refused.get("databaseRestored")), ("rolled-back", True))
                    self.assertFalse((root / ".cq-update-recovery.json").exists())
                    self.assertEqual(update.digest(release / "manifest.json"), installed)
                    self.assertEqual(stored(), initial)
            applied, run = attempt("pinned", pinned, False)
            run()
            self.assertEqual(applied["status"], "installed")
            self.assertEqual(applied["dataChanged"], [update.INSTALLATION_SETTINGS, update.MIGRATIONS, update.ATTEMPTS])
            self.assertEqual({key: applied[key] for key in ("dataStep", "attempts", "attemptsTransformed", "tableCreated", "otherDataUnchanged", "structureAsDeclared")},
                             {"dataStep": update.INSTALLATION_SETTINGS_AND_ATTEMPT_EFFORT, "attempts": 3, "attemptsTransformed": 3,
                              "tableCreated": update.INSTALLATION_SETTINGS, "otherDataUnchanged": True, "structureAsDeclared": True})
            updated = stored()
            self.assertEqual(updated, {**initial, "checksum": step["schemaAfter"], "tables": sorted(initial["tables"] + [update.INSTALLATION_SETTINGS]),
                                       "attempts": [{**row, "body": {**row["body"], "effort": None}} for row in initial["attempts"]]})
            self.assertEqual(updated["attempts"][2]["body"]["nested"], {"effort": "inner"})
            self.assertEqual(updated["databases"], ["postgres"], "The database the structure was declared in is dropped")
            # The SQL applied again, and to an attempt that states an effort, changes nothing.
            running(["-c", "UPDATE cq_usage_attempts SET body = body || '{\"effort\": \"High\"}'::jsonb WHERE attempt_id = '00000000-0000-0000-0000-000000000002'"])
            stated = stored()
            running(["-c", pinned])
            self.assertEqual(stored(), stated)
            self.assertEqual(stated["attempts"][1]["body"]["effort"], "High")


if __name__ == "__main__":
    unittest.main()
