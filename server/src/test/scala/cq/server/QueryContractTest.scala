package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class QueryContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("query", SessionId(UUID.randomUUID()), Role.Governor))
  private def draft(title: String, body: String, labels: Set[String], archived: Boolean): ItemDraft =
    ItemDraft(title, body, labels, archived, Content.Task(TaskStatus.Ready, List("Searchable"), None, Nil), Nil)
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Query contract scenario"))
  private def create(service: LedgerService[IO], owner: Scope, value: ItemDraft): IO[Throwable, ItemRevision] =
    change(service, owner, List(Mutation.Create(value))).map(_.items.head)
  private def matches(service: LedgerService[IO], owner: Scope, query: String, expected: List[ItemId]): IO[Throwable, Unit] =
    service.search(owner, query, None, 200).flatMap(page => ZIO.attempt(assert(page.items.map(_.id) == expected.sortBy(LedgerPolicy.key), query))).unit

  "Shared query service (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "combine Boolean text and exact attributes without widening project scope or interpreting SQL values" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = scope()
      val quotedTag = "needs' OR TRUE -- %_review"
      for {
        _ <- service.initialize(owner, "query scope")
        _ <- service.initialize(other, "other scope")
        a <- create(service, owner, draft("Alpha retry", "deadline café", Set(quotedTag), false))
        b <- create(service, owner, draft("Beta retry", "deadline", Set("ordinary"), false).copy(content = Content.Task(TaskStatus.Done, List("Searchable"), None, Nil)))
        c <- create(service, owner, draft("Archived alpha", "retry deadline", Set.empty, true))
        _ <- create(service, other, draft("Alpha retry", "deadline café", Set(quotedTag), false))
        _ <- matches(service, owner, "", List(a.id, b.id))
        _ <- matches(service, owner, "alpha OR beta AND NOT status:Done", List(a.id))
        _ <- matches(service, owner, "(alpha OR beta) status:done", List(b.id))
        _ <- matches(service, owner, "ledger:Tasks status:Ready \"retry deadline\"", List(a.id))
        _ <- matches(service, owner, "tag:" + io.circe.Json.fromString(quotedTag).noSpaces, List(a.id))
        _ <- matches(service, owner, "tag:ORDINARY", Nil)
        _ <- matches(service, owner, "T1 OR id:T3 archived:all", List(a.id, c.id))
        _ <- matches(service, owner, s"project:${other.project.value} OR status:Done", List(b.id))
        _ <- matches(service, owner, s"project:${owner.project.value} NOT project:${other.project.value}", List(a.id, b.id))
        _ <- matches(service, owner, "alpha OR archived:true", List(a.id, c.id))
        _ <- matches(service, owner, "NOT archived:true", List(a.id, b.id))
        _ <- matches(service, owner, "tag:\"' OR TRUE --\" OR id:T9223372036854775807", Nil)
      } yield ()
    }

    "preserve normalized whole words and phrases throughout the supported narrative" in { (service: LedgerService[IO]) =>
      val owner = scope()
      for {
        _ <- service.initialize(owner, "full text")
        a <- create(service, owner, draft("Ｎｅｗ café", "x " * 18000 + "late phrase", Set.empty, false))
        b <- create(service, owner, draft("Barrier", "alpha " + "x" * 513 + " beta", Set.empty, false))
        c <- create(service, owner, draft("Punctuation", "alpha,beta CAFÉ re-enter version2", Set.empty, false))
        _ <- matches(service, owner, "\"late phrase\"", List(a.id))
        _ <- matches(service, owner, "\"x late\"", List(a.id))
        _ <- matches(service, owner, "\"new café\"", List(a.id))
        _ <- matches(service, owner, "alpha beta", List(b.id, c.id))
        _ <- matches(service, owner, "\"alpha beta\"", List(c.id))
        _ <- matches(service, owner, "pha OR café", List(a.id, c.id))
        _ <- matches(service, owner, "re-enter version2", List(c.id))
        _ <- matches(service, owner, "\"x x x\"", List(a.id))
      } yield ()
    }

    "resolve every relation and inverse view including archived targets and symmetric references" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val other = scope()
      val relations = List(Relation.DerivedFrom, Relation.PartOf, Relation.BlockedBy, Relation.Reviews,
        Relation.Supports, Relation.Contradicts, Relation.Supersedes, Relation.RelatesTo)
      def field(relation: Relation): String = QueryCatalog.relations.find(_._2 == relation).get._1
      for {
        _ <- service.initialize(owner, "reference query")
        _ <- service.initialize(other, "other reference query")
        source <- create(service, owner, draft("Review", "Evidence", Set.empty, false).copy(content = Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None)))
        target <- create(service, owner, draft("Milestone", "Release", Set.empty, true).copy(content = Content.Milestone(MilestoneStatus.Open, "Release")))
        otherSource <- create(service, other, draft("Review", "Evidence", Set.empty, false).copy(content = Content.Review(ReviewStatus.Pending, Nil, Some(Citation.Commit("consumer", "abc123")), Nil, None)))
        otherTarget <- create(service, other, draft("Milestone", "Release", Set.empty, true).copy(content = Content.Milestone(MilestoneStatus.Open, "Release")))
        _ <- ZIO.foreachDiscard(relations) { relation => for {
          currentOtherSource <- service.get(other, otherSource.id)
          currentOtherTarget <- service.get(other, otherTarget.id)
          _ <- change(service, other, List(Mutation.Reference(otherSource.id, currentOtherSource.item.revision, relation, otherTarget.id, currentOtherTarget.item.revision, true)))
          current <- service.get(owner, source.id)
          beforeTarget <- service.get(owner, target.id)
          _ <- change(service, owner, List(Mutation.Reference(source.id, current.item.revision, relation, target.id, beforeTarget.item.revision, true)))
          _ <- matches(service, owner, s"${field(relation)}:M1", List(source.id))
          _ <- matches(service, owner, s"${field(LedgerPolicy.inverse(relation))}:R1 archived:all", List(target.id))
          _ <- matches(service, owner, s"${field(LedgerPolicy.inverse(relation))}:R1", Nil)
          _ <- matches(service, owner, s"(${field(relation)}:M1 OR ${field(LedgerPolicy.inverse(relation))}:R1) archived:all", List(source.id, target.id))
          _ <- matches(service, owner, s"NOT ${field(relation)}:M1 archived:all", List(target.id))
          _ <- matches(service, owner, s"${field(relation)}:M1 AND NOT ${field(LedgerPolicy.inverse(relation))}:R1 archived:all", List(source.id))
          _ <- matches(service, owner, s"NOT (${field(relation)}:M1 OR ${field(LedgerPolicy.inverse(relation))}:R1) archived:all", Nil)
          updated <- service.get(owner, target.id)
          updatedSource <- service.get(owner, source.id)
          _ <- change(service, owner, List(Mutation.Reference(target.id, updated.item.revision, LedgerPolicy.inverse(relation), source.id, updatedSource.item.revision, false)))
          _ <- matches(service, owner, s"${field(relation)}:M1 archived:all", Nil)
        } yield () }
      } yield ()
    }

    "refresh search projections on edits and restores and retain bounded stable pages" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val original = draft("Before", "first phrase", Set("old"), false)
      for {
        _ <- service.initialize(owner, "search updates")
        a <- create(service, owner, original)
        b <- create(service, owner, draft("Other", "second phrase", Set.empty, false))
        updated <- change(service, owner, List(Mutation.Replace(a.id, a.revision, original.copy(title = "After", body = "new phrase", labels = Set("new"), archived = true))))
        _ <- matches(service, owner, "before OR tag:old archived:all", Nil)
        _ <- matches(service, owner, "after tag:new archived:true", List(a.id))
        _ <- change(service, owner, List(Mutation.Restore(a.id, updated.items.head.revision, a.revision, Nil)))
        _ <- matches(service, owner, "before tag:old", List(a.id))
        _ <- matches(service, owner, "after OR tag:new archived:all", Nil)
        first <- service.search(owner, "phrase", None, 1)
        second <- service.search(owner, "phrase", first.after, 1)
        _ <- assertIO(first.items.map(_.id) == List(a.id) && first.hasMore && second.items.map(_.id) == List(b.id) && !second.hasMore && first.cursor == second.cursor)
        invalid <- service.search(owner, "alpha AND", None, 1).either
        _ <- assertIO(invalid match { case Left(DomainFailure(Fault.QuerySyntax(error))) => error.span == QuerySpan(9, 9); case _ => false })
      } yield ()
    }
  }
}

final class QueryContractDummy extends QueryContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class QueryContractPostgres extends QueryContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
