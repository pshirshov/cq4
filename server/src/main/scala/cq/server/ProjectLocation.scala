package cq.server

import cq.host.{BoundedHostCommand, GitEnvironment}
import java.nio.file.Path
import java.time.Duration

final class ProjectLocation(context: CliContext) {
  private val GitTimeout = Duration.ofSeconds(10)
  private val MaxOutputBytes = 8192
  def directory: Path = {
    val result = new BoundedHostCommand(GitEnvironment.isolated(context.environment), GitTimeout, MaxOutputBytes)
      .run(context.directory, List("git", "rev-parse", "--path-format=absolute", "--git-common-dir"))
    if (result.exit == 0) Path.of(result.text.trim).resolve("cq") else context.directory.resolve(".cq")
  }
}
