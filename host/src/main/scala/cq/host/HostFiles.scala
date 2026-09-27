package cq.host

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermissions
import scala.util.Using

object HostFiles {
  def bytes(path: Path, maximum: Int): Array[Byte] = {
    require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Host record must be a regular file")
    val value = Using.resource(Files.newInputStream(path))(_.readNBytes(maximum + 1))
    require(value.length <= maximum, "Host record exceeds its byte bound")
    value
  }
  def text(path: Path, maximum: Int): String = UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes(path, maximum))).toString
  def read[A](path: Path, codec: BaboonJsonCodec[A], maximum: Int): A =
    io.circe.parser.parse(text(path, maximum)).flatMap(codec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
  def encode[A](codec: BaboonJsonCodec[A], value: A): String = codec.encode(BaboonCodecContext.Default, value).noSpaces
  def directory(path: Path): Unit = {
    require(path.isAbsolute && path.normalize() == path, "Host directory must be absolute and normalized")
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    require(!Files.isSymbolicLink(path) && Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rwx------"), "Host directory must be private")
  }
  def immutable(path: Path, value: String, maximum: Int): Unit = {
    val encoded = UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value))
    require(encoded.remaining() <= maximum, "Host record exceeds its byte bound")
    val content = new Array[Byte](encoded.remaining())
    encoded.get(content)
    if (Files.exists(path)) {
      require(java.util.Arrays.equals(bytes(path, maximum), content), "Immutable host record identity changed")
    } else {
      val temporary = Files.createTempFile(path.getParent, ".upload-", ".pending",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      try {
        Files.write(temporary, content)
        Using.resource(FileChannel.open(temporary, StandardOpenOption.WRITE))(_.force(true))
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
        Using.resource(FileChannel.open(path.getParent, StandardOpenOption.READ))(_.force(true))
      } finally Files.deleteIfExists(temporary)
    }
  }
}
