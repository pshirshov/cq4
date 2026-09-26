package cq.core

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.Fault
import java.nio.charset.StandardCharsets.UTF_8

final case class ReadPage[A](entries: List[A], hasMore: Boolean)

object ReadPage {
  val MaxBytes = 512 * 1024
  private val EnvelopeBytes = 1024

  def select[A](source: Iterator[A], limit: Int, codec: BaboonJsonCodec[A]): ReadPage[A] = {
    require(limit > 0 && limit <= LedgerPolicy.MaxPage, "Repository page limit invariant")
    val entries = List.newBuilder[A]
    var bytes = EnvelopeBytes
    var count = 0
    var more = false
    while (!more && source.hasNext) {
      val value = source.next()
      val size = codec.encode(BaboonCodecContext.Default, value).noSpaces.getBytes(UTF_8).length + 1
      if (size > MaxBytes - EnvelopeBytes) throw DomainFailure(Fault.Limit("A record exceeds the read-page byte budget"))
      if (count == limit || bytes + size > MaxBytes) more = true
      else { entries += value; bytes += size; count += 1 }
    }
    ReadPage(entries.result(), more)
  }
}
