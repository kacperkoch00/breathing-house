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

`environment-monitor` and `occupancy-monitor` accept schema **v2 only**: they
require `sensorId`, resolve the current room from `home_api.sensor`, and snapshot
it. Schema v1 messages are rejected, and the monitors no longer write `device_id`.
Gateway `STATUS` messages (schema v1 on `status-data`) are consumed only by
`home-api` and are unchanged.

For each valid event, one database transaction:

1. Upsert `home_api.sensor` (insert-only `display_name`; never overwrite edits)
2. Read the sensor's current `room_id`
3. Insert reading/event with `sensor_id` + room snapshot
4. Commit; only then commit the Kafka offset

## Home API

Users create and edit rooms, rename discovered sensors, and assign sensors to
rooms. Room history endpoints query the **snapshotted** `room_id` on each event,
so history stays under the original room after a sensor moves.

Alerts evaluated by `home-api` are stored in `home_api.alert` (`sensor_id` is null
for composite room alerts) and are readable through the read-only
`GET /api/v1/alerts` (filterable, paginated) and `GET /api/v1/alerts/{id}`
(includes the rule snapshot) endpoints. There are no alert write or
notification-delivery endpoints.

## Deployment order

1. **Home API / database V4 and V5** — V4 creates `home_api.room` /
   `home_api.sensor`, adds nullable `sensor_id`, relaxes `room_id` NOT NULL, and
   backfills existing data; V5 drops history `device_id`, renames alert
   `device_*` columns to `sensor_*`, and drops `home_api.room_metadata`
2. **environment-monitor and occupancy-monitor** — schema v2 only, register sensors
3. **sensors-data-collector** — schema-v2 MQTT topics and envelopes (gateway
   `STATUS` remains schema v1)
