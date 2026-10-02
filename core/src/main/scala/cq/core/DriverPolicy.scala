package cq.core

import cq.api.*

sealed trait DriverDecision
object DriverDecision {
  case object Continue extends DriverDecision
  final case class Stop(value: DriverStopped) extends DriverDecision
}

object DriverPolicy {
  val MaxSessionKey = 200
  val MaxDrivers = 64
  // Start and resume directives issued by one drive; each is one harness continuation.
  val MaxDirectives = 64
  val MaxLineage = 1024
  val MaxDetail = 512
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
        invalid(extension || origin == DriverOrigin.UserPromptSubmit, "Park belongs to the UserPromptSubmit hook and the Pi extension")
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
  def awaitsUser(item: ItemSummary): Boolean = !item.archived && (item.id.ledger match {
    case Ledger.Questions => item.status == QuestionStatus.Open.toString
    case Ledger.OperatorActions => item.status == OperatorActionStatus.Requested.toString
    case _ => false
  })

  // The readiness decision of one cycle, made only from its issue-time snapshot and the snapshot of the cycle before it.
  def decide(snapshot: WorksetPreview, previous: Option[CycleRecord]): DriverDecision = {
    val items = (snapshot.advanceable.map(_.item) ++ snapshot.context.map(_.item)).map(item => item.id -> item).toMap
    val (waiting, work) = snapshot.readiness.filter(_.ready).map(entry => items(entry.item)).partition(awaitsUser)
    val blockers = snapshot.readiness.flatMap(_.reasons).collect { case WorksetReason.Blocked(prerequisite) => prerequisite }.distinct
      .flatMap(items.get).filter(awaitsUser)
    val user = (waiting ++ blockers).map(_.id).distinct
    val unchanged = previous.exists(_.snapshot.copy(snapshot = snapshot.snapshot) == snapshot)
    if (work.nonEmpty && !unchanged) DriverDecision.Continue
    else if (user.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.UserInputRequired,
      s"Awaiting the user on ${references(user)}; the driver never answers questions or infers approval"))
    else if (work.nonEmpty) DriverDecision.Stop(DriverStopped(DriverStop.Quiescent,
      "The previous cycle changed nothing in the advanceable set, its context or its readiness"))
    else DriverDecision.Stop(DriverStopped(DriverStop.Quiescent, "No item of the advanceable set is ready to advance"))
  }

  def changed(previous: CycleRecord, snapshot: WorksetPreview): Option[String] = {
    val current = snapshot.advanceable.map(_.item.id).toSet
    val added = current -- previous.advanceable
    val removed = previous.advanceable -- current
    if (added.isEmpty && removed.isEmpty) None
    else Some(s"CQ driver: the advanceable set changed to ${current.size} items" +
      (if (added.isEmpty) "" else "; added " + references(added)) + (if (removed.isEmpty) "" else "; removed " + references(removed)))
  }

  def ended(cycle: CycleRecord): CycleRecord = cycle.copy(state = CycleState.Ended, resumeToken = None,
    lineage = cycle.lineage.map(entry => if (entry.member.isInstanceOf[LineageMember.Run]) entry.copy(settled = true) else entry))

  // A stop turns the driver off, ends its cycle and releases the bind offer. `announced` records whether a control reply carried the reason.
  def stopped(record: DriverRecord, value: DriverStopped, announced: Boolean, now: Long): DriverRecord =
    record.copy(state = DriverState.Off, bind = None, cycle = record.cycle.map(ended), stopped = Some(value), announced = announced, touchedAt = now)

  def reason(value: DriverStop): String = value match {
    case DriverStop.Quiescent => "quiescent"
    case DriverStop.UserInputRequired => "user input required"
    case DriverStop.LimitReached => "limit reached"
    case DriverStop.NotBound => "not bound"
    case DriverStop.Failure => "failure"
    case DriverStop.Parked => "parked"
    case DriverStop.Off => "off"
  }

  def describe(record: DriverRecord): String = s"${references(record.targets)} through ${phase(record.through)}"

  def stopMessage(value: DriverStopped): String = s"CQ driver stopped (${reason(value.reason)}): ${value.detail}"

  // The indicator text every harness shows: state, workset roots, through phase, active child count and the last stop reason.
  def line(record: DriverRecord): String = record.state match {
    case DriverState.On =>
      val children = record.cycle.filter(_.active).fold(0)(_.activeChildren)
      s"CQ driver on: ${describe(record)}; $children active ${if (children == 1) "child" else "children"}"
    case DriverState.Binding => s"CQ driver binding: ${describe(record)}"
    case DriverState.Off => s"CQ driver off: ${describe(record)}" + record.stopped.fold("")(value => s"; stopped (${reason(value.reason)}): ${value.detail}")
  }

  def status(record: DriverRecord): DriverStatus = DriverStatus(record.key, record.state, record.attached.filter(_ => record.on), record.workset,
    record.targets, record.through,
    record.cycle.map(cycle => DriverCycle(cycle.id, cycle.number, cycle.state, cycle.roots, cycle.through, cycle.snapshot.snapshot,
      cycle.snapshot.advanceable.map(member => ItemRevision(member.item.id, member.item.revision)), cycle.run, cycle.created, cycle.lineage)),
    record.cycle.filter(_.active).fold(0)(_.activeChildren), record.directives, record.stopped, line(record))
}
