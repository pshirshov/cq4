package cq.server

import cq.api.*
import cq.host.*
import io.circe.Json
import java.net.URI
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class HarnessAdapterLocal extends AnyWordSpec {
  private def profile(harness: Harness): HarnessProfile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model", if (harness == Harness.Claude) "anthropic" else "selected-provider",
    HarnessUsage.version(harness), Nil, Set("PROVIDER_TOKEN"))
  private def invocation(role: Role, root: Path): HarnessInvocation = HarnessInvocation(role, AttemptId(UUID.randomUUID()), "Host role instructions",
    Json.obj("type" -> Json.fromString("object")), List(
      HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("scoped-domain-token", 2000)),
      HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("scoped-local-token", 2000))), root)
  private def adapters: List[HarnessAdapter] = List(new ClaudeAdapter, new CodexAdapter, new PiAdapter)
  private def environment: Map[String, String] = Map("HOME" -> "/test/home", "PATH" -> "/test/bin", "CQ_TOKEN" -> "operator-root-secret",
    "CQ_LOCAL_TOKEN" -> "parent-controller-secret", "CLAUDECODE" -> "parent-session", "CLAUDE_CODE_SAFE_MODE" -> "1",
    "PROVIDER_TOKEN" -> "configured-provider-secret", "UNRELATED_SECRET" -> "unrelated-secret")

  "Harness launch boundaries (Behavioral Active Blackbox; Group / filesystem Communication)" should {
    "restrict child-authored evidence to model-declared provenance in the output contract" in {
      val schemas = new McpSchemas()
      val modes = ExplorerMode.all.map(DispatchWork.Explorer.apply) :+ DispatchWork.Worker(WorkerMode.Probe)
      modes.foreach { mode =>
        val schema = schemas.childReport(mode)
        assert(schema.hcursor.downField("$defs").downField("cq_api_EvidenceOrigin").get[List[String]]("enum") == Right(List("ModelDeclared")))
      }
      assert(schemas.schema("EvidenceOrigin").hcursor.get[List[String]]("enum").toOption.get.toSet == EvidenceOrigin.all.map(_.toString).toSet)
    }

    "deliver complete canonical output contracts through Pi's native system instructions" in {
      val schemas = new McpSchemas()
      val modes = ExplorerMode.all.map(DispatchWork.Explorer.apply) ++ List(DispatchWork.Planner()) ++
        WorkerMode.all.map(DispatchWork.Worker.apply) ++ ReviewerMode.all.map(DispatchWork.Reviewer.apply)
      val children = modes.map { mode =>
        invocation(ChildContracts.role(mode), Path.of("/test/assets")).copy(
          system = new ChildInstructions()(mode), resultSchema = schemas.childReport(mode))
      }
      val governor = invocation(Role.Governor, Path.of("/test/assets")).copy(
        system = SupervisorProgram.Instructions, resultSchema = schemas.schema("GoverningReport"))
      (governor :: children.toList).foreach { original =>
        val prepared = schemas.nativeInvocation(Harness.Pi, original)
        val launch = new PiAdapter().launch(profile(Harness.Pi), prepared, environment)
        val system = launch.arguments(launch.arguments.indexOf("--system-prompt") + 1)
        assert(system.startsWith(original.system + "\n"))
        assert(io.circe.parser.parse(system.linesIterator.toList.last) == Right(original.resultSchema))
        assert(prepared.copy(system = original.system) == original)
        assert(system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768)
      }
    }

    "translate nested report unions for the Codex structured-output dialect" in {
      val report = new McpSchemas().childReport(DispatchWork.Planner())
      val launch = new CodexAdapter().launch(profile(Harness.Codex),
        invocation(Role.Planner, Path.of("/test/assets")).copy(resultSchema = report), environment)
      val native = io.circe.parser.parse(launch.assets.find(_.name == "result-schema.json").get.body).fold(throw _, identity)
      def hasOneOf(value: Json): Boolean = value.arrayOrObject(false, _.exists(hasOneOf),
        fields => fields.contains("oneOf") || fields.values.exists(hasOneOf))
      assert(hasOneOf(report))
      assert(!hasOneOf(native), "Codex rejects nested oneOf in the full Planner report schema")
      val checks = native.hcursor.downField("$defs").downField("cq_api_CohortCriterion").downField("properties").downField("checks")
      assert(!checks.downField("uniqueItems").succeeded)
      assert(checks.get[String]("description") == Right(CodexSchema.UniqueItemsRule))
      assert(launch.assets.find(_.name == "canonical-result-schema.json").get.body == report.noSpaces)
    }

    "reject output union translations whose alternatives can overlap" in {
      def union(types: String*): Json = Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj(
        "value" -> Json.obj("oneOf" -> Json.arr(types.map(value => Json.obj("type" -> Json.fromString(value)))*))))
      intercept[IllegalArgumentException](CodexSchema.result(union("string", "string")))
      intercept[IllegalArgumentException](CodexSchema.result(union("integer", "number")))
      intercept[IllegalArgumentException](CodexSchema.result(Json.obj("oneOf" -> Json.arr(Json.obj("type" -> Json.fromString("string"))))))
      val nullable = CodexSchema.result(union("string", "null"))
      assert(nullable.hcursor.downField("properties").downField("value").downField("anyOf").succeeded)
      val property = Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj("oneOf" -> Json.obj("type" -> Json.fromString("string"))))
      assert(CodexSchema.result(property) == property)
    }

    "isolate credentials and select explicit models without inherited harness control" in {
      adapters.foreach { adapter =>
        val launch = adapter.launch(profile(adapter.harness), invocation(Role.Worker, Path.of("/test/assets")), environment)
        assert(launch.environment("PROVIDER_TOKEN") == "configured-provider-secret")
        assert(!launch.environment.values.exists(value => Set("operator-root-secret", "parent-controller-secret", "parent-session", "unrelated-secret")(value)))
        assert(!launch.arguments.exists(value => value.contains("secret") || value.contains("scoped-domain-token") || value.contains("scoped-local-token")))
        assert(launch.arguments.contains("selected-model") && !launch.arguments.contains("--fallback-model"))
      }
      intercept[IllegalArgumentException](profile(Harness.Codex).copy(providerEnvironment = Set("CQ_TOKEN")))
      intercept[IllegalArgumentException](new PiAdapter().launch(profile(Harness.Pi), invocation(Role.Worker, Path.of("/test/assets")), environment - "PROVIDER_TOKEN"))
      intercept[IllegalArgumentException](new ClaudeAdapter().launch(profile(Harness.Claude).copy(provider = "unconfigured-provider"),
        invocation(Role.Worker, Path.of("/test/assets")), environment))
    }

    "give ledger mutation and dispatch only to governing profiles and native edits only to workers" in {
      val root = Path.of("/test/assets")
      val governor = invocation(Role.Governor, root)
      assert(governor.tools(McpTarget.Domain).contains("change") && governor.tools(McpTarget.Local) == List("dispatch") && !HarnessTools.edits(Role.Governor))
      List(Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer).foreach { role =>
        val child = invocation(role, root)
        assert(child.tools(McpTarget.Domain) == List("search", "read", "usage") && child.tools(McpTarget.Local) == List("workspace"))
        assert(HarnessTools.edits(role) == (role == Role.Worker))
        adapters.foreach { adapter =>
          val launch = adapter.launch(profile(adapter.harness), child, environment)
          assert(!launch.arguments.mkString(" ").contains("mcp__cq__change"))
          assert(!launch.assets.exists(asset => asset.body.contains("operator-root-secret")))
        }
      }
      intercept[IllegalArgumentException](invocation(Role.Human, root))
      intercept[IllegalArgumentException](invocation(Role.Collector, root))
    }

    "preserve configured toolchain discovery in child shells" in {
      val root = Files.createTempDirectory("cq-toolchain-").toAbsolutePath
      val probe = root.resolve("cq-toolchain-probe")
      try {
        Files.writeString(probe, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"))
        val source = sys.env.updated("PATH", root.toString + ":" + sys.env("PATH"))
        val environments = HostEnvironment.runtime(source) :: adapters.map { adapter =>
          adapter.launch(profile(adapter.harness).copy(providerEnvironment = Set.empty), invocation(Role.Worker, root), source).environment
        }
        environments.foreach { value =>
          val result = new BoundedHostCommand(value, Duration.ofSeconds(5), 4096)
            .run(root, List(sys.env("SHELL"), "-lc", "command -v cq-toolchain-probe"))
          assert(result.exit == 0 && result.text.trim == probe.toString, result)
        }
      } finally { Files.deleteIfExists(probe); Files.deleteIfExists(root) }
    }

    "materialize private immutable assets and reject divergent retries or symbolic replacements" in {
      val root = Files.createTempDirectory("cq-harness-assets-").toAbsolutePath
      val launch = new PiAdapter().launch(profile(Harness.Pi), invocation(Role.Reviewer, root), environment)
      launch.install(root)
      launch.install(root)
      assert(Files.getPosixFilePermissions(root) == PosixFilePermissions.fromString("rwx------"))
      launch.assets.foreach { asset =>
        assert(Files.readString(root.resolve(asset.name)) == asset.body)
        assert(Files.getPosixFilePermissions(root.resolve(asset.name)) == PosixFilePermissions.fromString("rw-------"))
      }
      val changed = launch.copy(assets = List(HarnessAsset("pi-mcp.json", "different")))
      intercept[IllegalArgumentException](changed.install(root))
      val link = root.resolve("replacement")
      Files.createSymbolicLink(link, root.resolve("pi-mcp.json"))
      intercept[IllegalArgumentException](launch.copy(assets = List(HarnessAsset("replacement", "different"))).install(root))
    }
  }
}
