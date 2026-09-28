package cq.server

import cq.api.*
import cq.host.{HostFiles, WorkflowAssets}
import io.circe.parser
import java.nio.file.Files
import org.scalatest.wordspec.AnyWordSpec

final class AttachedAssetsLocal extends AnyWordSpec {
  "Attached setup (Behavioral Active Effectual filesystem Good Communication)" should {
    "install scoped integrations without credentials and preserve unrelated Claude entries" in {
      val root = Files.createTempDirectory("cq-attached-config-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set("PROVIDER_API_KEY")))
      val settings = root.resolve("settings.json")
      val value = SupervisorSettings(root.resolve("state").toString, binary.toString, profiles, HostLimits(1000, 10000, 500, 100, 1000, 65536), Nil, None, None)
      Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, value))
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      Files.writeString(root.resolve(".mcp.json"), "{\"mcpServers\":{\"unrelated\":{\"command\":\"keep\"}},\"other\":true}")
      Harness.all.foreach { harness =>
        val paths = assets.write(harness, root, settings, binary, false)
        assert(paths == assets.write(harness, root, settings, binary, false))
        assert(paths.forall(Files.isRegularFile(_)))
        val commands = new WorkflowAssets().commands(harness)
        assert(commands.forall(value => value.body.contains("Call the CQ `session`") && !value.body.contains("{{")))
      }
      val claude = parser.parse(Files.readString(root.resolve(".mcp.json"))).fold(throw _, identity)
      assert(claude.hcursor.downField("mcpServers").downField("unrelated").get[String]("command") == Right("keep"))
      assert(claude.hcursor.get[Boolean]("other") == Right(true))
      val codex = Files.readString(root.resolve(".codex/config.toml"))
      assert(codex.contains("CQ_TOKEN_FILE") && codex.contains("PROVIDER_API_KEY") && codex.contains("\"host\", \"codex\""))
      val pi = parser.parse(Files.readString(root.resolve(".pi/extensions/cq-host.json"))).fold(throw _, identity)
      assert(pi.hcursor.get[List[io.circe.Json]]("tools").toOption.get.size == 9)
    }
    "preflight command conflicts and refuse user-owned TOML even with replacement requested" in {
      val root = Files.createTempDirectory("cq-attached-conflict-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val settings = root.resolve("settings.json")
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set.empty))
      Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, SupervisorSettings(root.resolve("state").toString,
        binary.toString, profiles, HostLimits(1000, 10000, 500, 100, 1000, 65536), Nil, None, None)))
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      Files.createDirectories(root.resolve(".codex"))
      Files.writeString(root.resolve(".codex/config.toml"), "model = 'keep'\n")
      intercept[IllegalArgumentException](assets.write(Harness.Codex, root, settings, binary, true))
      assert(Files.readString(root.resolve(".codex/config.toml")) == "model = 'keep'\n" && !Files.exists(root.resolve(".agents")))
      val command = root.resolve(new WorkflowAssets().commands(Harness.Claude).last.path)
      Files.createDirectories(command.getParent)
      Files.writeString(command, "keep")
      intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, false))
      assert(!Files.exists(root.resolve(".mcp.json")))
    }
  }
}
