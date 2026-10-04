package cq.server

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.util.Using

final case class SchemaIdentity(sql: String, sha256: String)
object SchemaIdentity {
  def current(): SchemaIdentity = {
    val resource = "/db/001-ledgers.sql"
    val bytes = Using.resource(Option(getClass.getResourceAsStream(resource)).getOrElse(throw new IllegalStateException(s"Missing $resource")))(_.readAllBytes())
    SchemaIdentity(new String(bytes, UTF_8), MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString)
  }
}
