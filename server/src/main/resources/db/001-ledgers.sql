CREATE TABLE cq_catalogue_clock (
  singleton boolean PRIMARY KEY CHECK (singleton),
  cursor bigint NOT NULL CHECK (cursor >= 0)
);
INSERT INTO cq_catalogue_clock(singleton, cursor) VALUES (true, 0);
CREATE TABLE cq_projects (
  project_id uuid PRIMARY KEY,
  body jsonb NOT NULL,
  change_cursor bigint NOT NULL DEFAULT 0 CHECK (change_cursor >= 0),
  fence_counter bigint NOT NULL DEFAULT 0 CHECK (fence_counter >= 0)
);
CREATE TABLE cq_counters (
  project_id uuid NOT NULL REFERENCES cq_projects,
  ledger text NOT NULL,
  last_number bigint NOT NULL CHECK (last_number > 0),
  PRIMARY KEY (project_id, ledger)
);
CREATE TABLE cq_items (
  project_id uuid NOT NULL REFERENCES cq_projects,
  ledger text NOT NULL,
  number bigint NOT NULL CHECK (number > 0),
  display_id text COLLATE "C" NOT NULL,
  revision bigint NOT NULL CHECK (revision > 0),
  schema_version text NOT NULL,
  archived boolean NOT NULL,
  status text NOT NULL,
  severity text CHECK (severity IN ('Critical', 'High', 'Medium', 'Low')),
  title text NOT NULL,
  narrative text NOT NULL,
  search_text text NOT NULL,
  search_words text[] GENERATED ALWAYS AS (string_to_array(btrim(search_text), ' ')) STORED,
  body jsonb NOT NULL,
  summary jsonb NOT NULL,
  PRIMARY KEY (project_id, ledger, number)
);
CREATE INDEX cq_items_active ON cq_items (project_id, ledger, number) WHERE NOT archived;
CREATE UNIQUE INDEX cq_items_display ON cq_items (project_id, display_id);
CREATE INDEX cq_items_archive_display ON cq_items (project_id, archived, display_id);
CREATE INDEX cq_items_status ON cq_items (project_id, status, ledger, number);
CREATE INDEX cq_items_search ON cq_items USING gin (search_words);
CREATE INDEX cq_items_labels ON cq_items USING gin ((summary->'labels'));
CREATE TABLE cq_labels (
  project_id uuid NOT NULL REFERENCES cq_projects,
  label text COLLATE "C" NOT NULL,
  members bigint NOT NULL CHECK (members > 0),
  PRIMARY KEY (project_id, label)
);
CREATE TABLE cq_edges (
  project_id uuid NOT NULL,
  source_ledger text NOT NULL,
  source_number bigint NOT NULL,
  relation text NOT NULL CHECK (relation IN ('DerivedFrom', 'PartOf', 'BlockedBy', 'Reviews', 'Supports', 'Contradicts', 'Supersedes', 'RelatesTo')),
  target_ledger text NOT NULL,
  target_number bigint NOT NULL,
  PRIMARY KEY (project_id, source_ledger, source_number, relation, target_ledger, target_number),
  FOREIGN KEY (project_id, source_ledger, source_number) REFERENCES cq_items,
  FOREIGN KEY (project_id, target_ledger, target_number) REFERENCES cq_items,
  CHECK ((source_ledger, source_number) <> (target_ledger, target_number))
);
CREATE INDEX cq_edges_inverse ON cq_edges (project_id, target_ledger, target_number, relation);
CREATE TABLE cq_history (
  project_id uuid NOT NULL,
  ledger text NOT NULL,
  number bigint NOT NULL,
  revision bigint NOT NULL,
  schema_version text NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, ledger, number, revision),
  FOREIGN KEY (project_id, ledger, number) REFERENCES cq_items
);
CREATE TABLE cq_requests (
  project_id uuid NOT NULL REFERENCES cq_projects,
  actor text NOT NULL,
  request_id uuid NOT NULL,
  fingerprint text NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, actor, request_id)
);
CREATE TABLE cq_changes (
  project_id uuid NOT NULL REFERENCES cq_projects,
  cursor bigint NOT NULL CHECK (cursor > 0),
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, cursor)
);
CREATE TABLE cq_claims (
  project_id uuid NOT NULL REFERENCES cq_projects,
  claim_id uuid NOT NULL,
  generation bigint NOT NULL CHECK (generation > 0),
  expires_at bigint NOT NULL,
  released boolean NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, claim_id),
  UNIQUE (project_id, generation)
);
CREATE TABLE cq_claim_members (
  project_id uuid NOT NULL,
  ledger text NOT NULL,
  number bigint NOT NULL,
  claim_id uuid NOT NULL,
  PRIMARY KEY (project_id, ledger, number),
  FOREIGN KEY (project_id, ledger, number) REFERENCES cq_items,
  FOREIGN KEY (project_id, claim_id) REFERENCES cq_claims
);
CREATE INDEX cq_claim_members_owner ON cq_claim_members (project_id, claim_id);

