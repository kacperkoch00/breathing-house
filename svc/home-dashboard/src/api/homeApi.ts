import type {
  Alert,
  AlertDetail,
  AlertStatus,
  EnvironmentReading,
  EventType,
  GatewayStatusResponse,
  OccupancyEvent,
  PageResponse,
  RoomSummary,
  RoomsResponse,
  SensorSummary,
  SensorsResponse,
  SensorType,
} from './types'

export class HomeApiError extends Error {
  readonly status: number
  readonly path: string

  constructor(status: number, path: string, message?: string) {
    super(message ?? `home-api ${path} failed (${status})`)
    this.name = 'HomeApiError'
    this.status = status
    this.path = path
  }
}

/** Empty string = same-origin relative /api (Vite or nginx proxy). */
export function getHomeApiBaseUrl(): string {
  const configured = import.meta.env.VITE_HOME_API_BASE_URL?.trim()
  return configured && configured.length > 0 ? configured.replace(/\/$/, '') : ''
}

interface FetchJsonOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
}

async function fetchJson<T>(path: string, options: FetchJsonOptions = {}): Promise<T> {
  const headers: Record<string, string> = { ...options.headers }
  let body: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const response = await fetch(`${getHomeApiBaseUrl()}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body,
  })

  if (!response.ok) {
    throw new HomeApiError(response.status, path, await readErrorMessage(response))
  }

  return response.json() as Promise<T>
}

async function readErrorMessage(response: Response): Promise<string | undefined> {
  try {
    const data: unknown = await response.json()
    if (
      data &&
      typeof data === 'object' &&
      'message' in data &&
      typeof (data as { message: unknown }).message === 'string'
    ) {
      const message = (data as { message: string }).message.trim()
      return message.length > 0 ? message : undefined
    }
  } catch {
    // Fall back to status+path when the body is not JSON.
  }
  return undefined
}

export function getGatewayStatus(): Promise<GatewayStatusResponse> {
  return fetchJson<GatewayStatusResponse>('/api/v1/sensor-gateway/status')
}

export function listRooms(): Promise<RoomsResponse> {
  return fetchJson<RoomsResponse>('/api/v1/rooms')
}

export function getRoom(roomId: string): Promise<RoomSummary> {
  return fetchJson<RoomSummary>(`/api/v1/rooms/${encodeURIComponent(roomId)}`)
}

export function createRoom(body: {
  name: string
  description?: string | null
}): Promise<RoomSummary> {
  return fetchJson<RoomSummary>('/api/v1/rooms', { method: 'POST', body })
}

export function updateRoom(
  roomId: string,
  body: { name?: string; description?: string | null },
): Promise<RoomSummary> {
  return fetchJson<RoomSummary>(`/api/v1/rooms/${encodeURIComponent(roomId)}`, {
    method: 'PATCH',
    body,
  })
}

export function listSensors(): Promise<SensorsResponse> {
  return fetchJson<SensorsResponse>('/api/v1/sensors')
}

export function updateSensorDisplayName(
  sensorId: string,
  displayName: string,
): Promise<SensorSummary> {
  return fetchJson<SensorSummary>(`/api/v1/sensors/${encodeURIComponent(sensorId)}`, {
    method: 'PATCH',
    body: { displayName },
  })
}

export function assignSensor(roomId: string, sensorId: string): Promise<SensorSummary> {
  return fetchJson<SensorSummary>(
    `/api/v1/rooms/${encodeURIComponent(roomId)}/sensors/${encodeURIComponent(sensorId)}`,
    { method: 'PUT' },
  )
}

export function unassignSensor(roomId: string, sensorId: string): Promise<SensorSummary> {
  return fetchJson<SensorSummary>(
    `/api/v1/rooms/${encodeURIComponent(roomId)}/sensors/${encodeURIComponent(sensorId)}`,
    { method: 'DELETE' },
  )
}

export function listEnvironmentReadings(
  roomId: string,
  options: {
    from?: string
    to?: string
    limit?: number
    sensorType?: SensorType
  } = {},
): Promise<PageResponse<EnvironmentReading>> {
  const params = new URLSearchParams()
  if (options.from != null) params.set('from', options.from)
  if (options.to != null) params.set('to', options.to)
  if (options.limit != null) params.set('limit', String(options.limit))
  if (options.sensorType != null) params.set('sensorType', options.sensorType)
  const query = params.toString()
  return fetchJson<PageResponse<EnvironmentReading>>(
    `/api/v1/rooms/${encodeURIComponent(roomId)}/environment-readings${query ? `?${query}` : ''}`,
  )
}

/** Latest reading for a room: prefer AIR, then ROOM. */
export async function getLatestEnvironmentReading(
  roomId: string,
): Promise<EnvironmentReading | null> {
  const air = await listEnvironmentReadings(roomId, { limit: 1, sensorType: 'AIR' })
  if (air.items.length > 0) {
    return air.items[0]
  }
  const room = await listEnvironmentReadings(roomId, { limit: 1, sensorType: 'ROOM' })
  return room.items[0] ?? null
}

export function listOccupancyEvents(
  roomId: string,
  options: {
    from?: string
    to?: string
    limit?: number
    eventType?: EventType
  } = {},
): Promise<PageResponse<OccupancyEvent>> {
  const params = new URLSearchParams()
  if (options.from != null) params.set('from', options.from)
  if (options.to != null) params.set('to', options.to)
  if (options.limit != null) params.set('limit', String(options.limit))
  if (options.eventType != null) params.set('eventType', options.eventType)
  const query = params.toString()
  return fetchJson<PageResponse<OccupancyEvent>>(
    `/api/v1/rooms/${encodeURIComponent(roomId)}/occupancy-events${query ? `?${query}` : ''}`,
  )
}

export function listAlerts(
  options: {
    status?: AlertStatus
    roomId?: string
    limit?: number
  } = {},
): Promise<PageResponse<Alert>> {
  const params = new URLSearchParams()
  if (options.status != null) params.set('status', options.status)
  if (options.roomId != null) params.set('roomId', options.roomId)
  if (options.limit != null) params.set('limit', String(options.limit))
  const query = params.toString()
  return fetchJson<PageResponse<Alert>>(`/api/v1/alerts${query ? `?${query}` : ''}`)
}

export function listActiveAlerts(limit = 20): Promise<PageResponse<Alert>> {
  return listAlerts({ status: 'ACTIVE', limit })
}

export function getAlert(id: number): Promise<AlertDetail> {
  return fetchJson<AlertDetail>(`/api/v1/alerts/${id}`)
}
