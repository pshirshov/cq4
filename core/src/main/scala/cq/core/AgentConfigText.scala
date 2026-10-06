package cq.core

import cq.api.*
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import scala.collection.mutable.ListBuffer

/** Where a role is assigned in a document: in `defaults.roles` (no harness) or in `harnesses.<harness>.roles`. */
final case class AgentRoleKey(harness: Option[Harness], role: AgentRole)

/**
 * One layer of the agent configuration as its text states it, free of the problems that reject a text when it is saved.
 * `references` locates the model references of each role in the text: one list per seat, one position per listed entry.
 */
final class ParsedAgents private[core] (val config: AgentConfig, private[core] val references: Map[AgentRoleKey, List[List[TextPosition]]])
object ParsedAgents {
  /** The layer nobody has written: the empty text. */
  val empty: ParsedAgents = new ParsedAgents(AgentConfig(Map.empty, Map.empty), Map.empty)
}

/** The model reference syntax: `harness:[provider/]model?effort=…`, `harness:@tier?effort=…`, and `[provider/]model?effort=…` in a tier. */
private[core] object AgentReferenceText {
  val Governing = "$harness"
  val MaxNameCharacters = 100
  // Written percent-encoded in a model name, whether or not the scalar is quoted, so that a name has one spelling.
  private val Reserved = "%?#,[]{} "
  private def lower[A](values: List[A]): Map[String, A] = values.map(value => value.toString.toLowerCase(Locale.ROOT) -> value).toMap
  val Harnesses: Map[String, Harness] = lower(Harness.all)
  val Roles: Map[String, AgentRole] = lower(AgentRole.all)
  val Tiers: Map[String, ModelTier] = lower(ModelTier.all)
  val Efforts: Map[String, Effort] = lower(Effort.all)
  val Strategies: Map[String, SeatStrategy] = Map("fallback" -> SeatStrategy.Fallback, "rr" -> SeatStrategy.RoundRobin, "first" -> SeatStrategy.First)
  val Modes: Map[String, PanelMode] = lower(PanelMode.all)
  def text[A](names: Map[String, A], value: A): String = names.collectFirst { case (name, candidate) if candidate == value => name }.get
  def listed[A](names: Map[String, A], all: List[A]): String = all.map(text(names, _)).mkString(", ")

  private def parameters(query: String): Either[String, Option[Effort]] =
    query.split("&", -1).toList.foldLeft[Either[String, Option[Effort]]](Right(None)) { (found, parameter) =>
      found.flatMap { effort =>
        parameter.split("=", 2) match {
          case Array("effort", _) if effort.nonEmpty => Left("effort is given twice")
          case Array("effort", value) => Efforts.get(value).map(Some(_)).toRight(s"unknown effort '$value'; expected one of ${listed(Efforts, Effort.all)}")
          case other => Left(s"unknown parameter '${other.head}'; a reference takes ?effort=… only")
        }
      }
    }

  private def split(text: String): Either[String, (String, Option[Effort])] = text.indexOf('?') match {
    case -1 => Right(text -> None)
    case at => parameters(text.substring(at + 1)).map(text.substring(0, at) -> _)
  }

  private def decoded(text: String): Either[String, String] = {
    val bytes = new java.io.ByteArrayOutputStream()
    var index = 0
    var malformed = false
    while (index < text.length && !malformed) {
      if (text(index) != '%') {
        val length = Character.charCount(text.codePointAt(index))
        bytes.write(text.substring(index, index + length).getBytes(UTF_8))
        index += length
      } else if (index + 2 < text.length && Character.digit(text(index + 1), 16) >= 0 && Character.digit(text(index + 2), 16) >= 0) {
        bytes.write(Integer.parseInt(text.substring(index + 1, index + 3), 16))
        index += 3
      } else malformed = true
    }
    if (malformed) Left("% is followed by two hexadecimal digits; a percent sign is written %25")
    else try Right(UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes.toByteArray)).toString)
    catch { case _: CharacterCodingException => Left("the percent-encoded bytes are not UTF-8") }
  }

  private def part(what: String, raw: String): Either[String, String] = decoded(raw).flatMap { value =>
    if (value.isEmpty) Left(s"the $what is empty")
    else if (value.length > MaxNameCharacters) Left(s"the $what is longer than $MaxNameCharacters characters")
    else if (value.exists(_.isControl)) Left(s"the $what contains a control character")
    else Right(value)
  }

  private def name(raw: String): Either[String, ModelName] =
    raw.find(character => Reserved.contains(character) && character != '%') match {
      case Some(character) => Left(s"${if (character == ' ') "a space" else s"'$character'"} in a model name is written ${encoded(character)}")
      case None if raw.isEmpty => Left("expected a model name")
      case None => raw.indexOf('/') match {
        case -1 => part("model", raw).map(ModelName(None, _))
        case at => for {
          provider <- part("provider", raw.substring(0, at))
          model <- part("model", raw.substring(at + 1))
        } yield ModelName(Some(provider), model)
      }
    }

  def reference(text: String): Either[String, ModelReference] = text.indexOf(':') match {
    case -1 => Left(s"expected a model reference: harness:model, harness:provider/model, harness:@tier or $Governing:…")
    case at => for {
      selector <- text.substring(0, at) match {
        case Governing => Right(HarnessSelector.Governing())
        case other => Harnesses.get(other).map(HarnessSelector.Named(_)).toRight(
          s"unknown harness '$other'; a reference starts with $Governing, ${listed(Harnesses, Harness.all)} and a colon")
      }
      rest <- split(text.substring(at + 1))
      target <- if (rest._1.startsWith("@")) Tiers.get(rest._1.substring(1)).map(ModelTarget.Tier(_)).toRight(
          s"unknown tier '${rest._1.substring(1)}'; expected one of ${listed(Tiers, ModelTier.all)}")
        else name(rest._1).map(ModelTarget.Exact(_))
    } yield ModelReference(selector, target, rest._2)
  }

  def tierEntry(text: String): Either[String, TierEntry] = split(text).flatMap { case (body, effort) =>
    val prefix = body.indexOf(':') match { case -1 => ""; case at => body.substring(0, at) }
    if (body.startsWith("@")) Left("a tier lists models, not tiers; a leading @ of a model name is written %40")
    else if (prefix == Governing || Harnesses.contains(prefix))
      Left(s"a tier entry has no harness prefix: the tier belongs to its harness; a model name that starts with '$prefix:' is written with %3A for the colon")
    else name(body).map(TierEntry(_, effort))
  }

  def encoded(character: Char): String = f"%%${character.toInt}%02X"
  private def encoded(text: String, slash: Boolean): String =
    text.flatMap(character => if (Reserved.contains(character) || (slash && character == '/')) encoded(character) else character.toString)

  def name(provider: Option[String], model: String): String = {
    val body = provider.fold(encoded(model, true))(value => encoded(value, true) + "/" + encoded(model, false))
    val led = if (body.startsWith("@")) encoded('@') + body.substring(1) else body
    // A colon that ends a plain scalar would read as the indicator of a mapping key.
    if (led.endsWith(":")) led.dropRight(1) + encoded(':') else led
  }

  def effort(value: Option[Effort]): String = value.fold("")(effort => "?effort=" + text(Efforts, effort))
}

