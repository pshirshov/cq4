package cq.host

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.Using

object PiAssets {
  val Resource = "cq/pi-attached.mjs"
  val ExtensionPath: Path = Path.of(".pi/extensions/cq-host.js")
  private val MaxExtensionBytes = 1024 * 1024

  def extension: CommandAsset = Using.resource(Option(getClass.getResourceAsStream("/" + Resource))
    .getOrElse(throw new IllegalStateException("Installed Pi attached extension is missing"))) { stream =>
    val bytes = stream.readNBytes(MaxExtensionBytes + 1)
    require(bytes.length <= MaxExtensionBytes, "Installed Pi extension exceeds its byte bound")
    CommandAsset(ExtensionPath, UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString)
  }
}
