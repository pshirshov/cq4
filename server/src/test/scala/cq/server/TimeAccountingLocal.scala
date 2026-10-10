package cq.server

import cq.core.TimeAccounting
import cq.core.TimeAccounting.{End, Input, Result, TimedUnit as U, UnitClass}
import org.scalatest.wordspec.AnyWordSpec

final class TimeAccountingLocal extends AnyWordSpec {
  private def s(seconds: Long): Long = seconds * 1000L

  private def attached(id: String, start: Long, session: String = "s", parent: Option[String] = None) =
    U(id, "Govern", s(start), parent, End.OpenAttached(session), UnitClass.Governing)
  private def managed(id: String, start: Long) = U(id, "Govern", s(start), None, End.Running, UnitClass.Governing)
  private def governed(id: String, start: Long, end: Long) = U(id, "Govern", s(start), None, End.Closed(s(end)), UnitClass.Governing)
  private def unit(id: String, category: String, start: Long, end: Long, parent: Option[String] = None) =
    U(id, category, s(start), parent, End.Closed(s(end)), UnitClass.Other)
  private def running(id: String, category: String, start: Long, parent: Option[String] = None) =
    U(id, category, s(start), parent, End.Running, UnitClass.Other)

  private def measure(units: Seq[U], read: Long = 0, latest: Map[String, Long] = Map.empty, window: Option[(Long, Long)] = None): Result =
    TimeAccounting.compute(Input(units, s(read), latest.view.mapValues(s).toMap, window.map { case (a, b) => (s(a), s(b)) }))

  private def split(r: Result, running: Long, waiting: Long, governing: Long, unobserved: Long, idle: Long): Unit = {
    val sp = r.split
    assert((sp.running, sp.waiting, sp.governing, sp.unobserved, sp.idle) == (s(running), s(waiting), s(governing), s(unobserved), s(idle)), sp.toString)
    assert(sp.sum == r.window.get.length)
  }
  private def of(r: Result, id: String) = r.units.find(_.id == id).get

