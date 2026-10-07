package cq.server

import cq.api.*
import cq.core.DomainFailure
import cq.host.*
import java.nio.file.Path
import java.time.{Clock, Duration, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import org.scalatest.wordspec.AnyWordSpec

final class SessionLifetimeLocal extends AnyWordSpec {
  private def uuid: UUID = UUID.randomUUID()
  private final class ManualClock(start: Long) extends Clock {
    val now = new AtomicLong(start)
    def advance(duration: Duration): Unit = { now.addAndGet(duration.toMillis); () }
    override def getZone: ZoneId = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant = Instant.ofEpochMilli(now.get())
  }
  private val Reached = Result.Failed(Fault.Missing("Fixture application reached"))
  private abstract class Stub extends ServerApi {
    override def call(command: Command): Result = throw new IllegalStateException("Unexpected call")
    override def usage(input: HostUsageInput): HostUsageResult = throw new IllegalStateException("Unexpected usage")
    override def artifact(input: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Unexpected artifact")
    override def grant(input: GrantRequest): AccessToken = throw new IllegalStateException("Unexpected grant")
    override def admit(input: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Unexpected admission")
    override def integrate(input: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Unexpected integration")
  }
  private def configuration(ownership: SessionOwnership): SupervisorConfig = {
    val project = ProjectConfig(ProjectId(uuid), "http://localhost", "Session lifetime")
    val assignment = Assignment(AssignmentId(uuid), project.project, Set.empty, Attribution.Unattributed, None, None)
    val attempt = Attempt(AttemptId(uuid), assignment.id, None, SessionId(uuid), Role.Governor, Harness.Codex, "fixture", "fixture", "fixture", 1000, UsagePhase.Govern, None)
    val profile = HarnessSetting(Harness.Codex, "/unused/codex", "fixture", "fixture", "0.156.1", Nil, Set.empty)
    val limits = HostLimits(3000, 1000, 300, 2000, 262144)
    val settings = SupervisorSettings("/unused/state", "/unused/guardian", List(profile), limits, Nil, None, None)
    val run = SupervisorRun(project, assignment, attempt, profile.version, "/unused/repository", GitCommit("0" * 40), ownership)
    SupervisorConfig(settings, project, SupervisorConfig.profile(profile), SupervisorConfig.limits(limits), run, Path.of("/unused/session"), "", None, Map.empty)
  }

  "Session lifetime (Behavioral Active Blackbox; manual time)" should {
    "I21: renew a host credential from the root credential so that a call 25 hours after the first one succeeds" in {
      val clock = new ManualClock(1_000_000_000_000L)
      val operator = "session-lifetime-root-" + uuid
      val authorization = new Authorization(AccessConfig(operator, "http://localhost"), clock)
      val session = SessionId(uuid)
      val project = ProjectId(uuid)
      val grants = new AtomicInteger(0)
      val root = new Stub {
        override def grant(input: GrantRequest): AccessToken = {
          grants.incrementAndGet()
          authorization.grant(authorization.authenticate(operator, Some(session.value.toString)), input)
        }
      }
      val collector = Actor("CQ host collector", session, Role.Collector)
      val api = new RenewingServerApi(root, project, collector, token => new Stub {
        override def call(command: Command): Result = { require(authorization.authenticate(token.value, None).scope(project).actor == collector); Reached }
      }, clock, Duration.ofHours(24).minus(Duration.ofMinutes(10)), Duration.ofHours(1))
      val command = Command.Projects(None, None, 1)
      assert(api.call(command) == Reached && grants.get() == 1)
      clock.advance(Duration.ofHours(22))
      assert(api.call(command) == Reached && grants.get() == 1, "A credential with more than an hour left was replaced")
      clock.advance(Duration.ofHours(3))
      assert(api.call(command) == Reached && grants.get() == 2)
      clock.advance(Duration.ofHours(22).plus(Duration.ofMinutes(51)))
      assert(api.call(command) == Reached && grants.get() == 3, "A credential with under an hour left was kept")
    }

    "I21: issue a local capability that carries no expiry and deny it once its attempt is revoked" in {
      val access = new LocalAccess
      val attempt = AttemptId(uuid)
      val other = AttemptId(uuid)
      val token = access.issue(attempt, Role.Worker)
      // LocalAccess reads no clock: the only bound on a capability is its revocation.
      assert(token.expiresAt == Long.MaxValue && access.issue(attempt, Role.Worker) == token)
      access.revoke(other)
      assert(access.authenticate(token.value) == LocalCapability(attempt, Role.Worker))
      access.revoke(attempt)
      assert(scala.util.Try(access.authenticate(token.value)).failed.toOption.exists { case DomainFailure(_: Fault.Denied) => true; case _ => false })
    }

    "I21: leave the watchdog of an attached and of a managed session unarmed until shutdown begins, then bound the drain" in {
      SessionOwnership.all.foreach { ownership =>
        val ticks = new AtomicLong(0)
        val halted = new AtomicInteger(0)
        val watchdog = new SupervisorWatchdog(configuration(ownership), () => ticks.get(), code => { require(code == 75); halted.incrementAndGet(); () })
        try {
          ticks.addAndGet(Duration.ofHours(72).toNanos)
          Thread.sleep(200)
          assert(halted.get() == 0 && !watchdog.stopping, s"The $ownership session was forced to exit before its shutdown began")
          watchdog.beginShutdown()
          // grace 300 ms + kill 2 s + the 10 s host drain
          ticks.addAndGet(Duration.ofMillis(12299).toNanos)
          Thread.sleep(200)
          assert(halted.get() == 0 && watchdog.stopping)
          ticks.addAndGet(Duration.ofMillis(1).toNanos)
          val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos
          while (halted.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
          assert(halted.get() > 0, s"The $ownership session's shutdown drain was not bounded")
        } finally watchdog.close()
      }
    }
  }
}
