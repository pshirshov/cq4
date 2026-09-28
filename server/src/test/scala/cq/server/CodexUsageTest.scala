package cq.server

import cq.api.*
import cq.core.UsageMath
import cq.host.*
import io.circe.Json
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

final class CodexUsageFixture(val root: Path) extends AutoCloseable {
  val thread = UUID.fromString("01a0ea30-6d5b-7581-8c72-83117c61ac2d")
  val turn = UUID.fromString("01a0ea30-6ddb-7c62-997e-64936096283b")
  val sessions = Files.createDirectory(root.resolve("sessions"))
  val day = Files.createDirectories(sessions.resolve("2026/09/28"))
  val file = day.resolve(s"rollout-fixture-$thread.jsonl")
  val binding = CodexUsageBinding(thread, "0.156.1", sessions.toString)
  val metadata = Json.obj("threadId" -> Json.fromString(thread.toString), "x-codex-turn-metadata" ->
    Json.obj("thread_id" -> Json.fromString(thread.toString), "codex_version" -> Json.fromString(binding.version)))
  val start = 1790635400000L
  val clock: Clock = Clock.fixed(Instant.ofEpochMilli(start + 1000), ZoneOffset.UTC)
  def sample(response: String, position: Long): CodexUsageSample = CodexUsageSample(thread, turn, response, position, start + position,
    Some("gpt-6-sol"), Some("openai"), TokenCounts(Counter(Some(100), Measurement.Observed), Counter(Some(30), Measurement.Observed),
      Counter(Some(20), Measurement.Observed), Counter(Some(0), Measurement.Observed), Counter(Some(8), Measurement.Observed)))
  def append(json: Json): Unit = Files.writeString(file, json.noSpaces + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND): Unit
  append(Json.obj("type" -> Json.fromString("session_meta"), "payload" -> Json.obj("id" -> Json.fromString(thread.toString),
    "cli_version" -> Json.fromString(binding.version), "model_provider" -> Json.fromString("openai"))))
  append(Json.obj("type" -> Json.fromString("turn_context"), "payload" -> Json.obj("turn_id" -> Json.fromString(turn.toString), "model" -> Json.fromString("gpt-6-sol"))))
  def event(value: CodexUsageSample): Json = {
    def count(value: Counter): Json = value.value.fold(Json.Null)(Json.fromLong)
    Json.obj("type" -> Json.fromString("token_usage_record"), "ordinal" -> Json.fromLong(value.position),
      "timestamp" -> Json.fromString(Instant.ofEpochMilli(value.occurredAt).toString), "payload" -> Json.obj(
        "thread_id" -> Json.fromString(value.thread.toString), "turn_id" -> Json.fromString(value.turn.toString), "response_id" -> Json.fromString(value.response),
        "usage" -> Json.obj("input_tokens" -> count(value.counters.input), "output_tokens" -> count(value.counters.output),
          "cached_input_tokens" -> count(value.counters.cacheRead), "cache_write_input_tokens" -> count(value.counters.cacheWrite),
          "reasoning_output_tokens" -> count(value.counters.reasoning)),
        "thread_token_usage" -> Json.obj("input_tokens" -> Json.fromLong(900000)), "turn_token_usage" -> Json.obj("input_tokens" -> Json.fromLong(400000))))
  }
  def run: SupervisorRun = {
    val project = ProjectConfig(ProjectId(UUID.randomUUID()), "http://localhost", "Usage fixture")
    val assignment = Assignment(AssignmentId(UUID.randomUUID()), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(UUID.randomUUID()), assignment.id, None, SessionId(UUID.randomUUID()), Role.Governor, Harness.Codex,
      "unobserved-interactive-provider", "unobserved-interactive-model", "fixture", start)
    SupervisorRun(project, assignment, attempt, binding.version, root.toString, GitCommit("a" * 40), SessionOwnership.Attached)
  }
  override def close(): Unit = Using.resource(Files.walk(root))(_.iterator().asScala.toList.reverse.foreach(Files.delete))
}