  "Time accounting (Behavioral Active Blackbox Good Communication)" should {
    "S1 attached: unobserved tail after the session's latest instant" in {
      val r = measure(Seq(attached("g", 0), unit("w", "Work", 10, 30)), latest = Map("s" -> 30), window = Some((0, 60)))
      split(r, 20, 0, 10, 30, 0)
      assert(r.open && r.lastObserved.contains(s(30)))
    }
    "S2 managed: the same records, governing running to the read time" in {
      val r = measure(Seq(managed("g", 0), unit("w", "Work", 10, 30)), read = 60)
      assert(r.window.exists(w => w.from == 0 && w.until == s(60)))
      split(r, 20, 0, 40, 0, 0)
      assert(!r.open)
    }
    "S3 managed and closed at 50" in {
      val r = measure(Seq(governed("g", 0, 50), unit("w", "Work", 10, 30)), window = Some((0, 60)))
      split(r, 20, 0, 30, 0, 10)
      assert(!r.open)
    }
    "S4 read during host work" in {
      val r = measure(Seq(attached("g", 0), running("c", "Check", 10)), read = 25, latest = Map("s" -> 10))
      assert(r.window.exists(w => w.from == 0 && w.until == s(25)))
      split(r, 15, 0, 10, 0, 0)
      assert(of(r, "c").duration == s(15))
    }
    "S5 interrupted publication, then closed by recovery" in {
      val a = measure(Seq(attached("g", 0), running("c", "Check", 10)), read = 100, latest = Map("s" -> 10))
      split(a, 90, 0, 10, 0, 0)
      val b = measure(Seq(governed("g", 0, 200), unit("c", "Check", 10, 200)), window = Some((0, 200)))
      assert(of(b, "c").duration == s(190))
      split(b, 190, 0, 10, 0, 0)
    }
    "S6 start not delivered, then delivered" in {
      val a = measure(Seq(attached("g", 0)), latest = Map("s" -> 10), window = Some((0, 25)))
      split(a, 0, 0, 10, 15, 0)
      val b = measure(Seq(attached("g", 0), unit("c", "Check", 10, 40)), latest = Map("s" -> 40), window = Some((0, 25)))
      split(b, 15, 0, 10, 0, 0)
    }
    "a governing attempt is never running, attached or managed" in {
      val a = measure(Seq(attached("g", 0), unit("w", "Work", 10, 30)), latest = Map("s" -> 30), window = Some((0, 60)))
      val b = measure(Seq(managed("g", 0), unit("w", "Work", 10, 30)), read = 60, window = Some((0, 60)))
      assert(a.split.running == s(20) && b.split.running == s(20))
    }
    "S7 parent clipped to a child that outlives the parent's request" in {
      val units = Seq(unit("r", "Review", 0, 100, Some("g")), unit("c", "Check", 20, 60, Some("r")), governed("g", 0, 100))
      val r = measure(units)
      assert(of(r, "r").duration == s(100) && of(r, "r").ownTime == s(60))
      assert(of(r, "c").duration == s(40) && of(r, "c").ownTime == s(40))
      assert(r.categories("Review").total == s(100) && r.categories("Review").ownTotal == s(60))
      val more = measure(units :+ unit("t", "Check", 70, 80, Some("r")))
      assert(of(more, "r").ownTime == s(50))
    }
    "parallel children give own time 40 and never negative" in {
      val r = measure(Seq(governed("g", 0, 100), unit("a", "Work", 10, 50, Some("g")), unit("b", "Work", 30, 70, Some("g"))))
      assert(of(r, "g").ownTime == s(40))
      assert(r.units.forall(_.ownTime >= 0))
    }
    "category totals may exceed the window" in {
      val r = measure(Seq(unit("a", "Work", 0, 60), unit("b", "Work", 0, 60)), window = Some((0, 60)))
      assert(r.categories("Work").total == s(120))
      split(r, 60, 0, 0, 0, 0)
    }
    "closed operator wait" in {
      val units = Seq(governed("g", 0, 60), U("w", "Await", s(20), Some("g"), End.Closed(s(50)), UnitClass.OperatorWait), unit("k", "Work", 30, 40))
      split(measure(units), 10, 20, 30, 0, 0)
    }
    "window beyond the last wait observation" in {
      val units = Seq(attached("g", 0), U("w", "Await", s(20), Some("g"), End.WaitRunning(s(40)), UnitClass.OperatorWait))
      val r = measure(units, latest = Map("s" -> 40), window = Some((0, 60)))
      split(r, 0, 20, 20, 20, 0)
      assert(r.open)
    }
    "explicit window that cuts a unit and its child" in {
      val r = measure(Seq(unit("a", "Work", 10, 50), unit("b", "Probe", 20, 30, Some("a"))), window = Some((25, 40)))
      val a = of(r, "a")
      assert(a.start == s(10) && a.end.contains(s(50)))
      assert(a.duration == s(15) && a.ownTime == s(10))
      assert(r.categories("Work").total == s(15))
    }
    "a parent that is not among the units is none" in {
      val r = measure(Seq(unit("a", "Work", 0, 10, Some("missing"))))
      assert(of(r, "a").ownTime == s(10))
    }
    "no units" in {
      val r = measure(Seq.empty)
      assert(r.window.isEmpty && r.split.sum == 0 && r.categories.isEmpty && !r.open && r.lastObserved.isEmpty)
    }
    "no units with an explicit window: no window, all zero" in {
      val r = measure(Seq.empty, window = Some((0, 60)))
      assert(r.window.isEmpty && r.split.sum == 0 && r.categories.isEmpty && !r.open && r.lastObserved.isEmpty)
    }
    "several open attached governing attempts use earliest start to latest observed" in {
      val r = measure(Seq(attached("a", 0, "s1"), attached("b", 10, "s2")), latest = Map("s1" -> 20, "s2" -> 30))
      assert(r.window.exists(w => w.from == 0 && w.until == s(30) && w.open))
      split(r, 0, 0, 30, 0, 0)
    }
    "only an open attached governing attempt" in {
      val r = measure(Seq(attached("g", 7)), latest = Map("s" -> 20))
      assert(r.window.exists(w => w.from == s(7) && w.length == 0 && w.open))
    }
    "20,000 units with nested parents and parallel children" in {
      val blocks = 4000
      val buf = Vector.newBuilder[U]
      for (b <- 0 until blocks) {
        val o = 100L * b
        buf += unit(s"r$b", "Review", o, o + 80)
        buf += unit(s"c1-$b", "Check", o + 10, o + 40, Some(s"r$b"))
        buf += unit(s"c2-$b", "Check", o + 30, o + 60, Some(s"r$b"))
        buf += unit(s"g-$b", "Probe", o + 15, o + 25, Some(s"c1-$b"))
        buf += unit(s"c4-$b", "Check", o + 70, o + 80, Some(s"r$b"))
      }
      val units = buf.result()
      assert(units.size == 20000)
      val began = System.nanoTime()
      val r = measure(units)
      val millis = (System.nanoTime() - began) / 1000000
      info(s"20,000 units computed in $millis ms")
      assert(r.categories("Review") == TimeAccounting.CategoryTime(s(320000), s(80000), blocks))
      assert(r.categories("Check") == TimeAccounting.CategoryTime(s(280000), s(240000), 3 * blocks))
      assert(r.categories("Probe") == TimeAccounting.CategoryTime(s(40000), s(40000), blocks))
      val last = 100L * (blocks - 1) + 80
      assert(r.window.exists(w => w.from == 0 && w.until == s(last)))
      split(r, 320000, 0, 0, 0, last - 320000)
      assert(of(r, "r17").ownTime == s(20) && of(r, "c1-17").ownTime == s(20))
    }
  }
}
