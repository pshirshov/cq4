package cq.host

import cq.api.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

object SessionDelivery {
  private val MaxChildren = 32
  def flush(directory: Path, run: SupervisorRun, api: ServerApi): Int = {
    val governing = new DeliveryQueue(directory.resolve("delivery")).flush(api)
    val root = directory.resolve("children")
    if (!Files.exists(root)) governing
    else {
      require(!Files.isSymbolicLink(root), "Child delivery root cannot be a symbolic link")
      val children = Using.resource(Files.list(root))(_.iterator().asScala.take(MaxChildren + 1).toList)
      require(children.size <= MaxChildren, "Child delivery inventory exceeds its bound")
      governing + children.sortBy(_.getFileName.toString).map { child =>
        require(Files.isDirectory(child) && !Files.isSymbolicLink(child), "Child delivery path must be a directory")
        val ticket = HostFiles.read(child.resolve("ticket.json"), DispatchTicket_JsonCodec, 65536)
        require(child.getFileName.toString == ticket.attempt.id.value.toString && ticket.attempt.session == run.attempt.session &&
          ticket.attempt.parent.contains(run.attempt.id) && ticket.assignment.project == run.project.project,
          "Child delivery ticket has another governing owner")
        require(Files.isDirectory(child.resolve("delivery")), "Child has no spooled publication; interruption reconciliation is required")
        new DeliveryQueue(child.resolve("delivery")).flush(api)
      }.sum
    }
  }
}
