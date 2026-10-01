package cq.core

import cq.api.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Locale, UUID}
import scala.util.Try

object QueryCatalog {
  val ledgers: Map[String, Ledger] = Ledger.all.map(value => value.toString.toLowerCase(Locale.ROOT) -> value).toMap
  val relations: Map[String, Relation] = Relation.all.map(value => value.toString.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT) -> value).toMap
  val statuses: Set[String] = (
    MilestoneStatus.all.map(_.toString) ++ IdeaStatus.all.map(_.toString) ++ DefectStatus.all.map(_.toString) ++
      GoalStatus.all.map(_.toString) ++ TaskStatus.all.map(_.toString) ++ ResearchStatus.all.map(_.toString) ++
      HypothesisStatus.all.map(_.toString) ++ QuestionStatus.all.map(_.toString) ++ DecisionStatus.all.map(_.toString) ++
      ReviewStatus.all.map(_.toString) ++ HandoffStatus.all.map(_.toString) ++ OperatorActionStatus.all.map(_.toString) ++
      MemoryStatus.all.map(_.toString) ++ UpstreamStatus.all.map(_.toString)
  ).map(_.toLowerCase(Locale.ROOT)).toSet
  val fields: Set[String] = Set("id", "ledger", "status", "tag", "project", "archived") ++ relations.keySet
}

enum QuerySite {
  case Field(span: QuerySpan, prefix: String)
  case Value(span: QuerySpan, prefix: String, field: String)
  case Term(span: QuerySpan, prefix: String, afterExpression: Boolean, openGroup: Boolean)
}

final class QueryParser {
  private val MaxCharacters = 4096
  private val MaxTokens = 512
  private val MaxNodes = 128
  private val MaxDepth = 16
  private val MaxWords = 64
  private val Identifier = "(?i)([a-z]+)([0-9]+)".r
  private def isItem(value: String): Boolean = value match {
    case Identifier(prefix, _) => Ledger.all.exists(ledger => LedgerPolicy.prefix(ledger).equalsIgnoreCase(prefix))
    case _ => false
  }
  private enum Kind { case Word, Quoted, Left, Right, Colon, Minus, End }
  private final case class Token(kind: Kind, value: String, span: QuerySpan)
  private final case class Invalid(diagnostic: QueryDiagnostic) extends RuntimeException(diagnostic.message)
  private def fail(span: QuerySpan, message: String): Nothing = throw Invalid(QueryDiagnostic(span, message))

  def parse(source: String): Either[QueryDiagnostic, QueryExpression] = parseExpression(source, false)

  def completionExpression(source: String): Either[QueryDiagnostic, QueryExpression] = parseExpression(source, true)

  private def parseExpression(source: String, allowOpenGroups: Boolean): Either[QueryDiagnostic, QueryExpression] = try {
    validateSource(source)
    val expression = new Parser(tokens(source, false), allowOpenGroups).parse()
    Right(if (hasArchive(expression)) expression else QueryExpression.And(QueryExpression.Archive(ArchiveFilter.Active), expression))
  } catch { case Invalid(diagnostic) => Left(diagnostic) }

  private def validateSource(source: String): Unit = {
    if (source.length > MaxCharacters) fail(QuerySpan(0, source.length), s"Query exceeds $MaxCharacters UTF-16 characters")
    if (!UTF_8.newEncoder().canEncode(source) || source.contains('\u0000')) fail(QuerySpan(0, source.length), "Query contains invalid Unicode or NUL")
  }

  def validCursor(source: String, cursor: Int): Boolean = cursor >= 0 && cursor <= source.length &&
    !(cursor > 0 && cursor < source.length && Character.isHighSurrogate(source(cursor - 1)) && Character.isLowSurrogate(source(cursor)))

  def completion(source: String, cursor: Int): Option[QuerySite] = {
    require(validCursor(source, cursor), "Cursor must be a UTF-16 character boundary within the query")
    try {
      validateSource(source)
      val before = tokens(source.take(cursor), true).dropRight(1)
      val current = before.lastOption.filter(token => token.span.end == cursor && Set(Kind.Word, Kind.Quoted).contains(token.kind))
      val previous = if (current.nonEmpty) before.dropRight(1) else before
      val field = if (previous.lastOption.exists(_.kind == Kind.Colon)) previous.dropRight(1).lastOption
        .filter(_.kind == Kind.Word).map(_.value.toLowerCase(Locale.ROOT)) else None
      val start = current.fold(cursor)(_.span.start)
      val prefix = current.fold("")(_.value)
      val valueStart = if (current.isEmpty && field.nonEmpty) {
        var index = cursor
        while (index < source.length && source(index).isWhitespace) index += 1
        index
      } else start
      val end = tokenEnd(source, valueStart)
      val span = QuerySpan(start, end)
      field match {
        case Some(name) => Some(QuerySite.Value(span, prefix, name))
        case None if current.exists(_.kind == Kind.Quoted) => None
        case None =>
          var next = end
          while (next < source.length && source(next).isWhitespace) next += 1
          if (next < source.length && source(next) == ':') Some(QuerySite.Field(span, prefix))
          else {
            val afterExpression = previous.lastOption.exists(token => token.kind == Kind.Right || token.kind == Kind.Quoted ||
              (token.kind == Kind.Word && !Set("AND", "OR", "NOT").contains(token.value.toUpperCase(java.util.Locale.ROOT))))
            val depth = previous.count(_.kind == Kind.Left) - previous.count(_.kind == Kind.Right)
            Some(QuerySite.Term(span, prefix, afterExpression, depth > 0))
          }
      }
    } catch { case _: Invalid => None }
  }

