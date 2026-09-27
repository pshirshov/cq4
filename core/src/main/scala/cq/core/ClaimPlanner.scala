package cq.core

import baboon.runtime.shared.BaboonCodecContext
import cq.api.*
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8

final class ClaimPlanner {
  def preview(tx: LedgerTransaction, scope: Scope, members: Set[ItemId], now: Long): ClaimPreview = {
    LedgerPolicy.invalid(members.nonEmpty && members.size <= LedgerPolicy.MaxBatch, "Invalid claim membership")
    val revisions = members.toList.sortBy(LedgerPolicy.key).map { id =>
      if (id.project != scope.project) throw DomainFailure(Fault.Denied("Claim item belongs to another project"))
      LedgerPolicy.invalid(id.number > 0, "Item number must be positive")
      val item = tx.summary(id).getOrElse(throw DomainFailure(Fault.Missing("Claim item does not exist")))
      ItemRevision(item.id, item.revision)
    }
    val claims = ClaimPolicy.overlapping(tx, members, now)
    val integrations = IntegrationPolicy.pending(tx, members ++ claims.flatMap(_.members))
    val plan = Json.obj("members" -> Json.fromValues(revisions.map(ItemRevision_JsonCodec.encode(BaboonCodecContext.Default, _))),
      "integrations" -> Json.fromValues(integrations.map(IntegrationHold_JsonCodec.encode(BaboonCodecContext.Default, _))),
      "claims" -> Json.fromValues(claims.map(c => Claim_JsonCodec.encode(BaboonCodecContext.Default, c).mapObject(_.remove("expiresAt")))))
    val result = ClaimPreview(revisions, claims, integrations, ClaimSnapshot(tx.cursor, PreviewDigest(scope, plan)))
    if (ClaimPreview_JsonCodec.encode(BaboonCodecContext.Default, result).noSpaces.getBytes(UTF_8).length > ReadPage.MaxBytes - ReadPage.EnvelopeBytes)
      throw DomainFailure(Fault.Limit("Claim preview exceeds the encoded-byte bound; choose fewer members"))
    result
  }
}
