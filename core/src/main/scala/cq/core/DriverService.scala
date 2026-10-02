package cq.core

import cq.api.*
import java.util.UUID
import scala.util.{Failure, Success, Try}

// The harness-neutral driver core. Every operation runs inside one project transaction, which serialises it with that project's ledger writes.
final class DriverService(registry: DriverRegistry, planner: WorksetPlanner) {
  import DriverPolicy.*

  private def denied(message: String): Nothing = throw DomainFailure(Fault.Denied(message))
  private def token(): DriverToken = DriverToken(UUID.randomUUID())

  private def authorized(scope: Scope, key: DriverKey, source: DriverOrigin, action: DriverControl): Unit = {
    if (scope.actor.role != Role.Human)
      denied("Driver control requires the operator credential of a CQ hook command or the Pi extension; attached sessions only bind and read status")
    DriverPolicy.key(key)
    origin(key, source, action)
  }

  // The status reads use the registry alone, outside any project transaction.
  def read(scope: Scope, key: DriverKey, source: DriverOrigin): DriverReply = {
    authorized(scope, key, source, DriverControl.Status())
    DriverReply.Status(registry.get(scope.project, key).map(status))
  }
  def own(scope: Scope): DriverReply = DriverReply.Status(registry.all(scope.project).filter(_.attached.contains(scope.actor.session))
    .sortBy(record => (record.on, record.touchedAt)).lastOption.map(status))

  // What the calling attached session may settle while its driver's start directive is pending: the answer `DriverBoundary.admit` gives
  // the completion write, readable before the integration is applied. A session without an on driver is not restricted.
  def settleable(scope: Scope): DriverReply = {
    val allowed = registry.bound(scope.project, scope.actor.session).flatMap(_.settleable(scope.actor.session))
    DriverReply.Settleable(allowed.nonEmpty, allowed.getOrElse(Set.empty))
  }

  // State-changing entry points: only the CQ hook commands and the Pi extension hold the operator credential they require.
  def control(tx: LedgerTransaction, scope: Scope, key: DriverKey, source: DriverOrigin, action: DriverControl, now: Long): DriverReply = {
    authorized(scope, key, source, action)
    val project = scope.project
    action match {
      case DriverControl.Start(target, attached) =>
        if (registry.get(project, key).exists(_.state != DriverState.Off))
          throw DomainFailure(Fault.Conflict("This session's CQ driver is already on; park it before driving other targets or another phase"))
        val preview = planner.resolve(tx, target)
        val others = registry.all(project).filter(_.key != key)
        // At capacity an off driver, or one whose session went silent, makes room; a live driver is never displaced.
        if (others.size >= MaxDrivers)
          others.filter(record => record.state == DriverState.Off || now - record.touchedAt > IdleMillis).sortBy(_.touchedAt).headOption match {
            case Some(oldest) => registry.remove(oldest)
            case None => throw DomainFailure(Fault.Limit(s"A project holds at most $MaxDrivers CQ drivers; park one first"))
          }
        val offer = if (attached.isEmpty) Some(BindOffer(token(), Math.addExact(now, BindMillis))) else None
        // The record of this key's earlier drive is replaced: what that drive left unsettled passes to the new one.
        val record = DriverRecord(project, key, if (attached.isEmpty) DriverState.Binding else DriverState.On, attached, preview.workset,
          preview.targets, preview.through, offer, None, 0, None, true, now, registry.get(project, key).fold(Map.empty[SessionId, Set[IntegrationId]])(outstanding))
        attached.foreach(supersede(project, _, key, now))
        registry.put(attached.fold(record)(inherited(record, _)))
        DriverReply.Started(status(record), preview, offer.map(_.token),
          if (attached.isEmpty) s"CQ driver binding: ${describe(record)}; it turns on when this session presents the bind token"
          else s"CQ driver on: ${describe(record)}")
      case _: DriverControl.Park => registry.get(project, key) match {
        case Some(record) if record.state != DriverState.Off =>
          val parked = stopped(record, DriverStopped(DriverStop.Parked, "Parked by the operator"), true, now)
          registry.put(parked)
          DriverReply.Parked(Some(status(parked)), s"CQ driver parked: ${describe(parked)}")
        case other => DriverReply.Parked(other.map(status), "CQ driver is already off")
      }
      case _: DriverControl.Status => read(scope, key, source)
      case _: DriverControl.Continue => registry.get(project, key) match {
        case None => DriverReply.Stop(DriverStopped(DriverStop.Off, "No CQ driver is on for this session"), None, Nil)
        case Some(record) => record.state match {
          case DriverState.Off if record.announced => DriverReply.Stop(DriverStopped(DriverStop.Off, "The CQ driver is off"), Some(status(record)), Nil)
          case DriverState.Off =>
            registry.put(record.copy(announced = true))
            DriverReply.Stop(record.stopped.get, Some(status(record)), List(stopMessage(record.stopped.get)))
          case DriverState.Binding => stop(record, DriverStopped(DriverStop.NotBound,
            "failure: no attached CQ session presented the bind token, so the driver never turned on"), Nil, now)
          case DriverState.On => continuation(tx, record, now)
        }
      }
    }
  }

