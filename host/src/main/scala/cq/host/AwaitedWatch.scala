package cq.host

import cq.api.*
import cq.core.{DomainFailure, DriverPolicy, LedgerPolicy}

/**
 * What one attached session waits on a person for, so that the session learns when the person settles it (D164): Open Questions and
 * Requested Operator Actions. They are the ones the session created, replaced or linked an item `BlockedBy`, and the ones that the
 * workset of its active advance workflow waits for a person on. The set lives in this host and, as events, in the session's units
 * file, which the session's waiter and its Stop hook read; a host that starts anew is another session and starts with none.
 *
 * Nothing is searched, and no reply to the session waits for a reading: an item the session wrote waiting enters at once, by what
 * the session wrote; one it linked, or one of the workflow's workset, enters at the next `poll`, which reads what is watched by ID. `report` is told what the host could not
 * examine. `enabled` is false for a session whose harness integration cannot tell it of an end until it says that it can.
 */
final class AwaitedWatch(api: ServerApi, project: ProjectId, directory: java.nio.file.Path, report: String => Unit, enabled: Boolean) {
  private val units = new SessionUnits(directory)
  private var active = enabled
  // What the session waits on, with the revision at which the host last found it waiting. `written` are those the session wrote,
  // `scoped` those its workflow waits for, and `leaving` those of the workflow before it, which are read once more before they go.
  private var watched = Map.empty[ItemId, Revision]
  private var written = Set.empty[ItemId]
  private var scoped = Set.empty[ItemId]
  private var leaving = Set.empty[ItemId]
  // The items a change of the session named, which the next round reads to learn whether they wait: each with the revision the
  // session left it at when it wrote it waiting, and with none when it only linked it. One the session wrote waiting and the round
  // finds settled at another revision was settled since, and is announced.
  private var named = Map.empty[ItemId, Option[Revision]]
  // The workflow whose workset has not been read yet.
  private var entered = Option.empty[WorksetTarget]
  // What was settled and the session has not read since: reading it takes it out of what is announced.
  private var unread = Set.empty[ItemId]
  // How many events of the session the turn ends of a session that asks this host have examined.
  private var told = 0

  def enable(): Unit = synchronized { active = true }

  private def waits(id: ItemId, draft: ItemDraft): Boolean = DriverPolicy.awaitsUser(id.ledger, LedgerPolicy.status(draft.content), false)
  private def detail(content: Content): Option[String] = content match {
    case value: Content.Question => value.answer
    case value: Content.OperatorAction => value.confirmation
    case _ => None
  }
  // The session holds the item as settled: it wrote it so, or was shown it so. Nothing announces it any more.
  private def release(id: ItemId): Unit = {
    val known = synchronized {
      val known = watched.contains(id) || unread(id)
      watched -= id; written -= id; scoped -= id; leaving -= id; named -= id; unread -= id
      known
    }
    if (known) units.released(id)
  }
  private def shown(item: Item): Unit = if (DriverPolicy.awaitable(item.id.ledger) && !waits(item.id, item.draft)) release(item.id)
  private def note(id: ItemId, wrote: Option[Revision]): Unit = synchronized {
    written += id; leaving -= id
    if (!watched.contains(id)) named += id -> wrote.orElse(named.get(id).flatten)
  }
  // The session wrote the item waiting at `revision`: it waits on it from now on, and no reading is needed to know.
  private def wrote(id: ItemId, revision: Revision): Unit = {
    val fresh = synchronized {
      val fresh = !watched.contains(id)
      watched += id -> revision; written += id; leaving -= id; named -= id
      fresh
    }
    if (fresh) began(id)
  }
  private def settledDraft(draft: ItemDraft): Boolean = draft.content match {
    case _: Content.Question | _: Content.OperatorAction => !waiting(draft)
    case _ => false
  }
  private def drafts(mutation: Mutation): List[ItemDraft] = mutation match {
    case Mutation.Create(draft) => List(draft)
    case Mutation.Produce(_, _, drafts, _) => drafts
    case _ => Nil
  }
  private def waiting(draft: ItemDraft): Boolean = draft.content match {
    case value: Content.Question => value.status == QuestionStatus.Open
    case value: Content.OperatorAction => value.status == OperatorActionStatus.Requested
    case _ => false
  }
  // The item of a BlockedBy edge that gates: a Question or an Operator Action.
  private def gate(mutation: Mutation): Option[ItemId] = (mutation match {
    case Mutation.Reference(_, _, Relation.BlockedBy, target, _, true) => Some(target)
    case Mutation.Reference(source, _, Relation.Blocks, _, _, true) => Some(source)
    case _ => None
  }).filter(id => DriverPolicy.awaitable(id.ledger))

