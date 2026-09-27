package cq.core

import cq.api.*

trait ResultAdmissionService[F[_, _]] {
  def admit(scope: Scope, input: HostAdmissionInput): F[Throwable, ResultAdmission]
  def get(scope: Scope, attempt: AttemptId): F[Throwable, ResultAdmission]
}
