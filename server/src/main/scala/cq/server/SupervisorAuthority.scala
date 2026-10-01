package cq.server

import cq.api.*
import cq.host.*
import java.time.{Clock, Duration}
import zio.{Task, ZIO}

/** `collector` and `governor` renew their credentials for as long as the host runs; `governorToken` is the governing harness's own credential. */
final case class SupervisorAuthority(root: ServerApi, collector: ServerApi, governor: ServerApi, governorToken: AccessToken)

object SupervisorAuthority {
  private val HttpDeadline = Duration.ofSeconds(10)
  /** The server grants scoped credentials for at most 24 hours; the margin absorbs clock difference between host and server. */
  val GrantLifetime: Duration = Duration.ofHours(24).minus(Duration.ofMinutes(10))
  private val RenewalMargin = Duration.ofHours(1)
  /** A harness process keeps the credential it was launched with, so it is granted when the harness starts and cannot be renewed. */
  def harnessGrant(root: ServerApi, project: ProjectId, actor: Actor, clock: Clock): AccessToken =
    root.grant(GrantRequest(project, actor, Math.addExact(clock.millis(), GrantLifetime.toMillis)))
  def acquire(config: SupervisorConfig, clock: Clock): Task[SupervisorAuthority] = ZIO.attemptBlocking {
    val session = config.run.attempt.session
    val root = new HttpServerApi(config.endpoint, HostCredential.read(config.environment), session, HttpDeadline)
    require(root.call(Command.Initialize(config.project)).isInstanceOf[Result.Initialized], "Project attachment failed")
    def renewing(actor: Actor): ServerApi = new RenewingServerApi(root, config.project.project, actor,
      token => new HttpServerApi(config.endpoint, token.value, session, HttpDeadline), clock, GrantLifetime, RenewalMargin)
    SupervisorAuthority(root, renewing(Actor("CQ host collector", session, Role.Collector)), renewing(config.owner.actor),
      harnessGrant(root, config.project.project, config.owner.actor, clock))
  }
}
