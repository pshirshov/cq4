package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.*
import distage.Lifecycle
import io.circe.parser.parse
import java.sql.{Connection, PreparedStatement}
import zio.{IO, Task}

final class PostgresLedgerRepository(database: LedgerDatabase) extends LedgerRepository[IO] {
  override def projects(after: Option[ProjectId], limit: Int): IO[Throwable, List[Project]] = database.transaction { connection =>
    new Jdbc(connection).query("SELECT body::text FROM cq_projects WHERE (?::uuid IS NULL OR project_id > ?::uuid) ORDER BY project_id LIMIT ?") { s =>
      s.setObject(1, after.map(_.value).orNull); s.setObject(2, after.map(_.value).orNull); s.setInt(3, limit)
    }(r => Wire.decode(Project_JsonCodec, r.getString(1)))
  }

  override def initialize(project: Project): IO[Throwable, Project] = database.transaction { connection =>
    val sql = new Jdbc(connection)
    sql.execute("INSERT INTO cq_projects(project_id, body) VALUES (?, ?::jsonb) ON CONFLICT DO NOTHING") { s =>
      s.setObject(1, project.id.value); s.setString(2, Wire.encode(Project_JsonCodec, project))
    }
    sql.query("SELECT body::text FROM cq_projects WHERE project_id = ?")(_.setObject(1, project.id.value))(r => Wire.decode(Project_JsonCodec, r.getString(1))).head
  }

  override def transact[A](project: ProjectId)(operation: LedgerTransaction => A): IO[Throwable, A] = database.transaction { connection =>
    val sql = new Jdbc(connection)
    val found = sql.query("SELECT body::text FROM cq_projects WHERE project_id = ? FOR UPDATE")(_.setObject(1, project.value))(r => Wire.decode(Project_JsonCodec, r.getString(1)))
    val metadata = found.headOption.getOrElse(throw DomainFailure(Fault.Missing("Project not initialized")))
    operation(new PostgresLedgerTransaction(connection, metadata))
  }
}

final class PostgresLedgerResource(repository: PostgresLedgerRepository, database: LedgerDatabase)
    extends Lifecycle.LiftF[Task, LedgerRepository[IO]](database.initialize.as(repository))

private[server] object Wire {
  def encode[A](codec: BaboonJsonCodec[A], value: A): String = codec.encode(BaboonCodecContext.Default, value).noSpaces
  def decode[A](codec: BaboonJsonCodec[A], value: String): A =
    parse(value).flatMap(codec.decode(BaboonCodecContext.Default, _)).fold(throw _, identity)
}

private final class PostgresLedgerTransaction(connection: Connection, override val project: Project) extends LedgerTransaction {
  private val sql = new Jdbc(connection)
  private def projectKey(s: PreparedStatement): Unit = s.setObject(1, project.id.value)
  private def itemKey(s: PreparedStatement, id: ItemId): Unit = {
    require(id.project == project.id, "Transaction project invariant violated")
    projectKey(s); s.setString(2, id.ledger.toString); s.setLong(3, id.number)
  }
  private def ledger(value: String): Ledger = Ledger.parse(value).getOrElse(throw new IllegalStateException(s"Invalid persisted ledger $value"))
  private def relation(value: String): Relation = Relation.parse(value).getOrElse(throw new IllegalStateException(s"Invalid persisted relation $value"))

  override def cursor: ChangeCursor = ChangeCursor(sql.query("SELECT change_cursor FROM cq_projects WHERE project_id = ?")(projectKey)(_.getLong(1)).head)

  override def allocate(value: Ledger): ItemId = {
    val number = sql.query("INSERT INTO cq_counters(project_id, ledger, last_number) VALUES (?, ?, 1) " +
      "ON CONFLICT(project_id, ledger) DO UPDATE SET last_number = cq_counters.last_number + 1 RETURNING last_number") { s =>
      projectKey(s); s.setString(2, value.toString)
    }(_.getLong(1)).head
    ItemId(project.id, value, number)
  }

  override def get(id: ItemId): Option[Item] = sql.query("SELECT body::text FROM cq_items WHERE project_id = ? AND ledger = ? AND number = ?")(itemKey(_, id))(r => Wire.decode(Item_JsonCodec, r.getString(1))).headOption

