import type { AlertSeverity } from './api/types'
import type { ComfortBand } from './comfort'

export function severityLabel(severity: AlertSeverity | string): string {
  switch (severity) {
    case 'CRITICAL':
      return 'Critical'
    case 'WARNING':
      return 'Warning'
    case 'INFO':
      return 'Notice'
    default:
      return String(severity)
  }
}

export function severityBadgeClass(severity: AlertSeverity | string): string {
  switch (severity) {
    case 'CRITICAL':
      return 'badge badge-error badge-soft badge-sm tracking-wide'
    case 'WARNING':
      return 'badge badge-warning badge-soft badge-sm tracking-wide'
    case 'INFO':
      return 'badge badge-info badge-soft badge-sm tracking-wide'
    default:
      return 'badge badge-ghost badge-sm tracking-wide'
  }
}

export function comfortBadgeClass(band: ComfortBand): string {
  switch (band) {
    case 'good':
      return 'badge badge-success badge-soft badge-sm font-mono tracking-wide'
    case 'moderate':
      return 'badge badge-warning badge-soft badge-sm font-mono tracking-wide'
    case 'poor':
      return 'badge badge-warning badge-sm font-mono tracking-wide'
    case 'bad':
      return 'badge badge-error badge-sm font-mono tracking-wide'
    default:
      return 'badge badge-ghost badge-sm font-mono tracking-wide'
  }
}

export function metricBandClass(band: ComfortBand): string {
  switch (band) {
    case 'good':
      return 'text-success'
    case 'moderate':
      return 'text-warning'
    case 'poor':
      return 'text-warning'
    case 'bad':
      return 'text-error'
    default:
      return 'text-base-content/45'
  }
}

export function metricTileClass(band: ComfortBand): string {
  switch (band) {
    case 'good':
      return 'border-success/30 bg-success/10'
    case 'moderate':
      return 'border-warning/30 bg-warning/10'
    case 'poor':
      return 'border-warning/40 bg-warning/15'
    case 'bad':
      return 'border-error/40 bg-error/15'
    default:
      return 'border-base-300 bg-base-200/60'
  }
}

export function scoreRingClass(band: ComfortBand): string {
  switch (band) {
    case 'good':
      return 'text-success'
    case 'moderate':
      return 'text-warning'
    case 'poor':
      return 'text-warning'
    case 'bad':
      return 'text-error'
    default:
      return 'text-base-content/40'
  }
}

export function roomCardBorderClass(band: ComfortBand, alerted: boolean): string {
  if (alerted || band === 'bad') {
    return 'border-l-4 border-l-error'
  }
  switch (band) {
    case 'poor':
    case 'moderate':
      return 'border-l-4 border-l-warning'
    case 'good':
      return 'border-l-4 border-l-success'
    default:
      return 'border-l-4 border-l-base-300'
  }
}

export function homeStatusLabel(
  gatewayOnline: boolean | null | undefined,
  alertCount: number,
): { label: string; className: string } {
  if (gatewayOnline === false) {
    return { label: 'Gateway offline', className: 'badge badge-error badge-sm gap-2 font-mono' }
  }
  if (alertCount > 0) {
    return { label: 'Needs attention', className: 'badge badge-warning badge-sm gap-2 font-mono' }
  }
  if (gatewayOnline == null) {
    return { label: 'Status unknown', className: 'badge badge-ghost badge-sm gap-2 font-mono' }
  }
  return { label: 'Home healthy', className: 'badge badge-success badge-soft badge-sm gap-2 font-mono' }
}

export function homeStatusDotClass(
  gatewayOnline: boolean | null | undefined,
  alertCount: number,
): string {
  if (gatewayOnline === false) return 'status status-error'
  if (alertCount > 0) return 'status status-warning'
  if (gatewayOnline == null) return 'status status-neutral'
  return 'status status-success'
}
