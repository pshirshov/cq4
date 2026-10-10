package cq.server

import cq.api.*
import cq.host.*
import io.circe.Json
import org.scalatest.wordspec.AnyWordSpec

final class TierRecommendationLocal extends AnyWordSpec {
  private val schemas = new McpSchemas()
  private val instructions = new ChildInstructions()

  "Tier recommendations in child contracts (Behavioral Active Blackbox Group)" should {
    "name the three tiers in the planner and plan-reviewer instructions within the byte bound" in {
      List(DispatchWork.Planner(), DispatchWork.Reviewer(ReviewerMode.Plan)).foreach { work =>
        val text = instructions(work)
        assert(instructions.resource(work).endsWith(if (work.isInstanceOf[DispatchWork.Planner]) "plan.md" else "review-proposal.md"))
        assert(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16384)
        List("fast", "standard", "frontier").foreach(tier => assert(text.contains(tier), s"$work does not name $tier"))
        assert(text.contains("recommendation"))
      }
    }

    "expose the Task recommendation in the planner result schema and translate it for Codex" in {
      val schema = schemas.childReport(DispatchWork.Planner())
      val defs = schema.hcursor.downField("$defs")
      val task = defs.downField("cq_api_Content_Task").downField("properties")
      assert(task.downField("recommendation").succeeded)
      assert(defs.downField("cq_api_TierRecommendation").downField("properties").keys.map(_.toSet) == Some(Set("tier", "reason")))
      assert(defs.downField("cq_api_ModelTier").get[List[String]]("enum").toOption.map(_.toSet) == Some(ModelTier.all.map(_.toString).toSet))
      val native: Json = CodexSchema.result(schema)
      assert(native.hcursor.downField("$defs").downField("cq_api_TierRecommendation").downField("properties").keys.map(_.toSet) == Some(Set("tier", "reason")))
      assert(native.hcursor.downField("$defs").downField("cq_api_Content_Task").downField("properties").downField("recommendation").succeeded)
    }
  }
}
