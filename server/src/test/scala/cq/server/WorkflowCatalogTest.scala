package cq.server

import cq.api.*
import cq.host.{WorkflowAssets, WorkflowCatalog, WorkflowCommand}
import io.circe.{Json, parser}
import java.nio.file.Files
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class WorkflowCatalogLocal extends AnyWordSpec {
  private def text(path: String): String = new String(getClass.getResourceAsStream("/" + path).readAllBytes(), "UTF-8")

  "Workflow command catalog (Behavioral Active Blackbox Group)" should {
    "match the alias, path and prompt of every written command asset for every harness" in {
      val assets = new WorkflowAssets
      assert(WorkflowCatalog.commands.map(_.command) == List("begin", "advance", "review", "upstream"))
      Harness.all.foreach { harness =>
        val root = Files.createTempDirectory("cq-catalog-export-").toAbsolutePath
        val written = assets.writeCommands(harness, root, false)
        val commands = assets.commands(harness)
        assert(written.size == WorkflowCatalog.commands.size && commands.size == WorkflowCatalog.commands.size)
        WorkflowCatalog.commands.zip(commands).zip(written).foreach { case ((command, asset), path) =>
          val alias = command.alias(harness)
          assert(asset.path == alias.path && path == root.resolve(alias.path), (harness, command.command))
          val body = Files.readString(path)
          assert(body == asset.body)
          val observedAlias = harness match {
            case Harness.Codex =>
              assert(alias.path.getParent.getFileName.toString == alias.alias && alias.path.getFileName.toString == "SKILL.md")
              "^---\nname: ([^\n]+)\n".r.findFirstMatchIn(body).map(_.group(1)).getOrElse("")
            case Harness.Claude =>
              assert(alias.path.startsWith(".claude/commands"))
              "/" + alias.path.getParent.getFileName + ":" + alias.path.getFileName.toString.stripSuffix(".md")
            case Harness.Pi =>
              assert(alias.path.getParent.toString == ".pi/prompts")
              "/" + alias.path.getFileName.toString.stripSuffix(".md")
          }
          assert(observedAlias == alias.alias, (harness, command.command))
          assert(body.linesIterator.exists(_.startsWith(s"description: ${command.description}")))
          val prompt = text(command.template).replace("{{WORKFLOW}}", command.command).replace("{{HARNESS}}", harness.toString.toLowerCase)
            .replace("{{VARIANT}}", command.variant).replace("{{ARGUMENTS}}", WorkflowCatalog.argumentGuide)
          assert(!prompt.contains("{{") && body.endsWith("\n\n" + prompt), (harness, command.command))
        }
        val files = Files.walk(root).iterator().asScala.filter(Files.isRegularFile(_)).toSet
        assert(files == written.toSet)
      }
    }

    "reference the instruction resources that the workflow assembly delivers" in {
      val assets = new WorkflowAssets
      List(WorkflowRequest.Begin(Set.empty), WorkflowRequest.Advance(Set.empty, WorkflowPhase.Work),
        WorkflowRequest.Review(ArtifactId(UUID.randomUUID()), ReviewerMode.Audit), WorkflowRequest.Upstream(Set.empty, UpstreamAction.Report)).foreach { request =>
        val command = WorkflowCatalog.of(request)
        assert(command.variant == request.getClass.getSimpleName)
        assert(assets.instructions(request) == command.instructions.map(text).mkString("\n"))
      }
    }

    "define arguments that agree with the generated WorkflowRequest model" in {
      val schemas = parser.parse(text("cq-schemas.json")).fold(throw _, identity)
      def schema(name: String): Json = schemas.hcursor.downField(s"cq_api_$name").focus.getOrElse(fail(s"Missing schema $name"))
      val variants = schema("WorkflowRequest").hcursor.downField("oneOf").values.getOrElse(fail("WorkflowRequest is not an ADT"))
        .map(_.hcursor.downField("required").as[List[String]].fold(throw _, identity).head).toList
      assert(variants == WorkflowCatalog.commands.map(_.variant))
      WorkflowCatalog.commands.foreach { command =>
        val variant = schema(s"WorkflowRequest_${command.variant}").hcursor
        val properties = variant.downField("properties").keys.getOrElse(fail(command.variant)).toSet
        assert(properties == command.arguments.map(_.option.field).toSet, command.variant)
        command.arguments.foreach { argument =>
          val property = variant.downField("properties").downField(argument.option.field)
          val reference = property.downField("$ref").as[String].toOption
          val enumeration = reference.map(_.stripPrefix("#/$defs/cq_api_")).flatMap(name => schema(name).hcursor.downField("enum").as[List[String]].toOption)
          assert(enumeration.getOrElse(Nil) == argument.option.choices, (command.variant, argument.option.field))
          // An optional argument must be a collection whose absence is the empty value.
          if (!argument.required) assert(property.downField("type").as[String].toOption.contains("array"), argument.option.field)
        }
      }
    }

    "drive WorkflowArguments parsing from the catalog's options and required/optional rules" in {
      val project = ProjectId(UUID.randomUUID())
      assert(WorkflowArguments.Options == WorkflowCatalog.options.map(_.flag).toSet + WorkflowCatalog.WorkflowFlag)
      def sample(command: WorkflowCommand): Map[String, String] = command.arguments.map { argument =>
        argument.option.flag -> (argument.option match {
          case WorkflowCatalog.Roots => "T1,I2"
          case WorkflowCatalog.Result => UUID.randomUUID().toString
          case option => option.choices.last.toLowerCase
        })
      }.toMap + (WorkflowCatalog.WorkflowFlag -> command.command)
      WorkflowCatalog.commands.foreach { command =>
        val full = sample(command)
        val parsed = WorkflowArguments.parse(project, full).getOrElse(fail(command.command))
        assert(WorkflowCatalog.of(parsed) == command && parsed.getClass.getSimpleName == command.variant)
        val fields = parsed.asInstanceOf[Product].productElementNames.toList
        assert(fields == command.arguments.map(_.option.field))
        command.arguments.filter(_.option.choices.nonEmpty).foreach { argument =>
          argument.option.choices.foreach { value =>
            val chosen = WorkflowArguments.parse(project, full.updated(argument.option.flag, value.toLowerCase)).get.asInstanceOf[Product]
            assert(chosen.productElement(fields.indexOf(argument.option.field)).toString == value)
          }
          intercept[IllegalArgumentException](WorkflowArguments.parse(project, full.updated(argument.option.flag, "unknown")))
        }
        command.arguments.foreach { argument =>
          val without = full - argument.option.flag
          if (argument.required) intercept[IllegalArgumentException](WorkflowArguments.parse(project, without))
          else assert(WorkflowArguments.parse(project, without).isDefined)
        }
        WorkflowCatalog.options.filterNot(option => command.arguments.exists(_.option == option)).foreach { option =>
          intercept[IllegalArgumentException](WorkflowArguments.parse(project, full + (option.flag -> "x")))
        }
      }
      intercept[IllegalArgumentException](WorkflowArguments.parse(project, Map(WorkflowCatalog.WorkflowFlag -> "unknown")))
    }

    "render the argument guidance of the entrypoint and CLI help from the catalog" in {
      assert(text("cq/workflows/entrypoint.md").contains("{{ARGUMENTS}}"))
      val help = CliHelp.render(List("help", "run"))
      assert(help.contains(WorkflowCatalog.optionHelp))
      WorkflowCatalog.options.foreach(option => assert(help.contains(s"  ${option.flag} ${option.value}")))
      WorkflowCatalog.commands.foreach { command =>
        assert(WorkflowCatalog.argumentGuide.contains(command.variant + " "))
        command.arguments.flatMap(_.option.choices).foreach(choice => assert(WorkflowCatalog.argumentGuide.contains(choice)))
      }
    }
  }
}
