package cq.server

import cq.api.*
import cq.host.*
import java.time.{Clock, Duration}
import zio.{Task, ZIO}

final case class SupervisorAuthority(root: ServerApi, collector: ServerApi, governor: ServerApi, governorToken: AccessToken, expiresAt: Long)

object SupervisorAuthority {
  private val HttpDeadline = Duration.ofSeconds(10)
  def acquire(config: SupervisorConfig, clock: Clock): Task[SupervisorAuthority] = ZIO.attemptBlocking {
    val session = config.run.attempt.session
    val root = new HttpServerApi(config.endpoint, config.environment.getOrElse("CQ_TOKEN", throw new IllegalArgumentException("CQ_TOKEN is required")), session, HttpDeadline)
    require(root.call(Command.Initialize(config.project)).isInstanceOf[Result.Initialized], "Project attachment failed")
    val expires = clock.millis() + SupervisorConfig.credentialLifetime(config.limits).toMillis
    val collector = root.grant(GrantRequest(config.project.project, Actor("CQ host collector", session, Role.Collector), expires))
    val governor = root.grant(GrantRequest(config.project.project, config.owner.actor, expires))
    SupervisorAuthority(root, new HttpServerApi(config.endpoint, collector.value, session, HttpDeadline),
      new HttpServerApi(config.endpoint, governor.value, session, HttpDeadline), governor, expires)
  }
}
