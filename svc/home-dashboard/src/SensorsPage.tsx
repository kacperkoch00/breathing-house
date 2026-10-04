import { useCallback, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { listRooms, listSensors } from './api/homeApi'
import type { RoomSummary, SensorSummary } from './api/types'
import { AppShell } from './AppShell'
import { formatLocalClock, formatUpdatedLabel, roomNameMap } from './overview'
import {
  primarySensorKind,
  sensorIconToneClass,
  sensorKindBadgeClass,
  sensorKindLabel,
  SensorTypeIcon,
  type KnownSensorKind,
} from './sensorVisuals'
import { useAutoRefresh } from './useAutoRefresh'

type LoadState = 'loading' | 'ready' | 'error'
type SensorFilter = 'ALL' | KnownSensorKind | 'UNASSIGNED'

interface SensorsState {
  sensors: SensorSummary[]
  rooms: RoomSummary[]
  fetchedAt: Date | null
  loadState: LoadState
  errorMessage: string | null
}

const initialState: SensorsState = {
  sensors: [],
  rooms: [],
  fetchedAt: null,
  loadState: 'loading',
  errorMessage: null,
}

const FILTERS: { id: SensorFilter; label: string }[] = [
  { id: 'ALL', label: 'All' },
  { id: 'AIR', label: 'Air' },
  { id: 'ROOM', label: 'Room' },
  { id: 'PRESENCE', label: 'Presence' },
  { id: 'OPENING', label: 'Opening' },
  { id: 'UNASSIGNED', label: 'Unassigned' },
]

function matchesFilter(sensor: SensorSummary, filter: SensorFilter): boolean {
  if (filter === 'ALL') return true
  if (filter === 'UNASSIGNED') return sensor.roomId == null
  return sensor.types.includes(filter)
}

export function SensorsPage() {
  const [state, setState] = useState<SensorsState>(initialState)
  const [filter, setFilter] = useState<SensorFilter>('ALL')

  const loadSensors = useCallback(async (isRefresh: boolean) => {
    if (!isRefresh) {
      setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
    }

    try {
      const [sensorsResponse, roomsResponse] = await Promise.all([listSensors(), listRooms()])
      setState({
        sensors: sensorsResponse.sensors,
        rooms: roomsResponse.rooms,
        fetchedAt: new Date(),
        loadState: 'ready',
        errorMessage: null,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load sensors'
      setState((prev) => ({
        ...prev,
        loadState: prev.fetchedAt ? 'ready' : 'error',
        errorMessage: message,
      }))
    }
  }, [])

  const { refreshing, refresh, clock } = useAutoRefresh(loadSensors)
  const roomLabels = useMemo(() => roomNameMap(state.rooms), [state.rooms])
  const filtered = useMemo(
    () => state.sensors.filter((sensor) => matchesFilter(sensor, filter)),
    [state.sensors, filter],
  )
  const loadingInitial = state.loadState === 'loading' && !state.fetchedAt
  const updatedLabel = refreshing
    ? 'Updating…'
    : state.fetchedAt
      ? formatUpdatedLabel(null, state.fetchedAt, clock)
      : 'Waiting for data'

  return (
    <AppShell clockLabel={formatLocalClock(clock)} footerLeft="Sensors">
      <section className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-primary mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
            Devices
          </p>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Sensors</h1>
          <p className="text-base-content/60 mt-2 max-w-2xl text-sm">
            Every discovered device in the home. Open one for latest readings and recent history.
          </p>
          <p className="text-base-content/45 mt-2 font-mono text-xs" aria-live="polite">
            {updatedLabel}
          </p>
        </div>
        <div className="flex shrink-0 flex-wrap gap-2">
          <button
            type="button"
            className="btn btn-sm btn-outline"
            title="Scan the home via the gateway for new sensors"
            // TODO: trigger gateway sensor discovery scan
            onClick={() => {}}
          >
            Scan
          </button>
          <button
            type="button"
            className="btn btn-sm btn-outline"
            onClick={() => void refresh()}
            disabled={refreshing || loadingInitial}
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
      </section>

      <div role="tablist" className="tabs tabs-box tabs-sm mb-5 flex-wrap">
        {FILTERS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            className={`tab ${filter === item.id ? 'tab-active' : ''}`}
            aria-selected={filter === item.id}
            onClick={() => setFilter(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>

      {state.errorMessage && (
        <div role="alert" className="alert alert-error alert-soft mb-4">
          <span className="text-sm">{state.errorMessage}</span>
        </div>
      )}

      {loadingInitial && (
        <div className="panel mb-4 flex items-center gap-3 px-4 py-8">
          <span className="loading loading-spinner loading-md text-primary" />
          <span className="font-mono text-sm">Loading sensors…</span>
        </div>
      )}

      {state.loadState === 'error' && !state.fetchedAt && (
        <div role="alert" className="alert alert-warning alert-soft mb-4">
          <span>Could not reach home-api.</span>
          <button type="button" className="btn btn-sm" onClick={() => void loadSensors(false)}>
            Retry
          </button>
        </div>
      )}

      {state.loadState === 'ready' && state.sensors.length === 0 && (
        <div className="panel px-4 py-8">
          <p className="font-medium">No sensors yet</p>
          <p className="text-base-content/60 mt-1 text-sm">
            Devices appear here after they send data through the gateway.
          </p>
        </div>
      )}

      {state.loadState === 'ready' && state.sensors.length > 0 && filtered.length === 0 && (
        <div className="panel px-4 py-8">
          <p className="font-medium">Nothing matches this filter</p>
          <p className="text-base-content/60 mt-1 text-sm">Try All, or another sensor type.</p>
        </div>
      )}

      {filtered.length > 0 && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((sensor) => {
            const kind = primarySensorKind(sensor.types)
            const roomLabel =
              sensor.roomId == null
                ? 'Unassigned'
                : (roomLabels[sensor.roomId] ?? sensor.roomId)

            return (
              <Link
                key={sensor.sensorId}
                to={`/sensors/${encodeURIComponent(sensor.sensorId)}`}
                className="panel hover:border-primary/40 block transition"
              >
                <article className="flex h-full flex-col gap-3 p-4">
                  <div className="flex items-start gap-3">
                    <span
                      className={`flex size-12 shrink-0 items-center justify-center rounded-field ${sensorIconToneClass(kind)}`}
                    >
                      <SensorTypeIcon kind={kind} className="size-7" />
                    </span>
                    <div className="min-w-0">
                      <h2 className="truncate text-lg font-medium">{sensor.displayName}</h2>
                      <p className="text-base-content/45 font-mono text-[11px]">{sensor.sensorId}</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {sensor.types.length === 0 ? (
                      <span className="badge badge-ghost badge-sm">Unknown</span>
                    ) : (
                      sensor.types.map((type) => (
                        <span
                          key={type}
                          className={`badge badge-sm font-normal ${sensorKindBadgeClass(type)}`}
                        >
                          {sensorKindLabel(type)}
                        </span>
                      ))
                    )}
                  </div>
                  <p className="text-base-content/55 mt-auto text-sm">{roomLabel}</p>
                </article>
              </Link>
            )
          })}
        </div>
      )}
    </AppShell>
  )
}
