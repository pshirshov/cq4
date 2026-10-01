package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.host.{ChildContracts, HarnessInvocation, HarnessSchema, HarnessTools, McpTarget}
import io.circe.{Json, JsonObject, parser}
import java.nio.charset.StandardCharsets.UTF_8

final case class McpTool(name: String, description: String, inputType: String, results: Set[String], writes: Boolean,
  decode: Json => Either[Throwable, Command])

object McpSchemas {
  /** When to revalidate, given with the dispatch tool rather than in the governing instructions. */
  val Revalidation: String = " Revalidate reruns the failed configured checks of an admitted worker result on its exact candidate, within each check's configured rounds; repeat its ID to poll. " +
    "Use it when the retained output shows an intermittent failure rather than a candidate defect; otherwise send the result to a worker."
}

final class McpSchemas {
  private val definitions = {
    val stream = Option(getClass.getResourceAsStream("/cq-schemas.json")).getOrElse(throw new IllegalStateException("Missing generated schemas"))
    try parser.parse(new String(stream.readAllBytes(), UTF_8)).fold(throw _, identity).asObject.get
    finally stream.close()
  }
  private def decoder[A](codec: BaboonJsonCodec[A])(wrap: A => Command): Json => Either[Throwable, Command] =
    json => codec.decode(BaboonCodecContext.Default, json).map(wrap)

  val tools: List[McpTool] = List(
    McpTool("search", "Read a bounded item page using text, quoted phrases, exact IDs (T42), ledger:, status:, tag:, project:, archived:true|false|all, or kebab-case relation:T42. NOT/- binds before AND (also implicit), then OR; keywords in any case, quote them to search the words; parentheses group. Active items are implicit unless archived: occurs. Continue with its snapshot cursor; restart on Resync. QuerySyntax returns UTF-16 source spans.", "SearchInput", Set("Found"), false,
      decoder(SearchInput_JsonCodec)(Command.Search.apply)),
    McpTool("read", "Preview a stored proposal by result handle; read integration reservations and durable result admission; inspect explicit claim membership and collateral overlap for reviewed human takeover; preview exact whole-subgraph termination with typed effects, exclusions and active claims; preview archival of a query's terminal items with the ones retained by open related items; read an item, bounded exact-revision batch, history or changes; complete query text at a UTF-16 cursor with bounded suggestions and syntax diagnostics; inspect artifact metadata or explicitly drill down into bounded text pages by Unicode code-point offset.", "ReadInput", Set("Proposal", "Integration", "Admission", "Detail", "Details", "History", "Changes", "ArtifactInfo", "ArtifactText", "QueryAnalyzed", "Termination", "Claims"), false,
      decoder(ReadInput_JsonCodec)(Command.Read.apply)),
    McpTool("graph", "Enumerate a transient workset from explicit roots: selected produced work and milestone members, separate one-hop context, and informational readiness reasons. Empty roots select nothing. Context does not expand siblings. Maximum 64 roots and 1024 visited items; Limit fails explicitly. Continue with the returned roots-bound snapshot; restart on Resync. Worksets do not acquire claims.", "GraphInput", Set("Workset"), false,
      decoder(GraphInput_JsonCodec)(Command.Graph.apply)),
    McpTool("change", "Commit an idempotent atomic change batch with expected revisions and claim fences. Produce creates and attaches descendants atomically under the producer revision and active owned fence; its optional milestone assigns the Task drafts to an existing Open milestone or to a Milestone created earlier in the batch. A Terminate mutation must stand alone, use a freshly reviewed read/Termination snapshot, and echo all previewed claim fences; stale/conflicted plans fail atomically. Governor authority required.", "ChangeInput", Set("Changed"), true,
      decoder(ChangeInput_JsonCodec)(Command.Change.apply)),
    McpTool("apply", "Apply the typed proposal in an admitted result by handle after inspecting read/Proposal. Only the original governor can apply it. Current exact assignment revisions/claim are required; an exact committed retry returns its original acknowledgement. Drafts and authority are resolved by the server.", "ProposalApplyInput", Set("Changed"), true,
      decoder(ProposalApplyInput_JsonCodec)(Command.ApplyProposal.apply)),
    McpTool("claim", s"Acquire, renew or release an explicit item-set claim. Duration is 1–${cq.core.LedgerPolicy.MaxClaimMillis} ms (at most ${cq.core.LedgerPolicy.MaxClaimMillis / 60000} minutes); the host renews the claim covering a running child and one it is preparing for integration or combination, and a renewal never shortens a lease, so renew only claims you hold outside running work before they expire. Governor authority required. Takeover requires Human authority and a freshly reviewed read/Claims snapshot; replaced claims lose their entire membership.", "ClaimInput", Set("Claimed"), true,
      decoder(ClaimInput_JsonCodec)(Command.ClaimWork.apply)),
    McpTool("usage", "Read task, cohort, session, evaluation or project usage totals, a per-phase report of attempts, finished wall time, tokens and costs, and bounded cost, observation, attempt and outcome audit pages. Shared totals are not per-member allocations.", "UsageInput", Set("UsageSummary", "UsagePhases", "UsageCosts", "UsageAudit", "UsageAttempts", "UsageOutcomes"), false,
      decoder(UsageInput_JsonCodec)(Command.Usage.apply)),
  )

