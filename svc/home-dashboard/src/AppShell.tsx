import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { homeStatusDotClass, homeStatusLabel } from './ui'
import { useActiveAlertCount } from './useActiveAlertCount'

interface AppShellProps {
  children: ReactNode
  gatewayOnline?: boolean | null
  /** Optional override; nav badge always uses the home-wide active count. */
  alertCount?: number
  clockLabel?: string
  footerLeft?: string
}

export function AppShell({
  children,
  gatewayOnline,
  alertCount,
  clockLabel,
  footerLeft = 'Breathing House',
}: AppShellProps) {
  const { pathname } = useLocation()
  const activeAlertCount = useActiveAlertCount()
  const statusAlertCount = alertCount ?? activeAlertCount
  const onSetup = pathname.startsWith('/setup')
  const onAlerts = pathname.startsWith('/alerts')
  const onSensors = pathname.startsWith('/sensors')
  const showHomeStatus = gatewayOnline !== undefined
  const status = homeStatusLabel(gatewayOnline, statusAlertCount)
  const statusDot = homeStatusDotClass(gatewayOnline, statusAlertCount)
  const alertBadgeLabel = activeAlertCount > 50 ? '50+' : String(activeAlertCount)

  return (
    <div className="bg-base-200 min-h-screen">
      <header className="border-base-300 bg-base-100/80 border-b backdrop-blur">
        <nav
          className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6"
          aria-label="Main navigation"
        >
          <div className="flex min-w-0 items-center gap-3">
            <Link to="/" className="btn btn-ghost h-auto min-h-0 gap-2 px-2 py-1">
              <span className="bg-primary text-primary-content flex size-8 shrink-0 items-center justify-center rounded-sm text-[10px] font-bold tracking-[0.14em]">
                BH
              </span>
              <span className="truncate text-sm font-semibold tracking-wide uppercase">
                Breathing House
              </span>
            </Link>
            {showHomeStatus && (
              <span className={status.className}>
                <span className={statusDot} />
                {status.label}
              </span>
            )}
          </div>

          <div className="flex shrink-0 items-center gap-2">
            {clockLabel && (
              <span className="text-base-content/50 hidden font-mono text-xs tracking-wide sm:inline">
                {clockLabel}
              </span>
            )}
            <span className="badge badge-ghost badge-sm font-mono tracking-wide">LIVE</span>
            {onAlerts ? (
              <span className="btn btn-sm btn-soft gap-1.5" aria-current="page">
                Alerts
                {activeAlertCount > 0 && (
                  <span className="badge badge-warning badge-sm font-mono">{alertBadgeLabel}</span>
                )}
              </span>
            ) : (
              <Link to="/alerts" className="btn btn-ghost btn-sm gap-1.5">
                Alerts
                {activeAlertCount > 0 && (
                  <span className="badge badge-warning badge-sm font-mono">{alertBadgeLabel}</span>
                )}
              </Link>
            )}
            {onSensors ? (
              <span className="btn btn-sm btn-soft" aria-current="page">
                Sensors
              </span>
            ) : (
              <Link to="/sensors" className="btn btn-ghost btn-sm">
                Sensors
              </Link>
            )}
            {onSetup ? (
              <span className="btn btn-sm btn-soft" aria-current="page">
                Setup
              </span>
            ) : (
              <Link to="/setup" className="btn btn-ghost btn-sm">
                Setup
              </Link>
            )}
          </div>
        </nav>
      </header>

      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">{children}</main>

      <footer className="border-base-300 text-base-content/45 mx-auto flex max-w-7xl items-center justify-between gap-4 border-t px-4 py-4 font-mono text-xs tracking-wide sm:px-6">
        <span>{footerLeft}</span>
        <span>Breathing House · v0.1</span>
      </footer>
    </div>
  )
}
