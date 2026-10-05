#!/usr/bin/env python3
"""Local native build, smoke check and recoverable replacement; full tests run separately."""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import runpy
import signal
import socket
import subprocess
import uuid
from typing import Callable, Protocol


SCHEMA_SOURCE = "server/src/main/resources/db/001-ledgers.sql"
MODEL_SOURCE = "models/cq-api.baboon"
ATTACHED_GOVERNOR_COLLECTOR = "CQ attached session; outer usage unavailable"
UNCHANGED_DATA = "unchanged-data"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def write_json(path: Path, value: dict) -> None:
    temporary = path.with_suffix(".tmp")
    with temporary.open("w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    sync_directory(path.parent)


class PackageStore(Protocol):
    def exists(self, path: Path) -> bool: ...
    def identity(self, path: Path) -> str: ...
    def rename(self, source: Path, destination: Path) -> None: ...


class Directories:
    def exists(self, path: Path) -> bool:
        return os.path.lexists(path)

    def identity(self, path: Path) -> str:
        return digest(path / "manifest.json")

    def rename(self, source: Path, destination: Path) -> None:
        require(not self.exists(destination), f"Replacement destination exists: {destination}")
        os.rename(source, destination)
        sync_directory(destination.parent)


def replace_packages(store: PackageStore, release: Path, candidate: Path, rollback: Path,
                     old: str, new: str, installed: Callable[[], None]) -> None:
    require(not store.exists(rollback), "Rollback destination already exists")
    require(store.identity(release) == old and store.identity(candidate) == new, "Package changed before replacement")
    try:
        store.rename(release, rollback)
        store.rename(candidate, release)
        require(store.identity(release) == new and store.identity(rollback) == old, "Installed package identity differs")
        installed()
    except BaseException:
        # Inspect actual directories: interruption can occur after rename but before its caller returns.
        if not store.exists(candidate) and store.exists(rollback):
            require(store.identity(release) == new, "Cannot recover an unrecognized installed package")
            store.rename(release, candidate)
        if store.exists(rollback) and not store.exists(release):
            store.rename(rollback, release)
        require(store.identity(release) == old and store.identity(candidate) == new, "Package recovery is incomplete")
        raise


def package(path: Path) -> dict:
    require(not path.is_symlink(), f"Package must be a direct directory: {path}")
    manifest = json.loads((path / "manifest.json").read_text())
    require(manifest["modelVersion"] == "0.1.0" and manifest["platform"] == "x86_64-linux", "Unsupported package")
    require({"bin/cq", "bin/cq-guardian", "runtime.nar", "runtime-paths.txt"} <= manifest["filesSha256"].keys(), "Incomplete package")
    for name, expected in manifest["filesSha256"].items():
        file = (path / name).resolve(strict=True)
        require(file.is_relative_to(path) and digest(file) == expected, f"Package file differs: {name}")
    for name in ("cq", "cq-guardian"):
        require(os.access(path / "bin" / name, os.X_OK), f"Not executable: {name}")
    return manifest


def compatible(before: dict, after: dict, step: dict) -> str:
    old, new = before["runtimeSourceSha256"], after["runtimeSourceSha256"]
    require(old[SCHEMA_SOURCE] == new[SCHEMA_SOURCE], "Schema changes require a matching explicit local update step; replacement refused")
    if old[MODEL_SOURCE] != new[MODEL_SOURCE]:
        require(step["kind"] == UNCHANGED_DATA and step["schema"] == old[SCHEMA_SOURCE]
                and step["modelBefore"] == old[MODEL_SOURCE] and step["modelAfter"] == new[MODEL_SOURCE],
                "Model changes require a matching explicit data update step; replacement refused")
    return new[SCHEMA_SOURCE]


class Commands:
    def __init__(self, root: Path, evidence: Path, environment: dict[str, str]):
        self.root = root
        self.evidence = evidence
        self.environment = environment

    def run(self, arguments: list[str], label: str, timeout: int) -> str:
        log = self.evidence / (label + ".log")
        print(f"{label}: {log}", flush=True)
        with log.open("w") as stream:
            result = subprocess.run(arguments, cwd=self.root, env=self.environment, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=timeout, close_fds=True)
        require(result.returncode == 0, f"{label} failed ({result.returncode}); inspect {log}")
        return log.read_text()


def build_candidate(build: Commands, evidence: Path, revision: str, candidate: Path, metadata: Path) -> None:
    build.run(["nix", "develop", "-c", "./dev/package-local", "--output", str(candidate),
               "--evidence-root", str(evidence / "local-build"), "--revision", revision, "--metadata", str(metadata)], "package-local", 1200)


def verify_local_candidate(manifest: dict, revision: str, smoke: Path) -> None:
    require(manifest["runtime"] == "native" and manifest["validation"] == "local-smoke",
            "Local candidate has not passed native smoke")
    require(manifest["sourceRevision"] == revision, "Local candidate revision differs from snapshot")
    require(manifest["localSmokeSha256"] == digest(smoke) and json.loads(smoke.read_text())["status"] == "passed",
            "Local smoke evidence differs or did not pass")


def main() -> None:
    os.umask(0o077)
    root = Path(__file__).resolve().parent.parent
    state_input = Path(os.environ.get("CQ_LOCAL_STATE", "/srv/nvme/tmp/cq4-playground"))
    require(state_input.is_absolute() and not state_input.is_symlink(), "CQ_LOCAL_STATE must name a direct absolute directory")
    state = state_input.resolve(strict=True)
    require(state.stat().st_uid == os.getuid() and (state / ".cq-local").is_file(), "Not an owned CQ local state directory")
    release = root / ".local/release"
    before = package(release)
    old = digest(release / "manifest.json")
    descriptor = os.open(state / "launcher.lock", os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        require(not os.path.lexists(state / ".cq-update-recovery.json"), "Unresolved update recovery marker; inspect it before retrying")
        data = state / "postgres"
        require(not data.is_symlink(), "PostgreSQL data must be a direct directory")
        require((data / "PG_VERSION").is_file() and not os.path.lexists(data / "postmaster.pid"), "Stop run-local and PostgreSQL before updating")
        attempt = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8]
        evidence = state / "updates" / attempt
        evidence.mkdir(parents=True, mode=0o700)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        checkout = evidence / "source"
        candidate = release.with_name("release-candidate-" + attempt)
        rollback = release.with_name("release-before-" + attempt)
        receipt = {"status": "building", "sourceRevision": revision, "state": str(state), "release": str(release),
                   "candidate": str(candidate), "rollbackPackage": str(rollback), "oldManifest": old,
                   "runtime": "native", "validation": "local-smoke"}
        write_json(evidence / "receipt.json", receipt)
        commands = Commands(root, evidence, dict(os.environ))
        commands.run(["git", "worktree", "add", "--detach", str(checkout), revision], "source-snapshot", 60)
        try:
            runtime_sources = runpy.run_path(str(root / "dev/package"))["runtime_sources"]
            require(runtime_sources(root) == runtime_sources(checkout), "Uncommitted runtime inputs differ from HEAD; commit them before updating")
            compatible(before, {"runtimeSourceSha256": runtime_sources(checkout)},
                       json.loads((checkout / "dev/local-update-step.json").read_text()))
            build = Commands(checkout, evidence, {**os.environ, "CQ_EVIDENCE_ROOT": str(evidence / "gates")})
            metadata = release / "native-config" if (release / "native-config").is_dir() else Path(before["nativeEvidence"]) / "native-config"
            build_candidate(build, evidence, revision, candidate, metadata)
            after = package(candidate)
            verify_local_candidate(after, revision, evidence / "local-build" / "native-smoke.json")
            require(after["runtimeSourceSha256"] == runtime_sources(checkout), "Candidate sources differ from the snapshot")
            step = json.loads((checkout / "dev/local-update-step.json").read_text())
            schema = compatible(before, after, step)
            new = digest(candidate / "manifest.json")
            receipt.update(status="candidate-verified", newManifest=new, schema=schema)
            write_json(evidence / "receipt.json", receipt)
            # The only step kind accepted leaves the stored data as it is: no SQL is applied.
            install(state, release, candidate, rollback, evidence, receipt, schema, commands, None)
        except BaseException as error:
            if receipt["status"] not in ("installed", "recovery-required", "rolled-back"):
                receipt.update(status="failed-before-install", error=str(error))
                write_json(evidence / "receipt.json", receipt)
            raise
        finally:
            commands.run(["git", "worktree", "remove", "--force", str(checkout)], "source-cleanup", 60)
        print(f"Installed source {revision}; manifest {receipt['newManifest']}. Receipt: {evidence / 'receipt.json'}")
        print("Restart ./run-local.sh and reload the browser. Rollback needs the retained package AND database backup.")


def install(state: Path, release: Path, candidate: Path, rollback: Path, evidence: Path,
            receipt: dict, schema: str, commands: Commands, sql: str | None) -> None:
    data = state / "postgres"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    environment = {key: value for key, value in os.environ.items() if not key.startswith("PG")}
    environment.update(PGHOST="127.0.0.1", PGPORT=str(port), PGUSER="cq", PGDATABASE="postgres",
                       PGPASSWORD=(state / "database-password").read_text().strip())
    database = Commands(commands.root, evidence, environment)
    marker = state / ".cq-update-recovery.json"
    write_json(marker, {"receipt": str(evidence / "receipt.json"), "recovery": "Stop owned processes. Verify the receipt's oldManifest package and before.dump database pair before removing this marker."})
    owned = False
    transformation_attempted = False
    before_data = None
    backup = None

    def query(statement: str, label: str) -> str:
        return database.run(["psql", "--no-psqlrc", "-v", "ON_ERROR_STOP=1", "-At", "-c", statement], label, 120).strip()

    def fingerprints(label: str) -> dict[str, str]:
        tables = query("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", label + "-tables").splitlines()
        values = {}
        for table in tables:
            require(table.startswith("cq_") and all(character.isalnum() or character == '_' for character in table), "Unexpected database table identity")
            rows = query(f'SELECT to_jsonb(t) FROM "{table}" t ORDER BY (to_jsonb(t))::text', label + "-" + table)
            values[table] = hashlib.sha256(rows.encode()).hexdigest()
        write_json(evidence / (label + ".json"), values)
        return values

    def interrupted(number, frame):
        raise KeyboardInterrupt(f"Interrupted by signal {number}")

    previous = {number: signal.signal(number, interrupted) for number in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        owned = True
        database.run(["pg_ctl", "-D", str(data), "-l", str(evidence / "postgres.log"), "-o",
                      f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "-t", "30", "start"], "database-start", 40)
        clients = database.run(["psql", "--no-psqlrc", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                               "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()"], "other-clients", 30).strip()
        require(clients == "0", "Another database client is connected; update refused")
        actual = database.run(["psql", "--no-psqlrc", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                              "SELECT checksum FROM cq_schema_migrations WHERE version=1"], "schema-before", 30).strip()
        require(actual == schema, "Database schema differs from package; update refused")
        attached = f"parent_id IS NULL AND body->>'role' = 'Governor' AND body->>'collector' = '{ATTACHED_GOVERNOR_COLLECTOR}'"
        pending = json.loads(query("SELECT json_build_object(" +
                        "'claims', (SELECT count(*) FROM cq_claims WHERE NOT released AND expires_at > (extract(epoch FROM clock_timestamp()) * 1000)::bigint), " +
                        "'managedAttempts', (SELECT count(*) FROM cq_usage_attempts WHERE effective_outcome IS NULL AND NOT COALESCE((" + attached + "), false)), " +
                        "'pendingIntegrations', (SELECT count(*) FROM cq_integrations WHERE jsonb_exists(body->'resolution','Pending')), " +
                        "'incompleteAttachedGovernors', (SELECT count(*) FROM cq_usage_attempts WHERE effective_outcome IS NULL AND " + attached + "))", "unsettled-work"))
        receipt["unsettledWork"] = pending
        require(pending["claims"] == 0 and pending["managedAttempts"] == 0 and pending["pendingIntegrations"] == 0,
                f"Reconcile active claims, managed attempts and pending integrations before updating: {pending}")
        backup = evidence / "before.dump"
        database.run(["pg_dump", "--format=custom", "--file", str(backup)], "database-backup", 600)
        database.run(["pg_restore", "--list", str(backup)], "backup-inventory", 30)
        with backup.open("rb") as stream:
            os.fsync(stream.fileno())
        sync_directory(evidence)
        receipt.update(backup=str(backup), backupSha256=digest(backup))
        write_json(evidence / "receipt.json", receipt)
        if sql is not None:
            before_data = fingerprints("data-before")
            transformation_attempted = True
            query(sql, "data-update")
            after_data = fingerprints("data-after")
            receipt.update(dataChanged=sorted(table for table in set(before_data) | set(after_data) if before_data.get(table) != after_data.get(table)))
            write_json(evidence / "receipt.json", receipt)
        database.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "-t", "30", "stop"], "database-stop", 40)
        owned = False
        require(not os.path.lexists(data / "postmaster.pid"), "Database shutdown is incomplete")

        def installed() -> None:
            receipt.update(status="installed", databaseStopped=True)
            write_json(evidence / "receipt.json", receipt)

        package(release)
        package(candidate)
        for path in candidate.rglob("*"):
            if path.is_file():
                with path.open("rb") as stream:
                    os.fsync(stream.fileno())
        for directory in sorted((path for path in candidate.rglob("*") if path.is_dir()), reverse=True):
            sync_directory(directory)
        sync_directory(candidate)
        replace_packages(Directories(), release, candidate, rollback, receipt["oldManifest"], receipt["newManifest"], installed)
    except BaseException as error:
        for number in previous:
            signal.signal(number, signal.SIG_IGN)
        receipt.update(status="recovery-required", error=str(error))
        write_json(evidence / "receipt.json", receipt)
        try:
            if transformation_attempted:
                if not owned:
                    owned = True
                    database.run(["pg_ctl", "-D", str(data), "-l", str(evidence / "postgres-recovery.log"), "-o",
                                  f"-h 127.0.0.1 -p {port} -c unix_socket_directories=''", "-w", "-t", "30", "start"], "database-recovery-start", 40)
                # One transaction: a restore that fails leaves the database as the failed step left it, never partly dropped.
                database.run(["pg_restore", "--clean", "--if-exists", "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges",
                              "--dbname", "postgres", str(backup)], "database-rollback", 600)
                require(fingerprints("data-restored") == before_data, "Restored database differs from backup state")
                require(query("SELECT checksum FROM cq_schema_migrations WHERE version=1", "schema-restored") == schema, "Restored schema differs")
                receipt.update(databaseRestored=True)
        finally:
            if owned:
                database.run(["pg_ctl", "-D", str(data), "-m", "fast", "-w", "-t", "30", "stop"], "database-recovery-stop", 40)
                owned = False
        require(not os.path.lexists(data / "postmaster.pid"), "Database recovery is incomplete")
        require(digest(release / "manifest.json") == receipt["oldManifest"], "Package recovery is incomplete")
        receipt.update(status="rolled-back", databaseStopped=True)
        write_json(evidence / "receipt.json", receipt)
        marker.unlink()
        sync_directory(state)
        raise
    else:
        marker.unlink()
        sync_directory(state)
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


if __name__ == "__main__":
    try:
        main()
    except BlockingIOError:
        raise SystemExit("The launcher owns the state. Stop ./run-local.sh with Ctrl-C and wait for CQ stopped.")
