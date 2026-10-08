package cq.host

import cq.api.*
import cq.core.{DomainFailure, DriverPolicy}
import scala.util.control.NonFatal

/**
 * The Open Questions one attached session waits on, so that the session learns when a person settles one of them (D164). They are
 * the Questions a change of the session created or revised and found Open, and the Questions that the workset of its active advance
 * workflow waits for a person on. The set lives in this host and, as events, in the session's units file, which the session's
 * waiter and its Stop hook read; a host that starts anew is another session and starts with none.
 *
 * Nothing is searched: a Question enters by its ID, from a reply to the session or from the preview of the workflow's workset, and
 * each `poll` reads the watched Questions by ID. `report` is told what the host could not examine.
 */
final class QuestionWatch(api: ServerApi, project: ProjectId, directory: java.nio.file.Path, report: String => Unit) {
  private val units = new SessionUnits(directory)
  // What the session wrote, and what its workflow waits for; a Question may be in both.
  private var written = Set.empty[ItemId]
  private var scoped = Set.empty[ItemId]
  // The workflow whose workset has not been read yet.
  private var entered = Option.empty[WorksetTarget]
  // The settled Questions the session has not read since: reading one of them takes its announcement back.
  private var unread = Set.empty[ItemId]
  // How many events of the session the turn ends of a session that asks this host have examined.
  private var told = 0

  // A Question the server refuses to show is one the session no longer waits on: the refusal is the server's answer.
  private def read(question: ItemId): Option[Item] = api.call(Command.Read(ReadInput(project, ReadSelection.ItemDetail(question)))) match {
    case Result.Detail(view) => Some(view.item)
    case Result.Failed(_: Fault.Missing) => None
    case Result.Failed(fault) => report(s"${SessionUnits.reference(question)} is no longer watched: it could not be read: $fault"); None
    case other => throw new IllegalStateException(s"Reading a Question returned ${other.getClass.getSimpleName}")
  }
  private def question(item: Item): Option[Content.Question] = item.draft.content match {
    case value: Content.Question => Some(value)
    case _ => None
  }
  private def waits(item: Item): Boolean = DriverPolicy.awaitsUser(item.id.ledger, cq.core.LedgerPolicy.status(item.draft.content), item.draft.archived)
  private def watched: Set[ItemId] = written ++ scoped
  private def release(id: ItemId): Unit = if (watched(id) || unread(id)) {
    written -= id; scoped -= id; unread -= id
    units.released(id)
  }
  // The session holds the Question as `item` shows it: an Open one it wrote is watched, and one that is settled needs no announcement.
  private def held(item: Item, wrote: Boolean): Unit = if (question(item).nonEmpty) synchronized {
    if (!waits(item)) release(item.id)
    else if (wrote && !written(item.id)) { if (!watched(item.id)) units.watching(item.id); written += item.id }
  }

  /** A domain command of the session and its result: the Questions a change touched are read once, and a Question shown is held as shown. */
  def observe(command: Command, result: Result): Unit = try (command, result) match {
    case (_, Result.Changed(ack)) => ack.items.map(_.id).filter(_.ledger == Ledger.Questions).distinct.foreach(id => read(id).foreach(held(_, true)))
    case (_, Result.Detail(view)) => held(view.item, false)
    case (_, Result.Details(page)) => page.items.foreach(view => held(view.item, false))
    case _ => ()
  } catch { case NonFatal(error) => report(s"The Questions of a reply were not examined: ${error.getMessage}") }

  /** The session activated `request`: the workset of an advance is read at the next poll; any other workflow waits for nothing of its own. */
  def enter(request: WorkflowRequest): Unit = synchronized {
    entered = Some(request match {
      case WorkflowRequest.Advance(roots, through) => WorksetTarget.Inline(roots, through)
      case _ => WorksetTarget.Inline(Set.empty, WorkflowPhase.Work)
    })
  }

  private def scope(target: WorksetTarget): Unit = {
    val awaited = target match {
      case WorksetTarget.Inline(roots, _) if roots.isEmpty => Set.empty[ItemId]
      case _ => api.call(Command.Workset(WorksetInput(project, WorksetAction.Preview(target)))) match {
        case Result.WorksetPreviewed(preview) => DriverPolicy.awaited(preview).filter(_.ledger == Ledger.Questions).toSet
        case Result.Failed(fault) => throw DomainFailure(fault)
        case other => throw new IllegalStateException(s"A workset preview returned ${other.getClass.getSimpleName}")
      }
    }
    synchronized {
      if (entered.contains(target)) entered = None
      (awaited -- watched).foreach(units.watching)
      val left = scoped -- awaited
      scoped = awaited
      (left -- written).foreach(units.released)
    }
  }

  /** One round: the workset of a workflow activated since the last round, then each watched Question by its ID. A workset the
    * server refuses to preview leaves what the workflow before it waited on; a server that does not answer fails the round. */
  def poll(): Unit = {
    synchronized(entered).foreach { target =>
      try scope(target) catch {
        case DomainFailure(fault) =>
          synchronized { if (entered.contains(target)) entered = None }
          report(s"The Questions that gate the workflow of this session are not watched: its workset could not be previewed: $fault")
      }
    }
    synchronized(watched).toList.sortBy(cq.core.LedgerPolicy.key).foreach { id =>
      val item = read(id)
      synchronized {
        if (watched(id)) item match {
          case Some(value) if waits(value) => ()
          case Some(value) =>
            val content = question(value).get
            written -= id; scoped -= id; unread += id
            units.settled(QuestionEnd(id, value.draft.title, content.status, content.answer))
          case None => release(id)
        }
      }
    }
  }

  /** The ends a turn end of the session announces, each once, for a session whose turn end asks this host; and whether it still waits on a Question. */
  def settled(): (List[QuestionEnd], Boolean) = synchronized {
    val events = SessionUnits.read(directory)
    val ends = SessionQuestions.unannounced(events, told)
    told = events.size
    (ends, SessionQuestions.open(events).nonEmpty)
  }
}
