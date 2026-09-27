package cq.server

import cq.api.*
import cq.host.*
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.{Base64, UUID}
import java.time.Duration
import org.scalatest.wordspec.AnyWordSpec

final class HostDeliveryLocal extends AnyWordSpec {
  private def project: ProjectId = ProjectId(UUID.randomUUID())
  private def attempt: AttemptId = AttemptId(UUID.randomUUID())
  private final class Receiver extends ServerApi {
    var values = Map.empty[ArtifactId, ArtifactUpload]
    var calls = 0
    var loseAcknowledgement = true
    override def artifact(value: ArtifactUpload): ArtifactMetadata = {
      calls += 1
      require(values.get(value.id).forall(_ == value), "Artifact replay changed its content")
      values = values.updated(value.id, value)
      if (loseAcknowledgement) { loseAcknowledgement = false; throw new IOException("Acknowledgement lost after publication") }
      ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture", value.body.getBytes(UTF_8).length,
        value.body.codePointCount(0, value.body.length), Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
    }
    override def call(value: Command): Result = throw new IllegalStateException("Not a publication operation")
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Not used in artifact replay scenario")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("This publication has no admission request")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Publication queue cannot grant authority")
  }

  "Host delivery (Behavioral Active Blackbox; Group / filesystem Communication)" should {
    "reject independently supplied result bytes that disagree with the sealed publication" in {
      val p = project
      val a = attempt
      val owner = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor)
      val item = ItemRevision(ItemId(p, Ledger.Tasks, 1), Revision(1))
      val assignment = Assignment(AssignmentId(UUID.randomUUID()), p, Set(item.id), Attribution.Direct, None, None)
      val record = Attempt(a, assignment.id, Some(attempt), owner.session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", 1000)
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, List(item), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val ticket = DispatchTicket(request, assignment, record, HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty))
      val ready = ChildResult(a, request, GitCommit("a" * 40), Some(GitCommit("b" * 40)),
        ChildReport.Work(List(WorkMember(item.id, WorkDisposition.CandidateReady, "Ready"))), Nil)
      val blocked = ready.copy(report = ChildReport.Work(List(WorkMember(item.id, WorkDisposition.Blocked, "Blocked"))))
      val intent = ChildPublication(p, owner, Some(ready), DispatchProjection.pending(ticket),
        AttemptOutcome(RequestId(NativeArtifacts.id(a, "outcome").value), a, AttemptState.Completed, 2000, Nil, None))
      val upload = ArtifactUpload(p, NativeArtifacts.id(a, "result"), a, ArtifactKind.Result, "application/json", HostFiles.encode(ChildResult_JsonCodec, blocked))
      val directory = Files.createTempDirectory("cq-inconsistent-publication-")
      intercept[IllegalArgumentException](new ChildPublicationDelivery(directory, ticket).seal(intent, List(HostDelivery.Artifact(upload))))
      assert(!Files.exists(directory.resolve("publication.json")))
    }

    "admit only normal successful exits and retain cancellation and uncertain cleanup outcomes" in {
      val spec = WorkspaceSpec(project, SessionId(UUID.randomUUID()), attempt, "/consumer", GitCommit("a" * 40))
      val normal = JobRecord(spec, "fixture", JobTarget.Stop, JobPhase.Settled,
        Some(JobExit(Some(0), None, StopReason.Exited, 0, 0, true, false)), None, 1, 1, 2)
      StopReason.all.foreach { reason =>
        val value = JobOutcome.observed(normal.copy(exit = normal.exit.map(_.copy(reason = reason))))
        val expected = reason match {
          case StopReason.Exited => AttemptState.Completed
          case StopReason.Cancelled | StopReason.OwnerExited => AttemptState.Cancelled
          case _ => AttemptState.Failed
        }
        assert(value.state == expected && value.succeeded == (reason == StopReason.Exited), reason.toString)
        assert(value.withResult(false) == (if (expected == AttemptState.Completed) AttemptState.Failed else expected))
        assert(value.problem.isEmpty == value.succeeded)
        value.problem.foreach(message => assert(message.contains(reason.toString)))
      }
      List(normal.copy(phase = JobPhase.Uncertain), normal.copy(exit = None),
        normal.copy(exit = normal.exit.map(_.copy(settled = false))),
        normal.copy(exit = normal.exit.map(_.copy(hostFailure = true)))).foreach { record =>
        val value = JobOutcome.observed(record)
        assert(!value.succeeded && value.withResult(true) == AttemptState.Unknown && value.withResult(false) == AttemptState.Unknown)
      }
      assert(!JobOutcome.observed(normal.copy(exit = normal.exit.map(_.copy(code = Some(1))))).succeeded)
      assert(!JobOutcome.observed(normal.copy(exit = normal.exit.map(_.copy(signal = Some(15))))).succeeded)
      intercept[IllegalArgumentException](JobOutcome.observed(normal.copy(phase = JobPhase.Running)))
    }

    "replay the same immutable batch after lost acknowledgement and preserve receipts across reopening" in {
      val directory = Files.createTempDirectory("cq-delivery-")
      val queue = new DeliveryQueue(directory)
      val upload = ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt, ArtifactKind.Result, "text/plain", "retained λ")
      val batch = DeliveryBatch(List(HostDelivery.Artifact(upload)))
      queue.enqueue(0, batch)
      queue.enqueue(0, batch)
      intercept[IllegalArgumentException](queue.enqueue(0, DeliveryBatch(List(HostDelivery.Artifact(upload.copy(body = "changed"))))))
      val receiver = new Receiver
      intercept[IOException](queue.flush(receiver))
      assert(receiver.values == Map(upload.id -> upload) && receiver.calls == 1)
      assert(!Files.exists(directory.resolve("000000.ack")))
      assert(new DeliveryQueue(directory).flush(receiver) == 1)
      assert(receiver.values == Map(upload.id -> upload) && receiver.calls == 2)
      assert(new DeliveryQueue(directory).flush(receiver) == 0 && receiver.calls == 2)
      assert(Files.getPosixFilePermissions(directory.resolve("000000.json")) == PosixFilePermissions.fromString("rw-------"))
      Files.writeString(directory.resolve("000000.ack"), "invalid")
      intercept[IllegalArgumentException](queue.flush(receiver))
    }

    "ignore uncommitted staging and replay a sealed complete publication after lost acknowledgement" in {
      val directory = Files.createTempDirectory("cq-final-delivery-")
      val queue = new DeliveryQueue(directory)
      val p = project
      val a = attempt
      val abandoned = ArtifactUpload(p, ArtifactId(UUID.randomUUID()), a, ArtifactKind.Transcript, "text/plain", "uncommitted")
      HostFiles.directory(directory.resolve("staging"))
      HostFiles.immutable(directory.resolve("staging/000000.json"), HostFiles.encode(DeliveryBatch_JsonCodec,
        DeliveryBatch(List(HostDelivery.Artifact(abandoned)))), 4096)
      val receiver = new Receiver
      assert(!queue.finalized && queue.flush(receiver) == 0 && receiver.calls == 0)
      val count = 33
      val uploads = List.tabulate(count)(index => ArtifactUpload(p, ArtifactId(UUID.randomUUID()), a,
        ArtifactKind.Transcript, "text/plain", s"committed-$index"))
      val entries = uploads.map(HostDelivery.Artifact.apply)
      queue.commit(entries)
      assert(queue.finalized && !Files.exists(directory.resolve("staging")))
      intercept[IOException](queue.flush(receiver))
      val reopened = new DeliveryQueue(directory)
      reopened.commit(entries)
      assert(reopened.finalized && reopened.flush(receiver) == 2)
      assert(receiver.values == uploads.map(value => value.id -> value).toMap && receiver.calls == count + 1)
      assert(reopened.flush(receiver) == 0)
      intercept[IllegalArgumentException](reopened.commit(entries.dropRight(1)))
      intercept[IllegalArgumentException](reopened.commit(entries :+ HostDelivery.Artifact(abandoned)))
      intercept[IllegalArgumentException](reopened.enqueue(0, DeliveryBatch(List(HostDelivery.Artifact(abandoned)))))
    }

    "refuse replay after an ambiguous rename until parent directory durability is confirmed" in {
      val root = Files.createTempDirectory("cq-publication-sync-")
      val preload = root.resolve("failure.so")
      val source = Path.of(System.getProperty("cq.test.sourceRoot"))
      val command = new BoundedHostCommand(sys.env, Duration.ofSeconds(30), 8192)
      val compile = command.run(source, List("gcc", "-std=c17", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror",
        "-o", preload.toString, source.resolve("dev/shutdown-stall.c").toString, "-ldl"))
      assert(compile.exit == 0, compile.text)
      val queue = root.resolve("delivery")
      val process = new BoundedHostCommand(sys.env ++ Map("LD_PRELOAD" -> preload.toString, "CQ_FIXTURE_STALL_ROOT" -> queue.toString,
        "CQ_FIXTURE_STALL_MODE" -> "publication"), Duration.ofSeconds(15), 8192)
      val result = process.run(root, List(Path.of(System.getProperty("java.home"), "bin", "java").toString,
        "-cp", System.getProperty("cq.test.classpath"), "cq.server.PublicationDurabilityCheck", queue.toString))
      assert(result.exit == 0 && result.text.contains("DURABLE_PUBLICATION_REPLAY"), result.text)
    }

    "preserve malformed and multipart native bytes behind deterministic artifact handles" in {
      val bytes = Array.tabulate[Byte](400000)(index => (index % 256).toByte)
      val p = project
      val a = attempt
      val (id, uploads) = NativeArtifacts.binary(p, a, "stdout", "application/x-ndjson", bytes)
      assert(NativeArtifacts.binary(p, a, "stdout", "application/x-ndjson", bytes) == (id, uploads))
      val manifest = Wire.decode(NativeManifest_JsonCodec, uploads.last.body)
      val parts = uploads.dropRight(1)
      assert(manifest.parts == parts.map(_.id) && manifest.bytes == bytes.length && uploads.last.id == id)
      assert(java.util.Arrays.equals(parts.flatMap(value => Base64.getDecoder.decode(value.body)).toArray, bytes))
      assert(uploads.forall(_.body.getBytes(UTF_8).length <= cq.core.ArtifactService.MaxBytes))
      val (_, empty) = NativeArtifacts.binary(p, a, "stderr", "application/octet-stream", Array.emptyByteArray)
      assert(Wire.decode(NativeManifest_JsonCodec, empty.head.body).parts.isEmpty)
    }

    "extract structured native results and reject incomplete or nonterminal output" in {
      val directory = Files.createTempDirectory("cq-native-output-")
      val parser = new HarnessOutput
      val expected = io.circe.Json.obj("summary" -> io.circe.Json.fromString("observed λ"))
      val claude = s"""{"type":"result","subtype":"success","is_error":false,"structured_output":${expected.noSpaces}}\n"""
      assert(parser.result(Harness.Claude, claude.getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Claude, claude.stripSuffix("\n").getBytes(UTF_8), directory))
      Files.writeString(directory.resolve("last-message.json"), expected.noSpaces)
      assert(parser.result(Harness.Codex, "{\"type\":\"turn.completed\"}\n".getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Codex, "{\"type\":\"turn.started\"}\n".getBytes(UTF_8), directory))
      val message = io.circe.Json.obj("type" -> io.circe.Json.fromString("message_end"), "message" -> io.circe.Json.obj(
        "role" -> io.circe.Json.fromString("assistant"), "stopReason" -> io.circe.Json.fromString("stop"), "content" -> io.circe.Json.arr(
          io.circe.Json.obj("type" -> io.circe.Json.fromString("text"), "text" -> io.circe.Json.fromString(expected.noSpaces)))))
      assert(parser.result(Harness.Pi, (message.noSpaces + "\n").getBytes(UTF_8), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Pi, (message.noSpaces.replace("\"stop\"", "\"error\"") + "\n").getBytes(UTF_8), directory))
    }

    "reject excessive native event counts before exhausting a 64 MiB heap" in {
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val value = new BoundedHostCommand(Map.empty, Duration.ofSeconds(15), 4096).run(Path.of("").toAbsolutePath.normalize(),
        List(java, "-Xmx64m", "-cp", System.getProperty("cq.test.classpath"), "cq.server.HarnessOutputBounds"))
      assert(value.exit == 0 && value.text.contains("BOUNDED_NATIVE_EVENT_REJECTION"), value.text)
    }
  }
}

