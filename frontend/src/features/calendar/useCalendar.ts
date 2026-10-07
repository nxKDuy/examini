import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { addDays, instantToDay, viewerZone } from '../../lib/zone'
import { fetchCalendar, type CalendarItem } from './calendarApi'
import { agendaRange, rangeOf, visibleDays, type CalendarView } from './calendarModel'
import { useIsNarrow, useNow } from '../../lib/hooks'

export { useIsNarrow, useNow }

export const useViewerZone = () => useMemo(viewerZone, [])

const EMPTY: CalendarItem[] = []

/** Agenda data: 30 days back .. 330 days ahead, plus Exams without a schedule. Shared by the page and dashboards. */
export function useAgendaCalendar(enabled = true) {
  const tz = useViewerZone()
  const userId = useAuthStore(s => s.user?.id)
  const today = instantToDay(Date.now(), tz)
  const { from, to } = useMemo(() => agendaRange(today, tz), [today, tz])
  const q = useQuery({
    queryKey: ['calendar', userId, 'agenda', from, to], queryFn: () => fetchCalendar(from, to, true),
    staleTime: 30_000, refetchOnWindowFocus: true, enabled,
  })
  return { ...q, tz, items: q.data?.items ?? EMPTY, clockOffset: q.data?.clockOffset ?? 0, truncated: q.data?.truncated ?? false }
}

/** Grid data for month / week / day (scheduled Exams only; unscheduled ones never get an invented date). */
export function useGridCalendar(view: Exclude<CalendarView, 'agenda'>, anchor: string, enabled = true) {
  const tz = useViewerZone()
  const userId = useAuthStore(s => s.user?.id)
  const days = useMemo(() => visibleDays(view, anchor), [view, anchor])
  const { from, to } = useMemo(() => rangeOf(days, tz), [days, tz])
  const q = useQuery({
    queryKey: ['calendar', userId, 'grid', from, to], queryFn: () => fetchCalendar(from, to, false),
    staleTime: 30_000, refetchOnWindowFocus: true, enabled,
  })
  return { ...q, tz, days, items: q.data?.items ?? EMPTY, clockOffset: q.data?.clockOffset ?? 0, truncated: q.data?.truncated ?? false }
}

export const todayIn = (tz: string, nowMs = Date.now()) => instantToDay(nowMs, tz)
export const tomorrowIn = (tz: string, nowMs = Date.now()) => addDays(instantToDay(nowMs, tz), 1)
