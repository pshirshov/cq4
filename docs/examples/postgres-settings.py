#!/usr/bin/env python3
"""Bounded PostgreSQL presets; only owned disposable clusters may disable durability."""
import argparse
from enum import Enum
import os
from pathlib import Path

MIB = 1024 * 1024
MIN_LOCAL_MEMORY_MIB = 1024
MAX_LOCAL_MEMORY_MIB = 8192
MAX_CONNECTIONS = 64
PROTECTED_SETTINGS = frozenset(('fsync', 'synchronous_commit', 'full_page_writes', 'max_connections',
                                'include', 'include_if_exists', 'include_dir', 'config_file', 'data_directory'))


class DatabaseRole(Enum):
    Functional = 'functional'
    Durable = 'durable'


def cluster_settings(role: DatabaseRole) -> list[str]:
    if not isinstance(role, DatabaseRole): raise TypeError('An explicit DatabaseRole is required')
    durability = 'off' if role is DatabaseRole.Functional else 'on'
    return [f'fsync={durability}', f'synchronous_commit={durability}', f'full_page_writes={durability}',
            f'max_connections={MAX_CONNECTIONS}']


def validate_additions(settings: list[str]) -> None:
    for setting in settings:
        key, separator, value = setting.partition('=')
        if not separator or not value.strip() or key.strip().lower() in PROTECTED_SETTINGS or '\n' in setting:
            raise ValueError(f'Invalid or protected PostgreSQL setting: {key.strip()}')


def memory_budget_mib() -> int:
    available = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
    # cgroup paths must follow this process's membership, including nested groups.
    membership = Path('/proc/self/cgroup').read_text().splitlines()
    unified = [entry.split(':', 2)[2] for entry in membership if entry.startswith('0::')]
    if len(unified) == 1:
        relative = Path(unified[0].lstrip('/'))
        if '..' in relative.parts: raise ValueError('Cannot resolve the process cgroup memory limit')
        directory = Path('/sys/fs/cgroup') / relative
        while directory.is_relative_to('/sys/fs/cgroup'):
            limit = directory / 'memory.max'
            if limit.is_file():
                value = limit.read_text().strip()
                if value != 'max': available = min(available, int(value))
            if directory == Path('/sys/fs/cgroup'): break
            directory = directory.parent
    else:
        controllers = [entry.split(':', 2)[2] for entry in membership if 'memory' in entry.split(':', 2)[1].split(',')]
        if len(controllers) == 1:
            relative = Path(controllers[0].lstrip('/'))
            if '..' in relative.parts: raise ValueError('Cannot resolve the process cgroup memory limit')
            directory = Path('/sys/fs/cgroup/memory') / relative
            while directory.is_relative_to('/sys/fs/cgroup/memory'):
                limit = directory / 'memory.limit_in_bytes'
                if limit.is_file(): available = min(available, int(limit.read_text().strip()))
                if directory == Path('/sys/fs/cgroup/memory'): break
                directory = directory.parent
    return min(available // MIB, MAX_LOCAL_MEMORY_MIB)


def local_settings(memory_mib: int) -> list[str]:
    if memory_mib < MIN_LOCAL_MEMORY_MIB or memory_mib > MAX_LOCAL_MEMORY_MIB:
        raise ValueError(f'CQ local database memory budget must be {MIN_LOCAL_MEMORY_MIB}–{MAX_LOCAL_MEMORY_MIB} MiB')
    return cluster_settings(DatabaseRole.Durable) + [f"shared_buffers='{memory_mib // 4}MB'",
        f"effective_cache_size='{memory_mib * 3 // 4}MB'", 'random_page_cost=1.1', 'effective_io_concurrency=200']


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--memory-mib', type=int)
    arguments = parser.parse_args()
    available = memory_budget_mib()
    memory = available if arguments.memory_mib is None else arguments.memory_mib
    if memory > available: raise ValueError(f'Requested budget exceeds the detected {available} MiB limit')
    print('\n'.join(local_settings(memory)))


if __name__ == '__main__':
    main()
