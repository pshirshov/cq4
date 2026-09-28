# M6 Baboon flake integration

The development shell now obtains the existing Baboon **0.0.196** compiler from the [upstream flake at the existing compiler revision](https://github.com/7mind/baboon/blob/c003b03a64b1e2ccdc060d20072c56f010beb111/flake.nix). `flake.lock` pins that source and its upstream inputs; CQ's root Nixpkgs revision is unchanged. The selected `baboon-jvm` output builds a staged compiler plus launcher on each declared platform. CQ's production native packaging remains separate M6 work.

`dev/generate` consumes the shell's explicit `CQ_BABOON` path, enters that pinned shell when the variable is absent, and verifies the compiler's version banner **before deleting generated output**. The manual download of a Linux-amd64 binary is removed. The existing exact-match Scala 3 runtime adaptations remain. CQ still has exactly one model at `0.1.0`; this increment adds no schema version or compatibility path.

The upstream Nix compiler build embeds a build timestamp and has no checkout Git metadata in its banner (`Baboon 0.0.196 @ #*`). Version validation uses the banner; source provenance comes from `flake.lock`. This report does not claim byte-identical compiler binaries across rebuilds.

## Actual evidence

Toolchain evidence root: `/srv/nvme/tmp/cq4-implementation/m6-toolchain-20260928`.

| Check | Observation |
| --- | --- |
| Upstream package build | `nix build --no-link --json github:7mind/baboon/c003b03a64b1e2ccdc060d20072c56f010beb111#baboon-jvm` succeeds on x86_64-linux; `baboon-nix-build.log` and `baboon-jvm-build.json` retain the build and store output. |
| Direct generator entrypoint | `./dev/generate` enters the new CQ shell and executes `/nix/store/2jglpkx8ajzjmg2zdm9b91ppqx45l0xv-baboon-jvm-0.0.196/bin/baboon-jvm`; version and generation log retained. |
| Old/new compiler output | `baboon-generation-comparison.json`: all **524** generated non-resource files are byte-identical to the prior downloaded compiler's output; zero additions or changes. |
| Invalid compiler precondition | An explicitly selected command emitting no Baboon banner is rejected with `Expected Baboon 0.0.196`, exit 1. All generated files are unchanged. `invalid-compiler.json` and log retain this boundary check. |
| Platform outputs | `baboon-platform-outputs.json` evaluates the upstream compiler derivations/version for x86_64-linux, aarch64-linux and aarch64-darwin. `cq-platform-shells.json` also evaluates each actual CQ development shell and its compiler path. This is evaluation evidence, not execution on the ARM systems. |
| Contracts | **`.work/evidence/20260928T040244-contracts` passes**: two deterministic generation/build passes, strict TypeScript, Scala contract export/verify, MCP schema export and cross-language round trips. |

All 279 non-documentation/non-README source/build/test hashes match the passing contract gate before commit. No model evaluation is necessary for a compiler-packaging change whose generated contracts are unchanged; the complete packaged release evaluation remains required in M6.

Independent Astra approved the final source/provenance and contract evidence with no blocking or major finding. The reviewer checked the 524-file output comparison, matching source hashes and invalid-compiler rejection before output mutation.

## Remaining M6 work

Complete native server/supervisor/CLI/frontend packaging, deterministic and native runtime gates, packaged nine-route consumer evaluation, backup/restore and operational evidence, exact install/run/verification instructions, release review and designated human acceptance.
