package cq.server

import baboon.runtime.shared.{BaboonCodecContext, BaboonJsonCodec}
import cq.api.*
import cq.core.*
import distage.Lifecycle
import io.circe.parser.parse
import java.sql.{Connection, PreparedStatement}
import zio.{IO, Task}

final class PostgresLedgerRepository(database: LedgerDatabase) extends LedgerRepository[IO] {
  override def projects(after: Option[ProjectId], limit: Int): IO[Throwable, ProjectPage] = database.transaction { connection =>
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
    val sql = new Jdbc(connection)
    val cursor = readCatalogueCursor(sql)
    val found = sql.query("SELECT body::text FROM cq_projects WHERE (?::uuid IS NULL OR project_id > ?::uuid) ORDER BY project_id LIMIT ?") { s =>
      s.setObject(1, after.map(_.value).orNull); s.setObject(2, after.map(_.value).orNull); s.setInt(3, limit + 1)
    }(r => Wire.decode(Project_JsonCodec, r.getString(1)))
    val selected = found.take(limit)
    ProjectPage(selected, selected.lastOption.map(_.id), found.size > limit, cursor)
  }

  private def readCatalogueCursor(sql: Jdbc): CatalogueCursor =
    CatalogueCursor(sql.query("SELECT cursor FROM cq_catalogue_clock WHERE singleton")(_ => ())(_.getLong(1)).head)
  override def catalogueCursor: IO[Throwable, CatalogueCursor] = database.transaction(connection => readCatalogueCursor(new Jdbc(connection)))
  override def itemCursor(project: ProjectId): IO[Throwable, ChangeCursor] = database.transaction { connection =>
    new Jdbc(connection).query("SELECT change_cursor FROM cq_projects WHERE project_id = ?")(_.setObject(1, project.value))(r => ChangeCursor(r.getLong(1)))
      .headOption.getOrElse(throw DomainFailure(Fault.Missing("Project not initialized")))
  }

