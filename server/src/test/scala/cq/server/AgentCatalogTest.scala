package cq.server

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import cq.core.CohortAssessmentPolicy
import cq.host.*
import io.circe.{Json, JsonObject, parser}
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class AgentCatalogLocal extends AnyWordSpec {
  private val Context = BaboonCodecContext.Default
  private val schemas = new McpSchemas()
  private val instructions = new ChildInstructions()
  private val catalog = new AgentCatalog(schemas, instructions)
  private def resource(path: String): String = new String(getClass.getResourceAsStream("/" + path).readAllBytes(), UTF_8)
  private def parsed(text: String): Json = parser.parse(text).fold(throw _, identity)

  /** The generated schema file, read independently of `McpSchemas`. */
  private val generated: JsonObject = parsed(resource("cq-schemas.json")).asObject.get
  private def generatedSchema(name: String): Json =
    generated(s"cq_api_$name").get.mapObject(_.add("$defs", Json.fromJsonObject(generated)))

  /** Every role mode the generated `DispatchWork` schema admits, decoded through the generated codec. */
  private val works: List[DispatchWork] = generated("cq_api_DispatchWork").get.hcursor.get[List[Json]]("oneOf").fold(throw _, identity).flatMap { branch =>
    val tag = branch.hcursor.get[List[String]]("required").fold(throw _, identity).head
    val body = generated(s"cq_api_DispatchWork_$tag").get
    val fields = body.hcursor.downField("properties").focus.flatMap(_.asObject).map(_.toList).getOrElse(Nil)
    val values = fields match {
      case Nil => List(Json.obj())
      case List(("mode", mode)) =>
        val name = mode.hcursor.get[String]("$ref").fold(throw _, identity).stripPrefix("#/$defs/")
        generated(name).get.hcursor.get[List[Json]]("enum").fold(throw _, identity).map(value => Json.obj("mode" -> value))
      case other => fail(s"Unexpected DispatchWork.$tag fields $other")
    }
    values.map(value => DispatchWork_JsonCodec.decode(Context, Json.obj(tag -> value)).fold(throw _, identity))
  }
  private val pairs: List[(DispatchWork, Harness)] = for { work <- works; harness <- Harness.all } yield work -> harness

  private val adapters: Map[Harness, HarnessAdapter] = List(new ClaudeAdapter, new CodexAdapter, new PiAdapter).map(adapter => adapter.harness -> adapter).toMap
  private val environment = Map("HOME" -> "/test/home", "PATH" -> "/test/bin")
  private val assets = Path.of("/test/assets")
  private def endpoint(target: McpTarget): HarnessMcp = target match {
    case McpTarget.Domain => HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("scoped-domain-token", 2000))
    case McpTarget.Local => HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("scoped-local-token", 2000))
  }
  private def profile(harness: Harness): HarnessProfile = HarnessProfile(harness, Path.of("/test/harness"), "selected-model",
    if (harness == Harness.Claude) "anthropic" else "selected-provider", HarnessUsage.version(harness), Nil, Set.empty)
  private def option(arguments: List[String], name: String): String = {
    require(arguments.count(_ == name) == 1, s"Expected one $name")
    arguments(arguments.indexOf(name) + 1)
  }
  private def split(value: String): List[String] = if (value.isEmpty) Nil else value.split(",", -1).toList
  private def asset(launched: HarnessLaunch, name: String): String = launched.assets.find(_.name == name).getOrElse(fail(s"Missing launch asset $name")).body
  private def configs(arguments: List[String]): List[(String, Json)] = arguments.sliding(2).collect { case List("-c", value) => value }.toList.map { value =>
    val (key, json) = value.splitAt(value.indexOf('='))
    key -> parsed(json.drop(1))
  }

  /** What a launched harness was actually told and permitted, read back from its launch arguments and assets. */
  private final case class Observed(prompt: String, schema: Json, builtinEnabled: Set[String], builtinDisabled: Set[String], mcp: Map[String, List[String]])
  private def observe(harness: Harness, launched: HarnessLaunch): Observed = {
    val arguments = launched.arguments
    val servers = McpTarget.values.toList.map(_.server)
    harness match {
      case Harness.Claude =>
        val builtin = split(option(arguments, "--tools"))
        val allowed = split(option(arguments, "--allowedTools"))
        assert(allowed.take(builtin.size) == builtin)
        val mcp = allowed.drop(builtin.size).map { name =>
          val parts = name.split("__", 3)
          assert(parts.length == 3 && parts(0) == "mcp", name)
          parts(1) -> parts(2)
        }
        assert(parsed(asset(launched, "claude-mcp.json")).hcursor.downField("mcpServers").keys.get.toList == servers)
        Observed(option(arguments, "--system-prompt"), parsed(option(arguments, "--json-schema")), builtin.toSet,
          split(option(arguments, "--disallowedTools")).toSet, servers.map(server => server -> mcp.filter(_._1 == server).map(_._2)).toMap)
      case Harness.Codex =>
        val values = configs(arguments)
        val settings = values.filter((key, _) => key.startsWith("features.") || Set("agents.enabled", "web_search", "tools.update_plan.enabled")(key))
        val enabled = settings.collect { case (key, value) if value == Json.True || value == Json.fromString("live") => key }.toSet
        assert(option(arguments, "--output-schema") == assets.resolve("result-schema.json").toString)
        Observed(values.toMap.apply("developer_instructions").asString.get, parsed(asset(launched, "result-schema.json")), enabled,
          settings.map(_._1).toSet -- enabled, servers.map(server => server -> values.toMap.apply(s"mcp_servers.$server.enabled_tools").as[List[String]].fold(throw _, identity)).toMap)
      case Harness.Pi =>
        val prompt = option(arguments, "--system-prompt")
        val tools = split(option(arguments, "--tools"))
        val bridge = parsed(asset(launched, "pi-mcp.json")).hcursor.downField("endpoints").as[List[Json]].fold(throw _, identity)
          .map(value => value.hcursor.get[String]("name").fold(throw _, identity) -> value.hcursor.get[List[String]]("tools").fold(throw _, identity)).toMap
        val bridged = servers.flatMap(server => bridge(server).map(server + "_" + _))
        assert(tools.takeRight(bridged.size) == bridged)
        Observed(prompt, parsed(prompt.linesIterator.toList.last), tools.dropRight(bridged.size).toSet, Set.empty, bridge)
    }
  }
  private def hasCheck(workspace: Json): Boolean = workspace.hcursor.get[List[Json]]("oneOf").fold(throw _, identity)
    .exists(_.hcursor.get[List[String]]("required") == Right(List("Check")))
  private def keys(value: Json, name: String): Boolean = value.arrayOrObject(false, _.exists(keys(_, name)),
    fields => fields.contains(name) || fields.values.exists(keys(_, name)))

  private def input(entry: AgentEntry): Json = ChildExecutionInput_JsonCodec.encode(Context, entry.inputExample)
  private def output(entry: AgentEntry): Json = ChildReport_JsonCodec.encode(Context, entry.outputExample)
  private def replaced(value: Json, field: String, replacement: Json): Json = value.arrayOrObject(value, items => Json.fromValues(items.map(replaced(_, field, replacement))),
    fields => Json.fromJsonObject(JsonObject.fromIterable(fields.toList.map((key, child) => key -> (if (key == field) replacement else replaced(child, field, replacement))))))

  "Agent role-mode catalog (Behavioral Active Blackbox Group)" should {
    "list one flat entry for every DispatchWork role and mode with its canonical prompt and schemas" in {
      assert(works.size == 9 && works.distinct.size == works.size)
      assert(catalog.entries.map(_.work) == works)
      works.foreach { work =>
        withClue(s"$work: ") {
          val entry = catalog.entry(work)
          val (role, mode) = work match {
            case DispatchWork.Explorer(mode) => Role.Explorer -> Some(mode.toString)
            case DispatchWork.Planner() => Role.Planner -> None
            case DispatchWork.Worker(mode) => Role.Worker -> Some(mode.toString)
            case DispatchWork.Reviewer(mode) => Role.Reviewer -> Some(mode.toString)
          }
          assert(entry.role == role && entry.mode == mode)
          assert(entry.promptResource.startsWith("cq/prompts/") && entry.prompt == resource(entry.promptResource) && entry.prompt.trim.nonEmpty)
          assert(entry.prompt == instructions(work))
          assert(entry.inputType == "ChildExecutionInput" && entry.inputSchema == schemas.schema("ChildExecutionInput"))
          assert(entry.inputSchema.mapObject(_.remove("$defs")) == generated("cq_api_ChildExecutionInput").get)
          assert(entry.report == ChildContracts.reportTag(work) && entry.outputSchema == schemas.childReport(work))
          assert(entry.outputSchema.hcursor.get[List[String]]("required") == Right(List(entry.report)))
          assert(entry.harnesses.map(_.harness) == Harness.all)
        }
      }
      val prompts = catalog.entries.groupBy(_.promptResource).view.mapValues(_.map(_.work).toSet).toMap
      assert(prompts.size == 7 && prompts("cq/prompts/explore.md") == ExplorerMode.all.map(DispatchWork.Explorer.apply).toSet &&
        prompts("cq/prompts/review-proposal.md") == Set(DispatchWork.Reviewer(ReviewerMode.Plan), DispatchWork.Reviewer(ReviewerMode.Audit)))
      assert(catalog.entries.groupBy(_.report).view.mapValues(_.size).toMap == Map("Evidence" -> 3, "Plan" -> 1, "Work" -> 2, "Review" -> 3))
    }

    "provide each harness's effective prompt, output schema and tool permissions from the launch functions" in {
      assert(pairs.size == 27)
      pairs.foreach { (work, harness) =>
        withClue(s"$work on $harness: ") {
          val entry = catalog.entry(work)
          val view = entry.on(harness)
          val attempt = AttemptId(UUID.randomUUID())
          val launchedBefore = schemas.nativeInvocation(harness, HarnessInvocation(ChildContracts.role(work), attempt, instructions(work),
            schemas.childReport(work), List(endpoint(McpTarget.Domain), endpoint(McpTarget.Local)), assets))
          assert(view.prompt == launchedBefore.system)
          assert(view.tools == HarnessTools.policy(work, harness) && view.tools.harness == harness && view.tools.role == entry.role)
          List(McpTarget.Domain -> HarnessTools.DomainTools, McpTarget.Local -> HarnessTools.LocalTools).foreach { (target, all) =>
            assert(view.tools.enabledMcp(target).nonEmpty && view.tools.disabledMcp(target).nonEmpty)
            assert((view.tools.enabledMcp(target) ++ view.tools.disabledMcp(target)).sorted == all.sorted)
          }
          assert(view.tools.enabledBuiltin.intersect(view.tools.disabledBuiltin).isEmpty)
          harness match {
            case Harness.Claude =>
              assert(view.prompt == entry.prompt && view.outputSchema == entry.outputSchema)
              assert(view.tools.enabledBuiltin.nonEmpty == (entry.role == Role.Worker) && view.tools.disabledBuiltin.nonEmpty)
            case Harness.Codex =>
              assert(view.prompt.startsWith(entry.prompt) && view.outputSchema == CodexSchema.result(entry.outputSchema))
              assert(!keys(view.outputSchema, "oneOf") && (!keys(entry.outputSchema, "oneOf") || keys(view.outputSchema, "anyOf")))
              assert(view.tools.enabledBuiltin.contains("features.shell_tool") == (entry.role == Role.Worker) && view.tools.disabledBuiltin.nonEmpty)
            case Harness.Pi =>
              assert(view.prompt.startsWith(entry.prompt + "\n") && view.prompt != entry.prompt && view.outputSchema == entry.outputSchema)
              assert(parsed(view.prompt.linesIterator.toList.last) == entry.outputSchema)
              assert(view.tools.enabledBuiltin.nonEmpty == (entry.role == Role.Worker) && (view.tools.enabledBuiltin ++ view.tools.disabledBuiltin).nonEmpty)
          }
        }
      }
      assert(catalog.entries.exists(entry => keys(entry.outputSchema, "oneOf")))
    }

    "launch every role mode on every harness with exactly the catalog's prompt, output schema and tool configuration" in {
      pairs.foreach { (work, harness) =>
        withClue(s"$work on $harness: ") {
          val entry = catalog.entry(work)
          val view = entry.on(harness)
          val attempt = AttemptId(UUID.randomUUID())
          val invocation = catalog.invocation(work, harness, attempt, endpoint, assets)
          // The composition child launch used before the catalog existed, restated as an independent oracle.
          assert(invocation == schemas.nativeInvocation(harness, HarnessInvocation(ChildContracts.role(work), attempt, instructions(work),
            schemas.childReport(work), List(endpoint(McpTarget.Domain), endpoint(McpTarget.Local)), assets)))
          val launched = adapters(harness).launch(profile(harness), invocation, environment)
          val observed = observe(harness, launched)
          assert(observed.prompt == view.prompt)
          assert(observed.schema == view.outputSchema)
          McpTarget.values.foreach { target =>
            assert(observed.mcp(target.server) == view.tools.enabledMcp(target))
            assert(view.tools.disabledMcp(target).intersect(observed.mcp(target.server)).isEmpty)
          }
          assert(observed.builtinEnabled == view.tools.enabledBuiltin.toSet)
          harness match {
            case Harness.Claude => assert(observed.builtinDisabled == view.tools.deniedBuiltin.toSet && observed.builtinDisabled.subsetOf(view.tools.disabledBuiltin.toSet))
            case Harness.Codex =>
              assert(observed.builtinDisabled == view.tools.disabledBuiltin.toSet)
              assert(option(launched.arguments, "--sandbox") == "danger-full-access")
              assert(asset(launched, "canonical-result-schema.json") == entry.outputSchema.noSpaces)
            case Harness.Pi => assert(view.tools.disabledBuiltin.toSet.intersect(observed.builtinEnabled).isEmpty)
          }
        }
      }
      val wrong = intercept[IllegalArgumentException](catalog.invocation(works.head, Harness.Claude, AttemptId(UUID.randomUUID()), _ => endpoint(McpTarget.Domain), assets))
      assert(wrong.getMessage.contains("MCP endpoint does not serve its requested target"))
    }

    "show the workspace Check branch only for reviewers, as the local workspace tool advertises it" in {
      // `advertised` reads only the schemas; the controllers a running LocalControl serves are not involved.
      val control = new LocalControl(null, null, null, null, null, null, schemas, null, null)
      works.foreach { work =>
        withClue(s"$work: ") {
          val entry = catalog.entry(work)
          val reviewer = work.isInstanceOf[DispatchWork.Reviewer]
          val advertised = control.advertised(LocalCapability(AttemptId(UUID.randomUUID()), entry.role))
          assert(advertised.hcursor.get[String]("name") == Right("workspace"))
          assert(advertised.hcursor.downField("inputSchema").focus.contains(entry.workspaceSchema))
          assert(hasCheck(entry.workspaceSchema) == reviewer)
          assert(keys(entry.workspaceSchema, "cq_api_WorkspaceCommand_Check") == reviewer)
          val branches = entry.workspaceSchema.hcursor.get[List[Json]]("oneOf").fold(throw _, identity).flatMap(_.hcursor.get[List[String]]("required").toOption.get)
          assert(branches == List("Entries", "Read", "MergeReport") ++ (if (reviewer) List("Check") else Nil))
          Harness.all.foreach { harness =>
            val tools = entry.on(harness).tools
            assert(tools.workspaceCheck == reviewer && tools.enabledMcp(McpTarget.Local) == List("workspace"))
          }
        }
      }
    }

    "validate the authored input and output examples against the generated schemas for every role mode and harness" in {
      pairs.foreach { (work, harness) =>
        withClue(s"$work on $harness: ") {
          val entry = catalog.entry(work)
          val view = entry.on(harness)
          assert(JsonSchemaCheck.errors(entry.inputSchema, input(entry)) == Nil)
          assert(JsonSchemaCheck.errors(generatedSchema("ChildExecutionInput"), input(entry)) == Nil)
          assert(JsonSchemaCheck.errors(view.outputSchema, output(entry)) == Nil)
          assert(JsonSchemaCheck.errors(entry.outputSchema, output(entry)) == Nil)
          assert(JsonSchemaCheck.errors(generatedSchema("ChildReport"), output(entry)) == Nil)
          assert(output(entry).asObject.get.keys.toList == List(entry.report))
        }
      }
    }

    "author examples that the child contracts accept for their own role mode" in {
      works.foreach { work =>
        withClue(s"$work: ") {
          val entry = catalog.entry(work)
          val example = entry.inputExample
          val request = example.input.request
          assert(entry.inputExample == AgentExamples.input(work) && entry.outputExample == AgentExamples.output(work))
          assert(ChildExecutionInput_JsonCodec.decode(Context, input(entry)) == Right(example))
          assert(request.work == work)
          ChildContracts.request(example.input.project, request)
          def revisions(views: List[ItemView]): List[ItemRevision] = views.map(view => ItemRevision(view.item.id, view.item.revision))
          assert(revisions(example.input.members) == request.members && revisions(example.input.guidance) == request.guidance)
          assert(example.input.artifacts.map(_.metadata.id) == request.artifacts && example.input.previous.nonEmpty == request.previous.nonEmpty)
          (example.input.members ++ example.input.guidance).foreach(view => cq.core.LedgerPolicy.validate(view.item.draft))
          if (work == DispatchWork.Planner()) assert(example.input.guidance.exists(_.item.draft.content match {
            case memory: Content.Memory => memory.status == MemoryStatus.Current && memory.evidence.nonEmpty && memory.evidence.forall(_.citations.nonEmpty)
            case _ => false
          }), "the Planner example carries a Current, evidenced Memory as guidance")
          example.input.artifacts.foreach { artifact =>
            val bytes = artifact.body.getBytes(UTF_8)
            assert(artifact.metadata.bytes == bytes.length && artifact.metadata.project == example.input.project)
            assert(artifact.metadata.sha256 == java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString)
          }
          assert(example.input.operatorRequirements.nonEmpty == OperatorRequirements.delivered(work, "operator request").nonEmpty)
          assert(HostFiles.encode(ChildInput_JsonCodec, example.input).getBytes(UTF_8).length <= ChildContracts.MaxInputBytes)
          example.input.previous.foreach { previous =>
            ChildContracts.result(example.input.project, previous)
            assert(previous.request.members == request.members && previous.report == AgentExamples.output(previous.request.work))
            assert(previous.request == AgentExamples.input(previous.request.work).input.request)
            assert(example.base == previous.candidate.getOrElse(previous.base))
          }
          work match {
            case DispatchWork.Reviewer(ReviewerMode.Candidate) =>
              assert(example.input.previous.exists(value => value.report.isInstanceOf[ChildReport.Work] && value.candidate.nonEmpty))
              assert(example.input.previous.get.validation.map(_.check).toSet.subsetOf(example.checks.map(_.name).toSet))
            case DispatchWork.Reviewer(ReviewerMode.Plan) =>
              val previous = example.input.previous.get
              assert(CohortAssessmentPolicy.reviewable(previous.request.work, previous.request.members, previous.report))
            case _ => ()
          }
          val report = ChildContracts.report(work, request.members, output(entry))
          assert(report == entry.outputExample)
          report match {
            case plan: ChildReport.Plan =>
              CohortAssessmentPolicy.criteria(plan, id => example.input.members.find(_.item.id == id).get.item)
              CohortAssessmentPolicy.checks(plan, example.checks)
            case _ => ()
          }
        }
      }
    }

    "reject examples that drift from their schemas" in {
      pairs.foreach { (work, harness) =>
        withClue(s"$work on $harness: ") {
          val entry = catalog.entry(work)
          val view = entry.on(harness)
          def rejects(schema: Json, value: Json, reason: String): Unit = {
            val errors = JsonSchemaCheck.errors(schema, value)
            assert(errors.exists(_.contains(reason)), s"expected '$reason' in $errors")
          }
          val in = input(entry)
          rejects(entry.inputSchema, in.mapObject(_.remove("base")), "missing required property base")
          rejects(entry.inputSchema, in.mapObject(_.add("extra", Json.True)), "undeclared property extra")
          rejects(entry.inputSchema, replaced(in, "harness", Json.fromString("Emacs")), "is not one of")
          rejects(entry.inputSchema, replaced(in, "project", Json.obj("value" -> Json.fromString("not-a-uuid"))), "is not a UUID")
          rejects(entry.inputSchema, replaced(in, "generation", Json.fromInt(1)), "expected \"string\"")
          rejects(entry.inputSchema, replaced(in, "retainedOutputBytes", Json.fromLong(4294967296L)), "violates maximum")
          val out = output(entry)
          rejects(view.outputSchema, replaced(out, "members", Json.arr()), "violate minItems")
          rejects(view.outputSchema, replaced(out, "item", Json.fromString("T12")), "expected \"object\"")
          rejects(view.outputSchema, out.mapObject(_.add("Other", Json.obj())), "undeclared property Other")
          catalog.entries.filter(_.report != entry.report).foreach { other =>
            assert(JsonSchemaCheck.errors(other.on(harness).outputSchema, out).nonEmpty, s"accepted by the ${other.report} schema")
          }
          entry.outputExample match {
            case ChildReport.Evidence(members) =>
              val observed = ChildReport_JsonCodec.encode(Context, ChildReport.Evidence(members.map(member =>
                member.copy(evidence = member.evidence.map(_.copy(origin = EvidenceOrigin.HostObserved))))))
              assert(observed != out)
              rejects(view.outputSchema, observed, "\"HostObserved\" is not one of [\"ModelDeclared\"]")
            case ChildReport.Review(members, proposal) =>
              assert(proposal.isEmpty)
              rejects(view.outputSchema, out.mapObject(_.mapValues(_.mapObject(_.remove("proposal")))), "missing required property proposal")
            case ChildReport.Plan(_, proposal, _) =>
              assert(proposal.nonEmpty)
              rejects(view.outputSchema, replaced(out, "reason", Json.fromString("")), "violates minLength")
            case ChildReport.Work(_) =>
              rejects(view.outputSchema, replaced(out, "disposition", Json.fromString("Accepted")), "is not one of")
          }
        }
      }
      intercept[IllegalArgumentException](JsonSchemaCheck.errors(Json.obj("const" -> Json.True), Json.True))
      intercept[IllegalArgumentException](JsonSchemaCheck.errors(Json.obj("type" -> Json.fromString("number")), Json.fromInt(1)))
    }
  }
}
