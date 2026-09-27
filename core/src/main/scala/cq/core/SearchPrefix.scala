package cq.core

import java.nio.charset.StandardCharsets.UTF_8

final case class SearchPrefix(value: String) {
  require(UTF_8.newEncoder().canEncode(value) && !value.contains('\u0000'), "Prefix requires valid Unicode without NUL")
  def matches(candidate: String): Boolean = candidate.startsWith(value)
  def upper: Option[String] = {
    val points = value.codePoints().toArray
    var last = points.length - 1
    while (last >= 0 && points(last) == Character.MAX_CODE_POINT) last -= 1
    if (last < 0) None
    else {
      points(last) = if (points(last) == Character.MIN_SURROGATE.toInt - 1) Character.MAX_SURROGATE.toInt + 1 else points(last) + 1
      Some(new String(points, 0, last + 1))
    }
  }
}

object SearchPrefix {
  val ordering: Ordering[String] = (left, right) => java.util.Arrays.compareUnsigned(left.getBytes(UTF_8), right.getBytes(UTF_8))
}
