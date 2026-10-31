import type { AppNotification } from './notificationApi'
import { t, locale } from '../../lib/i18n'

export type NotificationIcon = 'calendar-plus' | 'calendar-clock' | 'calendar-x' | 'play' | 'lock' | 'bell' | 'result' | 'send' | 'approved' | 'rejected' | 'member' | 'assigned'

const ICONS: Record<string, NotificationIcon> = {
  EXAM_SCHEDULED: 'calendar-plus', EXAM_RESCHEDULED: 'calendar-clock', EXAM_UNSCHEDULED: 'calendar-x', EXAM_OPENED: 'play', EXAM_CLOSED: 'lock',
  EXAM_REMINDER: 'bell', RESULT_AVAILABLE: 'result', SUBMISSION_RECEIVED: 'send', QUESTION_APPROVED: 'approved', QUESTION_REJECTED: 'rejected',
  EXAM_MEMBER_INVITED: 'member', EXAM_ASSIGNED: 'assigned', RESULT_REGRADED: 'result', GRADING_REVIEW_REQUIRED: 'send',
}
export const notificationIcon = (type: string): NotificationIcon => ICONS[type] ?? 'bell' // new types render generically

/** In-app destination. The backend supplies the exact SPA route in metadata.link; only same-origin paths are accepted. */
export function notificationTarget(n: Pick<AppNotification, 'entityType' | 'entityId' | 'metadata'>, student: boolean): string {
  const link = n.metadata?.link
  if (typeof link === 'string' && /^\/(?![/\\])/.test(link)) return link
  if (n.entityType === 'EXAM_SESSION') return `/exam/result/${n.entityId}`
  if (n.entityType === 'EXAM') return student ? `/exam/lobby/${n.entityId}` : `/exams/${n.entityId}`
  if (n.entityType === 'ORGANIZATION') return `/organizations/${n.entityId}`
  if (n.entityType === 'ORGANIZATION_INVITATION') return '/organizations?tab=invitations'
  if (n.entityType === 'QUESTION') return '/questions'
  return '/dashboard'
}

