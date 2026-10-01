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
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Publication cannot integrate candidates")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Publication queue cannot grant authority")
  }

  "Host delivery (Behavioral Active Blackbox; Group / filesystem Communication)" should {
    "reject fresh reviewer checks that would disappear from the inherited inventory" in {
      val inherited = List(ValidationEvidence("original", ValidationState.Passed, ArtifactId(UUID.randomUUID()), Nil))
      val extra = ValidationEvidence("newly-configured", ValidationState.Failed, ArtifactId(UUID.randomUUID()), Nil)
      intercept[IllegalArgumentException](ReviewerValidation.overlay(inherited, List(extra)))
      intercept[IllegalArgumentException](ReviewerValidation.inventory(inherited,
        List("original", "newly-configured").map(name => ValidationCheck(name, List("verify"), 1000, 65536, 1))))
      val failed = inherited.head.copy(state = ValidationState.Failed, artifact = ArtifactId(UUID.randomUUID()))
      assert(ReviewerValidation.overlay(inherited, List(failed)) == List(failed))
    }

    "replay sealed reviewer check evidence after a lost acknowledgement without rereading payload" in {
      val root = Files.createTempDirectory("cq-review-check-replay-")
      val spec = WorkspaceSpec(project, SessionId(UUID.randomUUID()), attempt, "/consumer", GitCommit("a" * 40))
      val ticket = DeclaredCheckTicket(attempt, ValidationCheck("verify", List("verify"), 1000, 65536, 1), spec, "a" * 64, Nil)
      val record = JobRecord(spec, ticket.fingerprint, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(0), None, StopReason.Exited, 4, 0, true, false)), None, 1, 1, 2)
      val payload = root.resolve("payload").resolve(spec.attempt.value.toString)
      HostFiles.directory(payload)
      Files.writeString(payload.resolve("stdout"), "pass")
      val directory = root.resolve("check")
      HostFiles.directory(directory)
      val publication = new DeclaredCheckPublication(directory, ticket, root.resolve("payload"))
      publication.seal(Some(record), None)
      val receiver = new Receiver
      intercept[IOException](publication.finish(receiver))
      Files.writeString(payload.resolve("stdout"), "different output after interruption")
      val reopened = new DeclaredCheckPublication(directory, ticket, root.resolve("payload"))
      reopened.reconcile(Some(record))
      val receipt = reopened.finish(receiver)
      assert(receipt.status.phase == DeclaredCheckPhase.Completed && receipt.status.evidence.exists(_.state == ValidationState.Passed))
      assert(receipt.acknowledged == 1 && reopened.finish(receiver).acknowledged == 0)
      val retained = receiver.values(NativeArtifacts.id(ticket.parent, "review-check-verify-stdout-part-0"))
      assert(new String(Base64.getDecoder.decode(retained.body), UTF_8) == "pass")
      intercept[IllegalArgumentException](reopened.reconcile(Some(record.copy(fingerprint = "b" * 64))))
      intercept[IllegalArgumentException](reopened.reconcile(Some(record.copy(workspace = spec.copy(base = GitCommit("b" * 40))))))
    }

    "I19: seal a rerun under its own artifact names with the earlier failed runs recorded in its evidence" in {
      val root = Files.createTempDirectory("cq-review-check-rerun-")
      val parent = AttemptId(UUID.randomUUID())
      val first = NativeArtifacts.id(parent, "review-check-verify")
      val spec = WorkspaceSpec(project, SessionId(UUID.randomUUID()), AttemptId(UUID.randomUUID()), "/consumer", GitCommit("a" * 40))
      val ticket = DeclaredCheckTicket(parent, ValidationCheck("verify", List("verify"), 1000, 65536, 2), spec, "a" * 64, List(first))
      val record = JobRecord(spec, ticket.fingerprint, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(0), None, StopReason.Exited, 4, 0, true, false)), None, 1, 1, 2)
      val payload = root.resolve("payload").resolve(spec.attempt.value.toString)
      HostFiles.directory(payload)
      Files.writeString(payload.resolve("stdout"), "pass")
      val directory = root.resolve("check")
      HostFiles.directory(directory)
      val publication = new DeclaredCheckPublication(directory, ticket, root.resolve("payload"))
      publication.seal(Some(record), None)
      val receiver = new Receiver
      receiver.loseAcknowledgement = false
      val receipt = publication.finish(receiver)
      val observation = NativeArtifacts.id(parent, "recheck-2-verify")
      assert(receipt.status.phase == DeclaredCheckPhase.Completed &&
        receipt.status.evidence.contains(ValidationEvidence("verify", ValidationState.Passed, observation, List(first))), receipt.status.toString)
      assert(receiver.values.contains(observation) && receiver.values.contains(NativeArtifacts.id(parent, "recheck-2-verify-stdout")) && !receiver.values.contains(first))
    }

    "preserve unknown reviewer checks without inventing observations when no job was committed" in {
      val root = Files.createTempDirectory("cq-review-check-missing-")
      val spec = WorkspaceSpec(project, SessionId(UUID.randomUUID()), attempt, "/consumer", GitCommit("a" * 40))
      val ticket = DeclaredCheckTicket(attempt, ValidationCheck("verify", List("verify"), 1000, 65536, 1), spec, "a" * 64, Nil)
      val publication = new DeclaredCheckPublication(root, ticket, root.resolve("payload"))
      publication.reconcile(None)
      val receiver = new Receiver
      receiver.loseAcknowledgement = false
      val receipt = publication.finish(receiver)
      assert(receipt.status.phase == DeclaredCheckPhase.Unknown && receipt.status.evidence.isEmpty)
      assert(receiver.values.values.forall(_.kind != ArtifactKind.Validation))
      assert(publication.finish(receiver).acknowledged == 0)
    }

    "mark truncated check output unknown and never rebuild a sealed queue after an interrupted status write" in {
      val root = Files.createTempDirectory("cq-review-check-partial-")
      val spec = WorkspaceSpec(project, SessionId(UUID.randomUUID()), attempt, "/consumer", GitCommit("a" * 40))
      val ticket = DeclaredCheckTicket(attempt, ValidationCheck("verify", List("verify"), 1000, 65536, 1), spec, "a" * 64, Nil)
      val record = JobRecord(spec, ticket.fingerprint, JobTarget.Run, JobPhase.Settled,
        Some(JobExit(Some(0), None, StopReason.Exited, 10, 0, true, false)), None, 1, 1, 2)
      val publication = new DeclaredCheckPublication(root, ticket, root.resolve("payload"))
      publication.seal(Some(record), None)
      val receiver = new Receiver
      receiver.loseAcknowledgement = false
      assert(publication.finish(receiver).status.evidence.exists(_.state == ValidationState.Unknown))
      Files.delete(root.resolve("result.json"))
      publication.reconcile(Some(record))
      val recovered = publication.finish(receiver)
      assert(recovered.status.phase == DeclaredCheckPhase.Unknown && recovered.status.evidence.isEmpty && recovered.acknowledged == 0)
    }

    "reject independently supplied result bytes that disagree with the sealed publication" in {
      val p = project
      val a = attempt
      val owner = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor)
      val item = ItemRevision(ItemId(p, Ledger.Tasks, 1), Revision(1))
      val assignment = Assignment(AssignmentId(UUID.randomUUID()), p, Set(item.id), Attribution.Direct, None, None)
      val record = Attempt(a, assignment.id, Some(attempt), owner.session, Role.Worker, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Work)
      val request = DispatchRequest(RequestId(UUID.randomUUID()), DispatchWork.Worker(WorkerMode.Implement), Harness.Codex, List(item), Nil, Nil, None,
        Fence(ClaimId(UUID.randomUUID()), 1), HostLimits(3000, 10000, 1000, 300, 2000, 262144))
      val ticket = DispatchTicket(request, assignment, record, HarnessSetting(Harness.Codex, "/fixture", "fixture", "fixture", "0.156.1", Nil, Set.empty), None)
      val ready = ChildResult(a, request, GitCommit("a" * 40), Some(GitCommit("b" * 40)),
        ChildReport.Work(List(WorkMember(item.id, WorkDisposition.CandidateReady, "Ready", Nil))), Nil, RetainedEvidence(Nil, Nil))
      val blocked = ready.copy(report = ChildReport.Work(List(WorkMember(item.id, WorkDisposition.Blocked, "Blocked", Nil))))
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
        assert(value.problem.exists(_.contains("disk-safety ceiling")) == (reason == StopReason.OutputLimit), reason.toString)
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
      def stream(text: String): java.io.InputStream = new java.io.ByteArrayInputStream(text.getBytes(UTF_8))
      val expected = io.circe.Json.obj("summary" -> io.circe.Json.fromString("observed λ"))
      val claude = s"""{"type":"result","subtype":"success","is_error":false,"structured_output":${expected.noSpaces}}\n"""
      assert(parser.result(Harness.Claude, stream(claude), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Claude, stream(claude.stripSuffix("\n")), directory))
      Files.writeString(directory.resolve("last-message.json"), expected.noSpaces)
      assert(parser.result(Harness.Codex, stream("{\"type\":\"turn.completed\"}\n"), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Codex, stream("{\"type\":\"turn.started\"}\n"), directory))
      val message = io.circe.Json.obj("type" -> io.circe.Json.fromString("message_end"), "message" -> io.circe.Json.obj(
        "role" -> io.circe.Json.fromString("assistant"), "stopReason" -> io.circe.Json.fromString("stop"), "content" -> io.circe.Json.arr(
          io.circe.Json.obj("type" -> io.circe.Json.fromString("text"), "text" -> io.circe.Json.fromString(expected.noSpaces)))))
      assert(parser.result(Harness.Pi, stream(message.noSpaces + "\n"), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Pi, stream(message.noSpaces.replace("\"stop\"", "\"error\"") + "\n"), directory))
      // An event beyond the line bound is skipped; it withdraws the Pi assistant message it may have superseded and is never a Codex terminal event.
      val oversized = "{\"type\":\"tool\",\"text\":\"" + "x" * (1024 * 1024) + "\"}\n"
      assert(parser.result(Harness.Claude, stream(oversized + claude), directory) == expected)
      assert(parser.result(Harness.Pi, stream(message.noSpaces + "\n" + oversized + message.noSpaces + "\n"), directory) == expected)
      intercept[IllegalArgumentException](parser.result(Harness.Pi, stream(message.noSpaces + "\n" + oversized), directory))
      intercept[IllegalArgumentException](parser.result(Harness.Codex, stream("{\"type\":\"turn.completed\"}\n" + oversized), directory))
    }

    "retain a stream that fits its bound whole and a longer one as head, truncation marker and tail" in {
      val directory = Files.createTempDirectory("cq-native-transcript-")
      val lines = (0 until 200).map(index => s"""{"type":"event","index":$index}""" + "\n").mkString
      val source = directory.resolve("stdout")
      Files.writeString(source, lines)
      val total = lines.length.toLong
      assert(new String(NativeTranscript.retained(source, lines.length), UTF_8) == lines)
      assert(NativeTranscript.retained(directory.resolve("absent"), 1024).isEmpty)
      val retained = new String(NativeTranscript.retained(source, 1024), UTF_8)
      val kept = retained.linesIterator.toList
      val marker = kept.indexWhere(_.contains(NativeTranscript.MarkerType))
      val (head, tail) = (kept.take(marker), kept.drop(marker + 1))
      assert(retained.length <= 1024 && retained.endsWith("\n") && head.nonEmpty && tail.nonEmpty && kept.count(_.contains(NativeTranscript.MarkerType)) == 1)
      assert(lines.startsWith(head.mkString("", "\n", "\n")) && lines.endsWith(tail.mkString("", "\n", "\n")))
      val omitted = total - head.map(_.length + 1).sum - tail.map(_.length + 1).sum
      assert(kept(marker) == s"""{"type":"cq.truncated","totalBytes":$total,"omittedBytes":$omitted}""")
      Files.writeString(source, "x" * 5000)
      val unbroken = new String(NativeTranscript.retained(source, 1024), UTF_8).linesIterator.toList
      assert(unbroken.size == 3 && unbroken(1).contains("\"totalBytes\":5000") && unbroken.head.forall(_ == 'x') && unbroken.last.forall(_ == 'x'))
      assert(new String(NativeTranscript.retained(source, 16), UTF_8) == "{\"type\":\"cq.truncated\",\"totalBytes\":5000,\"omittedBytes\":5000}\n")
    }

    "extract the result and the usage of a 256 MiB native stream within a 64 MiB heap" in {
      val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
      val value = new BoundedHostCommand(Map.empty, Duration.ofSeconds(120), 4096).run(Path.of("").toAbsolutePath.normalize(),
        List(java, "-Xmx64m", "-cp", System.getProperty("cq.test.classpath"), "cq.server.HarnessOutputBounds"))
      assert(value.exit == 0 && value.text.contains("STREAMED_NATIVE_OUTPUT"), value.text)
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
      override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Publication cannot integrate candidates")
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
