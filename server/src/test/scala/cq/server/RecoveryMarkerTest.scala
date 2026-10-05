package cq.server

import cq.api.*
import cq.host.*
import distage.Activation
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.time.Clock
import java.util.UUID
import scala.util.Try
import zio.ZIO

/**
 * D103: `recovery.json` is written only for a conclusion that holds. The case names are those of the R10 probe; every case runs at
 * least two successive startup recoveries over the same state root.
 */
final class RecoveryMarkerLocal extends SpecZIO with AssertZIO {
  override def config = super.config.copy(pluginConfig = PluginConfig.const(List(WorkspaceTestPlugin)), activation = Activation(Repo -> Repo.Prod))
  private def uuid: UUID = UUID.randomUUID()
  private val Upload = "cq job upload --session"
  private val Interrupted = "Supervisor publication was interrupted; retained output is a bounded snapshot, process settlement and remaining usage are unknown"
  private val Unsettled = "Owning session ended before the job settled; termination is unconfirmed"
  private def problem(receipt: WorkspaceCleanupReceipt, session: EndedSession): String = session.reported(receipt).flatMap(_.problem).getOrElse("")
  /** Every ref of the governing repository and its checked-out commit: a Git update would move one of them. */
  private def refs(local: LocalWorkspaceFixture): String = local.git(local.source, "for-each-ref") + "\n" + local.git(local.source, "rev-parse", "HEAD")
  private val pi = AttachedPiEvent(1, "native-session", 1, "provider", "model", 1000, Some("response-1"), "stop",
    Some(100), Some(30), Some(20), Some(0), None, Some(150), Some(DecimalAmount("0.004")))
  /** A Pi sample the host retained while its server could not be reached: `sample.json` and a committed, unacknowledged queue. */
  private def retainPi(session: EndedSession): Path = {
    val unreachable = new RecoveryServer
    unreachable.lose = _ => true
    assert(Try(new AttachedUsage(session.directory, session.run, Clock.systemUTC()).accept(pi, unreachable)).isFailure)
    val queue = session.directory.resolve("pi-usage").resolve("0001").resolve("delivery").resolve("final")
    assert(Files.exists(queue.resolve("000000.json")) && !Files.exists(queue.resolve("000000.ack")))
    queue
  }

