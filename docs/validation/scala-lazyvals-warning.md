# Scala lazy-value startup warning

Human evaluation report: D25 in the `cq4` project's Defects ledger. This investigation does not change the pinned compiler or suppress warnings.

## Observations (2026-09-28)

- CQ is compiled with Scala **3.9.0**, with `izumi-reflect_3` **3.0.9**, on the pinned GraalVM JDK **25.0.4.1**.
- Running the JVM entry point with `--sun-misc-unsafe-memory-access=debug` identifies the first caller as `izumi.reflect.macrortti.LightTypeTag.<clinit>` → `scala.runtime.LazyVals$.getOffsetStatic` → `Unsafe.objectFieldOffset`.
- A minimal Java probe containing `Class.forName("izumi.reflect.macrortti.LightTypeTag")` exits successfully and emits the same warning with both the **3.9.0** and **3.10.0-RC3** Scala runtimes on CQ's dependency classpath. This tests the published dependency's runtime behavior, not recompilation of all dependencies with the 3.10 compiler.
- Maven Central's Scala library metadata contains 3.10.0-RC1–RC3, without a 3.10 stable release. The [maintainer's release thread](https://contributors.scala-lang.org/t/scala-3-10-0-release-thread/7546) announces RC3 on September 25.

Reproduction source, exact commands/classpaths, Maven metadata, both output logs and machine-readable results are retained in `/srv/nvme/tmp/cq4-scala-warning-20260928`. Run `nix develop /home/pavel/work/safe/cq4/cq4 -c python3 probe.py` from that directory.

## Interpretation and follow-up

The [Scala 3.9 release notes](https://scala-lang.org/news/3.9/) explain that Scala 3.8+ emits VarHandle-based lazy values, while dependencies compiled with earlier Scala versions retain the Unsafe-based implementation. The measured dependency behavior matches that explanation. Updating CQ's compiler alone does not rewrite the published dependency's bytecode.

The general compiler problem is already tracked and closed by [scala/scala3#9013](https://github.com/scala/scala3/issues/9013). There is no evidence here for a new compiler defect, so no duplicate issue was submitted. D25 remains open: identify a dependency release rebuilt with the new scheme, or evaluate the explicitly opt-in Sloth bytecode transformation described by Scala maintainers. Either would need dependency/native-image verification before adoption. Other dependencies may also use the legacy scheme; this probe identifies the first startup caller, not an exhaustive inventory.
