package cq.server

import baboon.runtime.shared.{BaboonBinCodec, BaboonCodecContext, BaboonJsonCodec, LEDataInputStream, LEDataOutputStream}
import cq.api.*
import cq.host.*
import io.circe.{Json, JsonObject, parser}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CatalogReadLocal extends AnyWordSpec {
  private val Context = BaboonCodecContext.Default
  private val schemas = new McpSchemas()
  private val agents = new AgentCatalog(schemas, new ChildInstructions())
  private val workflows = new WorkflowAssets()
  private val read = new CatalogRead(agents, workflows)
  private val catalog = read.value
  private def resource(path: String): String = new String(getClass.getResourceAsStream("/" + path).readAllBytes(), UTF_8)
  private def parsed(text: String): Json = parser.parse(text).fold(throw _, identity)
  private val generated: JsonObject = parsed(resource("cq-schemas.json")).asObject.get
  private def generatedSchema(name: String): Json =
    generated(s"cq_api_$name").get.mapObject(_.add("$defs", Json.fromJsonObject(generated)))

  private def json[A](codec: BaboonJsonCodec[A], value: A): A = {
    val encoded = codec.encode(Context, value)
    codec.decode(Context, parsed(encoded.noSpaces)).fold(throw _, identity)
  }
  private def binary[A](codec: BaboonBinCodec[A], value: A): A = {
    val bytes = new ByteArrayOutputStream()
    codec.encode(Context, new LEDataOutputStream(bytes), value)
    codec.decode(Context, new LEDataInputStream(new ByteArrayInputStream(bytes.toByteArray))).fold(throw _, identity)
  }

  "Typed catalog read (Behavioral Active Blackbox Group)" should {
    "return every aliased workflow command and every DispatchWork role-mode entry on every harness" in {
      assert(catalog.commands.map(_.command) == List("begin", "advance", "review", "upstream", "drive", "park"))
      catalog.commands.foreach(command => assert(command.aliases.map(_.harness) == Harness.all, command.command))
      val works = ExplorerMode.all.map(DispatchWork.Explorer.apply) ++ List(DispatchWork.Planner()) ++
        WorkerMode.all.map(DispatchWork.Worker.apply) ++ ReviewerMode.all.map(DispatchWork.Reviewer.apply)
      assert(works.size == 9 && catalog.agents.map(_.work).toSet == works.toSet && catalog.agents.size == works.size)
      catalog.agents.foreach { agent =>
        withClue(s"${agent.work}: ") {
          assert(agent.harnesses.map(_.harness) == Harness.all)
          assert(agent.prompt.text.trim.nonEmpty && agent.inputSchema.nonEmpty && agent.outputSchema.nonEmpty && agent.workspaceSchema.nonEmpty)
          agent.harnesses.foreach { harness =>
            assert(harness.prompt.nonEmpty && harness.outputSchema.nonEmpty)
            assert(harness.tools.mcp.map(_.target) == List(CatalogMcpTarget.Domain, CatalogMcpTarget.Local))
            assert(harness.tools.mcp.flatMap(_.tools).exists(_.access == CatalogToolAccess.Enabled))
          }
        }
      }
    }

    "carry exactly the command catalog's descriptions, aliases, argument docs and prompts, and the assets WorkflowAssets writes" in {
      val workflows = catalog.commands.filter(view => WorkflowCatalog.named(view.command).nonEmpty)
      assert(workflows.size == WorkflowCatalog.commands.size)
      workflows.zip(WorkflowCatalog.commands).foreach { (view, source) =>
        withClue(s"${source.command}: ") {
          assert(view.command == source.command && view.variant == source.variant && view.description == source.description)
          assert(view.parameters == source.arguments.map(argument => CatalogArgument(argument.option.field, argument.option.flag,
            argument.option.value, argument.option.summary, argument.option.choices, argument.required, argument.note)))
          assert(view.template == CatalogPrompt(source.template, resource(source.template)))
          assert(view.instructions == source.instructions.map(path => CatalogPrompt(path, resource(path))))
          Harness.all.foreach { harness =>
            val alias = source.alias(harness)
            val written = this.workflows.commands(harness).filter(_.path == alias.path)
            assert(written.size == 1)
            assert(view.aliases.filter(_.harness == harness) == List(CatalogAlias(harness, alias.alias, alias.path.toString, written.head.body)))
          }
        }
      }
    }

    "include drive and park with their actual hook assets and Pi extension aliases" in {
      val drivers = catalog.commands.filter(view => Set("drive", "park").contains(view.command))
      assert(drivers.map(_.command) == List("drive", "park"))
      assert(drivers.size == DriverAssets.catalog.size)
      drivers.zip(DriverAssets.catalog).foreach { (view, source) =>
        assert(view.command == source.command && view.variant == source.name.toString && view.description == source.description)
        assert(view.template == CatalogPrompt(source.template, resource(source.template)))
        assert(view.parameters == source.arguments.map(argument => CatalogArgument(argument.option.field, argument.option.flag,
          argument.option.value, argument.option.summary, argument.option.choices, argument.required, argument.note)))
        assert(view.aliases.map(_.harness) == Harness.all)
        assert(view.template.text.nonEmpty)
        view.aliases.foreach { alias =>
          if (alias.harness == Harness.Pi) {
            assert(alias.alias == s"/cq:${view.command}" && alias.path == ".pi/extensions/cq-host.js")
            assert(alias.body == resource("cq/pi-attached.mjs"))
            assert(alias.body.contains(s"pi.registerCommand(\"cq:${view.command}\""))
          } else {
            val asset = DriverAssets.commands(alias.harness).find(_.path.toString == alias.path).get
            assert(alias.body == asset.body)
            assert(alias.alias == (if (alias.harness == Harness.Claude) s"/cq:${view.command}" else s"$$cq-${view.command}"))
          }
        }
      }
      val drive = drivers.head
      assert(drive.parameters.map(_.field) == List("targets", "through", "workset"))
      assert(drive.parameters.find(_.field == "through").get.choices == WorkflowPhase.all.map(_.toString.toLowerCase))
      assert(drive.parameters.forall(_.note.nonEmpty))
      assert(drivers.last.parameters.isEmpty)
    }

    "declare every driver template in native-image resource metadata" in {
      val metadata = parsed(resource("META-INF/native-image/cq/workflows/reachability-metadata.json"))
      val globs = metadata.hcursor.get[List[Json]]("resources").fold(throw _, identity)
        .map(_.hcursor.get[String]("glob").fold(throw _, identity))
      DriverAssets.catalog.foreach { command =>
        assert(globs.exists(glob => java.nio.file.FileSystems.getDefault.getPathMatcher(s"glob:$glob")
          .matches(java.nio.file.Path.of(command.template))), command.template)
      }
    }

    "carry exactly the agent catalog's prompts, schemas, examples and the launch tool permissions for every role-mode/harness pair" in {
      assert(catalog.agents.map(_.work) == agents.entries.map(_.work))
      catalog.agents.zip(agents.entries).foreach { (view, source) =>
        withClue(s"${source.work}: ") {
          assert(view.role == source.role && view.mode == source.mode && view.report == source.report && view.inputType == source.inputType)
          assert(view.prompt == CatalogPrompt(source.promptResource, resource(source.promptResource)) && view.prompt.text == source.prompt)
          assert(parsed(view.inputSchema) == source.inputSchema && parsed(view.outputSchema) == source.outputSchema)
          assert(parsed(view.workspaceSchema) == source.workspaceSchema)
          assert(view.inputExample == AgentExamples.input(source.work) && view.outputExample == AgentExamples.output(source.work))
          Harness.all.foreach { harness =>
            val target = view.harnesses.find(_.harness == harness).get
            val effective = source.on(harness)
            val policy = HarnessTools.policy(source.work, harness)
            assert(target.prompt == effective.prompt && parsed(target.outputSchema) == effective.outputSchema)
            assert(target.tools.edits == policy.edits && target.tools.workspaceCheck == policy.workspaceCheck)
            assert(target.tools.workspaceCheck == (source.role == Role.Reviewer))
            assert(target.tools.builtin.map(tool => tool.name -> tool.access.toString) == policy.builtin.map(tool => tool.name -> tool.access.toString))
            assert(target.tools.mcp.map(set => (set.target.toString, set.server, set.tools.map(tool => tool.name -> tool.access.toString))) ==
              policy.mcp.map(set => (set.target.toString, set.target.server, set.tools.map(tool => tool.name -> tool.access.toString))))
            McpTarget.values.foreach { mcp =>
              val enabled = target.tools.mcp.find(_.server == mcp.server).get.tools.filter(_.access == CatalogToolAccess.Enabled).map(_.name)
              assert(enabled == policy.enabledMcp(mcp) && enabled == HarnessTools.mcp(source.role, mcp))
            }
          }
        }
      }
    }

    "round-trip the catalog request and result through the generated JSON and UEBA codecs and match the generated schemas" in {
      val input = ReadInput(ProjectId(UUID.fromString("00000000-0000-4000-8000-000000000001")), ReadSelection.Catalog())
      val result: Result = Result.Catalog(catalog)
      assert(json(ReadInput_JsonCodec, input) == input && binary(ReadInput_UEBACodec, input) == input)
      assert(json(Result_JsonCodec, result) == result && binary(Result_UEBACodec, result) == result)
      assert(json(HelpCatalog_JsonCodec, catalog) == catalog && binary(HelpCatalog_UEBACodec, catalog) == catalog)
      assert(JsonSchemaCheck.errors(generatedSchema("ReadInput"), ReadInput_JsonCodec.encode(Context, input)) == Nil)
      assert(JsonSchemaCheck.errors(generatedSchema("Result"), Result_JsonCodec.encode(Context, result)) == Nil)
      val decoded = schemas.tools.find(_.name == "read").get.decode(parsed(s"""{"project":{"value":"${input.project.value}"},"selection":{"Catalog":{}}}"""))
      assert(decoded == Right(Command.Read(input)))
    }

    "be accepted by the read tool's advertised MCP output schema" in {
      val read = schemas.tools.find(_.name == "read").get
      assert(read.results.contains("Catalog"))
      val output = schemas.advertised(read).hcursor.downField("outputSchema").focus.get
      val body = Result_JsonCodec.encode(Context, Result.Catalog(catalog))
      assert(JsonSchemaCheck.errors(output, body) == Nil)
      val tags = output.hcursor.get[Vector[Json]]("oneOf").fold(throw _, identity).flatMap(_.hcursor.get[List[String]]("required").toOption).flatten
      assert(tags.contains("Catalog"))
    }
  }
}
