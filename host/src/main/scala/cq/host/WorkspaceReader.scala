package cq.host

import cq.api.*
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class WorkspaceReader {
  private val MaxDirectoryEntries = 10000
  private val MaxPageEntries = 200
  private val MaxFileBytes = 256 * 1024
  private val MaxTextCodePoints = 8192
  private val MaxPathCharacters = 1024
  def apply(root: Path, command: WorkspaceCommand): WorkspaceReply = {
    require(root.isAbsolute && !Files.isSymbolicLink(root), "Workspace root is not a verified directory")
    val realRoot = root.toRealPath()
    def resolve(value: String): Path = {
      require(value.length <= MaxPathCharacters && !value.contains('\u0000') && !value.contains('\\'), "Invalid workspace path")
      val path = Path.of(value)
      require(!path.isAbsolute && path.iterator().asScala.forall(part => !Set("..", ".git")(part.toString)), "Workspace path is outside its read scope")
      var current = realRoot
      path.iterator().asScala.foreach { part =>
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current), "Workspace reads cannot follow symbolic links")
      }
      val resolved = current.toRealPath()
      require(resolved.startsWith(realRoot), "Workspace path escaped its root")
      resolved
    }
    command match {
      case WorkspaceCommand.Entries(path, after, limit) =>
        require(limit > 0 && limit <= MaxPageEntries && after.forall(value => value.length <= MaxPathCharacters && !value.contains('/')), "Invalid workspace page bounds")
        val directory = resolve(path)
        val all = Using.resource(Files.list(directory))(_.iterator().asScala.take(MaxDirectoryEntries + 1).toList)
        require(all.size <= MaxDirectoryEntries, "Workspace directory exceeds its listing bound")
        val page = all.filterNot(_.getFileName.toString == ".git").sortBy(_.getFileName.toString)
          .filter(value => after.forall(_ < value.getFileName.toString)).take(limit + 1)
        val entries = page.take(limit).map { value =>
          val kind = if (Files.isSymbolicLink(value)) WorkspaceEntryKind.Symlink
          else if (Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS)) WorkspaceEntryKind.Directory
          else WorkspaceEntryKind.File
          WorkspaceEntry(value.getFileName.toString, kind)
        }
        WorkspaceReply.Listed(WorkspacePage(entries, entries.lastOption.map(_.name), page.size > limit))
      case WorkspaceCommand.Read(path, offset, limit) =>
        require(offset >= 0 && limit > 0 && limit <= MaxTextCodePoints, "Invalid workspace text bounds")
        val text = HostFiles.text(resolve(path), MaxFileBytes)
        val count = text.codePointCount(0, text.length)
        require(offset <= count, "Workspace text offset exceeds content")
        val next = offset + limit.min(count - offset)
        WorkspaceReply.Text(WorkspaceText(path, offset, next, next < count,
          text.substring(text.offsetByCodePoints(0, offset), text.offsetByCodePoints(0, next))))
      case _: WorkspaceCommand.MergeReport => throw new IllegalArgumentException("Merge report requires a prepared resolver workspace")
    }
  }
}
