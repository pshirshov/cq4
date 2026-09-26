package cq.server

import java.nio.file.{Files, Path}

object ExportSchema {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Expected schema output path")
    Files.writeString(Path.of(args(0)), new McpSchemas().probe.noSpaces)
  }
}
