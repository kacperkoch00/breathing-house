import type { Alert, AlertSeverity, EnvironmentReading, RoomSummary } from './api/types'
import { comfortForRoom, type ComfortBand, type MetricTile } from './comfort'

const SEVERITY_RANK: Record<AlertSeverity, number> = {
  INFO: 1,
  WARNING: 2,
  CRITICAL: 3,
}

export function formatTemp(temperature: number | null | undefined): string {
  if (temperature == null || Number.isNaN(temperature)) {
    return '—'
  }
  return `${temperature.toFixed(1)}°C`
}

export function formatReadingDetail(reading: EnvironmentReading | null): string {
  if (!reading) {
    return 'No readings yet'
  }

  const parts: string[] = []
  if (reading.humidity != null) {
    parts.push(`Humidity ${Math.round(reading.humidity)}%`)
  }
  if (reading.co2 != null) {
    parts.push(`CO₂ ${Math.round(reading.co2)} ppm`)
  }
  return parts.length > 0 ? parts.join(' · ') : 'No readings yet'
}

export function pickHighestSeverityAlert(alerts: Alert[]): Alert | null {
  if (alerts.length === 0) {
    return null
  }
  return alerts.reduce((best, alert) =>
    SEVERITY_RANK[alert.severity] > SEVERITY_RANK[best.severity] ? alert : best,
  )
}

/** Swap raw room/sensor ids in alert text for human-readable names. */
export function humanizeAlertMessage(
  alert: Pick<Alert, 'message' | 'roomId' | 'sensorId'>,
  labels: {
    rooms?: Record<string, string>
    sensors?: Record<string, string>
  } = {},
): string {
  let message = alert.message
  const roomName = alert.roomId ? labels.rooms?.[alert.roomId] : undefined
  if (alert.roomId && roomName) {
    message = message.split(alert.roomId).join(roomName)
  }
  const sensorName = alert.sensorId ? labels.sensors?.[alert.sensorId] : undefined
  if (alert.sensorId && sensorName) {
    message = message.split(alert.sensorId).join(sensorName)
  }
  return message
}

export function roomNameMap(rooms: RoomSummary[]): Record<string, string> {
  return Object.fromEntries(rooms.map((room) => [room.roomId, room.name]))
}

export function alertsForRoom(alerts: Alert[], roomId: string): Alert[] {
  return alerts.filter((alert) => alert.roomId === roomId)
}

export function roomTopline(alerts: Alert[]): string {
  const highest = pickHighestSeverityAlert(alerts)
  if (!highest) {
    return 'Good'
  }
  return highest.severity
}

export function cardTone(
  reading: EnvironmentReading | null,
  roomAlerts: Alert[],
): 'cool' | 'quiet' | 'warm' {
  const highest = pickHighestSeverityAlert(roomAlerts)
  if (highest?.severity === 'CRITICAL' || highest?.severity === 'WARNING') {
    return 'warm'
  }
  if (highest?.severity === 'INFO') {
    return 'quiet'
  }
  if (reading?.temperature == null) {
    return 'quiet'
  }
  if (reading.temperature < 20) {
    return 'cool'
  }
  if (reading.temperature >= 22) {
    return 'warm'
  }
  return 'quiet'
}

export function newestObservedAt(readings: Array<EnvironmentReading | null>): string | null {
  let newest: string | null = null
  for (const reading of readings) {
    if (!reading?.observedAt) {
      continue
    }
    if (newest == null || reading.observedAt > newest) {
      newest = reading.observedAt
    }
  }
  return newest
}

export function formatUpdatedLabel(
  observedAt: string | null,
  fetchedAt: Date,
  now: Date = new Date(),
): string {
  const source = observedAt ? new Date(observedAt) : fetchedAt
  if (Number.isNaN(source.getTime())) {
    return 'Updated just now'
  }
  const ageMs = now.getTime() - source.getTime()
  if (ageMs >= 0 && ageMs < 15_000) {
    return 'Updated just now'
  }
  return `Updated ${source.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
}

export function formatLocalClock(now: Date): string {
  return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

export interface RoomCardModel {
  roomId: string
  name: string
  value: string
  detail: string
  topline: string
  tone: 'cool' | 'quiet' | 'warm'
  alerted: boolean
  comfortLabel: string
  comfortBand: ComfortBand
  metrics: MetricTile[]
  advice: string | null
}

export function mapRoomCard(
  room: RoomSummary,
  reading: EnvironmentReading | null,
  alerts: Alert[],
): RoomCardModel {
  const roomAlerts = alertsForRoom(alerts, room.roomId)
  const comfort = comfortForRoom(room.roomId, reading, alerts)
  return {
    roomId: room.roomId,
    name: room.name,
    value: formatTemp(reading?.temperature),
    detail: formatReadingDetail(reading),
    topline: comfort.label,
    tone: cardTone(reading, roomAlerts),
    alerted: roomAlerts.length > 0,
    comfortLabel: comfort.label,
    comfortBand: comfort.band,
    metrics: comfort.metrics,
    advice: comfort.advice,
  }
}
