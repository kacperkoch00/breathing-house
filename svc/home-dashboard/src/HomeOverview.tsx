import { useCallback, useMemo, useState, type CSSProperties } from 'react'
import { Link } from 'react-router-dom'
import { AlertDrawer } from './AlertDrawer'
import { AppShell } from './AppShell'
import {
  getGatewayStatus,
  getLatestEnvironmentReading,
  listActiveAlerts,
  listRooms,
} from './api/homeApi'
import type { Alert, EnvironmentReading, RoomSummary } from './api/types'
import { adviceForAlert, computeHomeHealth } from './comfort'
import {
  formatLocalClock,
  formatUpdatedLabel,
  humanizeAlertMessage,
  mapRoomCard,
  pickHighestSeverityAlert,
  roomNameMap,
} from './overview'
import {
  comfortBadgeClass,
  metricTileClass,
  metricBandClass,
  roomCardBorderClass,
  scoreRingClass,
  severityLabel,
} from './ui'
import { useAutoRefresh } from './useAutoRefresh'

type LoadState = 'loading' | 'ready' | 'error'

interface OverviewState {
  gatewayOnline: boolean | null
  rooms: RoomSummary[]
  readingsByRoom: Record<string, EnvironmentReading | null>
  alerts: Alert[]
  fetchedAt: Date | null
  loadState: LoadState
  errorMessage: string | null
}

const initialState: OverviewState = {
  gatewayOnline: null,
  rooms: [],
  readingsByRoom: {},
  alerts: [],
  fetchedAt: null,
  loadState: 'loading',
  errorMessage: null,
}

