package cq.host

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.Using

/**
 * The retained copy of a native output stream. The guardian keeps the whole stream on disk; what is published is the stream itself
 * when it fits `bound`, otherwise its head and tail, cut at line boundaries where there are any, around one marker line
 * `{"type":"cq.truncated","totalBytes":N,"omittedBytes":M}`. The copy never exceeds `bound` unless the marker alone does.
 */
object NativeTranscript {
  val MarkerType = "cq.truncated"
  /** Marker with both counts at their widest, and the line break that separates it from an unterminated head. */
  private val MarkerReserve = marker(Long.MaxValue, Long.MaxValue).length + 1
  private val LineFeed = '\n'.toByte

  def marker(total: Long, omitted: Long): Array[Byte] = s"""{"type":"$MarkerType","totalBytes":$total,"omittedBytes":$omitted}\n""".getBytes(UTF_8)

  private def read(channel: FileChannel, position: Long, length: Int): Array[Byte] = {
    val buffer = ByteBuffer.allocate(length)
    var offset = position
    while (buffer.hasRemaining) {
      val count = channel.read(buffer, offset)
      require(count > 0, "Native output changed while its transcript was retained")
      offset += count
    }
    buffer.array()
  }

  def retained(path: Path, bound: Int): Array[Byte] = {
    require(bound > 0, "Retained output bound must be positive")
    if (!Files.exists(path)) Array.emptyByteArray
    else {
      require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Native output must be a regular file")
      Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
        val total = channel.size()
        if (total <= bound) read(channel, 0, total.toInt)
        else {
          val budget = math.max(0, bound - MarkerReserve)
          val first = read(channel, 0, budget / 2)
          val last = read(channel, total - (budget - budget / 2), budget - budget / 2)
          val head = first.take(first.lastIndexOf(LineFeed) match { case -1 => first.length; case index => index + 1 })
          val tail = last.drop(last.indexOf(LineFeed) match { case -1 => 0; case index if index == last.length - 1 => 0; case index => index + 1 })
          val separator = if (head.isEmpty || head.last == LineFeed) Array.emptyByteArray else Array(LineFeed)
          head ++ separator ++ marker(total, total - head.length - tail.length) ++ tail
        }
      }
    }
  }

  /** The complete stream for line-by-line readers; an absent file reads as an empty stream. */
  def stream(path: Path): InputStream =
    if (Files.exists(path)) { require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Native output must be a regular file"); Files.newInputStream(path) }
    else new ByteArrayInputStream(Array.emptyByteArray)
}