  override def put(item: Item): Unit = {
    sql.execute("INSERT INTO cq_items(project_id, ledger, number, revision, schema_version, archived, status, title, narrative, body) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT(project_id, ledger, number) DO UPDATE SET " +
      "revision = EXCLUDED.revision, schema_version = EXCLUDED.schema_version, archived = EXCLUDED.archived, " +
      "status = EXCLUDED.status, title = EXCLUDED.title, narrative = EXCLUDED.narrative, body = EXCLUDED.body") { s =>
      itemKey(s, item.id); s.setLong(4, item.revision.value); s.setString(5, item.baboonDomainVersion)
      s.setBoolean(6, item.draft.archived); s.setString(7, LedgerPolicy.status(item.draft.content))
      s.setString(8, item.draft.title); s.setString(9, item.draft.body); s.setString(10, Wire.encode(Item_JsonCodec, item))
    }
    ()
  }

  override def refs(id: ItemId): List[ItemRef] = {
    val forward = sql.query("SELECT relation, target_ledger, target_number FROM cq_edges WHERE project_id = ? AND source_ledger = ? AND source_number = ?")(itemKey(_, id)) { r =>
      ItemRef(relation(r.getString(1)), ItemId(project.id, ledger(r.getString(2)), r.getLong(3)))
    }
    val backward = sql.query("SELECT relation, source_ledger, source_number FROM cq_edges WHERE project_id = ? AND target_ledger = ? AND target_number = ?")(itemKey(_, id)) { r =>
      ItemRef(LedgerPolicy.inverse(relation(r.getString(1))), ItemId(project.id, ledger(r.getString(2)), r.getLong(3)))
    }
    (forward ++ backward).sortBy(r => (r.relation.toString, r.target.ledger.toString, r.target.number))
  }

  override def edge(edge: CanonicalEdge, present: Boolean): Boolean = {
    val command = if (present) "INSERT INTO cq_edges(project_id, source_ledger, source_number, relation, target_ledger, target_number) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING"
      else "DELETE FROM cq_edges WHERE project_id = ? AND source_ledger = ? AND source_number = ? AND relation = ? AND target_ledger = ? AND target_number = ?"
    sql.execute(command) { s =>
      itemKey(s, edge.source); s.setString(4, edge.relation.toString); s.setString(5, edge.target.ledger.toString); s.setLong(6, edge.target.number)
    } > 0
  }

  override def historical(id: ItemId, revision: Revision): Option[HistoryEntry] =
    sql.query("SELECT body::text FROM cq_history WHERE project_id = ? AND ledger = ? AND number = ? AND revision = ?") { s =>
      itemKey(s, id); s.setLong(4, revision.value)
    }(r => Wire.decode(HistoryEntry_JsonCodec, r.getString(1))).headOption

  override def history(id: ItemId, before: Revision, limit: Int): List[HistoryEntry] =
    sql.query("SELECT body::text FROM cq_history WHERE project_id = ? AND ledger = ? AND number = ? AND revision < ? ORDER BY revision DESC LIMIT ?") { s =>
      itemKey(s, id); s.setLong(4, before.value); s.setInt(5, limit)
    }(r => Wire.decode(HistoryEntry_JsonCodec, r.getString(1)))

  override def append(entry: HistoryEntry): Unit = {
    sql.execute("INSERT INTO cq_history(project_id, ledger, number, revision, schema_version, body) VALUES (?, ?, ?, ?, ?, ?::jsonb)") { s =>
      itemKey(s, entry.item.item.id); s.setLong(4, entry.item.item.revision.value); s.setString(5, entry.schemaVersion); s.setString(6, Wire.encode(HistoryEntry_JsonCodec, entry))
    }
    ()
  }

  private def requestKey(s: PreparedStatement, actor: Actor, id: RequestId): Unit = {
    projectKey(s); s.setString(2, Wire.encode(Actor_JsonCodec, actor)); s.setObject(3, id.value)
  }

  override def request(actor: Actor, id: RequestId): Option[StoredRequest] =
    sql.query("SELECT fingerprint, body::text FROM cq_requests WHERE project_id = ? AND actor = ? AND request_id = ?")(requestKey(_, actor, id))(r => StoredRequest(r.getString(1), Wire.decode(ChangeAck_JsonCodec, r.getString(2)))).headOption