  private def tokenEnd(source: String, start: Int): Int = {
    var index = start
    if (index < source.length && source(index) == '"') {
      index += 1
      var closed = false
      while (index < source.length && !closed) {
        if (source(index) == '\\') index = math.min(index + 2, source.length)
        else { closed = source(index) == '"'; index += 1 }
      }
    } else while (index < source.length && !source(index).isWhitespace && !"():\"".contains(source(index))) index += 1
    index
  }

  private def quotedPrefix(literal: String): Option[String] = {
    val value = new StringBuilder
    var index = 1
    def prefix: Option[String] = {
      val text = value.result()
      val complete = if (text.nonEmpty && Character.isHighSurrogate(text.last)) text.dropRight(1) else text
      Option.when(UTF_8.newEncoder().canEncode(complete) && !complete.contains('\u0000'))(complete)
    }
    while (index < literal.length) {
      val character = literal(index)
      if (character == '"' || character < ' ') return None
      if (character != '\\') { value.append(character); index += 1 }
      else {
        if (index + 1 == literal.length) return prefix
        literal(index + 1) match {
          case 'u' =>
            val digits = literal.slice(index + 2, index + 6)
            if (!digits.forall(c => "0123456789abcdefABCDEF".contains(c))) return None
            if (digits.length < 4) return prefix
            value.append(Integer.parseInt(digits, 16).toChar); index += 6
          case escaped =>
            val decoded = escaped match {
              case '"' => '"'
              case '\\' => '\\'
              case '/' => '/'
              case 'b' => '\b'
              case 'f' => '\f'
              case 'n' => '\n'
              case 'r' => '\r'
              case 't' => '\t'
              case _ => return None
            }
            value.append(decoded); index += 2
        }
      }
    }
    prefix
  }

  private def hasArchive(value: QueryExpression): Boolean = value match {
    case _: QueryExpression.Archive => true
    case QueryExpression.And(left, right) => hasArchive(left) || hasArchive(right)
    case QueryExpression.Or(left, right) => hasArchive(left) || hasArchive(right)
    case QueryExpression.Not(inner) => hasArchive(inner)
    case _ => false
  }

  private def tokens(source: String, allowOpenQuote: Boolean): Vector[Token] = {
    val output = Vector.newBuilder[Token]
    var index = 0
    var count = 0
    while (index < source.length) {
      if (source(index).isWhitespace) index += 1
      else {
        val start = index
        val token = source(index) match {
          case '(' => index += 1; Token(Kind.Left, "(", QuerySpan(start, index))
          case ')' => index += 1; Token(Kind.Right, ")", QuerySpan(start, index))
          case ':' => index += 1; Token(Kind.Colon, ":", QuerySpan(start, index))
          case '-' => index += 1; Token(Kind.Minus, "-", QuerySpan(start, index))
          case '"' =>
            index = tokenEnd(source, start)
            val span = QuerySpan(start, index)
            val literal = source.substring(start, index)
            val decoded = io.circe.parser.parse(literal).flatMap(_.as[String])
            val value = decoded.toOption.orElse(if (allowOpenQuote) quotedPrefix(literal) else None)
              .getOrElse(fail(span, if (index == source.length && !literal.endsWith("\"")) "Unterminated quoted value" else "Quoted values use JSON string escaping"))
            if (!UTF_8.newEncoder().canEncode(value) || value.contains('\u0000')) fail(span, "Quoted value contains invalid Unicode or NUL")
            Token(Kind.Quoted, value, span)
          case _ =>
            index = tokenEnd(source, start)
            val value = source.substring(start, index)
            if (value.contains('\\')) fail(QuerySpan(start, index), "Quote values that require escaping")
            Token(Kind.Word, value, QuerySpan(start, index))
        }
        count += 1
        if (count > MaxTokens) fail(token.span, s"Query exceeds $MaxTokens tokens")
        output += token
      }
    }
    output += Token(Kind.End, "", QuerySpan(source.length, source.length))
    output.result()
  }

