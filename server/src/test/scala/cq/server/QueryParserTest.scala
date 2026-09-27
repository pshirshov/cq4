package cq.server

import cq.api.*
import cq.core.{QueryCatalog, QueryParser, SearchText}
import org.scalatest.wordspec.AnyWordSpec

final class QueryParserLocal extends AnyWordSpec {
  private val parser = new QueryParser
  private def active(value: QueryExpression): QueryExpression = QueryExpression.And(QueryExpression.Archive(ArchiveFilter.Active), value)
  private def text(value: String): QueryExpression = QueryExpression.Text(List(value), false)
  private def parsed(value: String): QueryExpression = parser.parse(value).fold(error => fail(error.toString), identity)

  "Shared query language (Behavioral Active Blackbox Atomic)" should {
    "apply explicit Boolean precedence, implicit conjunction, grouping and unary negation" in {
      assert(parsed("alpha OR beta AND NOT gamma") == active(QueryExpression.Or(text("alpha"), QueryExpression.And(text("beta"), QueryExpression.Not(text("gamma"))))))
      assert(parsed("(alpha OR beta) -gamma") == active(QueryExpression.And(QueryExpression.Or(text("alpha"), text("beta")), QueryExpression.Not(text("gamma")))))
      assert(parsed("alpha beta") == parsed("alpha AND beta"))
      assert(parsed("and or not") == active(QueryExpression.And(QueryExpression.And(text("and"), text("or")), text("not"))))
      assert(parsed("   ") == active(QueryExpression.All()))
    }

    "parse every supported ledger and relation with canonical IDs and preserve exact tags" in {
      QueryCatalog.ledgers.foreach { case (name, ledger) => assert(parsed("ledger:" + name) == active(QueryExpression.LedgerIs(ledger))) }
      QueryCatalog.relations.foreach { case (name, relation) => assert(parsed(name + ":T42") == active(QueryExpression.Reference(relation, QueryItem(Ledger.Tasks, 42)))) }
      assert(parsed("t9223372036854775807") == active(QueryExpression.Id(QueryItem(Ledger.Tasks, Long.MaxValue))))
      assert(parsed("id:RS12") == active(QueryExpression.Id(QueryItem(Ledger.Researches, 12))))
      assert(parsed("status:DoNe") == active(QueryExpression.Status("done")))
      assert(parsed("tag:\"Needs review: λ\"") == active(QueryExpression.Tag("Needs review: λ")))
      assert(parsed("project:00000000-0000-0000-0000-000000000001") == active(QueryExpression.Project(ProjectId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")))))
      assert(parsed("version2") == active(text("version2")))
      List("T0", "T01", "T9223372036854775808", "id:unknown1", "ledger:unknown", "status:unknown", "owner:alice", "project:1-1-1-1-1").foreach { value =>
        assert(parser.parse(value).isLeft, value)
      }
    }

    "disable the default archive predicate whenever archive membership is explicit" in {
      assert(parsed("archived:all") == QueryExpression.Archive(ArchiveFilter.All))
      assert(parsed("NOT archived:true") == QueryExpression.Not(QueryExpression.Archive(ArchiveFilter.Archived)))
      assert(parsed("alpha OR archived:true") == QueryExpression.Or(text("alpha"), QueryExpression.Archive(ArchiveFilter.Archived)))
      assert(parsed("archived:false alpha") == active(text("alpha")))
      assert(parser.parse("archived:yes").isLeft)
    }

    "decode quoted values and return bounded diagnostics with browser-compatible source spans" in {
      assert(parsed("\"T42\"") == active(QueryExpression.Text(List("t42"), true)))
      assert(parsed("tag:\"a\\\"b\\\\c\"") == active(QueryExpression.Tag("a\"b\\c")))
      assert(parsed("\"Ｎｅｗ café\"") == active(QueryExpression.Text(List("new", "café"), true)))
      val source = "tag:\"😀\" AND bogus:value"
      val error = parser.parse(source).swap.toOption.get
      assert(error.span == QuerySpan(13, 18) && source.substring(error.span.start, error.span.end) == "bogus")
      val end = parser.parse("alpha AND").swap.toOption.get.span
      assert(end == QuerySpan(9, 9))
      List("alpha OR", "(alpha", "()", "alpha )", "tag:", "\"unfinished", "\"bad\\q\"", "\"\\u0000\"", "\"\\ud800\"", "\\bad", "\"\"", "🙂").foreach { source =>
        val diagnostic = parser.parse(source).swap.toOption.get
        assert(diagnostic.span.start >= 0 && diagnostic.span.end <= source.length && diagnostic.span.start <= diagnostic.span.end, source)
      }
    }

    "bound source size, recursive nesting, AST size, phrase size and searchable word bytes" in {
      List("x" * 4097, "(" * 17 + "x" + ")" * 17, "NOT " * 17 + "x", "x " * 100,
        "\"" + "x " * 65 + "\"", "x" * 513, "界" * 171).foreach(value => assert(parser.parse(value).isLeft, value.take(60)))
      assert(parser.parse("(" * 16 + "x" + ")" * 16).isRight)
      assert(parser.parse("x" * 512).isRight)
    }

    "normalize full text consistently without dropping phrase barriers or late repeated words" in {
      val document = SearchText.document("Ｎｅｗ café", "alpha,beta alpha " + "x" * 513 + " beta " + "repeat " * 20000 + "late phrase")
      assert(SearchText.contains(document, List("new", "café"), true))
      assert(SearchText.contains(document, List("alpha", "beta"), true))
      assert(SearchText.contains(document, List("late", "phrase"), true))
      assert(!SearchText.contains(SearchText.document("", "alpha " + "x" * 513 + " beta"), List("alpha", "beta"), true))
      assert(!SearchText.contains(document, List("pha"), false))
      assert(SearchText.words("re-enter a1 3.14 e\u0301") == List("re", "enter", "a1", "3", "14", "é"))
    }
  }
}
