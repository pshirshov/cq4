BEGIN;
ALTER TABLE cq_projects ADD COLUMN driver_clock bigint NOT NULL DEFAULT 0 CHECK (driver_clock >= 0);
CREATE TABLE cq_drivers (
  project_id uuid NOT NULL REFERENCES cq_projects,
  harness text NOT NULL,
  session_key text NOT NULL,
  revision bigint NOT NULL CHECK (revision > 0),
  state text NOT NULL CHECK (state IN ('Binding', 'On', 'Off')),
  attached uuid,
  cycle_id uuid,
  touched_at bigint NOT NULL,
  stopped_at bigint,
  summary jsonb NOT NULL,
  body jsonb NOT NULL,
  PRIMARY KEY (project_id, harness, session_key)
);
CREATE UNIQUE INDEX cq_drivers_bound ON cq_drivers(project_id, attached) WHERE state = 'On';
CREATE UNIQUE INDEX cq_drivers_cycle ON cq_drivers(project_id, cycle_id) WHERE cycle_id IS NOT NULL;

UPDATE cq_schema_migrations SET checksum='f0aaf1d080e961fdd09b6a098cbcaac3e108afca1af1df2a4515fa03ff924945' WHERE version=1 AND checksum='f64a63cd716367f15d9f4425087442ee34ce75afd71279618e5d04233b2cd23c';
COMMIT;
