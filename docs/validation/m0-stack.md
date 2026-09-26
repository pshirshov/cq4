# M0 stack proof evidence

Date: 2026-09-26. Baseline: `7e3076a`. The implementation is identified by each run's `source-sha256.json`; all non-documentation source hashes were compared against the working tree before commit and matched. M0 milestone approval is pending final Astra review of the committed increment.

## Executed checks

Commands below used `CQ_EVIDENCE_ROOT=/srv/nvme/tmp/cq4-implementation`. The default Baboon download/checksum path was exercised without `CQ_BABOON` overrides.

| Command | Exit | Evidence directory under the durable root |
| --- | --- | --- |
| `./dev/check contracts` | 0 | `20260926T165405-contracts` |
| `./dev/check fast` | 0 | `20260926T165431-fast` |
| `./dev/check postgres` | 0 | `20260926T165440-postgres` |
| `./dev/check native` | 0 | `20260926T165501-native` |

Each directory contains `result.json`, `commands.json`, source hashes and command logs. These supersede earlier runs lacking the result/source manifests. Earlier failures are retained, not converted into passes.

Observed checks:

- Locked Baboon generation is deterministic across repeated runs. Generated Scala and strict TypeScript compile.
- Scala → TypeScript → Scala preserves 9,007,199,254,740,993, signed 64-bit maximum values, Unicode and a typed conflict error. Scala UEBA encoding/decoding and canonical identifier parsing pass.
- The 0.1.0 → 0.2.0 fixture conversion preserves existing fields and initializes an added optional field in both languages.
- The exact advertised MCP input schema accepts the valid wire example and rejects numeric revisions, invalid UUIDs and missing fields using the SDK's AJV provider.
- One shared observable repository scenario passes through the same distage plugin with `Repo.Dummy` and `Repo.Prod`. It checks absent reads, lossless persistence, overwrite behavior and two-project isolation. PostgreSQL uses a new local cluster, not a mocked driver.
- Actual JVM and native processes pass bearer denial, Origin denial, CQ version advertisement/rejection, project-scope denial, JSONB round trip, WebSocket exchange, and MCP SDK initialize/list/call checks. Processes and the owned PostgreSQL cluster terminate within the runner's deadlines.

## Native artifact

Executable: `/srv/nvme/tmp/cq4-implementation/20260926T165501-native/cq`.

- Size: 83,626,200 bytes.
- SHA-256: `161b67912758314643c3c845a92a288338e5bf0bae0ec77eae185150b3fd4f03`.
- Flags: `--no-fallback -march=compatibility -O1 -J-Xmx8g`, with reachability configuration captured by exercising the JVM path.
- Run with the explicit environment in [README](../../README.md), substituting this executable for the sbt invocation. It requires PostgreSQL and this host's Nix runtime closure. Portable distribution packaging is M6 work.

Native reachability metadata is evidence for the exercised path. Future ledger/supervisor functionality requires new native execution coverage. Source registration uses an explicit plugin list on both runtimes.

## Failures and corrections

The [dependency record](../design/dependencies.md) describes the exact corrections. Initial compiler/runtime logs and the captured sbt shutdown thread dump are in `m0/` under the durable root. The initial native failure is retained in `20260926T164429-native/`. A standalone C reproduction isolates the static math-library link failure. The explicit compatibility CPU target is a documented mitigation; no dependency pin was silently downgraded.

## Limits and next gates

This proves the required implementation stack and minimal contracts. It does not prove ledger history, claims, production authentication/role profiles, synchronization/recovery, browser interaction, usage accounting, supervisor lifetime or a consumer workflow. Those are owned by M1–M6. The provided-database schema path is implemented but this checkpoint exercised the runner-owned cluster path. MCP protocol support is explicitly limited to the advertised 2025 versions pending installed harness integration checks.

No live model evaluation was run for M0. No usage-efficiency improvement is claimed. M2 and M6 human acceptance remain outstanding. Bulky evidence is retained at `/srv/nvme/tmp/cq4-implementation/`; these files must remain available through release acceptance. The committed source, pins and scripts reproduce the checks.
