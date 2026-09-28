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
    val watch = ClientFrame.Watch(RequestId(probe.project.value), LiveScope(true, Some(probe.project)))
    val usageCursor = ServerFrame.Updated(watch.id, LiveRevision(Some(CatalogueCursor(revision.value)), Some(ProjectCursors(probe.project, ChangeCursor(revision.value), revision.value))))
    val query = QueryExpression.And(QueryExpression.Archive(ArchiveFilter.Active), QueryExpression.Or(
      QueryExpression.Not(QueryExpression.Reference(Relation.BlockedBy, QueryItem(Ledger.Tasks, Long.MaxValue))),
      QueryExpression.Text(List("retry", "λ"), true)
    ))
    val binary = new ByteArrayOutputStream()
    Probe_UEBACodec.encode(context, new LEDataOutputStream(binary), probe)
    assert(Probe_UEBACodec.decode(context, new LEDataInputStream(new ByteArrayInputStream(binary.toByteArray))) == Right(probe))
    val queryBinary = new ByteArrayOutputStream()
    QueryExpression_UEBACodec.encode(context, new LEDataOutputStream(queryBinary), query)
    assert(QueryExpression_UEBACodec.decode(context, new LEDataInputStream(new ByteArrayInputStream(queryBinary.toByteArray))) == Right(query))
    assert(RevisionCodec.parseRepr(revision.toString) == Right(revision))
    assert(RevisionCodec.parseRepr(s"Revision:${Revision.baboonDomainVersion}#value:9223372036854775808").isLeft)
    args(0) match {
      case "export" =>
        Files.writeString(directory.resolve("scala-probe.json"), Probe_JsonCodec.encode(context, probe).noSpaces)
        Files.writeString(directory.resolve("scala-error.json"), ApiError_JsonCodec.encode(context, conflict).noSpaces)
        Files.writeString(directory.resolve("scala-query.json"), QueryExpression_JsonCodec.encode(context, query).noSpaces)
        Files.writeString(directory.resolve("scala-watch.json"), ClientFrame_JsonCodec.encode(context, watch).noSpaces)
        Files.writeString(directory.resolve("scala-usage-cursor.json"), ServerFrame_JsonCodec.encode(context, usageCursor).noSpaces)
      case "verify" =>
        val returned = parse(Files.readString(directory.resolve("typescript-probe.json"))).flatMap(Probe_JsonCodec.decode(context, _))
        assert(returned == Right(probe))
        val error = parse(Files.readString(directory.resolve("typescript-error.json"))).flatMap(ApiError_JsonCodec.decode(context, _))
        assert(error == Right(conflict))
        val returnedQuery = parse(Files.readString(directory.resolve("typescript-query.json"))).flatMap(QueryExpression_JsonCodec.decode(context, _))
        assert(returnedQuery == Right(query))
        assert(parse(Files.readString(directory.resolve("typescript-watch.json"))).flatMap(ClientFrame_JsonCodec.decode(context, _)) == Right(watch))
        assert(parse(Files.readString(directory.resolve("typescript-usage-cursor.json"))).flatMap(ServerFrame_JsonCodec.decode(context, _)) == Right(usageCursor))
      case _ => throw new IllegalArgumentException("Expected export|verify")
    }
    println(s"contracts ${args(0)} passed: JSON, UEBA, 64-bit values, typed error, recursive query")
  }
}