CREATE TABLE cq_usage_clock (
  project_id uuid PRIMARY KEY REFERENCES cq_projects,
  cursor bigint NOT NULL CHECK (cursor >= 0)
);
CREATE TABLE cq_usage_assignments (
  project_id uuid NOT NULL REFERENCES cq_projects,
  assignment_id uuid NOT NULL,
  attribution text NOT NULL,
  cohort uuid,
  evaluation_run text,
  evaluation_scenario text,
  actor jsonb NOT NULL,
  received_at bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, assignment_id)
);
CREATE INDEX cq_usage_cohorts ON cq_usage_assignments (project_id, cohort);
CREATE INDEX cq_usage_evaluations ON cq_usage_assignments (project_id, evaluation_run, evaluation_scenario);
CREATE TABLE cq_usage_members (
  project_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  ledger text NOT NULL,
  number bigint NOT NULL,
  PRIMARY KEY (project_id, assignment_id, ledger, number),
  FOREIGN KEY (project_id, assignment_id) REFERENCES cq_usage_assignments,
  FOREIGN KEY (project_id, ledger, number) REFERENCES cq_items
);
CREATE INDEX cq_usage_tasks ON cq_usage_members (project_id, ledger, number, assignment_id);
CREATE TABLE cq_usage_attempts (
  project_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  parent_id uuid,
  effective_outcome jsonb,
  session_id uuid NOT NULL,
  actor jsonb NOT NULL,
  received_at bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, attempt_id),
  FOREIGN KEY (project_id, assignment_id) REFERENCES cq_usage_assignments,
  FOREIGN KEY (project_id, parent_id) REFERENCES cq_usage_attempts
);
CREATE INDEX cq_usage_assignment_attempts ON cq_usage_attempts (project_id, assignment_id, attempt_id);
CREATE INDEX cq_usage_sessions ON cq_usage_attempts (project_id, session_id, attempt_id);
CREATE TABLE cq_usage_meters (
  project_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  meter text NOT NULL,
  actor jsonb NOT NULL,
  received_at bigint NOT NULL,
  body jsonb NOT NULL,
  projection jsonb NOT NULL,
  PRIMARY KEY (project_id, attempt_id, meter),
  FOREIGN KEY (project_id, attempt_id) REFERENCES cq_usage_attempts
);
CREATE TABLE cq_usage_costs (
  project_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  meter text NOT NULL,
  currency text COLLATE "C" NOT NULL,
  basis text COLLATE "C" NOT NULL,
  pricing_version text COLLATE "C" NOT NULL,
  amount numeric NOT NULL CHECK (amount >= 0),
  measurements bigint NOT NULL CHECK (measurements > 0),
  PRIMARY KEY (project_id, attempt_id, meter, currency, basis, pricing_version),
  FOREIGN KEY (project_id, attempt_id, meter) REFERENCES cq_usage_meters
);
CREATE TABLE cq_usage_records (
  project_id uuid NOT NULL,
  observation_id uuid NOT NULL,
  sequence bigint NOT NULL,
  attempt_id uuid NOT NULL,
  meter text NOT NULL,
  source text NOT NULL,
  position bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, observation_id),
  UNIQUE (project_id, sequence),
  FOREIGN KEY (project_id, attempt_id, meter) REFERENCES cq_usage_meters
);
CREATE INDEX cq_usage_attempt_records ON cq_usage_records (project_id, attempt_id, sequence);
CREATE INDEX cq_usage_native_sources ON cq_usage_records (project_id, attempt_id, source, position);
CREATE TABLE cq_usage_heads (
  project_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  meter text NOT NULL,
  position bigint NOT NULL,
  observation_id uuid NOT NULL,
  PRIMARY KEY (project_id, attempt_id, meter, position),
  FOREIGN KEY (project_id, attempt_id, meter) REFERENCES cq_usage_meters,
  FOREIGN KEY (project_id, observation_id) REFERENCES cq_usage_records
);
CREATE TABLE cq_usage_outcomes (
  project_id uuid NOT NULL,
  request_id uuid NOT NULL,
  sequence bigint NOT NULL,
  attempt_id uuid NOT NULL,
  actor jsonb NOT NULL,
  received_at bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, request_id),
  FOREIGN KEY (project_id, attempt_id) REFERENCES cq_usage_attempts
);
CREATE UNIQUE INDEX cq_usage_outcome_sequence ON cq_usage_outcomes (project_id, sequence);
CREATE INDEX cq_usage_attempt_outcomes ON cq_usage_outcomes (project_id, attempt_id, sequence);
CREATE TABLE cq_usage_spans (
  project_id uuid NOT NULL,
  span_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  session_id uuid NOT NULL,
  phase text NOT NULL,
  started_at bigint NOT NULL,
  finished_at bigint NOT NULL CHECK (finished_at >= started_at),
  actor jsonb NOT NULL,
  received_at bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, span_id),
  FOREIGN KEY (project_id, assignment_id) REFERENCES cq_usage_assignments
);
CREATE INDEX cq_usage_assignment_spans ON cq_usage_spans (project_id, assignment_id);
CREATE INDEX cq_usage_session_spans ON cq_usage_spans (project_id, session_id);
CREATE TABLE cq_artifacts (
  project_id uuid NOT NULL,
  artifact_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  metadata jsonb NOT NULL,
  content bytea NOT NULL CHECK (octet_length(content) <= 262144),
  PRIMARY KEY (project_id, artifact_id),
  FOREIGN KEY (project_id, attempt_id) REFERENCES cq_usage_attempts
);
CREATE TABLE cq_result_admissions (
  project_id uuid NOT NULL,
  attempt_id uuid NOT NULL,
  artifact_id uuid NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, attempt_id),
  FOREIGN KEY (project_id, attempt_id) REFERENCES cq_usage_attempts,
  FOREIGN KEY (project_id, artifact_id) REFERENCES cq_artifacts
);
CREATE TABLE cq_integrations (
  project_id uuid NOT NULL REFERENCES cq_projects,
  integration_id uuid NOT NULL,
  body jsonb NOT NULL,
  hold jsonb NOT NULL,
  PRIMARY KEY (project_id, integration_id)
);
CREATE TABLE cq_integration_members (
  project_id uuid NOT NULL,
  ledger text NOT NULL,
  item_number bigint NOT NULL,
  integration_id uuid NOT NULL,
  PRIMARY KEY (project_id, ledger, item_number),
  FOREIGN KEY (project_id, integration_id) REFERENCES cq_integrations,
  FOREIGN KEY (project_id, ledger, item_number) REFERENCES cq_items
);
CREATE INDEX cq_integration_members_owner ON cq_integration_members(project_id, integration_id);
CREATE TABLE cq_worksets (
  project_id uuid NOT NULL REFERENCES cq_projects,
  workset_id uuid NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, workset_id)
);
CREATE TABLE cq_project_settings (
  project_id uuid NOT NULL REFERENCES cq_projects,
  kind text NOT NULL,
  revision bigint NOT NULL CHECK (revision > 0),
  actor jsonb NOT NULL,
  updated_at bigint NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, kind)
);
