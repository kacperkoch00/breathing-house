import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertDrawer } from './AlertDrawer'
import {
  getLatestEnvironmentReading,
  getRoom,
  HomeApiError,
  listAlerts,
  listEnvironmentReadings,
  listOccupancyEvents,
} from './api/homeApi'
import type { Alert, EnvironmentReading, OccupancyEvent, RoomSummary } from './api/types'
import { Sparkline } from './charts/Sparkline'
import { formatLocalClock } from './overview'
import {
  formatConditionCo2,
  formatConditionHumidity,
  formatConditionTemp,
  formatEventTime,
  formatOccupancySummary,
  historyWindowFrom,
  humiditiesForSparkline,
  temperaturesForSparkline,
} from './roomDetail'

type LoadState = 'loading' | 'ready' | 'error' | 'not-found'

interface DetailState {
  room: RoomSummary | null
  latest: EnvironmentReading | null
  alerts: Alert[]
  history: EnvironmentReading[]
  occupancy: OccupancyEvent[]
  loadState: LoadState
  errorMessage: string | null
}

const initialState: DetailState = {
  room: null,
  latest: null,
  alerts: [],
  history: [],
  occupancy: [],
  loadState: 'loading',
  errorMessage: null,
}

async function loadEnvironmentHistory(roomId: string, from: string): Promise<EnvironmentReading[]> {
  const air = await listEnvironmentReadings(roomId, {
    from,
    limit: 100,
    sensorType: 'AIR',
  })
  if (air.items.length > 0) {
    return air.items
  }
  const room = await listEnvironmentReadings(roomId, {
    from,
    limit: 100,
    sensorType: 'ROOM',
  })
  return room.items
}

