package cq.server

import cq.api.*
import cq.host.CandidateMessage
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec

final class CandidateMessageLocal extends AnyWordSpec {
  private val project = ProjectId(UUID.randomUUID())
  private val actor = Actor("fixture", SessionId(UUID.randomUUID()), Role.Governor)
  private def id(ledger: Ledger, number: Long): ItemId = ItemId(project, ledger, number)
  private def view(item: ItemId, title: String, content: Content, refs: List[ItemRef]): ItemView =
    ItemView(Item(item, Revision(1), ItemDraft(title, "Body", Set.empty, false, content, Nil), 1, 1, Provenance(actor, 1, RequestId(UUID.randomUUID()))), refs)
  private val task = Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil)
  private val defect = Content.Defect(DefectStatus.Open, Severity.High, "Observed", "Expected", "Reproduction", None, Nil)

  "Candidate commit messages (Behavioral Active Blackbox Atomic)" should {
    "name the assigned tasks, their known producers and the attempt trailer" in {
      val attempt = AttemptId(UUID.randomUUID())
      val producer = view(id(Ledger.Defects, 77), "Worker workspaces start from the session base", defect, Nil)
      val members = List(
        view(id(Ledger.Tasks, 12), "Start  workers\nfrom the target", task, List(ItemRef(Relation.DerivedFrom, producer.item.id), ItemRef(Relation.DerivedFrom, id(Ledger.Goals, 3)))),
        view(id(Ledger.Tasks, 13), "Describe integrated commits", task, List(ItemRef(Relation.BlockedBy, id(Ledger.Tasks, 12)))))
      val message = CandidateMessage(attempt, members, List(producer), None)
      assert(message ==
        s"""T12 Start workers from the target; T13 Describe integrated commits
           |
           |Assignment:
           |- T12 Start workers from the target (derived from D77 Worker workspaces start from the session base, G3)
           |- T13 Describe integrated commits
           |
           |CQ-Attempt: ${attempt.value}
           |""".stripMargin)
    }

    "name the source integration of a combined candidate" in {
      val attempt = AttemptId(UUID.randomUUID())
      val source = IntegrationId(UUID.randomUUID())
      val plan = CombinationPlan(CombinationTicket(RequestId(UUID.randomUUID()), source, Fence(ClaimId(UUID.randomUUID()), 1)), project, actor,
        AttemptId(UUID.randomUUID()), "/repository", "refs/heads/main", GitCommit("a" * 40), GitCommit("b" * 40), ArtifactId(UUID.randomUUID()),
        List(ItemRevision(id(Ledger.Tasks, 12), Revision(1))))
      val message = CandidateMessage(attempt, List(view(id(Ledger.Tasks, 12), "Start workers from the target", task, Nil)), Nil, Some(plan))
      assert(message ==
        s"""T12 Start workers from the target
           |
           |Combines integration target ${"a" * 40} with candidate ${"b" * 40} after integration ${source.value} was not applied.
           |
           |Assignment:
           |- T12 Start workers from the target
           |
           |CQ-Attempt: ${attempt.value}
           |CQ-Integration: ${source.value}
           |""".stripMargin)
    }

    "bound long titles and subjects" in {
      val long = "x" * 300
      val members = List.fill(3)(view(id(Ledger.Tasks, 1), long, task, Nil))
      val message = CandidateMessage(AttemptId(UUID.randomUUID()), members, Nil, None)
      val subject = message.linesIterator.next()
      assert(subject.length == 200 && subject.endsWith("...") && message.contains("- T1 " + "x" * 69 + "..."))
      intercept[IllegalArgumentException](CandidateMessage(AttemptId(UUID.randomUUID()), Nil, Nil, None))
    }
  }
}
