package cq.core

import cq.api.*
import java.util.Locale

final class QueryCompleter(parser: QueryParser) {
  private val MaxSuggestions = 50
  private def matches(value: String, prefix: String): Boolean = value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))

  def complete(tx: LedgerTransaction, source: String, cursor: Int, limit: Int): QueryAnalysis = {
    LedgerPolicy.invalid(limit > 0 && limit <= MaxSuggestions, s"Completion size must be 1–$MaxSuggestions")
    LedgerPolicy.invalid(parser.validCursor(source, cursor), "Cursor must be a UTF-16 character boundary within the query")
    val diagnostic = parser.parse(source).left.toOption
    def values(span: QuerySpan, prefix: String, candidates: Iterable[String]): List[QuerySuggestion] =
      candidates.toList.filter(matches(_, prefix)).sorted.map(value => QuerySuggestion(QuerySuggestionKind.Value, span, value, value))
    def fields(span: QuerySpan, prefix: String, colon: Boolean): List[QuerySuggestion] =
      QueryCatalog.fields.toList.filter(matches(_, prefix)).sorted.map { field =>
        QuerySuggestion(if (QueryCatalog.relations.contains(field)) QuerySuggestionKind.Relation else QuerySuggestionKind.Field,
          span, field + (if (colon) ":" else ""), field)
      }
    def items(span: QuerySpan, prefix: String, archive: ArchiveFilter): List[QuerySuggestion] = {
      val folded = prefix.toUpperCase(Locale.ROOT)
      if (!folded.matches("[A-Z]*[0-9]*")) Nil
      else tx.completeItems(SearchPrefix(folded), archive, limit + 1).map { item =>
        val id = LedgerPolicy.prefix(item.id.ledger) + item.id.number
        QuerySuggestion(QuerySuggestionKind.Item, span, id, s"$id · ${item.title}" + (if (item.archived) " · archived" else ""))
      }
    }
    val candidates = parser.completion(source, cursor).toList.flatMap {
      case QuerySite.Field(span, prefix) => fields(span, prefix, false)
      case QuerySite.Term(span, prefix, afterExpression, openGroup) =>
        val operators = List("NOT", "(") ++ (if (afterExpression) List("AND", "OR") ++ (if (openGroup) List(")") else Nil) else Nil)
        fields(span, prefix, true) ++ operators.filter(matches(_, prefix)).map(value =>
          QuerySuggestion(QuerySuggestionKind.Operator, span, value + " ", value))
      case QuerySite.Value(span, prefix, field) => field match {
        case "ledger" => values(span, prefix, QueryCatalog.ledgers.keys)
        case "status" => values(span, prefix, QueryCatalog.statuses)
        case "archived" => values(span, prefix, List("all", "false", "true"))
        case "project" => values(span, prefix, List(tx.project.id.value.toString))
        case "tag" => tx.completeLabels(SearchPrefix(prefix), limit + 1).map { label =>
          QuerySuggestion(QuerySuggestionKind.Value, span, io.circe.Json.fromString(label).noSpaces, label)
        }
        case "id" => archiveScope(source, span).toList.flatMap(items(span, prefix, _))
        case relation if QueryCatalog.relations.contains(relation) => items(span, prefix, ArchiveFilter.All)
        case _ => Nil
      }
    }
    QueryAnalysis(diagnostic, candidates.take(limit), candidates.size > limit)
  }

  private def archiveScope(source: String, span: QuerySpan): Option[ArchiveFilter] = {
    // A valid placeholder lets the ordinary parser interpret archive scope on both sides of the cursor.
    // Unfinished text after the value falls back to the prefix alone, then to the language's default active scope.
    val placeholder = "T1"
    val prefix = source.take(span.start) + placeholder
    parser.completionExpression(prefix + source.drop(span.end)).orElse(parser.completionExpression(prefix)).fold(_ => Some(ArchiveFilter.Active), query => {
      def possible(value: QueryExpression, archived: Boolean): Set[Boolean] = value match {
        case QueryExpression.All() => Set(true)
        case QueryExpression.Archive(ArchiveFilter.All) => Set(true)
        case QueryExpression.Archive(ArchiveFilter.Active) => Set(!archived)
        case QueryExpression.Archive(ArchiveFilter.Archived) => Set(archived)
        case QueryExpression.Not(inner) => possible(inner, archived).map(!_)
        case QueryExpression.And(left, right) => for (l <- possible(left, archived); r <- possible(right, archived)) yield l && r
        case QueryExpression.Or(left, right) => for (l <- possible(left, archived); r <- possible(right, archived)) yield l || r
        case _ => Set(true, false)
      }
      (possible(query, false).contains(true), possible(query, true).contains(true)) match {
        case (true, true) => Some(ArchiveFilter.All)
        case (true, false) => Some(ArchiveFilter.Active)
        case (false, true) => Some(ArchiveFilter.Archived)
        case (false, false) => None
      }
    })
  }
}
