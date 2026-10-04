package cq.server

import java.nio.file.{Files, Path}
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class ServerCredentialsLocal extends AnyWordSpec {
  private def fixture(operation: Path => Unit): Unit = {
    val root = Files.createTempDirectory("cq-server-credentials-").toAbsolutePath
    try operation(root) finally Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  }
  "Runtime server credentials (Behavioral Active Blackbox filesystem)" should {
    "reject malformed UTF-8 rather than replacing invalid password bytes" in fixture { root =>
      val file = Files.write(root.resolve("password"), Array[Byte](0xc3.toByte, 0x28.toByte))
      intercept[java.nio.charset.MalformedInputException](ServerCredentials.password(Map("CQ_DATABASE_PASSWORD_FILE" -> file.toString)))
    }
    "read token and password files without changing their content or metadata" in fixture { root =>
      val token = Files.writeString(root.resolve("token"), "a" * 32 + "\n")
      val password = Files.writeString(root.resolve("password"), " leading and trailing spaces \r\n")
      val before = Files.getLastModifiedTime(password)
      val environment = Map("CQ_TOKEN_FILE" -> token.toString, "CQ_DATABASE_PASSWORD_FILE" -> password.toString)
      assert(ServerCredentials.token(environment) == "a" * 32)
      assert(ServerCredentials.password(environment) == " leading and trailing spaces ")
      assert(Files.readString(password) == " leading and trailing spaces \r\n" && Files.getLastModifiedTime(password) == before)
    }
    "keep explicit environment values authoritative, including an empty trust password" in {
      assert(ServerCredentials.password(Map("CQ_DATABASE_PASSWORD" -> "", "CQ_DATABASE_PASSWORD_FILE" -> "/missing")) == "")
      assert(ServerCredentials.token(Map("CQ_TOKEN" -> "a" * 32, "CQ_TOKEN_FILE" -> "/missing")) == "a" * 32)
    }
    "refuse missing, relative and symbolic credential files" in fixture { root =>
      val file = Files.writeString(root.resolve("password"), "secret")
      val link = Files.createSymbolicLink(root.resolve("link"), file)
      List("relative", root.resolve("missing").toString, link.toString, root.toString).foreach { path =>
        intercept[IllegalArgumentException](ServerCredentials.password(Map("CQ_DATABASE_PASSWORD_FILE" -> path)))
      }
      intercept[IllegalArgumentException](ServerCredentials.password(Map.empty))
    }
    "refuse empty, multiline, NUL and oversized password files without disclosing contents" in fixture { root =>
      val file = root.resolve("password")
      List("", "first\nsecond", "secret\u0000", "x" * 8193).foreach { contents =>
        Files.writeString(file, contents)
        val failure = intercept[IllegalArgumentException](ServerCredentials.password(Map("CQ_DATABASE_PASSWORD_FILE" -> file.toString)))
        assert(!failure.getMessage.contains("secret"))
      }
    }
  }
}
