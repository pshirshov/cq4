package cq.server

import cq.host.{HostCredential, HostFiles}
import java.nio.file.{Files, Path}

object ServerCredentials {
  private val MaxPasswordBytes = 8192
  def token(environment: Map[String, String]): String = HostCredential.read(environment)
  def password(environment: Map[String, String]): String =
    environment.get("CQ_DATABASE_PASSWORD").getOrElse {
      val value = environment.getOrElse("CQ_DATABASE_PASSWORD_FILE", throw new IllegalArgumentException("CQ_DATABASE_PASSWORD or CQ_DATABASE_PASSWORD_FILE is required"))
      val path = Path.of(value)
      require(path.isAbsolute && Files.isRegularFile(path) && !Files.isSymbolicLink(path), "CQ_DATABASE_PASSWORD_FILE must be an absolute regular file")
      val contents = HostFiles.text(path, MaxPasswordBytes)
      val password = if (contents.endsWith("\r\n")) contents.dropRight(2) else if (contents.endsWith("\n")) contents.dropRight(1) else contents
      require(password.nonEmpty && !password.exists(character => character == '\n' || character == '\r' || character == '\u0000'), "Database password file must contain one nonempty line")
      password
    }
}
