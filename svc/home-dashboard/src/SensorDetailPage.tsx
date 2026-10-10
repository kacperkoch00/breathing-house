import { useCallback, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  getSensor,
  HomeApiError,
  listEnvironmentReadings,
  listOccupancyEvents,
  listRooms,
  unpairSensor,
} from './api/homeApi'
import type {
  EnvironmentReading,
  OccupancyEvent,
  RoomSummary,
  SensorSummary,
} from './api/types'
import { AppShell } from './AppShell'
import { TrendChart } from './charts/TrendChart'
import {
  formatConditionCo2,
  formatConditionHumidity,
  formatConditionTemp,
  formatEventTime,
  formatOccupancyState,
  formatOccupancySummary,
  historyWindowFrom,
  metricSeries,
  seriesStats,
  type MetricKey,
} from './roomDetail'
import { formatLocalClock, formatUpdatedLabel, roomNameMap } from './overview'
import {
  resolveSensorKind,
  sensorIconToneClass,
  sensorKindBadgeClass,
  sensorKindLabel,
  SensorTypeIcon,
} from './sensorVisuals'
import { useAutoRefresh } from './useAutoRefresh'

type LoadState = 'loading' | 'ready' | 'error' | 'not-found'

interface DetailState {
  sensor: SensorSummary | null
  rooms: RoomSummary[]
  latestReading: EnvironmentReading | null
  history: EnvironmentReading[]
  occupancy: OccupancyEvent[]
  fetchedAt: Date | null
  loadState: LoadState
  errorMessage: string | null
}

const initialState: DetailState = {
  sensor: null,
  rooms: [],
  latestReading: null,
  history: [],
  occupancy: [],
  fetchedAt: null,
  loadState: 'loading',
  errorMessage: null,
}

function pickChartMetric(history: EnvironmentReading[]): MetricKey {
  if (metricSeries(history, 'temperature').length > 0) return 'temperature'
  if (metricSeries(history, 'humidity').length > 0) return 'humidity'
  return 'co2'
}

function roomSectionPath(sensor: SensorSummary): string {
  const kind = resolveSensorKind(sensor.types, sensor.sensorId)
  if (kind === 'PRESENCE' || kind === 'OPENING') return 'activity'
  return 'air'
}

