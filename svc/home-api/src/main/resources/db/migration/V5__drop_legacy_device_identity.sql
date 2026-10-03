-- Schema-v2 only: monitors no longer write device_id, so sensor_id is the sole sensor identity.
-- Drops legacy history device_id columns, renames alert device_* columns to sensor_*, and
-- removes the superseded room_metadata table.

ALTER TABLE environment.environment_reading DROP COLUMN IF EXISTS device_id;
ALTER TABLE occupancy.occupancy_event DROP COLUMN IF EXISTS device_id;

DROP INDEX IF EXISTS home_api.alert_one_active_instance_idx;

ALTER TABLE home_api.alert RENAME COLUMN device_id TO sensor_id;
ALTER TABLE home_api.alert_state RENAME COLUMN device_id TO sensor_id;
ALTER TABLE home_api.alert_state RENAME COLUMN device_key TO sensor_key;

-- The alert_state primary key follows the renamed column and keeps covering
-- (rule_id, room_id, sensor_key).

CREATE UNIQUE INDEX IF NOT EXISTS alert_one_active_instance_idx
  ON home_api.alert (rule_id, room_id, COALESCE(sensor_id, ''))
  WHERE status = 'ACTIVE';

DROP TABLE IF EXISTS home_api.room_metadata;
