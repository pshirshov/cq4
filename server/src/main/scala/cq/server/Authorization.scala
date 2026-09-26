package cq.server

import cq.api.*
import cq.core.{DomainFailure, Scope}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.util.{Base64, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

final case class AccessConfig(token: String, origin: String)

final class Authority private[server] (val credential: Credential) {
  def root: Boolean = credential.isInstanceOf[Credential.RootSession]
  def expiresAt: Long = credential match {
    case Credential.RootSession(_, expiry) => expiry
    case Credential.Scoped(grant) => grant.expiresAt
  }
  def scope(project: ProjectId): Scope = credential match {
    case Credential.RootSession(session, _) => Scope(project, Actor("operator", session, Role.Human))
    case Credential.Scoped(grant) if grant.project == project => Scope(project, grant.actor)
    case _ => throw DomainFailure(Fault.Denied("Project is outside credential scope"))
  }
  def requireRoot(): Unit = if (!root) throw DomainFailure(Fault.Denied("Operator authority required"))
}

final class Authorization(access: AccessConfig, clock: Clock) {
  private val SessionMillis = 12L * 60 * 60 * 1000
  private val MaxGrantMillis = 24L * 60 * 60 * 1000
  private val MaxTokenLength = 8192
  private val encoder = Base64.getUrlEncoder.withoutPadding()
  private val decoder = Base64.getUrlDecoder

  private def equal(left: String, right: String): Boolean = MessageDigest.isEqual(left.getBytes(UTF_8), right.getBytes(UTF_8))
  private def signature(payload: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(access.token.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(payload.getBytes(UTF_8))
  }
  private def sign(credential: Credential): AccessToken = {
    val payload = encoder.encodeToString(Wire.encode(Credential_JsonCodec, credential).getBytes(UTF_8))
    AccessToken(payload + "." + encoder.encodeToString(signature(payload)), new Authority(credential).expiresAt)
  }
  def login(bearer: String, sessionId: String): AccessToken = {
    if (!equal(bearer, access.token)) throw DomainFailure(Fault.Denied("Invalid operator credential"))
    val session = Try(UUID.fromString(sessionId)).getOrElse(throw DomainFailure(Fault.Invalid("Login requires CQ-Session UUID")))
    sign(Credential.RootSession(SessionId(session), Math.addExact(clock.millis(), SessionMillis)))
  }
  def grant(authority: Authority, request: GrantRequest): AccessToken = {
    authority.requireRoot()
    if (request.actor.subject.trim.isEmpty || request.actor.subject.length > 300 || request.expiresAt <= clock.millis() ||
      request.expiresAt - clock.millis() > MaxGrantMillis || request.actor.role == Role.Human)
      throw DomainFailure(Fault.Invalid("Grant requires a non-human role, subject and expiry within 24 hours"))
    sign(Credential.Scoped(request))
  }
  def authenticate(token: String, rootSession: Option[String]): Authority = {
    val credential = if (equal(token, access.token)) {
      val session = rootSession.flatMap(value => Try(UUID.fromString(value)).toOption)
        .getOrElse(throw DomainFailure(Fault.Denied("Operator bearer requests require CQ-Session UUID")))
      Credential.RootSession(SessionId(session), Math.addExact(clock.millis(), SessionMillis))
    } else {
      val decoded = Try {
        require(token.length <= MaxTokenLength)
        val parts = token.split("\\.", -1)
        require(parts.length == 2)
        require(MessageDigest.isEqual(signature(parts(0)), decoder.decode(parts(1))))
        Wire.decode(Credential_JsonCodec, new String(decoder.decode(parts(0)), UTF_8))
      }.toOption.getOrElse(throw DomainFailure(Fault.Denied("Invalid credential")))
      decoded
    }
    val authority = new Authority(credential)
    check(authority)
    authority
  }
  def check(authority: Authority): Unit =
    if (authority.expiresAt <= clock.millis()) throw DomainFailure(Fault.Denied("Credential expired"))
}
