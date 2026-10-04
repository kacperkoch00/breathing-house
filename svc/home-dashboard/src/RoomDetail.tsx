import { useCallback, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Outlet, useParams } from 'react-router-dom'
import { AppShell } from './AppShell'
import {
  getRoom,
  HomeApiError,
  listAlerts,
  listEnvironmentReadings,
  listOccupancyEvents,
  listSensors,
} from './api/homeApi'
import type { EnvironmentReading, SensorSummary } from './api/types'
import { roomAdvice, roomComfortLabel } from './comfort'
import { formatLocalClock, formatUpdatedLabel } from './overview'
import {
  historyWindowFrom,
  latestReadingPerSensor,
  overviewReading,
  type MetricKey,
} from './roomDetail'
import {
  isEnvironmentSensor,
  type RoomDetailState,
  type RoomOutletContext,
} from './room/roomContext'
import { comfortBadgeClass } from './ui'
import { useAutoRefresh } from './useAutoRefresh'

const initialState: RoomDetailState = {
  room: null,
  latestRoom: null,
  latestBySensor: [],
  selectedLatest: null,
  chartSensorId: null,
  sensors: [],
  alerts: [],
  history: [],
  occupancy: [],
  fetchedAt: null,
  loadState: 'loading',
  errorMessage: null,
}

/** Prefer AIR for the overview chart so the series stays on one device. */
function resolveChartSensorId(
  newestAir: EnvironmentReading | null,
  newestRoom: EnvironmentReading | null,
  envSensors: SensorSummary[],
): string | null {
  const envIds = new Set(envSensors.map((sensor) => sensor.sensorId))
  if (newestAir?.sensorId && envIds.has(newestAir.sensorId)) return newestAir.sensorId
  if (newestRoom?.sensorId && envIds.has(newestRoom.sensorId)) return newestRoom.sensorId
  return envSensors[0]?.sensorId ?? null
}

