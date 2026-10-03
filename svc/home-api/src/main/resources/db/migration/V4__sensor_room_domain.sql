-- Explicit rooms/sensors plus additive history columns for schema-v2 sensorId.
-- Additive for rolling deploys; legacy device_id and room_metadata kept temporarily.

CREATE TABLE IF NOT EXISTS home_api.room (
  room_id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT room_name_nonblank_chk CHECK (length(btrim(name)) > 0),
  CONSTRAINT room_name_length_chk CHECK (char_length(name) <= 100),
  CONSTRAINT room_description_length_chk CHECK (description IS NULL OR char_length(description) <= 500)
);

CREATE TABLE IF NOT EXISTS home_api.sensor (
  sensor_id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  room_id TEXT NULL REFERENCES home_api.room(room_id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sensor_display_name_nonblank_chk CHECK (length(btrim(display_name)) > 0),
  CONSTRAINT sensor_display_name_length_chk CHECK (char_length(display_name) <= 100)
);

CREATE INDEX IF NOT EXISTS sensor_room_id_sensor_id_idx
  ON home_api.sensor (room_id, sensor_id);

ALTER TABLE environment.environment_reading
  ADD COLUMN IF NOT EXISTS sensor_id TEXT;

ALTER TABLE occupancy.occupancy_event
  ADD COLUMN IF NOT EXISTS sensor_id TEXT;

ALTER TABLE environment.environment_reading
  ALTER COLUMN room_id DROP NOT NULL;

ALTER TABLE occupancy.occupancy_event
  ALTER COLUMN room_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS environment_reading_sensor_id_observed_at_idx
  ON environment.environment_reading (sensor_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS occupancy_event_sensor_id_observed_at_idx
  ON occupancy.occupancy_event (sensor_id, observed_at DESC);

-- 1–4: migrate historically discovered rooms into home_api.room
INSERT INTO home_api.room (room_id, name, description, created_at, updated_at)
SELECT
  discovered.room_id,
  COALESCE(NULLIF(btrim(metadata.display_name), ''), discovered.room_id) AS name,
  NULL,
  now(),
  COALESCE(metadata.updated_at, now())
FROM (
  SELECT room_id FROM environment.environment_reading WHERE room_id IS NOT NULL
  UNION
  SELECT room_id FROM occupancy.occupancy_event WHERE room_id IS NOT NULL
) discovered
LEFT JOIN home_api.room_metadata metadata ON metadata.room_id = discovered.room_id
ON CONFLICT (room_id) DO NOTHING;

-- 5: backfill sensor_id from nonblank device_id
UPDATE environment.environment_reading
SET sensor_id = btrim(device_id)
WHERE sensor_id IS NULL
  AND device_id IS NOT NULL
  AND length(btrim(device_id)) > 0;

UPDATE occupancy.occupancy_event
SET sensor_id = btrim(device_id)
WHERE sensor_id IS NULL
  AND device_id IS NOT NULL
  AND length(btrim(device_id)) > 0;

-- 6–8: create sensors; initial displayName = sensorId; current room from latest record
INSERT INTO home_api.sensor (sensor_id, display_name, room_id, created_at, updated_at)
SELECT
  latest.sensor_id,
  latest.sensor_id,
  CASE
    WHEN latest.room_id IS NOT NULL AND EXISTS (
      SELECT 1 FROM home_api.room r WHERE r.room_id = latest.room_id
    ) THEN latest.room_id
    ELSE NULL
  END,
  now(),
  now()
FROM (
  SELECT DISTINCT ON (sensor_id)
    sensor_id,
    room_id,
    observed_at
  FROM (
    SELECT sensor_id, room_id, observed_at
    FROM environment.environment_reading
    WHERE sensor_id IS NOT NULL
    UNION ALL
    SELECT sensor_id, room_id, observed_at
    FROM occupancy.occupancy_event
    WHERE sensor_id IS NOT NULL
  ) all_events
  ORDER BY sensor_id, observed_at DESC
) latest
ON CONFLICT (sensor_id) DO NOTHING;
