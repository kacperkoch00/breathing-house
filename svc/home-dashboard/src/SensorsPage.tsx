import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  admitGatewaySensor,
  listGatewayCandidates,
  startGatewayScan,
  type GatewayCandidate,
  type GatewayScanStatus,
} from './api/gatewayApi'
import { listRooms, listSensors, pairSensor, unpairSensor } from './api/homeApi'
import type { RoomSummary, SensorSummary } from './api/types'
import { AppShell } from './AppShell'
import { formatLocalClock, formatUpdatedLabel, roomNameMap } from './overview'
import {
  inferSensorKindFromId,
  resolveSensorKind,
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
  if (sensor.types.includes(filter)) return true
  return inferSensorKindFromId(sensor.sensorId) === filter
}

export function SensorsPage() {
  const [state, setState] = useState<SensorsState>(initialState)
  const [filter, setFilter] = useState<SensorFilter>('ALL')
  const [scanOpen, setScanOpen] = useState(false)
  const [scan, setScan] = useState<GatewayScanStatus | null>(null)
  const [scanError, setScanError] = useState<string | null>(null)
  const [pairingId, setPairingId] = useState<string | null>(null)
  const [unpairingId, setUnpairingId] = useState<string | null>(null)

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
  const knownIds = useMemo(() => new Set(state.sensors.map((sensor) => sensor.sensorId)), [state.sensors])

  useEffect(() => {
    if (!scanOpen || scan?.state !== 'scanning') {
      return
    }
    const timer = window.setInterval(() => {
      void listGatewayCandidates()
        .then((next) => setScan(next))
        .catch((error) => setScanError(error instanceof Error ? error.message : 'Scan failed'))
    }, 2000)
    return () => window.clearInterval(timer)
  }, [scanOpen, scan?.state])

  const handleScan = async () => {
    setScanOpen(true)
    setScanError(null)
    setScan(null)
    try {
      setScan(await startGatewayScan())
    } catch (error) {
      setScanError(error instanceof Error ? error.message : 'Could not reach the gateway')
    }
  }

  const handlePair = async (candidate: GatewayCandidate) => {
    setPairingId(candidate.sensorId)
    setScanError(null)
    try {
      if (!knownIds.has(candidate.sensorId)) {
        await pairSensor(candidate.sensorId)
      }
      try {
        await admitGatewaySensor(candidate.sensorId)
      } catch (error) {
        setScanError(error instanceof Error ? error.message : 'Gateway admit failed')
      }
      try {
        setScan(await listGatewayCandidates())
      } catch {
        // list refresh is best-effort; pair/admit already ran
      }
      await loadSensors(true)
    } catch (error) {
      setScanError(error instanceof Error ? error.message : 'Failed to pair sensor')
    } finally {
      setPairingId(null)
    }
  }

  const handleUnpair = async (sensor: SensorSummary) => {
    const confirmed = window.confirm(
      `Remove “${sensor.displayName}” from known devices?\n\nHistory is kept. The board can be paired again later.`,
    )
    if (!confirmed) return
    setUnpairingId(sensor.sensorId)
    try {
      await unpairSensor(sensor.sensorId)
      await loadSensors(true)
    } catch (error) {
      setState((prev) => ({
        ...prev,
        errorMessage: error instanceof Error ? error.message : 'Failed to unpair sensor',
      }))
    } finally {
      setUnpairingId(null)
    }
  }
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
            Known devices in the home. Scan to pair a blinking sensor, or open one for readings.
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
            onClick={() => void handleScan()}
            disabled={scanOpen && scan?.state === 'scanning'}
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
            Press Pair on a sensor, then Scan to add it.
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
            const kind = resolveSensorKind(sensor.types, sensor.sensorId)
            const typeBadges = sensor.types.length > 0 ? sensor.types : kind ? [kind] : []
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
                    {typeBadges.length === 0 ? (
                      <span className="badge badge-ghost badge-sm">Unknown</span>
                    ) : (
                      typeBadges.map((type) => (
                        <span
                          key={type}
                          className={`badge badge-sm font-normal ${sensorKindBadgeClass(type)}`}
                        >
                          {sensorKindLabel(type)}
                        </span>
                      ))
                    )}
                  </div>
                  <div className="mt-auto flex items-center justify-between gap-2">
                    <p className="text-base-content/55 text-sm">{roomLabel}</p>
                    <button
                      type="button"
                      className="btn btn-ghost btn-xs"
                      disabled={unpairingId === sensor.sensorId}
                      onClick={(event) => {
                        event.preventDefault()
                        event.stopPropagation()
                        void handleUnpair(sensor)
                      }}
                    >
                      {unpairingId === sensor.sensorId ? 'Removing' : 'Unpair'}
                    </button>
                  </div>
                </article>
              </Link>
            )
          })}
        </div>
      )}
      {scanOpen && (
        <dialog className="modal modal-open" aria-label="Scan for sensors">
          <div className="modal-box">
            <h2 className="text-lg font-semibold">Scan for sensors</h2>
            <p className="text-base-content/60 mt-1 text-sm">
              Press Pair on the device so its LED blinks, then add it here.
            </p>
            {scan?.state === 'scanning' && (
              <p className="text-base-content/55 mt-3 flex items-center gap-2 font-mono text-xs">
                <span className="loading loading-spinner loading-xs" />
                Looking for devices…
              </p>
            )}
            {scan?.state === 'complete' && (scan.candidates ?? []).length === 0 && !scanError && (
              <p className="text-base-content/55 mt-3 text-sm">
                Scan finished. No pairing advertisement was seen.
              </p>
            )}
            {(scanError || scan?.bleError) && (
              <div role="alert" className="alert alert-error alert-soft mt-3">
                <span className="text-sm">{scanError ?? scan?.bleError}</span>
              </div>
            )}
            <ul className="mt-4 space-y-2">
              {(scan?.candidates ?? []).length === 0 && scan?.state === 'scanning' && !scanError && (
                <li className="text-base-content/55 text-sm">No pairing sensors yet.</li>
              )}
              {(scan?.candidates ?? []).map((candidate) => {
                const known = knownIds.has(candidate.sensorId)
                const busy = pairingId === candidate.sensorId
                return (
                  <li
                    key={candidate.sensorId}
                    className="flex items-center justify-between gap-3 rounded-box border border-base-300 px-3 py-2"
                  >
                    <div className="min-w-0">
                      <p className="truncate font-medium">{candidate.sensorId}</p>
                      <p className="text-base-content/45 font-mono text-[11px]">
                        {candidate.type ?? 'Unknown'}
                        {known ? ' · already in list' : ''}
                      </p>
                    </div>
                    <button
                      type="button"
                      className="btn btn-sm btn-primary"
                      disabled={busy}
                      onClick={() => void handlePair(candidate)}
                    >
                      {busy ? 'Adding' : known ? 'Accept again' : 'Accept'}
                    </button>
                  </li>
                )
              })}
            </ul>
            <div className="modal-action">
              <button type="button" className="btn" onClick={() => setScanOpen(false)}>
                Done
              </button>
            </div>
          </div>
          <button
            type="button"
            className="modal-backdrop"
            aria-label="Close scan"
            onClick={() => setScanOpen(false)}
          />
        </dialog>
      )}
    </AppShell>
  )
}
