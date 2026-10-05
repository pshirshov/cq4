package cq.server

import cq.api.*
import cq.core.{DomainFailure, LedgerRepository, LedgerService, Scope}
import cq.host.{ServerApi, ServerUnavailable}
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import zio.{IO, Runtime, Unsafe, ZIO}

final class SessionClaimsDummy extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    activation = Activation(Repo -> Repo.Dummy),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def uuid: UUID = UUID.randomUUID()

  /** The governing credential of `scope` against the ledger service: a refusal is a `Failed` result, as the server answers a domain call. */
  private final class Governor(service: LedgerService[IO], scope: Scope, runtime: Runtime[Any]) extends ServerApi {
    val releases = new AtomicInteger(0)
    @volatile var unavailable = false
    override def call(command: Command): Result = {
      val effect = command match {
        case Command.ClaimWork(ClaimInput(_, ClaimAction.Acquire(id, members, duration))) => service.acquire(scope, id, members, duration)
        case Command.ClaimWork(ClaimInput(_, ClaimAction.Release(fence))) =>
          releases.incrementAndGet()
          if (unavailable) throw new ServerUnavailable("HTTP request failed: fixture", null)
          service.release(scope, fence)
        case other => throw new IllegalStateException("Unexpected fixture command " + other)
      }
      Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(effect.either).getOrThrow() } match {
        case Right(claim) => Result.Claimed(claim)
        case Left(DomainFailure(fault)) => Result.Failed(fault)
        case Left(error) => throw error
      }
    }
    override def usage(value: HostUsageInput): HostUsageResult = throw new IllegalStateException("Not a usage fixture")
    override def artifact(value: ArtifactUpload): ArtifactMetadata = throw new IllegalStateException("Not an artifact fixture")
    override def grant(value: GrantRequest): AccessToken = throw new IllegalStateException("Not an authority fixture")
    override def admit(value: HostAdmissionInput): ResultAdmission = throw new IllegalStateException("Not an admission fixture")
    override def integrate(value: HostIntegrationInput): IntegrationRecord = throw new IllegalStateException("Not an integration fixture")
  }

  private final case class World(service: LedgerService[IO], owner: Scope, other: Scope, items: List[ItemId], governor: Governor, claims: SessionClaims) {
    def acquire(item: ItemId): Fence = claims.call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Acquire(ClaimId(UUID.randomUUID()), Set(item), 1800000)))) match {
      case Result.Claimed(claim) => claim.fence
      case other => throw new IllegalStateException("Claim was not acquired: " + other)
    }
    def active: IO[Throwable, List[Claim]] = service.claimPreview(owner, items.toSet).map(_.claims)
  }
  private def world(service: LedgerService[IO], count: Int): IO[Throwable, World] = {
    val owner = Scope(ProjectId(uuid), Actor("CQ governor", SessionId(uuid), Role.Governor))
    val other = owner.copy(actor = owner.actor.copy(session = SessionId(uuid)))
    for {
      runtime <- ZIO.runtime[Any]
      _ <- service.initialize(owner, "Session claims")
      created <- service.change(owner, ChangeRequest(RequestId(uuid), List.tabulate(count)(index => Mutation.Create(ItemDraft(s"Task $index", "Narrative", Set.empty, false,
        Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil), Nil))), Nil, "Fixture"))
      governor = new Governor(service, owner, runtime)
    } yield World(service, owner, other, created.items.map(_.id), governor, new SessionClaims(owner, governor, logstage.IzLogger.NullLogger))
  }

  "The claims of an attached session (Behavioral Active Blackbox; dummy Group)" should {
    "D148: leave no active claim of the session after an orderly end, so that another session can claim the same work at once" in { (service: LedgerService[IO]) =>
      for {
        w <- world(service, 4)
        fences <- ZIO.attemptBlocking(w.items.take(3).map(w.acquire))
        // The session releases one claim itself; another session holds the fourth item.
        _ <- ZIO.attemptBlocking(w.claims.call(Command.ClaimWork(ClaimInput(w.owner.project, ClaimAction.Release(fences(2))))))
        foreign <- service.acquire(w.other, ClaimId(uuid), Set(w.items(3)), 1800000)
        blocked <- service.acquire(w.other, ClaimId(uuid), Set(w.items.head), 1800000).either
        before <- w.active
        _ <- w.claims.release
        after <- w.active
        taken <- service.acquire(w.other, ClaimId(uuid), w.items.take(3).toSet, 1800000).either
        _ <- w.claims.release
        _ <- ZIO.attempt {
          println(s"Session claims: before=${before.map(_.fence)} after=${after.map(_.fence)} releases=${w.governor.releases.get()}")
          assert(blocked.left.exists { case DomainFailure(_: Fault.Conflict) => true; case _ => false }, blocked.toString)
          assert(before.map(_.fence).toSet == fences.take(2).toSet + foreign.fence && after.map(_.fence) == List(foreign.fence), s"$before $after")
          assert(taken.isRight, taken.toString)
          // One release by the session, two at its end; a repeated end has nothing left to release.
          assert(w.governor.releases.get() == 3)
        }
      } yield ()
    }

    "D148: end without failing when a claim was already released or the server does not answer, leaving the rest to lease expiry" in { (service: LedgerService[IO]) =>
      for {
        w <- world(service, 3)
        fences <- ZIO.attemptBlocking(w.items.map(w.acquire))
        // Released without the host seeing it, as the recording of an integration releases a claim.
        _ <- service.release(w.owner, fences.head)
        _ <- w.claims.release
        stale <- w.active
        again <- ZIO.attemptBlocking(w.items.map(w.acquire))
        _ <- ZIO.succeed { w.governor.releases.set(0); w.governor.unavailable = true }
        _ <- w.claims.release
        kept <- w.active
        _ <- ZIO.attempt {
          println(s"Session claims without an answer: stale=${stale.map(_.fence)} kept=${kept.map(_.fence)} releases=${w.governor.releases.get()}")
          assert(stale.isEmpty && kept.map(_.fence).toSet == again.toSet, s"$stale $kept")
          // An unanswered release ends the attempt: the remaining claims would not be answered either.
          assert(w.governor.releases.get() == 1)
        }
      } yield ()
    }
  }
}