  def visible(authority: Authority): List[McpTool] = {
    val canWrite = authority.credential match {
      case _: Credential.RootSession => true
      case Credential.Scoped(grant) => grant.actor.role == Role.Governor
    }
    tools.filter(tool => !tool.writes || canWrite)
  }

  def advertised(tool: McpTool): Json = Json.obj(
    "name" -> Json.fromString(tool.name), "description" -> Json.fromString(tool.description),
    "inputSchema" -> schema(tool.inputType),
    "outputSchema" -> closure(Json.obj("type" -> Json.fromString("object"), "oneOf" -> Json.arr(
      (tool.results + "Failed").toList.sorted.map { tag => Json.obj(
        "type" -> Json.fromString("object"), "required" -> Json.arr(Json.fromString(tag)), "additionalProperties" -> Json.False,
        "properties" -> Json.obj(tag -> Json.obj("$ref" -> Json.fromString(s"#/$$defs/cq_api_Result_$tag"))),
      ) }*
    ))),
    "annotations" -> Json.obj("readOnlyHint" -> Json.fromBoolean(!tool.writes), "openWorldHint" -> Json.False),
  )

  def schema(name: String): Json = closure(definitions(s"cq_api_$name").get)

  def workspace(role: Role): Json = {
    val root = definitions("cq_api_WorkspaceCommand").get
    if (HarnessTools.workspaceCheck(role)) closure(root)
    else {
      val branches = root.hcursor.get[Vector[Json]]("oneOf").fold(throw _, identity)
        .filterNot(_.hcursor.get[List[String]]("required") == Right(List("Check")))
      closure(root.mapObject(_.add("oneOf", Json.arr(branches*))))
    }
  }

  def childReport(work: DispatchWork): Json = {
    val tag = ChildContracts.reportTag(work)
    val branches = definitions("cq_api_ChildReport").get.hcursor.get[Vector[Json]]("oneOf").fold(throw _, identity)
      .filter(_.hcursor.get[List[String]]("required") == Right(List(tag)))
    require(branches.size == 1, s"Expected one generated ChildReport.$tag schema")
    val result = closure(branches.head)
    if (tag != "Evidence") result
    else result.mapObject(_.add("$defs", result.hcursor.downField("$defs").focus.get.mapObject { values =>
      values.add("cq_api_EvidenceOrigin", values("cq_api_EvidenceOrigin").get.mapObject(
        _.add("enum", Json.arr(Json.fromString(EvidenceOrigin.ModelDeclared.toString)))))
    }))
  }

  /** The system instructions `harness` receives for `role`: the canonical ones plus what the harness cannot carry natively. Pure. */
  def nativeSystem(harness: Harness, role: Role, system: String, resultSchema: Json, targets: List[McpTarget]): String = {
    if (harness == Harness.Pi) system +
      "\nReturn one JSON value matching this complete output schema, without Markdown. " +
      "Preserve object wrappers and identifier fields exactly. Every declared property is required; use null only where permitted. " +
      "Each $ref resolves against this schema's $defs. CQ validates the complete result before admission.\n" +
      HarnessSchema.result(harness, resultSchema).noSpaces
    else if (harness != Harness.Codex) system
    else {
      // Codex 0.156.1 drops definitions above 5,000 normalized bytes; reserve room for its normalization.
      val GuideThresholdBytes = 4000
      val inputs = targets.flatMap { target =>
        HarnessTools.mcp(role, target).map { name =>
          val input = target match {
            case McpTarget.Domain => advertised(tools.find(_.name == name).get).hcursor.downField("inputSchema").focus.get
            case McpTarget.Local => name match {
              case "dispatch" => schema("DispatchCommand")
              case "workspace" => workspace(role)
              case _ => throw new IllegalArgumentException("Unknown local tool schema")
            }
          }
          s"${target.server}.$name" -> input
        }
      }.filter(_._2.noSpaces.getBytes(UTF_8).length > GuideThresholdBytes)
      system + argumentGuide(inputs)
    }
  }

