package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

object PreviewDigest {
  def apply(scope: Scope, plan: Json): String = {
    val identity = Json.obj("project" -> ProjectId_JsonCodec.encode(BaboonCodecContext.Default, scope.project),
      "actor" -> Actor_JsonCodec.encode(BaboonCodecContext.Default, scope.actor), "plan" -> plan)
    // Preview arrays denote sets; canonical order must not depend on process-local set iteration.
    def canonical(value: Json): Json = value.arrayOrObject(value,
      values => Json.fromValues(values.map(canonical).sortBy(_.noSpaces)),
      fields => Json.fromFields(fields.toList.sortBy(_._1).map { case (name, item) => name -> canonical(item) }))
    MessageDigest.getInstance("SHA-256").digest(canonical(identity).noSpaces.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  }
}
