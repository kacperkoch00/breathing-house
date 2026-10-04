import { useMemo } from 'react'
import type { EnvironmentReading, SensorSummary } from '../api/types'
import { TrendChart } from '../charts/TrendChart'
import { co2Band, humidityBand, temperatureBand, type ComfortBand } from '../comfort'
import {
  formatConditionCo2,
  formatConditionHumidity,
  formatConditionTemp,
  latestLightReading,
  latestMetricReading,
  metricSeries,
  overviewMetricSeries,
  seriesStats,
  type MetricKey,
} from '../roomDetail'
import { metricBandClass, metricTileClass } from '../ui'
import { useRoomOutlet } from './roomContext'

interface MetricCardModel {
  key: MetricKey
  label: string
  currentLabel: string
  band: ComfortBand
  formatValue: (value: number) => string
  points: ReturnType<typeof metricSeries>
  stats: ReturnType<typeof seriesStats>
  source: string
}

function sensorLabel(
  sensors: { sensorId: string; displayName: string }[],
  sensorId: string | null | undefined,
): string | null {
  if (!sensorId) return null
  return sensors.find((sensor) => sensor.sensorId === sensorId)?.displayName ?? sensorId
}

function selectedSensor(sensors: SensorSummary[], sensorId: string | null): SensorSummary | null {
  if (!sensorId) return null
  return sensors.find((sensor) => sensor.sensorId === sensorId) ?? null
}

function sensorProvides(
  sensor: SensorSummary | null,
  reading: EnvironmentReading | null,
  metric: MetricKey | 'light',
): boolean {
  if (!sensor) return true
  const types = sensor.types
  const isAir = types.includes('AIR')
  const isRoom = types.includes('ROOM')

  if (metric === 'light') return isRoom
  if (metric === 'humidity' || metric === 'co2') return isAir
  // temperature: both air and room
  if (metric === 'temperature') return isAir || isRoom
  return Boolean(reading)
}