  "Session recovery markers (Behavioral Active Blackbox; local Git Communication)" should {
    "io-04: write no marker when run.json is absent at the moment it is read, and recover the session once the fault is gone" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        // Examined first; its grant is the moment the later session's record disappears, after the candidates were selected.
        earlier <- state.session(0)
        later <- state.session(1)
        away = later.directory.resolve("run.json.away")
        _ <- ZIO.attemptBlocking {
          later.finish()
          state.onGrant = () => if (!Files.exists(away)) { Files.move(later.directory.resolve("run.json"), away); () }
        }
        first <- state.recover
        unmarked <- ZIO.attemptBlocking {
          val seen = (later.markerText, Files.exists(away))
          state.onGrant = () => ()
          Files.move(away, later.directory.resolve("run.json"))
          seen
        }
        second <- state.recover
        states <- later.admissions
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"io-04: first=$first marker=${unmarked._1} second=$second third=${third.totals}")
          assert(unmarked._2 && unmarked._1.isEmpty, s"The fault was not injected or a marker was written: $unmarked")
          assert(problem(first, later).startsWith("Session could not be examined") && first.totals.abandoned == 0 && earlier.outcome.contains(RecoveryOutcome.Recovered), first.toString)
          assert(second.sessions.map(_.session) == List(later.id) && second.totals.recovered == 1 && second.sessions.head.removed == later.attempts, second.toString)
          assert(later.outcome.contains(RecoveryOutcome.Recovered) && states == List(WorkspaceAdmission.Removed) && third.totals.examined == 0, s"${later.recovery} $states $third")
        }
      } yield ()
    }

    "io-04: report a session whose run.json is already absent or not a regular file when the pass begins, and recover it once the record is back" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        absent <- state.session(1)
        linked <- state.session(1)
        healthy <- state.session(1)
        faulty = List(absent, linked)
        away = faulty.map(_.directory.resolve("run.json.away"))
        // A host that holds its lock and has not recorded itself yet; once it ends, the directory it leaves holds only that lock.
        starting = state.root.resolve(uuid.toString)
        owner <- ZIO.attemptBlocking {
          (healthy :: faulty).foreach(_.finish())
          faulty.zip(away).foreach((session, kept) => Files.move(session.directory.resolve("run.json"), kept))
          Files.createSymbolicLink(linked.directory.resolve("run.json"), away(1))
          HostFiles.directory(starting)
          FileJobRepository.open(starting.resolve("journal"), state.project.project, SessionId(UUID.fromString(starting.getFileName.toString)))
        }
        first <- state.recover.ensuring(ZIO.succeed(owner.close()))
        unmarked <- ZIO.attemptBlocking {
          val seen = (faulty.map(_.markerText), healthy.outcome, Files.exists(starting.resolve("recovery.json")))
          Files.delete(linked.directory.resolve("run.json"))
          faulty.zip(away).foreach((session, kept) => Files.move(kept, session.directory.resolve("run.json")))
          seen
        }
        second <- state.recover
        states <- ZIO.foreach(faulty)(_.admissions)
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"io-04 before listing: first=$first markers=$unmarked second=$second third=$third")
          val unrecorded = SessionId(UUID.fromString(starting.getFileName.toString))
          def unreachable(receipt: WorkspaceCleanupReceipt, session: SessionId): Boolean =
            receipt.sessions.find(_.session == session).flatMap(_.problem).exists(text => text.startsWith("Session could not be examined") && text.contains("regular file"))
          assert(faulty.forall(session => unreachable(first, session.id)) && unmarked._1 == List(None, None), s"$first $unmarked")
          assert(first.live == List(unrecorded) && first.totals.examined == 3 && first.totals.problems == 2 && first.totals.abandoned == 0 && first.totals.recovered == 1, first.toString)
          assert(unmarked._2.contains(RecoveryOutcome.Recovered) && healthy.reported(first).exists(_.removed == healthy.attempts), s"$unmarked $first")
          // The records are back: both sessions are swept and marked. The host that never recorded itself has ended and left only its
          // lock: there is nothing to recover or report there, and nothing is concluded about it.
          assert(faulty.forall(session => second.sessions.find(_.session == session.id).exists(value => value.removed == session.attempts && value.problem.isEmpty)), second.toString)
          assert(second.totals.examined == 2 && second.totals.recovered == 2 && second.totals.abandoned == 0 && second.totals.problems == 0 && second.live.isEmpty, second.toString)
          assert(faulty.forall(_.outcome.contains(RecoveryOutcome.Recovered)) && states == List.fill(2)(List(WorkspaceAdmission.Removed)), s"$states")
          assert(third.totals.examined == 0 && third.sessions.isEmpty && !unmarked._3 && !Files.exists(starting.resolve("recovery.json")) &&
            Files.exists(starting.resolve("journal").resolve("owner.lock")), third.toString)
        }
      } yield ()
    }

    "io-02: not abandon a session whose run.json or job record exceeds its byte bound, and recover it once the record is within the bound" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      val padding = Array.fill[Byte](70 * 1024)(' ')
      for {
        record <- state.session(1)
        job <- state.session(1)
        files = List(record.directory.resolve("run.json"), job.directory.resolve("journal").resolve(job.attempts.head.value.toString + ".json"))
        originals <- ZIO.attemptBlocking {
          record.finish(); job.finish()
          val contents = files.map(Files.readAllBytes)
          files.zip(contents).foreach((file, content) => Files.write(file, content ++ padding))
          contents
        }
        first <- state.recover
        unmarked <- ZIO.attemptBlocking {
          val seen = List(record, job).map(_.markerText)
          files.zip(originals).foreach((file, content) => Files.write(file, content))
          seen
        }
        second <- state.recover
        states <- ZIO.foreach(List(record, job))(_.admissions)
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"io-02: first=$first markers=$unmarked second=$second third=${third.totals}")
          assert(unmarked == List(None, None) && first.totals.abandoned == 0 && first.totals.recovered == 0, s"$unmarked $first")
          assert(List(record, job).forall(session => problem(first, session).startsWith("Session could not be examined") && problem(first, session).contains("byte bound")), first.toString)
          assert(second.totals.examined == 2 && second.totals.recovered == 2 && second.totals.problems == 0, second.toString)
          assert(List(record, job).forall(_.outcome.contains(RecoveryOutcome.Recovered)) && states == List.fill(2)(List(WorkspaceAdmission.Removed)) && third.totals.examined == 0, s"$states $third")
        }
      } yield ()
    }

    "io-03, io-08: abandon at the first startup a session whose run.json is not UTF-8 or whose job record cannot be decoded, and only once" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        // FF FE 7B: bytes that were read and are not a UTF-8 text.
        encoding <- ZIO.attemptBlocking(state.raw(Array(0xFF.toByte, 0xFE.toByte, 0x7B.toByte)))
        empty <- state.session(1)
        malformed <- state.session(0)
        _ <- ZIO.attemptBlocking {
          empty.finish(); malformed.finish()
          Files.write(empty.directory.resolve("journal").resolve(uuid.toString + ".json"), Array.emptyByteArray)
          Files.write(malformed.directory.resolve("journal").resolve(uuid.toString + ".json"), Array(0x7B.toByte, 0xC3.toByte, 0x28.toByte, 0x7D.toByte))
        }
        first <- state.recover
        markers <- ZIO.attemptBlocking {
          val marker = encoding.resolve("recovery.json")
          assert(Files.exists(marker), "A run.json that is not UTF-8 was left unmarked: " + first)
          (HostFiles.read(marker, SessionRecovery_JsonCodec, 65536), empty.recovery, malformed.recovery)
        }
        admissions <- empty.admissions
        second <- state.recover
        _ <- ZIO.attempt {
          println(s"io-03, io-08: first=$first markers=$markers second=${second.totals}")
          assert(first.totals.examined == 3 && first.totals.abandoned == 3 && first.totals.recovered == 0, first.toString)
          assert(markers._1.outcome == RecoveryOutcome.Abandoned && markers._1.reason.startsWith("Session record run.json could not be decoded"), markers._1.toString)
          assert(List(markers._2, markers._3).forall(_.exists(value => value.outcome == RecoveryOutcome.Abandoned && value.reason.startsWith("Job journal could not be decoded"))), markers.toString)
          // Nothing of an abandoned session is swept, and it is not examined again.
          assert(admissions == List(WorkspaceAdmission.Open) && second.totals.examined == 0 && second.sessions.isEmpty, s"$admissions $second")
        }
      } yield ()
    }

    "io-09: record a session whose children directory cannot be read as a problem, and still examine, sweep and mark a later session" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      def mode(path: Path, value: String): Unit = { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(value)); () }
      for {
        faulty <- state.session(0)
        healthy <- state.session(1)
        children = faulty.directory.resolve("children")
        _ <- ZIO.attemptBlocking {
          faulty.finish(); healthy.finish()
          HostFiles.directory(children)
          mode(children, "---------")
        }
        first <- state.recover.either.ensuring(ZIO.succeed(mode(children, "rwx------")))
        marked <- ZIO.attemptBlocking((faulty.markerText, healthy.outcome))
        states <- healthy.admissions
        second <- state.recover
        _ <- ZIO.attempt {
          println(s"io-09: first=$first markers=$marked second=$second")
          val receipt = first.fold(error => fail("The pass ended without a receipt: " + error), identity)
          assert(problem(receipt, faulty).startsWith("Session could not be examined") && marked._1.isEmpty && receipt.totals.problems == 1, receipt.toString)
          assert(healthy.reported(receipt).exists(_.removed == healthy.attempts) && marked._2.contains(RecoveryOutcome.Recovered) && states == List(WorkspaceAdmission.Removed), s"$receipt $states")
          assert(receipt.totals.examined == 2 && receipt.totals.recovered == 1 && receipt.totals.abandoned == 0, receipt.toString)
          assert(second.sessions.isEmpty && second.totals.examined == 1 && second.totals.recovered == 1 && faulty.outcome.contains(RecoveryOutcome.Recovered), second.toString)
        }
      } yield ()
    }

    "chk-01: not mark a session Recovered while a sealed declared-check queue is unacknowledged, and deliver it at the next startup" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      val check = ValidationCheck("verify", List("verify"), 1000, 65536, 1, 0)
      for {
        ended <- state.session(state.run(Harness.Claude, SessionId(uuid)), 1, List(check))
        queue <- ZIO.attemptBlocking {
          val reviewer = ended.child(ended.attempts.head, DispatchWork.Reviewer(ReviewerMode.Candidate))
          val queue = ended.sealedCheck(reviewer, check).resolve("delivery").resolve("final")
          state.server.lose = upload => upload.id == NativeArtifacts.id(reviewer.attempt.id, "review-check-verify-status")
          queue
        }
        first <- state.recover
        unmarked <- ZIO.attemptBlocking {
          val seen = (ended.markerText, Files.exists(queue.resolve("000000.ack")), Files.exists(ended.directory.resolve("delivery").resolve("final").resolve("000000.ack")))
          state.server.lose = _ => false
          seen
        }
        second <- state.recover
        delivered <- ZIO.attemptBlocking((ended.recovery, Files.exists(queue.resolve("000000.ack")), state.grants.get()))
        explicit <- ended.flush
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"chk-01: first=$first unmarked=$unmarked second=$second delivered=$delivered flush=$explicit third=${third.totals}")
          // The governing publication was acknowledged at the first startup; only the check's queue was left.
          assert(unmarked == (None, false, true) && problem(first, ended).contains("Delivery reconciliation failed"), s"$unmarked $first")
          assert(delivered._2 && delivered._3 == 2 && ended.reported(second).exists(_.acknowledged == 1) && second.totals.acknowledged == 1, s"$delivered $second")
          assert(delivered._1.exists(_.outcome == RecoveryOutcome.Recovered) && second.totals.recovered == 1, delivered.toString)
          assert(explicit.acknowledged == 0 && explicit.incompleteTickets.isEmpty && third.totals.examined == 0, s"$explicit $third")
        }
      } yield ()
    }

    "use-01: not mark a session Recovered while an attached Pi usage sample is unacknowledged, and deliver it at the next startup" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(Harness.Pi, 0)
        queue <- ZIO.attemptBlocking {
          val queue = retainPi(ended)
          state.server.lose = upload => upload.id == NativeArtifacts.id(ended.run.attempt.id, "attached-pi-1")
          queue
        }
        first <- state.recover
        unmarked <- ZIO.attemptBlocking {
          val seen = (ended.markerText, Files.exists(queue.resolve("000000.ack")), Files.exists(ended.directory.resolve("delivery").resolve("final").resolve("000000.ack")))
          state.server.lose = _ => false
          seen
        }
        second <- state.recover
        delivered <- ZIO.attemptBlocking((ended.recovery, Files.exists(queue.resolve("000000.ack")), state.grants.get()))
        explicit <- ended.flush
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"use-01: first=$first unmarked=$unmarked second=$second delivered=$delivered flush=$explicit third=${third.totals}")
          assert(unmarked == (None, false, true) && problem(first, ended).contains("Delivery reconciliation failed"), s"$unmarked $first")
          assert(delivered._2 && delivered._3 == 2 && ended.reported(second).exists(_.acknowledged == 1), s"$delivered $second")
          assert(delivered._1.exists(_.outcome == RecoveryOutcome.Recovered) && explicit.acknowledged == 0 && third.totals.examined == 0, s"$delivered $explicit $third")
        }
      } yield ()
    }

    "use-03: deliver a Pi usage sample left unacknowledged by a session whose final publication was acknowledged, before marking it" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(Harness.Pi, 1)
        queue <- ZIO.attemptBlocking { ended.finish(); retainPi(ended) }
        first <- state.recover
        delivered <- ZIO.attemptBlocking((ended.recovery, Files.exists(queue.resolve("000000.ack")), state.grants.get()))
        explicit <- ended.flush
        second <- state.recover
        _ <- ZIO.attempt {
          println(s"use-03: first=$first delivered=$delivered flush=$explicit second=${second.totals}")
          assert(delivered._2 && delivered._3 == 1 && ended.reported(first).exists(value => value.acknowledged == 1 && value.removed == ended.attempts), s"$delivered $first")
          assert(delivered._1.exists(_.outcome == RecoveryOutcome.Recovered) && explicit.acknowledged == 0 && second.totals.examined == 0 && state.grants.get() == 1, s"$delivered $explicit $second")
        }
      } yield ()
    }

    "use-02: deliver an attached Codex usage sample left unacknowledged, before marking the session" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      val thread = uuid
      for {
        ended <- state.session(Harness.Codex, 0)
        queue <- ZIO.attemptBlocking {
          ended.finish()
          val sessions = Files.createDirectories(Files.createTempDirectory(local.directory, "codex-").resolve("sessions")).toRealPath()
          val sample = CodexUsageSample(thread, uuid, "response-1", 1, 2000, Some("gpt-6-sol"), Some("openai"), TokenCounts(Counter(Some(100), Measurement.Observed),
            Counter(Some(30), Measurement.Observed), Counter(Some(20), Measurement.Observed), Counter(Some(0), Measurement.Observed), Counter(Some(8), Measurement.Observed)))
          val root = ended.directory.resolve("codex-usage")
          val at = root.resolve("samples").resolve(NativeArtifacts.id(ended.run.attempt.id, "attached-codex/" + sample.response).value.toString)
          List(root, root.resolve("samples"), at).foreach(HostFiles.directory)
          HostFiles.immutable(root.resolve("binding.json"), HostFiles.encode(CodexUsageBinding_JsonCodec, CodexUsageBinding(thread, "0.156.1", sessions.toString)), 16384)
          HostFiles.immutable(at.resolve("sample.json"), HostFiles.encode(CodexUsageSample_JsonCodec, sample), 16384)
          at.resolve("delivery").resolve("final")
        }
        first <- state.recover
        delivered <- ZIO.attemptBlocking((ended.recovery, Files.exists(queue.resolve("000000.ack")), state.grants.get()))
        explicit <- ended.flush
        second <- state.recover
        _ <- ZIO.attempt {
          println(s"use-02: first=$first delivered=$delivered flush=$explicit second=${second.totals}")
          assert(delivered._2 && delivered._3 == 1 && ended.reported(first).exists(_.acknowledged == 1), s"$delivered $first")
          assert(delivered._1.exists(_.outcome == RecoveryOutcome.Recovered) && explicit.acknowledged == 0 && second.totals.examined == 0, s"$delivered $explicit $second")
        }
      } yield ()
    }

    "int-01: withhold the marker while an integrations/ reservation is not resolved, name it and cq job upload --session, and launch nothing" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(1)
        intent = ended.intent
        _ <- ZIO.attemptBlocking { ended.finish(); ended.integrationRequest(intent.id); state.server.reserve(intent) }
        _ <- ended.integration(intent, false)
        before <- ZIO.attemptBlocking((Files.readString(ended.integrationFile(intent.id)), refs(local)))
        first <- state.recover
        swept <- ended.admissions
        between <- ZIO.attemptBlocking((ended.markerText, ended.inventory))
        second <- state.recover
        after <- ZIO.attemptBlocking((ended.markerText, Files.readString(ended.integrationFile(intent.id)), refs(local), ended.inventory, ended.records.size))
        // What `cq job upload --session` records for a reservation whose host ended before Git execution was admitted.
        _ <- ZIO.attemptBlocking(state.server.resolve(intent.id, IntegrationResolution.NotApplied("Owning supervisor ended before Git execution admission")))
        third <- state.recover
        fourth <- state.recover
        _ <- ZIO.attempt {
          println(s"int-01: first=$first second=$second after=$after third=$third fourth=${fourth.totals} grants=${state.grants.get()}")
          assert(between._1.isEmpty && after._1.isEmpty && first.totals.recovered == 0 && second.totals.recovered == 0, s"$between $after")
          assert(List(first, second).forall(receipt => problem(receipt, ended).contains("integrations/") && problem(receipt, ended).contains(Upload)), s"$first $second")
          // The settled workspace was still swept; nothing was reserved, observed, prepared or moved.
          assert(swept == List(WorkspaceAdmission.Removed) && ended.reported(first).exists(_.removed == ended.attempts), s"$swept $first")
          assert(state.server.integrations.get() == 0 && after._2 == before._1 && after._3 == before._2 && after._4 == between._2 && after._5 == 1, s"$before $between $after")
          assert(ended.outcome.contains(RecoveryOutcome.Recovered) && third.totals.recovered == 1 && fourth.totals.examined == 0, s"${ended.recovery} $third $fourth")
        }
      } yield ()
    }

    "D144: record the marker for an ended session whose integration the server refused to reserve, since nothing was reserved or launched" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(1)
        intent = ended.intent
        _ <- ZIO.attemptBlocking { ended.finish(); ended.integrationRequest(intent.id) }
        _ <- ended.integration(intent, false)
        _ <- new FileIntegrationJournal(ended.directory.resolve("integrations"), ended.owner).locked(intent.id) { entry => ZIO.attemptBlocking(
          entry.write(entry.read.get.copy(observation = Some(IntegrationObservation.NotApplied("Server refused the reservation, so no Git update was launched: StaleFence(Fixture)"))))) }
        first <- state.recover
        _ <- ZIO.attempt {
          println(s"Refused integration at startup recovery: first=$first marker=${ended.recovery}")
          assert(problem(first, ended).isEmpty && ended.outcome.contains(RecoveryOutcome.Recovered) && first.totals.recovered == 1, s"$first ${ended.recovery}")
          assert(state.server.integrations.get() == 0 && state.server.resolution(intent.id).isEmpty)
        }
      } yield ()
    }

    "int-02: withhold the marker after the host ended during an integration Git job, and record that job as flush records an unsettled job" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        ended <- state.session(0)
        // The same unsettled job in a session whose governing publication is undelivered: its job is recorded by `SessionDelivery.flush`.
        flushed <- state.session(0)
        intent = ended.intent
        job <- ended.running
        twin <- flushed.running
        _ <- ZIO.attemptBlocking { ended.finish(); state.server.reserve(intent) }
        _ <- ended.integration(intent, true)
        before <- ZIO.attemptBlocking((Files.readString(ended.integrationFile(intent.id)), refs(local), ended.records))
        first <- state.recover
        workspace <- ended.service.get(ended.owner, job)
        recorded <- ZIO.attemptBlocking((ended.markerText, ended.records, flushed.records, ended.inventory))
        second <- state.recover
        after <- ZIO.attemptBlocking((ended.markerText, Files.readString(ended.integrationFile(intent.id)), refs(local), ended.inventory, ended.records))
        _ <- ZIO.attemptBlocking(state.server.resolve(intent.id, IntegrationResolution.NotApplied("Git refused the conditional update before commit; executor settled")))
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"int-02: first=$first recorded=$recorded second=$second third=$third")
          val List(record) = recorded._2
          val List(reference) = recorded._3
          assert(before._3.map(_.phase) == List(JobPhase.Running) && record.phase == JobPhase.Uncertain && record.target == JobTarget.Stop && record.problem.contains(Interrupted) &&
            record.revision == before._3.head.revision + 1, record.toString)
          assert((record.target, record.phase, record.exit, record.problem, record.revision) == (reference.target, reference.phase, reference.exit, reference.problem, reference.revision) &&
            reference.workspace.attempt == twin, s"$record $reference")
          val report = ended.reported(first).getOrElse(fail("The session is not in the receipt: " + first))
          assert(report.quarantined == List(RetainedWorkspace(job, Unsettled)) && workspace.admission == WorkspaceAdmission.Quarantined, s"$report $workspace")
          assert(report.problem.exists(text => text.contains("1 unsettled jobs recorded as Uncertain") && text.contains("integrations/") && text.contains(Upload)), report.toString)
          assert(recorded._1.isEmpty && after._1.isEmpty && problem(second, ended).contains("integrations/") && problem(second, ended).contains(Upload), s"$recorded $second")
          assert(state.server.integrations.get() == 0 && after._2 == before._1 && after._3 == before._2 && after._4 == recorded._4 && after._5 == recorded._2, s"$before $after")
          assert(ended.outcome.contains(RecoveryOutcome.Recovered) && third.totals.recovered == 1, s"${ended.recovery} $third")
        }
      } yield ()
    }

    "withhold the marker for an integration-requests/ entry whose reservation is unresolved, and count a request that never froze an intent" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        reserved <- state.session(0)
        unfrozen <- state.session(0)
        intent = reserved.intent
        _ <- ZIO.attemptBlocking {
          reserved.finish(); unfrozen.finish()
          // The server holds a reservation of this request and the session has no journal of it: nothing local can resolve it.
          reserved.integrationRequest(intent.id)
          state.server.reserve(intent)
          // Preparation was cut before it froze an intent: nothing was reserved, and nothing can ever be resolved for it.
          unfrozen.integrationRequest(IntegrationId(uuid))
        }
        before <- ZIO.attemptBlocking((refs(local), reserved.inventory ++ unfrozen.inventory))
        first <- state.recover
        between <- ZIO.attemptBlocking((reserved.markerText, unfrozen.recovery))
        second <- state.recover
        after <- ZIO.attemptBlocking((reserved.markerText, refs(local), reserved.inventory ++ unfrozen.inventory))
        _ <- ZIO.attemptBlocking(state.server.resolve(intent.id, IntegrationResolution.NotApplied("Operator resolved the reservation")))
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"integration-requests: first=$first between=$between second=$second third=$third")
          assert(between._1.isEmpty && after._1.isEmpty, s"$between $after")
          assert(List(first, second).forall(receipt => problem(receipt, reserved).contains("integration-requests/") && problem(receipt, reserved).contains(Upload)), s"$first $second")
          val note = "1 integration requests or combination tickets never frozen, retained for inspection"
          assert(between._2.exists(value => value.outcome == RecoveryOutcome.Recovered && value.reason.contains(note)) && problem(first, unfrozen).contains(note), s"$between $first")
          assert(state.server.integrations.get() == 0 && after._2 == before._1 && after._3 == before._2 && second.sessions.map(_.session) == List(reserved.id), s"$before $after $second")
          assert(reserved.outcome.contains(RecoveryOutcome.Recovered) && third.totals.recovered == 1, s"${reserved.recovery} $third")
        }
      } yield ()
    }

    "withhold the marker while a frozen combinations/ plan is unpublished, publish nothing, and count a ticket that never froze a plan" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      for {
        frozen <- state.session(0)
        unfrozen <- state.session(0)
        entry <- ZIO.attemptBlocking {
          frozen.finish(); unfrozen.finish()
          unfrozen.combination(false)
          frozen.combination(true)
        }
        before <- ZIO.attemptBlocking(refs(local))
        first <- state.recover
        between <- ZIO.attemptBlocking((frozen.markerText, unfrozen.recovery))
        second <- state.recover
        after <- ZIO.attemptBlocking {
          val seen = (frozen.markerText, refs(local), Files.exists(entry.resolve("delivery")), state.server.deliveries.get())
          // `cq job upload --session` publishes the frozen plan from its own queue.
          val queue = new DeliveryQueue(entry.resolve("delivery"))
          queue.enqueue(0, DeliveryBatch(List(HostDelivery.Artifact(ArtifactUpload(frozen.run.project.project, ArtifactId(uuid), frozen.run.attempt.id, ArtifactKind.Combination,
            "application/json", Files.readString(entry.resolve("plan.json")))))))
          assert(queue.flush(new RecoveryServer) == 1)
          seen
        }
        third <- state.recover
        _ <- ZIO.attempt {
          println(s"combinations: first=$first between=$between second=$second after=$after third=$third")
          assert(between._1.isEmpty && after._1.isEmpty, s"$between $after")
          assert(List(first, second).forall(receipt => problem(receipt, frozen).contains("combinations/") && problem(receipt, frozen).contains(Upload)), s"$first $second")
          val note = "1 integration requests or combination tickets never frozen, retained for inspection"
          assert(between._2.exists(value => value.outcome == RecoveryOutcome.Recovered && value.reason.contains(note)) && problem(first, unfrozen).contains(note), s"$between $first")
          assert(after._2 == before && !after._3 && after._4 == 0 && state.grants.get() == 0 && state.server.integrations.get() == 0, s"$before $after grants=${state.grants.get()}")
          assert(frozen.outcome.contains(RecoveryOutcome.Recovered) && third.totals.recovered == 1, s"${frozen.recovery} $third")
        }
      } yield ()
    }

    "chk-02: not keep a session pending for an uncommitted check ticket, and state the retained count in the marker and the receipt" in { (local: LocalWorkspaceFixture) =>
      val state = RecoveryState(local)
      val note = "1 incomplete tickets or usage samples retained for inspection"
      for {
        // The host was killed: the governing publication is reconciled, and the flush reports the ticket.
        killed <- state.session(1)
        // The host finished in order and its reviewer's result was published: nothing is flushed, and the ticket is still stated.
        finished <- state.session(1)
        _ <- ZIO.attemptBlocking {
          killed.uncommittedCheck(killed.child(killed.attempts.head, DispatchWork.Reviewer(ReviewerMode.Candidate)), "verify")
          finished.finish()
          val reviewer = finished.child(finished.attempts.head, DispatchWork.Reviewer(ReviewerMode.Candidate))
          finished.completed(reviewer)
          finished.uncommittedCheck(reviewer, "verify")
        }
        first <- state.recover
        markers <- ZIO.attemptBlocking(List(killed, finished).map(_.recovery))
        second <- state.recover
        _ <- ZIO.attempt {
          println(s"chk-02: first=$first markers=$markers second=${second.totals} grants=${state.grants.get()}")
          assert(markers.forall(_.exists(value => value.outcome == RecoveryOutcome.Recovered && value.reason.contains(note))), markers.toString)
          assert(List(killed, finished).forall(session => problem(first, session) == note) && first.totals.recovered == 2, first.toString)
          assert(List(killed, finished).forall(session => Files.exists(session.directory.resolve("children").resolve(session.attempts.head.value.toString).resolve("checks")
            .resolve("verify").resolve(".upload-interrupted.pending"))) && state.grants.get() == 1 && second.totals.examined == 0, s"${state.grants.get()} $second")
        }
      } yield ()
    }
  }
}
