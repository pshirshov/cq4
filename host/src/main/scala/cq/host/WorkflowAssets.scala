package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import scala.util.Using

enum WorkflowName { case Begin, Advance, Review, Upstream }
final case class CommandAsset(path: Path, body: String)

final class WorkflowAssets {
  private val MaxResourceBytes = 16384

  private def resource(name: String): String = Using.resource(Option(getClass.getResourceAsStream(s"/cq/workflows/$name.md"))
    .getOrElse(throw new IllegalStateException("Installed workflow resource is missing"))) { stream =>
    val bytes = stream.readNBytes(MaxResourceBytes + 1)
    require(bytes.length <= MaxResourceBytes, "Installed workflow resource exceeds its byte bound")
    UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
  }

  def name(request: WorkflowRequest): WorkflowName = request match {
    case _: WorkflowRequest.Begin => WorkflowName.Begin
    case _: WorkflowRequest.Advance => WorkflowName.Advance
    case _: WorkflowRequest.Review => WorkflowName.Review
    case _: WorkflowRequest.Upstream => WorkflowName.Upstream
  }

  def instructions(request: WorkflowRequest): String = resource("common") + "\n" + resource(name(request).toString.toLowerCase)

  def commands(harness: Harness): List[CommandAsset] = WorkflowName.values.toList.map { workflow =>
    val command = workflow.toString.toLowerCase
    val description = workflow match {
      case WorkflowName.Begin => "Capture CQ intake or a scope follow-up"
      case WorkflowName.Advance => "Advance selected CQ work through a specified phase"
      case WorkflowName.Review => "Independently review a stored CQ result"
      case WorkflowName.Upstream => "Prepare, report or recheck a CQ upstream defect"
    }
    val body = resource("entrypoint").replace("{{WORKFLOW}}", command).replace("{{HARNESS}}", harness.toString.toLowerCase).replace("{{VARIANT}}", workflow.toString)
    harness match {
      case Harness.Codex => CommandAsset(Path.of(s".agents/skills/cq-$command/SKILL.md"),
        s"---\nname: cq-$command\ndescription: $description. Use for the corresponding CQ workflow request.\n---\n\n$body")
      case Harness.Claude => CommandAsset(Path.of(s".claude/commands/cq/$command.md"),
        s"---\ndescription: $description\n---\n\nInvocation arguments: $$ARGUMENTS\n\n$body")
      case Harness.Pi => CommandAsset(Path.of(s".pi/prompts/cq:$command.md"),
        s"---\ndescription: $description\n---\n\nInvocation arguments: $$ARGUMENTS\n\n$body")
    }
  }

  def writeCommands(harness: Harness, root: Path, replace: Boolean): List[Path] = writeAssets(commands(harness), root, replace, Set.empty)

  def writeAssets(assets: List[CommandAsset], root: Path, replace: Boolean, merged: Set[Path]): List[Path] = {
    require(root.isAbsolute && root.normalize() == root, "Command export directory must be absolute and normalized")
    def safe(path: Path): Unit = {
      val ancestors = Iterator.iterate(path)(_.getParent).takeWhile(_ != null).toList
      require(ancestors.forall(value => !Files.isSymbolicLink(value)), "Command export cannot follow symbolic links")
      require(ancestors.drop(1).forall(value => !Files.exists(value, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)),
        "Command export parent is not a directory")
    }
    safe(root)
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Command export requires an existing project directory")
    require(assets.forall(value => !value.path.isAbsolute && value.path.normalize() == value.path && !value.path.startsWith("..")), "Asset path must stay inside the project")
    require(assets.map(_.path).distinct.size == assets.size, "Duplicate asset destination")
    val outputs = assets.map(value => root.resolve(value.path) -> value.body.getBytes(UTF_8))
    outputs.foreach { case (path, bytes) =>
      safe(path)
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Command destination is not a regular file")
        val same = Using.resource(Files.newInputStream(path))(_.readNBytes(bytes.length + 1)).sameElements(bytes)
        require(same || replace || merged(root.relativize(path)), s"Command destination differs: $path; use --replace for these generated files")
      }
    }
    outputs.foreach { case (path, bytes) =>
      safe(path)
      Files.createDirectories(path.getParent)
      if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (replace || merged(root.relativize(path))) Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
      } else Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
    outputs.map(_._1)
  }
}
