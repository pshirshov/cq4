package cq.server

import cq.api.*
import cq.core.*
import distage.{Activation, DIKey}
import distage.StandardAxis.Repo
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.scalatest.{AssertZIO, SpecZIO}
import java.util.UUID
import zio.{IO, ZIO}

abstract class BrowseContractTest extends SpecZIO with AssertZIO {
  override def config = super.config.copy(
    pluginConfig = PluginConfig.const(List(CqPlugin)),
    memoizationRoots = Set(DIKey[LedgerRepository[IO]], DIKey[LedgerService[IO]]),
  )
  private def scope(): Scope = Scope(ProjectId(UUID.randomUUID()), Actor("browse", SessionId(UUID.randomUUID()), Role.Human))
  private def task(title: String, status: TaskStatus): ItemDraft = ItemDraft(title, "", Set.empty, false, Content.Task(status, List("Browse"), None, Nil), Nil)
  private def defect(title: String, severity: Severity): ItemDraft = ItemDraft(title, "", Set.empty, false,
    Content.Defect(DefectStatus.Open, severity, "Cards", "Table", "Browse", None, Nil), Nil)
  private def change(service: LedgerService[IO], owner: Scope, mutations: List[Mutation]): IO[Throwable, ChangeAck] =
    service.change(owner, ChangeRequest(RequestId(UUID.randomUUID()), mutations, Nil, "Browse contract"))
  private def collect(service: LedgerService[IO], owner: Scope, order: ItemOrder, after: Option[ItemId], snapshot: Option[ChangeCursor], limit: Int): IO[Throwable, List[BrowseItem]] =
    service.browse(owner, "", order, after, snapshot, limit).flatMap { page =>
      if (page.hasMore) collect(service, owner, order, page.after, Some(page.cursor), limit).map(page.items ++ _)
      else ZIO.succeed(page.items)
    }
  private def milestone(title: String): ItemDraft = ItemDraft(title, "", Set.empty, false, Content.Milestone(MilestoneStatus.Open, "Browse"), Nil)
  private def membership(service: LedgerService[IO], owner: Scope, member: ItemId, target: ItemId, present: Boolean): IO[Throwable, ChangeAck] = for {
    source <- service.get(owner, member)
    container <- service.get(owner, target)
    acknowledgement <- change(service, owner, List(Mutation.Reference(member, source.item.revision, Relation.PartOf, target, container.item.revision, present)))
  } yield acknowledgement

