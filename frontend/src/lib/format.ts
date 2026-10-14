import { t, i18nMap, locale } from './i18n'
// Display helpers. Exam schedules are UTC LocalDateTime values without an offset.

// Formatters follow the UI language; one per locale, created on first use.
const cache = new Map<string, Intl.DateTimeFormat>()
const intl = (key: string, opts: Intl.DateTimeFormatOptions) => {
  const k = `${locale()}|${key}`
  let f = cache.get(k)
  if (!f) { f = new Intl.DateTimeFormat(locale(), opts); cache.set(k, f) }
  return f
}
const dateTime = { format: (d: Date) => intl('dateTime', { dateStyle: 'medium', timeStyle: 'short' }).format(d) }
const timeOnly = { format: (d: Date) => intl('time', { hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(d) }

/** Parse a server UTC LocalDateTime ("2026-10-05T08:00:00") or ISO instant. */
export function parseUtc(value: string | null | undefined): Date | null {
  if (!value) return null
  const hasZone = /[zZ]|[+-]\d{2}:?\d{2}$/.test(value)
  const date = new Date(hasZone ? value : `${value}Z`)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatDateTime(value: string | null | undefined, fallback = t('Chưa đặt')) {
  const date = parseUtc(value)
  return date ? dateTime.format(date) : fallback
}

export const formatClock = (date: Date) => timeOnly.format(date)

/** UTC LocalDateTime from the server -> value for <input type="datetime-local"> in local time. */
export function toLocalInput(value: string | null | undefined) {
  const date = parseUtc(value)
  if (!date) return ''
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 16)
}

/** Local datetime-local input -> UTC LocalDateTime expected by the Exam API. */
export function fromLocalInput(value: string): string | undefined {
  if (!value) return undefined
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString().slice(0, 19)
}

export function formatDuration(totalSeconds: number) {
  const s = Math.max(0, Math.floor(totalSeconds))
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  const mm = String(m).padStart(2, '0'), ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}

export function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined) return '-'
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/\.?0+$/, '')
}

export const shortId = (id: string | null | undefined) => (id ? id.slice(0, 8) : '-')

export const QUESTION_TYPE_LABEL: Record<string, string> = i18nMap({
  MULTIPLE_CHOICE: 'Trắc nghiệm', TRUE_FALSE: 'Đúng/Sai', SHORT_ANSWER: 'Trả lời ngắn', ESSAY: 'Tự luận',
})
export const DIFFICULTY_LABEL: Record<string, string> = i18nMap({ EASY: 'Dễ', MEDIUM: 'Trung bình', HARD: 'Khó' })
export const ACCESS_MODE_LABEL: Record<string, string> = i18nMap({
  ASSIGNED: 'Được phân công', JOIN_CODE: 'Mã tham gia', PUBLIC: 'Công khai (người dùng EXAMINI)',
})
export const EXAM_TYPE_LABEL: Record<string, string> = i18nMap({ PRACTICE: 'Luyện tập', EXAM: 'Thi chính thức' })
export const MEMBER_ROLE_LABEL: Record<string, string> = i18nMap({ OWNER: 'Chủ sở hữu', MANAGER: 'Quản lý', PROCTOR: 'Giám thị' })