export function SensorDetailPage() {
  const { sensorId = '' } = useParams<{ sensorId: string }>()
  const navigate = useNavigate()
  const [state, setState] = useState<DetailState>(initialState)
  const [unpairing, setUnpairing] = useState(false)

  const loadDetail = useCallback(
    async (isRefresh: boolean) => {
      if (!sensorId) {
        setState({ ...initialState, loadState: 'not-found' })
        return
      }

      if (!isRefresh) {
        setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
      }

      const from = historyWindowFrom()

      try {
        const [sensor, roomsResponse] = await Promise.all([getSensor(sensorId), listRooms()])
        const roomId = sensor.roomId
        const isEnv = sensor.types.some((type) => type === 'AIR' || type === 'ROOM')
        const isActivity = sensor.types.some((type) => type === 'PRESENCE' || type === 'OPENING')

        let latestReading: EnvironmentReading | null = null
        let history: EnvironmentReading[] = []
        let occupancy: OccupancyEvent[] = []

        if (roomId && isEnv) {
          const [latestPage, historyPage] = await Promise.all([
            listEnvironmentReadings(roomId, { sensorId, limit: 1 }),
            listEnvironmentReadings(roomId, { sensorId, from, limit: 100 }),
          ])
          latestReading = latestPage.items[0] ?? null
          history = historyPage.items
        }

        if (roomId && isActivity) {
          const occupancyPage = await listOccupancyEvents(roomId, { from, limit: 50 })
          occupancy = occupancyPage.items.filter((event) => event.sensorId === sensorId)
        }

        setState({
          sensor,
          rooms: roomsResponse.rooms,
          latestReading,
          history,
          occupancy,
          fetchedAt: new Date(),
          loadState: 'ready',
          errorMessage: null,
        })
      } catch (error) {
        if (error instanceof HomeApiError && error.status === 404) {
          setState({ ...initialState, loadState: 'not-found' })
          return
        }
        const message = error instanceof Error ? error.message : 'Failed to load sensor'
        setState((prev) => ({
          ...prev,
          loadState: prev.fetchedAt ? 'ready' : 'error',
          errorMessage: message,
        }))
      }
    },
    [sensorId],
  )

  const { refreshing, refresh, clock } = useAutoRefresh(loadDetail, {
    enabled: Boolean(sensorId),
  })

  const handleUnpair = async () => {
    const label = state.sensor?.displayName ?? sensorId
    const confirmed = window.confirm(
      `Remove “${label}” from known devices?\n\nHistory is kept. The board can be paired again later.`,
    )
    if (!confirmed) return
    setUnpairing(true)
    try {
      await unpairSensor(sensorId)
      navigate('/sensors')
    } catch (error) {
      setState((prev) => ({
        ...prev,
        errorMessage: error instanceof Error ? error.message : 'Failed to unpair sensor',
      }))
    } finally {
      setUnpairing(false)
    }
  }

  const roomLabels = useMemo(() => roomNameMap(state.rooms), [state.rooms])
  const sensor = state.sensor
  const kind = sensor ? resolveSensorKind(sensor.types, sensor.sensorId) : null
  const isEnv = kind === 'AIR' || kind === 'ROOM'
  const isActivity = kind === 'PRESENCE' || kind === 'OPENING'
  const chartMetric = pickChartMetric(state.history)
  const chartPoints = metricSeries(state.history, chartMetric)
  const chartStats = seriesStats(chartPoints)
  const latestOccupancy = state.occupancy[0] ?? null
  const loadingInitial = state.loadState === 'loading' && !state.fetchedAt
  const updatedLabel = refreshing
    ? 'Updating…'
    : state.fetchedAt
      ? formatUpdatedLabel(null, state.fetchedAt, clock)
      : 'Waiting for data'

  const chartLabel =
    chartMetric === 'temperature'
      ? 'Temperature'
      : chartMetric === 'humidity'
        ? 'Humidity'
        : 'CO₂'
  const formatChartValue =
    chartMetric === 'temperature'
      ? (value: number) => `${value.toFixed(1)}°`
      : chartMetric === 'humidity'
        ? (value: number) => `${Math.round(value)}%`
        : (value: number) => `${Math.round(value)}`

  return (
    <AppShell clockLabel={formatLocalClock(clock)} footerLeft="Sensor detail">
      <div className="mb-4">
        <Link to="/sensors" className="btn btn-ghost btn-sm font-mono">
          ← All sensors
        </Link>
      </div>

      {loadingInitial && (
        <section className="panel p-6">
          <h1 className="flex items-center gap-3 text-3xl font-semibold">
            <span className="loading loading-spinner loading-md text-primary" />
            Loading sensor…
          </h1>
        </section>
      )}

      {state.loadState === 'not-found' && (
        <section className="panel p-6">
          <h1 className="text-3xl font-semibold">Sensor not found</h1>
          <Link to="/sensors" className="btn btn-sm mt-4">
            ← Back to sensors
          </Link>
        </section>
      )}

      {state.loadState === 'error' && !state.fetchedAt && (
        <section className="panel p-6">
          <h1 className="text-3xl font-semibold">Could not load sensor</h1>
          <p className="text-base-content/70 mt-2 text-sm">
            {state.errorMessage ?? 'Something went wrong.'}
          </p>
          <button type="button" className="btn btn-sm mt-4" onClick={() => void loadDetail(false)}>
            Retry
          </button>
        </section>
      )}

      {state.loadState === 'ready' && sensor && (
        <>
          <section className="mb-5">
            <div className="mb-1 flex flex-wrap items-end justify-between gap-3">
              <p className="text-primary font-mono text-[11px] tracking-[0.16em] uppercase">
                Sensor
              </p>
              <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="btn btn-sm btn-outline"
                onClick={() => void handleUnpair()}
                disabled={unpairing}
              >
                {unpairing ? 'Removing' : 'Unpair'}
              </button>
              <button
                type="button"
                className="btn btn-sm btn-outline"
                onClick={() => void refresh()}
                disabled={refreshing}
                aria-busy={refreshing}
              >
                {refreshing ? (
                  <>
                    <span className="loading loading-spinner loading-xs" />
                    Updating
                  </>
                ) : (
                  'Refresh'
                )}
              </button>
              </div>
            </div>

            <div className="flex flex-wrap items-start gap-4">
              <span
                className={`flex size-16 shrink-0 items-center justify-center rounded-field ${sensorIconToneClass(kind)}`}
              >
                <SensorTypeIcon kind={kind} className="size-9" />
              </span>
              <div className="min-w-0">
                <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
                  {sensor.displayName}
                </h1>
                <p className="text-base-content/45 mt-1 font-mono text-sm">{sensor.sensorId}</p>
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {sensor.types.map((type) => (
                    <span
                      key={type}
                      className={`badge badge-sm font-normal ${sensorKindBadgeClass(type)}`}
                    >
                      {sensorKindLabel(type)}
                    </span>
                  ))}
                </div>
                <p className="text-base-content/60 mt-3 text-sm">
                  {sensor.roomId ? (
                    <>
                      In{' '}
                      <Link
                        to={`/rooms/${encodeURIComponent(sensor.roomId)}/${roomSectionPath(sensor)}`}
                        className="link link-hover text-primary"
                      >
                        {roomLabels[sensor.roomId] ?? sensor.roomId}
                      </Link>
                    </>
                  ) : (
                    'Unassigned'
                  )}
                  {' · '}
                  <Link to="/setup" className="link link-hover text-primary">
                    Manage in Setup
                  </Link>
                </p>
                <p className="text-base-content/45 mt-2 font-mono text-xs" aria-live="polite">
                  {updatedLabel}
                </p>
              </div>
            </div>
          </section>

          {!sensor.roomId && (
            <div role="alert" className="alert alert-info alert-soft mb-5">
              <span className="text-sm">
                No room assigned — history is room-scoped; assign this sensor in Setup to see
                readings and events.
              </span>
            </div>
          )}

          {sensor.roomId && isEnv && (
            <section className="mb-6" aria-label="Latest reading">
              <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
                Latest
              </p>
              <h2 className="mb-3 text-xl font-semibold">Conditions</h2>
              {state.latestReading ? (
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <MetricTile
                    label="Temperature"
                    value={formatConditionTemp(state.latestReading.temperature) ?? '—'}
                  />
                  <MetricTile
                    label="Humidity"
                    value={formatConditionHumidity(state.latestReading.humidity) ?? '—'}
                  />
                  <MetricTile
                    label="CO₂"
                    value={formatConditionCo2(state.latestReading.co2) ?? '—'}
                  />
                  <MetricTile
                    label="Light"
                    value={state.latestReading.lightLevel?.trim() || '—'}
                  />
                </div>
              ) : (
                <div className="panel text-base-content/55 px-4 py-6 text-sm">
                  No environment readings yet for this sensor.
                </div>
              )}
            </section>
          )}

          {sensor.roomId && isActivity && (
            <section className="mb-6" aria-label="Latest activity">
              <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
                Latest
              </p>
              <h2 className="mb-3 text-xl font-semibold">Current state</h2>
              <div className="panel rounded-field border px-4 py-5 sm:max-w-sm">
                <p className="metric-value text-4xl">
                  {latestOccupancy ? formatOccupancyState(latestOccupancy) : '—'}
                </p>
                <p className="text-base-content/50 mt-2 text-sm">
                  {latestOccupancy
                    ? formatEventTime(latestOccupancy.observedAt, { withSeconds: true })
                    : 'No events yet'}
                </p>
              </div>
            </section>
          )}

          {sensor.roomId && isEnv && (
            <section className="mb-6" aria-label="Recent trend">
              <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
                Last 24 hours
              </p>
              <h2 className="mb-3 text-xl font-semibold">{chartLabel} trend</h2>
              <div className="panel p-4 sm:p-5">
                {chartPoints.length > 0 ? (
                  <>
                    {chartStats && (
                      <p className="text-base-content/50 mb-3 font-mono text-xs">
                        24h {formatChartValue(chartStats.min)} – {formatChartValue(chartStats.max)}
                      </p>
                    )}
                    <TrendChart
                      points={chartPoints}
                      label={`${chartLabel} over the last 24 hours`}
                      formatValue={formatChartValue}
                      tone={
                        chartMetric === 'humidity'
                          ? 'secondary'
                          : chartMetric === 'co2'
                            ? 'accent'
                            : 'primary'
                      }
                    />
                  </>
                ) : (
                  <p className="text-base-content/55 text-sm">
                    Need more readings from this sensor to chart a trend.
                  </p>
                )}
              </div>
            </section>
          )}

          {sensor.roomId && isActivity && (
            <section aria-label="Recent activity">
              <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
                Last 24 hours
              </p>
              <h2 className="mb-3 text-xl font-semibold">Timeline</h2>
              {state.occupancy.length === 0 ? (
                <div className="panel text-base-content/55 px-4 py-6 text-sm">
                  No events in the last 24 hours.
                </div>
              ) : (
                <ol className="panel relative ms-2 border-s border-base-300">
                  {state.occupancy.map((event) => {
                    const isPresence = event.eventType === 'PRESENCE'
                    return (
                      <li key={event.id} className="relative flex gap-3 py-3 ps-6 pe-4">
                        <span
                          className={`absolute top-5 -left-[5px] size-2.5 rounded-full ${
                            isPresence ? 'bg-primary' : 'bg-secondary'
                          }`}
                          aria-hidden
                        />
                        <span
                          className={`badge badge-sm shrink-0 font-normal ${
                            isPresence
                              ? 'badge-primary badge-soft'
                              : 'badge-secondary badge-soft'
                          }`}
                        >
                          {isPresence ? 'Presence' : 'Opening'}
                        </span>
                        <p className="min-w-0 flex-1 text-sm font-medium leading-snug">
                          {formatOccupancySummary(event, {
                            [sensor.sensorId]: sensor.displayName,
                          })}
                        </p>
                        <time
                          className="text-base-content/45 shrink-0 font-mono text-[11px]"
                          dateTime={event.observedAt}
                        >
                          {formatEventTime(event.observedAt, { withSeconds: true })}
                        </time>
                      </li>
                    )
                  })}
                </ol>
              )}
            </section>
          )}
        </>
      )}
    </AppShell>
  )
}

function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="panel rounded-field border px-4 py-3">
      <p className="text-base-content/50 font-mono text-[10px] tracking-wide uppercase">{label}</p>
      <p className="metric-value mt-1 text-2xl">{value}</p>
    </div>
  )
}
