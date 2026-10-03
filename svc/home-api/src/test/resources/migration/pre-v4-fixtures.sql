-- Rooms: living-room (renamed via metadata), bedroom (no metadata), kitchen, attic, garage.
-- 'ghost' metadata has no history and must not create a room.
INSERT INTO home_api.room_metadata (room_id, display_name, updated_at) VALUES
  ('living-room', '  Living Room  ', '2026-10-01T10:00:00Z'),
  ('ghost', 'Ghost Room', '2026-10-01T10:00:00Z');

INSERT INTO environment.environment_reading (room_id, device_id, sensor_type, co2, observed_at, received_at) VALUES
  -- air-1 moved from bedroom (older) to living-room (newer)
  ('bedroom',     'air-1',  'AIR',  500, '2026-10-03T07:00:00Z', '2026-10-03T07:00:01Z'),
  ('living-room', 'air-1',  'AIR',  700, '2026-10-03T09:00:00Z', '2026-10-03T09:00:01Z'),
  -- padded device id is trimmed into sensor_id
  ('bedroom',     ' room-1 ', 'ROOM', NULL, '2026-10-03T07:30:00Z', '2026-10-03T07:30:01Z'),
  -- hub-1 reports in bedroom here, but its newest record overall is the kitchen occupancy event below
  ('bedroom',     'hub-1',  'ROOM', NULL, '2026-10-03T06:00:00Z', '2026-10-03T06:00:01Z'),
  -- legacy rows without a usable device id
  ('living-room', NULL,     'AIR',  600, '2026-10-03T05:00:00Z', '2026-10-03T05:00:01Z'),
  ('attic',       '   ',     'ROOM', NULL, '2026-10-03T04:00:00Z', '2026-10-03T04:00:01Z');

INSERT INTO occupancy.occupancy_event (room_id, device_id, event_type, present, open, observed_at, received_at) VALUES
  ('kitchen', 'door-1', 'OPENING',  NULL, true, '2026-10-03T10:00:00Z', '2026-10-03T10:00:01Z'),
  ('kitchen', 'hub-1',  'PRESENCE', true, NULL, '2026-10-03T11:00:00Z', '2026-10-03T11:00:01Z'),
  ('garage',  NULL,     'PRESENCE', false, NULL, '2026-10-03T03:00:00Z', '2026-10-03T03:00:01Z');
