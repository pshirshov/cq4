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
    "retain explicitly named evidence before incidental files exhaust the shared budget" in {
      val root = Files.createTempDirectory("cq-evidence-priority")
      val directory = Files.createDirectories(root.resolve(WorkspaceEvidence.EvidenceDirectory))
      (1 to WorkspaceEvidence.MaxFiles + 2).foreach(index => Files.writeString(directory.resolve(f"incidental-$index%03d.log"), "incidental"))
      val required = directory.resolve("zzz-required.log")
      Files.writeString(required, "required completion proof")
      val path = root.relativize(required).toString
      val collected = collector.collect(root, List(path, path))
      assert(collected.retained.files.head.path == path, collected.retained)
      assert(collected.retained.files.count(_.path == path) == 1, collected.retained)
      assert(collected.retained.files.size == WorkspaceEvidence.MaxFiles, collected.retained)
      assert(collected.retained.omitted.nonEmpty, collected.retained)
      assert(collected.uploads.head.body == "required completion proof")
    }
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
