package cq.core

import cq.api.*

object CohortAssessmentPolicy {
  val MaxMembers = CohortBounds.Members
  private val MaxAssessments = 8
  private val MaxText = 8192
  private val MaxCriteria = LedgerPolicy.MaxNestedEntries
  private val MaxChecks = 8
  private def valid(condition: Boolean, message: String): Unit = LedgerPolicy.invalid(condition, "Cohort assessment: " + message)
  private def text(value: String): Unit = valid(value.trim.nonEmpty && value.length <= MaxText, "bounded nonempty explanation required")

  def shape(assigned: List[ItemRevision], report: ChildReport.Plan): Unit = {
    valid(report.assessments.size <= MaxAssessments, "too many groups")
    val eligible = report.members.filter(value => Set(PlanDisposition.Proposed, PlanDisposition.Assessed)(value.disposition)).map(_.item).toSet
    val members = report.assessments.flatMap(_.members.map(_.member))
    valid(members.map(_.id).distinct.size == members.size, "groups overlap")
    valid(members.forall(member => assigned.contains(member) && eligible(member.id) && member.id.ledger == Ledger.Tasks),
      "group requires exact assigned task revisions and assessed/proposed outcomes")
    valid(report.members.filter(_.disposition == PlanDisposition.Assessed).forall(value => members.exists(_.id == value.item)),
      "Assessed outcome requires a stored assessment")
    report.assessments.foreach { group =>
      valid(group.members.size >= 2 && group.members.size <= MaxMembers, "group must contain two to four members")
      List(group.objective, group.dependencies, group.interference).foreach(text)
      group.members.foreach { member =>
        valid(member.acceptance.nonEmpty && member.acceptance.size <= MaxCriteria, "per-member acceptance mapping required")
        member.acceptance.foreach { criterion =>
          valid(criterion.criterion >= 0 && criterion.criterion < MaxCriteria, "invalid zero-based acceptance index")
          text(criterion.inspection)
          valid(criterion.checks.size <= MaxChecks && criterion.checks.forall(_.matches("[a-z][a-z0-9-]{0,49}")), "invalid named check mapping")
        }
      }
    }
  }

  def criteria(report: ChildReport.Plan, item: ItemId => Item): Unit = report.assessments.foreach { group =>
    group.members.foreach { member =>
      val current = item(member.member.id)
      valid(current.id == member.member.id && current.revision == member.member.revision, "member revision changed")
      val acceptance = current.draft.content match {
        case task: Content.Task => task.acceptance
        case _ => throw DomainFailure(Fault.Invalid("Cohort assessment requires tasks"))
      }
      valid(member.acceptance.map(_.criterion).sorted == acceptance.indices.toList, "mapping must cover every frozen acceptance criterion exactly")
    }
  }

  def checks(report: ChildReport.Plan, configured: List[ValidationCheck]): Unit = {
    val names = configured.map(_.name).toSet
    valid(report.assessments.flatMap(_.members.flatMap(_.acceptance.flatMap(_.checks))).forall(names), "mapping names an unconfigured check")
  }

  def reviewable(work: DispatchWork, members: List[ItemRevision], report: ChildReport): Boolean = {
    val proposal = ProposalPolicy.prepare(work, members, report).nonEmpty
    val assessment = (work, report) match {
      case (_: DispatchWork.Planner, plan: ChildReport.Plan) => shape(members, plan); plan.assessments.nonEmpty
      case _ => false
    }
    proposal || assessment
  }
}
