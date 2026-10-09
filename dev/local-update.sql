BEGIN;
CREATE TABLE IF NOT EXISTS cq_drive_periods (
  project_id uuid NOT NULL REFERENCES cq_projects,
  sequence bigint NOT NULL CHECK (sequence > 0),
  drive uuid NOT NULL,
  harness text NOT NULL,
  session_key text NOT NULL,
  attached uuid,
  state text NOT NULL CHECK (state IN ('Binding', 'On', 'Off', 'Removed')),
  reason text,
  at bigint NOT NULL,
  CHECK ((state = 'Off') = (reason IS NOT NULL)),
  PRIMARY KEY (project_id, sequence)
);
CREATE INDEX IF NOT EXISTS cq_drive_periods_drive ON cq_drive_periods(project_id, drive, sequence);
WITH fresh AS (
  SELECT project_id, harness, session_key, jsonb_build_object('value', gen_random_uuid()::text) AS drive
  FROM cq_drivers WHERE NOT jsonb_exists(body, 'drive')
)
UPDATE cq_drivers d SET body = jsonb_set(d.body, '{drive}', f.drive), summary = jsonb_set(d.summary, '{drive}', f.drive)
  FROM fresh f WHERE d.project_id = f.project_id AND d.harness = f.harness AND d.session_key = f.session_key;
INSERT INTO cq_drive_periods(project_id, sequence, drive, harness, session_key, attached, state, reason, at)
  SELECT d.project_id,
         COALESCE((SELECT max(p.sequence) FROM cq_drive_periods p WHERE p.project_id = d.project_id), 0)
           + row_number() OVER (PARTITION BY d.project_id ORDER BY COALESCE(d.stopped_at, d.touched_at), d.harness, d.session_key),
         (d.body->'drive'->>'value')::uuid, d.harness, d.session_key, d.attached, d.state,
         CASE WHEN d.state = 'Off' THEN d.body->'stopped'->>'reason' END, COALESCE(d.stopped_at, d.touched_at)
  FROM cq_drivers d
  WHERE NOT EXISTS (SELECT 1 FROM cq_drive_periods p WHERE p.project_id = d.project_id AND p.drive = (d.body->'drive'->>'value')::uuid);
UPDATE cq_schema_migrations SET checksum = 'e2f614ff501015977c9662dcc48701cfb8b0d93453db89b6a828ef804a2091d6' WHERE version = 1 AND checksum = 'da5a0f6cb21680869d5d40e52f1ee403434ab5a8ab9a33652f6730feb6cf01f1';
COMMIT;
