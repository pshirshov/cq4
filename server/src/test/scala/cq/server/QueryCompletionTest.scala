package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class QueryCompletionTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("completion", SessionId(UUID.randomUUID()), Role.Governor))
  private def draft(title: String, labels: Set[String], archived: Boolean): ItemDraft =
    ItemDraft(title, "Completion evidence", labels, archived, Content.Task(if (archived) TaskStatus.Done else TaskStatus.Ready, List("Completable"), None, Nil), Nil)
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Completion scenario"))
  private def create(service: LedgerService[IO], owner: Scope, value: ItemDraft): IO[Throwable, ItemRevision] =
    change(service, owner, List(Mutation.Create(value))).map(_.items.head)
  private def replacements(analysis: QueryAnalysis): List[String] = analysis.suggestions.map(_.text)

  "Cursor-aware query completion (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "offer items only in reference fields and respect direct ID archive scope before limiting" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val queries = List(
        "id:" -> List("T3", "T4"),
        "(id:" -> List("T3", "T4"),
        "((id:" -> List("T3", "T4"),
        "(archived:true id:" -> List("T1", "T2"),
        "NOT (archived:false OR (id:" -> List("T1", "T2"),
        "archived:all id:" -> List("T1", "T2", "T3", "T4"),
        "archived:true id:" -> List("T1", "T2"),
        "NOT archived:true id:" -> List("T3", "T4"),
        "NOT archived:false id:" -> List("T1", "T2"),
        "(archived:true OR archived:false) id:" -> List("T1", "T2", "T3", "T4"),
        "archived:true archived:false id:" -> Nil,
        "NOT status:done id:" -> List("T3", "T4"),
        "tag:\"archived:true\" id:" -> List("T3", "T4"),
        "blocked-by:" -> List("T1", "T2", "T3", "T4"),
      )
      for {
        _ <- service.initialize(owner, "contextual item completion")
        _ <- ZIO.foreach(List(true, true, false, false))(archived => create(service, owner, draft("Target", Set.empty, archived)))
        terms <- ZIO.foreach(List("", "T", "T1", "ledger:tasks AND "))(text => service.complete(owner, text, text.length, 50))
        _ <- assertIO(terms.forall(_.suggestions.forall(_.kind != QuerySuggestionKind.Item)))
        _ <- ZIO.foreach(queries) { case (text, expected) =>
          service.complete(owner, text, text.length, 50).flatMap(result => assertIO(replacements(result) == expected))
        }
        limited <- service.complete(owner, "id:", 3, 1)
        _ <- assertIO(replacements(limited) == List("T3") && limited.hasMore)
        suffix <- service.complete(owner, "id:T archived:true", 4, 50)
        _ <- assertIO(replacements(suffix) == List("T1", "T2") && suffix.suggestions.forall(_.span == QuerySpan(3, 4)))
        _ <- ZIO.foreach(List(
          ("id: OR", 3, List("T3", "T4")),
          ("id:T4 AND ", 5, List("T4")),
          ("archived:all id:T AND", 17, List("T1", "T2", "T3", "T4")),
          ("archived:true id: archived:", 17, List("T1", "T2")),
          ("status: id:", 11, List("T3", "T4")),
        )) { case (text, cursor, expected) =>
          service.complete(owner, text, cursor, 50).flatMap(result => assertIO(replacements(result) == expected))
        }
      } yield ()
    }

    "complete local grammar context and replace whole tokens without duplicating delimiters" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "grammar completion")
        field <- service.complete(owner, "ledger:Tasks", 2, 50)
        _ <- assertIO(field.suggestions == List(QuerySuggestion(QuerySuggestionKind.Field, QuerySpan(0, 6), "ledger", "ledger")))
        value <- service.complete(owner, "ledger:Tasks", 9, 50)
        _ <- assertIO(replacements(value) == List("tasks") && value.suggestions.head.span == QuerySpan(7, 12))
        empty <- service.complete(owner, "ledger: Tasks", 7, 50)
        _ <- assertIO(replacements(empty).toSet == QueryCatalog.ledgers.keySet && empty.suggestions.forall(_.span == QuerySpan(7, 13)))
        relation <- service.complete(owner, "blocked-b", 9, 50)
        _ <- assertIO(relation.suggestions == List(QuerySuggestion(QuerySuggestionKind.Relation, QuerySpan(0, 9), "blocked-by:", "blocked-by")))
        operator <- service.complete(owner, "alpha O", 7, 50)
        _ <- assertIO(replacements(operator) == List("OR "))
        initial <- service.complete(owner, "O", 1, 50)
        _ <- assertIO(!replacements(initial).contains("OR "))
        grouped <- service.complete(owner, "(alpha ", 7, 50)
        _ <- assertIO(replacements(grouped).contains(") ") && grouped.diagnostic.nonEmpty)
        following <- service.complete(owner, "alpha AND ", 10, 50)
        _ <- assertIO(replacements(following).contains("NOT ") && !replacements(following).contains("OR "))
        status <- service.complete(owner, "status:Do", 9, 50)
        _ <- assertIO(replacements(status).contains("done"))
        archived <- service.complete(owner, "archived:t", 10, 50)
        _ <- assertIO(replacements(archived) == List("true"))
        literal <- service.complete(owner, "\"ordinary phrase\"", 6, 50)
        _ <- assertIO(literal.suggestions.isEmpty && literal.diagnostic.isEmpty)
      } yield ()
    }

    "complete scoped archived references and quoted label prefixes with UTF-16 replacement spans" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = scope()
      val label = "😀 needs \"review\"\\path"
      val source = "tag:\"😀 ne\" OR status:Done"
      for {
        _ <- service.initialize(owner, "scoped completion")
        _ <- service.initialize(other, "hidden completion")
        _ <- create(service, owner, draft("Archived target", Set(label, "Needs review", "100%_literal"), true))
        _ <- create(service, other, draft("Secret", Set("Secret label"), false))
        ids <- service.complete(owner, "blocked-by:t", 12, 50)
        _ <- assertIO(replacements(ids) == List("T1") && ids.suggestions.head.label.contains("Archived target") && ids.suggestions.head.label.endsWith("archived"))
        partial <- service.complete(owner, source, 10, 50)
        _ <- assertIO(replacements(partial) == List(io.circe.Json.fromString(label).noSpaces) && partial.suggestions.head.span == QuerySpan(4, 11))
        incomplete <- service.complete(owner, "tag:\"😀 ne", 10, 50)
        _ <- assertIO(replacements(incomplete) == List(io.circe.Json.fromString(label).noSpaces) && incomplete.diagnostic.nonEmpty)
        lower <- service.complete(owner, "tag:needs", 9, 50)
        _ <- assertIO(lower.suggestions.isEmpty)
        wildcard <- service.complete(owner, "tag:100%_", 9, 50)
        _ <- assertIO(replacements(wildcard) == List("\"100%_literal\""))
        hidden <- service.complete(owner, "tag:Secret", 10, 50)
        _ <- assertIO(hidden.suggestions.isEmpty)
        project <- service.complete(owner, "project:", 8, 50)
        _ <- assertIO(replacements(project) == List(owner.project.value.toString))
        invalid <- ZIO.foreach(List(-1, source.length + 1, 6))(cursor => service.complete(owner, source, cursor, 50).either)
        _ <- assertIO(invalid.forall { case Left(DomainFailure(_: Fault.Invalid)) => true; case _ => false })
      } yield ()
    }

    "maintain bounded deterministic catalogs through edits, last-member removal, restoration and rollback" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val max = new String(Character.toChars(Character.MAX_CODE_POINT))
      val labels = Set("shared", "only first", "\ue000", "😀", max, max + "x", "\ud7ff", "\ue000x")
      val first = draft("First", labels, false)
      for {
        _ <- service.initialize(owner, "catalog accounting")
        a <- create(service, owner, first)
        b <- create(service, owner, draft("Second", Set("shared"), false))
        limited <- service.complete(owner, "tag:", 4, 2)
        _ <- assertIO(replacements(limited) == labels.toList.sorted(SearchPrefix.ordering).take(2).map(io.circe.Json.fromString(_).noSpaces) && limited.hasMore)
        supplementary <- service.complete(owner, "tag:\"" + max, 7, 50)
        _ <- assertIO(replacements(supplementary) == List(max, max + "x").map(io.circe.Json.fromString(_).noSpaces))
        boundary <- service.complete(owner, "tag:\"\ud7ff", 6, 50)
        _ <- assertIO(replacements(boundary) == List("\"\ud7ff\""))
        updatedA <- change(service, owner, List(Mutation.Replace(a.id, a.revision, first.copy(labels = Set.empty))))
        shared <- service.complete(owner, "tag:", 4, 50)
        _ <- assertIO(replacements(shared) == List("\"shared\""))
        _ <- change(service, owner, List(Mutation.Replace(b.id, b.revision, draft("Second", Set.empty, true))))
        none <- service.complete(owner, "tag:", 4, 50)
        _ <- assertIO(none.suggestions.isEmpty)
        _ <- change(service, owner, List(Mutation.Restore(a.id, updatedA.items.head.revision, a.revision, Nil)))
        restored <- service.complete(owner, "tag:", 4, 50)
        _ <- assertIO(restored.suggestions.map(_.label).toSet == labels)
        rejected <- change(service, owner, List(Mutation.Create(draft("Rolled back", Set("orphan"), false)),
          Mutation.Replace(b.id, Revision(999), draft("Invalid revision", Set("orphan"), false)))).either
        _ <- assertIO(rejected.isLeft)
        after <- service.complete(owner, "tag:orphan", 10, 50)
        ids <- service.complete(owner, "archived:all id:T", 16, 1)
        _ <- assertIO(after.suggestions.isEmpty && replacements(ids) == List("T1") && ids.hasMore)
      } yield ()
    }

    "bound completion requests and return the ordinary parser diagnostic for malformed text" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "bounded completion")
        limits <- ZIO.foreach(List(0, 51))(limit => service.complete(owner, "", 0, limit).either)
        _ <- assertIO(limits.forall(_.isLeft))
        malformed <- service.complete(owner, "alpha AND", 9, 50)
        _ <- assertIO(malformed.diagnostic.exists(_.span == QuerySpan(9, 9)))
        oversized <- service.complete(owner, "x" * 4097, 4097, 50)
        _ <- assertIO(oversized.diagnostic.nonEmpty && oversized.suggestions.isEmpty)
        empty <- service.complete(owner, "", 0, 1)
        _ <- assertIO(empty.suggestions.size == 1 && empty.hasMore)
      } yield ()
    }

    "reject strings without a lossless database and query representation before allocating items or labels" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val valid = draft("Valid", Set("valid"), false)
      val invalidDrafts = List("a\u0000b", "a\ud800b", "a\udc00b").flatMap { value =>
        List(valid.copy(labels = Set(value)), valid.copy(title = value), valid.copy(body = value),
          valid.copy(content = Content.Task(TaskStatus.Ready, List(value), None, Nil)))
      }
      for {
        _ <- service.initialize(owner, "representable strings")
        outcomes <- ZIO.foreach(invalidDrafts)(value => create(service, owner, value).either)
        observed = outcomes.map {
          case Left(DomainFailure(_: Fault.Invalid)) => "invalid"
          case Left(error) => error.getClass.getName
          case Right(_) => "accepted"
        }
        _ <- assertIO(observed == List.fill(invalidDrafts.size)("invalid"))
        ids <- service.complete(owner, "id:T", 4, 50)
        labels <- service.complete(owner, "tag:", 4, 50)
        _ <- assertIO(ids.suggestions.isEmpty && labels.suggestions.isEmpty)
        created <- create(service, owner, valid)
        _ <- assertIO(created.id.number == 1)
      } yield ()
    }
  }
}

final class QueryCompletionDummy extends QueryCompletionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class QueryCompletionPostgres extends QueryCompletionTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
