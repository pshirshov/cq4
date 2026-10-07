package cq.core

import cq.api.{AgentProblem, TextPosition}
import scala.collection.mutable.ListBuffer

private[core] sealed trait YamlNode { def at: TextPosition }
private[core] object YamlNode {
  final case class Scalar(text: String, quoted: Boolean, at: TextPosition) extends YamlNode
  final case class Mapping(entries: List[(Scalar, YamlNode)], at: TextPosition) extends YamlNode
  final case class Sequence(items: List[YamlNode], at: TextPosition) extends YamlNode
  /** A key without a value; `at` is the position of the key. */
  final case class Empty(at: TextPosition) extends YamlNode
}

/**
 * The YAML subset an agent configuration is written in: one document of block mappings, flow mappings, flow sequences, plain and
 * double-quoted scalars and comments. Everything else YAML has is refused with its position: anchors, aliases, tags, directives,
 * document markers, block scalars, block sequences, single-quoted and multi-line scalars, explicit keys and duplicate keys.
 * A text this accepts is YAML with the same meaning.
 */
private[core] object AgentYaml {
  // The deepest node a configuration has: document, harnesses, a harness, roles, a role, the seats of a panel, a seat, its entries, a reference.
  private val MaxDepth = 9
  private val FlowIndicators = ",[]{}"

  private final case class Failure(problem: AgentProblem.Syntax) extends RuntimeException(problem.message, null, false, false)

  def parse(text: String): Either[AgentProblem.Syntax, YamlNode] =
    try Right(new Reader(text).document()) catch { case Failure(problem) => Left(problem) }

  private final class Reader(text: String) {
    private var index = 0
    private var line = 1
    private var column = 1

    private def position: TextPosition = TextPosition(line, column)
    private def fail(at: TextPosition, message: String): Nothing = throw Failure(AgentProblem.Syntax(at, message))
    private def fail(message: String): Nothing = fail(position, message)
    private def eof: Boolean = index >= text.length
    private def peek: Char = text(index)
    private def ahead(offset: Int): Option[Char] = Option.when(index + offset < text.length)(text(index + offset))
    private def lineEnd(offset: Int): Boolean = ahead(offset).forall(character => character == '\n' || character == '\r')
    private def blank(offset: Int): Boolean = ahead(offset).forall(character => " \t\n\r".contains(character))
    private def advance(): Unit = { index += 1; column += 1 }
    private def newline(): Unit = {
      if (peek == '\r') index += 1
      index += 1
      line += 1
      column = 1
    }

    private def validate(): Unit = {
      var at = TextPosition(1, 1)
      text.indices.foreach { offset =>
        val character = text(offset)
        val paired = if (Character.isHighSurrogate(character)) offset + 1 < text.length && Character.isLowSurrogate(text(offset + 1))
          else !Character.isLowSurrogate(character) || (offset > 0 && Character.isHighSurrogate(text(offset - 1)))
        if (character == '\r' && !(offset + 1 < text.length && text(offset + 1) == '\n')) fail(at, "a carriage return must be followed by a line feed")
        if (character.isControl && !"\t\n\r".contains(character)) fail(at, "control characters are not allowed")
        if (!paired) fail(at, "the text is not valid Unicode")
        at = if (character == '\n') TextPosition(at.line + 1, 1) else TextPosition(at.line, at.column + 1)
      }
    }

    def document(): YamlNode = {
      validate()
      skipLines()
      if (eof) YamlNode.Mapping(Nil, TextPosition(1, 1))
      else {
        val node = block(0, 1)
        skipLines()
        if (!eof) fail("unexpected content after the configuration; a key of the outermost mapping starts in the column of its first key")
        node
      }
    }

    /** Skips spaces and tabs within the line; true when it skipped any. */
    private def spaces(): Boolean = {
      val start = index
      while (!eof && (peek == ' ' || peek == '\t')) advance()
      index > start
    }

    private def comment(): Unit = while (!lineEnd(0)) advance()

    /** Consumes the rest of a line that holds nothing more than a comment. */
    private def restOfLine(separated: Boolean): Unit = {
      val gap = spaces() || separated
      if (!eof && peek == '#') {
        if (!gap) fail("a comment must follow a space")
        comment()
      }
      if (!eof) {
        if (!lineEnd(0)) fail("unexpected text after the value")
        newline()
      }
    }

    /** From the start of a line: skips blank lines, comment lines and indentation up to the next character of the block. */
    private def skipLines(): Unit = {
      var going = true
      while (going && !eof) peek match {
        case ' ' => advance()
        case '\t' => fail("a tab cannot indent a line")
        case '\n' | '\r' => newline()
        case '#' => comment()
        case _ => going = false
      }
    }

    /**
     * Inside a flow collection: skips spaces, line breaks and comments. A continued line is indented more than the key of the
     * block mapping that holds the collection (column `indent`); a closing bracket alone may stand in that column.
     */
    private def gap(indent: Int): Unit = {
      var separated = index == 0 || " \t\n".contains(text(index - 1))
      var broken = false
      var going = true
      while (going && !eof) peek match {
        case ' ' | '\t' => advance(); separated = true
        case '\n' | '\r' => newline(); separated = true; broken = true
        case '#' if separated => comment()
        case '#' => fail("a comment must follow a space")
        case _ => going = false
      }
      if (eof) fail("a list or mapping is not closed")
      if (broken && column <= indent && !(column == indent && (peek == ']' || peek == '}')))
        fail("a continued line must be indented more than the key it belongs to")
    }

    /** At the first character of a node or key: refuses what the subset leaves out. */
    private def refuse(): Unit = {
      def marker(value: String): Boolean = column == 1 && text.startsWith(value, index) && blank(3)
      peek match {
        case '&' => fail("anchors are not supported")
        case '*' => fail("aliases are not supported")
        case '!' => fail("tags are not supported")
        case '|' | '>' => fail("block scalars are not supported")
        case '\'' => fail("single-quoted scalars are not supported; use double quotes")
        case '%' if column == 1 => fail("directives are not supported")
        case '%' | '@' | '`' => fail(s"a plain scalar cannot start with $peek; put the value in double quotes")
        case '-' | '.' if marker("---") || marker("...") => fail("document markers and multiple documents are not supported")
        case '-' if blank(1) => fail("block sequences are not supported; write the list as [a, b]")
        case '?' if blank(1) => fail("explicit keys are not supported")
        case ':' if blank(1) => fail("expected a key before the colon")
        case ',' | ']' | '}' | '[' | '{' => fail(s"unexpected $peek")
        case _ => ()
      }
    }

    private def scalar(flow: Boolean): YamlNode.Scalar = {
      refuse()
      if (peek == '"') quoted() else plain(flow)
    }

    private def plain(flow: Boolean): YamlNode.Scalar = {
      val start = position
      val from = index
      var end = index
      var going = true
      while (going && !eof) {
        val character = peek
        if (lineEnd(0)) going = false
        else if (flow && FlowIndicators.contains(character)) going = false
        else if (character == ':' && (blank(1) || (flow && ahead(1).exists(FlowIndicators.contains(_))))) going = false
        else if (character == '#' && index > from && " \t".contains(text(index - 1))) going = false
        else {
          advance()
          if (character != ' ' && character != '\t') end = index
        }
      }
      column -= index - end
      index = end
      YamlNode.Scalar(text.substring(from, end), false, start)
    }

    private def hex(digits: Int, at: TextPosition): Char = {
      val value = text.slice(index, index + digits)
      if (value.length != digits || !value.forall(character => Character.digit(character, 16) >= 0)) fail(at, s"an escape needs $digits hexadecimal digits")
      (0 until digits).foreach(_ => advance())
      Integer.parseInt(value, 16).toChar
    }

    private def quoted(): YamlNode.Scalar = {
      val start = position
      val value = new StringBuilder
      advance()
      var closed = false
      while (!closed) {
        if (lineEnd(0)) fail(start, "a double-quoted scalar is not closed on its line")
        peek match {
          case '"' => advance(); closed = true
          case '\\' =>
            val escape = position
            advance()
            if (lineEnd(0)) fail(start, "a double-quoted scalar is not closed on its line")
            val kind = peek
            advance()
            kind match {
              case '"' | '\\' | '/' => value.append(kind)
              case 'n' => value.append('\n')
              case 't' => value.append('\t')
              case 'r' => value.append('\r')
              case 'x' => value.append(hex(2, escape))
              case 'u' => value.append(hex(4, escape))
              case _ => fail(escape, s"the escape \\$kind is not supported")
            }
          case character => value.append(character); advance()
        }
      }
      YamlNode.Scalar(value.result(), true, start)
    }

    private def deeper(depth: Int): Unit = if (depth > MaxDepth) fail("the nesting is deeper than a configuration has")

    private def duplicate(entries: ListBuffer[(YamlNode.Scalar, YamlNode)], key: YamlNode.Scalar): Unit =
      if (entries.exists(_._1.text == key.text)) fail(key.at, s"duplicate key ${key.text}")

    private def flow(depth: Int, indent: Int): YamlNode = {
      deeper(depth)
      val start = position
      peek match {
        case '[' =>
          advance()
          gap(indent)
          val items = ListBuffer.empty[YamlNode]
          while (peek != ']') {
            items += flow(depth + 1, indent)
            gap(indent)
            peek match {
              case ',' => advance(); gap(indent)
              case ']' => ()
              case _ => fail("expected , or ]")
            }
          }
          advance()
          YamlNode.Sequence(items.toList, start)
        case '{' =>
          advance()
          gap(indent)
          val entries = ListBuffer.empty[(YamlNode.Scalar, YamlNode)]
          while (peek != '}') {
            if (peek == '[' || peek == '{') fail("a key must be a scalar")
            val key = scalar(true)
            duplicate(entries, key)
            gap(indent)
            if (peek != ':') fail("expected a colon and a space after the key")
            advance()
            gap(indent)
            if (peek == ',' || peek == '}') fail(s"the key ${key.text} has no value")
            entries += key -> flow(depth + 1, indent)
            gap(indent)
            peek match {
              case ',' => advance(); gap(indent)
              case '}' => ()
              case _ => fail("expected , or }")
            }
          }
          advance()
          YamlNode.Mapping(entries.toList, start)
        case _ => scalar(true)
      }
    }

    /** A node that starts its line, in a column greater than that of the key it belongs to (`parent`). */
    private def block(parent: Int, depth: Int): YamlNode = {
      deeper(depth)
      val indent = column
      if (peek == '[' || peek == '{') {
        val node = flow(depth, parent)
        restOfLine(false)
        node
      } else {
        val first = scalar(false)
        val separated = spaces()
        if (!eof && peek == ':' && blank(1)) mapping(indent, first, depth)
        else {
          restOfLine(separated)
          first
        }
      }
    }

    private def mapping(indent: Int, first: YamlNode.Scalar, depth: Int): YamlNode = {
      val entries = ListBuffer.empty[(YamlNode.Scalar, YamlNode)]
      var key = first
      var going = true
      while (going) {
        duplicate(entries, key)
        advance()
        val separated = spaces()
        val value =
          if (lineEnd(0) || peek == '#') {
            restOfLine(separated)
            skipLines()
            if (!eof && column > indent) block(indent, depth + 1) else YamlNode.Empty(key.at)
          } else if (peek == '[' || peek == '{') {
            val node = flow(depth + 1, indent)
            restOfLine(false)
            node
          } else {
            deeper(depth + 1)
            val node = scalar(false)
            val gap = spaces()
            if (!eof && peek == ':' && blank(1)) fail(node.at, "a nested mapping starts on the line after its key")
            restOfLine(gap)
            node
          }
        entries += key -> value
        skipLines()
        if (eof || column < indent) going = false
        else if (column > indent) fail("this line is indented more than the keys of its mapping")
        else {
          if (peek == '[' || peek == '{') fail("expected a key")
          key = scalar(false)
          spaces()
          if (eof || peek != ':' || !blank(1)) fail(key.at, "expected a colon and a space after the key")
        }
      }
      YamlNode.Mapping(entries.toList, first.at)
    }
  }
}
