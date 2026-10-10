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
  // A field the original lacks and the codec writes as null is an absent optional (a field added to a contract after the
  // document was stored): it is not a loss, so it is dropped from the encoded side before comparing.
  private def withoutAbsentNulls(original: Json, encoded: Json): Json = (original.asObject, encoded.asObject) match {
    case (Some(from), Some(to)) => Json.fromFields(to.toList.collect {
      case (name, value) if !(value.isNull && from(name).isEmpty) => name -> from(name).fold(value)(withoutAbsentNulls(_, value))
    })
    case _ => (original.asArray, encoded.asArray) match {
      case (Some(from), Some(to)) if from.size == to.size =>
        val paired = Json.fromValues(from.zip(to).map(withoutAbsentNulls))
        // Set codecs may reorder elements; pair each encoded element with the original it reproduces.
        if (shape(paired) == shape(original) || from.size > 256) paired
        else Json.fromValues(to.map(element => from.view.map(withoutAbsentNulls(_, element)).find(c => from.exists(shape(_) == shape(c))).getOrElse(element)))
      case _ => encoded
    }
  }

  def lossless(original: Json, encoded: Json): Boolean = shape(original) == shape(withoutAbsentNulls(original, encoded))
}
