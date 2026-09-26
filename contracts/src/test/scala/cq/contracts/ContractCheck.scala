package cq.contracts

import baboon.runtime.shared.{BaboonCodecContext, LEDataInputStream, LEDataOutputStream}
import cq.api.*
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Path}
import java.util.UUID
import io.circe.parser.parse

object ContractCheck {
  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Expected export|verify and a fixture directory")
    val directory = Path.of(args(1))
    Files.createDirectories(directory)
    val context = BaboonCodecContext.Default
    val revision = Revision(9007199254740993L)
    val probe = Probe(ProjectId(UUID.fromString("00000000-0000-0000-0000-000000000001")), revision, "round trip λ")
    val conflict = ApiError.Conflict(revision, Revision(Long.MaxValue))
    val binary = new ByteArrayOutputStream()
    Probe_UEBACodec.encode(context, new LEDataOutputStream(binary), probe)
    assert(Probe_UEBACodec.decode(context, new LEDataInputStream(new ByteArrayInputStream(binary.toByteArray))) == Right(probe))
    val old = cq.fixture.v0_1_0.Note(revision.value, "historical")
    val conversions = new cq.fixture.BaboonConversions(new cq.fixture.RequiredConversions {})
    val evolved = cq.fixture.Convert__Note__From__0_1_0.doConvert(None, conversions, old)
    assert(evolved == cq.fixture.Note(revision.value, "historical", None))
    assert(RevisionCodec.parseRepr(revision.toString) == Right(revision))
    assert(RevisionCodec.parseRepr("Revision:0.1.0#value:9223372036854775808").isLeft)
    args(0) match {
      case "export" =>
        Files.writeString(directory.resolve("scala-probe.json"), Probe_JsonCodec.encode(context, probe).noSpaces)
        Files.writeString(directory.resolve("scala-error.json"), ApiError_JsonCodec.encode(context, conflict).noSpaces)
      case "verify" =>
        val returned = parse(Files.readString(directory.resolve("typescript-probe.json"))).flatMap(Probe_JsonCodec.decode(context, _))
        assert(returned == Right(probe))
        val error = parse(Files.readString(directory.resolve("typescript-error.json"))).flatMap(ApiError_JsonCodec.decode(context, _))
        assert(error == Right(conflict))
      case _ => throw new IllegalArgumentException("Expected export|verify")
    }
    println(s"contracts ${args(0)} passed: JSON, UEBA, 64-bit values, typed error, evolution")
  }
}