  def nativeInvocation(harness: Harness, invocation: HarnessInvocation): HarnessInvocation = invocation.copy(
    system = nativeSystem(harness, invocation.role, invocation.system, invocation.resultSchema, invocation.endpoints.map(_.target)))

  def attachedInstructions(harness: Harness): String = {
    val instructions = SupervisorProgram.Guidance +
      " You are the already-running interactive Governor. Call session Context first and session Workflow before dispatch; follow the returned workflow instructions. " +
      "Do not invoke cq run for this interactive workflow. Report to the user normally; there is no governing JSON completion report. " +
      "Outer-session usage is explicitly unobserved unless a supported collector supplies it."
    if (harness != Harness.Codex) instructions
    else instructions + argumentGuide(tools.map(tool => ("cq." + tool.name, schema(tool.inputType))) ++
      List("cq.dispatch" -> schema("DispatchCommand"), "cq.session" -> schema("SessionCommand")))
  }

  def attachedTools: List[Json] = {
    def local(name: String, input: String, output: String, description: String): Json = Json.obj(
      "name" -> Json.fromString(name), "description" -> Json.fromString(description),
      "inputSchema" -> schema(input), "outputSchema" -> schema(output))
    List(local("session", "SessionCommand", "SessionReply",
      "First call Context for project, routes, limits, governing instructions and complete argument guide. Then Workflow with a fresh id and typed scope before dispatch; token is null unless the invocation carries a CQ driver --start-token or --resume-token, which you pass unchanged. An identical retry returns its original receipt without reactivating a superseded workflow. Context identifies the active workflow. Bind presents the token a CQ drive command printed; Driver reads this session's driver status. Neither starts nor parks a driver."),
      local("dispatch", "DispatchCommand", "DispatchReply",
        
        s"Select bounded cohorts, claim one complete choice, then StartChoice by ID, harness and fence. Up to ${DispatchController.MaxActiveChildren} children with disjoint members may run at once. Poll compact Status or Cancel; Status carries the child's workspace admission and retained directory, and quietMillis, the time since a running child's last output. Direct Start is unavailable. Prepare/apply reviewed integration; Combine a NotApplied integration and poll CombinationStatus. Forward handles; full child prompts/results stay outside your context." + McpSchemas.Revalidation)) ++ tools.map(advertised)
  }

  private def argumentGuide(inputs: List[(String, Json)]): String = {
    if (inputs.isEmpty) ""
    else {
      val selected = scala.collection.mutable.LinkedHashMap.empty[String, Json]
      inputs.foreach { case (_, input) => input.hcursor.downField("$defs").focus.get.asObject.get.toList.foreach { case (key, value) =>
        require(selected.get(key).forall(_ == value), s"Conflicting native tool schema definition $key")
        selected.update(key, value)
      }}
      val aliases = selected.keys.zipWithIndex.map((name, index) => name -> ("d" + Integer.toString(index, Character.MAX_RADIX))).toMap
      def localNames(value: Json): Json = value.arrayOrObject(value,
        values => Json.fromValues(values.map(localNames)),
        fields => Json.fromJsonObject(JsonObject.fromIterable(fields.toList.map { case (key, child) =>
          key -> (if (key == "$ref") Json.fromString("#/$defs/" + aliases(child.asString.get.stripPrefix("#/$defs/"))) else localNames(child))
        })))
      val guide = Json.obj("tools" -> Json.obj(inputs.map { case (name, value) => name -> localNames(value.mapObject(_.remove("$defs"))) }*),
        "$defs" -> Json.fromJsonObject(JsonObject.fromIterable(selected.map { case (name, value) => aliases(name) -> localNames(value) })))
      "\nCanonical argument schemas for CQ tools affected by native schema compaction. " +
        "Use these complete contracts when constructing tool arguments. Each $ref resolves against this document's $defs. " +
        "They do not grant additional permissions.\n" + guide.noSpaces
    }
  }

  private def closure(root: Json): Json = {
    val selected = scala.collection.mutable.LinkedHashMap.empty[String, Json]
    def visit(value: Json): Unit = {
      value.asObject.foreach { obj =>
        obj("$ref").flatMap(_.asString).foreach { reference =>
          val key = reference.stripPrefix("#/$defs/")
          if (!selected.contains(key)) {
            val definition = definitions(key).getOrElse(throw new IllegalStateException(s"Missing schema $key"))
            selected.update(key, definition)
            visit(definition)
          }
        }
        obj.values.foreach(visit)
      }
      value.asArray.foreach(_.foreach(visit))
    }
    visit(root)
    root.deepMerge(Json.obj("$defs" -> Json.fromJsonObject(JsonObject.fromIterable(selected))))
  }
}
