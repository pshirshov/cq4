package cq.server

import cq.api.*
import org.scalatest.wordspec.AnyWordSpec

final class SupervisorSettingsLocal extends AnyWordSpec {
  private def check(attempts: Int): ValidationCheck = ValidationCheck("unit", List("verify"), 1000, 65536, attempts)

  "Configured validation checks (Behavioral Active Blackbox Atomic)" should {
    "I19: accept one to three attempts of a failing check and reject any other count" in {
      List(1, 2, 3).foreach(attempts => SupervisorConfig.reruns(check(attempts)))
      List(Int.MinValue, -1, 0, 4, Int.MaxValue).foreach { attempts =>
        val refused = intercept[IllegalArgumentException](SupervisorConfig.reruns(check(attempts)))
        assert(refused.getMessage.contains("Invalid configured validation check"), attempts.toString)
      }
    }
  }
}
