package cq.server

import cq.api.*
import cq.core.DomainFailure
import fs2.Stream
import org.http4s.Response
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import zio.{Duration, Queue, Ref, Task, ZIO}
import zio.interop.catz.*
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.UUID

private final case class Subscription(id: RequestId, project: ProjectId, after: ChangeCursor)
private final case class UsageSubscription(id: RequestId, project: ProjectId, cursor: Option[Long])
private final case class Heartbeat(current: Option[String], previous: Option[String], lastPong: Long, lastPing: Long)

final class LiveSession(application: Application, authorization: Authorization, clock: Clock) {
  private val MaxFrameBytes = 2 * 1024 * 1024
  private val HeartbeatMillis = 10000L
  private val TimeoutMillis = 30000L
  private val PollMillis = 500L
  private val QueueCapacity = 64

  def open(builder: WebSocketBuilder2[Task], authority: Authority): Task[Response[Task]] = for {
    outgoing <- Queue.bounded[WebSocketFrame](QueueCapacity)
    watch <- Ref.Synchronized.make(Option.empty[Subscription])
    usageWatch <- Ref.Synchronized.make(Option.empty[UsageSubscription])
    heartbeat <- Ref.make(Heartbeat(None, None, clock.millis(), 0L))
    stopped <- Ref.make(false)
    send = (frame: ServerFrame) => outgoing.offer(WebSocketFrame.Text(Wire.encode(ServerFrame_JsonCodec, frame))).unit
    observeUsage = (subscription: UsageSubscription) => application.usageCursor(authority, subscription.project).flatMap { cursor =>
      val notify = if (subscription.cursor.contains(cursor)) ZIO.unit else send(ServerFrame.UsageCursor(subscription.id, subscription.project, cursor))
      notify.as(Option(subscription.copy(cursor = Some(cursor))))
    }.catchSome { case DomainFailure(fault) => send(ServerFrame.Resync(subscription.id, subscription.project, fault)).as(None) }
    close = (code: Int, reason: String) => stopped.getAndSet(true).flatMap { already =>
      if (already) ZIO.unit else outgoing.offer(WebSocketFrame.Close(code, reason).fold(throw _, identity)).unit
    }
    poll = for {
      _ <- ZIO.attempt(authorization.check(authority))
      now <- ZIO.succeed(clock.millis())
      beat <- heartbeat.get
      _ <- if (now - beat.lastPong > TimeoutMillis) ZIO.yieldNow *> heartbeat.get.flatMap { latest =>
        if (clock.millis() - latest.lastPong > TimeoutMillis) close(1001, "Heartbeat expired") else ZIO.unit
      } else if (now - beat.lastPing >= HeartbeatMillis) {
        val nonce = UUID.randomUUID().toString
        heartbeat.update(b => b.copy(previous = b.current, current = Some(nonce), lastPing = now)) *> send(ServerFrame.Ping(nonce))
      } else ZIO.unit
      _ <- watch.updateZIO {
        case None => ZIO.none
        case Some(subscription) => application.execute(authority,
          Command.Read(ReadInput(subscription.project, ReadSelection.Changes(subscription.after, 100)))).flatMap {
            case Result.Changes(page) =>
              send(ServerFrame.Changes(subscription.id, subscription.project, page)).as(Some(subscription.copy(after = page.cursor)))
            case Result.Failed(fault) => send(ServerFrame.Resync(subscription.id, subscription.project, fault)).as(None)
            case _ => ZIO.dieMessage("Changes command returned an unrelated result")
          }
      }
      _ <- usageWatch.updateZIO {
        case None => ZIO.none
        case Some(subscription) => observeUsage(subscription)
      }
    } yield ()
    fiber <- (stopped.get.flatMap { done => if (done) ZIO.unit else poll } *> ZIO.sleep(Duration.fromMillis(PollMillis)))
      .forever.catchAll {
        case _: DomainFailure => close(1008, "Credential expired or denied")
        case failure => ZIO.logError(s"WebSocket service failed: ${failure.getClass.getName}") *> close(1011, "Server failure")
      }.forkDaemon
    receive = (frame: WebSocketFrame) => stopped.get.flatMap { done =>
      if (done) ZIO.unit else frame match {
        case WebSocketFrame.Text(text, _) if text.getBytes(UTF_8).length <= MaxFrameBytes =>
          ZIO.attempt(Wire.decode(ClientFrame_JsonCodec, text)).foldZIO(_ => close(1007, "Invalid CQ frame"), {
            case ClientFrame.Ping(nonce) if nonce.length <= 100 => send(ServerFrame.Pong(nonce))
            case ClientFrame.Pong(nonce) => heartbeat.update { beat =>
              if (beat.current.contains(nonce) || beat.previous.contains(nonce)) beat.copy(lastPong = clock.millis()) else beat
            }
            case ClientFrame.Call(id, command) => application.execute(authority, command).flatMap(result => send(ServerFrame.Reply(id, result)))
            case ClientFrame.Subscribe(id, project, after) => watch.updateZIO { _ =>
              application.execute(authority, Command.Read(ReadInput(project, ReadSelection.Changes(after, 100)))).flatMap {
                case result @ Result.Changes(page) => send(ServerFrame.Reply(id, result)).as(Some(Subscription(id, project, page.cursor)))
                case result => send(ServerFrame.Reply(id, result)).as(None)
              }
            }
            case ClientFrame.WatchUsage(id, project) => usageWatch.updateZIO(_ => observeUsage(UsageSubscription(id, project, None)))
            case _ => close(1007, "Invalid CQ frame")
          })
        case _: WebSocketFrame.Text => close(1009, "Frame exceeds 2 MiB")
        case WebSocketFrame.Ping(data) => outgoing.offer(WebSocketFrame.Pong(data)).unit
        case _: WebSocketFrame.Pong => ZIO.unit
        case _: WebSocketFrame.Close => stopped.set(true)
        case _ => close(1003, "Expected a text frame")
      }
    }
    response <- builder.withOnClose(stopped.set(true) *> fiber.interrupt.unit *> outgoing.shutdown)
      .build(Stream.repeatEval(outgoing.take), _.evalMap(receive).drain)
      .onError(_ => stopped.set(true) *> fiber.interrupt.unit *> outgoing.shutdown)
  } yield response
}
