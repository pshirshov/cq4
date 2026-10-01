package cq.server

import cq.api.*
import cq.host.*
import java.time.Duration
import java.util.UUID
import zio.{Task, ZIO}

final case class HostValidated(evidence: ValidationEvidence, artifacts: List[ArtifactUpload])

/** Runs one configured check on an exact commit and renders its observation; artifacts are named `label` under the `parent` attempt.
  * `launch` runs the job to settlement and owns its workspace; the check's output is read from the payload directory afterwards. */
final class HostValidation(config: SupervisorConfig) {
  private def transcript(attempt: AttemptId, name: String, bound: Int): Array[Byte] =
    NativeTranscript.retained(config.directory.resolve("payload").resolve(attempt.value.toString).resolve(name), bound)

  def apply(parent: AttemptId, label: String, candidate: GitCommit, check: ValidationCheck,
    launch: (AttemptId, GitCommit, JobCommand) => Task[JobRecord]): Task[HostValidated] = for {
    id <- ZIO.succeed(AttemptId(UUID.randomUUID()))
    source = config.limits
    limits = ExecutionLimits(source.startup, Some(Duration.ofMillis(check.executionMillis)), source.heartbeat, source.grace, source.kill, check.retainedOutputBytes)
    record <- launch(id, candidate, JobCommand(check.command, HostEnvironment.runtime(config.environment), "", limits))
    validated <- ZIO.attemptBlocking {
      val project = config.project.project
      val (stdout, outParts) = NativeArtifacts.binary(project, parent, s"$label-stdout", "application/octet-stream", transcript(id, "stdout", check.retainedOutputBytes))
      val (stderr, errParts) = NativeArtifacts.binary(project, parent, s"$label-stderr", "application/octet-stream", transcript(id, "stderr", check.retainedOutputBytes))
      val observation = ValidationObservation(check, candidate, record, stdout, stderr)
      val artifact = ArtifactUpload(project, NativeArtifacts.id(parent, label), parent, ArtifactKind.Validation,
        "application/json", HostFiles.encode(ValidationObservation_JsonCodec, observation))
      val outcome = JobOutcome.observed(record)
      val state = if (outcome.succeeded) ValidationState.Passed else if (outcome.state == AttemptState.Unknown) ValidationState.Unknown else ValidationState.Failed
      HostValidated(ValidationEvidence(check.name, state, artifact.id), outParts ++ errParts :+ artifact)
    }
  } yield validated
}
