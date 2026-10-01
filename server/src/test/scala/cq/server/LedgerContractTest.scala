package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import zio.{IO, ZIO}

abstract class LedgerContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )

  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Governor))
  private def task(title: String): ItemDraft = ItemDraft(title, "Markdown λ", Set("test"), false, Content.Task(TaskStatus.Ready, List("Observable result"), None, Nil), Nil)
  private def request(mutations: List[Mutation], fences: List[Fence]): ChangeRequest = ChangeRequest(RequestId(UUID.randomUUID()), mutations, fences, "Contract scenario")
  private def create(service: LedgerService[IO], scope: Scope, draft: ItemDraft): IO[Throwable, ItemRevision] =
    service.change(scope, request(List(Mutation.Create(draft)), Nil)).map(_.items.head)
  private def denied[A](effect: IO[Throwable, A])(expected: Fault => Boolean): IO[Throwable, Unit] =
    effect.either.flatMap(result => assertIO(result match { case Left(DomainFailure(fault)) => expected(fault); case _ => false })).unit

  "Durable ledger service (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "keep accepted ideas active until implemented and forbid premature archival" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val accepted = task("Accepted idea").copy(content = Content.Idea(IdeaStatus.Accepted, "Deliver outcome", "Motivation"))
      for {
        _ <- service.initialize(owner, "idea lifecycle")
        first <- create(service, owner, accepted)
        active <- service.search(owner, "ledger:Ideas", None, 200)
        _ <- assertIO(active.items.head.outcome == ItemOutcome(false, false))
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(create(service, owner, accepted.copy(archived = true)))(_.isInstanceOf[Fault.Invalid])
        implemented = IdeaStatus.Implemented
        completed = accepted.copy(content = Content.Idea(implemented, "Delivered outcome", "Motivation"))
        changed <- service.change(owner, request(List(Mutation.Replace(first.id, first.revision, completed)), Nil))
        done <- service.search(owner, "ledger:Ideas status:Implemented", None, 200)
        _ <- assertIO(done.items.head.outcome == ItemOutcome(true, true))
        archived <- service.change(owner, request(List(Mutation.Archive(changed.items)), Nil))
        _ <- denied(service.change(owner, request(List(Mutation.Replace(first.id, archived.items.head.revision, accepted.copy(archived = true))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- ZIO.foreachDiscard(List(IdeaStatus.Declined, IdeaStatus.Withdrawn)) { status =>
          create(service, owner, accepted.copy(archived = true, content = Content.Idea(status, "Not pursued", "Motivation")))
        }
        terminal <- service.search(owner, "ledger:Ideas archived:true", None, 200)
        _ <- assertIO(terminal.items.size == 3 && terminal.items.forall(_.outcome.terminal) && terminal.items.count(_.outcome.satisfiesDependency) == 1)
      } yield ()
    }

    "create all fourteen typed ledgers and preserve project identity through reattachment" in { (service: LedgerService[IO]) =>
      val first = scope()
      val second = scope()
      val contents: List[Content] = List(
        Content.Milestone(MilestoneStatus.Open, "Deliver the release"),
        Content.Idea(IdeaStatus.Proposed, "Useful outcome", "User motivation"),
        Content.Defect(DefectStatus.Open, Severity.High, "Actual output", "Expected output", "Exact reproduction", None, Nil),
        Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"),
        Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil),
        Content.Research(ResearchStatus.Open, "Empirical uncertainty", Nil, None, None),
        Content.Hypothesis(HypothesisStatus.Proposed, "Falsifiable claim", "Rationale", Nil, None),
        Content.Question(QuestionStatus.Open, "Preference?", "Context", List("A", "B"), Some(QuestionRecommendation(0, "A needs no migration")), None),
        Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", List("Alternative")),
        Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None),
        Content.Handoff(HandoffStatus.Open, "Result so far", List("Remaining"), Nil),
        Content.OperatorAction(OperatorActionStatus.Requested, "Operator action", "Observable evidence", None, Nil),
        Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil),
        Content.Upstream(UpstreamStatus.Identified, "component", "1.2.3", "Reproduction", None, None),
      )
      for {
        initialized <- service.initialize(first, "first")
        attached <- service.initialize(first, "renamed directory")
        _ <- assertIO(initialized == attached && attached.name == "first")
        _ <- service.initialize(second, "second")
        created <- ZIO.foreach(contents)(c => create(service, first, task(c.getClass.getSimpleName).copy(content = c)))
        _ <- assertIO(created.map(_.id.ledger).toSet == Ledger.all.toSet && created.forall(_.id.number == 1))
        other <- create(service, second, task("Other project"))
        _ <- assertIO(other.id.number == 1 && other.id.project != first.project)
        _ <- denied(service.get(first, other.id))(_.isInstanceOf[Fault.Denied])
        worker = first.copy(actor = first.actor.copy(role = Role.Worker))
        _ <- denied(create(service, worker, task("Forbidden")))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "allocate independently under concurrency and replay acknowledgements without duplicate effects" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val another = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val sameRequest = request(List(Mutation.Create(task("Retry"))), Nil)
      for {
        _ <- service.initialize(owner, "concurrency")
        results <- ZIO.foreachPar((1 to 16).toList)(n => create(service, if (n % 2 == 0) owner else another, task(s"Task $n")))
        _ <- assertIO(results.map(_.id.number).toSet == (1L to 16L).toSet)
        retries <- ZIO.foreachPar((1 to 8).toList)(_ => service.change(owner, sameRequest))
        _ <- assertIO(retries.distinct.size == 1 && retries.head.items.head.id.number == 17)
        _ <- denied(service.change(owner, sameRequest.copy(reason = "Changed payload")))(_.isInstanceOf[Fault.Conflict])
        replay <- service.changes(owner, ChangeCursor(0), 200)
        _ <- assertIO(replay.events.size == 17 && replay.events.map(_.cursor.value) == (1L to 17L).toList)
        history <- service.history(owner, retries.head.items.head.id, Revision(Long.MaxValue), 200)
        _ <- assertIO(history.entries.size == 1)
      } yield ()
    }

    "distinguish a missing change reason from an over-long one" in { (service: LedgerService[IO]) =>
      val owner = scope()
      def withReason(reason: String): ChangeRequest = request(List(Mutation.Create(task("Reason bounds"))), Nil).copy(reason = reason)
      for {
        _ <- service.initialize(owner, "reason bounds")
        _ <- denied(service.change(owner, withReason("")))(_ == Fault.Invalid("Mutation reason required"))
        _ <- denied(service.change(owner, withReason("   \t ")))(_ == Fault.Invalid("Mutation reason required"))
        _ <- denied(service.change(owner, withReason("r" * 301))) {
          case Fault.Invalid(message) => message == "Mutation reason must be at most 300 characters; received 301" && !message.contains("required")
          case _ => false
        }
        accepted <- service.change(owner, withReason("r" * 300))
        _ <- assertIO(accepted.items.size == 1)
      } yield ()
    }

    "reject reordered mutations on immutable request replay without repeating side effects" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val ordered = request(List(Mutation.Create(task("First")), Mutation.Create(task("Second"))), Nil)
      for {
        _ <- service.initialize(owner, "ordered-request")
        first <- service.change(owner, ordered)
        _ <- denied(service.change(owner, ordered.copy(mutations = ordered.mutations.reverse)))(_.isInstanceOf[Fault.Conflict])
        replay <- service.change(owner, ordered)
        events <- service.changes(owner, ChangeCursor(0), 200)
        _ <- assertIO(replay == first && events.events.map(_.items) == List(first.items) && first.items.map(_.id.number) == List(1L, 2L))
      } yield ()
    }

    "reject fabricated observation provenance and invalid nested content without allocating items" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val invalidDrafts = List(
        task("Fabricated host observation").copy(content = Content.Task(TaskStatus.Ready, List("Acceptance"), None,
          List(Evidence("Tests passed", EvidenceOrigin.HostObserved, Nil)))),
        task("Fabricated human report").copy(content = Content.Task(TaskStatus.Ready, List("Acceptance"), None,
          List(Evidence("User approved", EvidenceOrigin.HumanReported, Nil)))),
        task("Empty evidence").copy(content = Content.Task(TaskStatus.Ready, List("Acceptance"), None,
          List(Evidence("", EvidenceOrigin.ModelDeclared, Nil)))),
        task("Invalid URL").copy(citations = List(Citation.Url("javascript:alert(1)"))),
        task("Empty file path").copy(citations = List(Citation.File("", None))),
        task("Invalid commit").copy(citations = List(Citation.Commit("consumer", "not-a-commit"))),
        task("Missing reviewed revision").copy(content = Content.Review(ReviewStatus.Pending,
          List(ReviewedItem(ItemId(owner.project, Ledger.Tasks, 999), Revision(1))), None, Nil, None)),
        task("Oversized optional narrative").copy(content = Content.Task(TaskStatus.Ready, List("Acceptance"), Some("x" * 65537), Nil)),
      )
      for {
        _ <- service.initialize(owner, "nested validation")
        results <- ZIO.foreach(invalidDrafts)(draft => create(service, owner, draft).either)
        _ <- assertIO(results.forall(_.isLeft))
        snapshot <- service.search(owner, "archived:all", None, 200)
        _ <- assertIO(snapshot.items.isEmpty && snapshot.cursor.value == 0)
        human = owner.copy(actor = owner.actor.copy(role = Role.Human))
        accepted = task("Human report").copy(content = Content.Task(TaskStatus.Ready, List("Acceptance"), None,
          List(Evidence("User ran the check", EvidenceOrigin.HumanReported, List(Citation.Url("https://example.com/result"))))))
        first <- create(service, human, accepted)
        _ <- assertIO(first.id.number == 1)
        _ <- service.change(owner, request(List(Mutation.Replace(first.id, first.revision, accepted.copy(title = "Preserved report"))), Nil))
      } yield ()
    }

    "keep human confirmation bound to the operator action and expected evidence" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val action = Content.OperatorAction(OperatorActionStatus.Confirmed, "Deploy candidate A", "Candidate A is reachable", Some("Approved"), Nil)
      val draft = task("Operator action").copy(content = action)
      for {
        _ <- service.initialize(owner, "confirmation applicability")
        created <- create(service, human, draft)
        _ <- denied(service.change(owner, request(List(Mutation.Replace(created.id, Revision(1), draft.copy(content = action.copy(action = "Deploy candidate B")))), Nil)))(_.isInstanceOf[Fault.Denied])
        _ <- denied(service.change(owner, request(List(Mutation.Replace(created.id, Revision(1), draft.copy(content = action.copy(expectedEvidence = "Candidate B is reachable")))), Nil)))(_.isInstanceOf[Fault.Denied])
        _ <- service.change(owner, request(List(Mutation.Replace(created.id, Revision(1), draft.copy(title = "Editorial correction"))), Nil))
        _ <- service.change(owner, request(List(Mutation.Replace(created.id, Revision(2), draft.copy(content = action.copy(action = "Deploy candidate B", confirmation = None)))), Nil))
      } yield ()
    }

    "roll back an entire batch and allow archive, status correction and restore as a new revision" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val initial = task("Task")
      for {
        _ <- service.initialize(owner, "rollback")
        first <- create(service, owner, initial)
        failed = request(List(Mutation.Create(task("Must roll back")), Mutation.Replace(first.id, Revision(9), initial)), Nil)
        _ <- denied(service.change(owner, failed))(_.isInstanceOf[Fault.Conflict])
        afterFailure <- service.search(owner, "archived:all", None, 200)
        _ <- assertIO(afterFailure.items.size == 1 && afterFailure.cursor.value == 1)
        archived = initial.copy(archived = true, content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Done"), Nil))
        _ <- service.change(owner, request(List(Mutation.Replace(first.id, Revision(1), archived)), Nil))
        hidden <- service.search(owner, "", None, 200)
        _ <- assertIO(hidden.items.isEmpty)
        direct <- service.get(owner, first.id)
        _ <- assertIO(direct.item.draft.archived)
        _ <- service.change(owner, request(List(Mutation.Restore(first.id, Revision(2), Revision(1), Nil)), Nil))
        restored <- service.get(owner, first.id)
        _ <- assertIO(restored.item.revision.value == 3 && restored.item.draft == initial)
        entries <- service.history(owner, first.id, Revision(Long.MaxValue), 2)
        _ <- assertIO(entries.hasMore && entries.entries.map(_.item.item.revision.value) == List(3L, 2L))
        older <- service.history(owner, first.id, Revision(2), 2)
        _ <- assertIO(!older.hasMore && older.entries.head.item.item.draft == initial)
      } yield ()
    }

    "accept operator-created ideas, defects and goals with empty narrative fields" in { (service: LedgerService[IO]) =>
      val owner = scope()
      def draft(content: Content): ItemDraft = ItemDraft("Only a title", "", Set.empty, false, content, Nil)
      for {
        _ <- service.initialize(owner, "Relaxed intake")
        _ <- create(service, owner, draft(Content.Idea(IdeaStatus.Proposed, "", "")))
        _ <- create(service, owner, draft(Content.Defect(DefectStatus.Open, Severity.Low, "", "", "", None, Nil)))
        _ <- create(service, owner, draft(Content.Goal(GoalStatus.Open, "", Nil, "")))
        _ <- denied(create(service, owner, draft(Content.Idea(IdeaStatus.Proposed, "x" * 100001, ""))))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(create(service, owner, ItemDraft(" ", "", Set.empty, false, Content.Idea(IdeaStatus.Proposed, "", ""), Nil)))(_.isInstanceOf[Fault.Invalid])
      } yield ()
    }

    "require a recommended alternative from agent-written questions and accept human ones" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val recommended = QuestionRecommendation(1, "B avoids the migration")
      def question(alternatives: List[String], recommendation: Option[QuestionRecommendation]): ItemDraft =
        task("Which option?").copy(content = Content.Question(QuestionStatus.Open, "Preference?", "Context", alternatives, recommendation, None))
      def content(recommendation: Option[QuestionRecommendation], status: QuestionStatus, answer: Option[String]): ItemDraft =
        task("Which option?").copy(content = Content.Question(status, "Preference?", "Context", List("A", "B"), recommendation, answer))
      val required = Fault.Invalid("An agent-created Question with alternatives must state its recommended alternative and reason")
      val index = Fault.Invalid("Recommended alternative must index the alternatives list")
      val reason = Fault.Invalid("Invalid recommendation reason")
      for {
        _ <- service.initialize(owner, "question recommendation")
        _ <- denied(create(service, owner, question(List("A", "B"), None)))(_ == required)
        _ <- ZIO.foreachDiscard(List(owner, human)) { author => for {
          _ <- denied(create(service, author, question(List("A", "B"), Some(recommended.copy(alternative = 2)))))(_ == index)
          _ <- denied(create(service, author, question(List("A", "B"), Some(recommended.copy(alternative = -1)))))(_ == index)
          _ <- denied(create(service, author, question(Nil, Some(recommended.copy(alternative = 0)))))(_ == index)
          _ <- denied(create(service, author, question(List("A", "B"), Some(recommended.copy(reason = " ")))))(_ == reason)
        } yield () }
        none <- service.search(owner, "archived:all", None, 200)
        _ <- assertIO(none.items.isEmpty)
        agent <- create(service, owner, question(List("A", "B"), Some(recommended)))
        stored <- service.get(owner, agent.id)
        _ <- assertIO(stored.item.draft.content == question(List("A", "B"), Some(recommended)).content)
        _ <- denied(service.change(owner, request(List(Mutation.Replace(agent.id, agent.revision, question(List("A", "B"), None))), Nil)))(_ == required)
        _ <- create(service, owner, question(Nil, None))
        _ <- create(service, owner, content(None, QuestionStatus.Withdrawn, None))
        asked <- create(service, human, question(List("A", "B"), None))
        linked <- service.change(owner, request(List(Mutation.Reference(asked.id, asked.revision, Relation.RelatesTo, agent.id, agent.revision, true)), Nil))
        current = linked.items.find(_.id == asked.id).get
        _ <- denied(service.change(owner, request(List(Mutation.Replace(current.id, current.revision,
          question(List("A", "C"), None))), Nil)))(_ == required)
        recorded <- service.change(owner, request(List(Mutation.Replace(current.id, current.revision, content(None, QuestionStatus.Answered, Some("A")))), Nil))
        _ <- assertIO(recorded.items.head.revision == Revision(3))
        answered <- service.change(human, request(List(Mutation.Replace(agent.id, linked.items.find(_.id == agent.id).get.revision,
          content(Some(recommended), QuestionStatus.Answered, Some("A")))), Nil))
        _ <- assertIO(answered.items.head.revision == Revision(3))
      } yield ()
    }

    "assign produced tasks to an existing or same-batch milestone and refuse closed milestones and bad indexes" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val goal = task("Goal").copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"))
      val research = task("Research").copy(content = Content.Research(ResearchStatus.Open, "Unknown", Nil, None, None))
      def milestone(status: MilestoneStatus): ItemDraft = task(s"$status milestone").copy(content = Content.Milestone(status, "Deliver the tasks"))
      val index = Fault.Invalid("Produce milestone must reference an earlier Create of a Milestone in this batch")
      val once = Fault.Invalid("An item may be changed only once in a batch")
      def produce(producer: ItemRevision, drafts: List[ItemDraft], ref: MilestoneRef): Mutation = Mutation.Produce(producer.id, producer.revision, drafts, Some(ref))
      for {
        _ <- service.initialize(owner, "milestone assignment")
        first <- create(service, owner, goal)
        second <- create(service, owner, goal)
        open <- create(service, owner, milestone(MilestoneStatus.Open))
        complete <- create(service, owner, milestone(MilestoneStatus.Complete))
        held <- create(service, owner, milestone(MilestoneStatus.Open))
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(first.id, second.id), 300000)
        foreign <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(held.id), 300000)
        fences = List(claim.fence)
        before <- service.changes(owner, ChangeCursor(0), 200)
        _ <- denied(service.change(owner, request(List(produce(first, List(task("Closed")), MilestoneRef.Existing(complete.id))), fences)))(
          _ == Fault.Invalid(s"Tasks can be assigned only to an Open milestone; M${complete.id.number} is Complete"))
        _ <- ZIO.foreachDiscard(List(
          List(produce(first, List(task("No create")), MilestoneRef.Created(0))),
          List(produce(first, List(task("Negative")), MilestoneRef.Created(-1))),
          List(Mutation.Create(task("Not a milestone")), produce(first, List(task("Wrong ledger")), MilestoneRef.Created(0))),
          List(produce(first, List(task("Later create")), MilestoneRef.Created(1)), Mutation.Create(milestone(MilestoneStatus.Open))),
        ))(mutations => denied(service.change(owner, request(mutations, fences)))(_ == index))
        _ <- denied(service.change(owner, request(List(produce(first, List(research), MilestoneRef.Existing(open.id))), fences)))(_ == Fault.Invalid("A Produce milestone requires a Task draft"))
        _ <- denied(service.change(owner, request(List(produce(first, List(task("Under a goal")), MilestoneRef.Existing(second.id))), fences)))(_ == Fault.Invalid("PartOf target must be a milestone"))
        _ <- denied(service.change(owner, request(List(produce(first, List(task("Missing")), MilestoneRef.Existing(open.id.copy(number = 99)))), fences)))(_.isInstanceOf[Fault.Missing])
        _ <- denied(service.change(owner, request(List(produce(first, List(task("Held")), MilestoneRef.Existing(held.id))), fences)))(_.isInstanceOf[Fault.StaleFence])
        retitled = Mutation.Replace(open.id, open.revision, milestone(MilestoneStatus.Open).copy(title = "Retitled"))
        assigned = produce(first, List(task("Twice")), MilestoneRef.Existing(open.id))
        _ <- denied(service.change(owner, request(List(retitled, assigned), fences)))(_ == once)
        _ <- denied(service.change(owner, request(List(assigned, retitled), fences)))(_ == once)
        after <- service.changes(owner, ChangeCursor(0), 200)
        _ <- assertIO(before == after)
        ack <- service.change(owner, request(List(produce(first, List(task("One"), research, task("Two")), MilestoneRef.Existing(open.id)),
          produce(second, List(task("Three")), MilestoneRef.Existing(open.id))), fences))
        _ <- assertIO(ack.items.count(_.id == open.id) == 1 && ack.items.find(_.id == open.id).exists(_.revision == Revision(2)) && ack.items.size == 7)
        views <- ZIO.foreach(ack.items.map(_.id))(service.get(owner, _))
        produced = views.filter(view => Set("One", "Two", "Three")(view.item.draft.title))
        _ <- assertIO(produced.map(view => view.item.draft.title -> view.refs.toSet).toMap == Map(
          "One" -> Set(ItemRef(Relation.DerivedFrom, first.id), ItemRef(Relation.PartOf, open.id)),
          "Two" -> Set(ItemRef(Relation.DerivedFrom, first.id), ItemRef(Relation.PartOf, open.id)),
          "Three" -> Set(ItemRef(Relation.DerivedFrom, second.id), ItemRef(Relation.PartOf, open.id))))
        _ <- assertIO(views.find(_.item.draft.title == "Research").exists(_.refs == List(ItemRef(Relation.DerivedFrom, first.id))))
        history <- service.history(owner, open.id, Revision(Long.MaxValue), 200)
        _ <- assertIO(history.entries.size == 2 && history.entries.head.item.refs.toSet == produced.map(view => ItemRef(Relation.Contains, view.item.id)).toSet)
        current <- service.get(owner, first.id)
        batch <- service.change(owner, request(List(Mutation.Create(research), Mutation.Create(milestone(MilestoneStatus.Open)),
          produce(ItemRevision(first.id, current.item.revision), List(task("Four")), MilestoneRef.Created(1))), fences))
        created = batch.items.find(_.id.ledger == Ledger.Milestones).get
        four <- service.get(owner, batch.items.filter(_.id.ledger == Ledger.Tasks).head.id)
        proposed <- service.history(owner, created.id, Revision(Long.MaxValue), 200)
        _ <- assertIO(created.revision == Revision(1) && four.refs.toSet == Set(ItemRef(Relation.DerivedFrom, first.id), ItemRef(Relation.PartOf, created.id)) &&
          proposed.entries.map(_.item.refs) == List(List(ItemRef(Relation.Contains, four.item.id))))
        _ <- service.release(other, foreign.fence)
      } yield ()
    }

    "retain terminal items while related items are still open" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val done = task("Done").copy(content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Result"), Nil))
      val goal = ItemDraft("Goal", "body", Set.empty, false, Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"), Nil)
      def link(source: ItemRevision, target: ItemRevision): Mutation =
        Mutation.Reference(source.id, source.revision, Relation.DerivedFrom, target.id, target.revision, true)
      for {
        _ <- service.initialize(owner, "Archive retention")
        g <- create(service, owner, goal)
        t <- create(service, owner, done)
        free <- create(service, owner, done.copy(title = "Unrelated"))
        linked <- service.change(owner, request(List(link(t, g)), Nil))
        task2 = linked.items.find(_.id == t.id).get
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(task2, free))), Nil)))(_.isInstanceOf[Fault.Invalid])
        unchanged <- service.get(owner, free.id)
        _ <- assertIO(!unchanged.item.draft.archived)
        preview <- service.archivePreview(owner, "ledger:Tasks", 50)
        _ <- assertIO(preview.members.map(_.id) == List(free.id) && preview.retained.map(r => (r.item.id, r.open)) == List((t.id, List(g.id))) && !preview.limited)
        achieved <- service.change(owner, request(List(Mutation.Replace(g.id, linked.items.find(_.id == g.id).get.revision,
          goal.copy(content = Content.Goal(GoalStatus.Achieved, "Outcome", List("Acceptance"), "Scope")))), Nil))
        after <- service.archivePreview(owner, "ledger:Tasks", 50)
        _ <- assertIO(after.members.map(_.id).toSet == Set(t.id, free.id) && after.retained.isEmpty)
        _ <- service.change(owner, request(List(Mutation.Archive(after.members.map(m => ItemRevision(m.id, m.revision)))), Nil))
      } yield ()
    }

    "keep adopted decisions and current memories active and archive only superseded, withdrawn or retracted ones" in { (service: LedgerService[IO]) =>
      val owner = scope()
      def decision(status: DecisionStatus): ItemDraft = task(s"Decision $status").copy(content = Content.Decision(status, "Choice", "Rationale", Nil))
      def memory(status: MemoryStatus): ItemDraft = task(s"Memory $status").copy(content = Content.Memory(status, "Knowledge", "Applies here", Nil))
      for {
        _ <- service.initialize(owner, "Active decisions and memories")
        adopted <- create(service, owner, decision(DecisionStatus.Adopted))
        current <- create(service, owner, memory(MemoryStatus.Current))
        supersededDecision <- create(service, owner, decision(DecisionStatus.Superseded))
        supersededMemory <- create(service, owner, memory(MemoryStatus.Superseded))
        withdrawn <- create(service, owner, decision(DecisionStatus.Withdrawn))
        retracted <- create(service, owner, memory(MemoryStatus.Retracted))
        decisions <- service.archivePreview(owner, "ledger:Decisions", 50)
        memories <- service.archivePreview(owner, "ledger:Memories", 50)
        _ <- assertIO(decisions.members.map(_.id).toSet == Set(supersededDecision.id, withdrawn.id) && decisions.retained.isEmpty)
        _ <- assertIO(memories.members.map(_.id).toSet == Set(supersededMemory.id, retracted.id) && memories.retained.isEmpty)
        _ <- assertIO((decisions.members ++ memories.members).forall(_.outcome == ItemOutcome(true, false)))
        active <- service.search(owner, "status:Adopted OR status:Current", None, 200)
        _ <- assertIO(active.items.map(_.id).toSet == Set(adopted.id, current.id) && active.items.forall(_.outcome == ItemOutcome(false, true)))
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(adopted))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(current))), Nil)))(_.isInstanceOf[Fault.Invalid])
        explicit <- ZIO.foreach(List(decision(DecisionStatus.Adopted), memory(MemoryStatus.Current)))(draft => create(service, owner, draft.copy(archived = true)))
        _ <- assertIO(explicit.map(_.id.ledger) == List(Ledger.Decisions, Ledger.Memories))
        archived <- service.change(owner, request(List(Mutation.Archive(List(supersededDecision, supersededMemory))), Nil))
        _ <- assertIO(archived.items.size == 2)
      } yield ()
    }

    "accept explicitly archived settled records while bulk archival keeps refusing them" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val adopted = task("Adopted decision").copy(content = Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil))
      val current = task("Current memory").copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil))
      val refused = "Only terminal items may be archived; unarchive an item before reopening it"
      for {
        _ <- service.initialize(owner, "Explicit settled archival")
        decision <- create(service, owner, adopted)
        memory <- create(service, owner, current)
        other <- create(service, owner, adopted.copy(title = "Still active decision"))
        archived <- service.change(owner, request(List(Mutation.Replace(decision.id, decision.revision, adopted.copy(archived = true))), Nil))
        kept <- service.change(owner, request(List(Mutation.Replace(decision.id, archived.items.head.revision, adopted.copy(archived = true, title = "Retitled"))), Nil))
        stored <- service.get(owner, decision.id)
        _ <- assertIO(kept.items.head.revision == Revision(3) && stored.item.draft.archived && stored.item.draft.title == "Retitled")
        _ <- service.change(owner, request(List(Mutation.Replace(memory.id, memory.revision, current.copy(archived = true))), Nil))
        _ <- create(service, owner, current.copy(title = "Created archived", archived = true))
        preview <- service.archivePreview(owner, "archived:all", 50)
        _ <- assertIO(preview.members.isEmpty && preview.retained.isEmpty)
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(other))), Nil)))(_ == Fault.Invalid(refused))
        active <- service.get(owner, other.id)
        _ <- assertIO(!active.item.draft.archived && active.item.revision == other.revision)
        proposed = adopted.copy(archived = true, content = Content.Decision(DecisionStatus.Proposed, "Choice", "Rationale", Nil))
        _ <- denied(service.change(owner, request(List(Mutation.Replace(other.id, other.revision, proposed)), Nil)))(_ == Fault.Invalid("Only terminal or settled items may be archived; unarchive an item before reopening it"))
      } yield ()
    }

    "let settled records neither archive nor retain their terminal neighbours" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val done = task("Done").copy(content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Result"), Nil))
      val adopted = task("Adopted decision").copy(content = Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil))
      val current = task("Current memory").copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil))
      def link(source: ItemRevision, target: ItemRevision): Mutation =
        Mutation.Reference(source.id, source.revision, Relation.DerivedFrom, target.id, target.revision, true)
      for {
        _ <- service.initialize(owner, "Settled retention")
        decision <- create(service, owner, adopted)
        memory <- create(service, owner, current)
        t <- create(service, owner, done)
        u <- create(service, owner, done.copy(title = "Done under memory"))
        _ <- service.change(owner, request(List(link(t, decision), link(u, memory)), Nil))
        preview <- service.archivePreview(owner, "", 50)
        _ <- assertIO(preview.members.map(_.id).toSet == Set(t.id, u.id) && preview.retained.isEmpty)
        _ <- service.change(owner, request(List(Mutation.Archive(preview.members.map(m => ItemRevision(m.id, m.revision)))), Nil))
        after <- service.archivePreview(owner, "", 50)
        _ <- assertIO(after.members.isEmpty && after.retained.isEmpty)
        active <- service.search(owner, "", None, 200)
        _ <- assertIO(active.items.map(_.id).toSet == Set(decision.id, memory.id))
      } yield ()
    }

    "archive only terminal current revisions atomically and replay the exact acknowledgement" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val done = task("Completed").copy(content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Result"), Nil))
      for {
        _ <- service.initialize(owner, "Archival invariant")
        first <- create(service, owner, done)
        second <- create(service, owner, done.copy(title = "Second"))
        open <- create(service, owner, task("Open"))
        _ <- denied(create(service, owner, task("Invalid").copy(archived = true)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first, open))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first, second.copy(revision = Revision(999))))), Nil)))(_.isInstanceOf[Fault.Conflict])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first, first))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(Nil)), Nil)))(_.isInstanceOf[Fault.Invalid])
        foreign = second.copy(id = second.id.copy(project = ProjectId(UUID.randomUUID())))
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first, foreign))), Nil)))(_.isInstanceOf[Fault.Denied])
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(second.id), 300000)
        _ <- denied(service.change(owner, request(List(Mutation.Archive(List(first, second))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        unchanged <- service.get(owner, first.id)
        _ <- assertIO(unchanged.item.revision == first.revision && !unchanged.item.draft.archived)
        _ <- service.release(owner, claim.fence)
        command = request(List(Mutation.Archive(List(first, second))), Nil)
        ack <- service.change(owner, command)
        replay <- service.change(owner, command)
        _ <- assertIO(ack == replay && ack.items.size == 2 && ack.items.forall(_.revision == Revision(2)))
        _ <- denied(service.change(owner, request(List(Mutation.Replace(first.id, Revision(2), task("Reopened").copy(archived = true))), Nil)))(_.isInstanceOf[Fault.Invalid])
        _ <- denied(service.change(owner, request(List(Mutation.Archive(ack.items)), Nil)))(_.isInstanceOf[Fault.Invalid])
        restored <- service.change(owner, request(List(Mutation.Restore(first.id, Revision(2), Revision(1), Nil)), Nil))
        _ <- assertIO(restored.items.head.revision == Revision(3))
        history <- service.history(owner, first.id, Revision(Long.MaxValue), 200)
        _ <- assertIO(history.entries.size == 3 && history.entries.exists(_.item.item.draft.archived))
      } yield ()
    }

    "normalize inverse references once and record both endpoint histories" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "references")
        left <- create(service, owner, task("Dependent"))
        right <- create(service, owner, task("Prerequisite").copy(archived = true, content = Content.Task(TaskStatus.Done, List("Observable result"), None, Nil)))
        added <- service.change(owner, request(List(Mutation.Reference(right.id, Revision(1), Relation.Blocks, left.id, Revision(1), true)), Nil))
        _ <- assertIO(added.items.map(_.revision.value) == List(2L, 2L))
        forward <- service.get(owner, left.id)
        backward <- service.get(owner, right.id)
        _ <- assertIO(forward.refs == List(ItemRef(Relation.BlockedBy, right.id)) && backward.refs == List(ItemRef(Relation.Blocks, left.id)))
        duplicate <- service.change(owner, request(List(Mutation.Reference(left.id, Revision(2), Relation.BlockedBy, right.id, Revision(2), true)), Nil))
        _ <- assertIO(duplicate.items.isEmpty && duplicate.cursor == added.cursor)
        _ <- service.change(owner, request(List(Mutation.Reference(left.id, Revision(2), Relation.BlockedBy, right.id, Revision(2), false)), Nil))
        histories <- ZIO.foreach(List(left.id, right.id))(id => service.history(owner, id, Revision(Long.MaxValue), 200))
        _ <- assertIO(histories.forall(h => h.entries.size == 3 && h.entries.head.item.refs.isEmpty && h.entries(1).item.refs.size == 1 && h.entries.last.item.refs.isEmpty))
        cross = right.id.copy(project = ProjectId(UUID.randomUUID()))
        _ <- denied(service.change(owner, request(List(Mutation.Reference(left.id, Revision(3), Relation.RelatesTo, cross, Revision(1), true)), Nil)))(_.isInstanceOf[Fault.Denied])
      } yield ()
    }

    "handoff a committed snapshot cursor to a paginated change stream" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "stream")
        first <- create(service, owner, task("Before snapshot"))
        snapshot <- service.search(owner, "archived:all", None, 200)
        second <- create(service, owner, task("After snapshot"))
        third <- create(service, owner, task("Later"))
        page <- service.changes(owner, snapshot.cursor, 1)
        _ <- assertIO(page.hasMore && page.events.flatMap(_.items).map(_.id) == List(second.id))
        next <- service.changes(owner, page.cursor, 1)
        _ <- assertIO(!next.hasMore && next.events.flatMap(_.items).map(_.id) == List(third.id))
        _ <- denied(service.changes(owner, ChangeCursor(999), 1))(_.isInstanceOf[Fault.Resync])
      } yield ()
    }

    "return compact discovery and byte-bounded history pages with lossless continuation" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val large = task("Large narrative").copy(body = "x" * 60000,
        content = Content.Task(TaskStatus.Ready, List("a" * 60000), Some("r" * 60000), Nil))
      for {
        _ <- service.initialize(owner, "bounded pages")
        first <- create(service, owner, large)
        _ <- ZIO.foreach((1L to 5L).toList)(revision => service.change(owner,
          request(List(Mutation.Replace(first.id, Revision(revision), large.copy(title = s"Revision ${revision + 1}"))), Nil)))
        discovery <- service.search(owner, "archived:all", None, 200)
        _ <- assertIO(Wire.encode(ItemPage_JsonCodec, discovery).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 4096)
        one <- service.history(owner, first.id, Revision(Long.MaxValue), 200)
        historyCount = one.entries.size
        historyBytes = Wire.encode(HistoryPage_JsonCodec, one).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        _ <- assertIO(historyCount > 0 && one.hasMore && historyBytes < 525000)
        two <- service.history(owner, first.id, one.entries.last.item.item.revision, 200)
        three <- service.history(owner, first.id, two.entries.last.item.item.revision, 200)
        _ <- assertIO(!three.hasMore && (one.entries ++ two.entries ++ three.entries).map(_.item.item.revision.value) == List(6L, 5L, 4L, 3L, 2L, 1L))
      } yield ()
    }

    "restore historical relationships and append both endpoint histories atomically" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "relationship restore")
        source <- create(service, owner, task("Dependent"))
        target <- create(service, owner, task("Prerequisite"))
        _ <- service.change(owner, request(List(Mutation.Reference(source.id, Revision(1), Relation.BlockedBy, target.id, Revision(1), true)), Nil))
        _ <- service.change(owner, request(List(Mutation.Reference(source.id, Revision(2), Relation.BlockedBy, target.id, Revision(2), false)), Nil))
        _ <- denied(service.change(owner, request(List(Mutation.Restore(source.id, Revision(3), Revision(2), List(ItemRevision(target.id, Revision(2))))), Nil)))(_.isInstanceOf[Fault.Conflict])
        unchanged <- service.get(owner, source.id)
        _ <- assertIO(unchanged.item.revision == Revision(3) && unchanged.refs.isEmpty)
        other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
        claim <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(target.id), 300000)
        _ <- denied(service.change(owner, request(List(Mutation.Restore(source.id, Revision(3), Revision(2), List(ItemRevision(target.id, Revision(3))))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        _ <- service.release(other, claim.fence)
        restored <- service.change(owner, request(List(Mutation.Restore(source.id, Revision(3), Revision(2), List(ItemRevision(target.id, Revision(3))))), Nil))
        _ <- assertIO(restored.items.toSet == Set(ItemRevision(source.id, Revision(4)), ItemRevision(target.id, Revision(4))))
        left <- service.get(owner, source.id)
        right <- service.get(owner, target.id)
        _ <- assertIO(left.refs == List(ItemRef(Relation.BlockedBy, target.id)) && right.refs == List(ItemRef(Relation.Blocks, source.id)))
        histories <- ZIO.foreach(List(source.id, target.id))(id => service.history(owner, id, Revision(Long.MaxValue), 200))
        _ <- assertIO(histories.forall(h => h.entries.size == 4 && h.entries.head.cursor == restored.cursor))
        _ <- service.change(owner, request(List(Mutation.Restore(source.id, Revision(4), Revision(1), List(ItemRevision(target.id, Revision(4))))), Nil))
        removed <- service.get(owner, target.id)
        _ <- assertIO(removed.item.revision == Revision(5) && removed.refs.isEmpty)
      } yield ()
    }

    "acquire sets atomically, reject overlap and fence results after expiry or release" in { (repository: LedgerRepository[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val start = 1000000L
      val service = new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(start), ZoneOffset.UTC), new QueryParser, new QueryCompleter(new QueryParser), new WorksetTraversal, new TerminationPlanner(new WorksetTraversal), new ClaimPlanner, new LedgerMutation(new TerminationPlanner(new WorksetTraversal)))
      val later = new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(start + 2000), ZoneOffset.UTC), new QueryParser, new QueryCompleter(new QueryParser), new WorksetTraversal, new TerminationPlanner(new WorksetTraversal), new ClaimPlanner, new LedgerMutation(new TerminationPlanner(new WorksetTraversal)))
      for {
        _ <- service.initialize(owner, "claims")
        left <- create(service, owner, task("One"))
        right <- create(service, owner, task("Two"))
        claimId = ClaimId(UUID.randomUUID())
        claim <- service.acquire(owner, claimId, Set(left.id), 1000)
        repeated <- service.acquire(owner, claimId, Set(left.id), 1000)
        _ <- assertIO(claim == repeated)
        _ <- denied(service.acquire(other, ClaimId(UUID.randomUUID()), Set(left.id, right.id), 1000))(_.isInstanceOf[Fault.Conflict])
        independent <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(right.id), 1000)
        _ <- denied(service.change(other, request(List(Mutation.Replace(left.id, Revision(1), task("Stale"))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        _ <- service.change(owner, request(List(Mutation.Replace(left.id, Revision(1), task("Owned"))), List(claim.fence)))
        replacement <- later.acquire(other, ClaimId(UUID.randomUUID()), Set(left.id), 1000)
        _ <- assertIO(replacement.fence.generation > independent.fence.generation)
        _ <- denied(later.change(owner, request(List(Mutation.Replace(left.id, Revision(2), task("Late result"))), List(claim.fence))))(_.isInstanceOf[Fault.StaleFence])
        _ <- later.release(other, replacement.fence)
        _ <- denied(later.change(other, request(List(Mutation.Replace(left.id, Revision(2), task("Released result"))), List(replacement.fence))))(_.isInstanceOf[Fault.StaleFence])
        _ <- later.change(owner, request(List(Mutation.Replace(left.id, Revision(2), task("New governing correction"))), Nil))
      } yield ()
    }

    "keep a replacement claim active when delivery repeats an earlier release" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      for {
        _ <- service.initialize(owner, "release retry")
        item <- create(service, owner, task("Claimed"))
        first <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(item.id), 300000)
        _ <- service.release(owner, first.fence)
        second <- service.acquire(other, ClaimId(UUID.randomUUID()), Set(item.id), 300000)
        _ <- service.release(owner, first.fence)
        _ <- denied(service.change(owner, request(List(Mutation.Replace(item.id, Revision(1), task("Must remain fenced"))), Nil)))(_.isInstanceOf[Fault.StaleFence])
        _ <- service.change(other, request(List(Mutation.Replace(item.id, Revision(1), task("Replacement owner"))), List(second.fence)))
      } yield ()
    }
  }
}

final class LedgerContractDummy extends LedgerContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}

final class LedgerContractPostgres extends LedgerContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
