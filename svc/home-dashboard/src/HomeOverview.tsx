import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertDrawer } from './AlertDrawer'
import {
  getGatewayStatus,
  getLatestEnvironmentReading,
  listActiveAlerts,
  listRooms,
} from './api/homeApi'
import type { Alert, EnvironmentReading, RoomSummary } from './api/types'
import {
  formatLocalClock,
  formatUpdatedLabel,
  mapRoomCard,
  newestObservedAt,
  pickHighestSeverityAlert,
} from './overview'
import { formatEventTime } from './roomDetail'

const POLL_MS = 30_000

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
  const [clock, setClock] = useState(() => new Date())
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

  useEffect(() => {
    void loadOverview(false)
    const pollId = window.setInterval(() => {
      void loadOverview(true)
    }, POLL_MS)
    return () => window.clearInterval(pollId)
  }, [loadOverview])

  useEffect(() => {
    const clockId = window.setInterval(() => setClock(new Date()), 30_000)
    return () => window.clearInterval(clockId)
  }, [])

  const gatewayLabel =
    state.gatewayOnline == null
      ? 'Gateway status unknown'
      : state.gatewayOnline
        ? 'Gateway online'
        : 'Gateway offline'

  const highestAlert = pickHighestSeverityAlert(state.alerts)
  const alertCount = state.alerts.length
  const intro =
    alertCount === 0
      ? 'No active alerts. Latest room readings are below.'
      : alertCount === 1
        ? 'One active alert needs attention.'
        : `${alertCount} active alerts need attention.`

  const roomCards = state.rooms.map((room) =>
    mapRoomCard(room, state.readingsByRoom[room.roomId] ?? null, state.alerts),
  )
  const updatedLabel = formatUpdatedLabel(
    newestObservedAt(Object.values(state.readingsByRoom)),
    state.fetchedAt ?? new Date(),
  )

  return (
    <main className="shell">
      <nav className="topbar" aria-label="Main navigation">
        <Link className="brand" to="/">
          <span className="brand-mark" aria-hidden="true">BH</span>
          <span>Breathing House</span>
        </Link>
        <span className={`status${state.gatewayOnline === false ? ' is-offline' : ''}`}>
          <span className="status-dot" />
          {gatewayLabel}
        </span>
      </nav>

      <section className="hero">
        <div>
          <p className="eyebrow">Home overview / {formatLocalClock(clock)}</p>
          <h1>A quieter read<br />of your home.</h1>
          <p className="intro">{state.loadState === 'loading' ? 'Loading live home data…' : intro}</p>
        </div>
        {highestAlert ? (
          <button
            type="button"
            className="hero-readout is-clickable"
            onClick={() => setSelectedAlertId(highestAlert.id)}
          >
            <span className="readout-label">Active alerts</span>
            <strong>{alertCount}</strong>
            <span>
              {highestAlert.severity} · {highestAlert.message}
            </span>
          </button>
        ) : (
          <div className="hero-readout">
            <span className="readout-label">Active alerts</span>
            <strong>{state.loadState === 'loading' && !state.fetchedAt ? '—' : alertCount}</strong>
            <span>None</span>
          </div>
        )}
      </section>

      {state.alerts.length > 0 && (
        <section className="overview-alerts" aria-labelledby="active-alerts-heading">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Attention</p>
              <h2 id="active-alerts-heading">Active alerts</h2>
            </div>
          </div>
          <ul className="alert-list">
            {state.alerts.map((alert) => (
              <li key={alert.id}>
                <button
                  type="button"
                  className="alert-row-button"
                  onClick={() => setSelectedAlertId(alert.id)}
                >
                  <span className="alert-severity">{alert.severity}</span>
                  <span className="alert-message">{alert.message}</span>
                  <time dateTime={alert.triggeredAt}>{formatEventTime(alert.triggeredAt)}</time>
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="overview" aria-labelledby="overview-heading">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Live spaces</p>
            <h2 id="overview-heading">Room conditions</h2>
          </div>
          <div className="heading-actions">
            <span className="updated">{state.fetchedAt ? updatedLabel : 'Waiting for data'}</span>
            <button
              type="button"
              className="refresh"
              onClick={() => void loadOverview(true)}
              disabled={state.loadState === 'loading' && !state.fetchedAt}
            >
              Refresh
            </button>
          </div>
        </div>

        {state.errorMessage && (
          <p className="banner error" role="alert">
            {state.errorMessage}
          </p>
        )}

        {state.loadState === 'loading' && !state.fetchedAt && (
          <p className="banner">Loading rooms and readings…</p>
        )}

        {state.loadState === 'error' && !state.fetchedAt && (
          <p className="banner">Could not reach home-api. Check that it is running and try Refresh.</p>
        )}

        {state.loadState === 'ready' && roomCards.length === 0 && (
          <p className="banner empty">No rooms yet. Add rooms in home-api to see conditions here.</p>
        )}

        {roomCards.length > 0 && (
          <div className="space-grid">
            {roomCards.map((space) => (
              <Link
                className="space-link"
                to={`/rooms/${space.roomId}`}
                key={space.roomId}
              >
                <article
                  className={`space-card ${space.tone}${space.alerted ? ' is-alerted' : ''}`}
                >
                  <div className="card-topline">
                    <span className="pulse" />
                    {space.topline}
                  </div>
                  <h3>{space.name}</h3>
                  <strong>{space.value}</strong>
                  <p>{space.detail}</p>
                </article>
              </Link>
            ))}
          </div>
        )}
      </section>

      <footer>
        <span>
          {state.gatewayOnline == null
            ? 'Gateway status unknown'
            : state.gatewayOnline
              ? 'Gateway is online'
              : 'Gateway is offline'}
        </span>
        <span>Breathing House · v0.1</span>
      </footer>

      <AlertDrawer alertId={selectedAlertId} onClose={() => setSelectedAlertId(null)} />
    </main>
  )
}
