package cq.server

import cq.api.Harness
import cq.host.{DriverAssets, WorkflowAssets}
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, NoSuchFileException, NotDirectoryException, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.util.Using

enum CommandFile {
  case Content(bytes: Array[Byte])
  case Missing, NotRegular, Unreadable
}

trait CommandAssetReader {
  def read(path: Path, maximum: Int): CommandFile
}

final class FileCommandAssetReader extends CommandAssetReader {
  override def read(path: Path, maximum: Int): CommandFile = {
    require(maximum > 0, "Command read bound must be positive")
    try {
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      if (!attributes.isRegularFile) CommandFile.NotRegular
      else CommandFile.Content(Using.resource(Files.newInputStream(path))(_.readNBytes(maximum)))
    } catch {
      case _: NoSuchFileException => CommandFile.Missing
      case _: NotDirectoryException => CommandFile.NotRegular
      case _: IOException => CommandFile.Unreadable
    }
  }
}

enum CommandAssetState { case Current, Missing, Different, NotRegular, Unreadable }
final case class CommandAssetCheck(path: Path, state: CommandAssetState)
final case class CommandDoctorReport(harness: Harness, directory: Path, checks: List[CommandAssetCheck]) {
  def current: Boolean = checks.forall(_.state == CommandAssetState.Current)
}
final class CommandAssetsNeedAttention extends RuntimeException("Command assets need attention; see doctor report")

final class CommandDoctor(reader: CommandAssetReader, workflows: WorkflowAssets) {
  def inspect(harness: Harness, directory: Path): CommandDoctorReport = {
    require(directory.isAbsolute && directory.normalize() == directory, "Doctor directory must be absolute and normalized")
    val commands = workflows.commands(harness) ++ DriverAssets.commands(harness)
    require(commands.nonEmpty && commands.map(_.path).distinct.size == commands.size, "Doctor command inventory invariant")
    val checks = commands.map { asset =>
      val expected = asset.body.getBytes(UTF_8)
      val state = reader.read(directory.resolve(asset.path), expected.length + 1) match {
        case CommandFile.Content(bytes) => if (bytes.sameElements(expected)) CommandAssetState.Current else CommandAssetState.Different
        case CommandFile.Missing => CommandAssetState.Missing
        case CommandFile.NotRegular => CommandAssetState.NotRegular
        case CommandFile.Unreadable => CommandAssetState.Unreadable
      }
      CommandAssetCheck(asset.path, state)
    }
    CommandDoctorReport(harness, directory, checks)
  }
}
