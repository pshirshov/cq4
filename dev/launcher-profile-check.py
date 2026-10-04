#!/usr/bin/env python3
"""Behavioral/Active/Blackbox local launcher checks with a real native release and PostgreSQL."""
import argparse
import json
import os
from pathlib import Path
import runpy
import signal
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
STARTUP_SECONDS = 60
SHUTDOWN_SECONDS = 60


def verify(release: Path, evidence: Path) -> None:
    evidence.mkdir(parents=True, exist_ok=False)
    support = runpy.run_path(str(ROOT / 'dev/check'))
    port = support['free_port'](); database_port = support['free_port']()
    while database_port == port: database_port = support['free_port']()
    state = evidence / 'state'
    environment = {key: value for key, value in os.environ.items() if not key.startswith(('CQ_', 'PG'))}
    environment.update(CQ_LOCAL_PORT=str(port), CQ_LOCAL_DB_PORT=str(database_port), CQ_ORIGIN=f'http://127.0.0.1:{port}')
    settings = []
    for index, memory in enumerate([2048, 1024, 1024]):
        log = evidence / f'launcher-{index + 1}.log'
        environment['CQ_LOCAL_DATABASE_MEMORY_MIB'] = str(memory)
        with log.open('w') as output:
            process = subprocess.Popen(['bash', str(ROOT / 'docs/examples/launch-local.sh'), str(release), str(state)],
                cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + STARTUP_SECONDS
                if index == 2:
                    process.wait(timeout=STARTUP_SECONDS)
                    assert process.returncode != 0
                    assert 'random_page_cost differs from the declared profile' in log.read_text()
                    assert not (state / 'postgres/postmaster.pid').exists()
                    break
                while True:
                    if process.poll() is not None: raise AssertionError(f'Launcher exited; inspect {log}')
                    try:
                        token = (state / 'token').read_text().strip()
                        request = urllib.request.Request(environment['CQ_ORIGIN'] + '/api/hello', headers={'Authorization': 'Bearer ' + token,
                            'CQ-Session': '00000000-0000-0000-0000-000000000001'})
                        with urllib.request.urlopen(request, timeout=1) as response: assert json.load(response)['version'] == '0.1.0'
                        break
                    except (FileNotFoundError, urllib.error.URLError, TimeoutError):
                        assert time.monotonic() < deadline, f'Launcher startup timed out; inspect {log}'
                        time.sleep(0.1)
                database_environment = {**environment, 'PGPASSWORD': (state / 'database-password').read_text().strip()}
                command = ['psql', '--no-psqlrc', '--set', 'ON_ERROR_STOP=1', '--host', '127.0.0.1', '--port', str(database_port),
                    '--username', 'cq', '--dbname', 'postgres', '--tuples-only', '--no-align']
                observed = json.loads((state / 'logs/postgres-settings.json').read_text()); settings.append(observed)
                assert all(observed[key] == 'on' for key in ['fsync', 'synchronous_commit', 'full_page_writes'])
                assert int(observed['shared_buffers']) == memory // 4 * 1024 * 1024
                assert int(observed['effective_cache_size']) == memory * 3 // 4 * 1024 * 1024
                assert observed['random_page_cost'] == '1.1' and observed['effective_io_concurrency'] == '200'
                if index == 0:
                    sql = 'CREATE TABLE cq_profile_marker(value text); INSERT INTO cq_profile_marker VALUES (\'retained\'); ALTER SYSTEM SET fsync=off;'
                    # ALTER SYSTEM cannot share a transaction with another statement.
                    subprocess.run(command + ['--command', sql.split(' ALTER SYSTEM')[0]], env=database_environment, check=True, capture_output=True)
                    subprocess.run(command + ['--command', 'ALTER SYSTEM SET fsync=off'], env=database_environment, check=True, capture_output=True)
                else:
                    result = subprocess.run(command + ['--command', 'SELECT value FROM cq_profile_marker'], env=database_environment,
                        check=True, capture_output=True, text=True)
                    assert result.stdout.strip() == 'retained'
                    subprocess.run(command + ['--command', 'ALTER SYSTEM SET random_page_cost=4'], env=database_environment, check=True, capture_output=True)
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM); process.wait(timeout=SHUTDOWN_SECONDS)
                assert not (state / 'postgres/postmaster.pid').exists(), 'Owned PostgreSQL cluster remained running'
    configuration = (state / 'postgres/postgresql.conf').read_text()
    assert configuration.splitlines().count("include = 'cq-local.conf'") == 1
    (evidence / 'results.json').write_text(json.dumps({'settings': settings, 'durableRestartPreservedData': True,
        'durabilityOverrideBlocked': True, 'configurationCollisionRefused': True, 'includeIdempotent': True}, indent=2) + '\n')
    print('PASS: local memory/planner settings, durable restart, override protection, idempotent config and owned cleanup')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    arguments = parser.parse_args()
    verify(arguments.release.resolve(strict=True), arguments.evidence)
