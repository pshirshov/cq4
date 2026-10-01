package cq.host

import cq.api.Harness
import io.circe.Json

/** The output schema a harness is given for a canonical result schema. Pure. */
object HarnessSchema {
  def result(harness: Harness, canonical: Json): Json = harness match {
    case Harness.Claude | Harness.Pi => canonical
    case Harness.Codex => CodexSchema.result(canonical)
  }
}

object CodexSchema {
  val UniqueItemsRule = "Items must be unique. CQ rejects duplicate set members."
  def result(schema: Json): Json = {
    val definitions = schema.hcursor.downField("$defs").focus.flatMap(_.asObject)
    def resolved(value: Json, seen: Set[String]): Json = value.hcursor.get[String]("$ref").toOption match {
      case None => value
      case Some(reference) =>
        require(reference.startsWith("#/$defs/") && !seen(reference), "Unsupported or cyclic schema alias")
        resolved(definitions.flatMap(_(reference.stripPrefix("#/$defs/"))).getOrElse(
          throw new IllegalArgumentException("Missing native schema reference")), seen + reference)
    }
    def disjoint(branches: Vector[Json]): Boolean = {
      val values = branches.map(resolved(_, Set.empty))
      val types = values.map(_.hcursor.get[String]("type").toOption.map {
        case "integer" => "number"
        case other => other
      })
      val tags = values.map { value =>
        for {
          properties <- value.hcursor.downField("properties").focus.flatMap(_.asObject)
          required <- value.hcursor.get[List[String]]("required").toOption
          if value.hcursor.get[String]("type") == Right("object") &&
            value.hcursor.get[Boolean]("additionalProperties") == Right(false) &&
            required.size == 1 && properties.keys.toSet == required.toSet
        } yield required.head
      }
      branches.size >= 2 && (types.forall(_.nonEmpty) && types.distinct.size == types.size ||
        tags.forall(_.nonEmpty) && tags.distinct.size == tags.size)
    }
    def translate(value: Json): Json = value.mapObject { fields =>
      val union = fields("oneOf") match {
        case None => fields
        case Some(branches) =>
          val values = branches.asArray.getOrElse(throw new IllegalArgumentException("Expected schema union array"))
          require(!fields.contains("anyOf") && disjoint(values), "Cannot translate overlapping schema union for Codex")
          fields.remove("oneOf").add("anyOf", branches)
      }
      val native = union("uniqueItems") match {
        case None => union
        case Some(value) =>
          require(value == Json.True && union("type").contains(Json.fromString("array")), "Expected generated Set schema")
          val description = union("description").map(_.asString.getOrElse(throw new IllegalArgumentException("Expected schema description")))
          union.remove("uniqueItems").add("description", Json.fromString(description.fold(UniqueItemsRule)(_ + " " + UniqueItemsRule)))
      }
      native.toList.foldLeft(native) { case (output, (key, child)) =>
        val nested = key match {
          case "$defs" | "properties" => child.mapObject(_.mapValues(translate))
          case "items" => translate(child)
          case "anyOf" => child.mapArray(_.map(translate))
          case _ => child
        }
        output.add(key, nested)
      }
    }
    require(!schema.hcursor.downField("oneOf").succeeded && !schema.hcursor.downField("anyOf").succeeded,
      "Codex requires an object root without a union")
    translate(schema)
  }
}
