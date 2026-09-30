package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import java.time.{Clock, Instant, ZoneOffset}
import zio.{IO, ZIO}

abstract class ClaimCoordinationTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("coordination", SessionId(UUID.randomUUID()), Role.Governor))
  private def task(title: String): ItemDraft = ItemDraft(title, "Narrative", Set.empty, false,
    Content.Task(TaskStatus.Ready, List("Acceptance"), None, Nil), Nil)
  private def change(service: LedgerService[IO], owner: Scope, operations: List[Mutation], fences: List[Fence]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), operations, fences, "Claim coordination"))
  private def create(service: LedgerService[IO], owner: Scope, title: String): IO[Throwable, ItemRevision] =
    change(service, owner, List(Mutation.Create(task(title))), Nil).map(_.items.head)
  private def reject[A](operation: IO[Throwable, A], accepts: Fault => Boolean): IO[Throwable, Unit] = operation.either.flatMap {
    value => assertIO(value match { case Left(DomainFailure(fault)) => accepts(fault); case _ => false }).unit
  }

  private def fixed(repository: LedgerRepository[IO], millis: Long): LedgerService[IO] = {
    val parser = new QueryParser
    val worksets = new WorksetTraversal
    new LedgerService.Impl[IO](repository, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), parser,
      new QueryCompleter(parser), worksets, new TerminationPlanner(worksets), new ClaimPlanner, new LedgerMutation(new TerminationPlanner(new WorksetTraversal)))
  }

  "Claim coordination (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "bind acquisition retries to the requested duration" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val id = ClaimId(UUID.randomUUID())
      for {
        _ <- service.initialize(owner, "acquisition identity")
        item <- create(service, owner, "Owned work")
        first <- service.acquire(owner, id, Set(item.id), 300000)
        same <- service.acquire(owner, id, Set(item.id), 300000)
        _ <- assertIO(first == same && first.origin == ClaimOrigin.Acquire(300000))
        renewed <- service.renew(owner, first.fence, 60000)
        replay <- service.acquire(owner, id, Set(item.id), 300000)
        _ <- assertIO(replay == renewed && replay.origin == first.origin)
        changed <- service.acquire(owner, id, Set(item.id), 60000).either
        _ <- ZIO.succeed(println(s"Changed acquisition duration observation: $changed"))
        _ <- assertIO(changed match { case Left(DomainFailure(_: Fault.Conflict)) => true; case _ => false })
      } yield ()
    }

    "D83: hold a lease for up to thirty minutes and reject longer durations" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val thirtyMinutes = 30L * 60 * 1000
      for {
        _ <- service.initialize(owner, "lease duration")
        item <- create(service, owner, "Long-running child")
        other <- create(service, owner, "Unrelated work")
        before <- ZIO.clockWith(_.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS))
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(item.id), thirtyMinutes)
        _ <- assertIO(claim.expiresAt >= before + thirtyMinutes && claim.origin == ClaimOrigin.Acquire(thirtyMinutes))
        renewed <- service.renew(owner, claim.fence, thirtyMinutes)
        _ <- assertIO(renewed.expiresAt >= claim.expiresAt)
        _ <- reject(service.acquire(owner, ClaimId(UUID.randomUUID()), Set(other.id), thirtyMinutes + 1), _.isInstanceOf[Fault.Invalid])
        _ <- reject(service.renew(owner, claim.fence, thirtyMinutes + 1), _.isInstanceOf[Fault.Invalid])
      } yield ()
    }

    "produce descendants under the producer fence with one atomic history event and frozen claim membership" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      for {
        _ <- service.initialize(owner, "atomic production")
        producer <- create(service, owner, "Producer")
        operation = Mutation.Produce(producer.id, producer.revision, List(task("First child"), task("Second child")))
        _ <- reject(change(service, owner, List(operation), Nil), _.isInstanceOf[Fault.StaleFence])
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(producer.id), 300000)
        _ <- reject(change(service, other, List(operation), List(claim.fence)), _.isInstanceOf[Fault.StaleFence])
        _ <- reject(change(service, owner, List(operation), Nil), _.isInstanceOf[Fault.StaleFence])
        _ <- reject(change(service, owner, List(operation.copy(expected = Revision(999))), List(claim.fence)), _.isInstanceOf[Fault.Conflict])
        _ <- reject(change(service, owner, List(operation.copy(drafts = List(task("Must roll back"), task("Invalid").copy(title = "")))), List(claim.fence)), _.isInstanceOf[Fault.Invalid])
        before <- service.get(owner, producer.id)
        _ <- assertIO(before.item.revision == Revision(1) && before.refs.isEmpty)
        request = ChangeRequest(RequestId(UUID.randomUUID()), List(operation), List(claim.fence), "Claimed production")
        attempts <- ZIO.collectAllPar(List(service.change(owner, request), service.change(owner, request)))
        _ <- assertIO(attempts.head == attempts.last)
        children = attempts.head.items.filterNot(_.id == producer.id)
        _ <- assertIO(children.map(_.id.number) == List(2L, 3L) && children.forall(_.revision == Revision(1)))
        parent <- service.get(owner, producer.id)
        views <- ZIO.foreach(children)(item => service.get(owner, item.id))
        _ <- assertIO(parent.item.revision == Revision(2) && parent.refs.toSet == children.map(item => ItemRef(Relation.Produces, item.id)).toSet)
        _ <- assertIO(views.forall(_.refs == List(ItemRef(Relation.DerivedFrom, producer.id))))
        histories <- ZIO.foreach(producer.id :: children.map(_.id))(service.history(owner, _, Revision(Long.MaxValue), 20))
        _ <- assertIO(histories.map(_.entries.head.cursor).distinct == List(attempts.head.cursor) && histories.map(_.entries.size) == List(2, 1, 1))
        kept <- service.renew(owner, claim.fence, 300000)
        _ <- assertIO(kept.members == Set(producer.id))
        childClaim <- service.acquire(other, ClaimId(UUID.randomUUID()), children.map(_.id).toSet, 300000)
        _ <- assertIO(childClaim.members.size == 2)
        _ <- reject(service.acquire(owner, ClaimId(UUID.randomUUID()), Set(children.head.id, producer.id), 300000), _.isInstanceOf[Fault.Conflict])
        _ <- service.release(owner, claim.fence)
        _ <- reject(change(service, owner, List(operation.copy(expected = parent.item.revision)), List(claim.fence)), _.isInstanceOf[Fault.StaleFence])
      } yield ()
    }

    "roll back allocations and history when production exceeds the producer reference bound" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "production bounds")
        producer <- create(service, owner, "Producer")
        claim <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(producer.id), 300000)
        _ <- reject(change(service, owner, List(Mutation.Produce(producer.id, producer.revision, Nil)), List(claim.fence)), _.isInstanceOf[Fault.Invalid])
        _ <- reject(change(service, owner, List(Mutation.Produce(producer.id, producer.revision, List.fill(65)(task("Too many")))), List(claim.fence)), _.isInstanceOf[Fault.Invalid])
        _ <- ZIO.foreachDiscard(List(64, 64, 64, 7)) { count => for {
          current <- service.get(owner, producer.id)
          _ <- change(service, owner, List(Mutation.Produce(producer.id, current.item.revision, List.fill(count)(task("Child")))), List(claim.fence))
        } yield () }
        before <- service.get(owner, producer.id)
        history <- service.history(owner, producer.id, Revision(Long.MaxValue), 20)
        _ <- assertIO(before.refs.size == 199 && before.item.revision == Revision(5))
        _ <- reject(change(service, owner, List(Mutation.Produce(producer.id, before.item.revision, List(task("Rolled back one"), task("Rolled back two")))), List(claim.fence)), _.isInstanceOf[Fault.Limit])
        after <- service.get(owner, producer.id)
        afterHistory <- service.history(owner, producer.id, Revision(Long.MaxValue), 20)
        _ <- assertIO(after == before && history == afterHistory)
        next <- create(service, owner, "After rollback")
        _ <- assertIO(next.id.number == 201)
      } yield ()
    }

    "review full collateral membership and replace overlapping claims only with explicit human authority" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val takeoverId = ClaimId(UUID.randomUUID())
      for {
        _ <- service.initialize(owner, "human takeover")
        a <- create(service, owner, "Requested")
        b <- create(service, owner, "Collateral")
        c <- create(service, owner, "Independent")
        old <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(a.id, b.id), 300000)
        independent <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(c.id), 300000)
        preview <- service.claimPreview(human, Set(a.id))
        _ <- assertIO(preview.members == List(a) && preview.claims == List(old))
        _ <- reject(service.takeover(owner, takeoverId, other.actor, Set(a.id), 300000, preview.snapshot), _.isInstanceOf[Fault.Denied])
        _ <- reject(service.takeover(human, takeoverId, other.actor.copy(role = Role.Worker), Set(a.id), 300000, preview.snapshot), _.isInstanceOf[Fault.Denied])
        _ <- reject(service.takeover(human, takeoverId, other.actor.copy(subject = "bad\u0000name"), Set(a.id), 300000, preview.snapshot), _.isInstanceOf[Fault.Invalid])
        _ <- service.renew(owner, old.fence, 200000)
        renewed <- service.claimPreview(human, Set(a.id))
        _ <- assertIO(renewed.snapshot == preview.snapshot && renewed.claims.head.origin == old.origin)
        attempts <- ZIO.collectAllPar(List.fill(2)(service.takeover(human, takeoverId, other.actor, Set(a.id), 300000, preview.snapshot)))
        fresh = attempts.head
        _ <- assertIO(attempts.head == attempts.last && fresh.owner == other.actor && fresh.members == Set(a.id) && fresh.fence.generation > independent.fence.generation)
        _ <- assertIO(fresh.origin == ClaimOrigin.Takeover(human.actor, preview.snapshot, 300000))
        _ <- reject(service.renew(owner, old.fence, 300000), _.isInstanceOf[Fault.StaleFence])
        _ <- reject(change(service, owner, List(Mutation.Replace(a.id, a.revision, task("Late result"))), List(old.fence)), _.isInstanceOf[Fault.StaleFence])
        untouched <- service.renew(owner, independent.fence, 300000)
        collateral <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(b.id), 300000)
        _ <- service.release(owner, old.fence)
        kept <- service.renew(owner, collateral.fence, 300000)
        _ <- assertIO(untouched.members == Set(c.id) && kept.fence == collateral.fence && kept.members == collateral.members && !kept.released)
        after <- ZIO.foreach(List(a, b, c))(item => service.get(owner, item.id))
        _ <- assertIO(after.forall(_.item.revision == Revision(1)))
        _ <- reject(service.acquire(other, takeoverId, Set(a.id), 300000), _.isInstanceOf[Fault.Conflict])
        _ <- reject(service.takeover(human, takeoverId, other.actor, Set(a.id), 60000, preview.snapshot), _.isInstanceOf[Fault.Conflict])
        _ <- reject(service.takeover(human, takeoverId, other.actor, Set(a.id), 300000, preview.snapshot.copy(digest = "altered")), _.isInstanceOf[Fault.Conflict])
        changed <- change(service, other, List(Mutation.Replace(a.id, a.revision, task("New owner"))), List(fresh.fence))
        replay <- service.takeover(human, takeoverId, other.actor, Set(a.id), 300000, preview.snapshot)
        _ <- assertIO(replay == fresh && changed.items.head.revision == Revision(2))
      } yield ()
    }

    "reject stale or foreign takeover previews and serialize distinct takeover attempts" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val otherHuman = human.copy(actor = human.actor.copy(session = SessionId(UUID.randomUUID())))
      val newOwner = owner.actor.copy(session = SessionId(UUID.randomUUID()))
      def take(snapshot: ClaimSnapshot, members: Set[ItemId]) = service.takeover(human, ClaimId(UUID.randomUUID()), newOwner, members, 300000, snapshot)
      for {
        _ <- service.initialize(owner, "takeover snapshots")
        a <- create(service, owner, "Requested")
        b <- create(service, owner, "Other requested")
        _ <- reject(service.claimPreview(human, Set.empty), _.isInstanceOf[Fault.Invalid])
        _ <- reject(service.claimPreview(human, (1L to 65L).map(n => a.id.copy(number = n)).toSet), _.isInstanceOf[Fault.Invalid])
        _ <- reject(service.claimPreview(human, Set(a.id.copy(project = ProjectId(UUID.randomUUID())))), _.isInstanceOf[Fault.Denied])
        _ <- reject(service.claimPreview(human, Set(a.id.copy(number = 999))), _.isInstanceOf[Fault.Missing])
        unclaimed <- service.claimPreview(human, Set(a.id))
        old <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(a.id), 300000)
        _ <- reject(take(unclaimed.snapshot, Set(a.id)), _.isInstanceOf[Fault.Conflict])
        preview <- service.claimPreview(human, Set(a.id))
        _ <- assertIO(unclaimed.snapshot.cursor == preview.snapshot.cursor && unclaimed.snapshot.digest != preview.snapshot.digest)
        _ <- reject(take(preview.snapshot, Set(b.id)), _.isInstanceOf[Fault.Conflict])
        _ <- reject(service.takeover(otherHuman, ClaimId(UUID.randomUUID()), newOwner, Set(a.id), 300000, preview.snapshot), _.isInstanceOf[Fault.Conflict])
        _ <- service.release(owner, old.fence)
        _ <- reject(take(preview.snapshot, Set(a.id)), _.isInstanceOf[Fault.Conflict])
        released <- service.claimPreview(human, Set(a.id))
        _ <- change(service, owner, List(Mutation.Reference(a.id, a.revision, Relation.Produces, b.id, b.revision, true)), Nil)
        _ <- reject(take(released.snapshot, Set(a.id)), _.isInstanceOf[Fault.Conflict])
        fresh <- service.claimPreview(human, Set(a.id))
        attempts <- ZIO.collectAllPar(List(take(fresh.snapshot, Set(a.id)).either, take(fresh.snapshot, Set(a.id)).either))
        _ <- assertIO(attempts.count(_.isRight) == 1 && attempts.count { case Left(DomainFailure(_: Fault.Conflict)) => true; case _ => false } == 1)
      } yield ()
    }


    "keep a replaced claim fenced when wall time returns before its old expiry" in { (repository: LedgerRepository[IO]) =>
      val earlier = fixed(repository, 10000)
      val later = fixed(repository, 12000)
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      for {
        _ <- earlier.initialize(owner, "monotonic ownership")
        item <- create(earlier, owner, "Owned work")
        old <- earlier.acquire(owner, ClaimId(UUID.randomUUID()), Set(item.id), 1000)
        replacement <- later.acquire(other, ClaimId(UUID.randomUUID()), Set(item.id), 300000)
        result <- earlier.renew(owner, old.fence, 300000).either
        _ <- ZIO.succeed(println(s"Replaced claim renewal after clock rollback: $result"))
        _ <- assertIO(result match { case Left(DomainFailure(_: Fault.StaleFence)) => true; case _ => false })
        _ <- reject(earlier.acquire(owner, old.fence.claim, Set(item.id), 1000), _.isInstanceOf[Fault.StaleFence])
        _ <- reject(earlier.release(owner, old.fence), _.isInstanceOf[Fault.StaleFence])
        _ <- change(later, other, List(Mutation.Replace(item.id, item.revision, task("Still owned"))), List(replacement.fence))
      } yield ()
    }

    "invalidate the whole claim after a partial replacement despite clock rollback" in { (repository: LedgerRepository[IO]) =>
      val earlier = fixed(repository, 10000)
      val later = fixed(repository, 12000)
      val owner = scope()
      val other = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      for {
        _ <- earlier.initialize(owner, "partial replacement")
        a <- create(earlier, owner, "Replaced")
        b <- create(earlier, owner, "Residual")
        old <- earlier.acquire(owner, ClaimId(UUID.randomUUID()), Set(a.id, b.id), 1000)
        replacement <- later.acquire(other, ClaimId(UUID.randomUUID()), Set(a.id), 300000)
        preview <- earlier.claimPreview(owner, Set(b.id))
        _ <- ZIO.succeed(println(s"Residual claim after clock rollback: ${preview.claims}"))
        _ <- assertIO(preview.claims.isEmpty)
        termination <- earlier.termination(owner, Set(b.id), TerminationIntent.Cancel)
        _ <- assertIO(termination.plan.claims.isEmpty)
        _ <- reject(change(earlier, owner, List(Mutation.Produce(b.id, b.revision, List(task("Stale child")))), List(old.fence)), _.isInstanceOf[Fault.StaleFence])
        _ <- reject(change(earlier, owner, List(Mutation.Replace(b.id, b.revision, task("Stale write"))), List(old.fence)), _.isInstanceOf[Fault.StaleFence])
        edited <- change(earlier, owner, List(Mutation.Replace(b.id, b.revision, task("Unclaimed correction"))), Nil)
        residual <- earlier.acquire(owner, ClaimId(UUID.randomUUID()), Set(b.id), 300000)
        _ <- assertIO(edited.items.head.revision == Revision(2) && residual.members == Set(b.id))
        _ <- reject(earlier.renew(owner, old.fence, 300000), _.isInstanceOf[Fault.StaleFence])
        kept <- later.renew(other, replacement.fence, 300000)
        _ <- assertIO(kept.fence == replacement.fence)
      } yield ()
    }

    "bound collateral previews by encoded bytes without omitting members" in { (repository: LedgerRepository[IO]) =>
      val service = fixed(repository, 10000)
      val owner = scope().copy(actor = Actor("界" * LedgerPolicy.MaxTitle, SessionId(UUID.randomUUID()), Role.Governor))
      for {
        _ <- service.initialize(owner, "collateral byte bound")
        roots <- repository.transact(owner.project) { tx =>
          val provenance = Provenance(owner.actor, 10000, RequestId(UUID.randomUUID()))
          (0 until LedgerPolicy.MaxBatch).toList.map { group =>
            val members = (0 until LedgerPolicy.MaxBatch).map { member =>
              val id = ItemId(owner.project, Ledger.Tasks, Long.MaxValue - group * LedgerPolicy.MaxBatch - member)
              tx.put(Item(id, Revision(1), task("Collateral"), 10000, 10000, provenance))
              id
            }.toSet
            tx.insertClaim(Claim(Fence(ClaimId(UUID.randomUUID()), tx.nextFence()), owner.actor, members, 310000, false, ClaimOrigin.Acquire(300000)))
            members.minBy(_.number)
          }
        }
        _ <- reject(service.claimPreview(owner, roots.toSet), _.isInstanceOf[Fault.Limit])
        bounded <- service.claimPreview(owner, Set(roots.head))
        _ <- assertIO(bounded.members.size == 1 && bounded.claims.size == 1 && bounded.claims.head.members.size == LedgerPolicy.MaxBatch)
      } yield ()
    }

    "invalidate expired takeover previews and retain takeover intent through renewal" in { (repository: LedgerRepository[IO]) =>
      val service = fixed(repository, 10000)
      val later = fixed(repository, 12000)
      val owner = scope()
      val human = owner.copy(actor = owner.actor.copy(role = Role.Human))
      val nextOwner = owner.copy(actor = owner.actor.copy(session = SessionId(UUID.randomUUID())))
      val identity = ClaimId(UUID.randomUUID())
      for {
        _ <- service.initialize(owner, "takeover expiry")
        item <- create(service, owner, "Owned work")
        _ <- service.acquire(owner, ClaimId(UUID.randomUUID()), Set(item.id), 1000)
        before <- service.claimPreview(human, Set(item.id))
        _ <- reject(later.takeover(human, identity, nextOwner.actor, Set(item.id), 60000, before.snapshot), _.isInstanceOf[Fault.Conflict])
        after <- later.claimPreview(human, Set(item.id))
        _ <- assertIO(after.claims.isEmpty && after.snapshot.cursor == before.snapshot.cursor && after.snapshot.digest != before.snapshot.digest)
        claim <- later.takeover(human, identity, nextOwner.actor, Set(item.id), 60000, after.snapshot)
        renewed <- later.renew(nextOwner, claim.fence, 300000)
        replay <- later.takeover(human, identity, nextOwner.actor, Set(item.id), 60000, after.snapshot)
        _ <- assertIO(replay == renewed && replay.origin == claim.origin)
        _ <- reject(later.takeover(human, identity, nextOwner.actor, Set(item.id), 300000, after.snapshot), _.isInstanceOf[Fault.Conflict])
        _ <- later.release(nextOwner, claim.fence)
        _ <- reject(later.takeover(human, identity, nextOwner.actor, Set(item.id), 60000, after.snapshot), _.isInstanceOf[Fault.StaleFence])
      } yield ()
    }

  }
}

final class ClaimCoordinationDummy extends ClaimCoordinationTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class ClaimCoordinationPostgres extends ClaimCoordinationTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
