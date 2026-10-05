package cq.server

import com.comcast.ip4s.{Host, Port}
import izumi.functional.bio.Exit
import izumi.functional.bio.UnsafeRun2
import izumi.functional.bio.UnsafeRun2.FailureHandler
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.{ConcurrentLinkedQueue, LinkedBlockingQueue, TimeUnit}
import org.http4s.{HttpApp, Response, Status}
import org.http4s.ember.core.EmberException
import org.http4s.ember.server.EmberServerBuilder
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import zio.{Task, ZIO}
import zio.interop.catz.*

final class ConnectionReportsLocal extends AnyWordSpec {
  private final class Unrelated extends RuntimeException("unrelated fiber failure")
  private val ReportWaitSeconds = 60L

  /** Blocks until the report under test has returned from a failure of every `expected` kind, in whichever order the supervisor delivers them. */
  private def processed(handled: LinkedBlockingQueue[Throwable], expected: Map[String, Throwable => Boolean]): Unit = {
    val missing = Iterator.continually(handled.poll(ReportWaitSeconds, TimeUnit.SECONDS)).takeWhile(_ != null)
      .scanLeft(expected)((awaited, failure) => awaited.filterNot(_._2(failure))).find(_.isEmpty)
    assert(missing.nonEmpty, s"The fiber supervisor did not report every one of: ${expected.keys.mkString(", ")}")
  }

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
      // Every failure the supervisor delivers, noted only after the report under test has returned from it: absence from `reported` is then a decision of that report.
      val handled = new LinkedBlockingQueue[Throwable]()
      val report = ConnectionReports.reporting(FailureHandler.Custom { exit => reported.add(exit); () }) match {
        case FailureHandler.Custom(report) => report
        case other => fail(s"The report under test is not a custom one: $other")
      }
      val runner = UnsafeRun2.createZIO[Any](handler = FailureHandler.Custom { exit =>
        report(exit)
        exit match { case Exit.Error(failure: Throwable, _) => handled.put(failure); case _ => () }
      })
      val unrelated = new Unrelated
      val answer = runner.unsafeRun(EmberServerBuilder.default[Task].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(0).get)
        .withHttpApp(HttpApp.pure[Task](Response[Task](Status.Ok))).build.use { server =>
          for {
            probe <- ZIO.attemptBlocking(exchange(server.address, ""))
            answer <- ZIO.attemptBlocking(exchange(server.address, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"))
            _ <- ZIO.fail(unrelated).fork.flatMap(_.await)
          } yield (probe, answer)
        })
      assert(answer._1.isEmpty)
      assert(answer._2.startsWith("HTTP/1.1 200 "))
      processed(handled, Map("the connection closed before a request" -> (_.isInstanceOf[EmberException.EmptyStream]), "the unrelated failure" -> (_ eq unrelated)))
      assert(reported.asScala.toList.collect { case Exit.Error(failure, _) => failure } == List(unrelated))
    }
  }
}
