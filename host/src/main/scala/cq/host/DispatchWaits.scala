package cq.host

import cq.api.*
import java.time.Duration

/** How long a governing session may make one dispatch call wait for work to end, and what every transport around it allows for that. */
object DispatchWaits {
  /** The longest wait one call may ask for. Every response of the governing model re-reads its whole context, so a short bound costs a
    * response per poll: with 20 s, 29–35 % of a measured run were responses that only asked again. The wait ends at once when the work
    * does, so the bound matters only while it runs. 120 s stays inside the idle time after which the providers still served the prompt
    * cache (kept after 146 s, lost after 356 s), and a longer bound gains 1–4 points more (I33 analysis, 2026-10-05). */
  val MaxMillis = 120000
  /** What a request may take besides the wait it asks for; a request that does not wait fails after this. */
  val RequestSeconds = 30L
  /** How much longer than the host an attached harness allows a tool call, so that the host's own answer or failure arrives first. */
  val HarnessMarginSeconds = 5L
  /** The tool-call bound of a harness that has one bound for all calls of the attached host. */
  val AttachedHarnessSeconds: Long = MaxMillis / 1000 + RequestSeconds + HarnessMarginSeconds
  /** The tool-call bound of a managed Governor's local dispatch endpoint, which has one bound for all its calls. */
  val ManagedGovernorSeconds: Long = MaxMillis / 1000 + RequestSeconds

  /** The yield time a Codex script that makes a waiting call asks for. Codex 0.156.1–0.160.0 hands a script of its `exec` tool back
    * unfinished after 30 s by default (`DEFAULT_CODE_MODE_EXEC_YIELD_TIME_MS`) and the model then asks again every 10 s; the script's
    * first-line pragma and the `wait` tool take a longer time. This one outlasts the host's deadline for the call. */
  val CodexScriptYieldMillis: Long = MaxMillis + RequestSeconds * 1000
  val CodexScript: String = s"A script of the exec tool is handed back unfinished after 30 s unless it asks for longer: begin a script that makes this call with the line " +
    s"`// @exec: {\"yield_time_ms\": $CodexScriptYieldMillis}`, and continue a script that is handed back as still running with the wait tool and yield_time_ms $CodexScriptYieldMillis."

  def admitted(waitMillis: Int, subject: String): Unit =
    require(waitMillis >= 0 && waitMillis <= MaxMillis, s"$subject wait must be 0–$MaxMillis ms")

  /** The wait `command` asks the host for; zero for a command that does not wait and for a wait the host refuses. */
  def millis(command: DispatchCommand): Int = {
    val asked = command match {
      case DispatchCommand.Status(_, wait) => wait
      case DispatchCommand.IntegrationStatus(_, wait) => wait
      case DispatchCommand.CombinationStatus(_, wait) => wait
      // A revalidation request waits for its round as long as a status call may.
      case _: DispatchCommand.Revalidate => MaxMillis
      case _ => 0
    }
    if (asked >= 0 && asked <= MaxMillis) asked else 0
  }

  /** The deadline of one request to the attached host. */
  def deadline(waitMillis: Int): Duration = Duration.ofSeconds(RequestSeconds).plusMillis(waitMillis)
}
