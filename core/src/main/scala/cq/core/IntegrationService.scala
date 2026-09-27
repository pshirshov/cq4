package cq.core

import cq.api.*

trait IntegrationService[F[_, _]] {
  def reserve(scope: Scope, intent: IntegrationIntent): F[Throwable, IntegrationRecord]
  def observe(scope: Scope, id: IntegrationId, observation: IntegrationObservation): F[Throwable, IntegrationRecord]
  def get(scope: Scope, id: IntegrationId): F[Throwable, IntegrationRecord]
}
