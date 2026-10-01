package cq.server

import cq.api.*
import cq.host.{DriverAssets, HostFiles, WorkflowAssets}
import io.circe.{Json, parser}
import java.nio.file.Files
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

final class AttachedAssetsLocal extends AnyWordSpec {
  private def project(prefix: String): (java.nio.file.Path, java.nio.file.Path, java.nio.file.Path) = {
    val root = Files.createTempDirectory(prefix).toAbsolutePath
    val binary = root.resolve("cq")
    Files.writeString(binary, "#!/bin/sh\nexit 0\n")
    assert(binary.toFile.setExecutable(true))
    val settings = root.resolve("settings.json")
    val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set.empty))
    Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, SupervisorSettings(root.resolve("state").toString,
      binary.toString, profiles, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)))
    (root, binary, settings)
  }

  "Attached setup (Behavioral Active Effectual filesystem Good Communication)" should {
    "install scoped integrations without credentials and preserve unrelated Claude entries" in {
      val root = Files.createTempDirectory("cq-attached-config-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set("PROVIDER_API_KEY")))
      val settings = root.resolve("settings.json")
      val value = SupervisorSettings(root.resolve("state").toString, binary.toString, profiles, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)
      Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, value))
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      Files.writeString(root.resolve(".mcp.json"), "{\"mcpServers\":{\"unrelated\":{\"command\":\"keep\"}},\"other\":true}")
      Harness.all.foreach { harness =>
        val paths = assets.write(harness, root, settings, binary, false, false)
        assert(paths == assets.write(harness, root, settings, binary, false, false))
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
      // Drive and park are commands of the Pi extension, with one toggle key; the prompt templates stay the four workflow prompts.
      val extension = Files.readString(root.resolve(".pi/extensions/cq-host.js"))
      assert(List("pi.registerCommand(\"cq:drive\"", "pi.registerCommand(\"cq:park\"", "pi.registerShortcut(DRIVER_TOGGLE", "DRIVER_TOGGLE = \"ctrl+alt+a\"",
        "connection.rpc(\"cq/driver\"").forall(extension.contains))
      val prompts = new WorkflowAssets().commands(Harness.Pi)
      assert(scala.util.Using.resource(Files.list(root.resolve(".pi/prompts")))(_.toList.asScala.toSet) == prompts.map(value => root.resolve(value.path)).toSet)
      assert(prompts.map(_.path.getFileName.toString) == List("cq:begin.md", "cq:advance.md", "cq:review.md", "cq:upstream.md") &&
        prompts.forall(value => Files.readString(root.resolve(value.path)) == value.body))
    }
    "approve the cq project server in Claude local settings while preserving existing local settings" in {
      val root = Files.createTempDirectory("cq-attached-approval-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val settings = root.resolve("settings.json")
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set.empty))
      Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, SupervisorSettings(root.resolve("state").toString,
        binary.toString, profiles, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)))
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      val local = root.resolve(".claude/settings.local.json")
      def approved: io.circe.Json = parser.parse(Files.readString(local)).fold(throw _, identity)
      val paths = assets.write(Harness.Claude, root, settings, binary, false, false)
      assert(paths.contains(local))
      assert(approved.hcursor.get[List[String]]("enabledMcpjsonServers") == Right(List("cq")))
      Files.writeString(local, "{\"enabledMcpjsonServers\":[\"other\"],\"permissions\":{\"allow\":[\"Bash(ls)\"]}}")
      assets.write(Harness.Claude, root, settings, binary, false, false)
      assets.write(Harness.Claude, root, settings, binary, false, false)
      assert(approved.hcursor.get[List[String]]("enabledMcpjsonServers") == Right(List("other", "cq")))
      assert(approved.hcursor.downField("permissions").get[List[String]]("allow") == Right(List("Bash(ls)")))
      // Claude Code records a declined server in disabledMcpjsonServers, which overrides the approval.
      Files.writeString(local, "{\"enabledMcpjsonServers\":[\"cq\"],\"disabledMcpjsonServers\":[\"other\",\"cq\"]}")
      assets.write(Harness.Claude, root, settings, binary, false, false)
      assert(approved.hcursor.get[List[String]]("enabledMcpjsonServers") == Right(List("cq")))
      assert(approved.hcursor.get[List[String]]("disabledMcpjsonServers") == Right(List("other")))
      List("[\"cq\"]", "{\"enabledMcpjsonServers\":\"cq\"}").foreach { invalid =>
        Files.writeString(local, invalid)
        intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, true, false))
        assert(Files.readString(local) == invalid)
      }
    }
    "preflight command conflicts and refuse user-owned TOML even with replacement requested" in {
      val root = Files.createTempDirectory("cq-attached-conflict-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val settings = root.resolve("settings.json")
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", "version", Nil, Set.empty))
      Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, SupervisorSettings(root.resolve("state").toString,
        binary.toString, profiles, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)))
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      Files.createDirectories(root.resolve(".codex"))
      Files.writeString(root.resolve(".codex/config.toml"), "model = 'keep'\n")
      intercept[IllegalArgumentException](assets.write(Harness.Codex, root, settings, binary, true, false))
      assert(Files.readString(root.resolve(".codex/config.toml")) == "model = 'keep'\n" && !Files.exists(root.resolve(".agents")))
      val command = root.resolve(new WorkflowAssets().commands(Harness.Claude).last.path)
      Files.createDirectories(command.getParent)
      Files.writeString(command, "keep")
      intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, false, false))
      assert(!Files.exists(root.resolve(".mcp.json")) && !Files.exists(root.resolve(".claude/settings.local.json")))
    }
    "install the Claude drive and park commands, CQ hooks and status line, keep user-owned hooks and refuse a differing status line" in {
      val (root, binary, settings) = project("cq-attached-claude-driver-")
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      val local = root.resolve(".claude/settings.local.json")
      def installed: Json = parser.parse(Files.readString(local)).fold(throw _, identity)
      def handler(command: String): Json = Json.obj("type" -> Json.fromString("command"), "command" -> Json.fromString(command))
      def group(handlers: Json*): Json = Json.obj("hooks" -> Json.arr(handlers*))
      def cq(event: String): Json = handler(s"$binary hook claude $event")
      val paths = assets.write(Harness.Claude, root, settings, binary, false, false)
      val drive = root.resolve(".claude/commands/cq/drive.md")
      val park = root.resolve(".claude/commands/cq/park.md")
      assert(paths.contains(drive) && paths.contains(park) && paths == assets.write(Harness.Claude, root, settings, binary, false, false))
      assert(Files.readString(drive).startsWith("---\ndescription: Turn the CQ auto-driver on for <target IDs> through=<phase> or workset=<id>\n---\n\nInvocation arguments: $ARGUMENTS\n\n"))
      assert(Files.readString(drive).contains("/cq:drive") && Files.readString(drive).contains("--setting-sources project,local") && Files.readString(park).contains("/cq:park"))
      assert(List(drive, park).forall(file => Files.readString(file).contains("cannot start or park a driver") && !Files.readString(file).contains("{{")))
      assert(installed == Json.obj("enabledMcpjsonServers" -> Json.arr(Json.fromString("cq")),
        "hooks" -> Json.obj("UserPromptSubmit" -> Json.arr(group(cq("UserPromptSubmit"))), "Stop" -> Json.arr(group(cq("Stop")))),
        "statusLine" -> cq("StatusLine")))
      // User-owned hook entries and events stay; an earlier CQ entry, even one for another executable, is replaced and not duplicated.
      val notify = Json.obj("matcher" -> Json.fromString(""), "hooks" -> Json.arr(handler("/usr/bin/notify-send done")))
      val audit = Json.arr(Json.obj("matcher" -> Json.fromString("Bash"), "hooks" -> Json.arr(handler("/usr/local/bin/audit"))))
      Files.writeString(local, Json.obj("permissions" -> Json.obj("allow" -> Json.arr(Json.fromString("Bash(ls)"))),
        "hooks" -> Json.obj("Stop" -> Json.arr(notify, group(handler("/old/place/cq hook claude Stop"))), "PreToolUse" -> audit,
          "UserPromptSubmit" -> Json.arr(group(handler("'/old place/cq' hook claude UserPromptSubmit"), handler("echo mine")))),
        "statusLine" -> handler("/old/place/cq hook claude StatusLine")).spaces2)
      assets.write(Harness.Claude, root, settings, binary, false, false)
      val merged = installed
      assets.write(Harness.Claude, root, settings, binary, false, false)
      assert(installed == merged && merged.hcursor.downField("permissions").get[List[String]]("allow") == Right(List("Bash(ls)")))
      assert(merged.hcursor.downField("hooks").focus.contains(Json.obj("Stop" -> Json.arr(notify, group(cq("Stop"))), "PreToolUse" -> audit,
        "UserPromptSubmit" -> Json.arr(group(handler("echo mine")), group(cq("UserPromptSubmit"))))))
      assert(merged.hcursor.downField("statusLine").focus.contains(cq("StatusLine")))
      // A statusLine that is not CQ's is user-owned: refused before anything is written, also under --replace, until the operator replaces it explicitly.
      val own = Json.obj("statusLine" -> handler("~/.claude/statusline.sh"), "hooks" -> Json.obj("Stop" -> Json.arr(notify)))
      Files.writeString(local, own.spaces2)
      Files.delete(drive)
      List(false, true).foreach { replace =>
        val refused = intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, replace, false))
        assert(refused.getMessage.contains("Claude statusLine in .claude/settings.local.json differs; use --replace-statusline"))
        assert(Files.readString(local) == own.spaces2 && !Files.exists(drive))
      }
      assert(DriverAssets.StatusLineFlag == "--replace-statusline")
      assets.write(Harness.Claude, root, settings, binary, false, true)
      assert(installed.hcursor.downField("statusLine").focus.contains(cq("StatusLine")) && Files.exists(drive))
      assert(installed.hcursor.downField("hooks").downField("Stop").focus.contains(Json.arr(notify, group(cq("Stop")))))
      List("{\"hooks\":[]}", "{\"hooks\":{\"Stop\":{}}}").foreach { invalid =>
        Files.writeString(local, invalid)
        intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, true, true))
        assert(Files.readString(local) == invalid)
      }
    }
    "install the Codex drive and park skills and CQ hooks in .codex/hooks.json and keep user-owned entries" in {
      val (root, binary, settings) = project("cq-attached-codex-driver-")
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      val file = root.resolve(".codex/hooks.json")
      def installed: Json = parser.parse(Files.readString(file)).fold(throw _, identity)
      def handler(command: String): Json = Json.obj("type" -> Json.fromString("command"), "command" -> Json.fromString(command))
      def group(handlers: Json*): Json = Json.obj("hooks" -> Json.arr(handlers*))
      def cq(event: String): Json = handler(s"$binary hook codex $event")
      val paths = assets.write(Harness.Codex, root, settings, binary, false, false)
      val drive = root.resolve(".agents/skills/cq-drive/SKILL.md")
      val park = root.resolve(".agents/skills/cq-park/SKILL.md")
      assert(paths.contains(file) && paths.contains(drive) && paths.contains(park) && paths == assets.write(Harness.Codex, root, settings, binary, false, false))
      assert(Files.readString(drive).startsWith("---\nname: cq-drive\ndescription: Turn the CQ auto-driver on for <target IDs> through=<phase> or workset=<id>\n---\n\n"))
      assert(Files.readString(drive).contains("$cq-drive") && Files.readString(drive).contains("`/hooks` review") && Files.readString(park).startsWith("---\nname: cq-park\n"))
      assert(List(drive, park).forall(file => Files.readString(file).contains("cannot start or park a driver") && !Files.readString(file).contains("{{")))
      // Codex shows no custom status-line text, so only the two hooks are installed.
      assert(installed == Json.obj("hooks" -> Json.obj("UserPromptSubmit" -> Json.arr(group(cq("UserPromptSubmit"))), "Stop" -> Json.arr(group(cq("Stop"))))))
      val notify = group(handler("/usr/bin/notify-send done"))
      val start = Json.arr(group(handler("/usr/local/bin/greet")))
      Files.writeString(file, Json.obj("note" -> Json.fromString("mine"), "hooks" -> Json.obj("SessionStart" -> start,
        "Stop" -> Json.arr(group(handler("/old/place/cq hook codex Stop"), handler("echo mine")), notify))).noSpaces)
      assets.write(Harness.Codex, root, settings, binary, false, false)
      val merged = installed
      assets.write(Harness.Codex, root, settings, binary, false, false)
      assert(installed == merged && merged == Json.obj("note" -> Json.fromString("mine"), "hooks" -> Json.obj("SessionStart" -> start,
        "Stop" -> Json.arr(group(handler("echo mine")), notify, group(cq("Stop"))), "UserPromptSubmit" -> Json.arr(group(cq("UserPromptSubmit"))))))
      Files.writeString(file, "[]")
      Files.delete(drive)
      intercept[IllegalArgumentException](assets.write(Harness.Codex, root, settings, binary, true, false))
      assert(Files.readString(file) == "[]" && !Files.exists(drive))
      // Pi drives from its extension: no hook entry, command file or skill is generated for it here.
      assert(DriverAssets.commands(Harness.Pi).isEmpty && DriverAssets.commands(Harness.Claude).size == 2 && DriverAssets.commands(Harness.Codex).size == 2)
    }
  }
}