/**
 * The textual form of an agent configuration layer: the stored representation is the text its operator wrote, so there is a parser
 * and no printer of documents. References, seats and resolved roles are rendered in the same syntax for previews and messages.
 */
object AgentConfigText {
  import AgentReferenceText.{Efforts, Harnesses, Modes, Roles, Strategies, Tiers, listed}

  def parse(text: String): Either[List[AgentProblem], ParsedAgents] = AgentYaml.parse(text) match {
    case Left(problem) => Left(List(problem))
    case Right(node) =>
      val reading = new Reading
      val config = reading.document(node)
      if (reading.problems.isEmpty) Right(new ParsedAgents(config, reading.references.toMap)) else Left(reading.problems.toList.distinct)
  }

  /** The problems that reject this text of one layer when it is saved; they depend on nothing but the text. */
  def problems(text: String): List[AgentProblem] = parse(text).fold(identity, _ => Nil)

  private final class Reading {
    val problems = ListBuffer.empty[AgentProblem]
    val references = ListBuffer.empty[(AgentRoleKey, List[List[TextPosition]])]

    private def syntax(at: TextPosition, message: String): Unit = problems += AgentProblem.Syntax(at, message)
    private def unknown(key: YamlNode.Scalar): Unit = problems += AgentProblem.UnknownKey(key.at, key.text)

