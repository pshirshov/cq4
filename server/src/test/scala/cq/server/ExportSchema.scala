package cq.server

import cq.api.*
import cq.host.*
import io.circe.{Json, parser}
import java.net.URI
import java.nio.file.{Files, Path}
import java.util.UUID

object ExportSchema {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Expected schema output path")
    val schemas = new McpSchemas()
    Files.writeString(Path.of(args(0)), io.circe.Json.arr(schemas.tools.map(schemas.advertised)*).noSpaces)
    val reports = io.circe.Json.obj(
      "Evidence" -> schemas.childReport(DispatchWork.Explorer(ExplorerMode.Investigate)),
      "Plan" -> schemas.childReport(DispatchWork.Planner()),
      "Work" -> schemas.childReport(DispatchWork.Worker(WorkerMode.Implement)),
      "Review" -> schemas.childReport(DispatchWork.Reviewer(ReviewerMode.Candidate)),
    )
    Files.writeString(Path.of(args(0)).resolveSibling("child-report-schemas.json"), reports.noSpaces)
    val codexReports = reports.asObject.get.toList.map { case (tag, schema) =>
      val profile = HarnessProfile(Harness.Codex, Path.of("/fixture/codex"), "fixture", "openai", HarnessUsage.version(Harness.Codex), Nil, Set.empty)
      val invocation = HarnessInvocation(Role.Planner, AttemptId(UUID.randomUUID()), "Fixture", schema, Nil, Path.of("/fixture/assets"))
      val launch = new CodexAdapter().launch(profile, invocation, Map("HOME" -> "/fixture/home", "PATH" -> "/fixture/bin"))
      tag -> parser.parse(launch.assets.find(_.name == "result-schema.json").get.body).fold(throw _, identity)
    }
    Files.writeString(Path.of(args(0)).resolveSibling("codex-report-schemas.json"), Json.obj(codexReports*).noSpaces)
    val guides = List(Role.Governor, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer).map { role =>
      val invocation = HarnessInvocation(role, AttemptId(UUID.randomUUID()), (if (role == Role.Governor) SupervisorProgram.Instructions else "Role instructions"), Json.obj(), List(
        HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("fixture", 1)),
        HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("fixture", 1))), Path.of("/fixture/assets"))
      assert(schemas.nativeInvocation(Harness.Claude, invocation) == invocation && schemas.nativeInvocation(Harness.Pi, invocation) == invocation)
      val prepared = schemas.nativeInvocation(Harness.Codex, invocation)
      assert(prepared.system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768)
      println(s"$role native instructions: ${prepared.system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length} UTF-8 bytes")
      role.toString -> parser.parse(prepared.system.linesIterator.toList.last).fold(throw _, identity)
    }
    Files.writeString(Path.of(args(0)).resolveSibling("native-guides.json"), Json.obj(guides*).noSpaces)
  }
}
