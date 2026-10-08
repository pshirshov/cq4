package cq.core

import cq.api.*
import DriverRecords.*

sealed trait DriverDecision
object DriverDecision {
  // `offered` holds the failed attempts of the previous cycle when they alone are why the drive continues, and `retried` those and the
  // ones of the unchanged cycles before it, the latest for each input.
  final case class Continue(retried: List[ChildOutcome], offered: List[ChildOutcome]) extends DriverDecision
  final case class Stop(value: DriverStopped) extends DriverDecision
}

object DriverPolicy {

  val MaxSessionKey = 200
  val MaxDrivers = 64
  // Start and resume directives issued by one drive; each is one harness continuation.
  val MaxDirectives = 64
  // Every driver reply carries the whole lineage of the latest cycle in its DriverStatus, and the host reads no reply above 2 MiB
  // (ServerApi). An entry encodes to about 165 bytes, so a cycle without a bound would, past some 12,000 members, make every reply of
  // its driver unreadable: continuation, status and park alike. The bound keeps the reply an order of magnitude below that.
  val MaxLineage = 1024
  val MaxDetail = 512
  // Outside blockers a stop detail names, and blocked items it names for each; the rest are counted.
  val MaxBlockersNamed = 8
  val BindMillis = 600000L
  // A driver untouched for this long belongs to a harness session that ended without parking.
  val IdleMillis = 8L * 60 * 60 * 1000
  val RootsFlag = "--roots"
  val ThroughFlag = "--through"
  val StartFlag = "--start-token"
  val ResumeFlag = "--resume-token"

  import LedgerPolicy.invalid

  // The key is the harness-supplied session identity (Decision 2): validated for shape, never authenticated and never defaulted.
  def key(value: DriverKey): Unit = {
    invalid(value.session.nonEmpty, "Driver session key is missing; no default session is used")
    invalid(value.session.length <= MaxSessionKey && value.session.matches("[A-Za-z0-9][A-Za-z0-9._:-]*"),
      s"Driver session key is malformed: expected 1–$MaxSessionKey characters of letters, digits, '.', '_', ':' or '-'")
  }

  def origin(key: DriverKey, origin: DriverOrigin, action: DriverControl): Unit = {
    val extension = origin == DriverOrigin.Extension
    invalid((key.harness == Harness.Pi) == extension, "Pi drives from its CQ extension; Claude Code and Codex drive from the CQ hook commands")
    action match {
      case DriverControl.Start(_, attached) =>
        invalid(extension || origin == DriverOrigin.UserPromptSubmit, "Drive-start belongs to the UserPromptSubmit hook and the Pi extension")
        invalid(attached.nonEmpty == extension, "The Pi extension supplies its attached session at drive-start; hook sessions bind with the hook-minted token")
      case _: DriverControl.Park =>
        // The Stop hook parks a drive whose attached host is gone while its work was in flight.
        invalid(extension || origin == DriverOrigin.UserPromptSubmit || origin == DriverOrigin.Stop, "Park belongs to the UserPromptSubmit and Stop hooks and the Pi extension")
      case _: DriverControl.Continue =>
        invalid(extension || origin == DriverOrigin.Stop, "The continuation query belongs to the Stop hook and the Pi extension")
      case _: DriverControl.Status => ()
    }
  }

  def reference(id: ItemId): String = LedgerPolicy.prefix(id.ledger) + id.number
  def references(ids: Iterable[ItemId]): String = ids.toList.sortBy(LedgerPolicy.key).map(reference).mkString(",")
  def phase(value: WorkflowPhase): String = value.toString.toLowerCase
  def member(value: LineageMember): String = value match {
    case LineageMember.Run(id) => s"run ${id.value}"
    case LineageMember.Session(id) => s"session ${id.value}"
    case LineageMember.Request(id) => s"request ${id.value}"
    case LineageMember.Attempt(id) => s"attempt ${id.value}"
    case LineageMember.Claim(id) => s"claim ${id.value}"
    case LineageMember.Proposal(id) => s"proposal ${id.value}"
    case LineageMember.Change(id) => s"change ${id.value}"
    case LineageMember.Integration(id) => s"integration ${id.value}"
    case LineageMember.Combination(id) => s"combination ${id.value}"
  }
  def answerRefused(questions: Iterable[ItemId]): String =
    s"The CQ driver never answers Questions: ${references(questions)} would be answered by a driven session; park the driver before recording the user's answer"
  def withdrawalRefused(questions: Iterable[ItemId]): String =
    s"The CQ driver never settles Questions: ${references(questions)} would be withdrawn by a driven session; park the driver before withdrawing a Question"
  def settlementRefused(actions: Iterable[ItemId]): String =
    s"The CQ driver never settles Operator Actions: ${references(actions)} would be taken out of Requested by a driven session; park the driver before settling an Operator Action"

