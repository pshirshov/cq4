package cq.server

import cq.api.*
import cq.host.*
import java.time.Duration
import java.util.UUID
import zio.{Task, ZIO}

final case class HostValidated(evidence: ValidationEvidence, artifacts: List[ArtifactUpload])

/** Runs one configured check on an exact commit and renders its observations; artifacts are named `label` under the `parent` attempt.
  * A failed run is repeated on the same commit until the check passes or has run `attempts` times; a run whose cleanup is unconfirmed
  * is never repeated. The evidence names the last run and records the failed runs before it.
  * `launch` runs the job to settlement and owns its workspace; the check's output is read from the payload directory afterwards. */
final class HostValidation(config: SupervisorConfig) {
  private def transcript(attempt: AttemptId, name: String, bound: Int): Array[Byte] =
    NativeTranscript.retained(config.directory.resolve("payload").resolve(attempt.value.toString).resolve(name), bound)

  def apply(parent: AttemptId, label: String, candidate: GitCommit, check: ValidationCheck,
    launch: (AttemptId, GitCommit, JobCommand) => Task[JobRecord]): Task[HostValidated] = {
    def attempt(number: Int, failed: List[HostValidated]): Task[HostValidated] =
      run(parent, if (number == 1) label else s"$label-r$number", candidate, check, launch).flatMap { last =>
        if (last.evidence.state == ValidationState.Failed && number < check.attempts) attempt(number + 1, failed :+ last)
        else ZIO.succeed(HostValidated(last.evidence.copy(failures = failed.map(_.evidence.artifact)), failed.flatMap(_.artifacts) ++ last.artifacts))
      }
    attempt(1, Nil)
  }

  private def run(parent: AttemptId, label: String, candidate: GitCommit, check: ValidationCheck,
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
      HostValidated(ValidationEvidence(check.name, state, artifact.id, Nil), outParts ++ errParts :+ artifact)
    }
  } yield validated
}
