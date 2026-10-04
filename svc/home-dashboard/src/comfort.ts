import type { Alert, AlertSeverity, EnvironmentReading } from './api/types'

export type ComfortBand = 'good' | 'moderate' | 'poor' | 'bad' | 'unknown'

const BAND_RANK: Record<ComfortBand, number> = {
  unknown: 0,
  good: 1,
  moderate: 2,
  poor: 3,
  bad: 4,
}

const SEVERITY_RANK: Record<AlertSeverity, number> = {
  INFO: 1,
  WARNING: 2,
  CRITICAL: 3,
}

function formatTemp(temperature: number | null | undefined): string {
  if (temperature == null || Number.isNaN(temperature)) return '—'
  return `${temperature.toFixed(1)}°C`
}

function pickHighestSeverityAlert(alerts: Alert[]): Alert | null {
  if (alerts.length === 0) return null
  return alerts.reduce((best, alert) =>
    SEVERITY_RANK[alert.severity] > SEVERITY_RANK[best.severity] ? alert : best,
  )
}

function alertsForRoom(alerts: Alert[], roomId: string): Alert[] {
  return alerts.filter((alert) => alert.roomId === roomId)
}

export function worstBand(...bands: ComfortBand[]): ComfortBand {
  return bands.reduce((worst, band) => (BAND_RANK[band] > BAND_RANK[worst] ? band : worst), 'good')
}

/** Comfort-oriented bands inspired by ASHRAE / common home monitors. */
export function temperatureBand(temperature: number | null | undefined): ComfortBand {
  if (temperature == null || Number.isNaN(temperature)) return 'unknown'
  if (temperature >= 20 && temperature < 23) return 'good'
  if ((temperature >= 18 && temperature < 20) || (temperature >= 23 && temperature < 25)) {
    return 'moderate'
  }
  if ((temperature >= 16 && temperature < 18) || (temperature >= 25 && temperature < 27)) {
    return 'poor'
  }
  return 'bad'
}

export function humidityBand(humidity: number | null | undefined): ComfortBand {
  if (humidity == null || Number.isNaN(humidity)) return 'unknown'
  if (humidity >= 40 && humidity <= 60) return 'good'
  if ((humidity >= 30 && humidity < 40) || (humidity > 60 && humidity <= 70)) return 'moderate'
  if ((humidity >= 20 && humidity < 30) || (humidity > 70 && humidity <= 80)) return 'poor'
  return 'bad'
}

export function co2Band(co2: number | null | undefined): ComfortBand {
  if (co2 == null || Number.isNaN(co2)) return 'unknown'
  if (co2 <= 800) return 'good'
  if (co2 <= 1200) return 'moderate'
  if (co2 <= 2000) return 'poor'
  return 'bad'
}

export function bandLabel(band: ComfortBand): string {
  switch (band) {
    case 'good':
      return 'Good'
    case 'moderate':
      return 'Fair'
    case 'poor':
      return 'Poor'
    case 'bad':
      return 'Bad'
    default:
      return '—'
  }
}

function penaltyForBand(band: ComfortBand): number {
  switch (band) {
    case 'moderate':
      return 12
    case 'poor':
      return 28
    case 'bad':
      return 45
    default:
      return 0
  }
}

export function roomComfortLabel(
  reading: EnvironmentReading | null,
  roomAlerts: Alert[],
): { label: string; band: ComfortBand } {
  const highest = pickHighestSeverityAlert(roomAlerts)
  if (highest?.severity === 'CRITICAL') {
    return { label: 'Alert', band: 'bad' }
  }
  if (highest?.severity === 'WARNING') {
    return { label: 'Watch', band: 'poor' }
  }

  if (!reading) {
    return { label: 'No data', band: 'unknown' }
  }

  const temp = temperatureBand(reading.temperature)
  const humidity = humidityBand(reading.humidity)
  const co2 = co2Band(reading.co2)
  const band = worstBand(temp, humidity, co2)

  if (co2 === 'bad' || co2 === 'poor') {
    return { label: 'Stuffy', band }
  }
  if (humidity === 'bad' || humidity === 'poor') {
    return {
      label: (reading.humidity ?? 0) > 60 ? 'Humid' : 'Dry',
      band,
    }
  }
  if (temp === 'bad' || temp === 'poor' || temp === 'moderate') {
    if ((reading.temperature ?? 21) < 20) return { label: 'Cool', band }
    if ((reading.temperature ?? 21) >= 23) return { label: 'Warm', band }
  }
  if (highest?.severity === 'INFO') {
    return { label: 'Fair', band: worstBand(band, 'moderate') }
  }
  return { label: 'Good', band: band === 'unknown' ? 'good' : band }
}

export function adviceForReading(reading: EnvironmentReading | null): string | null {
  if (!reading) return null

  const co2 = co2Band(reading.co2)
  if (co2 === 'bad' || co2 === 'poor') {
    return 'CO₂ high — open a window'
  }
  if (co2 === 'moderate') {
    return 'Air getting stuffy — ventilate soon'
  }

  const humidity = humidityBand(reading.humidity)
  if ((humidity === 'bad' || humidity === 'poor') && (reading.humidity ?? 0) > 60) {
    return 'Humidity high — ventilate or dehumidify'
  }
  if ((humidity === 'bad' || humidity === 'poor') && (reading.humidity ?? 50) < 40) {
    return 'Air is dry — consider a humidifier'
  }

  const temp = temperatureBand(reading.temperature)
  if ((temp === 'bad' || temp === 'poor') && (reading.temperature ?? 21) >= 25) {
    return 'Warm room — cool or ventilate'
  }
  if ((temp === 'bad' || temp === 'poor') && (reading.temperature ?? 21) < 18) {
    return 'Cool room — check heating'
  }

  return null
}

