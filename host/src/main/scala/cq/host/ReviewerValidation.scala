package cq.host

import cq.api.*

object ReviewerValidation {
  def inventory(inherited: List[ValidationEvidence], declarations: List[ValidationCheck]): Unit =
    require(inherited.map(_.check) == declarations.map(_.name), "Candidate review requires the worker's exact configured check inventory")

  def overlay(inherited: List[ValidationEvidence], observed: List[ValidationEvidence]): List[ValidationEvidence] = {
    require(observed.map(_.check).distinct.size == observed.size && observed.forall(value => inherited.exists(_.check == value.check)),
      "Requested reviewer evidence must belong to the inherited check inventory")
    val fresh = observed.map(value => value.check -> value).toMap
    inherited.map(value => fresh.getOrElse(value.check, value))
  }
}
