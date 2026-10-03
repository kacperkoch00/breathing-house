CREATE TABLE IF NOT EXISTS home_api.gateway_heartbeat (
  id BIGSERIAL PRIMARY KEY,
  gateway_id TEXT NOT NULL,
  device_id TEXT NULL,
  reported_status TEXT NOT NULL,
  observed_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  ingested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  payload JSONB NOT NULL,
  kafka_topic TEXT NOT NULL,
  kafka_partition INT NOT NULL,
  kafka_offset BIGINT NOT NULL,
  UNIQUE (kafka_topic, kafka_partition, kafka_offset)
);

CREATE INDEX IF NOT EXISTS gateway_heartbeat_gateway_received_idx
  ON home_api.gateway_heartbeat (gateway_id, received_at DESC, id DESC);
