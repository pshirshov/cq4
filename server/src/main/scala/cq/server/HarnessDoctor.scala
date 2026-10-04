package cq.server

import cq.api.*
import cq.host.{BoundedHostCommand, DriverAssets, HostFiles}
import io.circe.{Json, parser}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Duration
import org.tomlj.{Toml, TomlTable}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

final class HarnessDoctor(assets: AttachedAssets, reader: CommandAssetReader) {
  private val MaxBytes = 1024 * 1024
  private def text(path: Path): String = HostFiles.text(path.toRealPath(), MaxBytes)
  private def json(value: String): Json = parser.parse(value).fold(throw _, identity)
  private def toml(value: String): TomlTable = {
    val parsed = Toml.parse(value)
    require(!parsed.hasErrors, "Invalid TOML configuration")
    parsed
  }
  private def table(value: TomlTable, keys: String*): TomlTable = {
    val result = value.getTable(keys.toList.asJava)
    require(result != null, "Required TOML table is missing")
    result
  }
  private def sameMcp(actual: String, expected: String): Boolean = {
    val a = table(toml(actual), "mcp_servers", "cq")
    val e = table(toml(expected), "mcp_servers", "cq")
    a.keySet() == e.keySet() && List("command", "cwd").forall(key => a.getString(key) == e.getString(key)) &&
      List("startup_timeout_sec", "tool_timeout_sec").forall(key => a.getLong(key) == e.getLong(key)) &&
      List("args", "env_vars").forall { key =>
        val aa = a.getArray(key); val ea = e.getArray(key)
        aa != null && aa.size() == ea.size() && (0 until aa.size()).forall(index => aa.getString(index) == ea.getString(index))
      }
  }
  private def check(name: String, current: Boolean, detail: String): InstallationCheck =
    InstallationCheck(name, if (current) InstallationState.Current else InstallationState.Failed, detail)

