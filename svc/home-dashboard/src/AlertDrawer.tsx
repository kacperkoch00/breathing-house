import { useEffect, useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { getAlert, HomeApiError } from './api/homeApi'
import type { AlertDetail } from './api/types'
import { humanizeAlertMessage } from './overview'
import { formatEventTime } from './roomDetail'
import { severityBadgeClass, severityLabel } from './ui'

type DrawerLoadState = 'idle' | 'loading' | 'ready' | 'error' | 'not-found'

interface AlertDrawerProps {
  alertId: number | null
  roomNames?: Record<string, string>
  sensorNames?: Record<string, string>
  onClose: () => void
}

function formatRuleSnapshot(snapshot: unknown): string {
  try {
    return JSON.stringify(snapshot, null, 2)
  } catch {
    return String(snapshot)
  }
}

export function AlertDrawer({ alertId, roomNames, sensorNames, onClose }: AlertDrawerProps) {
  const titleId = useId()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const [detail, setDetail] = useState<AlertDetail | null>(null)
  const [loadState, setLoadState] = useState<DrawerLoadState>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const open = alertId != null

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    if (open && !dialog.open) {
      dialog.showModal()
    } else if (!open && dialog.open) {
      dialog.close()
    }
  }, [open])

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

  return (
    <dialog
      ref={dialogRef}
      className="modal modal-end"
      aria-labelledby={titleId}
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <div className="modal-box bg-base-100 border-base-300 flex h-full max-h-full w-full max-w-md flex-col rounded-none border-l p-0">
        <header className="border-base-300 flex items-start justify-between gap-4 border-b p-5">
          <div>
            <p className="text-base-content/45 font-mono text-[11px] tracking-[0.16em] uppercase">
              Alert detail
            </p>
            <h2 id={titleId} className="mt-1 text-xl font-semibold">
                  {loadState === 'ready' && detail ? (
                <span className="inline-flex items-center gap-2">
                  <span className={severityBadgeClass(detail.severity)}>
                    {severityLabel(detail.severity)}
                  </span>
                  Alert
                </span>
              ) : (
                'Alert'
              )}
            </h2>
          </div>
          <button type="button" className="btn btn-ghost btn-sm font-mono" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="flex-1 overflow-auto p-5">
          {loadState === 'loading' && (
            <div className="flex items-center gap-3 py-6">
              <span className="loading loading-spinner loading-md text-primary" />
              <span className="font-mono text-sm">Loading alert…</span>
            </div>
          )}

          {(loadState === 'error' || loadState === 'not-found') && (
            <div role="alert" className="alert alert-error alert-soft">
              <span className="font-mono text-sm">{errorMessage}</span>
            </div>
          )}

          {loadState === 'ready' && detail && (
            <>
              <dl className="divide-base-300 divide-y text-sm">
                {[
                  ['Severity', severityLabel(detail.severity)],
                  ['Status', detail.status === 'ACTIVE' ? 'Active' : 'Resolved'],
                  [
                    'What happened',
                    humanizeAlertMessage(detail, {
                      rooms: roomNames,
                      sensors: sensorNames,
                    }),
                  ],
                  [
                    'Sensor',
                    detail.sensorId
                      ? (sensorNames?.[detail.sensorId] ?? detail.sensorId)
                      : '—',
                  ],
                  ['Trigger value', detail.triggerValue ?? '—'],
                  ['Rule', detail.ruleId],
                ].map(([label, value]) => (
                  <div key={label} className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-3 py-3">
                    <dt className="text-base-content/45 text-[11px] tracking-wide uppercase">
                      {label}
                    </dt>
                    <dd className="break-words text-sm">{value}</dd>
                  </div>
                ))}
                <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-3 py-3">
                  <dt className="text-base-content/45 font-mono text-[11px] tracking-wide uppercase">
                    Room
                  </dt>
                  <dd>
                    <Link
                      className="link link-primary text-xs sm:text-sm"
                      to={`/rooms/${detail.roomId}`}
                      onClick={onClose}
                    >
                      {roomNames?.[detail.roomId] ?? detail.roomId}
                    </Link>
                  </dd>
                </div>
                <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-3 py-3">
                  <dt className="text-base-content/45 font-mono text-[11px] tracking-wide uppercase">
                    Triggered
                  </dt>
                  <dd className="font-mono text-xs sm:text-sm">
                    <time dateTime={detail.triggeredAt}>{formatEventTime(detail.triggeredAt)}</time>
                  </dd>
                </div>
                <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-3 py-3">
                  <dt className="text-base-content/45 font-mono text-[11px] tracking-wide uppercase">
                    Resolved
                  </dt>
                  <dd className="font-mono text-xs sm:text-sm">
                    {detail.resolvedAt ? (
                      <time dateTime={detail.resolvedAt}>{formatEventTime(detail.resolvedAt)}</time>
                    ) : (
                      '—'
                    )}
                  </dd>
                </div>
                <div className="grid grid-cols-[7.5rem_minmax(0,1fr)] gap-3 py-3">
                  <dt className="text-base-content/45 font-mono text-[11px] tracking-wide uppercase">
                    Last evaluated
                  </dt>
                  <dd className="font-mono text-xs sm:text-sm">
                    <time dateTime={detail.lastEvaluatedAt}>
                      {formatEventTime(detail.lastEvaluatedAt)}
                    </time>
                  </dd>
                </div>
              </dl>

              <div className="mt-5">
                <p className="text-base-content/45 mb-2 font-mono text-[11px] tracking-[0.16em] uppercase">
                  Rule snapshot
                </p>
                <pre className="bg-base-200 border-base-300 overflow-auto border p-3 font-mono text-[11px] break-words whitespace-pre-wrap">
                  {formatRuleSnapshot(detail.ruleSnapshot)}
                </pre>
              </div>
            </>
          )}
        </div>
      </div>
      <form method="dialog" className="modal-backdrop">
        <button type="submit" aria-label="Close alert detail">
          close
        </button>
      </form>
    </dialog>
  )
}
