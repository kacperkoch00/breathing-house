# Message flow: sensors, rooms, and history

## Identifiers

| Concept | Field | Notes |
| :------ | :---- | :---- |
| Physical sensor | `sensorId` | Immutable text supplied by the device; Kafka record key for non-status messages |
| Friendly sensor label | `displayName` | Mutable; defaults to `sensorId` on first discovery |
| Room entity | `roomId` | Server-generated UUID for new rooms; migrated legacy rooms keep their old IDs |
| Friendly room label | `name` | Required; need not be unique |
| History room | snapshotted `room_id` on each reading/event | Assignment that was current at ingest time |

A sensor belongs to at most one room. Unassigned sensors still persist events
with `roomId = null`. Moving a sensor does not rewrite history.

## MQTT → Kafka (sensors-data-collector)

Topics:

- `home/sensors/room`
- `home/sensors/air`
- `home/sensors/opening`
- `home/sensors/presence`
- `home/gateway/status`

Non-status MQTT payloads must include `sensorId`. The collector publishes Kafka
envelope **schema version 2** keyed by `sensorId` (no `roomId` / `deviceId` in
the envelope). Gateway `STATUS` remains schema version 1 on `status-data`.

| Sensor type | Kafka topic |
| :---------- | :---------- |
| ROOM, AIR | `sensor-data` |
| OPENING, PRESENCE | `event-data` |
| STATUS | `status-data` |

## Kafka → Postgres (monitors)

`environment-monitor` and `occupancy-monitor` accept schema **v1 and v2** during
migration:

- **v1:** `sensorId` from trimmed `deviceId` when present; retain envelope `roomId`
- **v2:** require `sensorId`; resolve current room from `home_api.sensor`; snapshot it

For each valid event, one database transaction:

1. Upsert `home_api.sensor` (insert-only `display_name`; never overwrite edits)
2. Read current `room_id` (v2) or keep envelope room (v1)
3. Insert reading/event with `sensor_id` + room snapshot
4. Commit; only then commit the Kafka offset

## Home API

Users create and edit rooms, rename discovered sensors, and assign sensors to
rooms. Room history endpoints query the **snapshotted** `room_id` on each event,
so history stays under the original room after a sensor moves.

## Rolling deployment order

1. **Home API / database V4** — creates `home_api.room` / `home_api.sensor`,
   adds nullable `sensor_id`, relaxes `room_id` NOT NULL, backfills existing data
2. **environment-monitor and occupancy-monitor** — dual-accept v1/v2, register sensors
3. **sensors-data-collector** — schema-v2 MQTT topics and envelopes

Later cleanup (separate task): drop schema-v1 acceptance and deprecated columns
(`device_id`, `room_metadata`).
