package cq.server

import cq.host.ServerUnavailable
import java.time.Duration
import logstage.IzLogger
import zio.{Task, ZIO}

object ClaimRenewal {
  /** `lease` is what every host renewal requests and `tick` the interval between renewals. A renewal the server left unanswered is
    * not retried once less than `margin` would be left of the lease last obtained. */
  final case class Policy(lease: Duration, tick: Duration, margin: Duration)
  val Default: Policy = Policy(Duration.ofMinutes(3), Duration.ofSeconds(20), Duration.ofSeconds(30))
}

/** Keeps a work claim renewed while a child or a host check runs under it. */
final class ClaimRenewal(policy: ClaimRenewal.Policy, logger: IzLogger) {
  /**
   * Renews every tick and ends only by failing. `obtained` is when the lease the caller holds was requested, on the ZIO clock
   * the loop reads and sleeps by; callers read it with `zio.Clock.nanoTime` (the live clock returns `System.nanoTime`).
   * A refusal — any failure other than `ServerUnavailable` — fails at once. An unanswered renewal (connection refused, timeout,
   * server error) is retried at the next tick for as long as that tick leaves more than the margin of the lease last obtained.
   */
  def maintain(obtained: Long, renew: Task[Unit]): Task[Nothing] = {
    def next(obtained: Long, unanswered: Int, since: Long): Task[Nothing] =
      ZIO.sleep(zio.Duration.fromJava(policy.tick)) *> zio.Clock.nanoTime.flatMap { began =>
        renew.foldZIO({
          case failure: ServerUnavailable => zio.Clock.nanoTime.flatMap { now =>
            val first = if (unanswered == 0) began else since
            if (now + policy.tick.toNanos < obtained + policy.lease.toNanos - policy.margin.toNanos)
              ZIO.succeed(logger.warn(s"Claim renewal unanswered (${failure.getMessage}); retrying at the next tick")) *> next(obtained, unanswered + 1, first)
            else ZIO.fail(new IllegalStateException(s"Claim renewal unanswered ${unanswered + 1} times over ${Duration.ofNanos(now - first).toMillis} ms; " +
              s"its lease is about to expire: ${failure.getMessage}", failure))
          }
          case failure => ZIO.fail(failure)
        }, _ => next(began, 0, began))
      }
    next(obtained, 0, obtained)
  }
}
