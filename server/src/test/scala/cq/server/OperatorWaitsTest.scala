package cq.server

import cq.api.*
import cq.core.{AwaitedSpans, DomainFailure}
import cq.host.*
import java.io.IOException
import java.nio.file.{Files, Path}
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The time a governing session waits on a person, as the host records it: spans of the phase Await on the governing attempt's own
  * assignment, observed again while the wait lasts, retained one record per wait and delivered with the rules of the server. */
final class OperatorWaitsLocal extends AnyWordSpec {
  private def uuid: UUID = UUID.randomUUID()
  private val Start = 1_000_000_000_000L
  private val Minute = 60_000L

  private final class ManualClock(var now: Long) extends Clock {
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = Instant.ofEpochMilli(now)
    override def millis(): Long = now
  }

  /** A server that answers reads of items from a table and registers spans with the rules of `AwaitedSpans`. */
  private class Server extends ServerApi {
    var items = Map.empty[ItemId, ItemView]
    var spans = Map.empty[RequestId, PhaseSpan]
    var registrations = 0
    var calls = 0
    /** The next registrations are recorded and their acknowledgement is lost. */
    var losing = 0
    override def call(command: Command): Result = command match {
      case Command.Read(ReadInput(_, ReadSelection.ItemDetail(id))) => items.get(id).fold[Result](Result.Failed(Fault.Missing("Missing")))(Result.Detail(_))
      case Command.Read(ReadInput(_, ReadSelection.ItemDetails(members, _))) =>
        if (members.forall(member => items.get(member.id).exists(_.item.revision == member.revision))) Result.Details(ItemViews(Nil, Nil)) else Result.Failed(Fault.Missing("Changed"))
      case other => throw new IllegalStateException(s"Unexpected command $other")
    }
    override def usage(input: HostUsageInput): HostUsageResult = input.operation match {
      case HostUsage.Span(span) =>
        calls += 1
        val answer = AwaitedSpans.admit(spans.get(span.id), span) match {
          case AwaitedSpans.Store => spans = spans.updated(span.id, span); registrations += 1; span
          case AwaitedSpans.Existing(stored) => stored
          case AwaitedSpans.Refused(fault) => throw DomainFailure(fault)
        }
        if (losing > 0) { losing -= 1; throw new IOException("Acknowledgement lost after the span was recorded") }
        HostUsageResult.Spanned(answer)
      case other => throw new IllegalStateException(s"Unexpected usage $other")
    }
    override def artifact(input: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Unexpected artifact")
    override def grant(input: GrantRequest): AccessToken = throw new IllegalStateException("Unexpected grant")
    override def admit(input: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Unexpected admission")
    override def integrate(input: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Unexpected integration")
  }

  private final class World {
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Waits")
    val directory: Path = Files.createTempDirectory("cq-operator-waits-")
    Files.createFile(directory.resolve("waiters.lock"))
    val clock = new ManualClock(Start)
    val server = new Server
    val assignment = Assignment(AssignmentId(uuid), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Claude, "unobserved-interactive-provider",
      "unobserved-interactive-model", "fixture", Start - 5000, UsagePhase.Govern, None)
    val run = SupervisorRun(project, assignment, attempt, "1", "/unused/repository", GitCommit("0" * 40), SessionOwnership.Attached)
    val waits = new OperatorWaits(directory, run, server, clock)
    val watch = new AwaitedWatch(server, project.project, directory, problem => fail(problem), true, Some(waits))
    private var next = 0L
    def id(ledger: Ledger): ItemId = { next += 1; ItemId(project.project, ledger, next) }
    def task(number: Long): ItemId = ItemId(project.project, Ledger.Tasks, 1000 + number)
    def question(status: QuestionStatus): Content = Content.Question(status, "Prompt", "Context", Nil, None, None)
    def action(status: OperatorActionStatus): Content = Content.OperatorAction(status, "Do it", "Evidence", None, Nil)
    def put(item: ItemId, revision: Long, content: Content, blocks: Iterable[ItemId]): Unit = {
      val draft = ItemDraft("Item " + item.number, "", Set.empty, false, content, Nil)
      server.items = server.items.updated(item, ItemView(Item(item, Revision(revision), draft, 0, 0, Provenance(Actor("fixture", attempt.session, Role.Human), 0, RequestId(uuid))),
        blocks.toList.map(ItemRef(Relation.Blocks, _))))
    }
    def blocking(item: ItemId, blocks: Iterable[ItemId]): Unit = { val view = server.items(item); put(item, view.item.revision.value, view.item.draft.content, blocks) }
    def draft(content: Content): ItemDraft = ItemDraft("Item", "", Set.empty, false, content, Nil)
    /** The session writes a Question or an Operator Action that waits, at the clock's time. */
    def waiting(ledger: Ledger, blocks: Iterable[ItemId]): ItemId = {
      val item = id(ledger)
      val content = if (ledger == Ledger.Questions) question(QuestionStatus.Open) else action(OperatorActionStatus.Requested)
      put(item, 1, content, blocks)
      val request = RequestId(uuid)
      watch.observe(Command.Change(ChangeInput(project.project, ChangeRequest(request, List(Mutation.Create(draft(content))), Nil, "Fixture"))),
        Result.Changed(ChangeAck(request, ChangeCursor(1), List(ItemRevision(item, Revision(1))))))
      item
    }
    /** A person answers the Question. */
    def answer(item: ItemId): Unit = { val view = server.items(item); put(item, view.item.revision.value + 1, question(QuestionStatus.Answered), view.refs.map(_.target)) }
    /** The session settles the item itself. */
    def release(item: ItemId, content: Content): Unit = {
      put(item, 2, content, server.items(item).refs.map(_.target))
      val request = RequestId(uuid)
      watch.observe(Command.Change(ChangeInput(project.project, ChangeRequest(request, List(Mutation.Replace(item, Revision(1), draft(content))), Nil, "Fixture"))),
        Result.Changed(ChangeAck(request, ChangeCursor(2), List(ItemRevision(item, Revision(2))))))
    }
    def at(offset: Long): Unit = clock.now = Start + offset
    def poll(): Unit = watch.poll(() => false)
    def span(item: ItemId): PhaseSpan = server.spans.values.filter(_.await.exists(_.item == item)).toList match {
      case List(one) => one
      case other => fail(s"Expected one span of $item, found $other")
    }
    def store: WaitStore = new WaitStore(directory.resolve(OperatorWaits.Directory), project.project)
    def retained: List[Path] = Using.resource(Files.list(directory.resolve(OperatorWaits.Directory)))(_.iterator().asScala.toList)
    def expected(item: ItemId, started: Long, finished: Long, state: AttemptState, blocked: Set[ItemId], observation: Long, truncated: Boolean = false): PhaseSpan =
      PhaseSpan(RequestId(OperatorWaits.identity(attempt.session, item, started)), assignment.id, attempt.session, UsagePhase.Await, started, finished, state, None,
        Some(AwaitedSpan(item, blocked, truncated, observation)))
  }

  "Operator waits (Behavioral Active Blackbox; manual time, filesystem Communication)" should {
    "record a Question answered by a person, an Operator Action the session releases and a Question still open at the end as overlapping spans of the governing assignment" in {
      val w = new World
      import w.*
      val t1 = task(1)
      val answered = waiting(Ledger.Questions, List(t1))
      at(Minute)
      val released = waiting(Ledger.OperatorActions, Nil)
      at(2 * Minute)
      val open = waiting(Ledger.Questions, List(task(2)))
      // Registered Running the moment the wait begins, on the assignment of the governing attempt.
      assert(span(answered) == expected(answered, Start, Start, AttemptState.Running, Set(t1), 1))
      assert(span(released) == expected(released, Start + Minute, Start + Minute, AttemptState.Running, Set.empty, 1))
      assert(span(open) == expected(open, Start + 2 * Minute, Start + 2 * Minute, AttemptState.Running, Set(task(2)), 1))
      // A round with nothing new, and less than five minutes after the last observation, observes nothing.
      at(3 * Minute); poll()
      assert(server.registrations == 3 && server.spans.values.forall(_.await.get.observation == 1))
      // Five minutes after the last observation of each, the host observes the wait still lasting.
      at(5 * Minute); poll()
      assert(span(answered) == expected(answered, Start, Start + 5 * Minute, AttemptState.Running, Set(t1), 2))
      assert(span(released).await.get.observation == 1 && span(open).await.get.observation == 1)
      at(6 * Minute); poll()
      assert(span(released) == expected(released, Start + Minute, Start + 6 * Minute, AttemptState.Running, Set.empty, 2))
      at(7 * Minute); poll()
      assert(span(open) == expected(open, Start + 2 * Minute, Start + 7 * Minute, AttemptState.Running, Set(task(2)), 2))
      // A person answers the Question: it ends Completed at the moment of the round that observed it.
      answer(answered)
      at(8 * Minute); poll()
      assert(span(answered) == expected(answered, Start, Start + 8 * Minute, AttemptState.Completed, Set(t1), 3))
      // The session releases the Operator Action itself: Cancelled.
      at(9 * Minute)
      release(released, action(OperatorActionStatus.Cancelled))
      assert(span(released) == expected(released, Start + Minute, Start + 9 * Minute, AttemptState.Cancelled, Set.empty, 3))
      // A round after both ended changes nothing of them. The session ends with the third still waiting: Cancelled.
      at(10 * Minute); poll()
      assert(span(answered).await.get.observation == 3 && span(released).await.get.observation == 3 && span(open).await.get.observation == 2)
      at(11 * Minute)
      watch.close()
      assert(span(open) == expected(open, Start + 2 * Minute, Start + 11 * Minute, AttemptState.Cancelled, Set(task(2)), 3))
      // Overlapping waits are three spans, none merged, all on the governing assignment of the one session.
      assert(server.spans.size == 3 && server.spans.values.forall(value => value.assignment == assignment.id && value.session == attempt.session))
    }

    "end a wait still open as Cancelled at the end of the session although the delivery before it failed" in {
      val w = new World
      import w.*
      val open = waiting(Ledger.Questions, List(task(1)))
      at(2 * Minute)
      intercept[IOException](watch.end(() => throw new IOException("The final delivery failed")))
      assert(span(open) == expected(open, Start, Start + 2 * Minute, AttemptState.Cancelled, Set(task(1)), 2))
    }

    "attribute a wait to every Task the awaited item blocked at any time during it and to at most 200 of them" in {
      val w = new World
      import w.*
      val (t1, t2) = (task(1), task(2))
      val awaited = waiting(Ledger.Questions, List(t1))
      at(Minute); poll()
      assert(span(awaited).await.get.observation == 1)
      // Linked as blocked after the wait began: a new observation, and the span still starts where the wait did.
      blocking(awaited, List(t1, t2))
      at(2 * Minute); poll()
      assert(span(awaited) == expected(awaited, Start, Start + 2 * Minute, AttemptState.Running, Set(t1, t2), 2))
      // Its link removed during the wait: the Task stays among the blocked items, and nothing is observed for the removal.
      blocking(awaited, List(t2))
      at(3 * Minute); poll()
      assert(span(awaited).await.get.observation == 2 && span(awaited).await.get.blocked == Set(t1, t2))
      answer(awaited)
      at(4 * Minute); poll()
      assert(span(awaited) == expected(awaited, Start, Start + 4 * Minute, AttemptState.Completed, Set(t1, t2), 3))

      val many = waiting(Ledger.Questions, (1L to 150L).map(task))
      assert(span(many).await.get.blocked.size == 150 && !span(many).await.get.truncated)
      blocking(many, (151L to 300L).map(task))
      at(5 * Minute); poll()
      val bounded = span(many).await.get
      assert(bounded.blocked.size == 200 && bounded.truncated && (1L to 150L).map(task).toSet.subsetOf(bounded.blocked), bounded.toString)
      // The flag stays, and nothing is observed again for the items left out.
      at(6 * Minute); poll()
      assert(span(many).await.get.observation == bounded.observation)
      blocking(many, Nil)
      at(12 * Minute); poll()
      assert(span(many).await.get.truncated && span(many).await.get.blocked == bounded.blocked)
    }

    "send a refresh again after a lost acknowledgement, ignore an older observation replayed late and keep one record for a wait of 24 hours" in {
      val w = new World
      import w.*
      // (a) The acknowledgement of the first send is lost: the record stays retained, the next round sends it again, and the server holds one span.
      server.losing = 1
      val awaited = waiting(Ledger.Questions, List(task(1)))
      val first = span(awaited)
      assert(server.registrations == 1 && store.read(first.id).exists(_.acknowledged.isEmpty))
      at(Minute); poll()
      assert(server.spans.size == 1 && server.registrations == 1 && store.read(first.id) == Some(RetainedWait(first, Some(1))))
      // The refresh is lost too, then replaced by the next observation.
      server.losing = 1
      at(5 * Minute); poll()
      val second = span(awaited)
      assert(second.await.get.observation == 2 && store.read(first.id) == Some(RetainedWait(second, Some(1))))
      at(6 * Minute); poll()
      assert(store.read(first.id) == Some(RetainedWait(second, Some(2))))
      // (b) The older observation is replayed after the newer one was acknowledged: the server changes nothing and nothing fails.
      val calls = server.calls
      assert(store.send(first, server) && server.spans(first.id) == second && server.calls == calls + 1)
      assert(store.read(first.id) == Some(RetainedWait(second, Some(2))))
      // (c) A wait of 24 hours of the controlled clock leaves one record and one span whose finish time is the last observation.
      (7 to 24 * 60).foreach { minute => at(minute * Minute); poll() }
      val last = span(awaited)
      assert(retained.map(_.getFileName.toString) == List(first.id.value.toString + ".json"))
      assert(last.state == AttemptState.Running && last.finishedAt == Start + 24 * 60 * Minute &&
        store.read(first.id).get.span == last && store.read(first.id).get.acknowledged == last.await.map(_.observation))
      assert(last.finishedAt - last.startedAt == 24 * 60 * Minute && last.await.get.observation == 2 + 287)
      // (d) The final delivery ends the wait once; a second one sends nothing.
      watch.close()
      assert(span(awaited).state == AttemptState.Cancelled)
      val sent = server.calls
      assert(waits.flush() == 0 && OperatorWaits.recover(directory, project.project, server) == 0 && server.calls == sent && !OperatorWaits.pending(directory, project.project))
    }

    "end a wait still Running as Unknown at its last observation when a later host or cq job upload recovers it, and leave one that ended" in {
      val w = new World
      import w.*
      val (finished, running, unsent) = (waiting(Ledger.Questions, Nil), waiting(Ledger.OperatorActions, List(task(1))), waiting(Ledger.Questions, Nil))
      at(5 * Minute); poll()
      answer(finished)
      at(6 * Minute); poll()
      assert(span(finished).state == AttemptState.Completed && span(running).state == AttemptState.Running)
      // The host ends here, without its final delivery. The server was not reached for the last observation of one wait.
      server.losing = 0
      val lastRunning = span(running)
      val lost = new Server { override def usage(input: HostUsageInput): HostUsageResult = throw new IOException("Unreachable") }
      assert(waits.flush() == 0 && OperatorWaits.pending(directory, project.project))
      val before = server.spans
      val sentBefore = server.calls
      assert(scala.util.Try(OperatorWaits.recover(directory, project.project, lost)).isFailure && OperatorWaits.pending(directory, project.project))
      assert(server.spans == before && store.read(lastRunning.id).get.span.state == AttemptState.Unknown)
      assert(OperatorWaits.recover(directory, project.project, server) == 2)
      assert(server.calls == sentBefore + 2)
      assert(span(running) == lastRunning.copy(state = AttemptState.Unknown, await = lastRunning.await.map(wait => wait.copy(observation = wait.observation + 1))))
      assert(span(unsent).state == AttemptState.Unknown && span(unsent).finishedAt == Start + 5 * Minute)
      assert(span(finished).state == AttemptState.Completed && span(finished).finishedAt == Start + 6 * Minute)
      assert(!OperatorWaits.pending(directory, project.project) && OperatorWaits.recover(directory, project.project, server) == 0)
    }

    "end a wait Completed when the session reads the Question a person answered before the round observed it" in {
      val w = new World
      import w.*
      val awaited = waiting(Ledger.Questions, List(task(1)))
      at(2 * Minute)
      answer(awaited)
      // The session reads the answered Question itself before any round.
      watch.observe(Command.Read(ReadInput(project.project, ReadSelection.ItemDetail(awaited))), Result.Detail(server.items(awaited)))
      assert(span(awaited).state == AttemptState.Completed && span(awaited).finishedAt == Start + 2 * Minute)
    }

    "keep a Task the session read as blocked by the awaited item although the link was removed before the next round" in {
      val w = new World
      import w.*
      val (t1, t2) = (task(1), task(2))
      val awaited = waiting(Ledger.Questions, List(t1))
      val before = server.items(awaited)
      // The link to T2 exists when the session reads the Question, and is gone before any round reads it.
      blocking(awaited, List(t1, t2))
      at(Minute)
      watch.observe(Command.Read(ReadInput(project.project, ReadSelection.ItemDetail(awaited))), Result.Detail(server.items(awaited)))
      server.items = server.items.updated(awaited, before)
      at(2 * Minute); poll()
      assert(span(awaited).await.get.blocked == Set(t1, t2) && span(awaited).state == AttemptState.Running)
      // A batch read shows it too.
      val t3 = task(3)
      blocking(awaited, List(t1, t3))
      watch.observe(Command.Read(ReadInput(project.project, ReadSelection.ItemDetails(Nil, 0))), Result.Details(ItemViews(List(server.items(awaited)), Nil)))
      server.items = server.items.updated(awaited, before)
      at(3 * Minute); poll()
      assert(span(awaited).await.get.blocked == Set(t1, t2, t3))
    }

    "keep a Task whose blocking link the session wrote and removed between two rounds" in {
      val w = new World
      import w.*
      val (t1, t2, t3) = (task(1), task(2), task(3))
      val awaited = waiting(Ledger.Questions, List(t1))
      val before = server.items(awaited)
      def link(mutation: Mutation): Unit = {
        val request = RequestId(uuid)
        watch.observe(Command.Change(ChangeInput(project.project, ChangeRequest(request, List(mutation), Nil, "Fixture"))),
          Result.Changed(ChangeAck(request, ChangeCursor(3), List(ItemRevision(awaited, Revision(1))))))
      }
      at(Minute)
      blocking(awaited, List(t1, t2))
      link(Mutation.Reference(awaited, Revision(1), Relation.Blocks, t2, Revision(1), true))
      blocking(awaited, List(t1, t3))
      link(Mutation.Reference(t3, Revision(1), Relation.BlockedBy, awaited, Revision(1), true))
      // Both links are gone before any round reads the Question.
      server.items = server.items.updated(awaited, before)
      assert(span(awaited).await.get.blocked == Set(t1, t2, t3))
      at(2 * Minute); poll()
      assert(span(awaited) == expected(awaited, Start, Start + Minute, AttemptState.Running, Set(t1, t2, t3), 3))
    }

    "report a recovery that leaves a wait unacknowledged as incomplete" in {
      val w = new World
      import w.*
      val awaited = waiting(Ledger.Questions, Nil)
      val lost = new Server { override def usage(input: HostUsageInput): HostUsageResult = throw new IOException("Unreachable") }
      assert(scala.util.Try(OperatorWaits.recover(directory, project.project, lost)).isFailure)
      assert(store.unacknowledged == 1 && OperatorWaits.recover(directory, project.project, server) == 1 && store.unacknowledged == 0 && span(awaited).state == AttemptState.Unknown)
    }
  }
}
