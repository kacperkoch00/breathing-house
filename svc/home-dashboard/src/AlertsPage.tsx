import { useCallback, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { AlertDrawer } from './AlertDrawer'
import { AppShell } from './AppShell'
import { listAlerts, listRooms } from './api/homeApi'
import type { Alert, AlertStatus, RoomSummary } from './api/types'
import { adviceForAlert } from './comfort'
import { formatLocalClock, formatUpdatedLabel, humanizeAlertMessage, roomNameMap } from './overview'
import { formatEventTime } from './roomDetail'
import { severityBadgeClass, severityLabel } from './ui'
import { useAutoRefresh } from './useAutoRefresh'

const PAGE_SIZE = 50

type LoadState = 'loading' | 'ready' | 'error'
type StatusFilter = 'ACTIVE' | 'ALL'

interface AlertsState {
  rooms: RoomSummary[]
  alerts: Alert[]
  hasMore: boolean
  activeCount: number
  fetchedAt: Date | null
  loadState: LoadState
  errorMessage: string | null
}

const initialState: AlertsState = {
  rooms: [],
  alerts: [],
  hasMore: false,
  activeCount: 0,
  fetchedAt: null,
  loadState: 'loading',
  errorMessage: null,
}

function statusForFilter(filter: StatusFilter): AlertStatus | undefined {
  return filter === 'ACTIVE' ? 'ACTIVE' : undefined
}

export function AlertsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const roomIdFilter = searchParams.get('roomId')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ACTIVE')
  const [state, setState] = useState<AlertsState>(initialState)
  const [selectedAlertId, setSelectedAlertId] = useState<number | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)

  const loadAlerts = useCallback(
    async (isRefresh: boolean) => {
      if (!isRefresh) {
        setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
      }

      try {
        const status = statusForFilter(statusFilter)
        const [roomsResponse, alertsPage, activePage] = await Promise.all([
          listRooms(),
          listAlerts({
            status,
            roomId: roomIdFilter ?? undefined,
            limit: PAGE_SIZE,
            offset: 0,
          }),
          listAlerts({ status: 'ACTIVE', limit: PAGE_SIZE, offset: 0 }),
        ])

        setState({
          rooms: roomsResponse.rooms,
          alerts: alertsPage.items,
          hasMore: alertsPage.hasMore,
          activeCount: activePage.hasMore
            ? activePage.items.length + 1
            : activePage.items.length,
          fetchedAt: new Date(),
          loadState: 'ready',
          errorMessage: null,
        })
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Failed to load alerts'
        setState((prev) => ({
          ...prev,
          loadState: prev.fetchedAt ? 'ready' : 'error',
          errorMessage: message,
        }))
      }
    },
    [roomIdFilter, statusFilter],
  )

  const { refreshing, refresh, clock } = useAutoRefresh(loadAlerts)

  const loadMore = async () => {
    if (loadingMore || !state.hasMore) return
    setLoadingMore(true)
    try {
      const page = await listAlerts({
        status: statusForFilter(statusFilter),
        roomId: roomIdFilter ?? undefined,
        limit: PAGE_SIZE,
        offset: state.alerts.length,
      })
      setState((prev) => ({
        ...prev,
        alerts: [...prev.alerts, ...page.items],
        hasMore: page.hasMore,
      }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load more alerts'
      setState((prev) => ({ ...prev, errorMessage: message }))
    } finally {
      setLoadingMore(false)
    }
  }

  const roomLabels = useMemo(() => roomNameMap(state.rooms), [state.rooms])
  const loadingInitial = state.loadState === 'loading' && !state.fetchedAt
  const updatedLabel = refreshing
    ? 'Updating…'
    : state.fetchedAt
      ? formatUpdatedLabel(null, state.fetchedAt, clock)
      : 'Waiting for data'

  const setRoomFilter = (roomId: string) => {
    const next = new URLSearchParams(searchParams)
    if (roomId) {
      next.set('roomId', roomId)
    } else {
      next.delete('roomId')
    }
    setSearchParams(next, { replace: true })
  }

  return (
    <AppShell
      alertCount={state.activeCount}
      clockLabel={formatLocalClock(clock)}
      footerLeft="Alerts"
    >
      <section className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-primary mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
            Alerts
          </p>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">What needs attention</h1>
          <p className="text-base-content/60 mt-2 max-w-2xl text-sm">
            Browse active and past alerts across the home. Filter by room when you need a quieter view.
          </p>
          <p className="text-base-content/45 mt-2 font-mono text-xs" aria-live="polite">
            {updatedLabel}
          </p>
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
      </section>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div role="tablist" className="tabs tabs-box tabs-sm">
          <button
            type="button"
            role="tab"
            className={`tab ${statusFilter === 'ACTIVE' ? 'tab-active' : ''}`}
            aria-selected={statusFilter === 'ACTIVE'}
            onClick={() => setStatusFilter('ACTIVE')}
          >
            Active
          </button>
          <button
            type="button"
            role="tab"
            className={`tab ${statusFilter === 'ALL' ? 'tab-active' : ''}`}
            aria-selected={statusFilter === 'ALL'}
            onClick={() => setStatusFilter('ALL')}
          >
            All
          </button>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <span className="text-base-content/50 font-mono text-[11px] tracking-wide uppercase">
            Room
          </span>
          <select
            className="select select-bordered select-sm"
            value={roomIdFilter ?? ''}
            onChange={(event) => setRoomFilter(event.target.value)}
          >
            <option value="">All rooms</option>
            {state.rooms.map((room) => (
              <option key={room.roomId} value={room.roomId}>
                {room.name}
              </option>
            ))}
          </select>
        </label>
      </div>

      {state.errorMessage && (
        <div role="alert" className="alert alert-error alert-soft mb-4">
          <span className="text-sm">{state.errorMessage}</span>
        </div>
      )}

      {loadingInitial && (
        <div className="panel mb-4 flex items-center gap-3 px-4 py-8">
          <span className="loading loading-spinner loading-md text-primary" />
          <span className="font-mono text-sm">Loading alerts…</span>
        </div>
      )}

      {state.loadState === 'error' && !state.fetchedAt && (
        <div role="alert" className="alert alert-warning alert-soft mb-4">
          <span>Could not reach home-api.</span>
          <button type="button" className="btn btn-sm" onClick={() => void loadAlerts(false)}>
            Retry
          </button>
        </div>
      )}

      {state.loadState === 'ready' && (
        <div className="panel">
          {state.alerts.length === 0 ? (
            <div className="px-4 py-8">
              <p className="text-success font-medium">
                {statusFilter === 'ACTIVE' ? 'All quiet' : 'No alerts found'}
              </p>
              <p className="text-base-content/60 mt-1 text-sm">
                {roomIdFilter
                  ? 'Nothing matches this room filter.'
                  : statusFilter === 'ACTIVE'
                    ? 'No active alerts right now.'
                    : 'There are no alerts to show.'}
              </p>
              <Link to="/" className="btn btn-ghost btn-sm mt-3">
                ← Back to overview
              </Link>
            </div>
          ) : (
            <>
              <ul className="divide-base-300 divide-y">
                {state.alerts.map((alert) => (
                  <li key={alert.id}>
                    <button
                      type="button"
                      className="hover:bg-base-300/40 flex w-full flex-col gap-1.5 px-4 py-3 text-left transition"
                      onClick={() => setSelectedAlertId(alert.id)}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className={severityBadgeClass(alert.severity)}>
                            {severityLabel(alert.severity)}
                          </span>
                          {alert.status === 'RESOLVED' && (
                            <span className="badge badge-ghost badge-sm">Resolved</span>
                          )}
                          {roomLabels[alert.roomId] && (
                            <span className="text-base-content/50 text-xs">
                              {roomLabels[alert.roomId]}
                            </span>
                          )}
                        </div>
                        <time
                          className="text-base-content/45 font-mono text-[11px]"
                          dateTime={alert.triggeredAt}
                        >
                          {formatEventTime(alert.triggeredAt)}
                        </time>
                      </div>
                      <span className="text-sm font-medium leading-snug">
                        {humanizeAlertMessage(alert, { rooms: roomLabels })}
                      </span>
                      <span className="text-base-content/55 text-sm leading-snug">
                        {adviceForAlert(alert)}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
              {state.hasMore && (
                <div className="border-base-300 border-t px-4 py-3">
                  <button
                    type="button"
                    className="btn btn-sm btn-outline"
                    disabled={loadingMore}
                    onClick={() => void loadMore()}
                  >
                    {loadingMore ? (
                      <>
                        <span className="loading loading-spinner loading-xs" />
                        Loading
                      </>
                    ) : (
                      'Load more'
                    )}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}

      <AlertDrawer
        alertId={selectedAlertId}
        roomNames={roomLabels}
        onClose={() => setSelectedAlertId(null)}
      />
    </AppShell>
  )
}