const rtfs = new Map<string, Intl.RelativeTimeFormat>()
const rtfFor = (loc: string) => { let f = rtfs.get(loc); if (!f) { f = new Intl.RelativeTimeFormat(loc.slice(0, 2), { numeric: 'always' }); rtfs.set(loc, f) } return f }
export function relativeTime(iso: string, nowMs: number): string {
  const rtf = rtfFor(locale())
  const diff = Math.round((Date.parse(iso) - nowMs) / 1000)
  const s = Math.abs(diff)
  if (s < 45) return t('vừa xong')
  if (s < 3600) return rtf.format(Math.round(diff / 60), 'minute')
  if (s < 86400) return rtf.format(Math.round(diff / 3600), 'hour')
  if (s < 7 * 86400) return rtf.format(Math.round(diff / 86400), 'day')
  return new Intl.DateTimeFormat(locale(), { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(iso))
}

export const unreadLabel = (count: number) => (count > 99 ? '99+' : String(count))

// ---- Client-side wording for server notices (scope: i18n) -------------------------------------
// The server stores Vietnamese title/message. In another language the text is rebuilt from type +
// metadata using the same facts the server used; anything unknown or incomplete keeps the server text.
const quote = (s: string) => `“${s}”`

function zoned(iso: string | undefined, zone: string | undefined) {
  if (!iso || !zone) return null
  try {
    const f = new Intl.DateTimeFormat('en-GB', { timeZone: zone, hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric', hour12: false, timeZoneName: 'shortOffset' })
    return `${f.format(new Date(iso))}, ${zone}`
  } catch { return null }
}

const DEADLINE_EN: Record<string, string> = {
  CAPPED_AT_END: 'The attempt ends at the latest at the scheduled end.',
  REQUIRE_FULL_WINDOW: 'You can start only while the full duration still fits before the end.',
}

function windowEn(m: Record<string, string>) {
  const parts: string[] = []
  const start = zoned(m.startAt, m.timezone), end = zoned(m.endAt, m.timezone)
  if (m.startAt && !start) return null
  if (m.endAt && !end) return null
  if (start) parts.push(`Starts: ${start}.`)
  if (end) parts.push(`Ends: ${end}.`)
  parts.push(DEADLINE_EN[m.deadlineRule ?? ''] ?? 'The duration counts from when you start.')
  return parts.join(' ')
}

/** English title/message for a known notice type; null keeps the server's own text. */
export function englishNotice(n: Pick<AppNotification, 'type' | 'metadata' | 'createdAt'>): { title: string; message: string } | null {
  const m = n.metadata ?? {}
  const title = m.examTitle ? quote(m.examTitle) : null
  const needTitle = (fn: (q: string) => { title: string; message: string } | null) => (title ? fn(title) : null)
  switch (n.type) {
    case 'EXAM_SCHEDULED': return needTitle(q => { const w = windowEn(m); return w ? { title: 'Exam scheduled', message: `Exam ${q} has been scheduled. ${w}` } : null })
    case 'EXAM_RESCHEDULED': return needTitle(q => { const w = windowEn(m); return w ? { title: 'Exam schedule changed', message: `Exam ${q} has a new schedule. ${w}` } : null })
    case 'EXAM_UNSCHEDULED': return needTitle(q => ({ title: 'Exam schedule removed', message: `Exam ${q} no longer has a fixed schedule. Watch for the teacher's next notice.` }))
    case 'EXAM_REMINDER': return needTitle(q => { const w = windowEn(m); return w ? { title: 'Exam starting soon', message: `Reminder: exam ${q} is about to start. ${w}` } : null })
    case 'EXAM_OPENED': return needTitle(q => {
      const start = m.startAt && Date.parse(m.startAt) > Date.parse(n.createdAt) ? zoned(m.startAt, m.timezone) : null
      return { title: 'Exam opened', message: `Exam ${q} is open.${start ? ` You can start from ${start}.` : ''}` }
    })
    case 'EXAM_CLOSED': return needTitle(q => ({ title: 'Exam closed', message: `Exam ${q} is closed and accepts no new starts.` }))
    case 'GRADING_REVIEW_REQUIRED': return needTitle(q => ({ title: 'Submissions need grading', message: `Exam ${q} has submissions waiting for teacher review and grading. Open the grading queue to handle them.` }))
    case 'RESULT_AVAILABLE': return needTitle(q => ({ title: 'Exam result available', message: m.gradingMode === 'MANUAL' ? `The teacher has finished grading exam ${q}. View your result now.` : `The result of exam ${q} is ready. View your result now.` }))
    case 'RESULT_REGRADED': return needTitle(q => ({ title: 'Exam result updated', message: `Your result for exam ${q} was updated after regrading. View the latest result.` }))
    case 'EXAM_ASSIGNED': return needTitle(q => ({ title: 'New exam assigned', message: `You have been assigned exam ${q}.` }))
    case 'EXAM_MEMBER_INVITED': return needTitle(q => m.role === 'MANAGER' || m.role === 'PROCTOR'
      ? { title: 'Exam collaboration invite', message: `You are invited to be ${m.role === 'MANAGER' ? 'a Manager' : 'a Proctor'} of exam ${q}. Accept the invitation to join.` } : null)
    case 'SUBMISSION_RECEIVED': return needTitle(q => ({ title: 'Submission received', message: m.submissionType === 'AUTO_SUBMITTED' ? `Time was up; your attempt for exam ${q} was submitted automatically.` : `Your attempt for exam ${q} was submitted successfully.` }))
    case 'QUESTION_APPROVED': return m.questionVersion ? { title: 'Question approved', message: `Version ${m.questionVersion} of the question was approved.` } : null
    case 'QUESTION_REJECTED': return m.questionVersion ? { title: 'Question rejected', message: `Version ${m.questionVersion} of the question was rejected. See the review note in the question bank.` } : null
    default: return null
  }
}

/** Title/message in the UI language: Vietnamese is the server text; English is rebuilt when the type is known. */
export function localizedNotice(n: AppNotification, lang: 'vi' | 'en') {
  return (lang === 'en' && englishNotice(n)) || { title: n.title, message: n.message }
}

export interface PreferenceRow { category: 'SCHEDULE' | 'REMINDER' | 'EXAM_STATUS' | 'RESULT' | 'GRADING'; label: string; hint: string }
const PREF_ROWS: PreferenceRow[] = [
  { category: 'SCHEDULE', label: 'Lịch thi', hint: 'Đặt, đổi hoặc gỡ lịch thi' },
  { category: 'REMINDER', label: 'Nhắc giờ thi', hint: 'Nhắc trước giờ bắt đầu' },
  { category: 'EXAM_STATUS', label: 'Mở/đóng kỳ thi', hint: 'Khi kỳ thi mở hoặc đóng' },
  { category: 'RESULT', label: 'Kết quả', hint: 'Công bố điểm, chấm lại' },
  { category: 'GRADING', label: 'Cần chấm tay', hint: 'Bài tự luận chờ giáo viên chấm' },
]
/** Students never get grading-review notices; staff never get their own result notices. */
export const preferenceRows = (student: boolean) => PREF_ROWS.filter(r => (student ? r.category !== 'GRADING' : r.category !== 'RESULT'))

export type InboxGroup = 'SCHEDULE' | 'RESULT' | 'OTHER'
export const INBOX_GROUPS: { value: InboxGroup | undefined; label: string }[] = [
  { value: undefined, label: 'Mọi loại' }, { value: 'SCHEDULE', label: 'Lịch & kỳ thi' }, { value: 'RESULT', label: 'Kết quả & chấm' }, { value: 'OTHER', label: 'Khác' },
]

const dayKey = (ms: number) => { const d = new Date(ms); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}` }
/** Splits a newest-first list into local-day sections: "Hôm nay", "Hôm qua", then dated headings. Order is kept. */
export function groupByDay<T extends { createdAt: string }>(items: T[], nowMs: number): { key: string; label: string; items: T[] }[] {
  const today = dayKey(nowMs), yesterday = dayKey(nowMs - 86_400_000)
  const out: { key: string; label: string; items: T[] }[] = []
  for (const it of items) {
    const ms = Date.parse(it.createdAt), k = dayKey(ms)
    let sec = out[out.length - 1]
    if (!sec || sec.key !== k) {
      const label = k === today ? t('Hôm nay') : k === yesterday ? t('Hôm qua')
        : new Intl.DateTimeFormat(locale(), { weekday: 'long', day: 'numeric', month: 'numeric', ...(new Date(ms).getFullYear() !== new Date(nowMs).getFullYear() ? { year: 'numeric' } : {}) }).format(new Date(ms))
      sec = { key: k, label, items: [] }; out.push(sec)
    }
    sec.items.push(it)
  }
  return out
}
