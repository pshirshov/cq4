package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
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
    "I17: store one agent configuration document per layer under compare-and-set, keep the revision of an unchanged text and bound the text" in {
      (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val human = Scope(ProjectId(UUID.randomUUID()), Actor("operator", SessionId(UUID.randomUUID()), Role.Human))
      val governor = human.copy(actor = Actor("governor", SessionId(UUID.randomUUID()), Role.Governor))
      val sibling = Scope(ProjectId(UUID.randomUUID()), human.actor)
      val Installation = AgentsScope.Installation()
      val Project = AgentsScope.Project()
      val bound = LedgerPolicy.MaxConfigBytes
      // The installation's document is shared by every project of a database, so each run writes a text of its own.
      def defaults(worker: String): String = s"defaults: { roles: { worker: $worker } } # ${human.project.value}\n"
      def installed(scope: Scope): IO[Throwable, Option[StoredInstallationSetting]] = repository.transact(scope.project)(_.installationSetting(InstallationSettingKind.Agents))
      def stored(scope: Scope): IO[Throwable, Option[StoredSetting]] = repository.transact(scope.project)(_.setting(ProjectSettingKind.Agents))
      def setting(revision: Revision, text: String): StoredInstallationSetting = StoredInstallationSetting(revision, InstallationSetting.Agents(text), human.actor, 1700000000000L)
      for {
        _ <- service.initialize(human, "agent configuration")
        _ <- service.initialize(sibling, "sibling")
        initial <- service.agents(human)
        base = initial.installation.revision
        absent <- installed(human)
        _ <- assertIO(initial.project == AgentsDocument(Revision(0), "", None, Nil) && absent.map(_.revision).getOrElse(Revision(0)) == base &&
          (base != Revision(0) || (absent.isEmpty && initial.installation == AgentsDocument(Revision(0), "", None, Nil))))
        // Saving the text a layer has without a stored document writes nothing.
        empty <- service.replaceAgents(human, Project, Revision(0), "")
        nothing <- stored(human)
        _ <- assertIO(empty == initial && nothing.isEmpty)
        _ <- denied(service.replaceAgents(governor, Project, Revision(0), defaults("claude:sonnet")))(_ == Fault.Denied("Agent configuration change requires human authority"))
        _ <- denied(service.previewAgents(governor, Project, defaults("claude:sonnet")))(_ == Fault.Denied("Agent configuration change requires human authority"))
        first <- service.replaceAgents(human, Installation, base, defaults("claude:sonnet"))
        next = Revision(base.value + 1)
        row <- installed(sibling)
        _ <- assertIO(first.installation.revision == next && first.installation.text == defaults("claude:sonnet") && first.installation.change.exists(_.actor == human.actor) &&
          row.exists(value => value.revision == next && value.value == InstallationSetting.Agents(defaults("claude:sonnet")) && value.actor == human.actor &&
            first.installation.change.exists(_.at == value.updatedAt)))
        _ <- denied(service.replaceAgents(human, Installation, base, defaults("claude:opus")))(_ == Fault.Conflict(
          s"Agent configuration of the installation changed: expected revision ${base.value}, actual ${next.value}; reload before saving"))
        same <- service.replaceAgents(sibling, Installation, next, defaults("claude:sonnet"))
        kept <- installed(human)
        _ <- assertIO(same.installation == first.installation && kept == row)
        // The comparison is the repository's: a write based on another revision than the stored one changes nothing.
        lost <- ZIO.foreach(List(base, Revision(next.value + 1)))(expected => repository.transact(human.project)(_.replaceInstallationSetting(expected, setting(Revision(next.value + 5), ""))))
        after <- installed(human)
        _ <- assertIO(lost == List(false, false) && after == row)
        second = Revision(next.value + 1)
        won <- repository.transact(sibling.project)(_.replaceInstallationSetting(next, setting(second, defaults("claude:opus"))))
        replaced <- installed(human)
        _ <- assertIO(won && replaced.contains(setting(second, defaults("claude:opus"))))
        project <- service.replaceAgents(human, Project, Revision(0), "defaults: { roles: { explorer: claude:haiku } }\n")
        document <- stored(human)
        other <- service.agents(sibling)
        _ <- assertIO(project.project.revision == Revision(1) && project.installation.revision == second &&
          document.exists(value => value.revision == Revision(1) && value.value == ProjectSetting.Agents("defaults: { roles: { explorer: claude:haiku } }\n") && value.actor == human.actor) &&
          other.project == AgentsDocument(Revision(0), "", None, Nil) && other.installation == project.installation)
        _ <- denied(service.replaceAgents(human, Project, Revision(0), ""))(_ == Fault.Conflict("Agent configuration of the project changed: expected revision 0, actual 1; reload before saving"))
        unchanged <- service.replaceAgents(human, Project, Revision(1), "defaults: { roles: { explorer: claude:haiku } }\n")
        _ <- assertIO(unchanged == project)
        route <- service.agentRoute(governor, Harness.Pi, AgentRole.Worker)
        _ <- assertIO(route == ResolvedAssignment(Harness.Pi, AgentRole.Worker, RoleResolution.Resolved(ResolvedRole(PanelMode.All, 1,
          List(ResolvedSeat(SeatStrategy.Fallback, List(ModelRoute(Harness.Claude, None, "opus", None)))), RoleOrigin(AgentLayer.Installation, RoleSource.DefaultRoles), Nil))))
        _ <- ZIO.foreachDiscard(List(Installation -> second, Project -> Revision(1))) { (layer, revision) =>
          denied(service.replaceAgents(human, layer, revision, "#" + "x" * bound))(_ == Fault.Invalid(s"Agent configuration exceeds $bound bytes: ${bound + 1} supplied")) *>
            denied(service.replaceAgents(human, layer, revision, "#" + "λ" * (bound / 2)))(_ == Fault.Invalid(s"Agent configuration exceeds $bound bytes: ${bound + 1} supplied")) *>
            denied(service.replaceAgents(human, layer, revision, "defaults: ["))(_.isInstanceOf[Fault.Invalid])
        }
        settled <- service.agents(human)
        _ <- assertIO(settled == project)
        full <- service.replaceAgents(human, Installation, second, "#" + "x" * (bound - 1))
        _ <- assertIO(full.installation.revision == Revision(second.value + 1) && full.installation.text.length == bound && full.project == project.project)
      } yield ()
    }

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

    // Defect 100, Question 24: a milestone is not closed while a Task it contains is Ready or Active.
    "refuse closing a milestone over a non-terminal Task by Replace and by Restore, judged on the state the request leaves" in { (service: LedgerService[IO]) =>
      val owner = scope()
      def milestone(status: MilestoneStatus): ItemDraft = task("Milestone").copy(content = Content.Milestone(status, "Deliver the tasks"))
      def work(status: TaskStatus): ItemDraft = task(s"$status task").copy(content = Content.Task(status, List("Observable result"), None, Nil))
      def refusal(target: ItemId, status: MilestoneStatus, tasks: (ItemId, TaskStatus)*): Fault = Fault.Invalid(
        s"M${target.number} cannot be changed to $status while it contains non-terminal Tasks: ${tasks.map((id, state) => s"T${id.number} ($state)").mkString(", ")}. " +
          s"Make each Done or Cancelled, or reassign it to another Open milestone, before closing M${target.number}")
      def current(id: ItemId): IO[Throwable, ItemRevision] = service.get(owner, id).map(view => ItemRevision(view.item.id, view.item.revision))
      def replace(id: ItemId, draft: ItemDraft): IO[Throwable, Mutation] = current(id).map(found => Mutation.Replace(id, found.revision, draft))
      // A new Open milestone containing one new Task for each status.
      def containing(statuses: TaskStatus*): IO[Throwable, (ItemId, List[ItemId])] = for {
        target <- create(service, owner, milestone(MilestoneStatus.Open))
        members <- ZIO.foreach(statuses.toList) { status => for {
          member <- create(service, owner, work(status))
          container <- current(target.id)
          _ <- service.change(owner, request(List(Mutation.Reference(member.id, member.revision, Relation.PartOf, target.id, container.revision, true)), Nil))
        } yield member.id }
      } yield (target.id, members)
      // The request is refused with exactly this fault and commits nothing: the change cursor and every named revision are unchanged.
      def refused(expected: Fault, items: List[ItemId])(mutations: IO[Throwable, List[Mutation]]): IO[Throwable, Unit] = for {
        before <- service.counts(owner).map(_.cursor)
        revisions <- ZIO.foreach(items)(current)
        result <- mutations.flatMap(value => service.change(owner, request(value, Nil))).either
        after <- service.counts(owner).map(_.cursor)
        kept <- ZIO.foreach(items)(current)
        _ <- result match {
          case Left(DomainFailure(fault)) if fault == expected => ZIO.unit
          case other => ZIO.fail(new AssertionError(s"Expected $expected, got $other"))
        }
        _ <- assertIO(before == after && revisions == kept)
      } yield ()
      def status(id: ItemId): IO[Throwable, String] = service.get(owner, id).map(view => LedgerPolicy.status(view.item.draft.content))
      val closedStatuses = List(MilestoneStatus.Complete, MilestoneStatus.Cancelled)
      for {
        _ <- service.initialize(owner, "milestone closure gate")
        // Replace: refused over a Ready or an Active Task, to either closed status.
        _ <- ZIO.foreachDiscard(for { closed <- closedStatuses; open <- List(TaskStatus.Ready, TaskStatus.Active) } yield (closed, open)) { (closed, open) => for {
          made <- containing(open)
          (target, members) = made
          _ <- refused(refusal(target, closed, members.head -> open), target :: members)(replace(target, milestone(closed)).map(List(_)))
        } yield () }
        // The fault names every non-terminal Task and no terminal one.
        mixed <- containing(TaskStatus.Ready, TaskStatus.Done, TaskStatus.Active, TaskStatus.Cancelled)
        _ <- refused(refusal(mixed._1, MilestoneStatus.Complete, mixed._2.head -> TaskStatus.Ready, mixed._2(2) -> TaskStatus.Active), mixed._1 :: mixed._2)(
          replace(mixed._1, milestone(MilestoneStatus.Complete)).map(List(_)))
        // Admitted: no Task, only terminal Tasks, and Tasks the same request makes terminal, in either order.
        _ <- ZIO.foreachDiscard(closedStatuses) { closed => for {
          empty <- containing()
          _ <- replace(empty._1, milestone(closed)).flatMap(value => service.change(owner, request(List(value), Nil)))
          terminal <- containing(TaskStatus.Done, TaskStatus.Cancelled)
          _ <- replace(terminal._1, milestone(closed)).flatMap(value => service.change(owner, request(List(value), Nil)))
          together <- containing(TaskStatus.Ready)
          finishing <- replace(together._2.head, work(TaskStatus.Done))
          closing <- replace(together._1, milestone(closed))
          _ <- service.change(owner, request(List(finishing, closing), Nil))
          reversed <- containing(TaskStatus.Active)
          cancelling <- replace(reversed._2.head, work(TaskStatus.Cancelled))
          first <- replace(reversed._1, milestone(closed))
          _ <- service.change(owner, request(List(first, cancelling), Nil))
          found <- ZIO.foreach(List(empty._1, terminal._1, together._1, reversed._1))(status)
          _ <- assertIO(found.forall(_ == closed.toString))
        } yield () }
        // The state the request leaves decides: a request that closes the milestone and reopens its Task is refused.
        reopening <- containing(TaskStatus.Done)
        _ <- refused(refusal(reopening._1, MilestoneStatus.Cancelled, reopening._2.head -> TaskStatus.Ready), reopening._1 :: reopening._2)(for {
          closing <- replace(reopening._1, milestone(MilestoneStatus.Cancelled))
          opening <- replace(reopening._2.head, work(TaskStatus.Ready))
        } yield List(closing, opening))
        // Reopening stays possible: the closed milestone by Replace and by Restore, and a terminal Task under a closed milestone by Replace.
        _ <- ZIO.foreachDiscard(closedStatuses) { closed => for {
          made <- containing(TaskStatus.Done)
          (target, members) = made
          openRevision <- current(target)
          closing <- service.change(owner, request(List(Mutation.Replace(target, openRevision.revision, milestone(closed))), Nil))
          closedRevision = closing.items.head
          _ <- service.change(owner, request(List(Mutation.Replace(target, closedRevision.revision, milestone(MilestoneStatus.Open))), Nil))
          reopened <- current(target)
          again <- service.change(owner, request(List(Mutation.Restore(target, reopened.revision, closedRevision.revision, Nil)), Nil))
          restored <- service.change(owner, request(List(Mutation.Restore(target, again.items.head.revision, openRevision.revision, Nil)), Nil))
          _ <- status(target).flatMap(found => assertIO(found == "Open"))
          // Restore to the closed revision is the same transition: refused while the Task is Ready, admitted once it is terminal again.
          _ <- replace(members.head, work(TaskStatus.Ready)).flatMap(value => service.change(owner, request(List(value), Nil)))
          _ <- refused(refusal(target, closed, members.head -> TaskStatus.Ready), target :: members)(
            ZIO.succeed(List(Mutation.Restore(target, restored.items.head.revision, closedRevision.revision, Nil))))
          _ <- replace(members.head, work(TaskStatus.Cancelled)).flatMap(value => service.change(owner, request(List(value), Nil)))
          _ <- service.change(owner, request(List(Mutation.Restore(target, restored.items.head.revision, closedRevision.revision, Nil)), Nil))
          // A terminal Task under the closed milestone is reopened; the milestone stays closed and keeps the Task.
          _ <- replace(members.head, work(TaskStatus.Active)).flatMap(value => service.change(owner, request(List(value), Nil)))
          stranded <- service.get(owner, members.head)
          _ <- status(target).flatMap(found => assertIO(found == closed.toString && stranded.refs == List(ItemRef(Relation.PartOf, target)) &&
            LedgerPolicy.status(stranded.item.draft.content) == "Active"))
          // The gate judges a change of status: an edit that keeps the closed status is admitted, a move to the other closed status is not.
          _ <- replace(target, milestone(closed).copy(title = "Retitled")).flatMap(value => service.change(owner, request(List(value), Nil)))
          other = closedStatuses.find(_ != closed).get
          _ <- refused(refusal(target, other, members.head -> TaskStatus.Active), target :: members)(replace(target, milestone(other)).map(List(_)))
          _ <- replace(target, milestone(MilestoneStatus.Open)).flatMap(value => service.change(owner, request(List(value), Nil)))
          _ <- status(target).flatMap(found => assertIO(found == "Open"))
        } yield () }
      } yield ()
    }

    "assign a Task only to an Open milestone through Produce, Reference and Restore while keeping removal allowed" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val goal = task("Goal").copy(content = Content.Goal(GoalStatus.Open, "Outcome", List("Acceptance"), "Scope"))
      def milestone(status: MilestoneStatus): ItemDraft = task(s"$status milestone").copy(content = Content.Milestone(status, "Deliver the tasks"))
      def closed(name: String, status: MilestoneStatus): Fault = Fault.Invalid(s"Tasks can be assigned only to an Open milestone; $name is $status")
      def link(source: ItemRevision, relation: Relation, target: ItemRevision, present: Boolean): ChangeRequest =
        request(List(Mutation.Reference(source.id, source.revision, relation, target.id, target.revision, present)), Nil)
      def current(id: ItemId): IO[Throwable, ItemRevision] = service.get(owner, id).map(view => ItemRevision(view.item.id, view.item.revision))
      for {
        _ <- service.initialize(owner, "open milestone assignment")
        producer <- create(service, owner, goal)
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(producer.id), 300000)
        complete <- create(service, owner, milestone(MilestoneStatus.Complete))
        archived <- create(service, owner, milestone(MilestoneStatus.Cancelled).copy(archived = true))
        open <- create(service, owner, milestone(MilestoneStatus.Open))
        loose <- create(service, owner, task("Loose"))
        before <- service.changes(owner, ChangeCursor(0), 200)
        _ <- ZIO.foreachDiscard(List(MilestoneStatus.Complete, MilestoneStatus.Cancelled)) { status =>
          denied(service.change(owner, request(List(Mutation.Create(milestone(status)),
            Mutation.Produce(producer.id, producer.revision, List(task("Under a closed milestone")), Some(MilestoneRef.Created(0)))), List(claim.fence))))(
            _ == closed("the Milestone created at index 0 of this batch", status))
        }
        _ <- denied(service.change(owner, link(loose, Relation.PartOf, complete, true)))(_ == closed(s"M${complete.id.number}", MilestoneStatus.Complete))
        _ <- denied(service.change(owner, link(complete, Relation.Contains, loose, true)))(_ == closed(s"M${complete.id.number}", MilestoneStatus.Complete))
        _ <- denied(service.change(owner, link(loose, Relation.PartOf, archived, true)))(_ == closed(s"M${archived.id.number}", MilestoneStatus.Cancelled))
        after <- service.changes(owner, ChangeCursor(0), 200)
        _ <- assertIO(before == after)
        linked <- service.change(owner, link(loose, Relation.PartOf, open, true))
        contained = linked.items.find(_.id == open.id).get
        member = linked.items.find(_.id == loose.id).get
        // A milestone closes only over terminal Tasks, so the same request finishes the Task.
        done = task("Loose").copy(content = Content.Task(TaskStatus.Done, List("Observable result"), None, Nil))
        completed <- service.change(owner, request(List(Mutation.Replace(open.id, contained.revision, milestone(MilestoneStatus.Complete)),
          Mutation.Replace(loose.id, member.revision, done)), Nil))
        kept <- service.get(owner, loose.id)
        _ <- assertIO(kept.refs == List(ItemRef(Relation.PartOf, open.id)))
        removed <- service.change(owner, link(completed.items.find(_.id == loose.id).get, Relation.PartOf, completed.items.find(_.id == open.id).get, false))
        emptied = removed.items.find(_.id == open.id).get
        unlinked = removed.items.find(_.id == loose.id).get
        // Restoring either endpoint re-adds the relationship: refused while the milestone, as the batch leaves it, is not Open.
        _ <- denied(service.change(owner, request(List(Mutation.Restore(loose.id, unlinked.revision, member.revision, List(emptied))), Nil)))(
          _ == closed(s"M${open.id.number}", MilestoneStatus.Complete))
        reopened <- service.change(owner, request(List(Mutation.Replace(open.id, emptied.revision, milestone(MilestoneStatus.Open))), Nil))
        _ <- denied(service.change(owner, request(List(Mutation.Restore(open.id, reopened.items.head.revision, completed.items.head.revision, List(unlinked))), Nil)))(
          _ == closed(s"M${open.id.number}", MilestoneStatus.Complete))
        reclosed <- service.change(owner, request(List(Mutation.Replace(open.id, reopened.items.head.revision, milestone(MilestoneStatus.Cancelled))), Nil))
        restored <- service.change(owner, request(List(Mutation.Restore(open.id, reclosed.items.head.revision, contained.revision, List(unlinked))), Nil))
        target <- service.get(owner, open.id)
        _ <- assertIO(restored.items.map(_.id).toSet == Set(open.id, loose.id) && target.refs == List(ItemRef(Relation.Contains, loose.id)) &&
          target.item.draft.content == milestone(MilestoneStatus.Open).content)
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

    "D128: archive adopted decisions only after every scope anchor is archived and recheck anchors atomically" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val adopted = task("Scoped decision").copy(content = Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil))
      val done = task("Archived scope").copy(archived = true, content = Content.Task(TaskStatus.Done, List("Observable result"), Some("Result"), Nil))
      def link(source: ItemRevision, target: ItemRevision, relation: Relation): IO[Throwable, Unit] = for {
        a <- service.get(owner, source.id)
        b <- service.get(owner, target.id)
        _ <- service.change(owner, request(List(Mutation.Reference(source.id, a.item.revision, relation, target.id, b.item.revision, true)), Nil))
      } yield ()
      for {
        _ <- service.initialize(owner, "Decision scope archival")
        anchor <- create(service, owner, done)
        milestone <- create(service, owner, done.copy(content = Content.Milestone(MilestoneStatus.Cancelled, "Archived milestone")))
        active <- create(service, owner, task("Active scope"))
        visible <- create(service, owner, done.copy(title = "Finished but unarchived scope", archived = false))
        derived <- create(service, owner, adopted)
        part <- create(service, owner, adopted.copy(title = "Milestone decision"))
        mixed <- create(service, owner, adopted.copy(title = "Mixed scope"))
        finished <- create(service, owner, adopted.copy(title = "Unarchived finished scope"))
        context <- create(service, owner, adopted.copy(title = "Context link is not an anchor"))
        unanchored <- create(service, owner, adopted.copy(title = "Standing decision"))
        memory <- create(service, owner, adopted.copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil)))
        _ <- link(derived, anchor, Relation.DerivedFrom)
        _ <- link(part, milestone, Relation.PartOf)
        _ <- link(mixed, anchor, Relation.DerivedFrom)
        _ <- link(mixed, active, Relation.DerivedFrom)
        _ <- link(finished, visible, Relation.DerivedFrom)
        _ <- link(context, anchor, Relation.RelatesTo)
        _ <- link(memory, anchor, Relation.DerivedFrom)
        preview <- service.archivePreview(owner, "ledger:Decisions", 50)
        _ <- assertIO(preview.members.map(_.id).toSet == Set(derived.id, part.id) && preview.retained.isEmpty)
        _ <- assertIO(preview.members.forall(_.outcome == ItemOutcome(false, true)))
        storedAnchor <- service.get(owner, anchor.id)
        _ <- service.change(owner, request(List(Mutation.Replace(anchor.id, storedAnchor.item.revision, done.copy(archived = false))), Nil))
        _ <- denied(service.change(owner, request(List(Mutation.Archive(preview.members.map(m => ItemRevision(m.id, m.revision)))), Nil)))(_.isInstanceOf[Fault.Invalid])
        unchanged <- service.get(owner, part.id)
        _ <- assertIO(!unchanged.item.draft.archived)
        restoredAnchor <- service.get(owner, anchor.id)
        _ <- service.change(owner, request(List(Mutation.Replace(anchor.id, restoredAnchor.item.revision, done)), Nil))
        fresh <- service.archivePreview(owner, "ledger:Decisions", 50)
        archived <- service.change(owner, request(List(Mutation.Archive(fresh.members.map(m => ItemRevision(m.id, m.revision)))), Nil))
        _ <- assertIO(archived.items.map(_.id).toSet == Set(derived.id, part.id))
        kept <- service.search(owner, "ledger:Decisions", None, 200)
        _ <- assertIO(kept.items.map(_.id).toSet == Set(mixed.id, finished.id, context.id, unanchored.id))
        memories <- service.archivePreview(owner, "ledger:Memories", 50)
        _ <- assertIO(memories.members.isEmpty)
      } yield ()
    }

    "accept explicitly archived settled records while bulk archival keeps refusing them" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val adopted = task("Adopted decision").copy(content = Content.Decision(DecisionStatus.Adopted, "Choice", "Rationale", Nil))
      val current = task("Current memory").copy(content = Content.Memory(MemoryStatus.Current, "Knowledge", "Applies here", Nil))
      val refused = "Only terminal items or adopted Decisions with fully archived scope may be bulk archived"
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
      val service = FixedLedger.at(repository, start)
      val later = FixedLedger.at(repository, start + 2000)
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