  def invocation(harness: Harness): String = harness match {
    case Harness.Claude | Harness.Pi => "/cq:advance"
    case Harness.Codex => "$cq-advance"
  }

  // The exact advance invocation the harness session must submit unchanged.
  def directive(harness: Harness, cycle: CycleRecord, token: CycleToken): DriverDirective = {
    val (flag, value) = token match {
      case CycleToken.Start(start) => StartFlag -> start
      case CycleToken.Resume(resume) => ResumeFlag -> resume
    }
    DriverDirective(cycle.id, token,
      s"${invocation(harness)} $RootsFlag ${references(cycle.roots)} $ThroughFlag ${phase(cycle.through)} $flag ${value.value}")
  }

  // Only a person can settle these; the driver never answers a question or infers an approval.
  // The write boundary judges a written item by the same predicate, so a write that ends a wait is exactly one that changes this.
  def awaitsUser(ledger: Ledger, status: String, archived: Boolean): Boolean = !archived && (ledger match {
    case Ledger.Questions => status == QuestionStatus.Open.toString
    case Ledger.OperatorActions => status == OperatorActionStatus.Requested.toString
    case _ => false
  })
  def awaitsUser(item: ItemSummary): Boolean = awaitsUser(item.id.ledger, item.status, item.archived)
  // The ledgers whose items can wait for a person.
  def awaitable(ledger: Ledger): Boolean = ledger == Ledger.Questions || ledger == Ledger.OperatorActions

  def outcome(value: ChildOutcome): Unit = {
    invalid(value.members.nonEmpty, "An attempt outcome names the items of its attempt")
    invalid(value.input.forall(input => input.nonEmpty && input.length <= MaxDetail), s"An attempt outcome's input fingerprint has 1–$MaxDetail characters")
    invalid(value.fault.forall(fault => fault.trim.nonEmpty && fault.length <= MaxDetail), s"An attempt outcome's fault has 1–$MaxDetail characters")
    invalid(!Set(ChildEnd.Retryable, ChildEnd.Repeated)(value.end) || (value.input.nonEmpty && value.fault.nonEmpty),
      "A retryable or repeated failure carries its input fingerprint and its fault")
    invalid(value.end != ChildEnd.Abstained || (value.input.nonEmpty && value.fault.nonEmpty),
      "An abstention carries its input fingerprint and, as its fault, which models abstained and why")
  }

  // The inputs a cycle left retryable: every attempt the cycle made on them failed without a result and the host offers them again.
  // Each is represented by the last such attempt the host reported. An abstention is no attempt on the input: no model ran on it.
  def retryable(cycle: CycleRecord): List[ChildOutcome] = {
    val attempted = cycle.outcomes.filterNot(_.end == ChildEnd.Abstained)
    val inputs = attempted.groupBy(_.input)
    attempted.filter(value => inputs(value.input).forall(_.end == ChildEnd.Retryable) && inputs(value.input).last == value)
  }

  // The inputs a cycle left without a model: every unit the cycle started on them abstained. Each is represented by the last one.
  def abstained(cycle: CycleRecord): List[ChildOutcome] = {
    val inputs = cycle.outcomes.groupBy(_.input)
    cycle.outcomes.filter(value => inputs(value.input).forall(_.end == ChildEnd.Abstained) && inputs(value.input).last == value)
  }

  def repeated(member: LineageMember, cycle: CycleRecord, value: ChildOutcome): DriverStopped = DriverStopped(DriverStop.Failure,
    s"${DriverPolicy.member(member)} of cycle ${cycle.number} failed on ${references(value.members)} with the same fault as the attempt before it on the same input: ${value.fault.get}")

  def retrying(previous: CycleRecord, retried: List[ChildOutcome]): Option[String] = Option.when(retried.nonEmpty)(
    s"CQ driver: cycle ${previous.number} changed nothing, and its work on ${retried.map(value => references(value.members)).distinct.mkString("; ")} " +
      "failed without a result; the same input is offered again with the fault")

