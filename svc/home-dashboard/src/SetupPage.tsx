import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  assignSensor,
  createRoom,
  deleteRoom,
  listRooms,
  listSensors,
  unassignSensor,
  updateRoom,
  updateSensorDisplayName,
} from './api/homeApi'
import type { RoomSummary, SensorSummary } from './api/types'
import { AppShell } from './AppShell'
import { formatLocalClock } from './overview'
import { useAutoRefresh } from './useAutoRefresh'

const NAME_MAX = 100
const DESCRIPTION_MAX = 500
const DISPLAY_NAME_MAX = 100

type LoadState = 'loading' | 'ready' | 'error'
type SetupTab = 'rooms' | 'unassigned' | 'devices'

interface SetupState {
  rooms: RoomSummary[]
  sensors: SensorSummary[]
  loadState: LoadState
  errorMessage: string | null
}

const initialState: SetupState = {
  rooms: [],
  sensors: [],
  loadState: 'loading',
  errorMessage: null,
}

function validateName(value: string, label: string, max: number): string | null {
  const trimmed = value.trim()
  if (!trimmed) return `${label} is required`
  if (trimmed.length > max) return `${label} must be at most ${max} characters`
  return null
}

function validateOptionalDescription(value: string): string | null {
  if (value.trim().length > DESCRIPTION_MAX) {
    return `Description must be at most ${DESCRIPTION_MAX} characters`
  }
  return null
}

function roomNameById(rooms: RoomSummary[], roomId: string | null): string {
  if (roomId == null) return 'Unassigned'
  return rooms.find((room) => room.roomId === roomId)?.name ?? roomId
}

function sensorTypeBadge(types: string[]): string {
  if (types.length === 0) return 'Unknown'
  return types.join(' · ')
}

