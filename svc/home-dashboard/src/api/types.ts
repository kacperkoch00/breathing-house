export type SensorType = 'ROOM' | 'AIR'
export type EventType = 'PRESENCE' | 'OPENING'
export type AlertStatus = 'ACTIVE' | 'RESOLVED'
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL'

export interface GatewayStatusResponse {
  online: boolean
}

export interface RoomSummary {
  roomId: string
  name: string
  description: string | null
  sensorIds: string[]
}

export interface RoomsResponse {
  rooms: RoomSummary[]
}

export interface EnvironmentReading {
  id: number
  roomId: string
  sensorId: string | null
  sensorType: SensorType
  temperature: number | null
  humidity: number | null
  co2: number | null
  light: number | null
  lightLevel: string | null
  observedAt: string
  receivedAt: string
  ingestedAt: string
}

export interface OccupancyEvent {
  id: number
  roomId: string
  sensorId: string | null
  eventType: EventType
  present: boolean | null
  open: boolean | null
  observedAt: string
  receivedAt: string
  ingestedAt: string
}

export interface Alert {
  id: number
  ruleId: string
  roomId: string
  sensorId: string | null
  severity: AlertSeverity
  status: AlertStatus
  message: string
  triggerValue: string | null
  triggeredAt: string
  resolvedAt: string | null
  lastEvaluatedAt: string
}

export interface PageResponse<T> {
  items: T[]
  limit: number
  offset: number
  hasMore: boolean
}