  /** A domain command of the session and its result. A change names what it created, replaced or linked as a gate; an item whose
    * content the reply shows the session as settled is read. No request is made here. */
  def observe(command: Command, result: Result): Unit = if (synchronized(active)) (command, result) match {
    case (change, Result.Changed(ack)) =>
      val revisions = ack.items.map(item => item.id -> item.revision).toMap
      val created = ack.items.filter(item => DriverPolicy.awaitable(item.id.ledger) && item.revision == Revision(1L))
      change match {
        case Command.Change(input) =>
          input.change.mutations.foreach {
            case Mutation.Replace(id, _, draft) if DriverPolicy.awaitable(id.ledger) => if (waiting(draft)) revisions.get(id).foreach(wrote(id, _)) else release(id)
            case other => gate(other).foreach(note(_, None))
          }
          // What the change created waits when every draft of it that could wait was written waiting, and is read at the next
          // round when only some were: the acknowledgement does not say which item came of which draft.
          val could = input.change.mutations.flatMap(drafts).filter(draft => waiting(draft) || settledDraft(draft))
          if (could.nonEmpty && could.forall(waiting)) created.foreach(item => wrote(item.id, item.revision))
          else if (could.exists(waiting)) created.foreach(item => note(item.id, Some(item.revision)))
        // An applied proposal names no drafts.
        case _ => created.foreach(item => note(item.id, Some(item.revision)))
      }
    case (_, Result.Detail(view)) => shown(view.item)
    case (_, Result.Details(page)) => page.items.foreach(view => shown(view.item))
    case _ => ()
  }

  /** The session activated `request`: the workset of an advance is read at the next round; any other workflow waits for nothing of its own. */
  def enter(request: WorkflowRequest): Unit = synchronized {
    entered = Some(request match {
      case WorkflowRequest.Advance(roots, through) => WorksetTarget.Inline(roots, through)
      case _ => WorksetTarget.Inline(Set.empty, WorkflowPhase.Work)
    })
  }

  private def call(command: Command): Result = api.call(command)
  private def scope(target: WorksetTarget): Unit = {
    val awaited = target match {
      case WorksetTarget.Inline(roots, _) if roots.isEmpty => Nil
      case _ => call(Command.Workset(WorksetInput(project, WorksetAction.Preview(target)))) match {
        case Result.WorksetPreviewed(preview) => DriverPolicy.awaiting(preview)
        case Result.Failed(fault) => throw DomainFailure(fault)
        case other => throw new IllegalStateException(s"A workset preview returned ${other.getClass.getSimpleName}")
      }
    }
    val fresh = synchronized {
      if (entered.contains(target)) entered = None
      val fresh = awaited.filterNot(item => watched.contains(item.id))
      watched ++= fresh.map(item => item.id -> item.revision)
      named --= fresh.map(_.id)
      // What only the workflow before waited for is read once more: settled meanwhile, it is announced, and released otherwise.
      leaving = (leaving ++ scoped -- awaited.map(_.id)) -- written
      scoped = awaited.map(_.id).toSet
      fresh.map(_.id)
    }
    fresh.foreach(began)
  }
  // An event the host writes after it decided: what the session did meanwhile is written after it.
  private def began(id: ItemId): Unit = { units.watching(id); if (!synchronized(watched.contains(id))) units.released(id) }