  // A harness may issue a new session key while its attached host lives on: the attached session's binding follows the key that drives now.
  private def supersede(project: ProjectId, attached: SessionId, key: DriverKey, now: Long): Unit =
    registry.bound(project, attached).foreach { previous =>
      registry.put(stopped(previous, DriverStopped(DriverStop.Parked, s"Its attached session was bound to the CQ driver of session ${key.session}"), false, now))
    }

  // A driver that binds an attached session also takes over what that session's drives under other session keys left unsettled.
  private def inherited(record: DriverRecord, attached: SessionId): DriverRecord =
    record.copy(carried = carry(record.carried, attached,
      registry.all(record.project).filter(_.key != record.key).flatMap(outstanding(_).get(attached)).flatten.toSet))

  private def stop(record: DriverRecord, value: DriverStopped, messages: List[String], now: Long): DriverReply = {
    val next = stopped(record, value, true, now)
    registry.put(next)
    DriverReply.Stop(value, Some(status(next)), messages :+ stopMessage(value))
  }

  private def directed(record: DriverRecord, cycle: CycleRecord, value: CycleToken, messages: List[String], now: Long): DriverReply = {
    val next = record.copy(cycle = Some(cycle), directives = record.directives + 1, touchedAt = now)
    registry.put(next)
    DriverReply.Continue(directive(record.key.harness, cycle, value), status(next), messages)
  }

  private def resume(record: DriverRecord, cycle: CycleRecord, now: Long): DriverReply =
    if (record.directives >= MaxDirectives) stop(record, limit, Nil, now)
    else {
      val resume = token()
      directed(record, cycle.copy(resumeToken = Some(resume)), CycleToken.Resume(resume), Nil, now)
    }

  private def limit: DriverStopped = DriverStopped(DriverStop.LimitReached, s"This drive issued its $MaxDirectives directives; drive again to continue")

  private def continuation(tx: LedgerTransaction, record: DriverRecord, now: Long): DriverReply = record.cycle match {
    case Some(cycle) if cycle.state == CycleState.Pending =>
      stop(record, DriverStopped(DriverStop.Failure, s"directive not started: the start directive of cycle ${cycle.number} was not submitted"), Nil, now)
    case Some(cycle) if cycle.active && cycle.inFlight.nonEmpty => resume(record, cycle, now)
    // Work that waits for the session gets one resume directive; a second stop on the same work ends the drive instead of resuming forever.
    case Some(cycle) if cycle.active && cycle.held.nonEmpty =>
      if (cycle.held != cycle.prompted) resume(record, cycle.copy(prompted = cycle.held), now)
      else stop(record, DriverStopped(DriverStop.Failure, s"cycle ${cycle.number} is held by ${cycle.held.toList.map(member).sorted.mkString(", ")}, " +
        "which only the session can resolve, and a resume directive did not resolve it"), Nil, now)
    case previous =>
      val finished = previous.map(ended)
      val settled = record.copy(cycle = finished)
      // Snapshot first: the advanceable set is computed from the frozen targets before any readiness decision.
      Try(planner.evaluate(tx, record.targets, record.through, record.workset)) match {
        case Failure(DomainFailure(fault)) =>
          stop(settled, DriverStopped(DriverStop.Failure, s"the advanceable set cannot be computed: $fault"), Nil, now)
        case Failure(error) => throw error
        case Success(snapshot) =>
          val messages = finished.flatMap(changed(_, snapshot)).toList
          val selected = snapshot.advanceable.map(_.item.id).toSet
          // A Milestone the cycle created for its Tasks stays context: it is accounted for while a selected item still belongs to it.
          val milestones = snapshot.context.map(_.item.id).filter(_.ledger == Ledger.Milestones).toSet
          val unselected = finished.toList.flatMap(_.created).filterNot(id => selected(id) || milestones(id))
          if (unselected.nonEmpty) stop(settled, DriverStopped(DriverStop.Failure,
            s"${references(unselected)} created by cycle ${finished.get.number} is not in the recomputed advanceable set"), messages, now)
          else decide(snapshot, finished) match {
            case DriverDecision.Stop(value) => stop(settled, value, messages, now)
            case DriverDecision.Continue if record.directives >= MaxDirectives => stop(settled, limit, messages, now)
            case DriverDecision.Continue =>
              val start = token()
              val cycle = CycleRecord(CycleId(UUID.randomUUID()), finished.fold(1)(_.number + 1), record.targets, record.through, snapshot,
                CycleState.Pending, start, None, Map.empty, None, Nil, Nil, Set.empty, Set.empty)
              directed(record, cycle, CycleToken.Start(start), messages, now)
          }
      }
  }

