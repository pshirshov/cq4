BEGIN;
UPDATE cq_drivers
SET body = jsonb_set(body, '{cycle}', '{"outcomes": [], "retried": []}'::jsonb || (body->'cycle'))
WHERE jsonb_typeof(body->'cycle') = 'object'
  AND NOT (jsonb_exists(body->'cycle', 'outcomes') AND jsonb_exists(body->'cycle', 'retried'));
COMMIT;
