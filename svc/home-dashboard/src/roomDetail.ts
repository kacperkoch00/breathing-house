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
  return metricSeries(readings, 'temperature').map((point) => point.value)
}

/** Newest-first API readings → oldest→newest humidity values (nulls dropped). */
export function humiditiesForSparkline(readings: EnvironmentReading[]): number[] {
  return metricSeries(readings, 'humidity').map((point) => point.value)
}

export type MetricKey = 'temperature' | 'humidity' | 'co2'

export interface MetricSeriesPoint {
  value: number
  at: string
  sensorId: string | null
}

/**
 * Keep history on one sensor so the chart is a real time series.
 * Do not fall back to mixing every sensor in the room — that looks like a
 * trend but is usually just one snapshot per device at nearly the same time.
 */
export function preferSensorHistory(
  readings: EnvironmentReading[],
  sensorId: string | null | undefined,
): EnvironmentReading[] {
  if (!sensorId) return readings
  return readings.filter((reading) => reading.sensorId === sensorId)
}

/** Newest-first API readings → oldest→newest metric points (nulls dropped). */
export function metricSeries(
  readings: EnvironmentReading[],
  metric: MetricKey,
): MetricSeriesPoint[] {
  return [...readings]
    .reverse()
    .map((reading) => {
      const value = reading[metric]
      if (value == null || Number.isNaN(value)) return null
      return {
        value,
        at: reading.observedAt,
        sensorId: reading.sensorId,
      }
    })
    .filter((point): point is MetricSeriesPoint => point != null)
}

/** Floor an ISO timestamp to the second for overview chart grouping. */
function timestampSecondKey(iso: string): string {
  const ms = new Date(iso).getTime()
  if (Number.isNaN(ms)) return iso
  return new Date(Math.floor(ms / 1000) * 1000).toISOString()
}

/**
 * Room-overview series: merge all sensors’ readings for a metric.
 * Same-second timestamps → average; otherwise keep the single value.
 */
export function overviewMetricSeries(
  readings: EnvironmentReading[],
  metric: MetricKey,
): MetricSeriesPoint[] {
  const buckets = new Map<
    string,
    { sum: number; count: number; sensorId: string | null; at: string }
  >()

  for (const reading of readings) {
    const value = reading[metric]
    if (value == null || Number.isNaN(value)) continue
    const key = timestampSecondKey(reading.observedAt)
    const existing = buckets.get(key)
    if (!existing) {
      buckets.set(key, {
        sum: value,
        count: 1,
        sensorId: reading.sensorId,
        at: reading.observedAt,
      })
      continue
    }
    existing.sum += value
    existing.count += 1
    existing.sensorId = null
    if (new Date(reading.observedAt).getTime() > new Date(existing.at).getTime()) {
      existing.at = reading.observedAt
    }
  }

  return [...buckets.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([, bucket]) => ({
      value: bucket.sum / bucket.count,
      at: bucket.at,
      sensorId: bucket.sensorId,
    }))
}

export function seriesStats(points: MetricSeriesPoint[]): {
  min: number
  max: number
  latest: number
} | null {
  if (points.length === 0) return null
  const values = points.map((point) => point.value)
  return {
    min: Math.min(...values),
    max: Math.max(...values),
    latest: values[values.length - 1]!,
  }
}

/** Newest-first readings → first (latest) reading per sensorId. */
export function latestReadingPerSensor(
  readings: EnvironmentReading[],
  sensorIds?: Set<string>,
): EnvironmentReading[] {
  const bySensor = new Map<string, EnvironmentReading>()
  for (const reading of readings) {
    if (!reading.sensorId) continue
    if (sensorIds && !sensorIds.has(reading.sensorId)) continue
    if (bySensor.has(reading.sensorId)) continue
    bySensor.set(reading.sensorId, reading)
  }
  return [...bySensor.values()]
}

/** Average a numeric metric across latest-per-sensor readings (nulls skipped). */
export function averageMetric(
  readings: EnvironmentReading[],
  metric: MetricKey,
): number | null {
  const values = readings
    .map((reading) => reading[metric])
    .filter((value): value is number => value != null && !Number.isNaN(value))
  if (values.length === 0) return null
  return values.reduce((sum, value) => sum + value, 0) / values.length
}

/** Most recent reading that carries a light level (newest-first input). */
export function latestLightReading(
  readings: EnvironmentReading[],
): EnvironmentReading | null {
  const ranked = [...readings].sort(
    (a, b) => new Date(b.observedAt).getTime() - new Date(a.observedAt).getTime(),
  )
  return ranked.find((reading) => Boolean(reading.lightLevel?.trim())) ?? null
}

/** Synthetic "now" reading for room overview comfort / tiles. */
export function overviewReading(
  latestBySensor: EnvironmentReading[],
): EnvironmentReading | null {
  if (latestBySensor.length === 0) return null
  const light = latestLightReading(latestBySensor)
  const base = latestBySensor[0]!
  return {
    ...base,
    sensorId: null,
    sensorType: light?.sensorType ?? base.sensorType,
    temperature: averageMetric(latestBySensor, 'temperature'),
    humidity: averageMetric(latestBySensor, 'humidity'),
    co2: averageMetric(latestBySensor, 'co2'),
    light: light?.light ?? null,
    lightLevel: light?.lightLevel ?? null,
    observedAt: light?.observedAt ?? base.observedAt,
  }
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

export function formatEventTime(iso: string, options: { withSeconds?: boolean } = {}): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    ...(options.withSeconds ? { second: '2-digit' as const } : {}),
  })
}

/** Newest-first occupancy list → latest event per sensorId. */
export function latestOccupancyBySensor(
  events: OccupancyEvent[],
): Record<string, OccupancyEvent> {
  const bySensor: Record<string, OccupancyEvent> = {}
  for (const event of events) {
    if (!event.sensorId || bySensor[event.sensorId]) continue
    bySensor[event.sensorId] = event
  }
  return bySensor
}

export function formatOccupancyState(event: OccupancyEvent): string {
  if (event.eventType === 'PRESENCE') {
    if (event.present == null) return 'Unknown'
    return event.present ? 'Present' : 'Clear'
  }
  if (event.open == null) return 'Unknown'
  return event.open ? 'Open' : 'Closed'
}

export function formatOccupancySummary(
  event: OccupancyEvent,
  sensorNames?: Record<string, string>,
): string {
  const sensorLabel =
    (event.sensorId && sensorNames?.[event.sensorId]) || event.sensorId || null

  let action: string
  if (event.eventType === 'PRESENCE') {
    if (event.present == null) {
      action = 'Presence update'
    } else {
      action = event.present ? 'Someone present' : 'Room clear'
    }
  } else if (event.open == null) {
    action = 'Opening update'
  } else {
    action = event.open ? 'Opened' : 'Closed'
  }

  return sensorLabel ? `${action} · ${sensorLabel}` : action
}

export function pointsToPolyline(points: SparklinePoint[]): string {
  return points.map((point) => `${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(' ')
}
