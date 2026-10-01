package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import zio.{Task, ZIO}

final case class PreparedRebase(outcome: RebaseOutcome, applied: Option[AppliedRebase])

/** Merges a reviewed candidate onto an advanced integration target and reruns every configured check on exactly the merged commit.
  * The observations are published under the governing attempt before the outcome is returned. */
final class RebasePreparation(config: SupervisorConfig, authority: SupervisorAuthority, candidates: CandidateWorkspace) {
  private val validation = new HostValidation(config)
  /** The preparations of this session whose checks failed, by reviewed candidate and target head. Worker and reviewer results are
    * bound to their governing session, so no other session prepares the same pair. */
  private var failed = Map.empty[(GitCommit, GitCommit), List[(IntegrationId, RebaseAttempt)]]

  def apply(id: IntegrationId, target: String, reviewed: GitCommit, launch: (AttemptId, GitCommit, JobCommand) => Task[JobRecord]): Task[PreparedRebase] =
    ZIO.attemptBlocking(candidates.advanced(reviewed)).flatMap {
      case None => ZIO.succeed(PreparedRebase(RebaseOutcome.Unneeded(), None))
      case Some(head) if config.settings.checks.isEmpty =>
        ZIO.succeed(PreparedRebase(RebaseOutcome.Refused(head, "No configured check would verify the rebased commit"), None))
      case Some(head) =>
        val earlier = synchronized(failed.getOrElse((reviewed, head), Nil))
        IntegrationValidation.exhausted(earlier.map(_._2), config.settings.checks) match {
          case Some(check) => ZIO.fail(DomainFailure(Fault.Limit(RebasePreparation.exhausted(earlier.last._1, check, reviewed, head))))
          case None => ZIO.attemptBlocking(candidates.rebase(head, reviewed, id,
            CandidateMessage.rebased(id, target, head, reviewed, candidates.subject(reviewed)))).flatMap {
            case HostRebase.Conflicted => ZIO.succeed(PreparedRebase(RebaseOutcome.Conflicted(head), None))
            case HostRebase.Refused(reason) => ZIO.succeed(PreparedRebase(RebaseOutcome.Refused(head, reason), None))
            case HostRebase.Merged(commit) =>
              val author = config.run.attempt.id
              val label = "rebase-" + id.value.toString.replace("-", "")
              for {
                results <- ZIO.foreach(config.settings.checks.zipWithIndex) { case (check, index) => validation(author, s"$label-$index", commit, check, launch) }
                _ <- ZIO.attemptBlocking(results.flatMap(_.artifacts).foreach(authority.collector.artifact))
                evidence = results.map(_.evidence)
                _ <- ZIO.attempt(require(!evidence.exists(_.state == ValidationState.Unknown), results.flatMap(_.unrun).headOption.getOrElse("Host validation cleanup is unconfirmed")))
                passed = evidence.forall(_.state == ValidationState.Passed)
                _ <- ZIO.succeed(synchronized { if (!passed) failed = failed.updated((reviewed, head), earlier :+ (id, RebaseAttempt(commit, evidence))) })
              } yield
                if (passed) PreparedRebase(RebaseOutcome.Applied(reviewed),
                  Some(AppliedRebase(head, commit, IntegrationRebase(reviewed, author, evidence, earlier.map(_._2)))))
                else PreparedRebase(RebaseOutcome.ChecksFailed(head, evidence), None)
          }
        }
    }
}

object RebasePreparation {
  /** The instruction comes first: a status blocker keeps only the beginning of a long message. */
  def exhausted(last: IntegrationId, check: ValidationCheck, reviewed: GitCommit, head: GitCommit): String =
    s"Rebase check limit reached; Integrate ${last.value} to record NotApplied, then Combine: check ${check.name} has used its " +
      s"${check.revalidations} revalidation rounds on ${reviewed.value} at target ${head.value}"

  /** Why the frozen intent still names the reviewed candidate although the target advanced, and what the governor does next. */
  def blocker(outcome: RebaseOutcome): Option[String] = {
    def advanced(target: GitCommit, cause: String): Option[String] =
      Some(s"Target advanced to ${target.value}; $cause; Integrate records NotApplied, then Combine")
    outcome match {
      case _: RebaseOutcome.Unneeded | _: RebaseOutcome.Applied => None
      case RebaseOutcome.Conflicted(target) => advanced(target, "conflict")
      case RebaseOutcome.ChecksFailed(target, validation) =>
        advanced(target, validation.filter(_.state != ValidationState.Passed).map(_.check).mkString("check ", ", ", " failed on the rebased commit"))
      case RebaseOutcome.Refused(target, reason) => advanced(target, "host rebase refused: " + reason)
    }
  }
}