  def inspect(harness: Harness, directory: Path, settingsFile: Path, executable: Path, readonlyHome: Path,
    harnessConfig: Option[Path], trustReport: Option[Path], environment: Map[String, String]): InstallationReport = {
    require(directory.isAbsolute && directory.normalize() == directory && Files.isDirectory(directory), "Doctor project must be an absolute normalized directory")
    val settings = Try(HostFiles.read(settingsFile.toRealPath(), SupervisorSettings_JsonCodec, MaxBytes))
    val profiles = settings.flatMap(value => Try(value.harnesses.map(SupervisorConfig.profile)))
    val profile = profiles.toOption.flatMap(_.find(_.harness == harness))
    val routesValid = profiles.toOption.exists(values => values.nonEmpty && values.map(_.harness).distinct.size == values.size) && profile.isDefined
    val routeCheck = check("Settings", routesValid, "A unique route must declare a package-verified harness version and valid executable, model, provider and environment names")
    val versionCheck = profile.fold(check("Harness version", false, "No valid harness route")) { route =>
      val immutable = readonlyHome.isAbsolute && Files.isDirectory(readonlyHome) && !Files.isWritable(readonlyHome) &&
        Using.resource(Files.list(readonlyHome))(values => !values.findAny().isPresent)
      val result = if (!immutable) None else Try {
        val isolated = environment.filter((name, _) => Set("PATH", "LANG", "LC_ALL")(name)) ++ Map(
          "CODEX_HOME" -> readonlyHome.toString, "CLAUDE_CONFIG_DIR" -> readonlyHome.toString,
          "PI_CODING_AGENT_DIR" -> readonlyHome.toString, "NO_COLOR" -> "1", "DISABLE_AUTOUPDATER" -> "1")
        new BoundedHostCommand(isolated, Duration.ofSeconds(10), 4096).run(directory, List(route.executable.toString, "--version"))
      }.toOption
      check("Harness version", result.exists(value => value.exit == 0 && value.text.split("[\\s()]+").contains(route.version)),
        s"Expected ${route.version}; public version probe requires an existing immutable empty --readonly-home directory; probe output is withheld")
    }
    val planned = Try(assets.plan(harness, directory, directory, settingsFile, executable, true, harness == Harness.Claude))
    val assetChecks = planned.toOption.fold(List(check("Assets", false, "Cannot derive assets: check settings and configuration syntax; contents are withheld"))) { values =>
      values.map { asset =>
        val current = reader.read(directory.resolve(asset.path), MaxBytes + 1) match {
          case CommandFile.Content(bytes) if bytes.length <= MaxBytes => Try {
            val actual = new String(bytes, UTF_8)
            if (asset.path.toString.endsWith(".json")) json(actual) == json(asset.body)
            else if (asset.path.toString.endsWith(".toml")) sameMcp(actual, asset.body)
            else actual == asset.body
          }.getOrElse(false)
          case _ => false
        }
        check(asset.path.toString, current, "Package-generated content must match; unrelated JSON entries and TOML tables are preserved; symlinks are accepted")
      }
    }
    val trust = harness match {
      case Harness.Claude =>
        val accepted = Try {
          val global = json(text(harnessConfig.getOrElse(throw new IllegalArgumentException("Claude trust configuration required"))))
          val local = json(text(directory.resolve(".claude/settings.local.json")))
          global.hcursor.downField("projects").downField(directory.toString).get[Boolean]("hasTrustDialogAccepted").contains(true) &&
            !global.hcursor.get[Boolean]("disableAllHooks").contains(true) && !local.hcursor.get[Boolean]("disableAllHooks").contains(true)
        }.getOrElse(false)
        check("Hook trust", accepted, "Claude project trust must be accepted in --harness-config; hooks must not be disabled")
      case Harness.Codex =>
        val accepted = Try {
          val config = toml(text(harnessConfig.getOrElse(throw new IllegalArgumentException("Codex trust configuration required"))))
          val project = table(config, "projects", directory.toString)
          val features = Option(config.getTable("features"))
          val report = json(text(trustReport.getOrElse(throw new IllegalArgumentException("Codex hook inspection report required"))))
          val hooksFile = directory.resolve(".codex/hooks.json")
          val hash = MessageDigest.getInstance("SHA-256").digest(text(hooksFile).getBytes(UTF_8)).map(byte => f"${byte & 255}%02x").mkString
          val cursor = report.hcursor
          val entries = cursor.downField("listing").get[List[Json]]("data").toOption.get
          val entry = entries.find(_.hcursor.get[String]("cwd").contains(directory.toString)).get
          val hooks = entry.hcursor.get[List[Json]]("hooks").toOption.get
          project.getString("trust_level") == "trusted" && !features.exists(value => Option(value.getBoolean("hooks")).contains(java.lang.Boolean.FALSE)) &&
            cursor.get[String]("version").toOption == profile.map(_.version) && cursor.get[String]("project").contains(directory.toString) &&
            cursor.get[String]("sha256").contains(hash) && entry.hcursor.get[List[Json]]("errors").contains(Nil) &&
            List(DriverOrigin.UserPromptSubmit, DriverOrigin.Stop).forall { origin =>
              val matches = hooks.filter(value => value.hcursor.get[String]("command").contains(DriverAssets.hookCommand(executable, harness, origin)))
              matches.size == 1 && {
                val value = matches.head.hcursor
                val state = table(config, "hooks", "state", value.get[String]("key").toOption.get)
                value.get[String]("sourcePath").contains(hooksFile.toString) && value.get[String]("currentHash").toOption.contains(state.getString("trusted_hash")) &&
                  !Option(state.getBoolean("enabled")).contains(java.lang.Boolean.FALSE)
              }
            }
        }.getOrElse(false)
        check("Hook trust", accepted, "Current assets must match a same-version Codex hooks/list report and persisted project/hook approvals; record a fresh report with cq-codex-hook-report")
      case Harness.Pi =>
        val accepted = Try {
          val raw = text(harnessConfig.getOrElse(throw new IllegalArgumentException("Pi trust configuration required")))
          val entries = json(raw.stripPrefix("\uFEFF")).asObject.getOrElse(throw new IllegalArgumentException("Pi trust must be an object"))
          require(entries.values.forall(value => value.isNull || value.asBoolean.isDefined), "Invalid Pi trust decision")
          Iterator.iterate(directory.toRealPath())(_.getParent).takeWhile(_ != null)
            .flatMap(path => entries(path.toString).flatMap(_.asBoolean)).take(1).toList == List(true)
        }.getOrElse(false)
        check("Hook trust", accepted, "Pi project extensions require persisted trust in --harness-config (agent trust.json); the nearest canonical folder decision applies")
    }
    InstallationReport(harness.toString.toLowerCase, routeCheck :: versionCheck :: assetChecks ::: List(trust))
  }
}
