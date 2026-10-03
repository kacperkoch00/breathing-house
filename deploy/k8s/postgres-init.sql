-- Shared init for local/CI Postgres. Also embedded in deploy/k8s/postgres.yaml.
-- Fresh installs match the post-V4 shape. Existing DBs are upgraded by Flyway V4.
CREATE SCHEMA IF NOT EXISTS environment;
CREATE SCHEMA IF NOT EXISTS occupancy;
CREATE SCHEMA IF NOT EXISTS home_api;

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

CREATE TABLE IF NOT EXISTS environment.environment_reading (
  id BIGSERIAL PRIMARY KEY,
  room_id TEXT,
  device_id TEXT,
  sensor_id TEXT,
  sensor_type TEXT NOT NULL CHECK (sensor_type IN ('ROOM', 'AIR')),
  temperature DOUBLE PRECISION,
  humidity DOUBLE PRECISION,
  co2 DOUBLE PRECISION,
  light DOUBLE PRECISION,
  light_level TEXT,
  observed_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  kafka_topic TEXT,
  kafka_partition INT,
  kafka_offset BIGINT,
  UNIQUE (kafka_topic, kafka_partition, kafka_offset)
);

CREATE INDEX IF NOT EXISTS environment_reading_room_observed_at_idx
  ON environment.environment_reading (room_id, observed_at DESC);
CREATE INDEX IF NOT EXISTS environment_reading_sensor_observed_at_idx
  ON environment.environment_reading (sensor_type, observed_at DESC);
CREATE INDEX IF NOT EXISTS environment_reading_sensor_id_observed_at_idx
  ON environment.environment_reading (sensor_id, observed_at DESC);

CREATE TABLE IF NOT EXISTS occupancy.occupancy_event (
  id BIGSERIAL PRIMARY KEY,
  room_id TEXT,
  device_id TEXT,
  sensor_id TEXT,
  event_type TEXT NOT NULL CHECK (event_type IN ('PRESENCE', 'OPENING')),
  present BOOLEAN,
  open BOOLEAN,
  observed_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  kafka_topic TEXT,
  kafka_partition INT,
  kafka_offset BIGINT,
  UNIQUE (kafka_topic, kafka_partition, kafka_offset)
);

CREATE INDEX IF NOT EXISTS occupancy_event_room_observed_at_idx
  ON occupancy.occupancy_event (room_id, observed_at DESC);
CREATE INDEX IF NOT EXISTS occupancy_event_type_observed_at_idx
  ON occupancy.occupancy_event (event_type, observed_at DESC);
CREATE INDEX IF NOT EXISTS occupancy_event_sensor_id_observed_at_idx
  ON occupancy.occupancy_event (sensor_id, observed_at DESC);