  // The readiness decision of one cycle, made from its issue-time snapshot, the snapshot of the cycle before it and what the attached
  // host reported about that cycle's child attempts.
  def decide(snapshot: WorksetPreview, previous: Option[CycleRecord]): DriverDecision = {
    val items = (snapshot.advanceable.map(_.item) ++ snapshot.context.map(_.item)).map(item => item.id -> item).toMap
    val work = snapshot.readiness.filter(_.ready).map(entry => items(entry.item)).filterNot(awaitsUser)
    val user = awaited(snapshot)
    val unchanged = previous.exists(_.snapshot.copy(snapshot = snapshot.snapshot) == snapshot)
    // A cycle that changed nothing but left failed inputs for the host to offer again is not quiescent. Its `retried` holds what the
    // unchanged cycles before it left in the same way, so an input that fails a second time before any cycle changes something ends the
    // drive, whatever the fault texts and whichever other inputs failed in between.
    val offered = if (unchanged) previous.toList.flatMap(retryable) else Nil
    val earlier = previous.toList.flatMap(_.retried)
    val again = for { now <- offered; before <- earlier if before.input == now.input } yield before -> now
    val unserved = if (unchanged) previous.toList.flatMap(abstained) else Nil
    if (work.nonEmpty && !unchanged) DriverDecision.Continue(Nil, Nil)
    else if (work.nonEmpty && again.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.Failure, again.map { (before, now) =>
      s"${references(now.members)} failed without a result twice on the same input while no cycle in between changed anything: " +
        s"attempt ${before.attempt.value}: ${before.fault.get}; attempt ${now.attempt.value} of cycle ${previous.get.number}: ${now.fault.get}"
    }.mkString("; ")))
    else if (work.nonEmpty && offered.nonEmpty)
      DriverDecision.Continue(earlier.filterNot(before => offered.exists(_.input == before.input)) ++ offered, offered)
    else if (user.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.UserInputRequired,
      s"Awaiting the user on ${references(user)}; the driver never answers questions or infers approval"))
    // Nothing changed because no model could run the work; selecting it again at once would find the same models unavailable.
    else if (work.nonEmpty && unserved.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.Failure,
      "No configured model could run " + unserved.map(value => s"${references(value.members)}: ${value.fault.get}").mkString("; ")))
    else if (work.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.Quiescent,
      "The previous cycle changed nothing in the advanceable set, its context or its readiness" + blocked(snapshot)))
    else DriverDecision.Stop(DriverStopped(DriverStop.Quiescent, "No item of the advanceable set is ready to advance" + blocked(snapshot)))
  }

  // What a set waits for a person on: its ready items that only a person settles, and such items that block one of its members.
  def awaited(snapshot: WorksetPreview): List[ItemId] = {
    val items = (snapshot.advanceable.map(_.item) ++ snapshot.context.map(_.item)).map(item => item.id -> item).toMap
    val waiting = snapshot.readiness.filter(_.ready).map(entry => items(entry.item)).filter(awaitsUser)
    val blockers = snapshot.readiness.flatMap(_.reasons).collect { case WorksetReason.Blocked(prerequisite) => prerequisite }.distinct
      .flatMap(items.get).filter(awaitsUser)
    (waiting ++ blockers).map(_.id).distinct
  }

  // The prerequisites outside the advanceable set that keep its items from being ready. The drive cannot change them, so the stop names
  // them: the first MaxBlockersNamed in ledger order, each with as many of the items it blocks, and a count of the rest.
  private def blocked(snapshot: WorksetPreview): String = {
    val advanceable = snapshot.advanceable.map(_.item.id).toSet
    val outside = snapshot.readiness.flatMap(entry => entry.reasons.collect {
      case WorksetReason.Blocked(prerequisite) if !advanceable(prerequisite) => prerequisite -> entry.item
    }).groupMap((prerequisite, _) => prerequisite)((_, item) => item).toList.sortBy((prerequisite, _) => LedgerPolicy.key(prerequisite))
    def more(count: Int, separator: String): String = if (count > MaxBlockersNamed) s"${separator}and ${count - MaxBlockersNamed} more" else ""
    if (outside.isEmpty) "" else "; blocked from outside the set: " + outside.take(MaxBlockersNamed).map { (prerequisite, items) =>
      val blocked = items.distinct.sortBy(LedgerPolicy.key)
      s"${reference(prerequisite)} blocks ${references(blocked.take(MaxBlockersNamed))}${more(blocked.size, " ")}"
    }.mkString("; ") + more(outside.size, "; ")
  }

  def changed(previous: CycleRecord, snapshot: WorksetPreview): Option[String] = {
    val current = snapshot.advanceable.map(_.item.id).toSet
    val added = current -- previous.advanceable
    val removed = previous.advanceable -- current
    if (added.isEmpty && removed.isEmpty) None
    else Some(s"CQ driver: the advanceable set changed to ${current.size} items" +
      (if (added.isEmpty) "" else "; added " + references(added)) + (if (removed.isEmpty) "" else "; removed " + references(removed)))
  }

  // What a drive leaves to the next one: the integrations it carried itself and those of its last cycle that never settled. The number
  // of integrations an attached host prepares is not bounded, so only the lineage bound of each contributing cycle bounds this set.
  def outstanding(record: DriverRecord): Map[SessionId, Set[IntegrationId]] = {
    val unsettled = record.cycle.toList.flatMap(_.lineage).collect { case LineageEntry(LineageMember.Integration(id), _, false) => id }.toSet
    record.attached.filter(_ => unsettled.nonEmpty).fold(record.carried)(session => carry(record.carried, session, unsettled))
  }
  def carry(carried: Map[SessionId, Set[IntegrationId]], session: SessionId, integrations: Set[IntegrationId]): Map[SessionId, Set[IntegrationId]] =
    if (integrations.isEmpty) carried else carried.updated(session, carried.getOrElse(session, Set.empty) ++ integrations)

  def ended(cycle: CycleRecord): CycleRecord = cycle.copy(state = CycleState.Ended, resumeToken = None,
    lineage = cycle.lineage.map(entry => if (entry.member.isInstanceOf[LineageMember.Run]) entry.copy(settled = true) else entry))

  // A stop turns the driver off, ends its cycle and releases the bind offer. `announced` records whether a control reply carried the reason.
  def stopped(record: DriverRecord, value: DriverStopped, announced: Boolean, now: Long): DriverRecord =
    record.copy(state = DriverState.Off, bind = None, cycle = record.cycle.map(ended), stopped = Some(value), announced = announced, touchedAt = now, stoppedAt = Some(now))

  // A drive that stopped for user input rests: it is off, and the next continuation query decides it anew (D164).
  def rests(record: DriverRecord): Boolean = record.state == DriverState.Off && record.announced && record.attached.nonEmpty &&
    record.stopped.exists(_.reason == DriverStop.UserInputRequired)
  val Rested = "CQ driver: the user settled what this drive waited for; it continues"
  // The settlements a session that is no person's made of what a resting drive waited for: the drive does not continue on them.
  def settledByAgents(settled: List[(ItemId, Role)]): DriverStopped = DriverStopped(DriverStop.Failure,
    settled.map((id, role) => s"${reference(id)} was settled by a $role session").mkString(", ") + ", not by a person; a drive continues only on what a person decided")

  def reason(value: DriverStop): String = value match {
    case DriverStop.Quiescent => "quiescent"
    case DriverStop.UserInputRequired => "user input required"
    case DriverStop.LimitReached => "limit reached"
    case DriverStop.NotBound => "not bound"
    case DriverStop.Failure => "failure"
    case DriverStop.Parked => "parked"
    case DriverStop.Off => "off"
    case DriverStop.RestoredArchive => "restored archive"
  }

  def describe(record: DriverRecord): String = s"${references(record.targets)} through ${phase(record.through)}"

  def stopMessage(value: DriverStopped): String = s"CQ driver stopped (${reason(value.reason)}): ${value.detail}"

  // The indicator text every harness shows: state, workset roots, through phase, active child count and the last stop reason.
  def line(record: DriverRecord): String = record.state match {
    case DriverState.On =>
      val children = record.cycle.filter(_.active).fold(0)(_.activeChildren)
      s"CQ driver on: ${describe(record)}; $children active ${if (children == 1) "child" else "children"}"
    case DriverState.Binding => s"CQ driver binding: ${describe(record)}"
    case DriverState.Off if rests(record) => s"CQ driver resting: ${describe(record)}; ${record.stopped.get.detail}"
    case DriverState.Off => s"CQ driver off: ${describe(record)}" + record.stopped.fold("")(value => s"; stopped (${reason(value.reason)}): ${value.detail}")
  }

  def status(record: DriverRecord): DriverStatus = DriverStatus(record.key, record.state, record.attached.filter(_ => record.on || rests(record)), record.workset,
    record.targets, record.through,
    record.cycle.map(cycle => DriverCycle(cycle.id, cycle.number, cycle.state, cycle.roots, cycle.through, cycle.snapshot.snapshot,
      cycle.snapshot.advanceable.map(member => ItemRevision(member.item.id, member.item.revision)), cycle.run, cycle.created, cycle.lineage)),
    record.cycle.filter(_.active).fold(0)(_.activeChildren), record.directives, record.stopped, line(record))
}
