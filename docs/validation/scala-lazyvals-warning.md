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

## Rejected remedy, 2026-09-29

D25 remains **Open**. The remaining-defect implementation did not establish a
safe warning-free dependency set. No warning suppression or dependency change
was adopted.

Evidence: `/srv/nvme/tmp/cq4-remaining-defects-20260928/scala-remedy`.

- Fresh Maven metadata still has Scala 3.9.0 as the latest stable library and
  3.10.0-RC3 as a candidate. The latest stable izumi-reflect 3.0.10 also emits
  `LazyVals.getOffsetStatic` warnings in the original minimal probe.
  Its [release note](https://github.com/zio/izumi-reflect/releases/tag/v3.0.10)
  describes a macro correction, not a rebuild of legacy lazy values.
- [Sloth](https://github.com/VirtusLab/sloth) is explicitly alpha quality. Its
  latest tag and current default branch both identify
  `ed68a4abad1f5c098a7ff48a30c273b8791e5a69` / `0.1.0-M2`. The CLI was built in
  scratch storage, then used on copies of 33 dependency JARs. Original dependency
  caches and CQ build inputs were unchanged. `transforms.json` retains exact
  input/output hashes and candidate class names.
- The transformed `LightTypeTag` probe passes even with Unsafe memory access
  denied. That does not establish application correctness: original CQ
  `query --help` exits 0; transformed CQ times out after 15 seconds both with
  default JVM policy and with Unsafe denied (`startup-results.json`).
- A thread dump after 69 seconds places the main thread in
  `LogstageCodecString$lzyINIT1`, repeatedly executing VarHandle compare-and-set.
  This locates the observed spin; it does not establish its underlying cause.
  A standalone call to the same accessor succeeds in both builds, so that
  attempted reduction is explicitly not a minimal reproduction.

Astra independently inspected these results and supports rejecting the candidate
remedy while retaining D25. A future correction needs a safe rebuilt dependency
set or a demonstrated transformer correction, followed by JVM and native checks.
The existing compiler issue does not establish that this separate transformation
failure belongs in scalac; no duplicate or speculative upstream report was sent.
