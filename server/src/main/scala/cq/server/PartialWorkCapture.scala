package cq.server

import cq.api.*
import cq.host.*
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.Duration
import scala.util.Try

/** Captures what a worker left behind when its attempt failed or was cancelled, so the next attempt can continue from it. */
final class PartialWorkCapture(config: SupervisorConfig) {
  import PartialWorkCapture.*
  private val command = new BoundedHostCommand(GitEnvironment.isolated(HostEnvironment.runtime(config.environment)), Duration.ofSeconds(10), MaxGitBytes)
  private def git(directory: Path, arguments: String*): Option[String] = {
    val result = command.run(directory, List("git", "--no-replace-objects", "--no-pager", "-c", "core.hooksPath=/dev/null", "-c", "submodule.recurse=false") ++ arguments)
    Option.when(result.exit == 0)(result.text)
  }
  private def tail(bytes: Array[Byte]): String = new String(bytes.drop(math.max(0, bytes.length - MaxTailBytes)), UTF_8)

  def capture(attempt: AttemptId, state: AttemptState, tree: Path, stdout: Array[Byte], stderr: Array[Byte]): (ArtifactId, List[ArtifactUpload]) = {
    val project = config.project.project
    def upload(name: String, body: String): ArtifactUpload = ArtifactUpload(project, NativeArtifacts.id(attempt, name), attempt, ArtifactKind.Evidence, "text/plain", body)
    val entries = git(tree, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--").map { output =>
      val tokens = output.split('\u0000').toList.filter(_.nonEmpty)
      def parse(remaining: List[String], acc: List[(String, String)]): List[(String, String)] = remaining match {
        case Nil => acc.reverse
        case entry :: rest =>
          val code = entry.take(2)
          val pathless = if (Set('R', 'C')(code.head)) rest.drop(1) else rest
          parse(pathless, (code, entry.drop(3)) :: acc)
      }
      parse(tokens, Nil)
    }
    val status = entries.map(values => upload("partial-status", values.map((code, path) => s"$code $path\n").mkString))
    val untracked = entries.toList.flatten.collect { case ("??", path) => path }
    val diff = Try(git(tree, "diff", "--no-color", "--no-ext-diff", "HEAD", "--")).toOption.flatten.map { text =>
      val bytes = text.getBytes(UTF_8)
      if (bytes.length <= MaxTextBytes) (upload("partial-diff", text), false)
      else (upload("partial-diff", WorkspaceEvidence.utf8Prefix(bytes, MaxTextBytes, true).getOrElse("")), true)
    }
    val evidence = new WorkspaceEvidence(project, attempt, "partial-evidence").collect(tree, untracked)
    val out = upload("partial-stdout", tail(stdout))
    val err = upload("partial-stderr", tail(stderr))
    val manifest = PartialWork(attempt, state, out.id, err.id, status.map(_.id), diff.map(_._1.id), diff.forall(_._2), evidence.retained)
    val record = ArtifactUpload(project, NativeArtifacts.id(attempt, "partial"), attempt, ArtifactKind.Evidence, "application/json",
      HostFiles.encode(PartialWork_JsonCodec, manifest))
    (record.id, out :: err :: status.toList ++ diff.map(_._1).toList ++ evidence.uploads :+ record)
  }
}

object PartialWorkCapture {
  val MaxTailBytes = 64 * 1024
  val MaxTextBytes = 256 * 1024
  private val MaxGitBytes = 8 * 1024 * 1024
}
