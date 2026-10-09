package cq.server

import cq.host.NativeExtensionPin
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import org.scalatest.wordspec.AnyWordSpec

final class NativeExtensionPinLocal extends AnyWordSpec {
  private def json(path: Path): Json =
    parse(Files.readString(path)).fold(e => throw new IllegalStateException(e.getMessage), identity)

  "The pinned subagent extension (Behavioral Active Blackbox Good Communication)" should {
    "be a locked non-flake GitHub source at the host revision" in {
      val root = Path.of(Option(System.getProperty("cq.test.sourceRoot")).getOrElse(fail("-Dcq.test.sourceRoot is required")))
      val lock = json(root.resolve("flake.lock")).hcursor
      val node = lock.downField("nodes").downField("ponygirls")
      assert(node.succeeded, "node ponygirls is missing")
      assert(node.downField("flake").as[Boolean] == Right(false))
      assert(node.downField("locked").downField("type").as[String] == Right("github"))
      assert(node.downField("locked").downField("owner").as[String] == Right("7mind"))
      assert(node.downField("locked").downField("repo").as[String] == Right("ponygirls"))
      assert(node.downField("locked").downField("rev").as[String] == Right(NativeExtensionPin.revision))
      assert(node.downField("inputs").failed, "ponygirls must have no inputs")
      assert(lock.downField("nodes").downField("root").downField("inputs").downField("ponygirls").as[String] == Right("ponygirls"))
    }

    "export the extension directory alone with the pinned name and version" in {
      val dir = Path.of(Option(System.getenv("CQ_PONYGIRLS_SUBAGENTS")).getOrElse(fail("CQ_PONYGIRLS_SUBAGENTS is not set")))
      assert(Files.isDirectory(dir), s"$dir is not a directory")
      assert(Files.isRegularFile(dir.resolve("index.ts")))
      assert(Files.isRegularFile(dir.resolve("package.json")))
      assert(!Files.exists(dir.resolve("flake.nix")))
      assert(!Files.exists(dir.resolve("nix")))
      val pkg = json(dir.resolve("package.json")).hcursor
      assert(pkg.downField("name").as[String] == Right(NativeExtensionPin.name))
      assert(pkg.downField("version").as[String] == Right(NativeExtensionPin.version))
    }
  }
}
