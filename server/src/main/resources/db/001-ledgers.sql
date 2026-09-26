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
  revision bigint NOT NULL CHECK (revision > 0),
  schema_version text NOT NULL,
  archived boolean NOT NULL,
  status text NOT NULL,
  title text NOT NULL,
  narrative text NOT NULL,
  search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', title || ' ' || narrative)) STORED,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, ledger, number)
);
CREATE INDEX cq_items_active ON cq_items (project_id, ledger, number) WHERE NOT archived;
CREATE INDEX cq_items_status ON cq_items (project_id, status, ledger, number);
CREATE INDEX cq_items_search ON cq_items USING gin (search_vector);
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
