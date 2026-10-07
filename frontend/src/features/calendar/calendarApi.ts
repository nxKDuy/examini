import api from '../../lib/api'

export type DeadlineRule = 'FULL_DURATION' | 'CAPPED_AT_END' | 'REQUIRE_FULL_WINDOW'

/** GET /exams/calendar item. Instants are UTC ISO strings; `timezone` is the Exam's IANA zone. */
export interface CalendarItem {
  examId: string; examTitle: string; status: string
  startAt: string | null; endAt: string | null; timezone: string; durationMinutes: number; deadlineRule: DeadlineRule
  canJoin: boolean; canStart: boolean; canResume: boolean; reasonCode: string | null; attemptsRemaining: number | null
  effectiveExpiresAt: string | null; relation: string; followed: boolean; participant: boolean
  participantCount: number | null; maxParticipants: number | null   // Teacher only
  calendarState: string; latestSessionId: string | null; latestSessionStatus: string | null  // Student only
  serverNow?: string
}
export interface Availability extends Omit<CalendarItem, 'relation' | 'participantCount' | 'maxParticipants'> { canView: boolean; scheduleRevision: number }

export interface Schedule {
  examId: string; startAt: string | null; endAt: string | null; timezone: string; deadlineRule: DeadlineRule
  autoOpen: boolean; autoClose: boolean; reminderOffsetsMinutes: number[]; scheduleRevision: number; lifecycleRevision: number; serverNow: string
}
export interface ScheduleInput {
  startAt: string | null; endAt: string | null; timezone: string; deadlineRule: DeadlineRule
  autoOpen: boolean; autoClose: boolean; reminderOffsetsMinutes: number[]; expectedRevision: number; reason?: string
}
export interface FollowState { examId: string; followed: boolean; participant: boolean }
interface Page<T> { content: T[]; totalPages: number; last?: boolean }

export interface CalendarResult { items: CalendarItem[]; clockOffset: number; truncated: boolean }
const MAX_PAGES = 30

/** Reads every page of the range; the server pages at <= 100 so a single page is never "the whole calendar". */
export async function fetchCalendar(fromIso: string, toIso: string, includeUnscheduled: boolean): Promise<CalendarResult> {
  const items: CalendarItem[] = []
  let clockOffset = 0, truncated = false
  for (let page = 0; ; page++) {
    const res = await api.get<Page<CalendarItem>>('/exams/calendar', { params: { from: fromIso, to: toIso, includeUnscheduled, page, size: 100 } })
    items.push(...res.data.content)
    const now = res.data.content[0]?.serverNow
    if (now && page === 0) clockOffset = Date.parse(now) - Date.now()
    if (res.data.last || page + 1 >= res.data.totalPages) break
    if (page + 1 >= MAX_PAGES) { truncated = true; break }
  }
  return { items, clockOffset, truncated }
}

export const calendarApi = {
  getSchedule: (id: string) => api.get<Schedule>(`/exams/${id}/schedule`).then(r => r.data),
  putSchedule: (id: string, body: ScheduleInput) => api.put<Schedule>(`/exams/${id}/schedule`, body).then(r => r.data),
  availability: (id: string) => api.get<Availability>(`/exams/${id}/availability`).then(r => r.data),
  codePreview: (code: string) => api.get<Availability>(`/exams/code/${encodeURIComponent(code)}/preview`).then(r => r.data),
  follow: (id: string) => api.put<FollowState>(`/exams/${id}/follow`).then(r => r.data),
  unfollow: (id: string) => api.delete<FollowState>(`/exams/${id}/follow`).then(r => r.data),
}
