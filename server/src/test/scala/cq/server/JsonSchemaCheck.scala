package cq.server

import io.circe.Json
import java.util.regex.Pattern

/** Validates a JSON value against the JSON Schema dialect of the generated cq-api schemas and their Codex translation.
  * It is strict about the dialect: a keyword, type or format it does not implement is an error, never silently accepted. */
object JsonSchemaCheck {
  private val Uuid = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  private def unsupported(what: String, path: String): Nothing = throw new IllegalArgumentException(s"Unsupported schema $what at $path")

  /** Violations of `schema` by `value`, each with the JSON path of the offending value; empty when the value is valid. */
  def errors(schema: Json, value: Json): List[String] = {
    val definitions = schema.hcursor.downField("$defs").focus.flatMap(_.asObject)
    def integer(argument: Json, path: String): Int = argument.asNumber.flatMap(_.toInt).getOrElse(unsupported("bound", path))
    def check(rule: Json, value: Json, path: String): List[String] = {
      val fields = rule.asObject.getOrElse(unsupported("non-object rule", path))
      fields.toList.flatMap { case (keyword, argument) =>
        def fail(message: String): List[String] = List(s"$path: $message")
        def branches: List[List[String]] = argument.asArray.getOrElse(unsupported(keyword, path)).toList.map(check(_, value, path))
        keyword match {
          case "$defs" => if (path == "$") Nil else unsupported("nested $defs", path)
          case "description" => if (argument.isString) Nil else unsupported("description", path)
          case "$ref" =>
            val name = argument.asString.filter(_.startsWith("#/$defs/")).getOrElse(unsupported("reference", path)).stripPrefix("#/$defs/")
            check(definitions.flatMap(_(name)).getOrElse(unsupported(s"missing definition $name", path)), value, path)
          case "type" =>
            val matches = argument.asString.getOrElse(unsupported("type", path)) match {
              case "object" => value.isObject
              case "array" => value.isArray
              case "string" => value.isString
              case "boolean" => value.isBoolean
              case "null" => value.isNull
              case "integer" => value.asNumber.exists(_.toBigInt.nonEmpty)
              case other => unsupported(s"type $other", path)
            }
            if (matches) Nil else fail(s"expected ${argument.noSpaces}, found ${value.name}")
          case "enum" =>
            if (argument.asArray.getOrElse(unsupported("enum", path)).contains(value)) Nil else fail(s"${value.noSpaces} is not one of ${argument.noSpaces}")
          case "properties" =>
            val properties = argument.asObject.getOrElse(unsupported("properties", path))
            value.asObject.toList.flatMap(members => properties.toList.flatMap { case (name, property) =>
              members(name).toList.flatMap(check(property, _, s"$path.$name"))
            })
          case "required" =>
            val names = argument.as[List[String]].getOrElse(unsupported("required", path))
            value.asObject.toList.flatMap(members => names.filterNot(members.contains).flatMap(name => fail(s"missing required property $name")))
          case "additionalProperties" =>
            if (argument != Json.False) unsupported("open additionalProperties", path)
            val declared = fields("properties").flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)
            value.asObject.toList.flatMap(_.keys.filterNot(declared).flatMap(name => fail(s"undeclared property $name")))
          case "items" => value.asArray.toList.flatMap(_.zipWithIndex.flatMap((item, index) => check(argument, item, s"$path[$index]")))
          case "oneOf" =>
            val results = branches
            if (results.count(_.isEmpty) == 1) Nil
            else fail(s"matches ${results.count(_.isEmpty)} of ${results.size} oneOf branches: ${results.flatten.mkString("; ")}")
          case "anyOf" =>
            val results = branches
            if (results.exists(_.isEmpty)) Nil else fail(s"matches no anyOf branch: ${results.flatten.mkString("; ")}")
          case "pattern" =>
            val pattern = Pattern.compile(argument.asString.getOrElse(unsupported("pattern", path)))
            value.asString.filterNot(pattern.matcher(_).find()).toList.flatMap(text => fail(s"'$text' does not match ${pattern.pattern}"))
          case "format" =>
            if (argument != Json.fromString("uuid")) unsupported(s"format ${argument.noSpaces}", path)
            value.asString.filterNot(Uuid.matcher(_).matches()).toList.flatMap(text => fail(s"'$text' is not a UUID"))
          case "minimum" | "maximum" =>
            val bound = argument.asNumber.flatMap(_.toBigDecimal).getOrElse(unsupported(keyword, path))
            value.asNumber.flatMap(_.toBigDecimal).filter(number => if (keyword == "minimum") number < bound else number > bound)
              .toList.flatMap(number => fail(s"$number violates $keyword $bound"))
          case "minItems" => value.asArray.filter(_.size < integer(argument, path)).toList.flatMap(items => fail(s"${items.size} items violate minItems ${argument.noSpaces}"))
          case "maxItems" => value.asArray.filter(_.size > integer(argument, path)).toList.flatMap(items => fail(s"${items.size} items violate maxItems ${argument.noSpaces}"))
          case "uniqueItems" =>
            if (argument != Json.True) unsupported("uniqueItems", path)
            value.asArray.filter(items => items.distinct.size != items.size).toList.flatMap(_ => fail("items are not unique"))
          case "minLength" | "maxLength" =>
            val bound = integer(argument, path)
            value.asString.map(text => text.codePointCount(0, text.length)).filter(length => if (keyword == "minLength") length < bound else length > bound)
              .toList.flatMap(length => fail(s"length $length violates $keyword $bound"))
          case other => unsupported(s"keyword $other", path)
        }
      }
    }
    check(schema, value, "$")
  }
}
