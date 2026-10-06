package cq.server

import cq.api.*
import cq.core.{AgentConfigText, AgentResolution}
import cq.host.{HarnessUsage, HostFiles}
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class AgentsDoctorLocal extends AnyWordSpec {
  private val token = "a" * 32
  // The example of the approved design (I17).
  private val Example = """defaults:
  roles:
    planner:  $harness:@frontier
    worker:   { fallback: [$harness:@standard, pi:@standard] }
    explorer: $harness:@fast
    reviewer: { any: [claude:@standard, pi:@standard], min: 1 }
harnesses:
  claude: { tiers: { frontier: [opus], standard: [sonnet], fast: [haiku] } }
  codex:
    tiers: { frontier: [gpt-6.1-sol?effort=xhigh], standard: [gpt-6.1-sol], fast: [gpt-6-luna?effort=low] }
    roles: { reviewer: { all: [claude:@standard, pi:@standard], min: 1 } }
  pi:
    tiers: { frontier: [openai-codex/gpt-6.1-sol?effort=xhigh], standard: [zai/glm-5.3, xiaomi-token-plan-ams/mimo-v2.6-pro], fast: [xiaomi-token-plan-ams/mimo-v2.6-pro?effort=low] }
"""
  private val Names = List("Project", "Credential", "Configuration", "Server defaults", "Project override", "Role planner", "Role worker", "Role explorer", "Role reviewer")

  /** What the server answers a read with for these two stored texts. */
  private def view(installation: String, project: String): AgentsView = {
    val defaults = AgentConfigText.parse(installation)
    val overrides = AgentConfigText.parse(project)
    def document(text: String, problems: List[AgentProblem]) = AgentsDocument(Revision(if (text.isEmpty) 0 else 1), text, None, problems)
    AgentsView(document(installation, defaults.left.getOrElse(Nil)), document(project, overrides.left.getOrElse(Nil)),
      (for { lower <- defaults; upper <- overrides } yield AgentResolution.assignments(lower, upper)).getOrElse(Nil))
  }

  private final class Fixture extends AutoCloseable {
    val root = Files.createTempDirectory("cq-agents-doctor-").toAbsolutePath
    val binary = root.resolve("harness")
    Files.writeString(binary, "#!/bin/sh\nexit 0\n")
    assert(binary.toFile.setExecutable(true))
    val project = ProjectConfig(ProjectId(UUID.randomUUID()), "http://127.0.0.1:12345", "Agents doctor")
    val projectFile = root.resolve("project.json")
    Files.writeString(projectFile, Wire.encode(ProjectConfig_JsonCodec, project))
    val settingsFile = root.resolve("settings.json")
    def entry(harness: Harness): HarnessSetting = HarnessSetting(harness, binary.toString, "settings-model",
      if (harness == Harness.Claude) "anthropic" else "openai", HarnessUsage.version(harness), Nil, Set.empty)
    def settings(entries: List[HarnessSetting]): Unit = Files.writeString(settingsFile, HostFiles.encode(SupervisorSettings_JsonCodec,
      SupervisorSettings(root.resolve("state").toString, binary.toString, entries, HostLimits(1000, 500, 100, 1000, 65536), Nil, None, None)))
    settings(Harness.all.map(entry))
    var served: Option[AgentsView] = Some(view(Example, ""))
    var asked = List.empty[(ProjectConfig, String)]
    val doctor = new AgentsDoctor(new AgentsReader {
      override def read(config: ProjectConfig, credential: String): AgentsView = {
        asked = asked :+ (config -> credential)
        served.getOrElse(throw new IllegalStateException("fixture-secret"))
      }
    })
    def inspect(harness: Harness): InstallationReport = doctor.inspect(harness, projectFile, settingsFile, Map("CQ_TOKEN" -> token))
    def found(report: InstallationReport, name: String): InstallationCheck = report.checks.find(_.name == name).getOrElse(fail(s"No check $name in ${report.checks.map(_.name)}"))
    def snapshot(): List[(String, List[Byte], Long)] = Using.resource(Files.walk(root)) { paths =>
      paths.iterator().asScala.filter(Files.isRegularFile(_)).map(path => (root.relativize(path).toString, Files.readAllBytes(path).toList, Files.getLastModifiedTime(path).toMillis)).toList.sortBy(_._1)
    }
    override def close(): Unit = Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  }

  "Agents doctor (Behavioral Active Blackbox Good Communication filesystem)" should {
    "report the approved example as current for each governing harness, with the plan and origin of every role, and write nothing" in Using.resource(new Fixture) { f =>
      val before = f.snapshot()
      Harness.all.foreach { harness =>
        val report = f.inspect(harness)
        assert(report.current && report.scope == "agents", report.toString)
        assert(report.checks.map(_.name) == Names ++ List("Session settings", "Settings entry claude", "Settings entry codex", "Settings entry pi", "Providers").filter(name =>
          name != "Settings entry codex" || harness == Harness.Codex))
      }
      assert(f.asked.forall(_ == (f.project -> token)) && f.asked.size == 3 && f.snapshot() == before)
      val codex = f.inspect(Harness.Codex)
      assert(f.found(codex, "Role planner").detail == "codex:gpt-6.1-sol?effort=xhigh (server defaults, defaults.roles)")
      assert(f.found(codex, "Role worker").detail ==
        "{ fallback: [codex:gpt-6.1-sol, pi:zai/glm-5.3, pi:xiaomi-token-plan-ams/mimo-v2.6-pro] } (server defaults, defaults.roles)")
      assert(f.found(codex, "Role reviewer").detail ==
        "{ all: [claude:sonnet, { fallback: [pi:zai/glm-5.3, pi:xiaomi-token-plan-ams/mimo-v2.6-pro] }], min: 1 } (server defaults, harnesses.codex.roles)")
      assert(f.found(codex, "Server defaults").detail == "Revision 1; no problems" && f.found(codex, "Project override").detail == "Not written; revision 0")
      assert(f.found(codex, "Settings entry pi").detail.endsWith("referenced by worker, reviewer"))
      assert(!codex.toString.contains(token))
    }
    "fail on a missing project file, a missing credential and an unreadable configuration without showing why the read failed" in Using.resource(new Fixture) { f =>
      val absent = f.doctor.inspect(Harness.Codex, f.root.resolve("absent.json"), f.settingsFile, Map("CQ_TOKEN" -> token))
      assert(!absent.current && absent.checks.map(_.name) == List("Project", "Credential") && f.found(absent, "Project").state == InstallationState.Failed &&
        f.found(absent, "Project").detail.contains("cq init"))
      val anonymous = f.doctor.inspect(Harness.Codex, f.projectFile, f.settingsFile, Map.empty)
      assert(!anonymous.current && f.found(anonymous, "Credential").state == InstallationState.Failed && f.asked.isEmpty)
      f.served = None
      val unreadable = f.inspect(Harness.Codex)
      assert(!unreadable.current && unreadable.checks.map(_.name) == List("Project", "Credential", "Configuration") &&
        f.found(unreadable, "Configuration").state == InstallationState.Failed && !unreadable.toString.contains("fixture-secret"))
    }
    "print the problems of a layer with their positions and resolve no role while a layer has them" in Using.resource(new Fixture) { f =>
      f.served = Some(view(Example, "defaults:\n  rolls: {}\nharnesses:\n  pi: { tiers: { fast: [glm] } }\n"))
      val report = f.inspect(Harness.Codex)
      val layer = f.found(report, "Project override")
      assert(!report.current && layer.state == InstallationState.Failed && f.found(report, "Server defaults").state == InstallationState.Current)
      assert(layer.detail == "Revision 1; 2 problems (line:column): 2:3: unknown key 'rolls'; 4:25: a pi model is written provider/model", layer.detail)
      AgentRole.all.foreach(role => assert(f.found(report, s"Role ${role.toString.toLowerCase}") ==
        InstallationCheck(s"Role ${role.toString.toLowerCase}", InstallationState.Failed, "Not resolved: a layer of the configuration has problems")))
      assert(report.checks.last.name == "Providers" && !report.checks.exists(_.name.startsWith("Settings entry")))
    }
    "name an unassigned role and an undefined tier in the operator's terms and say what to set" in Using.resource(new Fixture) { f =>
      f.served = Some(view("defaults:\n  roles:\n    planner: claude:opus\n    worker: $harness:@standard\n    reviewer: pi:zai/glm-5.3\n", ""))
      val report = f.inspect(Harness.Codex)
      assert(!report.current && f.found(report, "Role planner").state == InstallationState.Current && f.found(report, "Role reviewer").state == InstallationState.Current)
      assert(f.found(report, "Role explorer") == InstallationCheck("Role explorer", InstallationState.Failed,
        "no layer assigns the explorer role when codex governs; set defaults.roles.explorer or harnesses.codex.roles.explorer in " +
          "the server defaults or the project override (Agent models in the web UI; see `cq help`)"))
      assert(f.found(report, "Role worker") == InstallationCheck("Role worker", InstallationState.Failed,
        "the worker role refers to the standard tier of codex, which no layer defines; set harnesses.codex.tiers.standard in " +
          "the server defaults or the project override (Agent models in the web UI; see `cq help`)"))
    }
    "say which layer and position holds a reference that the governing harness cannot run" in Using.resource(new Fixture) { f =>
      f.served = Some(view(Example, "defaults:\n  roles:\n    explorer: $harness:fast-model\n"))
      val report = f.inspect(Harness.Pi)
      assert(f.found(report, "Role explorer") == InstallationCheck("Role explorer", InstallationState.Failed, "project override 3:15: a pi model is written provider/model"))
      assert(f.found(f.inspect(Harness.Codex), "Role explorer") ==
        InstallationCheck("Role explorer", InstallationState.Current, "codex:fast-model (project override, defaults.roles)"))
    }
    "fail on a referenced harness that the session settings lack, whose executable is absent or whose version is not verified" in Using.resource(new Fixture) { f =>
      f.settings(List(f.entry(Harness.Claude), f.entry(Harness.Codex)))
      val missing = f.inspect(Harness.Codex)
      assert(!missing.current && f.found(missing, "Settings entry pi") == InstallationCheck("Settings entry pi", InstallationState.Failed,
        s"pi is referenced by worker, reviewer but not in the session settings ${f.settingsFile}"))
      assert(f.found(missing, "Settings entry claude").state == InstallationState.Current && f.found(missing, "Role worker").state == InstallationState.Current)
      f.settings(List(f.entry(Harness.Claude), f.entry(Harness.Codex), f.entry(Harness.Pi).copy(executable = f.root.resolve("absent").toString)))
      val absent = f.found(f.inspect(Harness.Codex), "Settings entry pi")
      assert(absent.state == InstallationState.Failed && absent.detail.contains("referenced by worker, reviewer") && absent.detail.contains("missing or not executable"))
      f.settings(List(f.entry(Harness.Claude), f.entry(Harness.Codex).copy(version = "0.0.1"), f.entry(Harness.Pi)))
      val unverified = f.inspect(Harness.Codex)
      assert(f.found(unverified, "Settings entry codex").state == InstallationState.Failed && f.found(unverified, "Settings entry codex").detail.contains("package-verified version"))
      assert(f.found(unverified, "Providers").state == InstallationState.Failed)
      Files.writeString(f.settingsFile, "{ not settings")
      val unreadable = f.inspect(Harness.Codex)
      assert(!unreadable.current && unreadable.checks.last == InstallationCheck("Session settings", InstallationState.Failed,
        s"Session settings ${f.settingsFile} are missing, unreadable or name a harness twice; contents are not shown"))
      f.settings(List(f.entry(Harness.Codex), f.entry(Harness.Codex)))
      assert(f.inspect(Harness.Codex).checks.last.state == InstallationState.Failed)
    }
    "complete a route without a provider from its settings entry, and fail when the entry gives one the harness is not launched for" in Using.resource(new Fixture) { f =>
      assert(f.found(f.inspect(Harness.Codex), "Providers") == InstallationCheck("Providers", InstallationState.Current,
        "A route without a provider takes the provider of its harness's settings entry: claude → anthropic, codex → openai"))
      f.served = Some(view("defaults:\n  roles:\n    planner: pi:zai/glm-5.3\n    worker: pi:zai/glm-5.3\n    explorer: pi:zai/glm-5.3\n    reviewer: codex:openai/gpt-6.1-sol\n", ""))
      assert(f.found(f.inspect(Harness.Pi), "Providers") == InstallationCheck("Providers", InstallationState.Current, "Every resolved route names its provider"))
      f.served = Some(view(Example, ""))
      f.settings(List(f.entry(Harness.Claude).copy(provider = "bedrock"), f.entry(Harness.Codex), f.entry(Harness.Pi)))
      val refused = f.inspect(Harness.Codex)
      assert(!refused.current && f.found(refused, "Providers") == InstallationCheck("Providers", InstallationState.Failed,
        "claude:sonnet would take the provider bedrock of the claude settings entry, and Claude Code is launched for anthropic only"))
      f.settings(List(f.entry(Harness.Codex), f.entry(Harness.Pi)))
      val absent = f.inspect(Harness.Codex)
      assert(f.found(absent, "Providers").state == InstallationState.Failed &&
        f.found(absent, "Providers").detail == "a claude route names no provider, and the session settings have no claude entry to take one from")
    }
    "report a reviewer seat of the governing harness as self-review in the detail of a current check" in Using.resource(new Fixture) { f =>
      val claude = f.inspect(Harness.Claude)
      assert(claude.current && f.found(claude, "Role reviewer") == InstallationCheck("Role reviewer", InstallationState.Current,
        "{ any: [claude:sonnet, { fallback: [pi:zai/glm-5.3, pi:xiaomi-token-plan-ams/mimo-v2.6-pro] }], min: 1 } (server defaults, defaults.roles); " +
          "self-review: seat 1 of 2 can run a model of claude, the governing harness"))
      f.served = Some(view(Example, "harnesses:\n  codex:\n    roles: { reviewer: $harness:@standard }\n"))
      val codex = f.inspect(Harness.Codex)
      assert(codex.current && f.found(codex, "Role reviewer").detail ==
        "codex:gpt-6.1-sol (project override, harnesses.codex.roles); self-review: seat 1 of 1 can run a model of codex, the governing harness")
      assert(!f.found(f.inspect(Harness.Pi), "Role planner").detail.contains("self-review"))
    }
    "read the configuration of the project file's project from its endpoint with the operator credential" in Using.resource(new Fixture) { f =>
      @volatile var requests = List.empty[(String, String)]
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext("/api/call", exchange => {
        requests = requests :+ (exchange.getRequestHeaders.getFirst("Authorization") -> new String(exchange.getRequestBody.readAllBytes(), UTF_8))
        val body = Wire.encode(Result_JsonCodec, Result.Agents(view(Example, ""))).getBytes(UTF_8)
        exchange.sendResponseHeaders(200, body.length)
        Using.resource(exchange.getResponseBody)(_.write(body)); exchange.close()
      })
      server.start()
      try {
        Files.writeString(f.projectFile, Wire.encode(ProjectConfig_JsonCodec, f.project.copy(endpoint = s"http://127.0.0.1:${server.getAddress.getPort}")))
        val report = new AgentsDoctor(new HttpAgentsReader).inspect(Harness.Codex, f.projectFile, f.settingsFile, Map("CQ_TOKEN" -> token))
        assert(report.current, report.toString)
        assert(requests.map(_._1) == List("Bearer " + token))
        assert(Wire.decode(Command_JsonCodec, requests.head._2) == Command.Agents(AgentsInput(f.project.project, AgentsAction.Read())))
      } finally server.stop(0)
    }
  }
}
