package cq.server

import cq.api.*
import cq.core.Scope
import cq.host.{ServerApi, ServerUnavailable}
import logstage.IzLogger
import zio.{UIO, ZIO}

/** The work claims an attached session holds, as the replies to its Governor's claim commands establish them. The server offers no
  * query of the claims of an owner, so a claim the session acquired by another route is not known here and ends with its lease. */
final class SessionClaims(owner: Scope, governor: ServerApi, logger: IzLogger) {
  private var held = Map.empty[ClaimId, Fence]

  /** A Governor's domain command: a claim it is granted is held from then on, and one a reply shows released no longer is. */
  def call(command: Command): Result = {
    val result = governor.call(command)
    (command, result) match {
      case (_: Command.ClaimWork, Result.Claimed(claim)) if claim.owner == owner.actor => synchronized {
        held = if (claim.released) held - claim.fence.claim else held.updated(claim.fence.claim, claim.fence)
      }
      case _ => ()
    }
    result
  }

  /** Releases what the session still holds when its host ends in order, so that another session need not wait for the leases. It never
    * fails: a claim the server refuses to release stays as it is, and after an unanswered request the rest is left to lease expiry. */
  def release: UIO[Unit] = ZIO.attemptBlocking {
    val fences = synchronized(held.values.toList)
    fences.takeWhile { fence =>
      try {
        governor.call(Command.ClaimWork(ClaimInput(owner.project, ClaimAction.Release(fence)))) match {
          case Result.Claimed(_) => synchronized { held -= fence.claim }
          // Expired, replaced or taken over: the session no longer holds it.
          case Result.Failed(_: Fault.StaleFence) => synchronized { held -= fence.claim }
          case Result.Failed(fault) => logger.warn(s"Claim ${fence.claim.value} was not released at session end: $fault")
          case other => logger.warn(s"Claim ${fence.claim.value} release returned an unexpected result: ${other.getClass.getSimpleName}")
        }
        true
      } catch {
        case failure: ServerUnavailable =>
          logger.warn(s"Claims were not released at session end; their leases expire instead: ${failure.getMessage}")
          false
      }
    }
    ()
  }.catchAll(error => ZIO.succeed(logger.warn(s"Claims were not released at session end; their leases expire instead: ${error.getMessage}")))
}
