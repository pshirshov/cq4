package cq.server

import cq.api.*
import org.scalatest.wordspec.AnyWordSpec

final class SupervisorSettingsLocal extends AnyWordSpec {
  private def check(attempts: Int): ValidationCheck = ValidationCheck("unit", List("verify"), 1000, 65536, attempts, 0)

  "Configured validation checks (Behavioral Active Blackbox Atomic)" should {
    "I19: accept one to three attempts of a failing check and reject any other count" in {
      List(1, 2, 3).foreach(attempts => SupervisorConfig.reruns(check(attempts)))
      List(Int.MinValue, -1, 0, 4, Int.MaxValue).foreach { attempts =>
        val refused = intercept[IllegalArgumentException](SupervisorConfig.reruns(check(attempts)))
        assert(refused.getMessage.contains("Invalid configured validation check"), attempts.toString)
      }
    }
    "I19: accept zero to three governor-requested revalidation rounds of a check and reject any other count" in {
      List(0, 1, 2, 3).foreach(rounds => SupervisorConfig.reruns(check(1).copy(revalidations = rounds)))
      List(Int.MinValue, -1, 4, Int.MaxValue).foreach { rounds =>
        val refused = intercept[IllegalArgumentException](SupervisorConfig.reruns(check(1).copy(revalidations = rounds)))
        assert(refused.getMessage.contains("Invalid configured validation check"), rounds.toString)
      }
    }
  }
}
