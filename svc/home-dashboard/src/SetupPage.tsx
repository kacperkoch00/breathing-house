import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import {
  assignSensor,
  createRoom,
  listRooms,
  listSensors,
  unassignSensor,
  updateRoom,
  updateSensorDisplayName,
} from './api/homeApi'
import type { RoomSummary, SensorSummary } from './api/types'
import { formatLocalClock } from './overview'

const NAME_MAX = 100
const DESCRIPTION_MAX = 500
const DISPLAY_NAME_MAX = 100

type LoadState = 'loading' | 'ready' | 'error'

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

export function SetupPage() {
  const [state, setState] = useState<SetupState>(initialState)
  const [clock, setClock] = useState(() => new Date())

  const [createName, setCreateName] = useState('')
  const [createDescription, setCreateDescription] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const [editingRoomId, setEditingRoomId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [editRoomError, setEditRoomError] = useState<string | null>(null)
  const [savingRoomId, setSavingRoomId] = useState<string | null>(null)

  const [editingSensorId, setEditingSensorId] = useState<string | null>(null)
  const [editDisplayName, setEditDisplayName] = useState('')
  const [editSensorError, setEditSensorError] = useState<string | null>(null)
  const [savingSensorId, setSavingSensorId] = useState<string | null>(null)

  const [assignTargetBySensor, setAssignTargetBySensor] = useState<Record<string, string>>({})
  const [assignErrorBySensor, setAssignErrorBySensor] = useState<Record<string, string>>({})
  const [busySensorId, setBusySensorId] = useState<string | null>(null)

  const loadSetup = useCallback(async () => {
    setState((prev) => ({ ...prev, loadState: 'loading', errorMessage: null }))
    setCreateError(null)
    setEditRoomError(null)
    setEditSensorError(null)
    setAssignErrorBySensor({})

    try {
      const [roomsResponse, sensorsResponse] = await Promise.all([listRooms(), listSensors()])
      setState({
        rooms: roomsResponse.rooms,
        sensors: sensorsResponse.sensors,
        loadState: 'ready',
        errorMessage: null,
      })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to load setup data'
      setState((prev) => ({
        ...prev,
        loadState: prev.rooms.length > 0 || prev.sensors.length > 0 ? 'ready' : 'error',
        errorMessage: message,
      }))
    }
  }, [])

  useEffect(() => {
    void loadSetup()
  }, [loadSetup])

  useEffect(() => {
    const clockId = window.setInterval(() => setClock(new Date()), 30_000)
    return () => window.clearInterval(clockId)
  }, [])

  const { unassignedSensors, assignedSensors } = useMemo(() => {
    const unassigned: SensorSummary[] = []
    const assigned: SensorSummary[] = []
    for (const sensor of state.sensors) {
      if (sensor.roomId == null) unassigned.push(sensor)
      else assigned.push(sensor)
    }
    return { unassignedSensors: unassigned, assignedSensors: assigned }
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
      await loadSetup()
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
      await loadSetup()
    } catch (error) {
      setEditRoomError(error instanceof Error ? error.message : 'Failed to update room')
    } finally {
      setSavingRoomId(null)
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
      await loadSetup()
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
      await loadSetup()
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
      await loadSetup()
    } catch (error) {
      setAssignErrorBySensor((prev) => ({
        ...prev,
        [sensor.sensorId]: error instanceof Error ? error.message : 'Failed to unassign sensor',
      }))
    } finally {
      setBusySensorId(null)
    }
  }

  const renderSensorRow = (sensor: SensorSummary) => {
    const assignError = assignErrorBySensor[sensor.sensorId]
    const isBusy = busySensorId === sensor.sensorId
    const isRenaming = editingSensorId === sensor.sensorId
    const typesLabel = sensor.types.length > 0 ? sensor.types.join(', ') : 'No types yet'

    return (
      <li key={sensor.sensorId} className={sensor.roomId == null ? 'setup-sensor is-unassigned' : 'setup-sensor'}>
        <div className="setup-sensor-main">
          {isRenaming ? (
            <form
              className="setup-inline-form"
              onSubmit={(event) => void handleSaveSensorName(event, sensor.sensorId)}
            >
              <label className="setup-field">
                <span>Display name</span>
                <input
                  value={editDisplayName}
                  onChange={(event) => setEditDisplayName(event.target.value)}
                  maxLength={DISPLAY_NAME_MAX}
                  disabled={savingSensorId === sensor.sensorId}
                  autoFocus
                />
              </label>
              {editSensorError && (
                <p className="banner error" role="alert">
                  {editSensorError}
                </p>
              )}
              <div className="setup-actions">
                <button
                  type="submit"
                  className="refresh"
                  disabled={savingSensorId === sensor.sensorId}
                >
                  Save
                </button>
                <button
                  type="button"
                  className="setup-secondary"
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
              <div className="setup-sensor-title">
                <h3>{sensor.displayName}</h3>
                <button
                  type="button"
                  className="setup-secondary"
                  onClick={() => beginEditSensor(sensor)}
                >
                  Rename
                </button>
              </div>
              <p className="setup-meta">
                <code>{sensor.sensorId}</code>
                <span>{typesLabel}</span>
                <span>{roomNameById(state.rooms, sensor.roomId)}</span>
              </p>
            </>
          )}
        </div>

        <div className="setup-assign">
          <label className="setup-field setup-field-inline">
            <span>Room</span>
            <select
              value={assignTargetBySensor[sensor.sensorId] ?? ''}
              onChange={(event) =>
                setAssignTargetBySensor((prev) => ({
                  ...prev,
                  [sensor.sensorId]: event.target.value,
                }))
              }
              disabled={isBusy || state.rooms.length === 0}
            >
              <option value="">Select room…</option>
              {state.rooms.map((room) => (
                <option key={room.roomId} value={room.roomId}>
                  {room.name}
                </option>
              ))}
            </select>
          </label>
          <div className="setup-actions">
            <button
              type="button"
              className="refresh"
              onClick={() => void handleAssign(sensor.sensorId)}
              disabled={isBusy || state.rooms.length === 0}
            >
              Assign
            </button>
            {sensor.roomId != null && (
              <button
                type="button"
                className="setup-secondary"
                onClick={() => void handleUnassign(sensor)}
                disabled={isBusy}
              >
                Unassign
              </button>
            )}
          </div>
          {assignError && (
            <p className="banner error" role="alert">
              {assignError}
            </p>
          )}
        </div>
      </li>
    )
  }

  return (
    <main className="shell">
      <nav className="topbar" aria-label="Main navigation">
        <Link className="brand" to="/">
          <span className="brand-mark" aria-hidden="true">
            BH
          </span>
          <span>Breathing House</span>
        </Link>
        <div className="topbar-meta">
          <span className="nav-link is-current" aria-current="page">
            Setup
          </span>
          <Link className="back-link" to="/">
            ← Overview
          </Link>
        </div>
      </nav>

      <section className="detail-hero">
        <p className="eyebrow">Setup / {formatLocalClock(clock)}</p>
        <h1>Rooms and sensors</h1>
        <p className="intro">
          Create rooms, rename sensors, and assign them. Changes go straight to home-api.
        </p>
      </section>

      <section className="detail-body setup-body" aria-label="Setup">
        {state.errorMessage && (
          <p className="banner error" role="alert">
            {state.errorMessage}
          </p>
        )}

        {state.loadState === 'loading' && state.rooms.length === 0 && state.sensors.length === 0 && (
          <p className="banner">Loading rooms and sensors…</p>
        )}

        {state.loadState === 'error' && (
          <p className="banner">
            Could not reach home-api.{' '}
            <button type="button" className="refresh" onClick={() => void loadSetup()}>
              Retry
            </button>
          </p>
        )}

        {state.loadState !== 'error' && (
          <>
            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Spaces</p>
                  <h2>Rooms</h2>
                </div>
                <button type="button" className="refresh" onClick={() => void loadSetup()}>
                  Refresh
                </button>
              </div>

              <form className="setup-create" onSubmit={(event) => void handleCreateRoom(event)}>
                <p className="setup-form-label">Create a room</p>
                <div className="setup-form-grid">
                  <label className="setup-field">
                    <span>Name</span>
                    <input
                      value={createName}
                      onChange={(event) => setCreateName(event.target.value)}
                      maxLength={NAME_MAX}
                      placeholder="Kitchen"
                      disabled={creating}
                      required
                    />
                  </label>
                  <label className="setup-field">
                    <span>Description (optional)</span>
                    <input
                      value={createDescription}
                      onChange={(event) => setCreateDescription(event.target.value)}
                      maxLength={DESCRIPTION_MAX}
                      placeholder="South-facing, near the garden door"
                      disabled={creating}
                    />
                  </label>
                </div>
                {createError && (
                  <p className="banner error" role="alert">
                    {createError}
                  </p>
                )}
                <button type="submit" className="refresh" disabled={creating}>
                  {creating ? 'Creating…' : 'Create room'}
                </button>
              </form>

              {state.loadState === 'ready' && state.rooms.length === 0 && (
                <p className="banner empty">No rooms yet. Create one above to start assigning sensors.</p>
              )}

              {state.rooms.length > 0 && (
                <ul className="setup-list">
                  {state.rooms.map((room) => (
                    <li key={room.roomId} className="setup-room">
                      {editingRoomId === room.roomId ? (
                        <form
                          className="setup-inline-form"
                          onSubmit={(event) => void handleSaveRoom(event, room.roomId)}
                        >
                          <div className="setup-form-grid">
                            <label className="setup-field">
                              <span>Name</span>
                              <input
                                value={editName}
                                onChange={(event) => setEditName(event.target.value)}
                                maxLength={NAME_MAX}
                                disabled={savingRoomId === room.roomId}
                                autoFocus
                              />
                            </label>
                            <label className="setup-field">
                              <span>Description</span>
                              <input
                                value={editDescription}
                                onChange={(event) => setEditDescription(event.target.value)}
                                maxLength={DESCRIPTION_MAX}
                                disabled={savingRoomId === room.roomId}
                              />
                            </label>
                          </div>
                          {editRoomError && (
                            <p className="banner error" role="alert">
                              {editRoomError}
                            </p>
                          )}
                          <div className="setup-actions">
                            <button
                              type="submit"
                              className="refresh"
                              disabled={savingRoomId === room.roomId}
                            >
                              Save
                            </button>
                            <button
                              type="button"
                              className="setup-secondary"
                              onClick={() => {
                                setEditingRoomId(null)
                                setEditRoomError(null)
                              }}
                              disabled={savingRoomId === room.roomId}
                            >
                              Cancel
                            </button>
                          </div>
                        </form>
                      ) : (
                        <>
                          <div className="setup-room-title">
                            <div>
                              <h3>
                                <Link className="setup-room-link" to={`/rooms/${room.roomId}`}>
                                  {room.name}
                                </Link>
                              </h3>
                              <p className="setup-meta">
                                <span>
                                  {room.sensorIds.length === 1
                                    ? '1 sensor'
                                    : `${room.sensorIds.length} sensors`}
                                </span>
                                {room.description ? <span>{room.description}</span> : null}
                              </p>
                            </div>
                            <button
                              type="button"
                              className="setup-secondary"
                              onClick={() => beginEditRoom(room)}
                            >
                              Edit
                            </button>
                          </div>
                        </>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="detail-block">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">Devices</p>
                  <h2>Sensors</h2>
                </div>
              </div>

              {state.loadState === 'ready' && state.sensors.length === 0 && (
                <p className="banner empty">
                  No sensors yet. They appear here after discovery from the gateway.
                </p>
              )}

              {unassignedSensors.length > 0 && (
                <div className="setup-sensor-group">
                  <p className="setup-form-label">
                    Unassigned ({unassignedSensors.length})
                  </p>
                  <ul className="setup-list">{unassignedSensors.map(renderSensorRow)}</ul>
                </div>
              )}

              {assignedSensors.length > 0 && (
                <div className="setup-sensor-group">
                  <p className="setup-form-label">
                    Assigned ({assignedSensors.length})
                  </p>
                  <ul className="setup-list">{assignedSensors.map(renderSensorRow)}</ul>
                </div>
              )}
            </div>
          </>
        )}
      </section>

      <footer>
        <span>Setup</span>
        <span>Breathing House · v0.1</span>
      </footer>
    </main>
  )
}
