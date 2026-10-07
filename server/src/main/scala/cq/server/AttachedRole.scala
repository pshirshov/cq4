package cq.server

import cq.api.*
import cq.host.*
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.fundamentals.platform.cli.model.EntrypointArgs
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import logstage.IzLogger
import java.io.{InputStream, OutputStream}
import java.time.{Clock, Duration}
import java.util.concurrent.atomic.AtomicBoolean
import zio.{Task, Unsafe, ZIO}

final case class AttachedChannels(input: InputStream, output: OutputStream, owner: ProcessOwner)

final class AttachedProgram(config: SupervisorConfig, authority: SupervisorAuthority, gateway: AttachedGateway,
  dispatch: DispatchController, units: DispatchUnits, integrations: IntegrationController, combinations: CombinationController, revalidations: RevalidationController,
  watchdog: SupervisorWatchdog, channels: AttachedChannels, clock: Clock, local: LocalControlServer,
  codex: AttachedCodexUsage, cleanup: WorkspaceCleanup, release: SessionRelease, claims: SessionClaims, location: ProjectLocation, wait: WaitCommand, logger: IzLogger) {
  private val sessions = new AttachedSessions(location.directory)
  private val MaxRecordBytes = 65536
  private val limits = PeerLimits(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30), AttachedGateway.FrameBytes, 32)
  private lazy val queue = new DeliveryQueue(config.directory.resolve("delivery"))
  private val recorded = new AtomicBoolean(false)
  /** Records the session and registers its governing attempt, once, when the session first does governing work (`AttachedGateway.handle`).
    * A host whose harness only opened the connection writes nothing into its session directory and tells the server of no attempt (D160). */
  private def record: Task[Unit] = ZIO.suspend(if (recorded.compareAndSet(false, true)) initial else ZIO.unit)
  private def initial: Task[Unit] = ZIO.attemptBlocking {
    require(config.run.ownership == SessionOwnership.Attached, "Attached host requires attached ownership")
    HostFiles.directory(config.directory)
    HostFiles.immutable(config.directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, config.run), MaxRecordBytes)
    HostFiles.immutable(config.directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, config.settings), MaxRecordBytes)
    HostFiles.immutable(config.directory.resolve("owner.json"), io.circe.Json.obj("pid" -> io.circe.Json.fromLong(channels.owner.pid),
      "startMillis" -> io.circe.Json.fromLong(channels.owner.startMillis)).noSpaces, 1024)
    SessionWaiters.create(config.directory)
    sessions.record(config.run.attempt.session, AttachedHostRecord(config.directory.toString, wait.line))
    queue.enqueue(0, DeliveryBatch(List(
      HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Assign(config.run.assignment))),
      HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Start(config.run.attempt))))))
    queue.flush(authority.collector)
  } *> cleanup.recover.forkDaemon.unit
  private def shutdown: Task[Unit] = integrations.shutdown.zipPar(combinations.shutdown).zipPar(revalidations.shutdown).zipPar(units.shutdown).unit
  private def observe(operation: => Unit): Task[Unit] = ZIO.attemptBlocking(operation).catchAll { error => ZIO.attempt {
    codex.failure(error)
    val problem = codex.status
    logger.warn(s"Attached usage observation failed: $problem")
  }}.uninterruptible
  private val monitor: Task[Nothing] = (observe(codex.poll(authority.collector)) *> ZIO.sleep(zio.Duration.fromSeconds(5))).forever
  private def finish(peer: StdioPeer): Task[Unit] =
    (ZIO.succeed(peer.close()) *> shutdown *> ZIO.when(recorded.get())(observe(codex.finish(authority.collector)) *>
      ZIO.attemptBlocking {
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(config.run.attempt.id, "outcome").value), config.run.attempt.id,
          AttemptState.Unknown, math.max(config.run.attempt.startedAt, clock.millis()),
          List(peer.reason.getOrElse("Attached session ended"),
            "Outer interactive model completion is unobserved; Pi finalized messages and bound Codex response records cover only observed usage; Claude outer usage is unavailable; managed children are independent") ++
            codex.gaps, None)
        queue.commit(List(HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Finish(outcome)))))
        queue.flush(authority.collector)
        new SessionSpans(config, authority).flush()
      }) *>
      // The claims go once the work under them has settled or was cancelled and the Finish is committed locally; a claim under which a
      // child's result still awaits admission stays, and a host that dies leaves them all to their leases.
      claims.release(dispatch.undelivered) *> release.finish).ensuring(ZIO.attemptBlocking {
        sessions.forget(config.run.attempt.session)
        codex.close()
      }.orDie)
  private def loop(peer: StdioPeer): Task[Unit] = ZIO.attemptBlocking(peer.receive()).flatMap {
    case None => ZIO.unit
    case Some(request) =>
      // A request that waits for work is allowed that wait on top of the deadline every other request keeps, so a host that hangs is
      // still noticed as early as before.
      val deadline = AttachedGateway.deadline(request)
      (ZIO.attempt(peer.beginOperation(deadline)) *> gateway.handle(peer, request, record))
      .timeoutFail(new IllegalStateException(s"Attached MCP operation exceeded ${deadline.toSeconds} seconds"))(zio.Duration.fromJava(deadline))
      .ensuring(ZIO.succeed(peer.endOperation()))
      .flatMap(value => ZIO.attempt(value.foreach(peer.send))) *> ZIO.suspendSucceed(loop(peer))
  }
  def run: Task[Unit] = ZIO.runtime[Any].flatMap { runtime => ZIO.acquireReleaseWith(
    ZIO.attempt {
      val peer = new StdioPeer(channels.input, channels.output, channels.owner, limits, () => {
        watchdog.beginShutdown()
        Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(shutdown.orDie); () }
      })
      val guard = new TerminationGuard("cq-attached-termination", () => peer.close("Owning harness signalled termination"))
      guard.install()
      (peer, guard)
    })({ (peer, guard) => finish(peer).orDie.ensuring(ZIO.succeed(guard.release())) })({ (peer, _) =>
      ZIO.acquireReleaseWith(monitor.interruptible.fork)(_.interrupt)( _ => loop(peer)) }) }
}

final class AttachedRole(program: AttachedProgram) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = program.run
}
object AttachedRole extends RoleDescriptor {
  override val id = "host"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("Harness-owned CQ stdio MCP host"), Some("cq host HARNESS [--settings FILE]; CQ_SETTINGS may supply the settings path"), freeArgsAllowed = true)
}