object PublicationDurabilityCheck {
  def main(arguments: Array[String]): Unit = {
    val root = Path.of(arguments(0))
    val queue = new DeliveryQueue(root)
    val project = ProjectId(UUID.randomUUID())
    val attempt = AttemptId(UUID.randomUUID())
    val upload = ArtifactUpload(project, ArtifactId(UUID.randomUUID()), attempt, ArtifactKind.Transcript, "text/plain", "retained")
    val entries = List(HostDelivery.Artifact(upload))
    var published = 0
    val api = new ServerApi {
      override def artifact(value: ArtifactUpload): ArtifactMetadata = {
        published += 1
        ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType, "fixture", value.body.getBytes(UTF_8).length,
          value.body.length, Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
      }
      override def call(value: Command): Result = throw new IllegalStateException("Unexpected domain call")
      override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Unexpected usage call")
      override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("This publication has no admission request")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Unexpected grant")
    }
    def rejected[A](operation: => A): Boolean = try { operation; false } catch { case _: IOException => true }
    require(rejected(queue.commit(entries)) && Files.isDirectory(root.resolve("final")), "Expected failure after rename")
    require(rejected(queue.finalized), "Unforced final directory was reported durable")
    require(rejected(queue.flush(api)) && published == 0, "Unforced final publication reached the server")
    require(rejected(queue.commit(entries)), "Idempotent commit skipped the failed directory force")
    Files.createFile(root.resolve("release"))
    queue.commit(entries)
    require(queue.finalized && queue.flush(api) == 1 && published == 1 && queue.flush(api) == 0)
    println("DURABLE_PUBLICATION_REPLAY")
  }
}
