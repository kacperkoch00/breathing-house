import { useOutletContext } from 'react-router-dom'
import type {
  Alert,
  EnvironmentReading,
  OccupancyEvent,
  RoomSummary,
  SensorSummary,
} from '../api/types'
import type { MetricKey } from '../roomDetail'

export type RoomLoadState = 'loading' | 'ready' | 'error' | 'not-found'

export interface RoomDetailState {
  room: RoomSummary | null
  latestRoom: EnvironmentReading | null
  /** Latest reading per environment sensor in this room. */
  latestBySensor: EnvironmentReading[]
  selectedLatest: EnvironmentReading | null
  /** Sensor used for the overview trend chart (prefer AIR). */
  chartSensorId: string | null
  sensors: SensorSummary[]
  alerts: Alert[]
  history: EnvironmentReading[]
  occupancy: OccupancyEvent[]
  fetchedAt: Date | null
  loadState: RoomLoadState
  errorMessage: string | null
}

export interface RoomOutletContext {
  state: RoomDetailState
  /** null = room overview (averages + latest light). */
  selectedSensorId: string | null
  selectSensor: (sensorId: string | null) => void
  selectedMetric: MetricKey
  setSelectedMetric: (metric: MetricKey) => void
  selectedAlertId: number | null
  setSelectedAlertId: (id: number | null) => void
  sensorNames: Record<string, string>
  environmentSensors: SensorSummary[]
}

export function useRoomOutlet(): RoomOutletContext {
  return useOutletContext<RoomOutletContext>()
}

export function isEnvironmentSensor(sensor: SensorSummary): boolean {
  return sensor.types.some((type) => type === 'AIR' || type === 'ROOM')
}

export function isActivitySensor(sensor: SensorSummary): boolean {
  return sensor.types.some((type) => type === 'PRESENCE' || type === 'OPENING')
}