export function RoomAirView() {
  const {
    state,
    selectedSensorId,
    selectSensor,
    selectedMetric,
    setSelectedMetric,
    environmentSensors,
  } = useRoomOutlet()

  const overviewMode = selectedSensorId == null
  const multiSensor = environmentSensors.length > 1
  const activeSensor = selectedSensor(environmentSensors, selectedSensorId)
  const primaryReading = overviewMode ? null : state.selectedLatest
  const chartHistory = state.history

  const overviewTempReading = latestMetricReading(state.latestBySensor, 'temperature')
  const overviewHumidityReading = latestMetricReading(state.latestBySensor, 'humidity')
  const overviewCo2Reading = latestMetricReading(state.latestBySensor, 'co2')
  const overviewLight = latestLightReading(state.latestBySensor)

  const lightLabel = overviewMode
    ? overviewLight?.lightLevel?.trim() || null
    : sensorProvides(activeSensor, primaryReading, 'light')
      ? primaryReading?.lightLevel?.trim() || null
      : null
  const lightSourceId = overviewMode
    ? overviewLight?.sensorId
    : primaryReading?.sensorId ?? null

  const metricCards = useMemo((): MetricCardModel[] => {
    const chartSource =
      sensorLabel(state.sensors, selectedSensorId) ??
      (overviewMode ? 'All sensors' : 'Selected sensor')

    const seriesFor = (metric: MetricKey) =>
      overviewMode
        ? overviewMetricSeries(chartHistory, metric)
        : metricSeries(chartHistory, metric)

    const overviewSource = (reading: EnvironmentReading | null) => {
      const name = sensorLabel(state.sensors, reading?.sensorId)
      return name ? `Latest · ${name}` : 'Latest reading'
    }

    const tempValue = overviewMode
      ? overviewTempReading?.temperature
      : primaryReading?.temperature
    const humidityValue = overviewMode
      ? overviewHumidityReading?.humidity
      : primaryReading?.humidity
    const co2Value = overviewMode ? overviewCo2Reading?.co2 : primaryReading?.co2

    const cards: MetricCardModel[] = [
      {
        key: 'temperature',
        label: 'Temperature',
        currentLabel: formatConditionTemp(tempValue) ?? '—',
        band: temperatureBand(tempValue),
        formatValue: (value) => `${value.toFixed(1)}°`,
        points: seriesFor('temperature'),
        stats: seriesStats(seriesFor('temperature')),
        source: overviewMode ? overviewSource(overviewTempReading) : chartSource,
      },
      {
        key: 'humidity',
        label: 'Humidity',
        currentLabel: formatConditionHumidity(humidityValue) ?? '—',
        band: humidityBand(humidityValue),
        formatValue: (value) => `${Math.round(value)}%`,
        points: seriesFor('humidity'),
        stats: seriesStats(seriesFor('humidity')),
        source: overviewMode ? overviewSource(overviewHumidityReading) : chartSource,
      },
      {
        key: 'co2',
        label: 'CO₂',
        currentLabel: formatConditionCo2(co2Value) ?? '—',
        band: co2Band(co2Value),
        formatValue: (value) => `${Math.round(value)}`,
        points: seriesFor('co2'),
        stats: seriesStats(seriesFor('co2')),
        source: overviewMode ? overviewSource(overviewCo2Reading) : chartSource,
      },
    ]

    if (overviewMode) return cards
    return cards.filter((card) => sensorProvides(activeSensor, primaryReading, card.key))
  }, [
    overviewMode,
    overviewTempReading,
    overviewHumidityReading,
    overviewCo2Reading,
    primaryReading,
    activeSensor,
    selectedSensorId,
    state.sensors,
    chartHistory,
  ])

  const displayCard =
    metricCards.find((card) => card.key === selectedMetric) ?? metricCards[0] ?? null

  const hasConditions =
    metricCards.some((card) => card.currentLabel !== '—') || Boolean(lightLabel)

  return (
    <div aria-label="Air conditions">
      {environmentSensors.length > 0 && (
        <div className="mb-4 flex flex-wrap gap-2" role={multiSensor ? 'group' : undefined}>
          {multiSensor && (
            <button
              type="button"
              className={`badge badge-sm gap-1 border-0 font-normal ${
                overviewMode ? 'badge-primary' : 'badge-ghost cursor-pointer'
              }`}
              aria-pressed={overviewMode}
              onClick={() => selectSensor(null)}
            >
              <span className="font-mono text-[10px] tracking-wide uppercase opacity-60">
                All
              </span>
              Overview
            </button>
          )}
          {environmentSensors.map((sensor) => {
            const selected = sensor.sensorId === selectedSensorId
            const chipClass = `badge badge-sm gap-1 font-normal ${
              selected ? 'badge-primary' : 'badge-ghost'
            }`
            const content = (
              <>
                <span className="font-mono text-[10px] tracking-wide uppercase opacity-60">
                  {sensor.types.filter((type) => type === 'AIR' || type === 'ROOM').join(' · ') ||
                    'Sensor'}
                </span>
                {sensor.displayName}
              </>
            )

            if (!multiSensor) {
              return (
                <span key={sensor.sensorId} className={chipClass} title={sensor.sensorId}>
                  {content}
                </span>
              )
            }

            return (
              <button
                key={sensor.sensorId}
                type="button"
                className={`${chipClass} cursor-pointer border-0`}
                title={sensor.sensorId}
                aria-pressed={selected}
                onClick={() => selectSensor(sensor.sensorId)}
              >
                {content}
              </button>
            )
          })}
        </div>
      )}

      <p className="text-base-content/45 mb-4 text-xs">
        {overviewMode
          ? multiSensor
            ? 'Room overview · latest value per metric across sensors'
            : sensorLabel(state.sensors, state.chartSensorId)
              ? `Viewing ${sensorLabel(state.sensors, state.chartSensorId)}`
              : 'Room overview'
          : `Viewing ${sensorLabel(state.sensors, selectedSensorId) ?? 'sensor'} · only this sensor’s fields`}
      </p>

      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
            Now · last 24 hours
          </p>
          <h2 className="text-xl font-semibold">Conditions</h2>
        </div>
        {metricCards.length > 0 && (
          <div role="tablist" className="tabs tabs-box tabs-sm">
            {metricCards.map((card) => (
              <button
                key={card.key}
                type="button"
                role="tab"
                className={`tab ${
                  (displayCard?.key ?? selectedMetric) === card.key ? 'tab-active' : ''
                }`}
                aria-selected={(displayCard?.key ?? selectedMetric) === card.key}
                onClick={() => setSelectedMetric(card.key)}
              >
                {card.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {hasConditions && displayCard ? (
        <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-1">
            {metricCards.map((card) => (
              <button
                key={card.key}
                type="button"
                onClick={() => setSelectedMetric(card.key)}
                className={`panel rounded-field border px-4 py-3 text-left transition ${metricTileClass(
                  card.band,
                )} ${
                  displayCard.key === card.key
                    ? 'ring-primary/50 ring-2'
                    : 'hover:border-base-content/20'
                }`}
              >
                <p className="text-base-content/50 font-mono text-[10px] tracking-wide uppercase">
                  {card.label}
                </p>
                <p className={`metric-value mt-1 text-3xl ${metricBandClass(card.band)}`}>
                  {card.currentLabel}
                </p>
                {card.stats && (
                  <p className="text-base-content/45 mt-2 font-mono text-[11px]">
                    24h {card.formatValue(card.stats.min)} – {card.formatValue(card.stats.max)}
                  </p>
                )}
              </button>
            ))}
            {lightLabel && (
              <div className="panel border-base-300 bg-base-200/60 rounded-field border px-4 py-3">
                <p className="text-base-content/50 font-mono text-[10px] tracking-wide uppercase">
                  Light
                </p>
                <p className="metric-value mt-1 text-3xl">{lightLabel}</p>
                <p className="text-base-content/45 mt-2 text-[11px]">
                  {overviewMode ? 'Latest' : 'From this sensor'}
                  {sensorLabel(state.sensors, lightSourceId)
                    ? ` · ${sensorLabel(state.sensors, lightSourceId)}`
                    : ''}
                </p>
              </div>
            )}
          </div>

          <div className="panel p-4 sm:p-5">
            <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-base-content/45 font-mono text-[11px] tracking-[0.14em] uppercase">
                  {displayCard.label} trend
                </p>
                <p className={`metric-value mt-1 text-4xl ${metricBandClass(displayCard.band)}`}>
                  {displayCard.currentLabel}
                </p>
                <p className="text-base-content/50 mt-1 text-sm">Source: {displayCard.source}</p>
              </div>
              {displayCard.stats && (
                <div className="text-right font-mono text-xs">
                  <p className="text-base-content/45 uppercase tracking-wide">24h range</p>
                  <p className="mt-1 text-sm">
                    {displayCard.formatValue(displayCard.stats.min)} –{' '}
                    {displayCard.formatValue(displayCard.stats.max)}
                  </p>
                </div>
              )}
            </div>
            <TrendChart
              points={displayCard.points}
              label={`${displayCard.label} over the last 24 hours`}
              formatValue={displayCard.formatValue}
              tone={
                displayCard.key === 'humidity'
                  ? 'secondary'
                  : displayCard.key === 'co2'
                    ? 'accent'
                    : 'primary'
              }
            />
          </div>
        </div>
      ) : (
        <div role="alert" className="alert alert-info alert-soft">
          <span className="text-sm">
            {environmentSensors.length === 0
              ? 'No air or room sensors assigned to this space.'
              : 'No readings yet for this room.'}
          </span>
        </div>
      )}
    </div>
  )
}