  // Operations of one attached session under its own credential: the token-gated bind, the read-only status, activation and lineage.
  def session(scope: Scope, action: DriverSession, now: Long): DriverReply = {
    val project = scope.project
    val caller = scope.actor.session
    action match {
      case _: DriverSession.Status => own(scope)
      case _: DriverSession.Settleable => settleable(scope)
      case DriverSession.Bind(value) =>
        governor(scope)
        val record = registry.all(project).find(record => record.state == DriverState.Binding && record.bind.exists(_.token == value))
          .getOrElse(denied("Unknown or already used CQ driver bind token; run the drive command again"))
        if (record.bind.get.expiresAt <= now) denied("The CQ driver bind token expired; park and run the drive command again")
        supersede(project, caller, record.key, now)
        val bound = inherited(record.copy(state = DriverState.On, attached = Some(caller), bind = None, touchedAt = now), caller)
        registry.put(bound)
        DriverReply.Bound(status(bound), s"CQ driver on: ${describe(bound)}")
      case DriverSession.Activate(run, request, presented) =>
        governor(scope)
        DriverReply.Activation(activate(project, caller, run, request, presented, now))
      case DriverSession.Inherit(id, parent, member) =>
        LedgerAccess.write(scope)
        val (record, cycle) = registry.lineage(project, id, caller, now)
        cycle.lineage.find(_.member == member) match {
          case Some(entry) =>
            if (!entry.parent.contains(parent)) throw DomainFailure(Fault.Conflict("Lineage member is already registered under another parent"))
            // A member the session resumed is in flight again.
            if (cycle.resting(member)) registry.put(record.copy(cycle = Some(cycle.copy(resting = cycle.resting - member, prompted = cycle.prompted - member)), touchedAt = now))
            DriverReply.Lineage(id, entry)
          case None =>
            LedgerPolicy.invalid(cycle.lineage.exists(_.member == parent), "Lineage parent is not a member of this cycle")
            if (cycle.lineage.size >= MaxLineage) throw DomainFailure(Fault.Limit(s"A cycle holds at most $MaxLineage lineage members"))
            val entry = LineageEntry(member, Some(parent), false)
            registry.put(record.copy(cycle = Some(cycle.copy(lineage = cycle.lineage :+ entry)), touchedAt = now))
            DriverReply.Lineage(id, entry)
        }
      case DriverSession.Settle(id, member) =>
        val (record, cycle, entry) = registered(scope, id, member)
        val settled = entry.copy(settled = true)
        registry.put(record.copy(cycle = Some(cycle.copy(lineage = cycle.lineage.map(value => if (value.member == member) settled else value))), touchedAt = now))
        DriverReply.Lineage(id, settled)
      case DriverSession.Rest(id, member) =>
        val (record, cycle, entry) = registered(scope, id, member)
        registry.put(record.copy(cycle = Some(cycle.copy(resting = cycle.resting + member)), touchedAt = now))
        DriverReply.Lineage(id, entry)
      // The attached host can no longer account for the member, so the drive ends instead of continuing on lineage the host does not hold.
      case DriverSession.Fail(id, member, detail) =>
        LedgerAccess.write(scope)
        LedgerPolicy.invalid(detail.trim.nonEmpty && detail.length <= MaxDetail, s"A lineage failure requires a detail of 1–$MaxDetail characters")
        val (record, cycle) = registry.lineage(project, id, caller, now)
        val value = DriverStopped(DriverStop.Failure, s"${DriverPolicy.member(member)} of cycle ${cycle.number} $detail")
        val next = stopped(record, value, false, now)
        registry.put(next)
        DriverReply.Stop(value, Some(status(next)), Nil)
      case _: DriverSession.Change => throw new IllegalStateException("A cycle-attributed change is a ledger mutation")
    }
  }

