package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.{DispatchProjection, HostFiles, ServerUnavailable, WorkspaceReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant, LocalDateTime, OffsetDateTime}
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import zio.{Runtime, Scheduler, Trace, UIO, Unsafe, ZIO}
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
  "Claim renewal (Behavioral Active Blackbox; scripted server and clock Communication)" should {
    val policy = ClaimRenewal.Policy(Duration.ofMillis(1500), Duration.ofMillis(100), Duration.ofMillis(400))
    // D124: time moves only by the loop's own sleeps, so the renewal at call n happens n ticks after the start whatever the machine does.
    final class TickClock extends zio.Clock {
      val nanos = new AtomicLong(0)
      override def nanoTime(implicit trace: Trace): UIO[Long] = ZIO.succeed(nanos.get())
      override def sleep(duration: => zio.Duration)(implicit trace: Trace): UIO[Unit] = ZIO.succeed(nanos.addAndGet(duration.toNanos)).unit
      override def currentTime(unit: => TimeUnit)(implicit trace: Trace): UIO[Long] = zio.Clock.ClockLive.currentTime(unit)
      override def currentTime(unit: => ChronoUnit)(implicit trace: Trace, d: DummyImplicit): UIO[Long] = zio.Clock.ClockLive.currentTime(unit)
      override def currentDateTime(implicit trace: Trace): UIO[OffsetDateTime] = zio.Clock.ClockLive.currentDateTime
      override def instant(implicit trace: Trace): UIO[Instant] = zio.Clock.ClockLive.instant
      override def javaClock(implicit trace: Trace): UIO[java.time.Clock] = zio.Clock.ClockLive.javaClock
      override def localDateTime(implicit trace: Trace): UIO[LocalDateTime] = zio.Clock.ClockLive.localDateTime
      override def scheduler(implicit trace: Trace): UIO[Scheduler] = zio.Clock.ClockLive.scheduler
    }
    def maintained(renew: Int => Unit): (Throwable, Duration, Int) = {
      val calls = new AtomicInteger(0)
      val clock = new TickClock
      val failure = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(ZIO.withClock(clock)(new ClaimRenewal(policy, logstage.IzLogger.NullLogger).maintain(0L, ZIO.attemptBlocking(renew(calls.incrementAndGet()))).flip)
          .timeoutFail(new IllegalStateException("Renewal still running"))(zio.Duration.fromSeconds(30))).getOrThrowFiberFailure()
      }
      (failure, Duration.ofNanos(clock.nanos.get()), calls.get())
    }
    def unanswered: Nothing = throw new ServerUnavailable("HTTP response deadline exceeded", new java.util.concurrent.TimeoutException)
    "retry renewals the server left unanswered at the tick and keep renewing once it answers again" in {
      val end = DomainFailure(Fault.StaleFence("Scripted end"))
      val (failure, elapsed, calls) = maintained(call => if ((2 to 9).contains(call)) unanswered else if (call == 13) throw end)
      // Eight unanswered renewals in a row fit the lease; the loop renews on after them and is ended only by the scripted refusal.
      assert(failure == end && calls == 13 && elapsed.toMillis == 1300, s"$failure after $calls calls and $elapsed")
    }
    "stop at once when the server refuses a renewal" in {
      val (failure, elapsed, calls) = maintained(call => if (call == 2) throw DomainFailure(Fault.StaleFence("Claim released")))
      assert(failure == DomainFailure(Fault.StaleFence("Claim released")) && calls == 2 && elapsed.toMillis == 200, s"$failure after $calls calls and $elapsed")
    }
    "stop with the reason when renewals stay unanswered until the lease last obtained is about to expire" in {
      val (failure, elapsed, calls) = maintained(call => if (call > 3) unanswered)
      // The third renewal obtained the last lease at 300 ms; the renewal at 1300 ms is the first whose next tick leaves no more than the margin of it.
      assert(failure.getMessage == "Claim renewal unanswered 10 times over 900 ms; its lease is about to expire: HTTP response deadline exceeded" &&
        calls == 13 && elapsed.toMillis == 1300, s"${failure.getMessage} after $calls calls and $elapsed")
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
    "I17: admit the attempts of one unit on the same members, count each against the bound, and refuse seats that do not fit together" in {
      val project = ProjectId(UUID.randomUUID())
      def request(id: RequestId, harness: Harness, numbers: Long*): DispatchRequest = DispatchRequest(id, DispatchWork.Reviewer(ReviewerMode.Candidate), harness,
        numbers.toList.map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))), Nil, Nil, Some(ArtifactId(UUID.randomUUID())),
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 1000, 300, 2000, 262144))
      def conflict(operation: => Unit): String = intercept[cq.core.DomainFailure](operation).fault match {
        case Fault.Conflict(message) => message
        case other => fail(s"Expected a conflict, observed $other")
      }
      val unit = RequestId(UUID.randomUUID())
      val seats = List(request(unit, Harness.Claude, 1, 2), request(unit, Harness.Codex, 1, 2), request(unit, Harness.Pi, 1, 2))
      // A further seat or candidate of the same unit shares its members; another unit on one of them is refused as before.
      DispatchController.admissible(seats, request(unit, Harness.Codex, 1, 2))
      assert(conflict(DispatchController.admissible(seats, request(RequestId(UUID.randomUUID()), Harness.Codex, 2))).contains("An active child already covers T2"))
      // The unit's own attempts fill the bound like any others.
      assert(conflict(DispatchController.admissible(seats :+ request(unit, Harness.Codex, 1, 2), request(unit, Harness.Claude, 1, 2))).contains("at most 4 active children"))
      assert(conflict(DispatchController.admissible(seats :+ request(RequestId(UUID.randomUUID()), Harness.Codex, 9), request(unit, Harness.Claude, 1, 2))).contains("at most 4 active children"))
      // The seats a unit starts together fit together or not at all.
      DispatchController.capacity(0, 4)
      DispatchController.capacity(2, 2)
      assert(conflict(DispatchController.capacity(2, 3)).contains("at most 4 active children") && conflict(DispatchController.capacity(0, 5)).contains("at most 4 active children"))
    }
  }
  "Admission beside the governing session's own work (Behavioral Active Blackbox Atomic)" should {
    "I30: hold the members of an open workspace as any unit does (D83) and no child slot, whichever side starts" in {
      val project = ProjectId(UUID.randomUUID())
      def request(numbers: Long*): DispatchRequest = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex,
        numbers.toList.map(number => ItemRevision(ItemId(project, Ledger.Tasks, number), Revision(1))), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 1000, 300, 2000, 262144))
      def conflict(operation: => Unit): String = intercept[cq.core.DomainFailure](operation).fault match {
        case Fault.Conflict(message) => message
        case other => fail(s"Expected a conflict, observed $other")
      }
      val children = List(1L, 2L, 3L, 4L).map(number => DispatchUnits.Standing(request(number), DispatchUnits.slots(false, 1), false))
      val workspace = DispatchUnits.Standing(request(5), DispatchUnits.slots(true, 1), true)
      assert(DispatchUnits.slots(true, 1) == 0 && DispatchUnits.slots(false, 3) == 3)
      // A workspace opens although every child slot is taken: no process runs for it.
      DispatchUnits.admissible(children, request(5), 0)
      // An open workspace takes no slot from the children: four of them still start beside it, and a fifth is refused as without it.
      DispatchUnits.admissible(workspace :: children.take(3), request(4), 1)
      assert(conflict(DispatchUnits.admissible(workspace :: children, request(6), 1)).contains("at most 4 active children"))
      // Its members are held against a child and against a second workspace, and a child's against a workspace.
      // What covers them is the caller's own work, which does not end by itself: the refusal says what ends it.
      val own = "The governing session's own open workspace already covers T5; submit or cancel it before starting other work on the same members"
      assert(conflict(DispatchUnits.admissible(List(workspace), request(5, 6), 1)) == own && conflict(DispatchUnits.admissible(List(workspace), request(5), 0)) == own)
      assert(conflict(DispatchUnits.admissible(children, request(2), 0)).contains("An active child already covers T2"))
    }
    "I30: say Editing with next Submit while the session works, and nothing of it once the host works on the attempt again" in {
      val project = ProjectId(UUID.randomUUID())
      val members = List(ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(1)))
      val assignment = Assignment(AssignmentId(UUID.randomUUID()), project, members.map(_.id).toSet, Attribution.Direct, None, None)
      val governing = AttemptId(UUID.randomUUID())
      val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, Some(governing), SessionId(UUID.randomUUID()), Role.Governor, Harness.Claude,
        "unobserved-interactive-provider", "unobserved-interactive-model", DispatchController.OwnWorkCollector, 1, UsagePhase.Work, None)
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Claude, members, Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 1000, 300, 2000, 262144))
      def entry: DispatchExecution = Unsafe.unsafe { implicit unsafe =>
        new DispatchExecution(DispatchTicket(request, assignment, attempt, None, None), Path.of("/unused"),
          zio.Promise.unsafe.make[Throwable, Unit](zio.FiberId.None), zio.Promise.unsafe.make[Nothing, Unit](zio.FiberId.None))
      }
      val open = WorkspaceState(WorkspaceAdmission.Open, Some("/state/session/workspaces/attempt/tree"))
      val editing = entry
      editing.editing(open)
      assert(editing.status.phase == DispatchPhase.Editing && editing.status.next == ChildNext.Submit && editing.status.workspace.contains(open))
      assert(!DispatchController.terminal(DispatchPhase.Editing))
      editing.phase(DispatchPhase.Validating)
      assert(editing.status.phase == DispatchPhase.Validating && editing.status.next == ChildNext.Wait && editing.status.workspace.contains(open))
      val cancelled = entry
      cancelled.editing(open)
      assert(cancelled.requestStop("Cancelled by the governing session") && cancelled.status.phase == DispatchPhase.Stopping && cancelled.status.next == ChildNext.Wait)
      // A workspace that opens after the attempt was stopped is never offered for editing.
      val late = entry
      late.requestStop("Governing harness ended")
      late.editing(open)
      assert(late.status.phase == DispatchPhase.Stopping && late.status.workspace.isEmpty)
      // The ticket of such an attempt is the governing session's own, and a child's is not.
      assert(AttemptSettlement.own(editing.ticket) && !AttemptSettlement.own(editing.ticket.copy(attempt = attempt.copy(role = Role.Worker))) &&
        !AttemptSettlement.own(editing.ticket.copy(attempt = attempt.copy(parent = None))))
    }
  }
  "Compact dispatch projection (Behavioral Active Blackbox Atomic)" should {
    "I33: reply to a selection with its choices as retained, without the limits the request stated" in {
      val context = baboon.runtime.shared.BaboonCodecContext.Default
      val project = ProjectId(UUID.randomUUID())
      val member = ItemRevision(ItemId(project, Ledger.Tasks, 1), Revision(2))
      val limits = HostLimits(3000, 1000, 300, 2000, 262144)
      val choice = CohortChoice(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), List(member), List(member), List(ArtifactId(UUID.randomUUID())),
        Some(ArtifactId(UUID.randomUUID())), limits, Some(UUID.randomUUID()), CohortReason.CompatibleAssessment, Some(member.id))
      val decision = CohortDecision(RequestId(UUID.randomUUID()), ArtifactId(UUID.randomUUID()), CohortCounts(4, 1, 2, 1, 0, 0, 0, 3), List(choice, choice.copy(id = RequestId(UUID.randomUUID()))))
      val retained = CohortDecision_JsonCodec.encode(context, decision)
      val reply = DispatchReply_JsonCodec.encode(context, DispatchReply.Selection(DispatchProjection.offer(decision))).hcursor.downField("Selection").downField("value").focus.get
      // Field for field the retained decision, less the limits of each choice.
      val trimmed = retained.mapObject(_.add("choices", io.circe.Json.fromValues(
        retained.hcursor.get[List[io.circe.Json]]("choices").fold(throw _, identity).map(_.mapObject(_.remove("limits"))))))
      assert(reply == trimmed && !reply.noSpaces.contains("limits") && retained.noSpaces.contains("\"limits\":" + HostLimits_JsonCodec.encode(context, limits).noSpaces))
      assert(reply.noSpaces.length == retained.noSpaces.length - 2 * (",\"limits\":".length + HostLimits_JsonCodec.encode(context, limits).noSpaces.length))
    }
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
      val checked = DispatchProjection.completed(initial, result.copy(validation = List(ValidationEvidence("test", ValidationState.Failed, handle, Nil))), handle)
      assert(checked.counts.validationFailed == 1 && checked.next == ChildNext.Revise && checked.blocker.contains("Host check test: Failed"))
      // I19: a check that passed on a rerun is counted as intermittent and does not block.
      val ready = result.copy(candidate = Some(GitCommit("b" * 40)), report = ChildReport.Work(members.map(value => WorkMember(value.id, WorkDisposition.CandidateReady, "Ready", Nil))))
      val intermittent = DispatchProjection.completed(initial, ready.copy(validation = List(
        ValidationEvidence("flaky", ValidationState.Passed, handle, List(ArtifactId(UUID.randomUUID()))), ValidationEvidence("steady", ValidationState.Passed, handle, Nil))), handle)
      assert(intermittent.counts.validationIntermittent == 1 && intermittent.counts.validationFailed == 0 && intermittent.next == ChildNext.Review && intermittent.blocker.isEmpty)
      val exhausted = DispatchProjection.completed(initial, ready.copy(validation = List(ValidationEvidence("flaky", ValidationState.Failed, handle, List(ArtifactId(UUID.randomUUID()))))), handle)
      assert(exhausted.counts.validationIntermittent == 0 && exhausted.counts.validationFailed == 1 && exhausted.next == ChildNext.Revise)
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