    private def entries(node: YamlNode, what: String): List[(YamlNode.Scalar, YamlNode)] = node match {
      case YamlNode.Mapping(values, _) => values
      case _: YamlNode.Empty => Nil
      case other => syntax(other.at, s"expected a mapping with $what"); Nil
    }

    private def keyed[K, V](node: YamlNode, names: Map[String, K], all: List[K])(read: (K, YamlNode) => Option[V]): Map[K, V] =
      entries(node, s"the keys ${listed(names, all)}").flatMap { case (key, value) =>
        names.get(key.text) match {
          case Some(name) => read(name, value).map(name -> _)
          case None => unknown(key); None
        }
      }.toMap

    def document(node: YamlNode): AgentConfig = {
      var defaults = Map.empty[AgentRole, RoleChoice]
      var harnesses = Map.empty[Harness, HarnessAgents]
      entries(node, "the keys defaults, harnesses").foreach { case (key, value) =>
        key.text match {
          case "defaults" => entries(value, "the key roles").foreach { case (inner, assigned) =>
            if (inner.text == "roles") defaults = roles(assigned, None) else unknown(inner)
          }
          case "harnesses" => harnesses = keyed(value, Harnesses, Harness.all)((harness, body) => Some(harnessBody(harness, body)))
          case _ => unknown(key)
        }
      }
      AgentConfig(defaults, harnesses)
    }

    private def harnessBody(harness: Harness, node: YamlNode): HarnessAgents = {
      var tiers = Map.empty[ModelTier, List[TierEntry]]
      var assigned = Map.empty[AgentRole, RoleChoice]
      entries(node, "the keys tiers, roles").foreach { case (key, value) =>
        key.text match {
          case "tiers" => tiers = keyed(value, Tiers, ModelTier.all)((_, models) => tier(harness, models))
          case "roles" => assigned = roles(value, Some(harness))
          case _ => unknown(key)
        }
      }
      HarnessAgents(tiers, assigned)
    }

    private def tier(harness: Harness, node: YamlNode): Option[List[TierEntry]] = node match {
      case YamlNode.Sequence(Nil, at) => problems += AgentProblem.EmptyList(at); None
      case YamlNode.Sequence(items, _) => Some(items.flatMap {
        case scalar: YamlNode.Scalar => AgentReferenceText.tierEntry(scalar.text) match {
          case Left(message) => syntax(scalar.at, message); None
          case Right(entry) =>
            problems ++= AgentResolution.routeProblems(scalar.at, harness, entry.model, entry.effort)
            Some(entry)
        }
        case other => syntax(other.at, "expected a model name"); None
      })
      case other => syntax(other.at, "expected a list of models, as in [model, provider/model?effort=high]"); None
    }

    private def roles(node: YamlNode, scope: Option[Harness]): Map[AgentRole, RoleChoice] =
      keyed(node, Roles, AgentRole.all) { (role, value) =>
        roleValue(role, scope, value).map { case (choice, positions) =>
          references += AgentRoleKey(scope, role) -> positions
          choice
        }
      }

    // In `harnesses.<harness>.roles` the governing harness is that harness, so a `$harness` reference there is checked as a named one.
    private def reference(scalar: YamlNode.Scalar, scope: Option[Harness]): Option[ModelReference] =
      AgentReferenceText.reference(scalar.text) match {
        case Left(message) => syntax(scalar.at, message); None
        case Right(value) =>
          val harness = value.harness match {
            case HarnessSelector.Named(named) => Some(named)
            case HarnessSelector.Governing() => scope
          }
          harness.foreach { known =>
            problems ++= (value.target match {
              case ModelTarget.Exact(model) => AgentResolution.routeProblems(scalar.at, known, model, value.effort)
              case _: ModelTarget.Tier => AgentResolution.effortProblems(scalar.at, known, value.effort)
            })
          }
          Some(value)
      }

    private final case class Selected(key: YamlNode.Scalar, value: YamlNode, min: Option[(YamlNode.Scalar, YamlNode)])

