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
    Files.writeString(Path.of(args(0)).resolveSibling("child-report-schemas.json"), io.circe.Json.obj(
      "Work" -> schemas.childReport(DispatchWork.Worker(WorkerMode.Implement)),
      "Review" -> schemas.childReport(DispatchWork.Reviewer()),
    ).noSpaces)
    val guides = List(Role.Governor, Role.Worker, Role.Reviewer).map { role =>
      val invocation = HarnessInvocation(role, AttemptId(UUID.randomUUID()), "Role instructions", Json.obj(), List(
        HarnessMcp(McpTarget.Domain, URI.create("http://127.0.0.1:1234/mcp"), AccessToken("fixture", 1)),
        HarnessMcp(McpTarget.Local, URI.create("http://127.0.0.1:1235/mcp"), AccessToken("fixture", 1))), Path.of("/fixture/assets"))
      assert(schemas.nativeInvocation(Harness.Claude, invocation) == invocation && schemas.nativeInvocation(Harness.Pi, invocation) == invocation)
      val prepared = schemas.nativeInvocation(Harness.Codex, invocation)
      assert(prepared.system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 32768)
      role.toString -> parser.parse(prepared.system.linesIterator.toList.last).fold(throw _, identity)
    }
    Files.writeString(Path.of(args(0)).resolveSibling("native-guides.json"), Json.obj(guides*).noSpaces)
  }
}
