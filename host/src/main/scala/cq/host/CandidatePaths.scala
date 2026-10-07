package cq.host

import cq.api.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8

/** What a candidate does to one path against the commit it is compared with. */
enum PathChange { case Added, Changed, Deleted }

/** One path of a candidate's change. `bytes` is the size of the candidate's content: a deleted path has none, nor has one the host could not size. */
final case class CandidatePath(path: String, change: PathChange, bytes: Option[Long], binary: Boolean)

/** The paths a candidate adds, changes or deletes against `base`, as the text a candidate reviewer is given in its input. */
object CandidatePaths {
  // The list travels inside the reviewer's input, beside an assembled input of up to `ChildContracts.MaxInputBytes`, in one artifact part.
  private val MaxBytes = 32 * 1024
  val Heading = "Candidate paths"

  /** The artifact of a reviewing attempt that holds the list. */
  def artifact(attempt: AttemptId): ArtifactId = NativeArtifacts.id(attempt, "candidate-paths")

  private def heading(base: GitCommit): String =
    s"$Heading: every path the candidate adds, changes or deletes against ${base.value}, as the host read them from Git.\n"

  // A path is written as a JSON string: one line each, whatever characters the name holds.
  private def line(value: CandidatePath): String = {
    val content = if (value.change == PathChange.Deleted) "" else value.bytes.fold("")(bytes => s"$bytes bytes ") + (if (value.binary) "binary " else "text ")
    s"${value.change.toString.toLowerCase(java.util.Locale.ROOT)} $content${Json.fromString(value.path).noSpaces}\n"
  }

  def render(base: GitCommit, paths: List[CandidatePath]): String = {
    def count(change: PathChange): Int = paths.count(_.change == change)
    val summary = s"${paths.size} ${if (paths.size == 1) "path" else "paths"}: ${count(PathChange.Added)} added, ${count(PathChange.Changed)} changed, " +
      s"${count(PathChange.Deleted)} deleted; ${paths.count(value => value.binary && value.change != PathChange.Deleted)} binary.\n"
    val lines = paths.map(line)
    val sizes = lines.scanLeft(0)(_ + _.getBytes(UTF_8).length).tail
    val listed = lines.zip(sizes).takeWhile(_._2 <= MaxBytes).map(_._1)
    val omitted = lines.size - listed.size
    heading(base) + summary + listed.mkString + (if (omitted == 0) "" else s"$omitted more ${if (omitted == 1) "path is" else "paths are"} not listed: the list is cut at its size bound.\n")
  }

  /** The text for a candidate that changes more paths than the host reads the names of. */
  def unlisted(base: GitCommit, paths: Int, bound: Int): String =
    heading(base) + s"$paths paths, more than the $bound the host lists: none is listed here.\n"
}
