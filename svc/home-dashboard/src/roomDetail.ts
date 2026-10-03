import type { EnvironmentReading, OccupancyEvent } from './api/types'

const HISTORY_HOURS = 24

/** UTC ISO lower bound for the default room-detail history window. */
export function historyWindowFrom(now: Date = new Date()): string {
  return new Date(now.getTime() - HISTORY_HOURS * 60 * 60 * 1000).toISOString()
}

export interface SparklinePoint {
  x: number
  y: number
  value: number
}

/** Map a numeric series (oldest→newest) into SVG polyline coordinates. */
export function mapSparklinePoints(
  values: number[],
  width: number,
  height: number,
  padding = 4,
): SparklinePoint[] {
  if (values.length === 0) {
    return []
  }

  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  const innerWidth = Math.max(width - padding * 2, 1)
  const innerHeight = Math.max(height - padding * 2, 1)

  return values.map((value, index) => {
    const x =
      values.length === 1
        ? padding + innerWidth / 2
        : padding + (index / (values.length - 1)) * innerWidth
    const y = padding + (1 - (value - min) / span) * innerHeight
    return { x, y, value }
  })
}

/** Newest-first API readings → oldest→newest temperature values (nulls dropped). */
export function temperaturesForSparkline(readings: EnvironmentReading[]): number[] {
  return [...readings]
    .reverse()
    .map((reading) => reading.temperature)
    .filter((value): value is number => value != null && !Number.isNaN(value))
}

/** Newest-first API readings → oldest→newest humidity values (nulls dropped). */
export function humiditiesForSparkline(readings: EnvironmentReading[]): number[] {
  return [...readings]
    .reverse()
    .map((reading) => reading.humidity)
    .filter((value): value is number => value != null && !Number.isNaN(value))
}

export function formatConditionTemp(temperature: number | null | undefined): string | null {
  if (temperature == null || Number.isNaN(temperature)) {
    return null
  }
  return `${temperature.toFixed(1)}°C`
}

export function formatConditionHumidity(humidity: number | null | undefined): string | null {
  if (humidity == null || Number.isNaN(humidity)) {
    return null
  }
  return `${Math.round(humidity)}%`
}

export function formatConditionCo2(co2: number | null | undefined): string | null {
  if (co2 == null || Number.isNaN(co2)) {
    return null
  }
  return `${Math.round(co2)} ppm`
}

export function formatEventTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function formatOccupancySummary(event: OccupancyEvent): string {
  if (event.eventType === 'PRESENCE') {
    if (event.present == null) {
      return 'Presence'
    }
    return event.present ? 'Present' : 'Absent'
  }
  if (event.open == null) {
    return 'Opening'
  }
  return event.open ? 'Open' : 'Closed'
}

export function pointsToPolyline(points: SparklinePoint[]): string {
  return points.map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(' ')
}