export function adviceForAlert(alert: Alert): string {
  const message = alert.message.toLowerCase()
  if (message.includes('no recent air') || (message.includes('air reading') && message.includes('no recent'))) {
    return 'Air sensor has gone quiet — check power or placement'
  }
  if (message.includes('no recent room') || (message.includes('room reading') && message.includes('no recent'))) {
    return 'Room sensor has gone quiet — check the device'
  }
  if (message.includes('open') && message.includes('opening')) {
    return 'An opening has been left open — close it if you can'
  }
  if (message.includes('co2') || message.includes('co₂')) {
    return 'CO₂ is high — open a window'
  }
  if (message.includes('humid')) {
    return 'Humidity is off — ventilate or adjust'
  }
  if (message.includes('temp') || message.includes('heat') || message.includes('cold')) {
    return 'Temperature needs a quick check'
  }
  if (alert.severity === 'CRITICAL') {
    return 'Critical condition — check this room now'
  }
  if (alert.severity === 'WARNING') {
    return 'Condition is drifting — take a look soon'
  }
  return 'Worth a look when you have a moment'
}

export interface HomeHealth {
  score: number
  band: ComfortBand
  label: string
  advice: string
}

export function computeHomeHealth(
  readingsByRoom: Record<string, EnvironmentReading | null>,
  alerts: Alert[],
): HomeHealth {
  const readings = Object.values(readingsByRoom)
  const withData = readings.filter((reading): reading is EnvironmentReading => reading != null)

  if (withData.length === 0 && alerts.length === 0) {
    return {
      score: 0,
      band: 'unknown',
      label: 'No data',
      advice: 'Add rooms and sensors to start reading your home.',
    }
  }

  let score = 100
  let worst: ComfortBand = 'good'

  for (const reading of withData) {
    const bands = [
      temperatureBand(reading.temperature),
      humidityBand(reading.humidity),
      co2Band(reading.co2),
    ]
    for (const band of bands) {
      score -= penaltyForBand(band)
      worst = worstBand(worst, band)
    }
  }

  if (withData.length > 1) {
    const totalPenalty = 100 - score
    score = 100 - Math.round(totalPenalty / withData.length)
  }

  for (const alert of alerts) {
    if (alert.severity === 'CRITICAL') {
      score -= 18
      worst = worstBand(worst, 'bad')
    } else if (alert.severity === 'WARNING') {
      score -= 10
      worst = worstBand(worst, 'poor')
    } else {
      score -= 4
      worst = worstBand(worst, 'moderate')
    }
  }

  score = Math.max(0, Math.min(100, Math.round(score)))
  const band: ComfortBand =
    score >= 80 ? 'good' : score >= 60 ? 'moderate' : score >= 40 ? 'poor' : 'bad'

  const highest = pickHighestSeverityAlert(alerts)
  const actionable = alerts.filter(
    (alert) => alert.severity === 'WARNING' || alert.severity === 'CRITICAL',
  )
  const highestActionable = pickHighestSeverityAlert(actionable)
  const adviceFromAlert = highestActionable ? adviceForAlert(highestActionable) : null
  const adviceFromReading =
    withData
      .map((reading) => adviceForReading(reading))
      .find((advice): advice is string => advice != null) ?? null
  const noticeCount = alerts.length - actionable.length

  return {
    score,
    band,
    label: bandLabel(band),
    advice:
      adviceFromAlert ??
      adviceFromReading ??
      (noticeCount > 0
        ? `${noticeCount} sensor notice${noticeCount === 1 ? '' : 's'} — worth a quick check`
        : highest
          ? adviceForAlert(highest)
          : score >= 80
            ? 'Home looks comfortable right now.'
            : 'Conditions are drifting — check the rooms below.'),
  }
}

export interface MetricTile {
  key: 'temp' | 'humidity' | 'co2'
  label: string
  value: string
  band: ComfortBand
}

export function metricTiles(reading: EnvironmentReading | null): MetricTile[] {
  return [
    {
      key: 'temp',
      label: 'Temp',
      value: formatTemp(reading?.temperature),
      band: temperatureBand(reading?.temperature),
    },
    {
      key: 'humidity',
      label: 'Humidity',
      value:
        reading?.humidity == null || Number.isNaN(reading.humidity)
          ? '—'
          : `${Math.round(reading.humidity)}%`,
      band: humidityBand(reading?.humidity),
    },
    {
      key: 'co2',
      label: 'CO₂',
      value:
        reading?.co2 == null || Number.isNaN(reading.co2) ? '—' : `${Math.round(reading.co2)}`,
      band: co2Band(reading?.co2),
    },
  ]
}

export function roomAdvice(reading: EnvironmentReading | null, roomAlerts: Alert[]): string | null {
  const highest = pickHighestSeverityAlert(roomAlerts)
  if (highest && (highest.severity === 'WARNING' || highest.severity === 'CRITICAL')) {
    return adviceForAlert(highest)
  }
  return adviceForReading(reading) ?? (highest ? adviceForAlert(highest) : null)
}

export function comfortForRoom(
  roomId: string,
  reading: EnvironmentReading | null,
  alerts: Alert[],
) {
  const roomAlerts = alertsForRoom(alerts, roomId)
  const comfort = roomComfortLabel(reading, roomAlerts)
  return {
    ...comfort,
    metrics: metricTiles(reading),
    advice: roomAdvice(reading, roomAlerts),
  }
}
