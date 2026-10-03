import { useEffect, useId, useState } from 'react'
import { Link } from 'react-router-dom'
import { getAlert, HomeApiError } from './api/homeApi'
import type { AlertDetail } from './api/types'
import { formatEventTime } from './roomDetail'

type DrawerLoadState = 'idle' | 'loading' | 'ready' | 'error' | 'not-found'

interface AlertDrawerProps {
  alertId: number | null
  onClose: () => void
}

function formatRuleSnapshot(snapshot: unknown): string {
  try {
    return JSON.stringify(snapshot, null, 2)
  } catch {
    return String(snapshot)
  }
}

export function AlertDrawer({ alertId, onClose }: AlertDrawerProps) {
  const titleId = useId()
  const [detail, setDetail] = useState<AlertDetail | null>(null)
  const [loadState, setLoadState] = useState<DrawerLoadState>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const open = alertId != null

  useEffect(() => {
    if (alertId == null) {
      setDetail(null)
      setLoadState('idle')
      setErrorMessage(null)
      return
    }

    let cancelled = false
    setLoadState('loading')
    setErrorMessage(null)
    setDetail(null)

    void getAlert(alertId)
      .then((alert) => {
        if (cancelled) return
        setDetail(alert)
        setLoadState('ready')
      })
      .catch((error: unknown) => {
        if (cancelled) return
        if (error instanceof HomeApiError && error.status === 404) {
          setLoadState('not-found')
          setErrorMessage('Alert not found.')
          return
        }
        setLoadState('error')
        setErrorMessage(error instanceof Error ? error.message : 'Failed to load alert')
      })

    return () => {
      cancelled = true
    }
  }, [alertId])

  useEffect(() => {
    if (!open) return

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onClose])

  if (!open) {
    return null
  }

  return (
    <div className="drawer-root" role="presentation">
      <button
        type="button"
        className="drawer-scrim"
        aria-label="Close alert detail"
        onClick={onClose}
      />
      <aside
        className="drawer-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <header className="drawer-header">
          <div>
            <p className="eyebrow">Alert detail</p>
            <h2 id={titleId}>
              {loadState === 'ready' && detail ? detail.severity : 'Alert'}
            </h2>
          </div>
          <button type="button" className="drawer-close" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="drawer-body">
          {loadState === 'loading' && <p className="banner">Loading alert…</p>}

          {(loadState === 'error' || loadState === 'not-found') && (
            <p className="banner error" role="alert">
              {errorMessage}
            </p>
          )}

          {loadState === 'ready' && detail && (
            <>
              <dl className="drawer-fields">
                <div>
                  <dt>Severity</dt>
                  <dd>{detail.severity}</dd>
                </div>
                <div>
                  <dt>Status</dt>
                  <dd>{detail.status}</dd>
                </div>
                <div>
                  <dt>Message</dt>
                  <dd>{detail.message}</dd>
                </div>
                <div>
                  <dt>Room</dt>
                  <dd>
                    <Link className="drawer-room-link" to={`/rooms/${detail.roomId}`} onClick={onClose}>
                      {detail.roomId}
                    </Link>
                  </dd>
                </div>
                <div>
                  <dt>Sensor</dt>
                  <dd>{detail.sensorId ?? '—'}</dd>
                </div>
                <div>
                  <dt>Trigger value</dt>
                  <dd>{detail.triggerValue ?? '—'}</dd>
                </div>
                <div>
                  <dt>Triggered</dt>
                  <dd>
                    <time dateTime={detail.triggeredAt}>{formatEventTime(detail.triggeredAt)}</time>
                  </dd>
                </div>
                <div>
                  <dt>Resolved</dt>
                  <dd>
                    {detail.resolvedAt ? (
                      <time dateTime={detail.resolvedAt}>{formatEventTime(detail.resolvedAt)}</time>
                    ) : (
                      '—'
                    )}
                  </dd>
                </div>
                <div>
                  <dt>Last evaluated</dt>
                  <dd>
                    <time dateTime={detail.lastEvaluatedAt}>
                      {formatEventTime(detail.lastEvaluatedAt)}
                    </time>
                  </dd>
                </div>
                <div>
                  <dt>Rule</dt>
                  <dd>{detail.ruleId}</dd>
                </div>
              </dl>

              <div className="drawer-snapshot">
                <p className="eyebrow">Rule snapshot</p>
                <pre>{formatRuleSnapshot(detail.ruleSnapshot)}</pre>
              </div>
            </>
          )}
        </div>
      </aside>
    </div>
  )
}
