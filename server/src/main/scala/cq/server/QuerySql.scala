package cq.server

import cq.api.*
import cq.core.LedgerPolicy
import java.sql.PreparedStatement
import java.util.UUID

private[server] final case class QuerySql private (predicate: String, private val parameters: List[QuerySql.Parameter]) {
  def bind(statement: PreparedStatement, offset: Int): Int = {
    parameters.zipWithIndex.foreach { case (value, index) => value match {
      case QuerySql.Parameter.Text(text) => statement.setString(offset + index, text)
      case QuerySql.Parameter.Number(number) => statement.setLong(offset + index, number)
      case QuerySql.Parameter.Project(id) => statement.setObject(offset + index, id)
    }}
    offset + parameters.size
  }
}

private[server] object QuerySql {
  private enum Parameter {
    case Text(value: String)
    case Number(value: Long)
    case Project(value: UUID)
  }

  /** Rows of the active claim (`ClaimPolicy.active` at the bound time) covering the aliased `cq_items` row `i`; binds one bigint, the time. */
  val activeClaim: String = "FROM cq_claim_members m JOIN cq_claims c ON c.project_id = m.project_id AND c.claim_id = m.claim_id " +
    "WHERE m.project_id = i.project_id AND m.ledger = i.ledger AND m.number = i.number AND NOT c.released AND c.expires_at > ? " +
    "AND jsonb_array_length(c.body->'members') = (SELECT count(*) FROM cq_claim_members o WHERE o.project_id = c.project_id AND o.claim_id = c.claim_id)"

  def compile(query: QueryExpression, scope: ProjectId, now: Long): QuerySql = {
    val parameters = List.newBuilder[Parameter]
    def text(value: String): String = { parameters += Parameter.Text(value); "?" }
    def number(value: Long): String = { parameters += Parameter.Number(value); "?" }
    def reference(relation: Relation, item: QueryItem, reverse: Boolean): String = {
      val (source, target) = if (reverse) ("target", "source") else ("source", "target")
      parameters += Parameter.Project(scope.value)
      s"SELECT e.${source}_ledger, e.${source}_number FROM cq_edges e WHERE e.project_id = ? " +
        s"AND e.relation = ${text(relation.toString)} AND e.${target}_ledger = ${text(item.ledger.toString)} AND e.${target}_number = ${number(item.number)}"
    }
    def expression(value: QueryExpression): String = value match {
      case QueryExpression.All() => "TRUE"
      case QueryExpression.Text(words, phrase) =>
        val containment = s"i.search_words @> ARRAY[${words.map(text).mkString(",")}]::text[]"
        if (phrase) s"($containment AND strpos(i.search_text, ${text(words.mkString(" ", " ", " "))}) > 0)" else containment
      case QueryExpression.Id(item) => s"(i.ledger = ${text(item.ledger.toString)} AND i.number = ${number(item.number)})"
      case QueryExpression.LedgerIs(ledger) => s"i.ledger = ${text(ledger.toString)}"
      case QueryExpression.Status(status) => s"i.status = ${text(status)}"
      case QueryExpression.Tag(tag) => s"(i.summary->'labels') @> ${text(io.circe.Json.arr(io.circe.Json.fromString(tag)).noSpaces)}::jsonb"
      case QueryExpression.Project(id) => parameters += Parameter.Project(id.value); "i.project_id = ?"
      case QueryExpression.Archive(ArchiveFilter.Active) => "NOT i.archived"
      case QueryExpression.Archive(ArchiveFilter.Archived) => "i.archived"
      case QueryExpression.Archive(ArchiveFilter.All) => "TRUE"
      case QueryExpression.Reference(relation, item) =>
        s"(i.ledger, i.number) IN (${reference(relation, item, false)} UNION ${reference(LedgerPolicy.inverse(relation), item, true)})"
      case QueryExpression.Working() => parameters += Parameter.Number(now); s"EXISTS (SELECT 1 $activeClaim)"
      case QueryExpression.Not(inner) => s"NOT (${expression(inner)})"
      case QueryExpression.And(left, right) => s"(${expression(left)} AND ${expression(right)})"
      case QueryExpression.Or(left, right) => s"(${expression(left)} OR ${expression(right)})"
    }
    val predicate = expression(query)
    QuerySql(predicate, parameters.result())
  }
}
