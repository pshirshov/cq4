package cq.core

import cq.api.*

trait ProposalService[F[_, _]] {
  def preview(scope: Scope, result: ArtifactId): F[Throwable, ProposalPreview]
  def apply(scope: Scope, result: ArtifactId): F[Throwable, ChangeAck]
}
