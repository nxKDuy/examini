import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { addDays, instantToDay, viewerZone } from '../../lib/zone'
import { fetchCalendar, type CalendarItem } from './calendarApi'
import { agendaRange, nextBoundary, rangeOf, visibleDays, type CalendarView } from './calendarModel'
import { useIsNarrow, useNow } from '../../lib/hooks'

export { useIsNarrow, useNow }

export const useViewerZone = () => useMemo(viewerZone, [])

const EMPTY: CalendarItem[] = []
const MAX_TIMEOUT = 2 ** 31 - 1
const GRACE_MS = 1500
const LIGHT_REFRESH_MS = 120_000

/**
 * Ask the backend again just after the next boundary (a window opens or closes, an attempt expires) while the tab stays
 * open. The corrected clock only decides WHEN to ask; what is allowed always comes from the new answer, never from the clock.
 */
export function useBoundaryRefetch(items: Pick<CalendarItem, 'startAt' | 'endAt' | 'effectiveExpiresAt'>[], clockOffset: number, refetch: () => unknown, enabled = true) {
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!enabled) return
    const next = nextBoundary(items, Date.now() + clockOffset)
    if (next === null) return
    const wait = Math.min(Math.max(next - (Date.now() + clockOffset), 0) + GRACE_MS, MAX_TIMEOUT)
    const timer = setTimeout(() => { setTick(t => t + 1); void refetch() }, wait)
    return () => clearTimeout(timer)
    // `tick` re-arms the timer for the following boundary even when the refetched data is identical.
  }, [items, clockOffset, refetch, enabled, tick])
}

/** Agenda data: 30 days back .. 330 days ahead, plus Exams without a schedule. Shared by the page and dashboards. */
export function useAgendaCalendar(enabled = true) {
  const tz = useViewerZone()
  const userId = useAuthStore(s => s.user?.id)
  const today = instantToDay(Date.now(), tz)
  const { from, to } = useMemo(() => agendaRange(today, tz), [today, tz])
  const q = useQuery({
    queryKey: ['calendar', userId, 'agenda', from, to], queryFn: () => fetchCalendar(from, to, true),
    staleTime: 30_000, refetchOnWindowFocus: true, refetchInterval: LIGHT_REFRESH_MS, enabled,
  })
  const items = q.data?.items ?? EMPTY, clockOffset = q.data?.clockOffset ?? 0
  useBoundaryRefetch(items, clockOffset, q.refetch, enabled)
  return { ...q, tz, items, clockOffset, truncated: q.data?.truncated ?? false }
}

/** Grid data for a month (or week / day). Unscheduled Exams only come back when asked for, and never get an invented date. */
export function useGridCalendar(view: Exclude<CalendarView, 'agenda'>, anchor: string, enabled = true, includeUnscheduled = false) {
  const tz = useViewerZone()
  const userId = useAuthStore(s => s.user?.id)
  const days = useMemo(() => visibleDays(view, anchor), [view, anchor])
  const { from, to } = useMemo(() => rangeOf(days, tz), [days, tz])
  const q = useQuery({
    queryKey: ['calendar', userId, 'grid', from, to, includeUnscheduled], queryFn: () => fetchCalendar(from, to, includeUnscheduled),
    staleTime: 30_000, refetchOnWindowFocus: true, refetchInterval: LIGHT_REFRESH_MS, enabled,
  })
  const items = q.data?.items ?? EMPTY, clockOffset = q.data?.clockOffset ?? 0
  useBoundaryRefetch(items, clockOffset, q.refetch, enabled)
  return { ...q, tz, days, items, clockOffset, truncated: q.data?.truncated ?? false }
}

export const todayIn = (tz: string, nowMs = Date.now()) => instantToDay(nowMs, tz)
export const tomorrowIn = (tz: string, nowMs = Date.now()) => addDays(instantToDay(nowMs, tz), 1)
