#!/usr/bin/env python3
"""Behavioral/Active/Blackbox checks at the real PostgreSQL boundary."""
import argparse
import json
import os
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parent.parent


def verify(evidence: Path) -> None:
    support = runpy.run_path(str(ROOT / 'dev/check'))
    presets = support['POSTGRES_PRESETS']
    role = support['DatabaseRole']
    for addition in ['fsync=off', ' SYNCHRONOUS_COMMIT = off', 'full_page_writes=off', 'max_connections=1000',
                     "include='unsafe.conf'", 'config_file=unsafe.conf', 'work_mem=4MB\nfsync=off']:
        try: presets['validate_additions']([addition])
        except ValueError: pass
        else: raise AssertionError(f'Protected profile override accepted: {addition}')
    for memory in [0, 1023, 8193]:
        try: presets['local_settings'](memory)
        except ValueError: pass
        else: raise AssertionError('Out-of-bounds local memory accepted')
    for memory in [1024, 2048, 8192]:
        settings = presets['local_settings'](memory)
        assert all(f'{key}=on' in settings for key in ('fsync', 'synchronous_commit', 'full_page_writes'))
        assert f"shared_buffers='{memory // 4}MB'" in settings
    environment = {key: value for key, value in os.environ.items() if not key.startswith(('CQ_', 'PG'))}
    settings: list[dict[str, str]] = []
    addresses = []
    for profile in [role.Functional, role.Durable]:
        directory = evidence / profile.value; directory.mkdir(parents=True, exist_ok=False)
        checks = support['Checks'](directory, dict(environment))
        with checks.database(profile, []):
            addresses.append(checks.environment['CQ_DATABASE_URL'])
            settings.append(json.loads((directory / f'database-{profile.value}-settings.log').read_text()))
            supplied = {**checks.environment, 'CQ_TEST_DATABASE_URL': checks.environment['CQ_DATABASE_URL'],
                'CQ_TEST_DATABASE_USER': checks.environment['CQ_DATABASE_USER'], 'CQ_TEST_DATABASE_PASSWORD': ''}
            external_directory = directory / 'external'; external_directory.mkdir()
            external = support['Checks'](external_directory, supplied)
            try:
                with external.database(role.Functional, []): raise AssertionError('An external database became disposable')
            except ValueError as error: assert 'owned local' in str(error)
            if profile is role.Functional:
                try:
                    with external.database(role.Durable, []): raise AssertionError('Durable evidence accepted a nondurable external server')
                except ValueError as error: assert 'require fsync' in str(error)
            else:
                with external.database(role.Durable, []): pass
            observed = checks.run(['psql', '--no-psqlrc', '--set', 'ON_ERROR_STOP=1', '--dbname',
                checks.environment['CQ_DATABASE_URL'].removeprefix('jdbc:'), '--tuples-only', '--no-align', '--command',
                "SELECT json_object_agg(name,setting) FROM pg_settings WHERE name IN ('fsync','synchronous_commit','full_page_writes','max_connections')"],
                'settings-after-external-check', support['STARTUP_TIMEOUT'])
            assert json.loads(observed) == settings[-1], 'External validation changed cluster settings'
    assert addresses[0] != addresses[1], 'Cluster roles must have separate lifetimes'
    assert settings[0]['fsync'] == 'off' and settings[1]['fsync'] == 'on'
    (evidence / 'results.json').write_text(json.dumps({'profiles': settings, 'protectedOverridesRejected': True,
        'externalDurabilityValidated': True, 'separateLifetimes': True}, indent=2) + '\n')
    print('PASS: real functional/durable cluster boundaries, protected settings, local memory bounds and external server validation')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    arguments = parser.parse_args()
    verify(arguments.evidence)
