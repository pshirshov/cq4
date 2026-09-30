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

  def completion(id: IntegrationId, repository: String, target: String, candidate: GitCommit, worker: ArtifactId,
    reviewer: ArtifactId, validation: List[ArtifactId], fence: Fence, items: List[Item]): ChangeRequest = {
    val citations = List(Citation.Commit(repository, candidate.value), Citation.Artifact(worker), Citation.Artifact(reviewer)) ++
      validation.map(Citation.Artifact.apply)
    val evidence = Evidence(s"Host recorded integration ${id.value} into $target", EvidenceOrigin.HostObserved, citations)
    val mutations = items.map { item =>
      val task = item.draft.content match {
        case value: Content.Task => value
        case _ => throw DomainFailure(Fault.Invalid("Integration completes only explicitly reviewed task members"))
      }
      LedgerPolicy.invalid(!Set[TaskStatus](TaskStatus.Done, TaskStatus.Cancelled)(task.status), "Integration member is already terminal")
      val summary = s"Integrated ${candidate.value} into $target"
      // The worker's recorded result is retained and the integration appended (D80); a recorded result too long to extend
      // stays as recorded, since the appended validation evidence cites the same commit.
      val result = task.result.fold(summary)(recorded => if (recorded.length + summary.length + 2 <= LedgerPolicy.MaxBody) recorded + "\n\n" + summary else recorded)
      val draft = item.draft.copy(content = task.copy(status = TaskStatus.Done, result = Some(result), validation = task.validation :+ evidence))
      LedgerPolicy.validate(draft)
      Mutation.Replace(item.id, item.revision, draft)
    }
    ChangeRequest(RequestId(id.value), mutations, List(fence), s"Integrate reviewed candidate ${id.value}")
  }
}
