import { useMemo, useState } from 'react'
import type { OccupancyEvent, SensorSummary } from '../api/types'
import {
  formatEventTime,
  formatOccupancyState,
  formatOccupancySummary,
  latestOccupancyBySensor,
} from '../roomDetail'
import { isActivitySensor, useRoomOutlet } from './roomContext'

type ActivityFilter = 'ALL' | 'PRESENCE' | 'OPENING'

function activityKind(sensor: SensorSummary): 'PRESENCE' | 'OPENING' | null {
  if (sensor.types.includes('PRESENCE')) return 'PRESENCE'
  if (sensor.types.includes('OPENING')) return 'OPENING'
  return null
}

export function RoomActivityView() {
  const { state, sensorNames } = useRoomOutlet()
  const [filter, setFilter] = useState<ActivityFilter>('ALL')

  const activitySensors = useMemo(
    () => state.sensors.filter(isActivitySensor),
    [state.sensors],
  )

  const latestBySensor = useMemo(
    () => latestOccupancyBySensor(state.occupancy),
    [state.occupancy],
  )

  const timeline = useMemo(() => {
    if (filter === 'ALL') return state.occupancy
    return state.occupancy.filter((event) => event.eventType === filter)
  }, [state.occupancy, filter])

  return (
    <div aria-label="Presence and openings" className="grid gap-6">
      <section>
        <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
          Now
        </p>
        <h2 className="mb-3 text-xl font-semibold">Current state</h2>

        {activitySensors.length === 0 ? (
          <div className="panel text-base-content/55 px-4 py-6 text-sm">
            No presence or opening sensors in this room.
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {activitySensors.map((sensor) => {
              const kind = activityKind(sensor)
              const latest = latestBySensor[sensor.sensorId] ?? null
              return (
                <article key={sensor.sensorId} className="panel rounded-field border px-4 py-4">
                  <div className="mb-2 flex items-center justify-between gap-2">
                    <span
                      className={`badge badge-sm font-normal ${
                        kind === 'PRESENCE'
                          ? 'badge-primary badge-soft'
                          : 'badge-secondary badge-soft'
                      }`}
                    >
                      {kind === 'PRESENCE' ? 'Presence' : 'Opening'}
                    </span>
                    <span className="text-base-content/45 font-mono text-[10px] tracking-wide uppercase">
                      {sensor.displayName}
                    </span>
                  </div>
                  <p className="metric-value text-3xl">
                    {latest ? formatOccupancyState(latest) : '—'}
                  </p>
                  <p className="text-base-content/50 mt-2 text-sm">
                    {latest
                      ? formatEventTime(latest.observedAt, { withSeconds: true })
                      : 'No events yet'}
                  </p>
                </article>
              )
            })}
          </div>
        )}
      </section>

      <section>
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
              Last 24 hours
            </p>
            <h2 className="text-xl font-semibold">Timeline</h2>
          </div>
          <div role="tablist" className="tabs tabs-box tabs-sm">
            {(
              [
                ['ALL', 'All'],
                ['PRESENCE', 'Presence'],
                ['OPENING', 'Opening'],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                role="tab"
                className={`tab ${filter === value ? 'tab-active' : ''}`}
                aria-selected={filter === value}
                onClick={() => setFilter(value)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {activitySensors.length === 0 ? null : timeline.length === 0 ? (
          <div className="panel text-base-content/55 px-4 py-6 text-sm">
            No events in the last 24 hours
            {filter !== 'ALL' ? ` for ${filter.toLowerCase()}` : ''}.
          </div>
        ) : (
          <ol className="panel relative ms-2 border-s border-base-300 ps-0">
            {timeline.map((event) => (
              <TimelineRow
                key={event.id}
                event={event}
                sensorNames={sensorNames}
              />
            ))}
          </ol>
        )}
      </section>
    </div>
  )
}

function TimelineRow({
  event,
  sensorNames,
}: {
  event: OccupancyEvent
  sensorNames: Record<string, string>
}) {
  const isPresence = event.eventType === 'PRESENCE'
  return (
    <li className="relative flex gap-3 py-3 ps-6 pe-4">
      <span
        className={`absolute top-5 -left-[5px] size-2.5 rounded-full ${
          isPresence ? 'bg-primary' : 'bg-secondary'
        }`}
        aria-hidden
      />
      <span
        className={`badge badge-sm shrink-0 font-normal ${
          isPresence ? 'badge-primary badge-soft' : 'badge-secondary badge-soft'
        }`}
      >
        {isPresence ? 'Presence' : 'Opening'}
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium leading-snug">
          {formatOccupancySummary(event, sensorNames)}
        </p>
      </div>
      <time
        className="text-base-content/45 shrink-0 font-mono text-[11px]"
        dateTime={event.observedAt}
      >
        {formatEventTime(event.observedAt, { withSeconds: true })}
      </time>
    </li>
  )
}
