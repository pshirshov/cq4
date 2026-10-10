package cq.core

import scala.collection.mutable

/** Time accounting over units of work that are already in memory (Decision 14 rules 2, 3, 4, 6, 9 and 10).
  *
  * Pure: it reads no repository and no clock. Every time is in milliseconds. The read time and the latest recorded instant per
  * session are supplied by the caller.
  */
object TimeAccounting {
  /** What a unit is for the coarse split. */
  enum UnitClass {
    case Governing, OperatorWait, Other
  }

  /** How a unit ends. */
  enum End {
    /** Recorded end. */
    case Closed(at: Long)
    /** No recorded end, observed to the read time. */
    case Running
    /** Open governing attempt of an attached session, observed to the latest recorded instant of `session`. */
    case OpenAttached(session: String)
    /** Running operator wait, observed to its last observation. */
    case WaitRunning(lastObservation: Long)
  }

  final case class TimedUnit(id: String, category: String, start: Long, parent: Option[String], end: End, kind: UnitClass)

  final case class Input(
    units: Seq[TimedUnit],
    readTime: Long,
    sessionLatest: Map[String, Long],
    window: Option[(Long, Long)]
  )

  final case class UnitTime(
    id: String,
    category: String,
    start: Long,
    /** The recorded end, when there is one. */
    end: Option[Long],
    /** Start to the end of the observed part; the read time, the latest session instant or the last observation when open. */
    observedEnd: Long,
    /** Observed part, inside the explicit window when there is one. */
    duration: Long,
    ownTime: Long
  )

  final case class CategoryTime(total: Long, ownTotal: Long, units: Int)

  final case class Window(from: Long, until: Long, open: Boolean, explicit: Boolean) {
    def length: Long = until - from
  }

  final case class Split(running: Long, waiting: Long, governing: Long, unobserved: Long, idle: Long) {
    def sum: Long = running + waiting + governing + unobserved + idle
  }

  final case class Result(
    window: Option[Window],
    units: Vector[UnitTime],
    categories: Map[String, CategoryTime],
    split: Split,
    open: Boolean,
    lastObserved: Option[Long]
  )

  private val ZeroSplit = Split(0L, 0L, 0L, 0L, 0L)

  private def observedEnd(unit: TimedUnit, input: Input): Long = {
    val raw = unit.end match {
      case End.Closed(at) => at
      case End.Running => input.readTime
      case End.OpenAttached(session) => input.sessionLatest.getOrElse(session, unit.start)
      case End.WaitRunning(last) => last
    }
    math.max(unit.start, raw)
  }

  private def hasTail(unit: TimedUnit): Boolean = unit.end match {
    case _: End.OpenAttached | _: End.WaitRunning => true
    case _ => false
  }

  private def isOpenAttached(unit: TimedUnit): Boolean = unit.end match {
    case _: End.OpenAttached => true
    case _ => false
  }

  def compute(input: Input): Result = {
    val units = input.units.toVector
    val ends = units.map(observedEnd(_, input))
    val open = units.exists(hasTail)
    val lastObserved = if (units.isEmpty) None else Some(ends.max)
    val window: Option[Window] = input.window match {
      case _ if units.isEmpty => None
      case Some((from, until)) => Some(Window(from, math.max(from, until), open, explicit = true))
      case None =>
        val from = units.map(_.start).min
        val until = if (units.size == 1 && isOpenAttached(units.head)) from else ends.max
        Some(Window(from, until, open, explicit = false))
    }
    val explicitWindow = window.filter(_.explicit)
    def clip(from: Long, until: Long): (Long, Long) = explicitWindow match {
      case Some(w) =>
        val a = math.max(from, w.from)
        val b = math.min(until, w.until)
        if (b > a) (a, b) else (a, a)
      case None => (from, until)
    }
    val figures = units.indices.map(i => clip(units(i).start, ends(i)))
    val index = units.indices.map(i => units(i).id -> i).toMap
    // direct children by parent index; a parent outside the units, or the unit itself, counts as none
    val children = mutable.HashMap.empty[Int, mutable.ArrayBuffer[Int]]
    units.indices.foreach { i =>
      units(i).parent.flatMap(index.get).filter(_ != i).foreach(p => children.getOrElseUpdate(p, mutable.ArrayBuffer.empty) += i)
    }
    def own(i: Int): Long = {
      val (from, until) = figures(i)
      val duration = until - from
      children.get(i) match {
        case None => duration
        case Some(kids) =>
          val clipped = kids.iterator
            .map(figures)
            .map { case (a, b) => (math.max(a, from), math.min(b, until)) }
            .filter { case (a, b) => b > a }
            .toArray
            .sortBy(_._1)
          var covered = 0L
          var cursor = from
          clipped.foreach { case (a, b) =>
            val lo = math.max(a, cursor)
            if (b > lo) { covered += b - lo; cursor = b }
          }
          duration - covered
      }
    }
    val times = units.indices.map { i =>
      val (from, until) = figures(i)
      UnitTime(units(i).id, units(i).category, units(i).start, units(i).end match { case End.Closed(at) => Some(at); case _ => None }, ends(i), until - from, own(i))
    }.toVector
    val categories = times.groupBy(_.category).map { case (category, members) =>
      category -> CategoryTime(members.map(_.duration).sum, members.map(_.ownTime).sum, members.size)
    }
    val split = window.fold(ZeroSplit)(w => coarse(units, ends, w))
    Result(window, times, categories, split, open, lastObserved)
  }

  // classes: 0 running, 1 waiting, 2 governing, 3 tail
  private def coarse(units: Vector[TimedUnit], ends: Vector[Long], window: Window): Split = {
    val events = mutable.ArrayBuffer.empty[(Long, Int, Int)]
    def add(cls: Int, from: Long, until: Long): Unit = {
      val a = math.max(from, window.from)
      val b = math.min(until, window.until)
      if (b > a) { events += ((a, cls, 1)); events += ((b, cls, -1)) }
    }
    units.indices.foreach { i =>
      val u = units(i)
      val cls = u.kind match {
        case UnitClass.Other => 0
        case UnitClass.OperatorWait => 1
        case UnitClass.Governing => 2
      }
      add(cls, u.start, ends(i))
      if (hasTail(u)) add(3, ends(i), window.until)
    }
    val sorted = events.sortBy(_._1).toArray
    val counts = Array.fill(4)(0)
    val spans = Array.fill(5)(0L)
    def state: Int =
      if (counts(0) > 0) 0 else if (counts(1) > 0) 1 else if (counts(2) > 0) 2 else if (counts(3) > 0) 3 else 4
    var cursor = window.from
    var k = 0
    while (k < sorted.length) {
      val t = sorted(k)._1
      if (t > cursor) { spans(state) += t - cursor; cursor = t }
      while (k < sorted.length && sorted(k)._1 == t) { counts(sorted(k)._2) += sorted(k)._3; k += 1 }
    }
    if (window.until > cursor) spans(state) += window.until - cursor
    Split(spans(0), spans(1), spans(2), spans(3), spans(4))
  }
}
