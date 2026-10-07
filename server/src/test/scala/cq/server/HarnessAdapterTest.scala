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
  private def profile(harness: Harness): HarnessProfile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model", if (harness == Harness.Claude) "anthropic" else "selected-provider", None,
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
    "pass a route's effort to each harness in its own spelling, and no effort argument when the route states none" in {
      val worker = invocation(Role.Worker, Path.of("/test/assets"))
      def arguments(harness: Harness, effort: Option[Effort]): List[String] =
        adapters.find(_.harness == harness).get.launch(profile(harness).copy(effort = effort), worker, environment).arguments
      for (harness <- Harness.all; effort <- Effort.all.filter(cq.core.AgentResolution.efforts(harness))) {
        val text = effort.toString.toLowerCase
        val launched = arguments(harness, Some(effort))
        val expected = harness match {
          case Harness.Claude => List("--model", "selected-model", "--effort", text)
          case Harness.Pi => List("--provider", "selected-provider", "--model", "selected-model", "--thinking", text)
          case Harness.Codex => List("-c", "model_provider=\"selected-provider\"", "-c", s"model_reasoning_effort=\"$text\"")
        }
        assert(launched.containsSlice(expected), s"$harness $effort")
        // Nothing else of the launch depends on the effort.
        assert(launched.diff(arguments(harness, None)).sorted == expected.takeRight(2).sorted && arguments(harness, None).diff(launched).isEmpty, s"$harness $effort")
      }
      Harness.all.foreach { harness =>
        val plain = arguments(harness, None)
        assert(!plain.exists(argument => Set("--effort", "--thinking")(argument) || argument.startsWith("model_reasoning_effort")), harness)
      }
      // A level the harness does not name is refused and never passed on: Claude Code and Pi would run their default level instead.
      for (harness <- Harness.all; effort <- Effort.all.filterNot(cq.core.AgentResolution.efforts(harness)))
        assert(intercept[IllegalArgumentException](arguments(harness, Some(effort))).getMessage.contains(HarnessAdapter.EffortUnsupported), s"$harness $effort")
      assert(!cq.core.AgentResolution.efforts(Harness.Claude)(Effort.Off) && !cq.core.AgentResolution.efforts(Harness.Pi)(Effort.Ultra) &&
        cq.core.AgentResolution.efforts(Harness.Codex)(Effort.Ultra))
    }

    "refuse a Pi model name that Pi would read as a model and a thinking level" in {
      val pi = new PiAdapter()
      def launch(model: String, effort: Option[Effort]): HarnessLaunch =
        pi.launch(profile(Harness.Pi).copy(model = model, effort = effort), invocation(Role.Worker, Path.of("/test/assets")), environment)
      List(None, Some(Effort.Low)).foreach { effort =>
        assert(intercept[IllegalArgumentException](launch("glm:high", effort)).getMessage.contains(PiAdapter.AmbiguousModel))
        assert(launch("gpt-4o:extended", effort).arguments.containsSlice(List("--model", "gpt-4o:extended")))
      }
      // The other harnesses pass the name on.
      List(new ClaudeAdapter, new CodexAdapter).foreach { adapter =>
        assert(adapter.launch(profile(adapter.harness).copy(model = "m:high"), invocation(Role.Worker, Path.of("/test/assets")), environment).arguments.containsSlice(List("--model", "m:high")))
      }
    }

    "take the model, the provider and the effort of a launch from its route and the rest from the settings entry" in {
      val setting = HarnessSetting(Harness.Codex, "/test/codex", "entry-model", "entry-provider", HarnessUsage.version(Harness.Codex), Nil, Set("PROVIDER_TOKEN"))
      assert(HarnessProfile.route(setting) == ModelRoute(Harness.Codex, Some("entry-provider"), "entry-model", None))
      assert(HarnessProfile(setting, HarnessProfile.route(setting)) == HarnessProfile(Harness.Codex, Path.of("/test/codex"), "entry-model", "entry-provider", None,
        setting.version, Nil, Set("PROVIDER_TOKEN")))
      val routed = HarnessProfile(setting, ModelRoute(Harness.Codex, Some("route-provider"), "route-model", Some(Effort.Ultra)))
      assert((routed.model, routed.provider, routed.effort, routed.executable, routed.version) ==
        ("route-model", "route-provider", Some(Effort.Ultra), Path.of("/test/codex"), setting.version))
      // A route without a provider takes the entry's.
      assert(HarnessProfile(setting, ModelRoute(Harness.Codex, None, "route-model", None)).provider == "entry-provider")
      intercept[IllegalArgumentException](HarnessProfile(setting, ModelRoute(Harness.Pi, Some("zai"), "glm", None)))
      val launched = new CodexAdapter().launch(routed, invocation(Role.Worker, Path.of("/test/assets")), environment).arguments
      assert(launched.containsSlice(List("--model", "route-model")) &&
        launched.containsSlice(List("-c", "model_provider=\"route-provider\"", "-c", "model_reasoning_effort=\"ultra\"")))
    }

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
        assert(system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= HarnessInvocation.MaxSystemBytes)
      }
    }

    "keep the Governor's complete native instructions within the launch bound on every harness" in {
      val schemas = new McpSchemas()
      val governor = invocation(Role.Governor, Path.of("/test/assets")).copy(
        system = SupervisorProgram.Instructions, resultSchema = schemas.schema("GoverningReport"))
      adapters.foreach { adapter =>
        val prepared = schemas.nativeInvocation(adapter.harness, governor)
        val launch = adapter.launch(profile(adapter.harness), prepared, environment)
        assert(prepared.system.startsWith(governor.system) && launch.arguments.nonEmpty)
        launch.arguments.foreach(argument => assert(argument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= HarnessLaunch.MaxArgumentBytes, adapter.harness))
      }
    }

    "I17: read as an abstention only what refuses the route or its settings entry, and let every other fault before a launch through" in {
      import cq.host.{Abstention, RouteRefusal}
      def launched(step: => Unit): Either[Throwable, Unit] = scala.util.Try(Abstention.unless(AbstentionReason.Launch)(step)).toEither
      // A fault of the host is a failure with its own text: another model would meet the same disk.
      val disk = new java.io.IOException("No space left on device")
      val bound = new IllegalArgumentException("requirement failed: Harness launch argument exceeds the operating system's per-argument limit")
      assert(launched(throw disk) == Left(disk) && launched(throw bound) == Left(bound) && launched(()) == Right(()))
      assert(launched(RouteRefusal.unless(false, "Configured provider environment is unavailable")) ==
        Left(Abstention(AbstentionReason.Launch, "Configured provider environment is unavailable")))
      // The refusals of a route: the settings entry, the effort, the provider environment, a provider Claude does not have, a Pi name.
      val setting = HarnessSetting(Harness.Pi, "/bin/pi", "model", "provider", "0.99.1", Nil, Set.empty)
      def refusal(step: => Any): String = intercept[RouteRefusal](step).getMessage
      assert(refusal(HarnessProfile(setting.copy(version = "0.0.1"), ModelRoute(Harness.Pi, None, "model", None))).contains(HarnessProfile.Unverified))
      assert(refusal(HarnessProfile(setting, ModelRoute(Harness.Pi, None, "", None))).contains("Explicit harness model/provider required"))
      assert(refusal(HarnessProfile(setting, ModelRoute(Harness.Codex, None, "model", None))).contains("Model route and settings entry name different harnesses"))
      val pi = HarnessProfile(setting, ModelRoute(Harness.Pi, None, "model", None))
      assert(refusal(HarnessEnvironment.isolated(pi.copy(providerEnvironment = Set("ABSENT_KEY")), Map("HOME" -> "/h", "PATH" -> "/p"))).contains("Configured provider environment is unavailable"))
      // A host without HOME or PATH is no property of the route.
      val bare = intercept[IllegalArgumentException](HarnessEnvironment.isolated(pi, Map.empty))
      assert(!bare.isInstanceOf[RouteRefusal] && bare.getMessage.contains("Harness execution requires explicit HOME and PATH"))
    }
    "refuse a launch whose encoded argument exceeds the operating system's per-argument limit" in {
      // Codex receives the instructions JSON-encoded in one argument: a control character occupies six bytes there.
      val controls = invocation(Role.Governor, Path.of("/test/assets")).copy(system = "\u0001" * (HarnessInvocation.MaxSystemBytes / 2))
      val refused = intercept[IllegalArgumentException](new CodexAdapter().launch(profile(Harness.Codex), controls, environment))
      assert(refused.getMessage.contains("Harness launch argument exceeds the operating system's per-argument limit"))
      val quoted = invocation(Role.Governor, Path.of("/test/assets")).copy(system = "\"" * HarnessInvocation.MaxSystemBytes)
      adapters.foreach(adapter => assert(adapter.launch(profile(adapter.harness), quoted, environment).arguments.nonEmpty))
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

    "allow only a Governor's dispatch endpoint a tool call that outlasts the longest dispatch wait" in {
      val root = Path.of("/test/assets")
      HarnessTools.Roles.foreach { role =>
        val waits = if (role == Role.Governor) Some(150L) else None
        val codex = new CodexAdapter().launch(profile(Harness.Codex), invocation(role, root), environment).arguments
        assert(codex.contains("mcp_servers.cq.tool_timeout_sec=30") && codex.contains(s"mcp_servers.cq_host.tool_timeout_sec=${waits.getOrElse(30L)}"), role)
        val claude = new ClaudeAdapter().launch(profile(Harness.Claude), invocation(role, root), environment).assets.find(_.name == "claude-mcp.json").get
        val servers = io.circe.parser.parse(claude.body).fold(throw _, identity).hcursor.downField("mcpServers")
        assert(servers.downField("cq").downField("timeout").focus.isEmpty, role)
        assert(servers.downField("cq_host").get[Option[Long]]("timeout") == Right(waits.map(_ * 1000)), role)
      }
      assert(DispatchWaits.ManagedGovernorSeconds == 150 && DispatchWaits.ManagedGovernorSeconds * 1000 > DispatchWaits.MaxMillis)
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

    // Question 26: the operator's outer sandbox is the only filesystem boundary of a Codex child, whatever its role.
    // One case per role, so a regression names every role it affects: Worker edits, the other four do not.
    HarnessTools.Roles.foreach { role =>
      s"launch a Codex $role (${if (HarnessTools.edits(role)) "editing" else "non-editing"}) without the Codex sandbox, leaving approval and tool policy unchanged" in {
        assert(HarnessTools.edits(role) == (role == Role.Worker))
        val arguments = new CodexAdapter().launch(profile(Harness.Codex), invocation(role, Path.of("/test/assets")), environment).arguments
        assert(arguments.count(_ == "--sandbox") == 1)
        assert(arguments(arguments.indexOf("--sandbox") + 1) == "danger-full-access")
        assert(!arguments.contains("workspace-write") && !arguments.contains("read-only"))
        assert(!arguments.exists(value => value.startsWith("sandbox_mode=") || value.startsWith("sandbox_workspace_write.")))
        val settings = arguments.sliding(2).collect { case List("-c", value) => value }.toList
        assert(settings.count(_.startsWith("approval_policy=")) == 1 && settings.contains("approval_policy=\"never\""))
        val policy = HarnessTools.policy(role, Harness.Codex)
        policy.builtin.foreach { tool =>
          val expected = if (tool.name == "web_search") Json.fromString(if (tool.enabled) "live" else "disabled") else Json.fromBoolean(tool.enabled)
          assert(settings.contains(tool.name + "=" + expected.noSpaces))
        }
        McpTarget.values.foreach { target =>
          assert(settings.contains(s"mcp_servers.${target.server}.enabled_tools=" + Json.arr(policy.enabledMcp(target).map(Json.fromString)*).noSpaces))
        }
      }
    }

    "cover the five launched roles in the per-role Codex sandbox cases" in {
      assert(HarnessTools.Roles.toSet == Set(Role.Governor, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer))
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