  private def read(id: ItemId): Option[Item] = call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(id)))) match {
    case Result.Detail(view) => Some(view.item)
    case Result.Failed(_: Fault.Missing) => None
    case Result.Failed(fault) => report(s"${SessionUnits.reference(id)} is not watched: it could not be read: $fault"); None
    case other => throw new IllegalStateException(s"Reading ${SessionUnits.reference(id)} returned ${other.getClass.getSimpleName}")
  }
  // Whether every member still has the revision the host last saw: one request, whose reply holds no content.
  private def unchanged(members: List[(ItemId, Revision)]): Boolean =
    call(Command.Read(ReadInput(project, ReadSelection.ItemDetails(members.map((id, revision) => ItemRevision(id, revision)), 1)))) match {
      case _: Result.Details => true
      case _: Result.Failed => false
      case other => throw new IllegalStateException(s"A batch read returned ${other.getClass.getSimpleName}")
    }

  private def examined(id: ItemId, item: Option[Item], stopping: () => Boolean): Unit = {
    sealed trait Verdict
    case object Began extends Verdict
    case object Left extends Verdict
    final case class Ended(end: AwaitedEnd) extends Verdict
    val verdict = synchronized {
      val pending = named.get(id)
      val known = watched.contains(id)
      named -= id
      item match {
        case _ if pending.isEmpty && !known => None
        case Some(value) if waits(id, value.draft) =>
          if (leaving(id)) { watched -= id; leaving -= id; Some(Left) }
          else { watched += id -> value.revision; Option.when(!known)(Began) }
        case Some(value) if known || pending.flatten.exists(_ != value.revision) =>
          watched += id -> value.revision
          Some(Ended(AwaitedEnd(id, value.draft.title, LedgerPolicy.status(value.draft.content), detail(value.draft.content))))
        // Named by a change and found settled at the first reading: the session never waited on it.
        case Some(_) => written -= id; None
        case None => watched -= id; written -= id; scoped -= id; leaving -= id; Option.when(known)(Left)
      }
    }
    verdict.foreach {
      case Began => began(id)
      case Left => units.released(id)
      // The item stays watched until its end is written: a round that fails here finds it again.
      case Ended(end) =>
        units.settled(end, stopping)
        val still = synchronized {
          val still = watched.contains(id)
          watched -= id; written -= id; scoped -= id; leaving -= id
          if (still) unread += id
          still
        }
        if (!still) units.released(id)
    }
  }

  /** One round: the workset of a workflow activated since the last round, then what is watched. One request tells whether anything
    * watched changed, for each `LedgerPolicy`-bounded batch of it; only what changed, what a change named and what is leaving is read
    * by itself. A workset the server refuses to preview leaves what the workflow before it waited on; a server that does not answer
    * fails the round, which is made again. `stopping` ends the round between two requests. */
  def poll(stopping: () => Boolean): Unit = if (synchronized(active)) {
    synchronized(entered).foreach { target =>
      try scope(target) catch {
        case DomainFailure(fault) =>
          synchronized { if (entered.contains(target)) entered = None }
          report(s"What gates the workflow of this session is not watched: its workset could not be previewed: $fault")
      }
    }
    val (known, single) = synchronized {
      val single = named.keySet ++ leaving
      (watched.toList.filterNot((id, _) => single(id)).sortBy((id, _) => LedgerPolicy.key(id)), single.toList.sortBy(LedgerPolicy.key))
    }
    val changed = known.grouped(AwaitedWatch.Batch).flatMap(batch => if (stopping() || unchanged(batch)) Nil else batch.map(_._1)).toList
    (single ++ changed).foreach(id => if (!stopping()) examined(id, read(id), stopping))
  }

  /** For a session whose turn end asks this host: the ends it has not read that no turn end was told yet, whether it still waits
    * on a person, and how many events of the session that takes into account. */
  def settled(): (List[AwaitedEnd], Boolean, Int) = synchronized {
    val events = SessionUnits.read(directory)
    val ends = SessionAwaited.unread(events, told, events.size)
    told = events.size
    (ends, SessionAwaited.open(events).nonEmpty, events.size)
  }
}

object AwaitedWatch {
  /** The members of one batch read, which the server bounds. */
  val Batch: Int = cq.core.CohortBounds.Candidates
}
