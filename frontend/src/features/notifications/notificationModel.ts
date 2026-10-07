import type { AppNotification } from './notificationApi'

export type NotificationIcon = 'calendar-plus' | 'calendar-clock' | 'calendar-x' | 'play' | 'lock' | 'bell' | 'result' | 'send' | 'approved' | 'rejected' | 'member' | 'assigned'

const ICONS: Record<string, NotificationIcon> = {
  EXAM_SCHEDULED: 'calendar-plus', EXAM_RESCHEDULED: 'calendar-clock', EXAM_UNSCHEDULED: 'calendar-x', EXAM_OPENED: 'play', EXAM_CLOSED: 'lock',
  EXAM_REMINDER: 'bell', RESULT_AVAILABLE: 'result', SUBMISSION_RECEIVED: 'send', QUESTION_APPROVED: 'approved', QUESTION_REJECTED: 'rejected',
  EXAM_MEMBER_INVITED: 'member', EXAM_ASSIGNED: 'assigned',
}
export const notificationIcon = (type: string): NotificationIcon => ICONS[type] ?? 'bell' // new types render generically

/** In-app destination. The backend supplies the exact SPA route in metadata.link; only same-origin paths are accepted. */
export function notificationTarget(n: Pick<AppNotification, 'entityType' | 'entityId' | 'metadata'>, student: boolean): string {
  const link = n.metadata?.link
  if (typeof link === 'string' && /^\/(?![/\\])/.test(link)) return link
  if (n.entityType === 'EXAM_SESSION') return `/exam/result/${n.entityId}`
  if (n.entityType === 'EXAM') return student ? `/exam/lobby/${n.entityId}` : `/exams/${n.entityId}`
  if (n.entityType === 'QUESTION') return '/questions'
  return '/dashboard'
}

const rtf = new Intl.RelativeTimeFormat('vi', { numeric: 'always' })
export function relativeTime(iso: string, nowMs: number): string {
  const diff = Math.round((Date.parse(iso) - nowMs) / 1000)
  const s = Math.abs(diff)
  if (s < 45) return 'vừa xong'
  if (s < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (s < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (s < 7 * 86400) return rtf.format(Math.round(diff / 86400), 'day')
  return new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(iso))
}

export const unreadLabel = (count: number) => (count > 99 ? '99+' : String(count))