    /** The one of fallback, rr, first, all, any that a mapping states, with its `min` when it has one. */
    private def selected(mapping: YamlNode.Mapping): Option[Selected] = {
      val (selectors, others) = mapping.entries.partition { case (key, _) => Strategies.contains(key.text) || Modes.contains(key.text) }
      val (minimum, unknowns) = others.partition(_._1.text == "min")
      unknowns.foreach(entry => unknown(entry._1))
      selectors match {
        case List((key, value)) => Some(Selected(key, value, minimum.headOption))
        case Nil => syntax(mapping.at, "expected one of the keys fallback, rr, first, all, any"); None
        case _ :: second :: _ => syntax(second._1.at, "a mapping here takes one of fallback, rr, first, all, any, not several"); None
      }
    }

    private def strategy(found: Selected, scope: Option[Harness]): Option[(SeatChoice, List[TextPosition])] = {
      found.min.foreach { case (key, _) => syntax(key.at, "min belongs to a panel (all or any), not to a strategy") }
      found.value match {
        case YamlNode.Sequence(Nil, at) => problems += AgentProblem.EmptyList(at); None
        case YamlNode.Sequence(items, _) =>
          val read = items.map {
            case scalar: YamlNode.Scalar => reference(scalar, scope).map(_ -> scalar.at)
            case other => syntax(other.at, "expected a model reference"); None
          }
          Option.when(read.forall(_.nonEmpty) && found.min.isEmpty)(
            SeatChoice.Strategy(Strategies(found.key.text), read.flatten.map(_._1)) -> read.flatten.map(_._2))
        case other => syntax(other.at, "expected a list of model references, as in [claude:@standard, pi:@standard]"); None
      }
    }

    private def seat(node: YamlNode, scope: Option[Harness]): Option[(SeatChoice, List[TextPosition])] = node match {
      case scalar: YamlNode.Scalar => reference(scalar, scope).map(value => SeatChoice.Single(value) -> List(scalar.at))
      case mapping: YamlNode.Mapping => selected(mapping).flatMap { found =>
        if (Strategies.contains(found.key.text)) strategy(found, scope)
        else { syntax(found.key.at, "a seat of a panel is a model reference or a strategy, not another panel"); None }
      }
      case other => syntax(other.at, "expected a model reference or a strategy"); None
    }

    private def minimum(found: Selected, panel: TextPosition, seats: Int): Option[Int] = found.min match {
      case None => syntax(panel, "a panel states min, as in { any: [a, b], min: 1 }"); None
      case Some((_, scalar: YamlNode.Scalar)) if !scalar.quoted && scalar.text.matches("-?[0-9]+") => scalar.text.toIntOption match {
        case None => syntax(scalar.at, "min is out of range"); None
        case Some(value) if seats > 0 && (value < 1 || value > seats) => problems += AgentProblem.InvalidMinimum(scalar.at, value, seats); None
        case Some(value) => Some(value)
      }
      case Some((_, other)) => syntax(other.at, "min is a whole number"); None
    }

    private def roleValue(role: AgentRole, scope: Option[Harness], node: YamlNode): Option[(RoleChoice, List[List[TextPosition]])] = node match {
      case scalar: YamlNode.Scalar => seat(scalar, scope).map { case (choice, positions) => RoleChoice.Seat(choice) -> List(positions) }
      case mapping: YamlNode.Mapping => selected(mapping).flatMap { found =>
        Modes.get(found.key.text) match {
          case None => strategy(found, scope).map { case (choice, positions) => RoleChoice.Seat(choice) -> List(positions) }
          case Some(mode) =>
            if (role != AgentRole.Reviewer) problems += AgentProblem.PanelNotAllowed(mapping.at, role)
            val seats = found.value match {
              case YamlNode.Sequence(Nil, at) => problems += AgentProblem.EmptyList(at); Nil
              case YamlNode.Sequence(items, _) => items.map(seat(_, scope))
              case other => syntax(other.at, "expected a list of seats, as in [claude:@standard, pi:@standard]"); Nil
            }
            minimum(found, mapping.at, seats.size).filter(_ => seats.nonEmpty && seats.forall(_.nonEmpty) && role == AgentRole.Reviewer).map { min =>
              RoleChoice.Panel(mode, seats.flatten.map(_._1), min) -> seats.flatten.map(_._2)
            }
        }
      }
      case other => syntax(other.at, "expected a model reference, a strategy or a panel"); None
    }
  }

