package cq.server

import cq.api.*
import cq.host.{HarnessUsage, HostFiles, WorkflowAssets}
import io.circe.{Json, parser}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class HarnessDoctorLocal extends AnyWordSpec {
  private final class Fixture extends AutoCloseable {
    val root = Files.createTempDirectory("cq-harness-doctor-").toAbsolutePath
    val binary = root.resolve("cq")
    Files.writeString(binary, "#!/bin/sh\n[ -z \"$CQ_TOKEN$OPENAI_API_KEY$ANTHROPIC_API_KEY\" ] || exit 1\necho '" + Harness.all.map(HarnessUsage.version).mkString(" ") + "'\n")
    assert(binary.toFile.setExecutable(true))
    val readonly = Files.createDirectory(root.resolve("readonly"))
    Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("r-xr-xr-x"))
    val settings = root.resolve("settings.json")
    val routes = Harness.all.toList.map(h => HarnessSetting(h, binary.toString, "model", "provider", HarnessUsage.version(h), Nil, Set.empty))
    val value = SupervisorSettings(root.resolve("state").toString, binary.toString, routes, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)
    Files.writeString(settings, HostFiles.encode(SupervisorSettings_JsonCodec, value))
    val assets = new AttachedAssets(new McpSchemas, new WorkflowAssets)
    val doctor = new HarnessDoctor(assets, new FileCommandAssetReader)
    def install(harness: Harness): Unit = { assets.write(harness, root, settings, binary, false, false); () }
    def inspect(harness: Harness, config: Option[Path], report: Option[Path]): InstallationReport =
      doctor.inspect(harness, root, settings, binary, readonly, config, report, Map("PATH" -> "/bin", "CQ_TOKEN" -> "fixture-secret", "OPENAI_API_KEY" -> "fixture-secret"))
    def snapshot(): List[(String, List[Byte], Long)] = Using.resource(Files.walk(root)) { paths =>
      paths.iterator().asScala.filter(Files.isRegularFile(_)).map(path => (root.relativize(path).toString, Files.readAllBytes(path).toList, Files.getLastModifiedTime(path).toMillis)).toList.sortBy(_._1)
    }
    override def close(): Unit = {
      Files.setPosixFilePermissions(readonly, PosixFilePermissions.fromString("rwx------"))
      Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
    }
  }
  "Harness doctor (Behavioral Active Blackbox Good Communication filesystem and version process)" should {
    "verify Pi assets and pins without writes or forwarded credentials and detect missing or changed content" in Using.resource(new Fixture) { f =>
      f.install(Harness.Pi)
      val config = f.root.resolve("pi-trust.json")
      Files.writeString(config, Json.obj(f.root.toRealPath().toString -> Json.True).noSpaces)
      val before = f.snapshot()
      assert(f.inspect(Harness.Pi, Some(config), None).current)
      assert(f.snapshot() == before && Using.resource(Files.list(f.readonly))(_.count()) == 0)
      val extension = f.root.resolve(".pi/extensions/cq-host.js")
      Files.writeString(extension, "changed")
      val mismatch = f.inspect(Harness.Pi, None, None)
      assert(!mismatch.current && mismatch.checks.exists(c => c.name == ".pi/extensions/cq-host.js" && c.state == InstallationState.Failed))
      Files.delete(extension)
      assert(!f.inspect(Harness.Pi, None, None).current)
    }
    "say which trust input was not given, and name the hook report helper as each package layout ships it" in Using.resource(new Fixture) { f =>
      def trust(harness: Harness, config: Option[Path], report: Option[Path]): InstallationCheck = {
        f.install(harness)
        f.inspect(harness, config, report).checks.find(_.name == "Hook trust").get
      }
      val supplied = f.root.resolve("supplied")
      Files.writeString(supplied, "")
      // A check that fails for want of an option says so: its other reasons are a trust decision the file does not hold.
      Harness.all.foreach(harness => assert(trust(harness, None, None).state == InstallationState.Failed && trust(harness, None, None).detail.endsWith(
        if (harness == Harness.Codex) "; not given: --harness-config, --trust-report" else "; not given: --harness-config"), harness.toString))
      assert(trust(Harness.Codex, Some(supplied), None).detail.endsWith("; not given: --trust-report"))
      Harness.all.foreach(harness => assert(!trust(harness, Some(supplied), Some(supplied)).detail.contains("not given"), harness.toString))
      // The Nix package installs the helper on the path; an archive has it as a script among its examples.
      assert(trust(Harness.Codex, Some(supplied), Some(supplied)).detail.contains("record a fresh report with cq-codex-hook-report (in an archive: python3 examples/codex-hook-report.py)"))
      // Pi's --approve decides for one process and saves nothing.
      assert(trust(Harness.Pi, Some(supplied), Some(supplied)).detail.contains("a launch with --approve saves none; /trust in Pi saves one"))
    }
    "refuse untrusted Pi projects and honor the nearest canonical folder decision without writes" in Using.resource(new Fixture) { f =>
      f.install(Harness.Pi)
      assert(!f.inspect(Harness.Pi, None, None).current)
      val config = f.root.resolve("pi-trust.json")
      Files.writeString(config, Json.obj(f.root.getParent.toString -> Json.True).noSpaces)
      val before = f.snapshot()
      assert(f.inspect(Harness.Pi, Some(config), None).current)
      assert(f.snapshot() == before)
      Files.writeString(config, Json.obj(f.root.getParent.toString -> Json.True, f.root.toRealPath().toString -> Json.False).noSpaces)
      assert(!f.inspect(Harness.Pi, Some(config), None).current)
      Files.writeString(config, Json.obj(f.root.getParent.toString -> Json.True, f.root.toRealPath().toString -> Json.Null).noSpaces)
      assert(f.inspect(Harness.Pi, Some(config), None).current)
      Files.writeString(config, Json.obj(f.root.toString -> Json.True, "/unrelated" -> Json.fromString("invalid")).noSpaces)
      assert(!f.inspect(Harness.Pi, Some(config), None).current)
    }
    "require Claude project approval and detect disabled hooks while preserving unrelated configuration" in Using.resource(new Fixture) { f =>
      f.install(Harness.Claude)
      val config = f.root.resolve("claude-global.json")
      def approval(accepted: Boolean): Unit = Files.writeString(config, Json.obj("projects" -> Json.obj(f.root.toString -> Json.obj("hasTrustDialogAccepted" -> Json.fromBoolean(accepted)))).noSpaces)
      approval(false)
      assert(!f.inspect(Harness.Claude, Some(config), None).current)
      approval(true)
      val local = f.root.resolve(".claude/settings.local.json")
      val json = parser.parse(Files.readString(local)).toOption.get
      Files.writeString(local, json.deepMerge(Json.obj("unrelated" -> Json.fromString("keep"))).noSpaces)
      assert(f.inspect(Harness.Claude, Some(config), None).current)
      Files.writeString(local, json.deepMerge(Json.obj("disableAllHooks" -> Json.True)).noSpaces)
      assert(!f.inspect(Harness.Claude, Some(config), None).current)
      // An integration an earlier package generated starts the host without its executable, and such a host refuses to start.
      val mcp = f.root.resolve(".mcp.json")
      val current = parser.parse(Files.readString(mcp)).toOption.get
      val args = current.hcursor.downField("mcpServers").downField("cq").get[List[String]]("args").toOption.get
      assert(args.takeRight(2) == List("--executable", f.binary.toString))
      Files.writeString(mcp, current.deepMerge(Json.obj("mcpServers" -> Json.obj("cq" -> Json.obj("args" -> Json.fromValues(args.dropRight(2).map(Json.fromString)))))).noSpaces)
      val stale = f.inspect(Harness.Claude, Some(config), None)
      assert(!stale.current && stale.checks.exists(check => check.name == ".mcp.json" && check.state == InstallationState.Failed))
      Files.writeString(mcp, current.noSpaces)
      // Without the permission for the waiter, a session would be asked before each background wait.
      val workspaces = s"/${f.root.resolve("state")}/*/workspaces/**"
      assert(json.hcursor.downField("permissions").get[List[String]]("allow") == Right(List(s"Bash(${f.binary} wait)", s"Edit($workspaces)", s"Read($workspaces)")))
      Files.writeString(local, json.mapObject(_.remove("permissions")).noSpaces)
      val unapproved = f.inspect(Harness.Claude, Some(config), None)
      assert(!unapproved.current && unapproved.checks.exists(check => check.name == ".claude/settings.local.json" && check.state == InstallationState.Failed))
      // I30: without the rules for the workspaces of its sessions, a Governor that works itself would be asked before each edit there.
      List(s"Edit($workspaces)", s"Read($workspaces)").foreach { rule =>
        Files.writeString(local, json.deepMerge(Json.obj("permissions" -> Json.obj("allow" -> Json.fromValues(
          json.hcursor.downField("permissions").get[List[String]]("allow").toOption.get.filterNot(_ == rule).map(Json.fromString))))).noSpaces)
        val asked = f.inspect(Harness.Claude, Some(config), None)
        assert(!asked.current && asked.checks.exists(check => check.name == ".claude/settings.local.json" && check.state == InstallationState.Failed), rule)
      }
      Files.writeString(local, json.noSpaces)
      assert(f.inspect(Harness.Claude, Some(config), None).current)
    }
    "inspect declarative configuration and approval symlinks without changing them" in Using.resource(new Fixture) { f =>
      f.install(Harness.Claude)
      val config = f.root.resolve("claude-global.json")
      Files.writeString(config, Json.obj("projects" -> Json.obj(f.root.toString -> Json.obj("hasTrustDialogAccepted" -> Json.True))).noSpaces)
      val links = List(f.root.resolve(".mcp.json"), f.root.resolve(".claude/settings.local.json"), f.settings, config)
      links.foreach { path =>
        val target = path.resolveSibling(path.getFileName.toString + ".source")
        Files.move(path, target)
        Files.createSymbolicLink(path, target)
      }
      val before = f.snapshot()
      assert(f.inspect(Harness.Claude, Some(config), None).current)
      assert(f.snapshot() == before && links.forall(Files.isSymbolicLink(_)))
      intercept[IllegalArgumentException](f.assets.write(Harness.Claude, f.root, f.settings, f.binary, true, true))
      assert(f.snapshot() == before)
    }
    "verify Codex MCP semantics and report-bound persisted approvals, refusing stale reports and revoked trust" in Using.resource(new Fixture) { f =>
      f.install(Harness.Codex)
      val projectConfig = f.root.resolve(".codex/config.toml")
      Files.writeString(projectConfig, Files.readString(projectConfig) + "\n[unrelated]\nsetting = true\n")
      val config = f.root.resolve("codex-global.toml")
      val report = f.root.resolve("hook-report.json")
      val hooks = f.root.resolve(".codex/hooks.json")
      val hash = "sha256:" + "a" * 64
      val origins = List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop)
      val metadata = origins.map { origin =>
        val key = hooks.toString + ":" + (if (origin == DriverOrigin.Stop) "stop" else "user_prompt_submit") + ":0:0"
        Json.obj("key" -> Json.fromString(key), "sourcePath" -> Json.fromString(hooks.toString),
          "command" -> Json.fromString(cq.host.DriverAssets.hookCommand(f.binary, Harness.Codex, origin)), "currentHash" -> Json.fromString(hash))
      }
      val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(hooks)).map(b => f"${b & 255}%02x").mkString
      Files.writeString(report, Json.obj("version" -> Json.fromString(HarnessUsage.version(Harness.Codex)), "project" -> Json.fromString(f.root.toString), "sha256" -> Json.fromString(sha),
        "listing" -> Json.obj("data" -> Json.arr(Json.obj("cwd" -> Json.fromString(f.root.toString), "errors" -> Json.arr(), "hooks" -> Json.fromValues(metadata))))).noSpaces)
      val trust = "[projects." + Json.fromString(f.root.toString).noSpaces + "]\ntrust_level = \"trusted\"\n" + metadata.map { value =>
        "[hooks.state." + value.hcursor.get[String]("key").map(Json.fromString).toOption.get.noSpaces + "]\ntrusted_hash = \"" + hash + "\"\n"
      }.mkString
      Files.writeString(config, trust)
      val before = f.snapshot()
      assert(f.inspect(Harness.Codex, Some(config), Some(report)).current)
      assert(f.snapshot() == before)
      Files.writeString(config, trust + "\n[features]\nhooks = false\n")
      assert(!f.inspect(Harness.Codex, Some(config), Some(report)).current)
      // A Codex session runs no command to wait, so no rule of its shell is expected.
      Files.writeString(config, trust)
      val unruled = f.inspect(Harness.Codex, Some(config), Some(report))
      assert(unruled.current && !unruled.checks.exists(_.name.contains("rules")) && !Files.exists(f.root.resolve(".codex/rules")))
      Files.writeString(config, trust.replace("trusted_hash", "revoked_hash"))
      assert(!f.inspect(Harness.Codex, Some(config), Some(report)).current)
      Files.writeString(config, trust)
      Files.writeString(hooks, Files.readString(hooks) + "\n")
      assert(!f.inspect(Harness.Codex, Some(config), Some(report)).current)
      assert(!f.inspect(Harness.Codex, Some(config), None).current)
    }
    "refuse unverified routes, writable version-probe homes and malformed configuration without disclosing contents" in Using.resource(new Fixture) { f =>
      f.install(Harness.Pi)
      Files.setPosixFilePermissions(f.readonly, PosixFilePermissions.fromString("rwx------"))
      assert(!f.inspect(Harness.Pi, None, None).current)
      Files.writeString(f.settings, HostFiles.encode(SupervisorSettings_JsonCodec, f.value.copy(harnesses = f.routes.map(_.copy(version = "unverified")))))
      assert(!f.inspect(Harness.Pi, None, None).current)
      Files.writeString(f.settings, "fixture-secret")
      val report = f.inspect(Harness.Pi, None, None)
      assert(!report.current && !report.toString.contains("fixture-secret"))
    }
    "export declarative assets for the actual project directory without touching that project and reject unverified build routes" in Using.resource(new Fixture) { f =>
      val destination = Files.createDirectory(f.root.resolve("export"))
      val project = f.root.resolve("absent-consumer")
      f.assets.exportAssets(Harness.Codex, destination, project, f.settings, f.binary)
      assert(Files.readString(destination.resolve(".codex/config.toml")).contains(project.toString))
      assert(!Files.exists(project))
      Files.writeString(f.settings, HostFiles.encode(SupervisorSettings_JsonCodec, f.value.copy(harnesses = f.routes.map(_.copy(version = "unverified")))))
      intercept[IllegalArgumentException](f.assets.exportAssets(Harness.Pi, destination, project, f.settings, f.binary))
    }
  }
}
