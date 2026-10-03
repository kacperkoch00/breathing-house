CREATE SCHEMA IF NOT EXISTS home_api;

CREATE TABLE IF NOT EXISTS home_api.alert (
  id BIGSERIAL PRIMARY KEY,
  rule_id TEXT NOT NULL,
  room_id TEXT NOT NULL,
  device_id TEXT,
  severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
  status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'RESOLVED')),
  message TEXT NOT NULL,
  trigger_value TEXT,
  triggered_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ,
  last_evaluated_at TIMESTAMPTZ NOT NULL,
  rule_snapshot JSONB NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS alert_one_active_instance_idx
  ON home_api.alert (rule_id, room_id, COALESCE(device_id, ''))
  WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS alert_status_triggered_at_idx
  ON home_api.alert (status, triggered_at DESC);

CREATE INDEX IF NOT EXISTS alert_room_triggered_at_idx
  ON home_api.alert (room_id, triggered_at DESC);

CREATE TABLE IF NOT EXISTS home_api.alert_state (
  rule_id TEXT NOT NULL,
  room_id TEXT NOT NULL,
  device_key TEXT NOT NULL,
  device_id TEXT,
  condition_active BOOLEAN NOT NULL,
  condition_started_at TIMESTAMPTZ,
  last_value TEXT,
  rule_fingerprint TEXT NOT NULL,
  last_evaluated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (rule_id, room_id, device_key)
);
