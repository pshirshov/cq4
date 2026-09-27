package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

final class ChildInstructions {
  private val MaxBytes = 16384
  def apply(work: DispatchWork): String = {
    val name = work match {
      case _: DispatchWork.Explorer => "explore"
      case _: DispatchWork.Planner => "plan"
      case DispatchWork.Worker(mode) => mode.toString.toLowerCase
      case DispatchWork.Reviewer(ReviewerMode.Candidate) => "review-candidate"
      case _: DispatchWork.Reviewer => "review-proposal"
    }
    Using.resource(Option(getClass.getResourceAsStream(s"/cq/prompts/$name.md"))
      .getOrElse(throw new IllegalStateException("Installed child prompt is missing"))) { stream =>
      val bytes = stream.readNBytes(MaxBytes + 1)
      require(bytes.length <= MaxBytes, "Installed child prompt exceeds its byte bound")
      UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
    }
  }
}
