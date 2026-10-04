import { Link } from 'react-router-dom'
import { AlertDrawer } from '../AlertDrawer'
import { adviceForAlert } from '../comfort'
import { humanizeAlertMessage } from '../overview'
import { formatEventTime } from '../roomDetail'
import { severityBadgeClass, severityLabel } from '../ui'
import { useRoomOutlet } from './roomContext'

export function RoomAlertsView() {
  const { state, selectedAlertId, setSelectedAlertId, sensorNames } = useRoomOutlet()
  const room = state.room
  const roomNames = room ? { [room.roomId]: room.name } : undefined

  return (
    <div aria-label="Room alerts">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <div>
          <p className="text-base-content/45 mb-1 font-mono text-[11px] tracking-[0.16em] uppercase">
            Alerts
          </p>
          <h2 className="text-xl font-semibold">What needs attention</h2>
        </div>
        {room && (
          <Link
            to={`/alerts?roomId=${encodeURIComponent(room.roomId)}`}
            className="link link-hover text-primary text-sm"
          >
            Browse all alerts →
          </Link>
        )}
      </div>

      <div className="panel">
        {state.alerts.length === 0 ? (
          <div className="px-4 py-8">
            <p className="text-success font-medium">Nothing active in this room</p>
            <p className="text-base-content/60 mt-1 text-sm">
              Active alerts for this space will show up here.
            </p>
          </div>
        ) : (
          <ul className="divide-base-300 divide-y">
            {state.alerts.map((alert) => (
              <li key={alert.id}>
                <button
                  type="button"
                  className="hover:bg-base-300/40 flex w-full flex-col gap-1.5 px-4 py-3 text-left transition"
                  onClick={() => setSelectedAlertId(alert.id)}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className={severityBadgeClass(alert.severity)}>
                      {severityLabel(alert.severity)}
                    </span>
                    <time
                      className="text-base-content/45 font-mono text-[11px]"
                      dateTime={alert.triggeredAt}
                    >
                      {formatEventTime(alert.triggeredAt)}
                    </time>
                  </div>
                  <span className="text-sm font-medium leading-snug">
                    {humanizeAlertMessage(alert, { rooms: roomNames })}
                  </span>
                  <span className="text-base-content/55 text-sm leading-snug">
                    {adviceForAlert(alert)}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <AlertDrawer
        alertId={selectedAlertId}
        roomNames={roomNames}
        sensorNames={sensorNames}
        onClose={() => setSelectedAlertId(null)}
      />
    </div>
  )
}
