package cq.server

import izumi.functional.bio.Exit
import izumi.functional.bio.UnsafeRun2.FailureHandler
import org.http4s.ember.core.EmberException

object ConnectionReports {
  /** Ember reads a request in a fiber of its own and ends a connection that the client closed before sending anything by failing that fiber
    * with `EmptyStream`, which Ember itself handles; the launcher's fiber report would log that ordinary event as an unhandled failure. */
  def reporting(reported: FailureHandler): FailureHandler = reported match {
    case FailureHandler.Custom(report) => FailureHandler.Custom {
      case Exit.Error(_: EmberException.EmptyStream, _) => ()
      case exit => report(exit)
    }
    case other => other
  }
}
