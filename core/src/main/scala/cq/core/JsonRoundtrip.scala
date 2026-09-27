package cq.core

import io.circe.Json

object JsonRoundtrip {
  private enum Shape {
    case Scalar(value: Json)
    case Fields(values: Map[String, Shape])
    case Elements(counts: Map[Shape, Int])
  }

  private def shape(value: Json): Shape = value.arrayOrObject(Shape.Scalar(value),
    values => Shape.Elements(values.map(shape).groupMapReduce(identity)(_ => 1)(_ + _)),
    fields => Shape.Fields(fields.toMap.view.mapValues(shape).toMap))

  // Generated Set codecs may reorder arrays. This checks loss only; decoded Lists retain wire order.
  // Do not use this comparison for request identity, semantic equality or artifact digests.
  def lossless(original: Json, encoded: Json): Boolean = shape(original) == shape(encoded)
}