  private final class Parser(input: Vector[Token], allowOpenGroups: Boolean) {
    private var offset = 0
    private var nodes = 0
    private def current: Token = input(offset)
    private def take(): Token = { val result = current; offset += 1; result }
    private def keyword(value: String): Boolean = current.kind == Kind.Word && current.value.equalsIgnoreCase(value)
    private def node(value: QueryExpression, span: QuerySpan): QueryExpression = {
      nodes += 1
      if (nodes > MaxNodes) fail(span, s"Query exceeds $MaxNodes expression nodes")
      value
    }
    def parse(): QueryExpression = {
      val result = if (current.kind == Kind.End) QueryExpression.All() else disjunction(0)
      if (current.kind != Kind.End) fail(current.span, "Unexpected token after query expression")
      result
    }
    private def disjunction(depth: Int): QueryExpression = {
      var value = conjunction(depth)
      while (keyword("OR")) { val operator = take(); value = node(QueryExpression.Or(value, conjunction(depth)), operator.span) }
      value
    }
    private def conjunction(depth: Int): QueryExpression = {
      var value = unary(depth)
      while (!keyword("OR") && current.kind != Kind.End && current.kind != Kind.Right) {
        val span = current.span
        if (keyword("AND")) take()
        value = node(QueryExpression.And(value, unary(depth)), span)
      }
      value
    }
    private def unary(depth: Int): QueryExpression = {
      if (depth > MaxDepth) fail(current.span, s"Query nesting exceeds $MaxDepth levels")
      if (keyword("NOT") || current.kind == Kind.Minus) {
        val operator = take()
        node(QueryExpression.Not(unary(depth + 1)), operator.span)
      } else if (current.kind == Kind.Left) {
        take()
        val value = disjunction(depth + 1)
        if (current.kind == Kind.Right) take()
        else if (!allowOpenGroups || current.kind != Kind.End) fail(current.span, "Expected closing parenthesis")
        value
      } else {
        if (!Set(Kind.Word, Kind.Quoted).contains(current.kind) || keyword("AND") || keyword("OR")) fail(current.span, "Expected query term")
        val token = take()
        val value = if (token.kind == Kind.Word && current.kind == Kind.Colon) {
          take()
          if (!Set(Kind.Word, Kind.Quoted).contains(current.kind)) fail(current.span, "Expected attribute value")
          attribute(token, take())
        } else if (token.kind == Kind.Word && isItem(token.value)) QueryExpression.Id(item(token))
        else text(token)
        node(value, token.span)
      }
    }
    private def item(token: Token): QueryItem = token.value match {
      case Identifier(prefix, number) =>
        val ledger = Ledger.all.find(value => LedgerPolicy.prefix(value).equalsIgnoreCase(prefix)).getOrElse(fail(token.span, "Unknown item prefix"))
        val value = Try(number.toLong).toOption.filter(_ > 0).filter(_.toString == number).getOrElse(fail(token.span, "Item number must be a canonical positive 64-bit integer"))
        QueryItem(ledger, value)
      case _ => fail(token.span, "Expected item ID such as T42")
    }
    private def text(token: Token): QueryExpression = {
      val words = SearchText.words(token.value)
      if (words.isEmpty || words.size > MaxWords || !words.forall(SearchText.bounded))
        fail(token.span, s"Text requires 1–$MaxWords words, each at most ${SearchText.MaxWordBytes} UTF-8 bytes")
      QueryExpression.Text(words, token.kind == Kind.Quoted)
    }
    private def attribute(key: Token, value: Token): QueryExpression = {
      val field = key.value.toLowerCase(Locale.ROOT)
      val folded = value.value.toLowerCase(Locale.ROOT)
      field match {
        case "id" => QueryExpression.Id(item(value))
        case "ledger" => QueryExpression.LedgerIs(QueryCatalog.ledgers.getOrElse(folded, fail(value.span, "Unknown ledger")))
        case "status" =>
          if (!QueryCatalog.statuses(folded)) fail(value.span, "Unknown ledger status")
          QueryExpression.Status(folded)
        case "tag" =>
          if (value.value.trim.isEmpty || value.value.length > LedgerPolicy.MaxLabel) fail(value.span, "Invalid tag length or empty value")
          QueryExpression.Tag(value.value)
        case "project" =>
          val id = Try(UUID.fromString(value.value)).toOption.filter(_.toString.equalsIgnoreCase(value.value)).getOrElse(fail(value.span, "Expected canonical project UUID"))
          QueryExpression.Project(ProjectId(id))
        case "archived" => QueryExpression.Archive(folded match {
          case "false" => ArchiveFilter.Active
          case "true" => ArchiveFilter.Archived
          case "all" => ArchiveFilter.All
          case _ => fail(value.span, "Archived must be true, false or all")
        })
        case relation if QueryCatalog.relations.contains(relation) => QueryExpression.Reference(QueryCatalog.relations(relation), item(value))
        case _ => fail(key.span, "Unknown query attribute")
      }
    }
  }
}
