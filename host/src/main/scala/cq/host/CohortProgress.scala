package cq.host

import cq.api.*
import cq.core.LedgerPolicy
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

final case class CohortExecutionFingerprint(group: String, members: Map[ItemId, String])
/** The fault of an attempt that left no result, and the artifact that carries it to the next attempt on the same input. */
final case class CohortFailure(artifact: ArtifactId, fault: String)

object CohortFailure {
  /** The fault of a child that left no result and that its receipt advises retrying. */
  def fault(status: DispatchStatus): Option[String] = status.blocker.filter(_ => status.result.isEmpty && status.next == ChildNext.Retry)
}

final class CohortProgress {
  private final case class Seen(attempts: Int, offered: Int, sequence: Int)
  private var members = Map.empty[ItemId, Seen]
  private var completed = Set.empty[String]
  private var executed = Set.empty[String]
  private var failures = Map.empty[String, CohortFailure]
  private var inspectedAfter = Option.empty[ItemId]

  def order(ids: List[ItemId]): List[ItemId] = synchronized {
    ids.sortBy(LedgerPolicy.key).foreach { id =>
      if (!members.contains(id)) members += id -> Seen(0, 0, members.size)
    }
    ids.sortBy { id => val seen = members(id); (seen.attempts, seen.offered, seen.sequence, LedgerPolicy.key(id)) }
  }
  def offered(ids: List[ItemId]): Unit = synchronized {
    ids.distinct.foreach(id => members = members.updated(id, members(id).copy(offered = members(id).offered + 1)))
  }
  def pool(ids: List[ItemId], limit: Int): List[ItemId] = synchronized {
    val position = inspectedAfter.fold(-1)(ids.indexOf)
    (ids.drop(position + 1) ++ ids.take(position + 1)).take(limit)
  }
  def inspected(ids: List[ItemId], offered: Boolean): Unit = synchronized {
    inspectedAfter = if (offered) None else ids.lastOption
  }
  def started(fingerprint: CohortExecutionFingerprint): Unit = synchronized {
    require(!executed(fingerprint.group) && !fingerprint.members.values.exists(executed), "Cohort operative input was already executed")
    executed ++= fingerprint.members.values.toSet + fingerprint.group
    fingerprint.members.keys.foreach(id => members = members.updated(id, members(id).copy(attempts = members(id).attempts + 1)))
  }
  /**
   * An attempt that left no result has not executed its input, which is offered again with the fault. The same fault twice in a row on
   * one input is an unchanged failure: the input stays deferred, and the reply is true.
   */
  def finished(fingerprint: CohortExecutionFingerprint, failure: Option[CohortFailure]): Boolean = synchronized {
    require(executed(fingerprint.group), "Unstarted cohort cannot finish")
    completed += fingerprint.group
    val inputs = fingerprint.members.values.toSet + fingerprint.group
    val repeated = failure.exists(value => failures.get(fingerprint.group).exists(_.fault == value.fault))
    failures --= inputs
    failure.filterNot(_ => repeated).foreach { value =>
      executed --= inputs
      failures ++= inputs.map(_ -> value)
    }
    repeated
  }
  def failure(fingerprint: String): Option[CohortFailure] = synchronized(failures.get(fingerprint))
  def ended(fingerprint: String): Boolean = synchronized(completed(fingerprint))
  def deferred(fingerprint: String): Boolean = synchronized(executed(fingerprint))
}

object CohortFingerprint {
  private def digest(value: Json): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.noSpaces.getBytes(UTF_8)))

  def assessment(semantic: String, members: List[ItemRevision]): String = digest(Json.obj("semantic" -> Json.fromString(semantic),
    "assessment" -> Json.fromValues(members.sortBy(ref => LedgerPolicy.key(ref.id)).map(ref =>
      ItemRevision_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, ref)))))

  private def canonical(value: Json): Json = value.arrayOrObject(value,
    values => Json.fromValues(values.map(canonical)),
    fields => Json.fromFields(fields.toList.sortBy(_._1).map((key, value) => key -> canonical(value))))

  private def report(value: Json): Json = value.arrayOrObject(value,
    values => Json.fromValues(values.map(report)),
    fields => {
      val reference = fields.contains("id") && fields.contains("revision") && fields.size == 2
      Json.fromFields(fields.toList.filterNot((key, _) => key == "labels" || (reference && key == "revision")).map { (key, value) =>
        val nested = report(value)
        val unordered = Set("members", "assessments", "checks")(key) ||
          (key == "acceptance" && nested.asArray.exists(_.forall(_.hcursor.downField("criterion").succeeded)))
        key -> (if (unordered) nested.asArray.fold(nested)(values => Json.fromValues(values.sortBy(_.noSpaces))) else nested)
      })
    })

  def apply(work: DispatchWork, members: List[ItemView], guidance: List[ItemView], artifacts: List[CohortArtifactFingerprint],
    results: List[CohortResultFingerprint], base: GitCommit, checks: List[ValidationCheck]): String = {
    val ids = members.map(_.item.id).toSet
    def project(value: ChildReport): ChildReport = value match {
      case evidence: ChildReport.Evidence => evidence.copy(members = evidence.members.filter(member => ids(member.item)))
      case plan: ChildReport.Plan => plan.copy(members = plan.members.filter(member => ids(member.item)),
        assessments = plan.assessments.filter(_.members.exists(member => ids(member.member.id)))
          .map(group => group.copy(members = group.members.filter(member => ids(member.member.id)))))
      case worker: ChildReport.Work => worker.copy(members = worker.members.filter(member => ids(member.item)))
      case review: ChildReport.Review => review.copy(members = review.members.filter(member => ids(member.item)))
    }
    def items(values: List[ItemView]): Json = Json.fromValues(values.sortBy(value => LedgerPolicy.key(value.item.id)).map { view =>
      Json.obj("id" -> ItemId_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, view.item.id),
        "draft" -> ItemDraft_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, view.item.draft).mapObject(_.remove("labels")),
        "refs" -> Json.fromValues(view.refs.map(ref => ItemRef_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, ref)).sortBy(_.noSpaces)))
    })
    def result(source: CohortResultFingerprint): Option[Json] = {
      val value = source.value
      val overlapping = value.request.members.exists(member => ids(member.id))
      if (overlapping && value.request.work == work) None
      else Some(Json.obj(
        "report" -> report(ChildReport_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default,
          if (overlapping) project(value.report) else value.report)),
        "candidate" -> value.candidate.fold(Json.Null)(value => Json.fromString(value.value)),
        "observations" -> Json.fromValues(source.validation.map(_.content).map(canonical).sortBy(_.noSpaces)),
        "validation" -> Json.fromValues(value.validation.sortBy(_.check).map(value => Json.obj(
          "check" -> Json.fromString(value.check), "state" -> Json.fromString(value.state.toString))))))
    }
    val evidence = (results.flatMap(result) ++
      artifacts.filterNot(value => Set(ArtifactKind.Result, ArtifactKind.Selection)(value.kind))
        .map(value => Json.obj("kind" -> Json.fromString(value.kind.toString), "mediaType" -> Json.fromString(value.mediaType),
          "content" -> value.content))).map(canonical).distinct.sortBy(_.noSpaces)
    val value = canonical(Json.obj("work" -> DispatchWork_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, work),
      "members" -> items(members), "guidance" -> items(guidance), "evidence" -> Json.fromValues(evidence),
      "base" -> Json.fromString(base.value), "checks" -> Json.fromValues(checks.sortBy(_.name).map(value => ValidationCheck_JsonCodec.encode(baboon.runtime.shared.BaboonCodecContext.Default, value)))))
    digest(value)
  }
}
