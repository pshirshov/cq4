package cq.server

import cq.api.*
import cq.core.DomainFailure
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

final case class LocalCapability(attempt: AttemptId, role: Role)

final class LocalAccess(authority: SupervisorAuthority, clock: Clock) {
  private val MaxCapabilities = 33
  private var address = Option.empty[URI]
  private var grants = Map.empty[LocalCapability, AccessToken]
  def bind(value: URI): Unit = synchronized {
    require(address.isEmpty && value.getHost == "127.0.0.1" && value.getScheme == "http", "Local control address must be bound once on loopback")
    address = Some(value.resolve("/mcp"))
  }
  def endpoint: URI = synchronized(address.getOrElse(throw new IllegalStateException("Local control service has not started")))
  def issue(attempt: AttemptId, role: Role): AccessToken = synchronized {
    require(Set(Role.Governor, Role.Explorer, Role.Planner, Role.Worker, Role.Reviewer)(role), "Unsupported local capability role")
    val capability = LocalCapability(attempt, role)
    grants.getOrElse(capability, {
      require(grants.size < MaxCapabilities, "Local capability inventory exhausted")
      val token = AccessToken(UUID.randomUUID().toString + UUID.randomUUID().toString, authority.expiresAt)
      grants = grants.updated(capability, token)
      token
    })
  }
  def authenticate(token: String): LocalCapability = synchronized {
    if (token.length > 256) throw DomainFailure(Fault.Denied("Invalid local credential"))
    grants.find { case (_, value) => value.expiresAt > clock.millis() && MessageDigest.isEqual(value.value.getBytes(UTF_8), token.getBytes(UTF_8)) }
      .map(_._1).getOrElse(throw DomainFailure(Fault.Denied("Local credential is missing, expired or revoked")))
  }
  def revoke(attempt: AttemptId): Unit = synchronized { grants = grants.filterNot(_._1.attempt == attempt) }
}
