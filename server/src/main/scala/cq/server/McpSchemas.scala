package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.host.{ChildContracts, HarnessInvocation, McpTarget}
import io.circe.{Json, JsonObject, parser}
import java.nio.charset.StandardCharsets.UTF_8

final case class McpTool(name: String, description: String, inputType: String, results: Set[String], writes: Boolean,
  decode: Json => Either[Throwable, Command])

final class McpSchemas {
  private val definitions = {
    val stream = Option(getClass.getResourceAsStream("/cq-schemas.json")).getOrElse(throw new IllegalStateException("Missing generated schemas"))
    try parser.parse(new String(stream.readAllBytes(), UTF_8)).fold(throw _, identity).asObject.get
    finally stream.close()
  }
  private def decoder[A](codec: BaboonJsonCodec[A])(wrap: A => Command): Json => Either[Throwable, Command] =
    json => codec.decode(BaboonCodecContext.Default, json).map(wrap)

  val tools: List[McpTool] = List(
    McpTool("search", "Read a bounded item page using text, quoted phrases, exact IDs (T42), ledger:, status:, tag:, project:, archived:true|false|all, or kebab-case relation:T42. Uppercase NOT/- binds before AND (also implicit), then OR; parentheses group. Active items are implicit unless archived: occurs. Continue with its snapshot cursor; restart on Resync. QuerySyntax returns UTF-16 source spans.", "SearchInput", Set("Found"), false,
      decoder(SearchInput_JsonCodec)(Command.Search.apply)),
    McpTool("read", "Preview a stored proposal by result handle; read integration reservations and durable result admission; inspect explicit claim membership and collateral overlap for reviewed human takeover; preview exact whole-subgraph termination with typed effects, exclusions and active claims; read an item, bounded exact-revision batch, history or changes; complete query text at a UTF-16 cursor with bounded suggestions and syntax diagnostics; inspect artifact metadata or explicitly drill down into bounded text pages by Unicode code-point offset.", "ReadInput", Set("Proposal", "Integration", "Admission", "Detail", "Details", "History", "Changes", "ArtifactInfo", "ArtifactText", "QueryAnalyzed", "Termination", "Claims"), false,
      decoder(ReadInput_JsonCodec)(Command.Read.apply)),
    McpTool("graph", "Enumerate a transient workset from explicit roots: selected produced work and milestone members, separate one-hop context, and informational readiness reasons. Empty roots select nothing. Context does not expand siblings. Maximum 64 roots and 1024 visited items; Limit fails explicitly. Continue with the returned roots-bound snapshot; restart on Resync. Worksets do not acquire claims.", "GraphInput", Set("Workset"), false,
      decoder(GraphInput_JsonCodec)(Command.Graph.apply)),
    McpTool("change", "Commit an idempotent atomic change batch with expected revisions and claim fences. Produce creates and attaches descendants atomically under the producer revision and active owned fence. A Terminate mutation must stand alone, use a freshly reviewed read/Termination snapshot, and echo all previewed claim fences; stale/conflicted plans fail atomically. Governor authority required.", "ChangeInput", Set("Changed"), true,
      decoder(ChangeInput_JsonCodec)(Command.Change.apply)),
    McpTool("apply", "Apply the typed proposal in an admitted result by handle after inspecting read/Proposal. Only the original governor can apply it. Current exact assignment revisions/claim are required; an exact committed retry returns its original acknowledgement. Drafts and authority are resolved by the server.", "ProposalApplyInput", Set("Changed"), true,
      decoder(ProposalApplyInput_JsonCodec)(Command.ApplyProposal.apply)),
    McpTool("claim", "Acquire, renew or release an explicit item-set claim. Governor authority required. Takeover requires Human authority and a freshly reviewed read/Claims snapshot; replaced claims lose their entire membership.", "ClaimInput", Set("Claimed"), true,
      decoder(ClaimInput_JsonCodec)(Command.ClaimWork.apply)),
    McpTool("usage", "Read task, cohort, session, evaluation or project usage totals and bounded cost, observation, attempt and outcome audit pages. Shared totals are not per-member allocations.", "UsageInput", Set("UsageSummary", "UsageCosts", "UsageAudit", "UsageAttempts", "UsageOutcomes"), false,
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
    if (role == Role.Reviewer) closure(root)
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
    closure(branches.head)
  }

  def nativeInvocation(harness: Harness, invocation: HarnessInvocation): HarnessInvocation = {
    if (harness == Harness.Pi) invocation.copy(system = invocation.system +
      "\nReturn one JSON value matching this complete output schema, without Markdown. " +
      "Preserve object wrappers and identifier fields exactly. Every declared property is required; use null only where permitted. " +
      "Each $ref resolves against this schema's $defs. CQ validates the complete result before admission.\n" + invocation.resultSchema.noSpaces)
    else if (harness != Harness.Codex) invocation
    else {
      // Codex 0.156.1 drops definitions above 5,000 normalized bytes; reserve room for its normalization.
      val GuideThresholdBytes = 4000
      val inputs = invocation.endpoints.flatMap { endpoint =>
        invocation.tools(endpoint.target).map { name =>
          val input = endpoint.target match {
            case McpTarget.Domain => advertised(tools.find(_.name == name).get).hcursor.downField("inputSchema").focus.get
            case McpTarget.Local => name match {
              case "dispatch" => schema("DispatchCommand")
              case "workspace" => workspace(invocation.role)
              case _ => throw new IllegalArgumentException("Unknown local tool schema")
            }
          }
          s"${endpoint.name}.$name" -> input
        }
      }.filter(_._2.noSpaces.getBytes(UTF_8).length > GuideThresholdBytes)
      if (inputs.isEmpty) invocation
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
        invocation.copy(system = invocation.system + "\nCanonical argument schemas for CQ tools affected by native schema compaction. " +
          "Use these complete contracts when constructing tool arguments. Each $ref resolves against this document's $defs. " +
          "They do not grant additional permissions.\n" + guide.noSpaces)
      }
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
