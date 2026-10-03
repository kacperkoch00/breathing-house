import type {
  EnvironmentReading,
  GatewayStatusResponse,
  PageResponse,
  RoomsResponse,
  Alert,
  SensorType,
} from './types'

const DEFAULT_BASE_URL = 'http://localhost:8082'

export function getHomeApiBaseUrl(): string {
  const configured = import.meta.env.VITE_HOME_API_BASE_URL?.trim()
  return configured && configured.length > 0 ? configured.replace(/\/$/, '') : DEFAULT_BASE_URL
}

async function fetchJson<T>(path: string): Promise<T> {
  const response = await fetch(`${getHomeApiBaseUrl()}${path}`)
  if (!response.ok) {
    throw new Error(`home-api ${path} failed (${response.status})`)
  }
  return response.json() as Promise<T>
}

export function getGatewayStatus(): Promise<GatewayStatusResponse> {
  return fetchJson<GatewayStatusResponse>('/api/v1/sensor-gateway/status')
}

export function listRooms(): Promise<RoomsResponse> {
  return fetchJson<RoomsResponse>('/api/v1/rooms')
}

export function listEnvironmentReadings(
  roomId: string,
  options: { limit?: number; sensorType?: SensorType } = {},
): Promise<PageResponse<EnvironmentReading>> {
  const params = new URLSearchParams()
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

export function listActiveAlerts(limit = 20): Promise<PageResponse<Alert>> {
  const params = new URLSearchParams({ status: 'ACTIVE', limit: String(limit) })
  return fetchJson<PageResponse<Alert>>(`/api/v1/alerts?${params}`)
}
