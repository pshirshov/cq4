BEGIN;
ALTER TABLE cq_usage_clock ADD COLUMN IF NOT EXISTS attempt_events bigint NOT NULL DEFAULT 0 CHECK (attempt_events >= 0);
UPDATE cq_usage_clock c SET attempt_events = counted.events
  FROM (SELECT project_id, count(*) + count(effective_outcome) AS events FROM cq_usage_attempts GROUP BY project_id) counted
  WHERE counted.project_id = c.project_id AND c.attempt_events <> counted.events;
CREATE INDEX IF NOT EXISTS cq_claims_live ON cq_claims (project_id, expires_at) WHERE NOT released;
UPDATE cq_schema_migrations SET checksum = 'da5a0f6cb21680869d5d40e52f1ee403434ab5a8ab9a33652f6730feb6cf01f1' WHERE version = 1 AND checksum = '19bac0029e8f2d1134a872671a186bc7282096b6d05a94f97930f30901259563';
COMMIT;