  override def acknowledge(actor: Actor, value: StoredRequest): Unit = {
    sql.execute("INSERT INTO cq_requests(project_id, actor, request_id, fingerprint, body) VALUES (?, ?, ?, ?, ?::jsonb)") { s =>
      requestKey(s, actor, value.acknowledgement.request); s.setString(4, value.fingerprint); s.setString(5, Wire.encode(ChangeAck_JsonCodec, value.acknowledgement))
    }
    ()
  }

  override def publish(request: RequestId, items: List[ItemRevision]): ChangeCursor = {
    val next = ChangeCursor(sql.query("UPDATE cq_projects SET change_cursor = change_cursor + 1 WHERE project_id = ? RETURNING change_cursor")(projectKey)(_.getLong(1)).head)
    sql.execute("INSERT INTO cq_changes(project_id, cursor, body) VALUES (?, ?, ?::jsonb)") { s =>
      projectKey(s); s.setLong(2, next.value); s.setString(3, Wire.encode(ChangeEvent_JsonCodec, ChangeEvent(next, request, items)))
    }
    next
  }

  override def changes(after: ChangeCursor, limit: Int): List[ChangeEvent] =
    sql.query("SELECT body::text FROM cq_changes WHERE project_id = ? AND cursor > ? ORDER BY cursor LIMIT ?") { s =>
      projectKey(s); s.setLong(2, after.value); s.setInt(3, limit)
    }(r => Wire.decode(ChangeEvent_JsonCodec, r.getString(1)))

  override def scan(filter: ItemFilter, after: Option[ItemId], limit: Int): List[Item] = {
    val ledgerFilter = filter.ledger.fold("")(_ => " AND ledger = ?")
    val archiveFilter = filter.archived match {
      case ArchiveFilter.Active => " AND NOT archived"
      case ArchiveFilter.Archived => " AND archived"
      case ArchiveFilter.All => ""
    }
    val pagination = after.fold("")(_ => " AND (ledger, number) > (?, ?)")
    sql.query(s"SELECT body::text FROM cq_items WHERE project_id = ?$ledgerFilter$archiveFilter$pagination ORDER BY ledger, number LIMIT ?") { s =>
      projectKey(s)
      var index = 2
      filter.ledger.foreach { value => s.setString(index, value.toString); index += 1 }
      after.foreach { id => s.setString(index, id.ledger.toString); s.setLong(index + 1, id.number); index += 2 }
      s.setInt(index, limit)
    }(r => Wire.decode(Item_JsonCodec, r.getString(1)))
  }

  override def claim(id: ItemId): Option[Claim] =
    sql.query("SELECT c.body::text FROM cq_claim_members m JOIN cq_claims c USING(project_id, claim_id) WHERE m.project_id = ? AND m.ledger = ? AND m.number = ?")(itemKey(_, id))(r => Wire.decode(Claim_JsonCodec, r.getString(1))).headOption

  override def claimById(id: ClaimId): Option[Claim] =
    sql.query("SELECT body::text FROM cq_claims WHERE project_id = ? AND claim_id = ?") { s =>
      projectKey(s); s.setObject(2, id.value)
    }(r => Wire.decode(Claim_JsonCodec, r.getString(1))).headOption

  override def saveClaim(claim: Claim): Unit = {
    sql.execute("INSERT INTO cq_claims(project_id, claim_id, generation, expires_at, released, body) VALUES (?, ?, ?, ?, ?, ?::jsonb) " +
      "ON CONFLICT(project_id, claim_id) DO UPDATE SET expires_at = EXCLUDED.expires_at, released = EXCLUDED.released, body = EXCLUDED.body") { s =>
      projectKey(s); s.setObject(2, claim.fence.claim.value); s.setLong(3, claim.fence.generation)
      s.setLong(4, claim.expiresAt); s.setBoolean(5, claim.released); s.setString(6, Wire.encode(Claim_JsonCodec, claim))
    }
    claim.members.foreach { id =>
      sql.execute("INSERT INTO cq_claim_members(project_id, ledger, number, claim_id) VALUES (?, ?, ?, ?) ON CONFLICT(project_id, ledger, number) DO UPDATE SET claim_id = EXCLUDED.claim_id") { s =>
        itemKey(s, id); s.setObject(4, claim.fence.claim.value)
      }
    }
  }

  override def nextFence(): Long = sql.query("UPDATE cq_projects SET fence_counter = fence_counter + 1 WHERE project_id = ? RETURNING fence_counter")(projectKey)(_.getLong(1)).head
}
