import { useCallback, useEffect, useState } from 'react'

const DEFAULT_INTERVAL_MS = 30_000

interface UseAutoRefreshOptions {
  intervalMs?: number
  enabled?: boolean
}

/**
 * Mount-load once, poll with soft refresh, expose manual refresh + clock.
 * `load(true)` must not flash a full-page loading state.
 */
export function useAutoRefresh(
  load: (isRefresh: boolean) => Promise<void>,
  options: UseAutoRefreshOptions = {},
) {
  const intervalMs = options.intervalMs ?? DEFAULT_INTERVAL_MS
  const enabled = options.enabled ?? true
  const [refreshing, setRefreshing] = useState(false)
  const [clock, setClock] = useState(() => new Date())

  const refresh = useCallback(async () => {
    if (refreshing) return
    setRefreshing(true)
    try {
      await load(true)
      setClock(new Date())
    } finally {
      setRefreshing(false)
    }
  }, [load, refreshing])

  useEffect(() => {
    if (!enabled) return
    void load(false)
    const pollId = window.setInterval(() => {
      void load(true)
    }, intervalMs)
    return () => window.clearInterval(pollId)
  }, [load, intervalMs, enabled])

  useEffect(() => {
    const clockId = window.setInterval(() => setClock(new Date()), 30_000)
    return () => window.clearInterval(clockId)
  }, [])

  return { refreshing, refresh, clock }
}
