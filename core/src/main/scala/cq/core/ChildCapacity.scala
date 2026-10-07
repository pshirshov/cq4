package cq.core

/** How many child attempts one governing session runs at once. A unit whose first seats do not fit is not started. */
object ChildCapacity {
  val MaxActiveChildren = 4
}
