package cq.core

import cq.api.*

object IntegrationPolicy {
  val MaxIntentBytes = 512 * 1024
  val MaxChecks = 8

  def hold(value: IntegrationIntent): IntegrationHold =
    IntegrationHold(value.id, value.owner, value.target, value.expected, value.candidate, value.members)

  def pending(tx: LedgerTransaction, members: Set[ItemId]): List[IntegrationHold] =
    members.toList.flatMap(tx.pendingIntegration).groupBy(_.id).valuesIterator.map { copies =>
      require(copies.distinct.size == 1, "Pending integration members disagree on their reservation")
      copies.head
    }.toList.sortBy(_.id.value.toString)

  def unreserved(tx: LedgerTransaction, members: Set[ItemId]): Unit =
    pending(tx, members).headOption.foreach(value => throw DomainFailure(Fault.IntegrationPending(value.id)))

  /** The failed runs one check's evidence records: the runs before its last and, when the last failed too, that one. */
  def failedRuns(evidence: ValidationEvidence): List[ArtifactId] =
    if (evidence.state == ValidationState.Failed) evidence.failures :+ evidence.artifact else evidence.failures

  /** `candidate` is the commit that lands; with a host rebase it is the rebased commit, and the reviewed commit and the host's checks
    * of the rebased commit are cited beside the worker, the reviewer and their validation. `failed` are the runs that failed before a
    * cited passing run of the same check; they, the rebase's own and the failed runs on its earlier merge commits are cited in a second
    * evidence entry. `rounds` are the revalidation rounds of the worker's result, cited in a third whatever their outcome. */
  def completion(id: IntegrationId, repository: String, target: String, candidate: GitCommit, rebase: Option[IntegrationRebase], worker: ArtifactId,
    reviewer: ArtifactId, validation: List[ArtifactId], failed: List[ArtifactId], rounds: List[ArtifactId], fence: Fence, items: List[Item]): ChangeRequest = {
    val citations = List(Citation.Commit(repository, candidate.value)) ++ rebase.map(value => Citation.Commit(repository, value.reviewed.value)) ++
      List(Citation.Artifact(worker), Citation.Artifact(reviewer)) ++
      (validation ++ rebase.toList.flatMap(_.validation.map(_.artifact))).map(Citation.Artifact.apply)
    val evidence = Evidence(s"Host recorded integration ${id.value} into $target", EvidenceOrigin.HostObserved, citations)
    def cited(description: String, artifacts: List[ArtifactId]): List[Evidence] =
      if (artifacts.isEmpty) Nil else List(Evidence(description, EvidenceOrigin.HostObserved, artifacts.map(Citation.Artifact.apply)))
    val reruns = cited(s"Host check runs that failed before the passing runs cited for integration ${id.value}",
      failed ++ rebase.toList.flatMap(value => value.failed.flatMap(_.validation.flatMap(failedRuns)) ++ value.validation.flatMap(_.failures))) ++
      cited(s"Revalidation rounds of the checks cited for integration ${id.value}", rounds)
    val mutations = items.map { item =>
      val task = item.draft.content match {
        case value: Content.Task => value
        case _ => throw DomainFailure(Fault.Invalid("Integration completes only explicitly reviewed task members"))
      }
      LedgerPolicy.invalid(!Set[TaskStatus](TaskStatus.Done, TaskStatus.Cancelled)(task.status), "Integration member is already terminal")
      val summary = s"Integrated ${candidate.value} into $target" +
        rebase.fold("")(value => s" (host rebase of reviewed candidate ${value.reviewed.value})")
      // The worker's recorded result is retained and the integration appended (D80); a recorded result too long to extend
      // stays as recorded, since the appended validation evidence cites the same commit.
      val result = task.result.fold(summary)(recorded => if (recorded.length + summary.length + 2 <= LedgerPolicy.MaxBody) recorded + "\n\n" + summary else recorded)
      val draft = item.draft.copy(content = task.copy(status = TaskStatus.Done, result = Some(result), validation = task.validation ++ (evidence :: reruns)))
      LedgerPolicy.validate(draft)
      Mutation.Replace(item.id, item.revision, draft)
    }
    ChangeRequest(RequestId(id.value), mutations, List(fence), s"Integrate reviewed candidate ${id.value}")
  }
}