  "Sorted browse (Behavioral Active Blackbox; dummy Group / PostgreSQL Good Communication)" should {
    "sort the whole query in either direction with stable ties, Unicode order and absent severity last" in { (service: LedgerService[IO]) =>
      val owner = scope()
      val fixtures = List(task("Zulu", TaskStatus.Ready), defect("Same", Severity.Low), defect("Alpha", Severity.Critical),
        task("Alpha", TaskStatus.Done), defect("Same", Severity.High), task("😀", TaskStatus.Ready), task("\ue000", TaskStatus.Ready))
      val orders = List(
        (ItemOrderField.Id, List(1, 2, 4, 0, 3, 5, 6), List(6, 5, 3, 0, 4, 2, 1)),
        (ItemOrderField.Type, List(1, 2, 4, 0, 3, 5, 6), List(0, 3, 5, 6, 1, 2, 4)),
        (ItemOrderField.Title, List(2, 3, 1, 4, 0, 6, 5), List(5, 6, 0, 1, 4, 2, 3)),
        (ItemOrderField.Status, List(3, 1, 2, 4, 0, 5, 6), List(0, 5, 6, 1, 2, 4, 3)),
        (ItemOrderField.Modified, List(1, 2, 4, 0, 3, 5, 6), List(1, 2, 4, 0, 3, 5, 6)),
        (ItemOrderField.Severity, List(2, 4, 1, 0, 3, 5, 6), List(1, 4, 2, 0, 3, 5, 6)),
      )
      for {
        _ <- service.initialize(owner, "Browse ordering")
        created <- change(service, owner, fixtures.map(Mutation.Create.apply))
        _ <- ZIO.foreachDiscard(orders) { case (field, ascending, descending) =>
          ZIO.foreachDiscard(List((SortDirection.Ascending, ascending), (SortDirection.Descending, descending))) { case (direction, expected) =>
            ZIO.foreachDiscard(List(1, 3, 200)) { limit => collect(service, owner, ItemOrder(field, direction, false), None, None, limit).flatMap { actual =>
              assertIO(actual.map(_.summary.id) == expected.map(created.items(_).id) && actual.filter(_.summary.id.ledger == Ledger.Tasks).forall(_.severity.isEmpty))
            } }
          }
        }
        filtered <- service.browse(owner, "ledger:Defects Same", ItemOrder(ItemOrderField.Severity, SortDirection.Ascending, false), None, None, 1)
        _ <- assertIO(filtered.items.map(_.summary.id) == List(created.items(4).id) && filtered.hasMore)
      } yield ()
    }

    "report each row's milestone and group by it with unassigned rows last across page boundaries" in { (service: LedgerService[IO], repository: LedgerRepository[IO]) =>
      val owner = scope()
      val fixtures = List(milestone("Later"), milestone("Sooner"), task("Zulu", TaskStatus.Ready), task("Alpha", TaskStatus.Done), task("Same", TaskStatus.Ready),
        task("Same", TaskStatus.Ready), task("Mike", TaskStatus.Ready), defect("Alpha", Severity.High), defect("Same", Severity.Low))
      // Fixture index of each member -> fixture index of its milestone; "Mike" joins and leaves again.
      val members = List(2 -> 1, 3 -> 0, 4 -> 1, 5 -> 0)
      for {
        _ <- service.initialize(owner, "Browse grouping")
        created <- change(service, owner, fixtures.map(Mutation.Create.apply))
        ids = created.items.map(_.id)
        _ <- ZIO.foreachDiscard((6 -> 0) :: members) { case (member, target) => membership(service, owner, ids(member), ids(target), true) }
        joined <- collect(service, owner, ItemOrder(ItemOrderField.Id, SortDirection.Ascending, false), None, None, 200)
        _ <- assertIO(joined.find(_.summary.id == ids(6)).flatMap(_.milestone).contains(ids(0)))
        _ <- membership(service, owner, ids(6), ids(0), false)
        expected = members.map { case (member, target) => ids(member) -> ids(target) }.toMap
        _ <- ZIO.foreachDiscard(ItemOrderField.all) { field =>
          ZIO.foreachDiscard(SortDirection.all) { direction =>
            collect(service, owner, ItemOrder(field, direction, false), None, None, 200).flatMap { flat =>
              val grouped = flat.sortBy(item => expected.get(item.summary.id).fold(Long.MaxValue)(_.number))
              assertIO(flat.size == fixtures.size && flat.forall(item => item.milestone == expected.get(item.summary.id))) *>
                ZIO.foreachDiscard(List(1, 3, 200)) { limit =>
                  collect(service, owner, ItemOrder(field, direction, true), None, None, limit).flatMap(actual => assertIO(actual == grouped))
                }
            }
          }
        }
        rows <- collect(service, owner, ItemOrder(ItemOrderField.Id, SortDirection.Ascending, true), None, None, 200)
        single <- repository.transact(owner.project)(tx => rows.map(row => tx.browseItem(row.summary.id)))
        _ <- assertIO(single == rows.map(Some(_)) && rows.map(_.milestone.map(_.number)) == List(Some(1L), Some(1L), Some(2L), Some(2L), None, None, None, None, None))
      } yield ()
    }

    "order modified timestamps numerically and move a later revision across page boundaries" in { (repository: LedgerRepository[IO]) =>
      val owner = scope()
      def at(millis: Long) = FixedLedger.at(repository, millis)
      val earlier = at(9); val later = at(100)
      for {
        _ <- earlier.initialize(owner, "Modified ordering")
        created <- change(earlier, owner, List(Mutation.Create(task("First", TaskStatus.Ready)), Mutation.Create(task("Second", TaskStatus.Ready))))
        _ <- change(later, owner, List(Mutation.Replace(created.items.head.id, Revision(1), task("Changed", TaskStatus.Ready))))
        asc <- later.browse(owner, "", ItemOrder(ItemOrderField.Modified, SortDirection.Ascending, false), None, None, 1)
        desc <- later.browse(owner, "", ItemOrder(ItemOrderField.Modified, SortDirection.Descending, false), None, None, 1)
        _ <- assertIO(asc.items.head.summary.id == created.items(1).id && asc.items.head.summary.updatedAt == 9 && asc.hasMore)
        _ <- assertIO(desc.items.head.summary.id == created.items.head.id && desc.items.head.summary.updatedAt == 100 && desc.hasMore)
        next <- later.browse(owner, "", ItemOrder(ItemOrderField.Modified, SortDirection.Ascending, false), asc.after, Some(asc.cursor), 1)
        _ <- assertIO(next.items == desc.items && !next.hasMore)
      } yield ()
    }

    "count only unarchived items in the selected project and reject stale or unscoped continuations" in { (service: LedgerService[IO]) =>
      val owner = scope(); val other = scope(); val order = ItemOrder(ItemOrderField.Title, SortDirection.Ascending, false)
      val original = task("First", TaskStatus.Done)
      for {
        _ <- service.initialize(owner, "Counts and snapshots")
        _ <- service.initialize(other, "Other project")
        created <- change(service, owner, List(Mutation.Create(original), Mutation.Create(defect("Second", Severity.High)), Mutation.Create(original.copy(archived = true))))
        foreign <- change(service, other, List(Mutation.Create(original)))
        before <- service.counts(owner)
        _ <- assertIO(before.entries.map(_.ledger).toSet == Ledger.all.toSet && before.entries.filter(_.count > 0).toSet == Set(LedgerCount(Ledger.Tasks, 1), LedgerCount(Ledger.Defects, 1)))
        first <- service.browse(owner, "", order, None, None, 1)
        missing <- service.browse(owner, "", order, first.after, None, 1).either
        _ <- assertIO(missing match { case Left(DomainFailure(_: Fault.Invalid)) => true; case _ => false })
        denied <- service.browse(owner, "", order, Some(foreign.items.head.id), Some(first.cursor), 1).either
        _ <- assertIO(denied match { case Left(DomainFailure(_: Fault.Denied)) => true; case _ => false })
        _ <- change(service, owner, List(Mutation.Replace(created.items.head.id, created.items.head.revision, original.copy(archived = true))))
        stale <- service.browse(owner, "", order, first.after, Some(first.cursor), 1).either
        _ <- assertIO(stale match { case Left(DomainFailure(_: Fault.Resync)) => true; case _ => false })
        after <- service.counts(owner)
        _ <- assertIO(after.cursor.value > before.cursor.value && after.entries.find(_.ledger == Ledger.Tasks).exists(_.count == 0))
        archived <- service.browse(owner, "archived:all", order, None, None, 200)
        _ <- assertIO(archived.items.size == 3)
        invalid <- service.browse(owner, "alpha AND", order, None, None, 20).either
        _ <- assertIO(invalid match { case Left(DomainFailure(_: Fault.QuerySyntax)) => true; case _ => false })
      } yield ()
    }
  }
}

final class BrowseContractDummy extends BrowseContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Dummy))
}
final class BrowseContractPostgres extends BrowseContractTest {
  override def config = super.config.copy(activation = Activation(Repo -> Repo.Prod))
}
