package cq.server

import cq.api.*
import cq.host.WorkspaceEvidence
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class WorkspaceEvidenceLocal extends AnyWordSpec {
  private def collector = new WorkspaceEvidence(ProjectId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "evidence")

  "Workspace evidence collection (Behavioral Active Blackbox Atomic)" should {
    "omit an unreadable named directory instead of failing the whole collection" in {
      val root = Files.createTempDirectory("cq-evidence")
      val blocked = Files.createDirectory(root.resolve("blocked"))
      try {
        Files.createDirectories(root.resolve(WorkspaceEvidence.EvidenceDirectory))
        Files.writeString(root.resolve(WorkspaceEvidence.EvidenceDirectory).resolve("run.log"), "failing then passing\n")
        Files.writeString(blocked.resolve("hidden.log"), "unreadable\n")
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("---------"))
        val collected = collector.collect(root, List("blocked"))
        assert(collected.retained.files.map(_.path) == List(".work/evidence/run.log"), collected.retained)
        assert(collected.retained.omitted == List("blocked"), collected.retained)
      } finally {
        Files.setPosixFilePermissions(blocked, PosixFilePermissions.fromString("rwx------"))
      }
    }
  }
}