export function RoomDetail() {
  const { roomId = '' } = useParams<{ roomId: string }>()
  const [state, setState] = useState<DetailState>(initialState)
  const [clock, setClock] = useState(() => new Date())
  const [selectedAlertId, setSelectedAlertId] = useState<number | null>(null)

  const loadDetail = useCallback(async () => {
    if (!roomId) {
      setState({ ...initialState, loadState: 'not-found' })
      return
    }

    setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
    const from = historyWindowFrom()

    try {
      const [room, latest, alertsPage, history, occupancyPage] = await Promise.all([
        getRoom(roomId),
        getLatestEnvironmentReading(roomId),
        listAlerts({ status: 'ACTIVE', roomId, limit: 20 }),
        loadEnvironmentHistory(roomId, from),
        listOccupancyEvents(roomId, { from, limit: 20 }),
      ])

      setState({
        room,
        latest,
        alerts: alertsPage.items,
        history,
        occupancy: occupancyPage.items,
        loadState: 'ready',
        errorMessage: null,
      })
    } catch (error) {
      if (error instanceof HomeApiError && error.status === 404) {
        setState({ ...initialState, loadState: 'not-found' })
        return
      }
      const message = error instanceof Error ? error.message : 'Failed to load room'
      setState((prev) => ({
        ...prev,
        loadState: 'error',
        errorMessage: message,
      }))
    }
  }, [roomId])

  useEffect(() => {
    void loadDetail()
  }, [loadDetail])

  useEffect(() => {
    const clockId = window.setInterval(() => setClock(new Date()), 30_000)
    return () => window.clearInterval(clockId)
  }, [])

  const tempSeries = temperaturesForSparkline(state.history)
  const humiditySeries = humiditiesForSparkline(state.history)

  const tempLabel = formatConditionTemp(state.latest?.temperature)
  const humidityLabel = formatConditionHumidity(state.latest?.humidity)
  const co2Label = formatConditionCo2(state.latest?.co2)
  const lightLabel = state.latest?.lightLevel?.trim() || null
  const hasConditions = Boolean(tempLabel || humidityLabel || co2Label || lightLabel)

  return (
    <main className="shell">
      <nav className="topbar" aria-label="Main navigation">
        <Link className="brand" to="/">
          <span className="brand-mark" aria-hidden="true">BH</span>
          <span>Breathing House</span>
        </Link>
        <Link className="back-link" to="/">
          ← All rooms
        </Link>
      </nav>

      {state.loadState === 'loading' && (
        <section className="detail-hero">
          <p className="eyebrow">Room / {formatLocalClock(clock)}</p>
          <h1>Loading room…</h1>
          <p className="intro">Fetching latest conditions and history.</p>
        </section>
      )}

      {state.loadState === 'not-found' && (
        <section className="detail-hero">
          <p className="eyebrow">Room</p>
          <h1>Room not found</h1>
          <p className="intro">That room id is not in home-api.</p>
          <p className="banner">
            <Link className="back-link" to="/">
              ← Back to overview
            </Link>
          </p>
        </section>
      )}

      {state.loadState === 'error' && (
        <section className="detail-hero">
          <p className="eyebrow">Room</p>
          <h1>Could not load room</h1>
          <p className="intro">{state.errorMessage ?? 'Something went wrong.'}</p>
          <button type="button" className="refresh" onClick={() => void loadDetail()}>
            Retry
          </button>
        </section>
      )}

      {state.loadState === 'ready' && state.room && (
        <>
          <section className="detail-hero">
            <p className="eyebrow">Room / {formatLocalClock(clock)}</p>
            <h1>{state.room.name}</h1>
            {state.room.description ? (
              <p className="intro">{state.room.description}</p>
            ) : (
              <p className="intro">Latest conditions, alerts, and recent activity for this space.</p>
            )}
          </section>

          <section className="detail-body" aria-label="Room detail">
            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Now</p>
                  <h2>Latest conditions</h2>
                </div>
              </div>
              {hasConditions ? (
                <dl className="condition-grid">
                  {tempLabel && (
                    <div>
                      <dt>Temperature</dt>
                      <dd>{tempLabel}</dd>
                    </div>
                  )}
                  {humidityLabel && (
                    <div>
                      <dt>Humidity</dt>
                      <dd>{humidityLabel}</dd>
                    </div>
                  )}
                  {co2Label && (
                    <div>
                      <dt>CO₂</dt>
                      <dd>{co2Label}</dd>
                    </div>
                  )}
                  {lightLabel && (
                    <div>
                      <dt>Light</dt>
                      <dd>{lightLabel}</dd>
                    </div>
                  )}
                </dl>
              ) : (
                <p className="banner empty">No readings yet</p>
              )}
            </div>

            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Attention</p>
                  <h2>Active alerts</h2>
                </div>
              </div>
              {state.alerts.length === 0 ? (
                <p className="banner empty">No active alerts</p>
              ) : (
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
              )}
            </div>

            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Last 24 hours</p>
                  <h2>Environment history</h2>
                </div>
              </div>
              {tempSeries.length >= 2 ? (
                <div className="sparkline-stack">
                  <div className="sparkline-panel">
                    <span className="sparkline-label">Temperature</span>
                    <Sparkline values={tempSeries} label="Temperature over the last 24 hours" />
                  </div>
                  {humiditySeries.length >= 2 && (
                    <div className="sparkline-panel humidity">
                      <span className="sparkline-label">Humidity</span>
                      <Sparkline
                        values={humiditySeries}
                        label="Humidity over the last 24 hours"
                        className="sparkline humidity"
                      />
                    </div>
                  )}
                </div>
              ) : (
                <p className="banner empty">Not enough temperature history to chart yet.</p>
              )}
            </div>

            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Last 24 hours</p>
                  <h2>Recent occupancy</h2>
                </div>
              </div>
              {state.occupancy.length === 0 ? (
                <p className="banner empty">No occupancy events in the last 24 hours</p>
              ) : (
                <ul className="event-list">
                  {state.occupancy.map((event) => (
                    <li key={event.id}>
                      <span className="event-summary">{formatOccupancySummary(event)}</span>
                      <span className="event-type">{event.eventType}</span>
                      <time dateTime={event.observedAt}>{formatEventTime(event.observedAt)}</time>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>
        </>
      )}

      <footer>
        <span>Room detail</span>
        <span>Breathing House · v0.1</span>
      </footer>

      <AlertDrawer alertId={selectedAlertId} onClose={() => setSelectedAlertId(null)} />
    </main>
  )
}
