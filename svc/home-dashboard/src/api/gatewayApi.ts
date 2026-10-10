export interface GatewayCandidate {
  sensorId: string
  type: string | null
  bleAddress?: string
  rssi?: number | null
}

export interface GatewayScanStatus {
  state: 'idle' | 'scanning' | 'complete'
  requestId: string | null
  expiresAt: string | null
  candidates: GatewayCandidate[]
  windowSeconds?: number
  bleError?: string
}

export class GatewayApiError extends Error {
  readonly status: number

  constructor(status: number, message?: string) {
    super(message ?? `gateway request failed (${status})`)
    this.name = 'GatewayApiError'
    this.status = status
  }
}

export function getGatewayBaseUrl(): string {
  const configured = import.meta.env.VITE_GATEWAY_BASE_URL?.trim()
  return configured && configured.length > 0
    ? configured.replace(/\/$/, '')
    : 'http://breathinghouse.local:8090'
}

async function gatewayJson<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = {}
  let body: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }
  const response = await fetch(`${getGatewayBaseUrl()}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body,
  })
  if (!response.ok) {
    let message: string | undefined
    try {
      const data: unknown = await response.json()
      if (data && typeof data === 'object' && 'message' in data && typeof data.message === 'string') {
        message = data.message
      }
    } catch {
      // status only
    }
    throw new GatewayApiError(response.status, message)
  }
  return response.json() as Promise<T>
}

export function startGatewayScan(): Promise<GatewayScanStatus> {
  return gatewayJson<GatewayScanStatus>('/scan', { method: 'POST' })
}

export function listGatewayCandidates(): Promise<GatewayScanStatus> {
  return gatewayJson<GatewayScanStatus>('/candidates')
}

export function admitGatewaySensor(sensorId: string): Promise<{ sensorId: string; state: string }> {
  return gatewayJson('/admit', { method: 'POST', body: { sensorId } })
}