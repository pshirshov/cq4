package cq.server

import cq.api.*
import cq.host.*
import izumi.distage.roles.model.{RoleDescriptor, RoleTask}
import izumi.fundamentals.platform.cli.model.EntrypointArgs
import izumi.fundamentals.platform.cli.model.schema.{ParserDef, RoleParserSchema}
import logstage.IzLogger
import java.io.{InputStream, OutputStream}
import java.time.{Clock, Duration}
import zio.{Task, Unsafe, ZIO}

final case class AttachedChannels(input: InputStream, output: OutputStream, owner: ProcessOwner)

final class AttachedProgram(config: SupervisorConfig, authority: SupervisorAuthority, gateway: AttachedGateway,
  dispatch: DispatchController, integrations: IntegrationController, combinations: CombinationController, revalidations: RevalidationController,
  watchdog: SupervisorWatchdog, channels: AttachedChannels, clock: Clock, local: LocalControlServer,
  codex: AttachedCodexUsage, cleanup: WorkspaceCleanup, release: SessionRelease, claims: SessionClaims, logger: IzLogger) {
  private val MaxRecordBytes = 65536
  private val RequestSeconds = 30L
  private val limits = PeerLimits(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(RequestSeconds), AttachedGateway.FrameBytes, 32)
  private val queue = new DeliveryQueue(config.directory.resolve("delivery"))
  private def initial: Task[Unit] = ZIO.attemptBlocking {
    require(config.run.ownership == SessionOwnership.Attached, "Attached host requires attached ownership")
    HostFiles.directory(config.directory)
    HostFiles.immutable(config.directory.resolve("run.json"), HostFiles.encode(SupervisorRun_JsonCodec, config.run), MaxRecordBytes)
    HostFiles.immutable(config.directory.resolve("settings.json"), HostFiles.encode(SupervisorSettings_JsonCodec, config.settings), MaxRecordBytes)
    HostFiles.immutable(config.directory.resolve("owner.json"), io.circe.Json.obj("pid" -> io.circe.Json.fromLong(channels.owner.pid),
      "startMillis" -> io.circe.Json.fromLong(channels.owner.startMillis)).noSpaces, 1024)
    queue.enqueue(0, DeliveryBatch(List(
      HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Assign(config.run.assignment))),
      HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Start(config.run.attempt))))))
    queue.flush(authority.collector)
  } *> cleanup.recover.forkDaemon.unit
  private def shutdown: Task[Unit] = integrations.shutdown.zipPar(combinations.shutdown).zipPar(revalidations.shutdown).zipPar(dispatch.shutdown).unit
  private def observe(operation: => Unit): Task[Unit] = ZIO.attemptBlocking(operation).catchAll { error => ZIO.attempt {
    codex.failure(error)
    val problem = codex.status
    logger.warn(s"Attached usage observation failed: $problem")
  }}.uninterruptible
  private val monitor: Task[Nothing] = (observe(codex.poll(authority.collector)) *> ZIO.sleep(zio.Duration.fromSeconds(5))).forever
  private def finish(peer: StdioPeer): Task[Unit] =
    // The claims go once the work under them has settled or was cancelled; a host that dies leaves them to their leases.
    (ZIO.succeed(peer.close()) *> shutdown *> claims.release *> observe(codex.finish(authority.collector)) *>
      ZIO.attemptBlocking {
        val outcome = AttemptOutcome(RequestId(NativeArtifacts.id(config.run.attempt.id, "outcome").value), config.run.attempt.id,
          AttemptState.Unknown, math.max(config.run.attempt.startedAt, clock.millis()),
          List(peer.reason.getOrElse("Attached session ended"),
            "Outer interactive model completion is unobserved; Pi finalized messages and bound Codex response records cover only observed usage; Claude outer usage is unavailable; managed children are independent") ++
            codex.gaps, None)
        queue.commit(List(HostDelivery.Usage(HostUsageInput(config.project.project, HostUsage.Finish(outcome)))))
        queue.flush(authority.collector)
        new SessionSpans(config, authority).flush()
      }.unit *> release.finish).ensuring(ZIO.attemptBlocking(codex.close()).orDie)
  private def loop(peer: StdioPeer): Task[Unit] = ZIO.attemptBlocking(peer.receive()).flatMap {
    case None => ZIO.unit
    case Some(request) => (ZIO.attempt(peer.beginOperation()) *> gateway.handle(peer, request))
      .timeoutFail(new IllegalStateException("Attached MCP operation exceeded 30 seconds"))(zio.Duration.fromSeconds(RequestSeconds))
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
      initial *> ZIO.acquireReleaseWith(monitor.interruptible.fork)(_.interrupt)( _ => loop(peer)) }) }
}

final class AttachedRole(program: AttachedProgram) extends RoleTask[Task] {
  override def start(parameters: EntrypointArgs): Task[Unit] = program.run
}
object AttachedRole extends RoleDescriptor {
  override val id = "host"
  override def parserSchema: RoleParserSchema = RoleParserSchema(id, ParserDef.Empty,
    Some("Harness-owned CQ stdio MCP host"), Some("cq host HARNESS [--settings FILE]; CQ_SETTINGS may supply the settings path"), freeArgsAllowed = true)
}