  override def initialize(project: Project): IO[Throwable, Project] = database.transaction { connection =>
    val sql = new Jdbc(connection)
    val inserted = sql.execute("INSERT INTO cq_projects(project_id, body) VALUES (?, ?::jsonb) ON CONFLICT DO NOTHING") { s =>
      s.setObject(1, project.id.value); s.setString(2, Wire.encode(Project_JsonCodec, project))
    }
    if (inserted > 0) sql.execute("UPDATE cq_catalogue_clock SET cursor = cursor + 1 WHERE singleton")(_ => ())
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

  override def renameProject(value: Project): Unit = {
    require(value.id == project.id, "Project identity cannot change")
    sql.execute("UPDATE cq_projects SET body = ?::jsonb WHERE project_id = ?") { s =>
      s.setString(1, Wire.encode(Project_JsonCodec, value)); s.setObject(2, value.id.value)
    }
    sql.execute("UPDATE cq_catalogue_clock SET cursor = cursor + 1 WHERE singleton")(_ => ())
    ()
  }

  override def cursor: ChangeCursor = ChangeCursor(sql.query("SELECT change_cursor FROM cq_projects WHERE project_id = ?")(projectKey)(_.getLong(1)).head)

  override def allocate(value: Ledger): ItemId = {
    val number = sql.query("INSERT INTO cq_counters(project_id, ledger, last_number) VALUES (?, ?, 1) " +
      "ON CONFLICT(project_id, ledger) DO UPDATE SET last_number = cq_counters.last_number + 1 RETURNING last_number") { s =>
      projectKey(s); s.setString(2, value.toString)
    }(_.getLong(1)).head
    ItemId(project.id, value, number)
  }

  override def get(id: ItemId): Option[Item] = sql.query("SELECT body::text FROM cq_items WHERE project_id = ? AND ledger = ? AND number = ?")(itemKey(_, id))(r => Wire.decode(Item_JsonCodec, r.getString(1))).headOption

  override def summary(id: ItemId): Option[ItemSummary] = sql.query("SELECT summary::text FROM cq_items WHERE project_id = ? AND ledger = ? AND number = ?")(itemKey(_, id))(r => Wire.decode(ItemSummary_JsonCodec, r.getString(1))).headOption

  override def put(item: Item): Unit = {
    val previous = sql.query("SELECT summary::text FROM cq_items WHERE project_id = ? AND ledger = ? AND number = ?")(itemKey(_, item.id))
      (r => Wire.decode(ItemSummary_JsonCodec, r.getString(1))).headOption.fold(Set.empty[String])(_.labels)
    sql.execute("INSERT INTO cq_items(project_id, ledger, number, revision, schema_version, archived, status, title, narrative, body, summary, search_text, display_id) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?) ON CONFLICT(project_id, ledger, number) DO UPDATE SET " +
      "revision = EXCLUDED.revision, schema_version = EXCLUDED.schema_version, archived = EXCLUDED.archived, " +
      "status = EXCLUDED.status, title = EXCLUDED.title, narrative = EXCLUDED.narrative, body = EXCLUDED.body, summary = EXCLUDED.summary, search_text = EXCLUDED.search_text") { s =>
      itemKey(s, item.id); s.setLong(4, item.revision.value); s.setString(5, item.baboonDomainVersion)
      s.setBoolean(6, item.draft.archived); s.setString(7, LedgerPolicy.status(item.draft.content).toLowerCase(java.util.Locale.ROOT))
      s.setString(8, item.draft.title); s.setString(9, item.draft.body); s.setString(10, Wire.encode(Item_JsonCodec, item)); s.setString(11, Wire.encode(ItemSummary_JsonCodec, LedgerPolicy.summary(item)))
      s.setString(12, SearchText.document(item.draft.title, item.draft.body))
      s.setString(13, LedgerPolicy.prefix(item.id.ledger) + item.id.number)
    }
    def labelKey(label: String)(statement: PreparedStatement): Unit = { projectKey(statement); statement.setString(2, label) }
    (previous -- item.draft.labels).toList.sorted(SearchPrefix.ordering).foreach { label =>
      val removed = sql.execute("DELETE FROM cq_labels WHERE project_id = ? AND label = ? AND members = 1")(labelKey(label))
      if (removed == 0) require(sql.execute("UPDATE cq_labels SET members = members - 1 WHERE project_id = ? AND label = ? AND members > 1")(labelKey(label)) == 1,
        "Label catalog is missing an existing item label")
    }
    (item.draft.labels -- previous).toList.sorted(SearchPrefix.ordering).foreach { label =>
      sql.execute("INSERT INTO cq_labels(project_id, label, members) VALUES (?, ?, 1) ON CONFLICT(project_id, label) DO UPDATE SET members = cq_labels.members + 1")(labelKey(label))
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

  override def history(id: ItemId, before: Revision, limit: Int): ReadPage[HistoryEntry] =
    sql.page("SELECT body::text FROM cq_history WHERE project_id = ? AND ledger = ? AND number = ? AND revision < ? ORDER BY revision DESC LIMIT ?", limit, HistoryEntry_JsonCodec) { s =>
      itemKey(s, id); s.setLong(4, before.value); s.setInt(5, limit + 1)
    }

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

  override def changes(after: ChangeCursor, limit: Int): ReadPage[ChangeEvent] =
    sql.page("SELECT body::text FROM cq_changes WHERE project_id = ? AND cursor > ? ORDER BY cursor LIMIT ?", limit, ChangeEvent_JsonCodec) { s =>
      projectKey(s); s.setLong(2, after.value); s.setInt(3, limit + 1)
    }

  override def scan(query: QueryExpression, after: Option[ItemId], limit: Int): ReadPage[ItemSummary] = {
    val compiled = QuerySql.compile(query, project.id)
    val pagination = after.fold("")(_ => " AND (i.ledger, i.number) > (?, ?)")
    sql.page(s"SELECT i.summary::text FROM cq_items i WHERE i.project_id = ? AND (${compiled.predicate})$pagination ORDER BY i.ledger, i.number LIMIT ?", limit, ItemSummary_JsonCodec) { s =>
      projectKey(s)
      var index = compiled.bind(s, 2)
      after.foreach { id => s.setString(index, id.ledger.toString); s.setLong(index + 1, id.number); index += 2 }
      s.setInt(index, limit + 1)
    }
  }

  private def prefixWhere(column: String, prefix: SearchPrefix): String = s"$column >= ?" + prefix.upper.fold("")(_ => s" AND $column < ?")
  private def bindPrefix(prefix: SearchPrefix, limit: Int)(statement: PreparedStatement): Unit = {
    projectKey(statement); statement.setString(2, prefix.value)
    prefix.upper match {
      case Some(upper) => statement.setString(3, upper); statement.setInt(4, limit)
      case None => statement.setInt(3, limit)
    }
  }
  override def completeItems(prefix: SearchPrefix, limit: Int): List[ItemSummary] =
    sql.query(s"SELECT summary::text FROM cq_items WHERE project_id = ? AND ${prefixWhere("display_id", prefix)} ORDER BY display_id LIMIT ?")
      (bindPrefix(prefix, limit))(r => Wire.decode(ItemSummary_JsonCodec, r.getString(1)))
  override def completeLabels(prefix: SearchPrefix, limit: Int): List[String] =
    sql.query(s"SELECT label FROM cq_labels WHERE project_id = ? AND ${prefixWhere("label", prefix)} ORDER BY label LIMIT ?")
      (bindPrefix(prefix, limit))(_.getString(1))

  override def claim(id: ItemId): Option[Claim] =
    sql.query("SELECT c.body::text FROM cq_claim_members m JOIN cq_claims c USING(project_id, claim_id) WHERE m.project_id = ? AND m.ledger = ? AND m.number = ?")(itemKey(_, id))(r => Wire.decode(Claim_JsonCodec, r.getString(1))).headOption

  override def claimById(id: ClaimId): Option[Claim] =
    sql.query("SELECT body::text FROM cq_claims WHERE project_id = ? AND claim_id = ?") { s =>
      projectKey(s); s.setObject(2, id.value)
    }(r => Wire.decode(Claim_JsonCodec, r.getString(1))).headOption

  override def claimMembers(id: ClaimId): Set[ItemId] = {
    val members = sql.query("SELECT ledger, number FROM cq_claim_members WHERE project_id = ? AND claim_id = ? LIMIT ?") { s =>
      projectKey(s); s.setObject(2, id.value); s.setInt(3, LedgerPolicy.MaxBatch + 1)
    }(r => ItemId(project.id, ledger(r.getString(1)), r.getLong(2)))
    require(members.size <= LedgerPolicy.MaxBatch, "Persisted claim membership exceeds its bound")
    members.toSet
  }

  override def insertClaim(claim: Claim): Unit = {
    sql.execute("INSERT INTO cq_claims(project_id, claim_id, generation, expires_at, released, body) VALUES (?, ?, ?, ?, ?, ?::jsonb)") { s =>
      projectKey(s); s.setObject(2, claim.fence.claim.value); s.setLong(3, claim.fence.generation)
      s.setLong(4, claim.expiresAt); s.setBoolean(5, claim.released); s.setString(6, Wire.encode(Claim_JsonCodec, claim))
    }
    claim.members.foreach { id =>
      sql.execute("INSERT INTO cq_claim_members(project_id, ledger, number, claim_id) VALUES (?, ?, ?, ?) ON CONFLICT(project_id, ledger, number) DO UPDATE SET claim_id = EXCLUDED.claim_id") { s =>
        itemKey(s, id); s.setObject(4, claim.fence.claim.value)
      }
    }
  }

  override def updateClaim(claim: Claim): Unit = {
    val changed = sql.execute("UPDATE cq_claims SET expires_at = ?, released = ?, body = ?::jsonb WHERE project_id = ? AND claim_id = ? AND generation = ?") { s =>
      s.setLong(1, claim.expiresAt); s.setBoolean(2, claim.released); s.setString(3, Wire.encode(Claim_JsonCodec, claim))
      s.setObject(4, project.id.value); s.setObject(5, claim.fence.claim.value); s.setLong(6, claim.fence.generation)
    }
    require(changed == 1, "Claim fence does not exist")
  }

  override def admission(attempt: AttemptId): Option[ResultAdmission] =
    sql.query("SELECT body::text FROM cq_result_admissions WHERE project_id = ? AND attempt_id = ?") { s =>
      projectKey(s); s.setObject(2, attempt.value)
    }(r => Wire.decode(ResultAdmission_JsonCodec, r.getString(1))).headOption

  override def insertAdmission(value: ResultAdmission): Unit = {
    sql.execute("INSERT INTO cq_result_admissions(project_id, attempt_id, artifact_id, body) VALUES (?, ?, ?, ?::jsonb)") { s =>
      projectKey(s); s.setObject(2, value.artifact.attempt.value); s.setObject(3, value.artifact.id.value)
      s.setString(4, Wire.encode(ResultAdmission_JsonCodec, value))
    }
    ()
  }

  override def nextFence(): Long = sql.query("UPDATE cq_projects SET fence_counter = fence_counter + 1 WHERE project_id = ? RETURNING fence_counter")(projectKey)(_.getLong(1)).head

  override def integration(id: IntegrationId): Option[IntegrationRecord] =
    sql.query("SELECT body::text FROM cq_integrations WHERE project_id = ? AND integration_id = ?") { s =>
      projectKey(s); s.setObject(2, id.value)
    }(r => Wire.decode(IntegrationRecord_JsonCodec, r.getString(1))).headOption

  override def pendingIntegration(item: ItemId): Option[IntegrationHold] =
    sql.query("SELECT i.hold::text FROM cq_integration_members m JOIN cq_integrations i USING(project_id, integration_id) WHERE m.project_id = ? AND m.ledger = ? AND m.item_number = ?") { s =>
      itemKey(s, item)
    }(r => Wire.decode(IntegrationHold_JsonCodec, r.getString(1))).headOption

  override def insertIntegration(value: IntegrationRecord): Unit = {
    require(value.resolution == IntegrationResolution.Pending(), "New integration must be pending")
    sql.execute("INSERT INTO cq_integrations(project_id, integration_id, body, hold) VALUES (?, ?, ?::jsonb, ?::jsonb)") { s =>
      projectKey(s); s.setObject(2, value.intent.id.value); s.setString(3, Wire.encode(IntegrationRecord_JsonCodec, value))
      s.setString(4, Wire.encode(IntegrationHold_JsonCodec, IntegrationPolicy.hold(value.intent)))
    }
    value.intent.members.foreach { ref =>
      sql.execute("INSERT INTO cq_integration_members(project_id, ledger, item_number, integration_id) VALUES (?, ?, ?, ?)") { s =>
        itemKey(s, ref.id); s.setObject(4, value.intent.id.value)
      }
    }
  }

  override def resolveIntegration(value: IntegrationRecord): Unit = {
    require(integration(value.intent.id).exists(old => old.intent == value.intent && old.resolution == IntegrationResolution.Pending()) &&
      value.resolution != IntegrationResolution.Pending(), "Integration resolution requires the exact pending intent")
    val count = sql.execute("DELETE FROM cq_integration_members WHERE project_id = ? AND integration_id = ?") { s =>
      projectKey(s); s.setObject(2, value.intent.id.value)
    }
    require(count == value.intent.members.size, "Integration membership is inconsistent")
    val changed = sql.execute("UPDATE cq_integrations SET body = ?::jsonb WHERE project_id = ? AND integration_id = ?") { s =>
      s.setString(1, Wire.encode(IntegrationRecord_JsonCodec, value)); s.setObject(2, project.id.value); s.setObject(3, value.intent.id.value)
    }
    require(changed == 1, "Integration record disappeared")
  }
}
