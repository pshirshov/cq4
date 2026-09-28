package cq.host

import java.nio.file.{Files, Path}

object HostCredential {
  private val MaxBytes = 8192
  def read(environment: Map[String, String]): String = {
    val token = environment.get("CQ_TOKEN").orElse(environment.get("CQ_TOKEN_FILE").map { value =>
      val path = Path.of(value)
      require(path.isAbsolute && Files.isRegularFile(path) && !Files.isSymbolicLink(path), "CQ_TOKEN_FILE must be an absolute regular file")
      HostFiles.text(path, MaxBytes).trim
    }).getOrElse(throw new IllegalArgumentException("CQ_TOKEN or CQ_TOKEN_FILE is required"))
    require(token.length >= 32 && token.length <= MaxBytes && !token.exists(_.isWhitespace), "Invalid CQ operator credential")
    token
  }
}
