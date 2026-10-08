package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import java.time.Duration
import java.util.UUID
import zio.{Task, ZIO}

/** `unrun` is set when the session could not start the check's job: the evidence is then `Unknown` and names an artifact that states why. */
final case class HostValidated(evidence: ValidationEvidence, artifacts: List[ArtifactUpload], unrun: Option[String])

/** Runs one configured check on an exact commit and renders its observations; artifacts are named `label` under the `parent` attempt.
  * A failed run is repeated on the same commit until the check passes or has run `attempts` times; a run whose cleanup is unconfirmed
  * is never repeated. The evidence names the last run and records the failed runs before it. A run whose job is refused with a `Limit` fault
  * ends the check as `Unknown` with that reason instead of failing its caller.
  * `launch` runs the job to settlement and owns its workspace; the check's output is read from the payload directory afterwards. */
final class HostValidation(config: SupervisorConfig) {
  private def transcript(attempt: AttemptId, name: String, bound: Int): Array[Byte] =
    NativeTranscript.retained(config.directory.resolve("payload").resolve(attempt.value.toString).resolve(name), bound)

  def apply(parent: AttemptId, label: String, candidate: GitCommit, check: ValidationCheck,
    launch: (String, AttemptId, GitCommit, JobCommand) => Task[JobRecord]): Task[HostValidated] = {
    def attempt(number: Int, failed: List[HostValidated]): Task[HostValidated] =
      run(parent, if (number == 1) label else s"$label-r$number", candidate, check, launch).flatMap { last =>
        if (last.evidence.state == ValidationState.Failed && number < check.attempts) attempt(number + 1, failed :+ last)
        else ZIO.succeed(HostValidated(last.evidence.copy(failures = failed.map(_.evidence.artifact)), failed.flatMap(_.artifacts) ++ last.artifacts, last.unrun))
      }
    attempt(1, Nil)
  }

  private def run(parent: AttemptId, label: String, candidate: GitCommit, check: ValidationCheck,
    launch: (String, AttemptId, GitCommit, JobCommand) => Task[JobRecord]): Task[HostValidated] = for {
    id <- ZIO.succeed(AttemptId(UUID.randomUUID()))
    source = config.limits
    limits = ExecutionLimits(source.startup, Some(Duration.ofMillis(check.executionMillis)), source.heartbeat, source.grace, source.kill, check.retainedOutputBytes)
    launched <- launch(check.name, id, candidate, JobCommand(check.command, HostEnvironment.runtime(config.environment), "", limits)).map(Right(_))
      .catchSome { case DomainFailure(Fault.Limit(reason)) => ZIO.succeed(Left(s"Host check ${check.name} was not run: $reason")) }
    validated <- ZIO.attemptBlocking(launched match {
      case Left(reason) =>
        val artifact = ArtifactUpload(config.project.project, NativeArtifacts.id(parent, label), parent, ArtifactKind.Validation, "text/plain", reason)
        HostValidated(ValidationEvidence(check.name, ValidationState.Unknown, artifact.id, Nil), List(artifact), Some(reason))
      case Right(record) =>
        val project = config.project.project
        val (stdout, outParts) = NativeArtifacts.binary(project, parent, s"$label-stdout", "application/octet-stream", transcript(id, "stdout", check.retainedOutputBytes))
        val (stderr, errParts) = NativeArtifacts.binary(project, parent, s"$label-stderr", "application/octet-stream", transcript(id, "stderr", check.retainedOutputBytes))
        val observation = ValidationObservation(check, candidate, record, stdout, stderr)
        val artifact = ArtifactUpload(project, NativeArtifacts.id(parent, label), parent, ArtifactKind.Validation,
          "application/json", HostFiles.encode(ValidationObservation_JsonCodec, observation))
        val outcome = JobOutcome.observed(record)
        val state = if (outcome.succeeded) ValidationState.Passed else if (outcome.state == AttemptState.Unknown) ValidationState.Unknown else ValidationState.Failed
        HostValidated(ValidationEvidence(check.name, state, artifact.id, Nil), outParts ++ errParts :+ artifact, None)
    })
  } yield validated
}
