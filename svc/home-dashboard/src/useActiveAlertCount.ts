import { useEffect, useState } from 'react'
import { listAlerts } from './api/homeApi'

const POLL_MS = 30_000
/** Fetch one past the badge cap so we can show "50+". */
const FETCH_LIMIT = 51

/**
 * Home-wide active alert count for the AppShell nav badge.
 * Independent of whichever page is mounted.
 */
export function useActiveAlertCount(): number {
  const [count, setCount] = useState(0)

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      try {
        const page = await listAlerts({ status: 'ACTIVE', limit: FETCH_LIMIT })
        if (cancelled) return
        const next =
          page.hasMore || page.items.length >= FETCH_LIMIT ? FETCH_LIMIT : page.items.length
        setCount(next)
      } catch {
        // Keep the last known count on transient failures.
      }
    }

    void load()
    const id = window.setInterval(() => void load(), POLL_MS)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  return count
}