final class CodexUsageLocal extends AnyWordSpec {
  private def fixture[A](run: CodexUsageFixture => A): A = Using.resource(new CodexUsageFixture(Files.createTempDirectory("cq-codex-usage-")))(run)
  private final class Receiver extends ServerApi {
    var observations = Map.empty[ObservationId, UsageUpload]
    var lose = false
    override def usage(value: HostUsageInput): HostUsageResult = value.operation match {
      case HostUsage.Meter(meter) => HostUsageResult.Metered(meter)
      case HostUsage.Ingest(upload) =>
        require(observations.get(upload.observation.id).forall(_ == upload), "Conflicting observation replay")
        observations += upload.observation.id -> upload
        if (lose) { lose = false; throw new java.io.IOException("Lost recorded usage acknowledgement") }
        HostUsageResult.Ingested(UsageReceipt(upload.observation.id, observations.size.toLong))
      case _ => throw new IllegalArgumentException("Unexpected usage operation")
    }
    override def artifact(value: ArtifactUpload): ArtifactMetadata = ArtifactMetadata(value.project, value.id, value.attempt, value.kind, value.mediaType,
      "fixture", value.body.length, value.body.length, Actor("fixture", SessionId(UUID.randomUUID()), Role.Collector), 1)
    override def call(value: Command): Result = throw new IllegalStateException("Not a query")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Not a grant")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Not an admission")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Not an integration")
  }
  private final class DummySource extends CodexUsageSource {
    var samples = List.empty[CodexUsageSample]
    override def poll(binding: CodexUsageBinding): CodexUsagePage = {
      val result = samples
      samples = Nil
      CodexUsagePage(result, true, Some(CodexUsageFile("dummy", "dummy")), false)
    }
  }
  "Attached Codex accounting (Behavioral Active Blackbox; dummy Group / filesystem Communication)" should {
    "replay already owned samples after the wall clock moves backwards before shutdown" in fixture { f =>
      val receiver = new Receiver
      val run = f.run
      val directory = f.root.resolve("cq")
      f.append(f.event(f.sample("before-clock-change", 1).copy(occurredAt = f.start + 2000)))
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions); observer.poll(receiver); observer.finish(receiver)
      }
      val receipt = Using.resource(Files.walk(directory.resolve("codex-usage/samples")))(_.iterator().asScala.find(_.getFileName.toString.endsWith(".ack")).get)
      Files.delete(receipt)
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { recovered =>
        assert(recovered.recover(receiver).acknowledged == 1)
        assert(receiver.observations.size == 1)
      }
    }
    "recover a durably claimed response after local sample publication fails" in fixture { f =>
      val receiver = new Receiver
      val run = f.run
      val directory = f.root.resolve("cq")
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions)
        observer.poll(receiver)
        Files.writeString(directory.resolve("codex-usage/samples"), "Fixture blocks sample directory creation")
        f.append(f.event(f.sample("claimed", 1)))
        assert(scala.util.Try(observer.poll(receiver)).isFailure)
        assert(Using.resource(Files.list(f.root.resolve(s"cq-usage/${f.thread}/responses")))(_.count()) == 1)
        Files.delete(directory.resolve("codex-usage/samples"))
      }
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { recovered =>
        recovered.recover(receiver)
        assert(receiver.observations.size == 1, "Durably claimed response disappeared during abrupt recovery")
      }
    }
    "reject conflicting repeated responses across sequential native observers" in fixture { f =>
      val receiver = new Receiver
      val first = new DummySource
      first.samples = List(f.sample("response", 1))
      Using.resource(new AttachedCodexUsage(f.root.resolve("one"), f.run, first, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions); observer.poll(receiver)
      }
      val second = new DummySource
      val value = f.sample("response", 2)
      second.samples = List(value.copy(counters = value.counters.copy(input = Counter(Some(200), Measurement.Observed))))
      Using.resource(new AttachedCodexUsage(f.root.resolve("two"), f.run, second, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions)
        intercept[IllegalArgumentException](observer.poll(receiver))
      }
    }
    List("dummy", "rollout").foreach { adapter =>
      s"deduplicate response records and replay lost acknowledgement with $adapter source" in fixture { f =>
        val run = f.run
        val directory = f.root.resolve("cq")
        val receiver = new Receiver
        val dummy = new DummySource
        val source = if (adapter == "dummy") dummy else new CodexRollout
        val first = f.sample("first", 1)
        val second = f.sample("second", 3)
        val input = List(first.copy(response = "before", occurredAt = f.start - 1), first, first.copy(position = 2), second)
        if (adapter == "dummy") dummy.samples = input else input.foreach(value => f.append(f.event(value)))
        Using.resource(new AttachedCodexUsage(directory, run, source, f.clock)) { observer =>
          observer.observe(Some(f.metadata), f.sessions)
          receiver.lose = true
          intercept[java.io.IOException](observer.poll(receiver))
          observer.poll(receiver)
          assert(receiver.observations.size == 2)
          val counters = receiver.observations.values.map(_.observation.counters).toList
          assert(counters.map(_.input.value.get).sum == 200 && counters.map(_.output.value.get).sum == 60)
          assert(receiver.observations.values.forall(value => value.observation.completeness == UsageCompleteness.Partial && value.observation.cost.basis == CostBasis.Unknown))
          observer.finish(receiver)
        }
        Using.resource(new AttachedCodexUsage(directory, run, if (adapter == "dummy") dummy else new CodexRollout, f.clock)) { recovered =>
          assert(recovered.recover(receiver).acknowledged == 0)
          assert(receiver.observations.size == 2)
        }
      }
    }
    "exclude overlapping native owners and preserve response ownership across reconnect and clock rollback" in fixture { f =>
      val receiver = new Receiver
      val first = f.run
      val secondRun = f.run
      val second = secondRun.copy(attempt = secondRun.attempt.copy(startedAt = f.start - 1000))
      f.append(f.event(f.sample("first", 1)))
      val one = new AttachedCodexUsage(f.root.resolve("one"), first, new CodexRollout, f.clock)
      val two = new AttachedCodexUsage(f.root.resolve("two"), second, new CodexRollout, f.clock)
      try {
        one.observe(Some(f.metadata), f.sessions)
        assert(scala.util.Try(two.observe(Some(f.metadata), f.sessions)).isFailure)
        one.poll(receiver)
        one.finish(receiver)
        one.close()
        two.observe(Some(f.metadata), f.sessions)
        two.poll(receiver)
        assert(receiver.observations.size == 1)
        f.append(f.event(f.sample("second", 2)))
        two.poll(receiver)
        assert(receiver.observations.size == 2)
        assert(receiver.observations.values.count(_.observation.attempt == first.attempt.id) == 1)
      } finally { one.close(); two.close() }
    }
    "replay retained samples only after an abrupt owner loss without an end window" in fixture { f =>
      val receiver = new Receiver
      val run = f.run
      val directory = f.root.resolve("cq")
      f.append(f.event(f.sample("first", 1)))
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions); observer.poll(receiver)
      }
      f.append(f.event(f.sample("after-death", 2)))
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { recovered =>
        assert(recovered.recover(receiver).acknowledged == 0)
        assert(receiver.observations.size == 1)
      }
    }
    "collect delayed flushed records within the closed window and exclude records at its end" in fixture { f =>
      val receiver = new Receiver
      val run = f.run
      val directory = f.root.resolve("cq")
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { observer =>
        observer.observe(Some(f.metadata), f.sessions); observer.finish(receiver)
      }
      f.append(f.event(f.sample("late-flush", 1)))
      f.append(f.event(f.sample("next-session", 2).copy(occurredAt = f.clock.millis())))
      Using.resource(new AttachedCodexUsage(directory, run, new CodexRollout, f.clock)) { recovered =>
        assert(recovered.recover(receiver).acknowledged == 1)
        assert(receiver.observations.size == 1)
      }
    }
  }

  "Native Codex rollout (Behavioral Active Effectual filesystem Communication)" should {
    "read per-response increments once, wait for a trailing line and exclude foreign thread records" in fixture { f =>
      val source = new CodexRollout
      val sample = f.sample("first", 1)
      val encoded = f.event(sample).noSpaces
      Files.writeString(f.file, encoded.take(30), StandardOpenOption.APPEND)
      val initial = source.poll(f.binding)
      assert(initial.samples.isEmpty && initial.pendingLine)
      Files.writeString(f.file, encoded.drop(30) + "\n", StandardOpenOption.APPEND)
      f.append(f.event(sample.copy(thread = UUID.randomUUID(), response = "foreign")))
      f.append(Json.obj("type" -> Json.fromString("event_msg"), "payload" -> Json.obj("type" -> Json.fromString("token_count"), "info" -> Json.obj())))
      assert(source.poll(f.binding).samples == List(sample))
      assert(source.poll(f.binding).samples.isEmpty)
    }
    "reject ambiguous and replaced rollout identity and invalid numeric counters" in fixture { f =>
      val duplicate = f.day.resolve(s"rollout-duplicate-${f.thread}.jsonl")
      Files.copy(f.file, duplicate)
      intercept[IllegalArgumentException](new CodexRollout().poll(f.binding))
      Files.delete(duplicate)
      val source = new CodexRollout
      source.poll(f.binding)
      val previous = Files.readAllBytes(f.file)
      val replacement = f.day.resolve("replacement")
      Files.write(replacement, previous)
      Files.move(replacement, f.file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
      assert(scala.util.Try(source.poll(f.binding)).isFailure)
      val invalid = f.sample("invalid", 1).copy(counters = f.sample("invalid", 1).counters.copy(input = Counter(Some(-1), Measurement.Observed)))
      f.append(f.event(invalid))
      intercept[IllegalArgumentException](new CodexRollout().poll(f.binding))
    }
  }
}
