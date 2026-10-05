package cq.server

import com.comcast.ip4s.{Host, Port}
import izumi.functional.bio.Exit
import izumi.functional.bio.UnsafeRun2
import izumi.functional.bio.UnsafeRun2.FailureHandler
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import org.http4s.{HttpApp, Response, Status}
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO}
import zio.interop.catz.*

final class ConnectionReportsLocal extends AnyWordSpec {
  private final class Unrelated extends RuntimeException("unrelated fiber failure")

  /** What the server sends on a connection until it closes it; the client's side is closed for writing after `sent`. */
  private def exchange(address: InetSocketAddress, sent: String): String = {
    val socket = new Socket(address.getAddress, address.getPort)
    try {
      socket.getOutputStream.write(sent.getBytes(UTF_8))
      socket.shutdownOutput()
      new String(socket.getInputStream.readAllBytes(), UTF_8)
    } finally socket.close()
  }

  "the fiber report of a server" should {
    "omit a connection closed before a request and keep every other failure" in {
      val reported = new ConcurrentLinkedQueue[Exit.Failure[Any]]()
      val runner = UnsafeRun2.createZIO[Any](handler = ConnectionReports.reporting(FailureHandler.Custom { exit => reported.add(exit); () }))
      val unrelated = new Unrelated
      val answer = runner.unsafeRun(EmberServerBuilder.default[Task].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
        .withHttpApp(HttpApp.pure[Task](Response[Task](Status.Ok))).build.use { server =>
          for {
            // The server closes its side only after the request reader has ended, so the report of that fiber precedes the end of this read.
            probe <- ZIO.attemptBlocking(exchange(server.address, ""))
            answer <- ZIO.attemptBlocking(exchange(server.address, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
            _ <- ZIO.fail(unrelated).fork.flatMap(_.await)
          } yield (probe, answer)
        })
      assert(answer._1.isEmpty)
      assert(answer._2.startsWith("HTTP/1.1 200 "))
      assert(reported.asScala.toList.collect { case Exit.Error(failure, _) => failure } == List(unrelated))
    }
  }
}