  private def selector(value: HarnessSelector): String = value match {
    case HarnessSelector.Governing() => AgentReferenceText.Governing
    case HarnessSelector.Named(harness) => AgentReferenceText.text(Harnesses, harness)
  }

  def render(reference: ModelReference): String = selector(reference.harness) + ":" + (reference.target match {
    case ModelTarget.Exact(model) => AgentReferenceText.name(model.provider, model.model)
    case ModelTarget.Tier(tier) => "@" + AgentReferenceText.text(Tiers, tier)
  }) + AgentReferenceText.effort(reference.effort)

  def render(route: ModelRoute): String =
    AgentReferenceText.text(Harnesses, route.harness) + ":" + AgentReferenceText.name(route.provider, route.model) + AgentReferenceText.effort(route.effort)

  private def strategy(kind: SeatStrategy, entries: List[String]): String = s"{ ${AgentReferenceText.text(Strategies, kind)}: [${entries.mkString(", ")}] }"

  def render(seat: SeatChoice): String = seat match {
    case SeatChoice.Single(reference) => render(reference)
    case SeatChoice.Strategy(kind, entries) => strategy(kind, entries.map(render))
  }

  private def panel(mode: PanelMode, seats: List[String], min: Int): String = s"{ ${AgentReferenceText.text(Modes, mode)}: [${seats.mkString(", ")}], min: $min }"

  def render(choice: RoleChoice): String = choice match {
    case RoleChoice.Seat(seat) => render(seat)
    case RoleChoice.Panel(mode, seats, min) => panel(mode, seats.map(render), min)
  }

  /** A seat of one candidate that falls back to nothing reads as that candidate. */
  def render(seat: ResolvedSeat): String = seat match {
    case ResolvedSeat(SeatStrategy.Fallback, List(route)) => render(route)
    case ResolvedSeat(kind, candidates) => strategy(kind, candidates.map(render))
  }

  /** The role value that states this plan with exact models; one seat that must deliver reads as that seat. */
  def render(role: ResolvedRole): String = role match {
    case ResolvedRole(PanelMode.All, 1, List(seat), _, _) => render(seat)
    case _ => panel(role.mode, role.seats.map(render), role.min)
  }

  private def at(position: TextPosition): String = s"${position.line}:${position.column}"
  private def harness(value: Harness): String = AgentReferenceText.text(Harnesses, value)
  private def role(value: AgentRole): String = AgentReferenceText.text(Roles, value)

  /** One line for a person; a positioned problem starts with line:column of its layer's text. */
  def describe(problem: AgentProblem): String = problem match {
    case AgentProblem.Syntax(position, message) => s"${at(position)}: $message"
    case AgentProblem.UnknownKey(position, key) => s"${at(position)}: unknown key '$key'"
    case AgentProblem.EmptyList(position) => s"${at(position)}: the list is empty"
    case AgentProblem.InvalidMinimum(position, min, seats) => s"${at(position)}: min is $min, and a panel of $seats seats takes a min from 1 to $seats"
    case AgentProblem.PanelNotAllowed(position, value) => s"${at(position)}: the ${role(value)} role takes a model reference or a strategy; only the reviewer role takes a panel"
    case AgentProblem.ProviderRequired(position, value) => s"${at(position)}: a ${harness(value)} model is written provider/model"
    case AgentProblem.ProviderNotAllowed(position, value) => s"${at(position)}: a ${harness(value)} model is written without a provider"
    case AgentProblem.EffortUnsupported(position, value, effort) =>
      s"${at(position)}: ${harness(value)} does not take effort ${AgentReferenceText.text(Efforts, effort)}; it takes ${listed(Efforts, Effort.all.filter(AgentResolution.efforts(value)))}"
    case AgentProblem.ModelAmbiguous(position, value, model) =>
      s"${at(position)}: ${harness(value)} reads the ending of the model name '$model' as a thinking level, so the name selects no one model; a level is written ?effort=…"
    case AgentProblem.RoleUnassigned(value, unassigned) => s"no layer assigns the ${role(unassigned)} role when ${harness(value)} governs"
    case AgentProblem.TierUndefined(value, tier, assigned) =>
      s"the ${role(assigned)} role refers to the ${AgentReferenceText.text(Tiers, tier)} tier of ${harness(value)}, which no layer defines"
  }
}
