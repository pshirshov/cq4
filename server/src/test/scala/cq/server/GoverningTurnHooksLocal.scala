package cq.server

import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Using

/** The retained hook captures of a real Claude Code 2.1.295 process against an invented provider
  * (`server/src/test/resources/governing-turns/`, `docs/validation/governing-turn-hooks.md`). */
final class GoverningTurnHooksLocal extends AnyWordSpec {
  private final case class Hook(event: String, stdin: Json, time: Double)

  private def lines(name: String): List[String] = Using.resource(getClass.getResourceAsStream(s"/governing-turns/$name")) { stream =>
    require(stream != null, s"Missing capture file $name")
    new String(stream.readAllBytes(), UTF_8).split("\n").toList.filter(_.nonEmpty)
  }
  private def parse(text: String): Json = io.circe.parser.parse(text).fold(error => fail(s"Not JSON: $error"), identity)
  private def hooks(capture: String): List[Hook] = lines(s"$capture/hooks.jsonl").map { line =>
    val record = parse(line).hcursor
    val raw = record.get[String]("stdin").fold(error => fail(error.toString), identity)
    Hook(record.get[String]("event").toOption.get, parse(raw), record.get[Double]("t_ms").toOption.get)
  }
  private def names(capture: String): List[String] = hooks(capture).map(_.event)
  private def stops(capture: String): List[Hook] = hooks(capture).filter(_.event == "Stop")

  // The ordered hook events of each capture as the document states them (file order of hooks.jsonl).
  private val Plain = List("SessionStart", "UserPromptSubmit", "MessageDisplay", "Stop", "SessionEnd")
  private val Tool = List("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolBatch", "MessageDisplay", "Stop", "SessionEnd")
  private val Blocked = List("SessionStart", "UserPromptSubmit", "MessageDisplay", "Stop", "MessageDisplay", "Stop", "SessionEnd")
  private val Two = List("SessionStart", "UserPromptSubmit", "MessageDisplay", "Stop", "UserPromptSubmit", "MessageDisplay", "Stop", "SessionEnd")
  private val Interrupted = List("SessionStart", "UserPromptSubmit", "SessionEnd")
  private val Background = List("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolBatch", "MessageDisplay", "Stop")
  private val Expected: List[(String, List[String], Int)] = List(
    ("print-a", Plain, 1), ("print-b", Tool, 1), ("print-c", Blocked, 2), ("print-d", Two, 2), ("print-e", Interrupted, 0), ("print-ei", Interrupted, 0),
    ("print-f", Background ::: List("UserPromptSubmit", "MessageDisplay", "Stop", "SessionEnd"), 2),
    ("print-g", List("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "SubagentStart", "PostToolBatch", "SubagentStop", "MessageDisplay", "Stop",
      "UserPromptSubmit", "MessageDisplay", "Stop", "SessionEnd"), 2),
    ("interactive-a", Plain, 1), ("interactive-b", Tool, 1), ("interactive-c", Blocked, 2), ("interactive-d", Two, 2), ("interactive-e", Interrupted, 0),
    ("interactive-f", Background ::: List("UserPromptSubmit", "Stop", "MessageDisplay", "SessionEnd"), 2),
    ("interactive-g", List("SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "SubagentStart", "PostToolBatch", "SubagentStop", "Stop", "MessageDisplay",
      "UserPromptSubmit", "Stop", "MessageDisplay", "SessionEnd"), 2))

  "The retained Claude Code hook captures (Behavioral Active Blackbox; Atomic)" should {
    "hold, for every hook invocation, one JSON object that names the logged event and a session" in {
      Expected.foreach { case (capture, _, _) =>
        lines(s"$capture/hooks.jsonl").foreach { line =>
          val record = parse(line).hcursor
          val event = record.get[String]("event").toOption.get
          val input = parse(record.get[String]("stdin").toOption.get)
          assert(input.isObject, s"$capture: $event input is not an object")
          assert(input.hcursor.get[String]("hook_event_name") == Right(event), s"$capture: $event")
          assert(input.hcursor.get[String]("session_id").exists(_.nonEmpty), s"$capture: $event has no session_id")
        }
      }
    }
    "carry one session id per capture" in {
      Expected.foreach { case (capture, _, _) =>
        assert(hooks(capture).flatMap(_.stdin.hcursor.get[String]("session_id").toOption).distinct.size == 1, capture)
      }
    }
    "show the ordered hook events and the number of Stop invocations the document states" in {
      Expected.foreach { case (capture, events, stopCount) =>
        assert(names(capture) == events, capture)
        assert(stops(capture).size == stopCount, capture)
      }
    }
    "show a Stop hook that blocks once as two Stop invocations, the second with stop_hook_active" in {
      List("print-c", "interactive-c").foreach { capture =>
        assert(stops(capture).map(_.stdin.hcursor.get[Boolean]("stop_hook_active")) == List(Right(false), Right(true)), capture)
      }
      List("print-a", "print-b", "interactive-a", "interactive-b").foreach { capture =>
        assert(stops(capture).map(_.stdin.hcursor.get[Boolean]("stop_hook_active")) == List(Right(false)), capture)
      }
    }
    "show an interrupted turn without any Stop invocation" in {
      List("print-e", "print-ei", "interactive-e").foreach { capture =>
        assert(!names(capture).contains("Stop"), capture)
        assert(names(capture).count(_ == "UserPromptSubmit") == 1, capture)
      }
    }
    "show the two turns of one session as disjoint intervals" in {
      List("print-d", "interactive-d").foreach { capture =>
        val all = hooks(capture)
        val first = all.indexWhere(_.event == "Stop")
        assert(all(first).time < all.drop(first).find(_.event == "UserPromptSubmit").get.time, capture)
      }
    }
    "show that the exit of a background command starts a second turn with a task notification as its prompt" in {
      List("print-f", "interactive-f").foreach { capture =>
        val prompts = hooks(capture).filter(_.event == "UserPromptSubmit").map(_.stdin.hcursor.get[String]("prompt").toOption.get)
        assert(prompts.size == 2 && prompts(1).startsWith("<task-notification>") && !prompts.head.startsWith("<task-notification>"), capture)
        assert(stops(capture).head.stdin.hcursor.downField("background_tasks").downArray.get[String]("status") == Right("running"), capture)
      }
    }
    "keep every provider request of a turn between its UserPromptSubmit and its last Stop" in {
      Expected.filterNot(_._3 == 0).foreach { case (capture, _, _) =>
        val all = hooks(capture)
        val prompts = all.filter(_.event == "UserPromptSubmit").map(_.time)
        val requests = lines(s"$capture/stub.jsonl").map(parse(_).hcursor)
        val arrived = requests.filter(_.get[String]("phase") == Right("arrive")).map(r => r.get[Int]("seq").toOption.get -> r.get[Double]("t_ms").toOption.get).toMap
        val done = requests.filter(_.get[String]("phase") == Right("done")).map(r => r.get[Int]("seq").toOption.get -> r.get[Double]("t_ms").toOption.get).toMap
        assert(arrived.keySet == done.keySet, capture)
        prompts.zipWithIndex.foreach { case (start, index) =>
          val next = prompts.lift(index + 1).getOrElse(Double.MaxValue)
          val last = all.filter(h => h.event == "Stop" && h.time >= start && h.time < next).map(_.time).max
          arrived.filter { case (_, t) => t >= start && t < next }.keys.foreach(seq => assert(done(seq) <= last, s"$capture request $seq ends after the last Stop"))
        }
        assert(arrived.values.forall(t => prompts.exists(_ <= t)), capture)
      }
    }
  }
}
