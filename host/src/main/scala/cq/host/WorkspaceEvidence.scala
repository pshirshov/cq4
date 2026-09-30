package cq.host

import cq.api.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final case class CollectedEvidence(retained: RetainedEvidence, uploads: List[ArtifactUpload])

object WorkspaceEvidence {
  val EvidenceDirectory = ".work/evidence"
  val MaxFiles = 32
  val MaxFileBytes = 256 * 1024
  val MaxTotalBytes = 2 * 1024 * 1024
  val MaxOmitted = 32
  val MaxPathCharacters = 1024
  private val MaxWalkEntries = 10000

  /** Decodes the longest strictly valid UTF-8 prefix of `bytes(0 until length)`, dropping at most one trailing incomplete sequence when cut. */
  def utf8Prefix(bytes: Array[Byte], length: Int, cut: Boolean): Option[String] = {
    val decoder = UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val minimum = if (cut) math.max(0, length - 3) else length
    (length to minimum by -1).iterator.map(end => Try(decoder.reset().decode(ByteBuffer.wrap(bytes, 0, end)).toString).toOption).collectFirst { case Some(text) => text }
  }
}

/** Retains bounded text evidence from a worker workspace as Evidence artifacts of the worker attempt. */
final class WorkspaceEvidence(project: ProjectId, attempt: AttemptId, prefix: String) {
  require(prefix.matches("[a-z][a-z0-9-]{0,30}"), "Invalid evidence artifact prefix")
  import WorkspaceEvidence.*
  private final class Collection(realRoot: Path) {
    private var files = List.empty[EvidenceFile]
    private var uploads = List.empty[ArtifactUpload]
    private var omitted = List.empty[String]
    private var hidden = 0
    private var seen = Set.empty[String]
    private var total = 0
    private def omit(path: String): Unit = if (omitted.size < MaxOmitted) omitted ::= path else hidden += 1
    private def relative(path: Path): String = realRoot.relativize(path).toString
    private def resolve(value: String): Either[String, Path] = Try {
      require(value.nonEmpty && value.length <= MaxPathCharacters && !value.contains('\u0000') && !value.contains('\\'), "Invalid evidence path")
      val path = Path.of(value)
      require(!path.isAbsolute && path.iterator().asScala.forall(part => !Set("..", ".git")(part.toString)), "Evidence path is outside its workspace")
      var current = realRoot
      path.iterator().asScala.foreach { part =>
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current), "Evidence path cannot follow symbolic links")
      }
      require(Files.exists(current, LinkOption.NOFOLLOW_LINKS), "Evidence path does not exist")
      val resolved = current.toRealPath()
      require(resolved.startsWith(realRoot), "Evidence path escaped its workspace")
      resolved
    }.toEither.left.map(_ => value)
    private def walk(directory: Path): List[Path] = {
      val entries = Using.resource(Files.walk(directory))(_.iterator().asScala.take(MaxWalkEntries + 1).toList)
      require(entries.size <= MaxWalkEntries, "Evidence directory exceeds its walk bound")
      entries.filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !path.iterator().asScala.exists(_.toString == ".git") &&
        !Files.isSymbolicLink(path)).sortBy(_.toString)
    }
    def add(value: String): Unit = resolve(value) match {
      case Left(rejected) => omit(rejected)
      case Right(resolved) =>
        val candidates = if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) walk(resolved) else List(resolved)
        candidates.foreach(retain)
    }
    private def retain(file: Path): Unit = {
      val path = relative(file)
      if (!seen(path)) {
        seen += path
        val size = Files.size(file)
        val remaining = math.min(MaxFileBytes.toLong, MaxTotalBytes.toLong - total)
        if (files.size >= MaxFiles || remaining <= 0) omit(path)
        else {
          val cut = size > remaining
          val bytes = Using.resource(Files.newInputStream(file))(_.readNBytes(remaining.toInt))
          utf8Prefix(bytes, bytes.length, cut) match {
            case None => omit(path)
            case Some(text) =>
              val retainedBytes = text.getBytes(UTF_8).length
              val id = NativeArtifacts.id(attempt, s"$prefix-${files.size}")
              uploads ::= ArtifactUpload(project, id, attempt, ArtifactKind.Evidence, "text/plain", text)
              files ::= EvidenceFile(path, id, size, cut || retainedBytes < size)
              total += retainedBytes
          }
        }
      }
    }
    def result: CollectedEvidence = {
      val listed = omitted.reverse ++ Option.when(hidden > 0)(s"(and $hidden more omitted paths)")
      CollectedEvidence(RetainedEvidence(files.reverse, listed), uploads.reverse)
    }
  }

  /** Collects the evidence directory first, then the worker-named paths, in that order; bounds apply across both. */
  def collect(root: Path, named: List[String]): CollectedEvidence = {
    require(root.isAbsolute && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Workspace root is not a verified directory")
    val collection = new Collection(root.toRealPath())
    if (Files.isDirectory(root.resolve(EvidenceDirectory), LinkOption.NOFOLLOW_LINKS)) collection.add(EvidenceDirectory)
    named.foreach(collection.add)
    collection.result
  }
}
