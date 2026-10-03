-- History tables as they existed before Flyway V4 (room_id NOT NULL, no sensor_id).
CREATE SCHEMA IF NOT EXISTS environment;
CREATE SCHEMA IF NOT EXISTS occupancy;

CREATE TABLE environment.environment_reading (
  id BIGSERIAL PRIMARY KEY,
  room_id TEXT NOT NULL,
  device_id TEXT,
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

CREATE INDEX environment_reading_room_observed_at_idx
  ON environment.environment_reading (room_id, observed_at DESC);
CREATE INDEX environment_reading_sensor_observed_at_idx
  ON environment.environment_reading (sensor_type, observed_at DESC);

CREATE TABLE occupancy.occupancy_event (
  id BIGSERIAL PRIMARY KEY,
  room_id TEXT NOT NULL,
  device_id TEXT,
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

CREATE INDEX occupancy_event_room_observed_at_idx
  ON occupancy.occupancy_event (room_id, observed_at DESC);
CREATE INDEX occupancy_event_type_observed_at_idx
  ON occupancy.occupancy_event (event_type, observed_at DESC);