  private def registered(scope: Scope, id: CycleId, member: LineageMember): (DriverRecord, CycleRecord, LineageEntry) = {
    LedgerAccess.write(scope)
    val record = registry.all(scope.project).find(_.cycle.exists(_.id == id)).getOrElse(throw DomainFailure(Fault.Missing("Unknown CQ driver cycle")))
    val cycle = record.cycle.get
    if (!record.attached.contains(scope.actor.session) && !cycle.delegated(scope.actor.session)) denied("Only a session in the cycle's lineage settles its members")
    (record, cycle, cycle.lineage.find(_.member == member).getOrElse(throw DomainFailure(Fault.Missing("Unknown lineage member"))))
  }

  private def governor(scope: Scope): Unit =
    if (scope.actor.role != Role.Governor) denied("Only a CQ attached session binds a driver or activates a driven workflow")

  // Start and resume are separate: a start token creates the cycle's one run, a resume token only reattaches to it.
  private def activate(project: ProjectId, caller: SessionId, run: RequestId, request: WorkflowRequest, presented: Option[CycleToken], now: Long): DriverActivation =
    registry.bound(project, caller) match {
      case None => presented match {
        case None => DriverActivation.Undriven()
        case Some(value) =>
          val raw = value match { case CycleToken.Start(start) => start; case CycleToken.Resume(resume) => resume }
          registry.all(project).find(record => record.on && record.cycle.exists(_.tokens(raw)))
            .foreach(registry.fail(_, "a driver token was presented by a session other than the bound attached session", now))
          denied("No CQ driver is on for this attached session; its driver token is not valid here")
      }
      case Some(record) =>
        def fail(detail: String): Nothing = registry.fail(record, detail, now)
        val value = presented.getOrElse(fail("untracked activation: the bound attached session activated a workflow without a start or resume token"))
        val cycle = record.cycle.filter(_.state != CycleState.Ended).getOrElse(fail("untracked activation: no driven cycle is pending or active"))
        if (request != WorkflowRequest.Advance(cycle.roots, cycle.through))
          fail(s"the activation's workflow, roots or phase differ from the directive of cycle ${cycle.number}")
        value match {
          case CycleToken.Start(start) =>
            if (start != cycle.startToken) fail("unknown start token")
            if (cycle.run.contains(run)) DriverActivation.Started(cycle.id)
            else {
              if (cycle.state != CycleState.Pending) fail(s"the start token of cycle ${cycle.number} was already used")
              registry.put(record.copy(cycle = Some(cycle.copy(state = CycleState.Active, run = Some(run),
                lineage = List(LineageEntry(LineageMember.Run(run), None, false)))), touchedAt = now))
              DriverActivation.Started(cycle.id)
            }
          case CycleToken.Resume(resume) =>
            if (!cycle.active) fail("a resume token was presented for a cycle that has not started")
            if (cycle.resumed.get(resume).contains(run)) DriverActivation.Resumed(cycle.id, cycle.run.get)
            else {
              if (!cycle.resumeToken.contains(resume)) fail("unknown or already used resume token")
              registry.put(record.copy(cycle = Some(cycle.copy(resumeToken = None, resumed = cycle.resumed.updated(resume, run))), touchedAt = now))
              DriverActivation.Resumed(cycle.id, cycle.run.get)
            }
        }
    }
}
