import type { ReactNode } from 'react'

export type KnownSensorKind = 'AIR' | 'ROOM' | 'PRESENCE' | 'OPENING'

const KIND_PRIORITY: KnownSensorKind[] = ['AIR', 'ROOM', 'PRESENCE', 'OPENING']

export function primarySensorKind(types: string[]): KnownSensorKind | null {
  for (const kind of KIND_PRIORITY) {
    if (types.includes(kind)) return kind
  }
  return null
}

/** Pairing often knows the kind before the first reading exists. */
export function inferSensorKindFromId(sensorId: string): KnownSensorKind | null {
  const lower = sensorId.trim().toLowerCase()
  for (const kind of KIND_PRIORITY) {
    const prefix = kind.toLowerCase()
    if (lower.startsWith(`${prefix}-`) || lower.startsWith(`${prefix}_`)) {
      return kind
    }
  }
  return null
}

export function resolveSensorKind(types: string[], sensorId: string): KnownSensorKind | null {
  return primarySensorKind(types) ?? inferSensorKindFromId(sensorId)
}

export function sensorKindLabel(kind: KnownSensorKind | string): string {
  switch (kind) {
    case 'AIR':
      return 'Air'
    case 'ROOM':
      return 'Room'
    case 'PRESENCE':
      return 'Presence'
    case 'OPENING':
      return 'Opening'
    default:
      return kind
  }
}

export function sensorKindBadgeClass(kind: KnownSensorKind | string): string {
  switch (kind) {
    case 'AIR':
      return 'badge-primary badge-soft'
    case 'ROOM':
      return 'badge-accent badge-soft'
    case 'PRESENCE':
      return 'badge-secondary badge-soft'
    case 'OPENING':
      return 'badge-info badge-soft'
    default:
      return 'badge-ghost'
  }
}

function IconShell({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      {children}
    </svg>
  )
}

export function SensorTypeIcon({
  kind,
  className = 'size-8',
}: {
  kind: KnownSensorKind | null
  className?: string
}) {
  switch (kind) {
    case 'AIR':
      return (
        <IconShell className={className}>
          <path d="M4 8c2.5-3 5.5-3 8 0s5.5 3 8 0" />
          <path d="M4 14c2.5-3 5.5-3 8 0s5.5 3 8 0" />
          <path d="M4 20c2.5-3 5.5-3 8 0s5.5 3 8 0" />
        </IconShell>
      )
    case 'ROOM':
      return (
        <IconShell className={className}>
          <path d="M12 3 4 8v11h16V8l-8-5Z" />
          <path d="M9 19v-6h6v6" />
          <path d="M12 8v2" />
        </IconShell>
      )
    case 'PRESENCE':
      return (
        <IconShell className={className}>
          <circle cx="12" cy="7" r="3" />
          <path d="M6 20c1.5-3.5 4-5 6-5s4.5 1.5 6 5" />
        </IconShell>
      )
    case 'OPENING':
      return (
        <IconShell className={className}>
          <path d="M5 4h9a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5" />
          <path d="M14 4v16" />
          <path d="M9 12h.01" />
          <path d="M16 8l3 4-3 4" />
        </IconShell>
      )
    default:
      return (
        <IconShell className={className}>
          <rect x="5" y="5" width="14" height="14" rx="2" />
          <path d="M9 12h6" />
          <path d="M12 9v6" />
        </IconShell>
      )
  }
}

export function sensorIconToneClass(kind: KnownSensorKind | null): string {
  switch (kind) {
    case 'AIR':
      return 'text-primary bg-primary/10'
    case 'ROOM':
      return 'text-accent bg-accent/10'
    case 'PRESENCE':
      return 'text-secondary bg-secondary/10'
    case 'OPENING':
      return 'text-info bg-info/10'
    default:
      return 'text-base-content/60 bg-base-200'
  }
}
