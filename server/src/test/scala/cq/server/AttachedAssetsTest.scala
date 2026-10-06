package cq.server

import cq.api.*
import cq.host.{DispatchWaits, DriverAssets, HarnessUsage, HostFiles, WorkflowAssets}
import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class AttachedAssetsLocal extends AnyWordSpec {
  private def project(prefix: String): (java.nio.file.Path, java.nio.file.Path, java.nio.file.Path) = {
    val root = Files.createTempDirectory(prefix).toAbsolutePath
    val binary = root.resolve("cq")
    Files.writeString(binary, "#!/bin/sh\nexit 0\n")
    assert(binary.toFile.setExecutable(true))
    val settings = root.resolve("settings.json")
    val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", HarnessUsage.version(harness), Nil, Set.empty))
    Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, SupervisorSettings(root.resolve("state").toString,
      binary.toString, profiles, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)))
    (root, binary, settings)
  }

  /** What `cq configure claude` allows for a state root `state` under `root`: the waiter, and the file tools in its sessions' workspaces. */
  private def allowed(root: Path, binary: Path): List[String] =
    List(s"Bash($binary wait)", s"Edit(/${root.resolve("state")}/*/workspaces/**)", s"Read(/${root.resolve("state")}/*/workspaces/**)")

  "Attached setup (Behavioral Active Effectual filesystem Good Communication)" should {
    "install scoped integrations without credentials and preserve unrelated Claude entries" in {
      val root = Files.createTempDirectory("cq-attached-config-").toAbsolutePath
      val binary = root.resolve("cq")
      Files.writeString(binary, "#!/bin/sh\nexit 0\n")
      assert(binary.toFile.setExecutable(true))
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", HarnessUsage.version(harness), Nil, Set("PROVIDER_API_KEY")))
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
      // Every integration starts the host with the executable it approved for `cq wait`, so the host can name that command to the session.
      val launched = List("host", "--settings", settings.toString, "--executable", binary.toString)
      assert(claude.hcursor.downField("mcpServers").downField("cq").get[List[String]]("args") == Right(launched.patch(1, List("claude"), 0)))
      assert(codex.contains("args = " + launched.patch(1, List("codex"), 0).map(value => "\"" + value + "\"").mkString("[", ", ", "]")))
      // A Codex session waits in a status call of the host and runs no command for it, so nothing is approved for its shell.
      assert(!Files.exists(root.resolve(".codex/rules")))
      assert(parser.parse(Files.readString(root.resolve(".claude/settings.local.json"))).fold(throw _, identity)
        .hcursor.downField("permissions").get[List[String]]("allow") == Right(allowed(root, binary)))
      // I30: nothing is configured for a Codex sandbox, whose writable roots take no pattern: see docs/interactive.md.
      assert(!codex.contains("writable_roots") && !codex.contains("sandbox"))
      // Every harness allows a tool call the longest dispatch wait, the host's own 30 s for the request and a margin for the host to answer first.
      assert(codex.contains("\ntool_timeout_sec = 155\n") && claude.hcursor.downField("mcpServers").downField("cq").get[Long]("timeout") == Right(155000L))
      assert(DispatchWaits.AttachedHarnessSeconds * 1000 == DispatchWaits.MaxMillis + (DispatchWaits.RequestSeconds + DispatchWaits.HarnessMarginSeconds) * 1000)
      val bridges = List("pi-attached.mjs" -> (DispatchWaits.RequestSeconds + DispatchWaits.HarnessMarginSeconds), "pi-bridge.mjs" -> DispatchWaits.RequestSeconds).map { (name, seconds) =>
        val source = new String(getClass.getResourceAsStream("/cq/" + name).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        assert(source.contains(s"const MAX_WAIT_MILLIS = ${DispatchWaits.MaxMillis};") && source.contains(s"const REQUEST_MILLIS = ${seconds * 1000};"), name)
        assert(source.contains("REQUEST_MILLIS + waitMillis("), name)
      }
      assert(bridges.size == 2)
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
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", HarnessUsage.version(harness), Nil, Set.empty))
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
      // The waiter is allowed as exactly one command line, with no wildcard; what the operator allowed stays, and nothing is added twice.
      assert(approved.hcursor.downField("permissions").get[List[String]]("allow") == Right("Bash(ls)" :: allowed(root, binary)))
      // I30: the file tools are allowed in the workspaces of the sessions of this state root: the rule is rooted at the filesystem,
      // names one directory level for the session and nothing else under the state root.
      val state = root.resolve("state")
      assert(assets.claudeWorkspaceRules(root, state.toString) == List(s"Edit(/$state/*/workspaces/**)", s"Read(/$state/*/workspaces/**)") &&
        state.toString.startsWith("/") && assets.claudeWorkspaceRules(root, "state") == assets.claudeWorkspaceRules(root, state.toString))
      // A state root reached through a symbolic link is named as written and as it resolves; one no rule can name is refused.
      val real = Files.createDirectory(root.resolve("real-state"))
      val link = Files.createSymbolicLink(root.resolve("linked-state"), real)
      assert(assets.claudeWorkspaceRules(root, link.toString) == List(link, real.toRealPath()).flatMap(path => List(s"Edit(/$path/*/workspaces/**)", s"Read(/$path/*/workspaces/**)")))
      List("state (old)", "state*", "my state", "state[1]").foreach(name => intercept[IllegalArgumentException](assets.claudeWorkspaceRules(root, root.resolve(name).toString)))
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
      val profiles = Harness.all.toList.map(harness => HarnessSetting(harness, binary.toString, "model", "provider", HarnessUsage.version(harness), Nil, Set.empty))
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
        "permissions" -> Json.obj("allow" -> Json.fromValues(allowed(root, binary).map(Json.fromString))),
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
      assert(installed == merged && merged.hcursor.downField("permissions").get[List[String]]("allow") == Right("Bash(ls)" :: allowed(root, binary)))
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
    "own only the hook command CQ generates, replace a merged file by renaming and refuse the status-line flag where none is installed" in {
      val (root, binary, settings) = project("cq-attached-ownership-")
      val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
      val local = root.resolve(".claude/settings.local.json")
      def installed: Json = parser.parse(Files.readString(local)).fold(throw _, identity)
      def handler(command: String): Json = Json.obj("type" -> Json.fromString("command"), "command" -> Json.fromString(command))
      def group(handlers: Json*): Json = Json.obj("hooks" -> Json.arr(handlers*))
      def cq(event: String): Json = handler(s"$binary hook claude $event")
      // A user command that merely ends like a CQ hook, and a wrapped copy of the CQ hook, are the user's.
      val users = List("/usr/local/bin/log --tag hook claude Stop", s"timeout 5 $binary hook claude Stop", s"sh -c '$binary hook claude Stop'")
      Files.createDirectories(local.getParent)
      Files.writeString(local, Json.obj("hooks" -> Json.obj("Stop" -> Json.arr(group(users.map(handler)*), group(handler(s"  /old/place/cq   hook  claude\tStop "))))).spaces2)
      Files.setPosixFilePermissions(local, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"))
      val inode = Files.getAttribute(local, "unix:ino")
      assets.write(Harness.Claude, root, settings, binary, false, false)
      assert(installed.hcursor.downField("hooks").downField("Stop").focus.contains(Json.arr(group(users.map(handler)*), group(cq("Stop")))))
      // The merged file is replaced as a whole: a reader sees the old content or the new one, never a truncated file.
      assert(Files.getAttribute(local, "unix:ino") != inode && Files.getPosixFilePermissions(local) == java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"))
      assert(Using.resource(Files.list(local.getParent))(_.iterator().asScala.map(_.getFileName.toString).toSet) == Set("settings.local.json", "commands"))
      // A statusLine that wraps the CQ command is the user's as well.
      val wrapped = installed.mapObject(_.add("statusLine", handler(s"timeout 2 $binary hook claude StatusLine")))
      Files.writeString(local, wrapped.spaces2)
      val refused = intercept[IllegalArgumentException](assets.write(Harness.Claude, root, settings, binary, false, false))
      assert(refused.getMessage.contains("use --replace-statusline") && installed == wrapped)
      // Codex and Pi have no CQ status line: the flag is refused by name and nothing is written.
      List(Harness.Codex, Harness.Pi).foreach { harness =>
        val (other, executable, file) = project(s"cq-attached-flag-${harness.toString.toLowerCase}-")
        val flag = intercept[IllegalArgumentException](assets.write(harness, other, file, executable, false, true))
        assert(flag.getMessage == s"requirement failed: --replace-statusline applies only to claude: cq configure installs no status line for ${harness.toString.toLowerCase}")
        assert(!Files.exists(other.resolve(".codex")) && !Files.exists(other.resolve(".pi")) && !Files.exists(other.resolve(".agents")))
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
