# Dependency and compatibility record

Versions were resolved from the published Maven/npm metadata, Baboon release metadata, and the immutable Nix input on 2026-09-26. Prereleases were excluded. `flake.lock`, `project/build.properties`, `build.sbt`, `package-lock.json` and `dev/generate` hold executable pins.

| Component | Pin |
| --- | --- |
| Nixpkgs | `4975466d324710c576dc11ad614684e6bd8cad8e` |
| sbt | 2.0.9 |
| Scala | 3.9.0 |
| izumi BIO/distage/testkit | 1.2.25 |
| ZIO / Cats interop | 2.1.26 / 23.1.0.13 |
| http4s | 0.23.37 |
| Circe | 0.14.16 |
| PostgreSQL server / JDBC | 18.6 / 42.7.13 |
| Baboon | 0.0.196, upstream flake/compiler commit `c003b03a64b1e2ccdc060d20072c56f010beb111`, `baboon-jvm` package |
| GraalVM CE | 25.3.4.1, Java/native-image 25.0.4.1 |
| Node / TypeScript / esbuild | 24.20.0 / 7.0.2 / 0.28.2 |
| MCP test SDK / WebSocket test client | 1.30.1 / ws 8.22.0 |
| Go consumer-evaluation runtime | 1.27.1 (`go_1_27` from the existing Nixpkgs pin) |

The Go runtime was added for the required consumer evaluation corpus on 2026-09-27. The existing Nix input resolves `go_1_27.version` to 1.27.1, matching the stable release reported by the [official Go download metadata](https://go.dev/dl/?mode=json). The Nixpkgs input was not upgraded.

## Reproduced incompatibilities

1. Baboon's emitted Scala runtime uses two `do/while` loops, rejected by Scala 3, and its `tryConvert` method lacks the target `ClassTag`. The generation step applies exact-match checked changes: equivalent `while` conditions with the body evaluated first, and an explicit target type argument plus `ClassTag`. A changed upstream source fails generation rather than applying an uncertain patch. This is a local compatibility patch to a pinned runtime, not an upstream fix. The proof checks string-bearing binary round trips and compiles the facade; further production codec tests remain necessary.
2. Baboon's TypeScript runtime imports omit ESM file suffixes even when generated model imports use `.js`. NodeNext compilation fails. CQ uses TypeScript's Bundler resolution and esbuild, consistent with a browser bundle. Strict type checking passes; executing generated source directly as unbundled Node ESM remains unsupported.
3. Direct `make` calls inside a named distage plugin object fail source-position macro expansion with Scala 3.9.0 and 3.8.4. Moving bindings into an included `ModuleDef`, following the reference project's composition pattern, compiles on 3.9.0. The Scala pin remains 3.9.0.
4. The check runner uses sbt `--server --batch` and appends `exit`, preserving per-run environment configuration. In-process distage/ZIO tests passed but JVM shutdown remained busy in a ZIO shutdown hook loading a class through sbt's `ZombieClassLoader`. A captured thread dump identifies that path. Tests now use a forked JVM to keep the runtime's shutdown hooks outside sbt's retired test classloader. The PostgreSQL check passes and exits with this correction.
5. sbt 2 classpaths use virtual file references. `export Runtime/fullClasspath` prints virtual identifiers, not runnable paths. The build's `runtimeClasspath` task resolves them through sbt's `fileConverter`, following the [sbt 2 migration documentation](https://www.scala-sbt.org/2.x/docs/en/changes/migrating-from-sbt-1.x.html).
6. The initial native build reached linking but failed on `_dl_x86_cpu_features`. A standalone C `log2` call linked against the same static `libm.a` reproduces the failure. The installed GraalVM source shows its AVX2 sorting path adds this static dependency. CQ explicitly requests `-march=compatibility`, avoiding that architecture-specific path and defining a portable CPU baseline. This mitigates the toolchain incompatibility; AVX2-targeted native linking remains unsupported by this pinned configuration. The corrected CQ native build and real transport/PostgreSQL checks pass.

Initial failure logs are retained under `/srv/nvme/tmp/cq4-implementation/m0/` and the failed native run `20260926T164429-native/`. Native execution passes in `20260926T165018-native/`; the executable is 79.75 MiB and was built in 56 seconds on this host. This proves the stack path, not the future production supervisor or ledger coverage. Verified command manifests are linked from implementation status.

## Browser verification toolchain

Playwright 1.63.0 is pinned in `package-lock.json`. On 2026-09-26, `npm view playwright version engines --json` reported stable 1.63.0 and Node >=20; the pinned Nixpkgs `playwright-driver.version` also resolves to 1.63.0. The project uses Node 24.20.0. Linux development shells expose that Nixpkgs driver's matching browser bundle through `PLAYWRIGHT_BROWSERS_PATH`. Browser executables and their libraries come from the pinned Nix closure; the Ubuntu-oriented host package validation is disabled, while launch and real browser execution remain required checks. No system package installation is performed.

The [Playwright browser documentation](https://playwright.dev/docs/browsers) requires browser binaries matching the Playwright release. `dev/browser.mjs` uses Chromium, captures traces/screenshots, and checks real CQ HTTP/WebSocket operation. `dev/connection-browser.mjs` intercepts the real WebSocket connection to inject heartbeat/protocol faults; it is separate from real-model consumer evaluations.

## Baboon flake packaging

M6 replaces the manual compiler download with the upstream pinned flake package, preserving the existing compiler version/commit and CQ Nixpkgs pin. All 524 generated non-resource files match the previous compiler output byte-for-byte; generated contracts pass. [Package, platform and verification evidence](../validation/m6-toolchain.md). Upstream compiler dependencies retain their own lock; CQ does not override them with its development runtime.
