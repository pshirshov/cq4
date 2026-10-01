package cq.server

import java.util.concurrent.CountDownLatch

/** SIGINT/SIGTERM from the owning process: the JVM exits once its shutdown hooks return, so the hook ends the session through `onSignal` and holds the exit until `release`; the supervisor watchdog's halt deadline fences a stalled finish. */
final class TerminationGuard(name: String, onSignal: () => Unit) {
  private val finished = new CountDownLatch(1)
  def install(): Unit = Runtime.getRuntime.addShutdownHook(Thread.ofPlatform().name(name).unstarted(() => {
    onSignal()
    finished.await()
  }))
  def release(): Unit = finished.countDown()
}
