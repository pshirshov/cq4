package cq.server

import cq.api.*
import cq.host.{DispatchProjection, HostFiles, WorkspaceReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class DispatchLocal extends AnyWordSpec {
  private def workspace(body: Path => Unit): Unit = {
    val root = Files.createTempDirectory("cq-workspace-reader-")
    try body(root)
    finally Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
  }
  "Workspace reads (Behavioral Active Blackbox Good Communication)" should {
    "paginate directory names and Unicode code points without exposing Git metadata" in workspace { root =>
      Files.writeString(root.resolve(".git"), "private metadata")
      Files.writeString(root.resolve("a.txt"), "a🙂β𐐀z")
      Files.createDirectory(root.resolve("b"))
      Files.createSymbolicLink(root.resolve("c"), root.resolve("a.txt"))
      val reader = new WorkspaceReader
      val first = reader(root, WorkspaceCommand.Entries(".", None, 2)).asInstanceOf[WorkspaceReply.Listed].page
      assert(first.entries == List(WorkspaceEntry("a.txt", WorkspaceEntryKind.File), WorkspaceEntry("b", WorkspaceEntryKind.Directory)))
      assert(first.after.contains("b") && first.hasMore)
      val next = reader(root, WorkspaceCommand.Entries(".", first.after, 2)).asInstanceOf[WorkspaceReply.Listed].page
      assert(next.entries == List(WorkspaceEntry("c", WorkspaceEntryKind.Symlink)) && !next.hasMore)
      val text = reader(root, WorkspaceCommand.Read("a.txt", 1, 3)).asInstanceOf[WorkspaceReply.Text].page
      assert(text.text == "🙂β𐐀" && text.offset == 1 && text.next == 4 && text.hasMore)
      assert(reader(root, WorkspaceCommand.Read("a.txt", 5, 1)).asInstanceOf[WorkspaceReply.Text].page.text.isEmpty)
    }
    "reject traversal, Git metadata, symlink traversal, oversized and non-UTF8 content" in workspace { root =>
      val reader = new WorkspaceReader
      Files.writeString(root.resolve(".git"), "metadata")
      Files.createDirectory(root.resolve("sub"))
      Files.createSymbolicLink(root.resolve("linked"), root.resolve("sub"))
      Files.write(root.resolve("large"), Array.fill[Byte](256 * 1024 + 1)(65))
      Files.write(root.resolve("binary"), Array[Byte](0xc3.toByte, 0x28.toByte))
      for (path <- List("../outside", ".git", "sub/../.git", "linked/file", "large", root.resolve(".git").toString))
        intercept[IllegalArgumentException](reader(root, WorkspaceCommand.Read(path, 0, 10)))
      intercept[java.nio.charset.CharacterCodingException](reader(root, WorkspaceCommand.Read("binary", 0, 10)))
      intercept[IllegalArgumentException](reader(root, WorkspaceCommand.Read("binary", 0, 8193)))
      intercept[IllegalArgumentException](reader(root, WorkspaceCommand.Entries(".", None, 201)))
      intercept[IllegalArgumentException](reader(root, WorkspaceCommand.Read("binary", -1, 10)))
    }
  }
  "Concurrent child admission (Behavioral Active Blackbox Atomic)" should {
    "D83: admit up to four active children with disjoint members and refuse overlapping or excess starts" in {
      val project = ProjectId(UUID.randomUUID())
      def request(numbers: Long*): DispatchRequest = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Probe), Harness.Codex,
        numbers.toList.map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 1000, 300, 2000, 262144))
      def conflict(active: List[DispatchRequest], next: DispatchRequest): String =
        intercept[cq.core.DomainFailure](DispatchController.admissible(active, next)).fault match {
          case Fault.Conflict(message) => message
          case other => fail(s"Expected a conflict, observed $other")
        }
      val active = List(request(1), request(2, 3), request(4))
      DispatchController.admissible(Nil, request(1))
      DispatchController.admissible(active, request(5))
      assert(conflict(active, request(3, 6)).contains("T3"))
      assert(conflict(active :+ request(5), request(6)).contains(DispatchController.MaxActiveChildren.toString))
      assert(DispatchController.MaxActiveChildren == 4)
    }
  }
  "Compact dispatch projection (Behavioral Active Blackbox Atomic)" should {
    "bound large cohort narratives and preserve validation and member outcomes" in {
      val project = ProjectId(UUID.randomUUID())
      val members = (1L to 16L).map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))).toList
      val attempt = AttemptId(UUID.randomUUID())
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex,
        members, Nil, Nil, None, Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 1000, 300, 2000, 262144))
      val initial = DispatchStatus(request.request, attempt, DispatchPhase.Running, Some(JobPhase.Settled), members.map(_.id),
        DispatchProjection.EmptyCounts, ChildNext.Wait, None, None, None, false, true, None, None)
      val handle = ArtifactId(UUID.randomUUID())
      val report = ChildReport.Work(members.map(value => WorkMember(value.id, WorkDisposition.Blocked, "🙂" * 4000, Nil)))
      val result = ChildResult(attempt, request, GitCommit("a" * 40), None, report, Nil, RetainedEvidence(Nil, Nil))
      val projected = DispatchProjection.completed(initial, result, handle)
      assert(projected.counts.blocked == 16 && projected.next == ChildNext.ResolveBlocker && projected.result.contains(handle))
      assert(projected.blocker.contains("🙂" * 300) && projected.detailsOmitted && projected.usageDelivered)
      assert(HostFiles.encode(DispatchStatus_JsonCodec, projected).getBytes(UTF_8).length < 12 * 1024)
      val checked = DispatchProjection.completed(initial, result.copy(validation = List(ValidationEvidence("test", ValidationState.Failed, handle))), handle)
      assert(checked.counts.validationFailed == 1 && checked.next == ChildNext.Revise && checked.blocker.contains("Host check test: Failed"))
    }
    "report a retained workspace with its directory and a removed workspace without one" in {
      val spec = WorkspaceSpec(ProjectId(UUID.randomUUID()), SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/repo", GitCommit("a" * 40))
      val record = WorkspaceRecord(spec, "/workspaces/" + spec.attempt.value + "/tree", WorkspaceAdmission.Open, None, None)
      assert(DispatchProjection.workspace(record) == WorkspaceState(WorkspaceAdmission.Open, Some(record.directory)))
      val quarantined = record.copy(admission = WorkspaceAdmission.Quarantined, quarantineReason = Some("Child result failed"))
      assert(DispatchProjection.workspace(quarantined) == WorkspaceState(WorkspaceAdmission.Quarantined, Some(record.directory)))
      assert(DispatchProjection.workspace(record.copy(admission = WorkspaceAdmission.Removed)) == WorkspaceState(WorkspaceAdmission.Removed, None))
    }
  }
}
