package cq.host

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import scala.util.Using

final class ChildInstructions {
  private val MaxBytes = 16384
  /** Classpath path of the prompt resource a dispatched child of this mode is instructed with. */
  def resource(work: DispatchWork): String = {
    val name = work match {
      case _: DispatchWork.Explorer => "explore"
      case _: DispatchWork.Planner => "plan"
      case DispatchWork.Worker(mode) => mode.toString.toLowerCase
      case DispatchWork.Reviewer(ReviewerMode.Candidate) => "review-candidate"
      case _: DispatchWork.Reviewer => "review-proposal"
    }
    s"cq/prompts/$name.md"
  }

  def apply(work: DispatchWork): String = {
    Using.resource(Option(getClass.getResourceAsStream("/" + resource(work)))
      .getOrElse(throw new IllegalStateException("Installed child prompt is missing"))) { stream =>
      val bytes = stream.readNBytes(MaxBytes + 1)
      require(bytes.length <= MaxBytes, "Installed child prompt exceeds its byte bound")
      UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString
    }
  }
}
