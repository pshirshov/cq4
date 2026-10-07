BEGIN;
CREATE TABLE IF NOT EXISTS cq_installation_settings (
  kind text PRIMARY KEY,
  revision bigint NOT NULL CHECK (revision > 0),
  actor jsonb NOT NULL,
  updated_at bigint NOT NULL,
  body jsonb NOT NULL
);
UPDATE cq_usage_attempts SET body = body || '{"effort": null}'::jsonb WHERE NOT jsonb_exists(body, 'effort');
UPDATE cq_schema_migrations SET checksum = '19bac0029e8f2d1134a872671a186bc7282096b6d05a94f97930f30901259563' WHERE version = 1 AND checksum = 'f0aaf1d080e961fdd09b6a098cbcaac3e108afca1af1df2a4515fa03ff924945';
COMMIT;