export function SetupPage() {
  const [state, setState] = useState<SetupState>(initialState)
  const [tab, setTab] = useState<SetupTab>('rooms')
  const [showCreateRoom, setShowCreateRoom] = useState(false)

  const [createName, setCreateName] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const [editingRoomId, setEditingRoomId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [editRoomError, setEditRoomError] = useState<string | null>(null)
  const [savingRoomId, setSavingRoomId] = useState<string | null>(null)
  const [deletingRoomId, setDeletingRoomId] = useState<string | null>(null)
  const [deleteRoomError, setDeleteRoomError] = useState<string | null>(null)

  const [editingSensorId, setEditingSensorId] = useState<string | null>(null)
  const [editDisplayName, setEditDisplayName] = useState('')
  const [editSensorError, setEditSensorError] = useState<string | null>(null)
  const [savingSensorId, setSavingSensorId] = useState<string | null>(null)

  const [assignTargetBySensor, setAssignTargetBySensor] = useState<Record<string, string>>({})
  const [assignErrorBySensor, setAssignErrorBySensor] = useState<Record<string, string>>({})
  const [busySensorId, setBusySensorId] = useState<string | null>(null)

  const loadSetup = useCallback(async (isRefresh: boolean) => {
    if (!isRefresh) {
      setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
      setCreateError(null)
      setEditRoomError(null)
      setEditSensorError(null)
      setAssignErrorBySensor({})
    }

    try {
      const [roomsResponse, sensorsResponse] = await Promise.all([listRooms(), listSensors()])
      setState({
        rooms: roomsResponse.rooms,
        sensors: sensorsResponse.sensors,
        loadState: 'ready',
        errorMessage: null,
      })
      setShowCreateRoom(roomsResponse.rooms.length === 0)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load setup data'
      setState((prev) => ({
        ...prev,
        loadState: prev.rooms.length > 0 || prev.sensors.length > 0 ? 'ready' : 'error',
        errorMessage: message,
      }))
    }
  }, [])

  const { refreshing, refresh, clock } = useAutoRefresh(loadSetup)

  const { unassignedSensors, assignedSensors, sensorsByRoom } = useMemo(() => {
    const unassigned: SensorSummary[] = []
    const assigned: SensorSummary[] = []
    const byRoom: Record<string, SensorSummary[]> = {}
    for (const sensor of state.sensors) {
      if (sensor.roomId == null) {
        unassigned.push(sensor)
      } else {
        assigned.push(sensor)
        const list = byRoom[sensor.roomId] ?? []
        list.push(sensor)
        byRoom[sensor.roomId] = list
      }
    }
    return { unassignedSensors: unassigned, assignedSensors: assigned, sensorsByRoom: byRoom }
  }, [state.sensors])

  const beginEditRoom = (room: RoomSummary) => {
    setEditingRoomId(room.roomId)
    setEditName(room.name)
    setEditDescription(room.description ?? '')
    setEditRoomError(null)
  }

  const beginEditSensor = (sensor: SensorSummary) => {
    setEditingSensorId(sensor.sensorId)
    setEditDisplayName(sensor.displayName)
    setEditSensorError(null)
  }

  const handleCreateRoom = async (event: FormEvent) => {
    event.preventDefault()
    const nameError = validateName(createName, 'Name', NAME_MAX)
    const descriptionError = validateOptionalDescription(createDescription)
    const validationError = nameError ?? descriptionError
    if (validationError) {
      setCreateError(validationError)
      return
    }

    setCreating(true)
    setCreateError(null)
    try {
      const description = createDescription.trim()
      await createRoom({
        name: createName.trim(),
        description: description.length > 0 ? description : null,
      })
      setCreateName('')
      setCreateDescription('')
      setShowCreateRoom(false)
      setTab('rooms')
      await loadSetup(true)
    } catch (error) {
      setCreateError(error instanceof Error ? error.message : 'Failed to create room')
    } finally {
      setCreating(false)
    }
  }

  const handleSaveRoom = async (event: FormEvent, roomId: string) => {
    event.preventDefault()
    const nameError = validateName(editName, 'Name', NAME_MAX)
    const descriptionError = validateOptionalDescription(editDescription)
    const validationError = nameError ?? descriptionError
    if (validationError) {
      setEditRoomError(validationError)
      return
    }

    setSavingRoomId(roomId)
    setEditRoomError(null)
    try {
      const description = editDescription.trim()
      await updateRoom(roomId, {
        name: editName.trim(),
        description: description.length > 0 ? description : null,
      })
      setEditingRoomId(null)
      await loadSetup(true)
    } catch (error) {
      setEditRoomError(error instanceof Error ? error.message : 'Failed to update room')
    } finally {
      setSavingRoomId(null)
    }
  }

  const handleDeleteRoom = async (room: RoomSummary) => {
    const sensorCount = (sensorsByRoom[room.roomId] ?? []).length
    const detail =
      sensorCount > 0
        ? `${sensorCount} sensor${sensorCount === 1 ? '' : 's'} will become unassigned. History is kept.`
        : 'History for this room id is kept.'
    const confirmed = window.confirm(`Remove “${room.name}”?\n\n${detail}`)
    if (!confirmed) return

    setDeletingRoomId(room.roomId)
    setDeleteRoomError(null)
    try {
      await deleteRoom(room.roomId)
      if (editingRoomId === room.roomId) setEditingRoomId(null)
      await loadSetup(true)
    } catch (error) {
      setDeleteRoomError(error instanceof Error ? error.message : 'Failed to remove room')
    } finally {
      setDeletingRoomId(null)
    }
  }

  const handleSaveSensorName = async (event: FormEvent, sensorId: string) => {
    event.preventDefault()
    const validationError = validateName(editDisplayName, 'Display name', DISPLAY_NAME_MAX)
    if (validationError) {
      setEditSensorError(validationError)
      return
    }

    setSavingSensorId(sensorId)
    setEditSensorError(null)
    try {
      await updateSensorDisplayName(sensorId, editDisplayName.trim())
      setEditingSensorId(null)
      await loadSetup(true)
    } catch (error) {
      setEditSensorError(error instanceof Error ? error.message : 'Failed to rename sensor')
    } finally {
      setSavingSensorId(null)
    }
  }

  const handleAssign = async (sensorId: string) => {
    const roomId = assignTargetBySensor[sensorId]
    if (!roomId) {
      setAssignErrorBySensor((prev) => ({ ...prev, [sensorId]: 'Select a room first' }))
      return
    }

    setBusySensorId(sensorId)
    setAssignErrorBySensor((prev) => {
      const next = { ...prev }
      delete next[sensorId]
      return next
    })
    try {
      await assignSensor(roomId, sensorId)
      await loadSetup(true)
    } catch (error) {
      setAssignErrorBySensor((prev) => ({
        ...prev,
        [sensorId]: error instanceof Error ? error.message : 'Failed to assign sensor',
      }))
    } finally {
      setBusySensorId(null)
    }
  }

  const handleUnassign = async (sensor: SensorSummary) => {
    if (sensor.roomId == null) return

    setBusySensorId(sensor.sensorId)
    setAssignErrorBySensor((prev) => {
      const next = { ...prev }
      delete next[sensor.sensorId]
      return next
    })
    try {
      await unassignSensor(sensor.roomId, sensor.sensorId)
      await loadSetup(true)
    } catch (error) {
      setAssignErrorBySensor((prev) => ({
        ...prev,
        [sensor.sensorId]: error instanceof Error ? error.message : 'Failed to unassign sensor',
      }))
    } finally {
      setBusySensorId(null)
    }
  }

  const renderAssignControls = (sensor: SensorSummary, compact = false) => {
    const assignError = assignErrorBySensor[sensor.sensorId]
    const isBusy = busySensorId === sensor.sensorId

    return (
      <div className={compact ? 'mt-3 space-y-2' : 'space-y-2'}>
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <select
            className="select select-bordered select-sm w-full sm:max-w-xs"
            aria-label={`Assign ${sensor.displayName} to room`}
            value={assignTargetBySensor[sensor.sensorId] ?? sensor.roomId ?? ''}
            onChange={(event) =>
              setAssignTargetBySensor((prev) => ({
                ...prev,
                [sensor.sensorId]: event.target.value,
              }))
            }
            disabled={isBusy || state.rooms.length === 0}
          >
            <option value="">Choose a room…</option>
            {state.rooms.map((room) => (
              <option key={room.roomId} value={room.roomId}>
                {room.name}
              </option>
            ))}
          </select>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={() => void handleAssign(sensor.sensorId)}
              disabled={isBusy || state.rooms.length === 0}
            >
              {isBusy ? <span className="loading loading-spinner loading-xs" /> : 'Assign'}
            </button>
            {sensor.roomId != null && (
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => void handleUnassign(sensor)}
                disabled={isBusy}
              >
                Remove
              </button>
            )}
          </div>
        </div>
        {assignError && (
          <div role="alert" className="alert alert-error alert-soft py-2 text-sm">
            <span>{assignError}</span>
          </div>
        )}
      </div>
    )
  }

  const renderSensorCard = (sensor: SensorSummary, options?: { showRoom?: boolean }) => {
    const isRenaming = editingSensorId === sensor.sensorId
    const showRoom = options?.showRoom ?? true

    return (
      <article key={sensor.sensorId} className="panel p-4">
        {isRenaming ? (
          <form
            className="space-y-3"
            onSubmit={(event) => void handleSaveSensorName(event, sensor.sensorId)}
          >
            <fieldset className="fieldset">
              <legend className="fieldset-legend">Display name</legend>
              <input
                className="input input-bordered w-full"
                value={editDisplayName}
                onChange={(event) => setEditDisplayName(event.target.value)}
                maxLength={DISPLAY_NAME_MAX}
                disabled={savingSensorId === sensor.sensorId}
                autoFocus
              />
            </fieldset>
            {editSensorError && (
              <div role="alert" className="alert alert-error alert-soft">
                <span>{editSensorError}</span>
              </div>
            )}
            <div className="flex flex-wrap gap-2">
              <button
                type="submit"
                className="btn btn-sm btn-primary"
                disabled={savingSensorId === sensor.sensorId}
              >
                Save
              </button>
              <button
                type="button"
                className="btn btn-sm btn-ghost"
                onClick={() => {
                  setEditingSensorId(null)
                  setEditSensorError(null)
                }}
                disabled={savingSensorId === sensor.sensorId}
              >
                Cancel
              </button>
            </div>
          </form>
        ) : (
          <>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="text-base font-medium">{sensor.displayName}</h3>
                  <span className="badge badge-ghost badge-sm font-mono">
                    {sensorTypeBadge(sensor.types)}
                  </span>
                  {sensor.roomId == null && (
                    <span className="badge badge-warning badge-soft badge-sm">Needs a room</span>
                  )}
                </div>
                <p className="text-base-content/50 mt-1 font-mono text-xs">{sensor.sensorId}</p>
                {showRoom && (
                  <p className="text-base-content/60 mt-1 text-sm">
                    {roomNameById(state.rooms, sensor.roomId)}
                  </p>
                )}
              </div>
              <button
                type="button"
                className="btn btn-ghost btn-xs"
                onClick={() => beginEditSensor(sensor)}
              >
                Rename
              </button>
            </div>
            {renderAssignControls(sensor, true)}
          </>
        )}
      </article>
    )
  }

  return (
    <AppShell clockLabel={formatLocalClock(clock)} footerLeft="Setup">
      <div className="mb-4">
        <Link to="/" className="btn btn-ghost btn-sm font-mono">
          ← Overview
        </Link>
      </div>

      <section className="mb-5 flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-primary mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
            Home setup
          </p>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">Organize your home</h1>
          <p className="text-base-content/60 mt-2 max-w-2xl text-sm">
            Create rooms, then put each sensor in the right place — like areas in Home Assistant.
          </p>
        </div>
        <button
          type="button"
          className="btn btn-sm btn-outline"
          onClick={() => void refresh()}
          disabled={refreshing || (state.loadState === 'loading' && state.rooms.length === 0)}
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

      {state.errorMessage && (
        <div role="alert" className="alert alert-error alert-soft mb-4">
          <span>{state.errorMessage}</span>
        </div>
      )}

      {state.loadState === 'loading' && state.rooms.length === 0 && state.sensors.length === 0 && (
        <div className="panel mb-4 flex items-center gap-3 px-4 py-8">
          <span className="loading loading-spinner loading-md text-primary" />
          <span className="font-mono text-sm">Loading rooms and sensors…</span>
        </div>
      )}

      {state.loadState === 'error' && (
        <div role="alert" className="alert alert-warning alert-soft mb-4">
          <span>Could not reach home-api.</span>
          <button type="button" className="btn btn-sm" onClick={() => void loadSetup(false)}>
            Retry
          </button>
        </div>
      )}

      {state.loadState !== 'error' && (
        <>
          <section className="panel mb-5 overflow-hidden">
            <div className="stats stats-vertical sm:stats-horizontal bg-base-100 w-full">
              <div className="stat py-4">
                <div className="stat-title font-mono text-[11px] tracking-wide uppercase">Rooms</div>
                <div className="stat-value metric-value text-2xl">{state.rooms.length}</div>
              </div>
              <div className="stat py-4">
                <div className="stat-title font-mono text-[11px] tracking-wide uppercase">
                  Sensors
                </div>
                <div className="stat-value metric-value text-2xl">{state.sensors.length}</div>
                <div className="stat-desc font-mono">{assignedSensors.length} assigned</div>
              </div>
              <div className="stat py-4">
                <div className="stat-title font-mono text-[11px] tracking-wide uppercase">
                  Unassigned
                </div>
                <div
                  className={`stat-value metric-value text-2xl ${
                    unassignedSensors.length > 0 ? 'text-warning' : 'text-success'
                  }`}
                >
                  {unassignedSensors.length}
                </div>
                <div className="stat-desc font-mono">
                  {unassignedSensors.length > 0 ? 'Needs a room' : 'All placed'}
                </div>
              </div>
            </div>
          </section>

          {unassignedSensors.length > 0 && (
            <div role="alert" className="alert alert-warning alert-soft mb-5">
              <div className="w-full">
                <p className="font-medium">
                  {unassignedSensors.length === 1
                    ? '1 sensor still needs a room'
                    : `${unassignedSensors.length} sensors still need a room`}
                </p>
                <p className="text-sm opacity-80">
                  Assign them so overview and room pages can show readings in the right place.
                </p>
              </div>
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setTab('unassigned')}
              >
                Review
              </button>
            </div>
          )}

          <div role="tablist" className="tabs tabs-box mb-5 w-full max-w-xl">
            <button
              type="button"
              role="tab"
              className={`tab ${tab === 'rooms' ? 'tab-active' : ''}`}
              aria-selected={tab === 'rooms'}
              onClick={() => setTab('rooms')}
            >
              Rooms
              <span className="badge badge-ghost badge-sm ml-2">{state.rooms.length}</span>
            </button>
            <button
              type="button"
              role="tab"
              className={`tab ${tab === 'unassigned' ? 'tab-active' : ''}`}
              aria-selected={tab === 'unassigned'}
              onClick={() => setTab('unassigned')}
            >
              Needs a room
              {unassignedSensors.length > 0 && (
                <span className="badge badge-warning badge-sm ml-2">
                  {unassignedSensors.length}
                </span>
              )}
            </button>
            <button
              type="button"
              role="tab"
              className={`tab ${tab === 'devices' ? 'tab-active' : ''}`}
              aria-selected={tab === 'devices'}
              onClick={() => setTab('devices')}
            >
              All devices
            </button>
          </div>

          {tab === 'rooms' && (
            <section aria-label="Rooms" className="space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-base-content/60 text-sm">
                  Rooms are places in your home. Sensors inherit the room you assign them to.
                </p>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => setShowCreateRoom((open) => !open)}
                >
                  {showCreateRoom ? 'Cancel' : 'Add room'}
                </button>
              </div>

              {showCreateRoom && (
                <form className="panel p-4" onSubmit={(event) => void handleCreateRoom(event)}>
                  <h2 className="mb-3 text-base font-medium">New room</h2>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <fieldset className="fieldset">
                      <legend className="fieldset-legend">Name</legend>
                      <input
                        className="input input-bordered w-full"
                        value={createName}
                        onChange={(event) => setCreateName(event.target.value)}
                        maxLength={NAME_MAX}
                        placeholder="Kitchen"
                        disabled={creating}
                        required
                        autoFocus
                      />
                    </fieldset>
                    <fieldset className="fieldset">
                      <legend className="fieldset-legend">Notes (optional)</legend>
                      <input
                        className="input input-bordered w-full"
                        value={createDescription}
                        onChange={(event) => setCreateDescription(event.target.value)}
                        maxLength={DESCRIPTION_MAX}
                        placeholder="Near the garden door"
                        disabled={creating}
                      />
                    </fieldset>
                  </div>
                  {createError && (
                    <div role="alert" className="alert alert-error alert-soft mt-3">
                      <span>{createError}</span>
                    </div>
                  )}
                  <div className="mt-3 flex flex-wrap gap-2">
                    <button type="submit" className="btn btn-primary btn-sm" disabled={creating}>
                      {creating ? (
                        <>
                          <span className="loading loading-spinner loading-xs" />
                          Creating…
                        </>
                      ) : (
                        'Create room'
                      )}
                    </button>
                  </div>
                </form>
              )}

              {state.loadState === 'ready' && state.rooms.length === 0 && (
                <div role="alert" className="alert alert-info alert-soft">
                  <span>No rooms yet. Add a room to start placing sensors.</span>
                </div>
              )}

              {deleteRoomError && (
                <div role="alert" className="alert alert-error alert-soft">
                  <span>{deleteRoomError}</span>
                </div>
              )}

              <div className="grid gap-3 md:grid-cols-2">
                {state.rooms.map((room) => {
                  const roomSensors = sensorsByRoom[room.roomId] ?? []
                  const roomBusy =
                    savingRoomId === room.roomId || deletingRoomId === room.roomId
                  return (
                    <article key={room.roomId} className="panel flex flex-col p-4">
                      {editingRoomId === room.roomId ? (
                        <form
                          className="space-y-3"
                          onSubmit={(event) => void handleSaveRoom(event, room.roomId)}
                        >
                          <div className="grid gap-3">
                            <fieldset className="fieldset">
                              <legend className="fieldset-legend">Name</legend>
                              <input
                                className="input input-bordered w-full"
                                value={editName}
                                onChange={(event) => setEditName(event.target.value)}
                                maxLength={NAME_MAX}
                                disabled={roomBusy}
                                autoFocus
                              />
                            </fieldset>
                            <fieldset className="fieldset">
                              <legend className="fieldset-legend">Notes</legend>
                              <input
                                className="input input-bordered w-full"
                                value={editDescription}
                                onChange={(event) => setEditDescription(event.target.value)}
                                maxLength={DESCRIPTION_MAX}
                                disabled={roomBusy}
                              />
                            </fieldset>
                          </div>
                          {editRoomError && (
                            <div role="alert" className="alert alert-error alert-soft">
                              <span>{editRoomError}</span>
                            </div>
                          )}
                          <div className="flex flex-wrap gap-2">
                            <button
                              type="submit"
                              className="btn btn-sm btn-primary"
                              disabled={roomBusy}
                            >
                              Save
                            </button>
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              onClick={() => {
                                setEditingRoomId(null)
                                setEditRoomError(null)
                              }}
                              disabled={roomBusy}
                            >
                              Cancel
                            </button>
                          </div>
                        </form>
                      ) : (
                        <>
                          <div className="mb-3 flex items-start justify-between gap-2">
                            <div className="min-w-0">
                              <h2 className="text-lg font-medium">
                                <Link className="link link-hover" to={`/rooms/${room.roomId}`}>
                                  {room.name}
                                </Link>
                              </h2>
                              {room.description && (
                                <p className="text-base-content/55 mt-1 text-sm">
                                  {room.description}
                                </p>
                              )}
                            </div>
                            <button
                              type="button"
                              className="btn btn-ghost btn-xs"
                              onClick={() => beginEditRoom(room)}
                              disabled={roomBusy}
                            >
                              Edit
                            </button>
                          </div>

                          <p className="text-base-content/45 mb-2 font-mono text-[11px] tracking-wide uppercase">
                            Sensors · {roomSensors.length}
                          </p>
                          {roomSensors.length === 0 ? (
                            <p className="text-base-content/50 text-sm">
                              No sensors here yet. Assign some from Needs a room.
                            </p>
                          ) : (
                            <ul className="flex flex-wrap gap-2">
                              {roomSensors.map((sensor) => (
                                <li key={sensor.sensorId}>
                                  <span className="badge badge-soft badge-sm gap-1">
                                    <span className="font-mono text-[10px] opacity-70">
                                      {sensorTypeBadge(sensor.types)}
                                    </span>
                                    {sensor.displayName}
                                  </span>
                                </li>
                              ))}
                            </ul>
                          )}

                          <div className="mt-auto flex flex-wrap gap-2 pt-4">
                            <Link to={`/rooms/${room.roomId}`} className="btn btn-ghost btn-xs">
                              Open room
                            </Link>
                            {unassignedSensors.length > 0 && (
                              <button
                                type="button"
                                className="btn btn-ghost btn-xs"
                                onClick={() => setTab('unassigned')}
                              >
                                Assign sensors
                              </button>
                            )}
                            <button
                              type="button"
                              className="btn btn-ghost btn-xs text-error"
                              onClick={() => void handleDeleteRoom(room)}
                              disabled={roomBusy}
                              aria-busy={deletingRoomId === room.roomId}
                            >
                              {deletingRoomId === room.roomId ? 'Removing…' : 'Remove'}
                            </button>
                          </div>
                        </>
                      )}
                    </article>
                  )
                })}
              </div>
            </section>
          )}

          {tab === 'unassigned' && (
            <section aria-label="Unassigned sensors" className="space-y-4">
              <p className="text-base-content/60 text-sm">
                These devices were discovered but are not in a room yet. Pick a room and assign.
              </p>

              {state.rooms.length === 0 && (
                <div role="alert" className="alert alert-info alert-soft">
                  <span>Create a room first, then come back here to place sensors.</span>
                  <button type="button" className="btn btn-sm" onClick={() => setTab('rooms')}>
                    Go to rooms
                  </button>
                </div>
              )}

              {unassignedSensors.length === 0 ? (
                <div role="alert" className="alert alert-success alert-soft">
                  <span>All sensors have a room. Nice.</span>
                </div>
              ) : (
                <div className="grid gap-3 md:grid-cols-2">
                  {unassignedSensors.map((sensor) =>
                    renderSensorCard(sensor, { showRoom: false }),
                  )}
                </div>
              )}
            </section>
          )}

          {tab === 'devices' && (
            <section aria-label="All devices" className="space-y-4">
              <p className="text-base-content/60 text-sm">
                Every discovered sensor. Rename, move between rooms, or remove from a room.
              </p>

              {state.sensors.length === 0 ? (
                <div role="alert" className="alert alert-info alert-soft">
                  <span>No sensors yet. They appear after discovery from the gateway.</span>
                </div>
              ) : (
                <div className="grid gap-3 md:grid-cols-2">
                  {state.sensors.map((sensor) => renderSensorCard(sensor))}
                </div>
              )}
            </section>
          )}
        </>
      )}
    </AppShell>
  )
}
