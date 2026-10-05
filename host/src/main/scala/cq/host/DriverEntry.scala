package cq.host

import cq.api.*
import cq.core.DomainFailure

/** What a CQ hook command or the Pi extension received from its harness: the harness identifier, the hook event and the session key. */
final case class DriverCall(harness: String, event: String, session: Option[String])

/**
 * The state-changing driver entry points. Only the CQ UserPromptSubmit and Stop hook commands and the Pi extension call them, with the
 * operator credential; the session key is trusted as the harness supplied it (Decision 2) and never defaults.
 */
final class DriverEntry(api: ServerApi, project: ProjectId) {
  import DriverEntry.*

  private def control(call: DriverCall, action: DriverControl): DriverReply = {
    val (key, origin) = identify(call)
    api.call(Command.Driver(DriverInput(project, DriverRequest.Control(key, origin, action)))) match {
      case Result.Driver(reply) => reply
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Driver control returned an unexpected result")
    }
  }

  def start(call: DriverCall, arguments: String, attached: Option[SessionId]): DriverReply = {
    identify(call)
    control(call, DriverControl.Start(DriverArguments.parse(project, arguments), attached))
  }
  def park(call: DriverCall): DriverReply = control(call, DriverControl.Park())
  def continuation(call: DriverCall): DriverReply = control(call, DriverControl.Continue())
  def status(call: DriverCall): DriverReply = control(call, DriverControl.Status())
}

object DriverEntry {
  private def invalid(message: String): Nothing = throw DomainFailure(Fault.Invalid(message))

  def identify(call: DriverCall): (DriverKey, DriverOrigin) = {
    val harness = Harness.all.find(_.toString.toLowerCase == call.harness).getOrElse(invalid(s"Unknown harness identifier ${call.harness}"))
    val origin = DriverOrigin.all.find(_.toString == call.event).getOrElse(invalid(s"Unknown CQ hook event ${call.event}"))
    val session = call.session.filter(_.nonEmpty).getOrElse(invalid("Driver session key is missing; no default session is used"))
    (DriverKey(harness, session), origin)
  }
}

/** Driver operations of one attached session, under its governor credential: token-gated bind, read-only status, activation and lineage. */
final class DriverSessionClient(api: ServerApi, project: ProjectId) {
  private def call(action: DriverSession): DriverReply =
    api.call(Command.Driver(DriverInput(project, DriverRequest.Session(action)))) match {
      case Result.Driver(reply) => reply
      case Result.Failed(fault) => throw DomainFailure(fault)
      case _ => throw new IllegalStateException("Driver session operation returned an unexpected result")
    }

  def bind(token: DriverToken): DriverReply = call(DriverSession.Bind(token))
  def status: DriverReply = call(DriverSession.Status())
  /** The integrations this session may apply while its driver's start directive is pending; none is returned when no start directive is pending. */
  def settleable: Option[Set[IntegrationId]] = call(DriverSession.Settleable()) match {
    case DriverReply.Settleable(pending, integrations) => Option.when(pending)(integrations)
    case _ => throw new IllegalStateException("Driver settlement query returned an unexpected reply")
  }
  def activate(run: RequestId, request: WorkflowRequest, token: Option[CycleToken]): DriverActivation = call(DriverSession.Activate(run, request, token)) match {
    case DriverReply.Activation(value) => value
    case _ => throw new IllegalStateException("Driver activation returned an unexpected reply")
  }
  def inherit(cycle: CycleId, parent: LineageMember, member: LineageMember): Unit = { call(DriverSession.Inherit(cycle, parent, member)); () }
  def settle(cycle: CycleId, member: LineageMember): Unit = { call(DriverSession.Settle(cycle, member)); () }
  /** Settles a child attempt with how it ended. The server stops the driver when the attempt repeated the fault before it on the same input. */
  def conclude(cycle: CycleId, outcome: ChildOutcome): Unit = { call(DriverSession.Conclude(cycle, outcome)); () }
  def rest(cycle: CycleId, member: LineageMember): Unit = { call(DriverSession.Rest(cycle, member)); () }
  /** Stops the driver of `cycle` with reason failure; the detail continues the sentence that names the member. */
  def fail(cycle: CycleId, member: LineageMember, detail: String): DriverStopped = call(DriverSession.Fail(cycle, member, detail)) match {
    case DriverReply.Stop(stopped, _, _) => stopped
    case _ => throw new IllegalStateException("Driver lineage failure returned an unexpected reply")
  }
}
