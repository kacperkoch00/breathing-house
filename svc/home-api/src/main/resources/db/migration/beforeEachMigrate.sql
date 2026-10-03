-- Fresh clusters get post-V5 history tables (no device_id) from deploy/k8s/postgres-init.sql, but
-- V4 still backfills sensor_id from device_id and V5 drops it. Until V5 has run (alert_state still
-- has device_key), make sure the column exists so the unchanged V4 applies on both fresh and
-- upgraded databases. V5 removes it again.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = 'home_api' AND table_name = 'alert_state' AND column_name = 'device_key'
  ) THEN
    IF to_regclass('environment.environment_reading') IS NOT NULL THEN
      ALTER TABLE environment.environment_reading ADD COLUMN IF NOT EXISTS device_id TEXT;
    END IF;
    IF to_regclass('occupancy.occupancy_event') IS NOT NULL THEN
      ALTER TABLE occupancy.occupancy_event ADD COLUMN IF NOT EXISTS device_id TEXT;
    END IF;
  END IF;
END
$$;