export function RoomDetail() {
  const { roomId = '' } = useParams<{ roomId: string }>()
  const [state, setState] = useState<RoomDetailState>(initialState)
  const [selectedAlertId, setSelectedAlertId] = useState<number | null>(null)
  const [selectedMetric, setSelectedMetric] = useState<MetricKey>('temperature')
  const [selectedSensorId, setSelectedSensorId] = useState<string | null>(null)
  const selectedSensorIdRef = useRef<string | null>(null)
  const loadGenerationRef = useRef(0)

  const loadDetail = useCallback(
    async (isRefresh: boolean) => {
      if (!roomId) {
        setState({ ...initialState, loadState: 'not-found' })
        return
      }

      const generation = ++loadGenerationRef.current

      if (!isRefresh) {
        setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
      }

      const from = historyWindowFrom()

      try {
        const [room, airPage, roomPage, sensorsResponse, alertsPage, occupancyPage] =
          await Promise.all([
            getRoom(roomId),
            listEnvironmentReadings(roomId, { limit: 1, sensorType: 'AIR' }),
            listEnvironmentReadings(roomId, { limit: 1, sensorType: 'ROOM' }),
            listSensors(),
            listAlerts({ status: 'ACTIVE', roomId, limit: 20 }),
            listOccupancyEvents(roomId, { from, limit: 50 }),
          ])

        if (generation !== loadGenerationRef.current) return

        const roomSensors = sensorsResponse.sensors.filter((sensor) => sensor.roomId === roomId)
        const newestAir = airPage.items[0] ?? null
        const newestRoom = roomPage.items[0] ?? null
        const envSensors = roomSensors.filter(isEnvironmentSensor)
        const envIds = new Set(envSensors.map((sensor) => sensor.sensorId))
        const chartSensorId = resolveChartSensorId(newestAir, newestRoom, envSensors)

        let selected = selectedSensorIdRef.current
        // null = overview. Drop a selection if that sensor left the room.
        if (selected != null && !envIds.has(selected)) {
          selected = null
          selectedSensorIdRef.current = null
          setSelectedSensorId(null)
        }

        const [recentPage, historyPage, selectedLatestPage] = await Promise.all([
          listEnvironmentReadings(roomId, { limit: 50 }),
          // Overview: all sensors (merged/averaged in the chart). Selected: one sensor.
          selected
            ? listEnvironmentReadings(roomId, { from, limit: 100, sensorId: selected })
            : listEnvironmentReadings(roomId, { from, limit: 200 }),
          selected
            ? listEnvironmentReadings(roomId, { limit: 1, sensorId: selected })
            : Promise.resolve({ items: [] as EnvironmentReading[] }),
        ])

        if (generation !== loadGenerationRef.current) return

        const latestBySensor = latestReadingPerSensor(recentPage.items, envIds)
        const selectedLatest = selectedLatestPage.items[0] ?? null

        setState({
          room,
          latestRoom: newestRoom,
          latestBySensor,
          selectedLatest,
          chartSensorId,
          sensors: roomSensors,
          alerts: alertsPage.items,
          history: historyPage.items,
          occupancy: occupancyPage.items,
          fetchedAt: new Date(),
          loadState: 'ready',
          errorMessage: null,
        })
      } catch (error) {
        if (generation !== loadGenerationRef.current) return
        if (error instanceof HomeApiError && error.status === 404) {
          setState({ ...initialState, loadState: 'not-found' })
          return
        }
        const message = error instanceof Error ? error.message : 'Failed to load room'
        setState((prev) => ({
          ...prev,
          loadState: prev.fetchedAt ? 'ready' : 'error',
          errorMessage: message,
        }))
      }
    },
    [roomId],
  )

  const { refreshing, refresh, clock } = useAutoRefresh(loadDetail, {
    enabled: Boolean(roomId),
  })

  const selectSensor = (sensorId: string | null) => {
    if (sensorId === selectedSensorIdRef.current) return
    selectedSensorIdRef.current = sensorId
    setSelectedSensorId(sensorId)
    void loadDetail(true)
  }

  const comfortReading =
    selectedSensorId == null
      ? overviewReading(state.latestBySensor)
      : state.selectedLatest
  const comfort = roomComfortLabel(comfortReading, state.alerts)
  const advice = roomAdvice(comfortReading, state.alerts)
  const updatedLabel = refreshing
    ? 'Updating…'
    : state.fetchedAt
      ? formatUpdatedLabel(null, state.fetchedAt, clock)
      : 'Waiting for data'
  const loadingInitial = state.loadState === 'loading' && !state.fetchedAt
  const sensorNames = useMemo(
    () =>
      Object.fromEntries(state.sensors.map((sensor) => [sensor.sensorId, sensor.displayName])),
    [state.sensors],
  )
  const environmentSensors = useMemo(
    () => state.sensors.filter(isEnvironmentSensor),
    [state.sensors],
  )

  const outletContext: RoomOutletContext = {
    state,
    selectedSensorId,
    selectSensor,
    selectedMetric,
    setSelectedMetric,
    selectedAlertId,
    setSelectedAlertId,
    sensorNames,
    environmentSensors,
  }

  return (
    <AppShell
      alertCount={state.alerts.length}
      clockLabel={formatLocalClock(clock)}
      footerLeft="Room detail"
    >
      <div className="mb-4">
        <Link to="/" className="btn btn-ghost btn-sm font-mono">
          ← All rooms
        </Link>
      </div>

      {loadingInitial && (
        <section className="panel p-6">
          <p className="text-primary mb-2 font-mono text-[11px] tracking-[0.16em] uppercase">
            Room / {formatLocalClock(clock)}
          </p>
          <h1 className="flex items-center gap-3 text-3xl font-semibold">
            <span className="loading loading-spinner loading-md text-primary" />
            Loading room…
          </h1>
        </section>
      )}

      {state.loadState === 'not-found' && (
        <section className="panel p-6">
          <p className="text-base-content/45 mb-2 font-mono text-[11px] tracking-[0.16em] uppercase">
            Room
          </p>
          <h1 className="text-3xl font-semibold">Room not found</h1>
          <Link to="/" className="btn btn-sm mt-4">
            ← Back to overview
          </Link>
        </section>
      )}

      {state.loadState === 'error' && !state.fetchedAt && (
        <section className="panel p-6">
          <p className="text-base-content/45 mb-2 font-mono text-[11px] tracking-[0.16em] uppercase">
            Room
          </p>
          <h1 className="text-3xl font-semibold">Could not load room</h1>
          <p className="text-base-content/70 mt-2 text-sm">
            {state.errorMessage ?? 'Something went wrong.'}
          </p>
          <button type="button" className="btn btn-sm mt-4" onClick={() => void loadDetail(false)}>
            Retry
          </button>
        </section>
      )}

      {state.loadState === 'ready' && state.room && (
        <>
          <section className="mb-5">
            <div className="mb-1 flex flex-wrap items-end justify-between gap-3">
              <p className="text-primary font-mono text-[11px] tracking-[0.16em] uppercase">
                Room comfort
              </p>
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
            <div className="flex flex-wrap items-center gap-3">
              <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">{state.room.name}</h1>
              <span className={comfortBadgeClass(comfort.band)}>{comfort.label}</span>
            </div>
            <p className="text-base-content/60 mt-2 max-w-2xl text-sm">
              {advice ||
                state.room.description ||
                'Latest conditions, alerts, and recent activity for this space.'}
            </p>
            <p className="text-base-content/45 mt-2 text-xs" aria-live="polite">
              {updatedLabel}
            </p>
          </section>

          <div className="tabs tabs-box tabs-sm mb-5" aria-label="Room sections">
            <NavLink
              to="air"
              end
              relative="path"
              className={({ isActive }) => `tab ${isActive ? 'tab-active' : ''}`}
            >
              Air
            </NavLink>
            <NavLink
              to="alerts"
              relative="path"
              className={({ isActive }) => `tab ${isActive ? 'tab-active' : ''}`}
            >
              Alerts
              {state.alerts.length > 0 && (
                <span className="badge badge-warning badge-sm ms-1.5 font-mono">
                  {state.alerts.length}
                </span>
              )}
            </NavLink>
            <NavLink
              to="activity"
              relative="path"
              className={({ isActive }) => `tab ${isActive ? 'tab-active' : ''}`}
            >
              Activity
            </NavLink>
          </div>

          <Outlet context={outletContext} />
        </>
      )}
    </AppShell>
  )
}
