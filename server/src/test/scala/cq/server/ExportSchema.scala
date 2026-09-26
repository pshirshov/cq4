package cq.server

import java.nio.file.{Files, Path}

object ExportSchema {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Expected schema output path")
    val schemas = new McpSchemas()
    Files.writeString(Path.of(args(0)), io.circe.Json.arr(schemas.tools.map(schemas.advertised)*).noSpaces)
  }
}