export function HomeOverview() {
  const [state, setState] = useState<OverviewState>(initialState)
  const [selectedAlertId, setSelectedAlertId] = useState<number | null>(null)

  const loadOverview = useCallback(async (isRefresh: boolean) => {
    if (!isRefresh) {
      setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
    }

    try {
      const [gateway, roomsResponse, alertsPage] = await Promise.all([
        getGatewayStatus(),
        listRooms(),
        listActiveAlerts(20),
      ])

      const readingEntries = await Promise.all(
        roomsResponse.rooms.map(async (room) => {
          try {
            const reading = await getLatestEnvironmentReading(room.roomId)
            return [room.roomId, reading] as const
          } catch {
            return [room.roomId, null] as const
          }
        }),
      )

      const readingsByRoom: Record<string, EnvironmentReading | null> = {}
      for (const [roomId, reading] of readingEntries) {
        readingsByRoom[roomId] = reading
      }

      setState({
        gatewayOnline: gateway.online,
        rooms: roomsResponse.rooms,
        readingsByRoom,
        alerts: alertsPage.items,
        fetchedAt: new Date(),
        loadState: 'ready',
        errorMessage: null,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load home overview'
      setState((prev) => ({
        ...prev,
        loadState: prev.fetchedAt ? 'ready' : 'error',
        errorMessage: message,
      }))
    }
  }, [])

  const { refreshing, refresh, clock } = useAutoRefresh(loadOverview)

  const highestAlert = pickHighestSeverityAlert(state.alerts)
  const alertCount = state.alerts.length
  const roomLabels = useMemo(() => roomNameMap(state.rooms), [state.rooms])
  const roomCards = state.rooms.map((room) =>
    mapRoomCard(room, state.readingsByRoom[room.roomId] ?? null, state.alerts),
  )
  const updatedLabel = refreshing
    ? 'Updating…'
    : state.fetchedAt
      ? formatUpdatedLabel(null, state.fetchedAt, clock)
      : 'Waiting for data'

  const health = useMemo(
    () => computeHomeHealth(state.readingsByRoom, state.alerts),
    [state.readingsByRoom, state.alerts],
  )

  const loadingInitial = state.loadState === 'loading' && !state.fetchedAt

  return (
    <AppShell
      gatewayOnline={state.gatewayOnline}
      alertCount={alertCount}
      clockLabel={formatLocalClock(clock)}
      footerLeft="Home overview"
    >
      <div className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-primary mb-1 font-mono text-[11px] font-semibold tracking-[0.18em] uppercase">
            Home health
          </p>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            A quieter read of your home
          </h1>
        </div>
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

      <section className="panel mb-5 p-5">
        <div className="flex flex-col items-stretch gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-5">
            {loadingInitial ? (
              <div className="bg-base-200 flex size-28 items-center justify-center rounded-full">
                <span className="loading loading-spinner loading-md text-primary" />
              </div>
            ) : (
              <div className="flex flex-col items-center gap-1">
                <div
                  className={`radial-progress ${scoreRingClass(health.band)}`}
                  style={
                    {
                      '--value': health.score,
                      '--size': '7rem',
                      '--thickness': '0.55rem',
                    } as CSSProperties
                  }
                  aria-valuenow={health.score}
                  role="progressbar"
                  aria-label={`Home health score ${health.score}`}
                >
                  <span className="metric-value text-2xl text-base-content">{health.score}</span>
                </div>
                <span className="text-base-content/45 font-mono text-[10px] tracking-[0.14em] uppercase">
                  Health
                </span>
              </div>
            )}
            <div className="min-w-0">
              <div className="mb-2 flex flex-wrap items-center gap-2">
                <span className={comfortBadgeClass(health.band)}>
                  {loadingInitial ? '…' : health.label}
                </span>
                <span className="text-base-content/45 font-mono text-xs" aria-live="polite">
                  {updatedLabel}
                </span>
              </div>
              <p className="text-lg font-medium">
                {loadingInitial ? 'Reading live conditions…' : health.advice}
              </p>
              <p className="text-base-content/50 mt-1 font-mono text-xs">
                {alertCount === 0
                  ? `${state.rooms.length} rooms monitored`
                  : `${alertCount} active alert${alertCount === 1 ? '' : 's'}`}
              </p>
              {alertCount > 0 && (
                <Link to="/alerts" className="link link-hover text-primary mt-2 inline-block text-sm">
                  All alerts →
                </Link>
              )}
            </div>
          </div>

          {highestAlert && (
            <button
              type="button"
              className="btn btn-warning btn-sm sm:self-end"
              onClick={() => setSelectedAlertId(highestAlert.id)}
            >
              View alert
            </button>
          )}
        </div>
      </section>

      {highestAlert && (
        <div
          role="alert"
          className={`alert alert-soft mb-5 ${
            highestAlert.severity === 'CRITICAL'
              ? 'alert-error'
              : highestAlert.severity === 'WARNING'
                ? 'alert-warning'
                : 'alert-info'
          }`}
        >
          <div className="w-full min-w-0">
            <p className="text-xs tracking-[0.14em] uppercase opacity-70">
              {severityLabel(highestAlert.severity)}
              {roomLabels[highestAlert.roomId]
                ? ` · ${roomLabels[highestAlert.roomId]}`
                : ''}
            </p>
            <p className="mt-1 font-medium">
              {humanizeAlertMessage(highestAlert, { rooms: roomLabels })}
            </p>
            <p className="mt-1 text-sm opacity-80">{adviceForAlert(highestAlert)}</p>
          </div>
        </div>
      )}

      {state.errorMessage && (
        <div role="alert" className="alert alert-error alert-soft mb-4">
          <span className="font-mono text-sm">{state.errorMessage}</span>
        </div>
      )}

      {state.loadState === 'error' && !state.fetchedAt && (
        <div role="alert" className="alert alert-warning alert-soft mb-4">
          <span>Could not reach home-api. Check that it is running and try Refresh.</span>
        </div>
      )}

      <section aria-labelledby="overview-heading">
        <div className="mb-3">
          <p className="text-base-content/45 font-mono text-[11px] tracking-[0.16em] uppercase">
            Rooms
          </p>
          <h2 id="overview-heading" className="text-xl font-semibold">
            Comfort by space
          </h2>
        </div>

        {state.loadState === 'ready' && roomCards.length === 0 && (
          <div role="alert" className="alert alert-info alert-soft">
            <span>No rooms yet. Add rooms in Setup to see conditions here.</span>
          </div>
        )}

        {loadingInitial && (
          <div className="panel flex items-center gap-3 px-4 py-8">
            <span className="loading loading-spinner loading-md text-primary" />
            <span className="text-base-content/70 font-mono text-sm">Loading rooms…</span>
          </div>
        )}

        {roomCards.length > 0 && (
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
            {roomCards.map((space) => (
              <Link key={space.roomId} to={`/rooms/${space.roomId}`} className="block">
                <article
                  className={`panel bg-base-100 hover:border-primary/40 h-full transition ${roomCardBorderClass(space.comfortBand, space.alerted)}`}
                >
                  <div className="flex h-full flex-col gap-3 p-4">
                    <div className="flex items-start justify-between gap-2">
                      <h3 className="text-lg font-medium">{space.name}</h3>
                      <span className={comfortBadgeClass(space.comfortBand)}>
                        {space.comfortLabel}
                      </span>
                    </div>

                    <div className="grid grid-cols-3 gap-2">
                      {space.metrics.map((metric) => (
                        <div
                          key={metric.key}
                          className={`rounded-field border px-2 py-2 ${metricTileClass(metric.band)}`}
                        >
                          <p className="text-base-content/50 font-mono text-[10px] tracking-wide uppercase">
                            {metric.label}
                          </p>
                          <p className={`metric-value text-base ${metricBandClass(metric.band)}`}>
                            {metric.value}
                            {metric.key === 'co2' && metric.value !== '—' ? (
                              <span className="text-[10px] opacity-70"> ppm</span>
                            ) : null}
                          </p>
                        </div>
                      ))}
                    </div>

                    {space.advice && (
                      <p className="text-base-content/65 text-sm leading-snug">{space.advice}</p>
                    )}
                  </div>
                </article>
              </Link>
            ))}
          </div>
        )}
      </section>

      <AlertDrawer
        alertId={selectedAlertId}
        roomNames={roomLabels}
        onClose={() => setSelectedAlertId(null)}
      />
    </AppShell>
  )
}
