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
                subprocess.run(["psql", "-h", "127.0.0.1", "-p", str(port), "-U", "cq", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", "CREATE TABLE cq_schema_migrations(version integer PRIMARY KEY, checksum text); INSERT INTO cq_schema_migrations VALUES (1,'schema'); CREATE TABLE cq_claims(released boolean, expires_at bigint); CREATE TABLE cq_usage_attempts(effective_outcome text); CREATE TABLE cq_integrations(body jsonb); CREATE TABLE cq_fixture(value text); INSERT INTO cq_fixture VALUES ('retained');"], check=True, stdout=subprocess.DEVNULL)
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
            update.install(root, release, candidate, rollback, evidence, receipt, "schema", update.Commands(root, evidence, dict(os.environ)), "schema", None)
            self.assertEqual(receipt["status"], "installed")
            self.assertFalse((data / "postmaster.pid").exists())
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(rollback / "manifest.json"), receipt["oldManifest"])
            dump = subprocess.check_output(["pg_restore", "-f", "-", receipt["backup"]], text=True)
            self.assertIn("retained", dump)
            self.assertEqual(receipt["backupSha256"], update.digest(Path(receipt["backup"])))
            # A mismatched database refuses without changing either package and clears a verified recovery marker.
            candidate.mkdir()
            (candidate / "manifest.json").write_text("third")
            next_receipt = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
            with self.assertRaisesRegex(RuntimeError, "Database schema differs"):
                update.install(root, release, candidate, root / "next-rollback", evidence, next_receipt, "different", update.Commands(root, evidence, dict(os.environ)), "different", None)
            self.assertEqual(next_receipt["status"], "rolled-back")
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(release / "manifest.json"), receipt["newManifest"])
            shutil.rmtree(candidate)
            fixture(candidate, "third")
            transformed = {"oldManifest": update.digest(release / "manifest.json"), "newManifest": update.digest(candidate / "manifest.json"), "status": "candidate-verified"}
            (candidate / "bin/cq").write_text("modified after candidate verification")
            sql = "BEGIN; CREATE TABLE cq_drivers(value text); UPDATE cq_schema_migrations SET checksum='next'; COMMIT;"
            with self.assertRaisesRegex(RuntimeError, "Package file differs"):
                update.install(root, release, candidate, root / "transform-rollback", evidence, transformed, "next",
                               update.Commands(root, evidence, dict(os.environ)), "schema", sql)
            self.assertEqual(transformed["status"], "rolled-back")
            self.assertFalse((root / ".cq-update-recovery.json").exists())
            self.assertEqual(update.digest(release / "manifest.json"), receipt["newManifest"])
            self.assertEqual(json.loads((evidence / "data-before.json").read_text()), json.loads((evidence / "data-restored.json").read_text()))



if __name__ == "__main__":
    unittest.main()
