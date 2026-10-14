import { addDays, dayStart, formatDateIn, formatDateTimeIn, formatTimeIn, instantToDay, offsetMinutes, wallParts, weekdayIndex, zoneLabel } from '../../lib/zone'
import type { CalendarItem, DeadlineRule } from './calendarApi'
import { t as tt, i18nMap } from '../../lib/i18n'

export type CalendarView = 'agenda' | 'month' | 'week' | 'day'
export const VIEW_LABEL: Record<CalendarView, string> = i18nMap({ agenda: 'Agenda', month: 'Tháng', week: 'Tuần', day: 'Ngày' })
const HOUR = 3600e3, MINUTE = 60e3

export const toMs = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null)

/** Where an Exam sits on a time grid. Display only; availability always comes from the backend. */
export interface Span { start: number; end: number; kind: 'window' | 'open-start' | 'deadline' }
export function itemSpan(item: Pick<CalendarItem, 'startAt' | 'endAt'>): Span | null {
  const start = toMs(item.startAt), end = toMs(item.endAt)
  if (start !== null && end !== null && end > start) return { start, end, kind: 'window' }
  if (start !== null) return { start, end: start + HOUR, kind: 'open-start' }
  if (end !== null) return { start: end - 30 * MINUTE, end, kind: 'deadline' }
  return null
}

// ---- ranges ---------------------------------------------------------------------------

export function visibleDays(view: CalendarView, anchor: string): string[] {
  if (view === 'day') return [anchor]
  if (view === 'week') { const first = addDays(anchor, -weekdayIndex(anchor)); return Array.from({ length: 7 }, (_, i) => addDays(first, i)) }
  if (view === 'month') {
    const monthStart = `${anchor.slice(0, 7)}-01`
    const first = addDays(monthStart, -weekdayIndex(monthStart))
    const next = addDays(monthStart, 32).slice(0, 7) + '-01'
    const weeks = Math.ceil(((Date.parse(next) - Date.parse(first)) / 864e5) / 7)
    return Array.from({ length: weeks * 7 }, (_, i) => addDays(first, i))
  }
  return [anchor]
}

export function rangeOf(days: string[], tz: string) {
  return { from: new Date(dayStart(days[0], tz)).toISOString(), to: new Date(dayStart(addDays(days[days.length - 1], 1), tz)).toISOString() }
}

export const AGENDA_BACK_DAYS = 30
export const AGENDA_AHEAD_DAYS = 330 // together < 366 days, the backend maximum

export function agendaRange(today: string, tz: string) {
  return rangeOf([addDays(today, -AGENDA_BACK_DAYS), addDays(today, AGENDA_AHEAD_DAYS)], tz)
}

export function shiftAnchor(view: CalendarView, anchor: string, dir: -1 | 1): string {
  if (view === 'month') {
    const [y, m] = anchor.split('-').map(Number)
    const t = new Date(Date.UTC(y, m - 1 + dir, 1))
    return `${t.getUTCFullYear()}-${String(t.getUTCMonth() + 1).padStart(2, '0')}-01`
  }
  return addDays(anchor, dir * (view === 'week' ? 7 : 1))
}

// ---- grouping -------------------------------------------------------------------------

export function overlapsDay(span: Span | null, day: string, tz: string) {
  if (!span) return false
  const from = dayStart(day, tz), to = dayStart(addDays(day, 1), tz)
  return span.start < to && span.end > from
}

export function itemsOnDay(items: CalendarItem[], day: string, tz: string) {
  return items.filter(i => overlapsDay(itemSpan(i), day, tz)).sort(byStart)
}

export const byStart = (a: CalendarItem, b: CalendarItem) =>
  (itemSpan(a)?.start ?? Infinity) - (itemSpan(b)?.start ?? Infinity) || a.examTitle.localeCompare(b.examTitle, 'vi')

/** States after which an Exam without endAt no longer "runs". Used to classify, never to grant anything. */
const TERMINAL = new Set(['CLOSED', 'EXAM_CLOSED', 'COMPLETED', 'SUBMITTED', 'AUTO_SUBMITTED', 'CANCELLED', 'MISSED', 'AFTER_END_AT'])
/** Start-only and not finished: it has no real end, so the 1-hour display block must not make it look ended. */
export const isOpenEnded = (item: Pick<CalendarItem, 'startAt' | 'endAt' | 'calendarState'>) => !!item.startAt && !item.endAt && !TERMINAL.has(item.calendarState)

export type AgendaKey = 'today' | 'upcoming' | 'later' | 'unscheduled' | 'past'
export const AGENDA_LABEL: Record<AgendaKey, string> = i18nMap({ today: 'Hôm nay', upcoming: 'Sắp tới (7 ngày)', later: 'Sau đó', unscheduled: 'Chưa có lịch', past: 'Đã qua' })
export const AGENDA_ORDER: AgendaKey[] = ['today', 'upcoming', 'later', 'unscheduled', 'past']

export function agendaGroups(items: CalendarItem[], nowMs: number, tz: string): { key: AgendaKey; label: string; items: CalendarItem[] }[] {
  const today = instantToDay(nowMs, tz)
  const todayStart = dayStart(today, tz), tomorrow = dayStart(addDays(today, 1), tz), weekEnd = dayStart(addDays(today, 8), tz)
  const buckets: Record<AgendaKey, CalendarItem[]> = { today: [], upcoming: [], later: [], unscheduled: [], past: [] }
  for (const item of items) {
    const span = itemSpan(item)
    if (!span) buckets.unscheduled.push(item)
    else if ((isOpenEnded(item) ? Infinity : span.end) <= todayStart) buckets.past.push(item)
    else if (span.start < tomorrow) buckets.today.push(item)
    else if (span.start < weekEnd) buckets.upcoming.push(item)
    else buckets.later.push(item)
  }
  buckets.past.sort((a, b) => byStart(b, a))
  buckets.unscheduled.sort((a, b) => a.examTitle.localeCompare(b.examTitle, 'vi'))
  for (const k of ['today', 'upcoming', 'later'] as const) buckets[k].sort(byStart)
  return AGENDA_ORDER.map(key => ({ key, label: AGENDA_LABEL[key], items: buckets[key] }))
}

// ---- time grid layout -----------------------------------------------------------------

export interface Block { item: CalendarItem; top: number; height: number; col: number; cols: number; cluster: number; clippedStart: boolean; clippedEnd: boolean }

/**
 * Day segments as percentages of the 24-row wall-clock axis (00:00..24:00 in the viewer zone), so a 10:00 Exam is
 * on the 10:00 row on 23h and 25h days too. Overlapping blocks share columns. Display only.
 */
export function layoutDay(items: CalendarItem[], day: string, tz: string, minPercent = 3): Block[] {
  const from = dayStart(day, tz), to = dayStart(addDays(day, 1), tz)
  const wall = (ms: number) => { const w = wallParts(ms, tz); return w.h * 60 + w.mi + w.s / 60 }
  const segs = items.flatMap(item => {
    const span = itemSpan(item)
    if (!span || span.start >= to || span.end <= from) return []
    const s = span.start <= from ? 0 : wall(span.start)
    let e = span.end >= to ? 1440 : wall(span.end)
    if (e <= s) e = Math.min(1440, s + 1) // DST overlap/gap can fold the end onto the start on the wall clock
    return [{ item, s, e, top: (s / 1440) * 100, height: Math.max(minPercent, ((e - s) / 1440) * 100), clippedStart: span.start < from, clippedEnd: span.end > to }]
  }).sort((a, b) => a.s - b.s || b.e - a.e)

  const blocks: Block[] = []
  let cluster: typeof segs = [], clusterEnd = -Infinity, clusterId = 0
  const flush = () => {
    const colEnds: number[] = []
    const placed = cluster.map(seg => {
      let col = colEnds.findIndex(end => end <= seg.s)
      if (col < 0) { col = colEnds.length; colEnds.push(seg.e) } else colEnds[col] = seg.e
      return { seg, col }
    })
    for (const { seg, col } of placed) blocks.push({ item: seg.item, top: seg.top, height: seg.height, col, cols: colEnds.length, cluster: clusterId, clippedStart: seg.clippedStart, clippedEnd: seg.clippedEnd })
    cluster = []; clusterEnd = -Infinity; clusterId++
  }
  for (const seg of segs) {
    if (cluster.length && seg.s >= clusterEnd) flush()
    cluster.push(seg); clusterEnd = Math.max(clusterEnd, seg.e)
  }
  if (cluster.length) flush()
  return blocks
}

/** Position of the "now" line on the wall-clock axis, or null when `day` is not today in `tz`. */
export function nowTop(nowMs: number, day: string, tz: string): number | null {
  if (instantToDay(nowMs, tz) !== day) return null
  const w = wallParts(nowMs, tz)
  return ((w.h * 60 + w.mi + w.s / 60) / 1440) * 100
}

/** Instants (server-corrected clock) at which the backend's answer may change: a window opens/closes or an attempt expires. */
export function nextBoundary(items: Pick<CalendarItem, 'startAt' | 'endAt' | 'effectiveExpiresAt'>[], nowMs: number): number | null {
  let next = Infinity
  for (const i of items) for (const iso of [i.startAt, i.endAt, i.effectiveExpiresAt]) {
    const t = toMs(iso)
    if (t !== null && t > nowMs && t < next) next = t
  }
  return Number.isFinite(next) ? next : null
}

/** A cluster too crowded for `maxCols` columns: the first maxCols-1 columns stay, the rest collapse into one "+N" block. */
export interface Overflow { cluster: number; top: number; height: number; col: number; cols: number; hidden: CalendarItem[] }
export function collapseCrowded(blocks: Block[], maxCols: number): { shown: Block[]; overflow: Overflow[] } {
  const shown: Block[] = [], hiddenBy = new Map<number, Block[]>()
  for (const b of blocks) {
    if (b.cols <= maxCols || b.col < maxCols - 1) shown.push(b.cols <= maxCols ? b : { ...b, cols: maxCols })
    else hiddenBy.set(b.cluster, [...(hiddenBy.get(b.cluster) ?? []), b])
  }
  const overflow = [...hiddenBy.entries()].map(([cluster, list]) => {
    const top = Math.min(...list.map(b => b.top)), bottom = Math.max(...list.map(b => b.top + b.height))
    return { cluster, top, height: bottom - top, col: maxCols - 1, cols: maxCols, hidden: list.map(b => b.item) }
  })
  return { shown, overflow }
}

// ---- state, rules, text ---------------------------------------------------------------

export type StateTone = 'success' | 'neutral' | 'warning' | 'muted' | 'danger'
export interface StateInfo { label: string; tone: StateTone; icon: 'play' | 'clock' | 'lock' | 'edit' | 'check' | 'alert' | 'send' | 'key' | 'ban' }

const STATES: Record<string, StateInfo> = i18nMap({
  // Teacher lifecycle
  DRAFT: { label: 'Bản nháp', tone: 'warning', icon: 'edit' },
  PUBLISHED: { label: 'Đã xuất bản', tone: 'neutral', icon: 'lock' },
  OPEN: { label: 'Đang mở', tone: 'success', icon: 'play' },
  CLOSED: { label: 'Đã đóng', tone: 'muted', icon: 'lock' },
  COMPLETED: { label: 'Đã kết thúc', tone: 'muted', icon: 'check' },
  // Student calendarState
  AVAILABLE: { label: 'Làm được ngay', tone: 'success', icon: 'play' },
  IN_PROGRESS: { label: 'Đang làm', tone: 'success', icon: 'clock' },
  SUBMITTED: { label: 'Đã nộp', tone: 'muted', icon: 'send' },
  AUTO_SUBMITTED: { label: 'Tự động nộp', tone: 'muted', icon: 'send' },
  CANCELLED: { label: 'Đã hủy', tone: 'muted', icon: 'ban' },
  MISSED: { label: 'Đã lỡ', tone: 'danger', icon: 'alert' },
  UPCOMING: { label: 'Sắp diễn ra', tone: 'neutral', icon: 'clock' },
  NOT_OPEN: { label: 'Chưa mở', tone: 'neutral', icon: 'lock' },
  JOIN_REQUIRED: { label: 'Cần tham gia', tone: 'neutral', icon: 'key' },
  // reasonCode used as calendarState
  EXAM_NOT_PUBLISHED: { label: 'Chưa xuất bản', tone: 'warning', icon: 'edit' },
  EXAM_CLOSED: { label: 'Đã đóng', tone: 'muted', icon: 'lock' },
  BEFORE_START_AT: { label: 'Chưa đến giờ', tone: 'neutral', icon: 'clock' },
  AFTER_END_AT: { label: 'Hết giờ', tone: 'muted', icon: 'lock' },
  EXAM_NOT_OPEN: { label: 'Chưa mở', tone: 'neutral', icon: 'lock' },
  ATTEMPT_LIMIT: { label: 'Hết lượt', tone: 'muted', icon: 'ban' },
  INSUFFICIENT_TIME: { label: 'Không đủ thời gian', tone: 'warning', icon: 'alert' },
  CAPACITY_FULL: { label: 'Đã đủ chỗ', tone: 'warning', icon: 'alert' },
  SNAPSHOT_UNAVAILABLE: { label: 'Tạm thời không khả dụng', tone: 'danger', icon: 'alert' },
  PARTICIPATION_REVOKED: { label: 'Đã bị gỡ', tone: 'muted', icon: 'ban' },
  EXAM_INTEGRITY_LOCKED: { label: 'Đang khóa', tone: 'warning', icon: 'lock' },
})
export const stateInfo = (state: string | null | undefined): StateInfo =>
  STATES[state ?? ''] ?? { label: state ?? tt('Không rõ'), tone: 'neutral', icon: 'clock' }

export const REASON_TEXT: Record<string, string> = i18nMap({
  EXAM_NOT_PUBLISHED: 'Giáo viên chưa xuất bản kỳ thi.',
  EXAM_CLOSED: 'Kỳ thi đã đóng, không nhận lượt làm bài mới.',
  BEFORE_START_AT: 'Chưa đến giờ bắt đầu. Bạn có thể xem trước nhưng chưa tham gia hoặc bắt đầu được.',
  AFTER_END_AT: 'Đã qua giờ kết thúc, không nhận lượt làm bài mới.',
  EXAM_NOT_OPEN: 'Giáo viên chưa mở kỳ thi.',
  JOIN_REQUIRED: 'Bạn cần tham gia kỳ thi trước khi bắt đầu.',
  ATTEMPT_LIMIT: 'Bạn đã dùng hết số lượt làm bài.',
  INSUFFICIENT_TIME: 'Thời gian còn lại không đủ cho một lượt làm bài đầy đủ theo quy định của kỳ thi này.',
  CAPACITY_FULL: 'Kỳ thi đã đủ số người làm bài cùng lúc. Hãy thử lại sau ít phút.',
  SNAPSHOT_UNAVAILABLE: 'Nội dung đề tạm thời không khả dụng. Hãy thử lại sau.',
  PARTICIPATION_REVOKED: 'Bạn không còn trong danh sách của kỳ thi này nên không thể tham gia hoặc bắt đầu lượt mới.',
  EXAM_INTEGRITY_LOCKED: 'Giáo viên đang khóa kỳ thi nên chưa nhận lượt bắt đầu mới.',
})
export const reasonText = (code: string | null | undefined) => (code ? REASON_TEXT[code] ?? tt('Chưa thể bắt đầu ({0}).', { 0: code }) : null)

export const RULE_LABEL: Record<DeadlineRule, string> = i18nMap({ FULL_DURATION: 'Đủ thời lượng', CAPPED_AT_END: 'Nộp đúng giờ kết thúc', REQUIRE_FULL_WINDOW: 'Phải còn đủ thời lượng' })
export const RULE_HELP: Record<DeadlineRule, string> = i18nMap({
  FULL_DURATION: 'Bắt đầu muộn vẫn được trong cửa sổ làm bài. Hạn nộp = lúc bắt đầu + thời lượng, kể cả khi vượt giờ kết thúc.',
  CAPPED_AT_END: 'Bắt đầu muộn vẫn được trong cửa sổ làm bài. Hạn nộp là mốc sớm hơn giữa (lúc bắt đầu + thời lượng) và giờ kết thúc.',
  REQUIRE_FULL_WINDOW: 'Không cho bắt đầu nếu thời gian còn lại ít hơn thời lượng. Hạn nộp = lúc bắt đầu + thời lượng.',
})
export const ruleLabel = (rule: string) => RULE_LABEL[rule as DeadlineRule] ?? rule

export const RELATION_LABEL: Record<string, string> = i18nMap({
  OWNER: 'Chủ sở hữu', MANAGER: 'Quản lý', PROCTOR: 'Giám thị', ASSIGNED: 'Được giao', JOINED: 'Đã tham gia', FOLLOWER: 'Đang theo dõi', REVOKED: 'Đã bị gỡ',
})
export const relationLabel = (r: string) => RELATION_LABEL[r] ?? r

export function describeCountdown(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
  if (d >= 1) return `${d} ngày${h ? ` ${h} giờ` : ''}`
  if (h >= 1) return `${h} giờ${m ? ` ${m} phút` : ''}`
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

/** One countdown line per Student item. Display only; the server decides what is allowed. */
export function countdownLine(item: Pick<CalendarItem, 'startAt' | 'endAt' | 'effectiveExpiresAt' | 'calendarState' | 'canStart' | 'canResume'>, nowMs: number): string | null {
  const start = toMs(item.startAt), end = toMs(item.endAt), expires = toMs(item.effectiveExpiresAt)
  if (item.calendarState === 'IN_PROGRESS' && expires !== null) return expires > nowMs ? tt('Còn {0} để nộp', { 0: describeCountdown(expires - nowMs) }) : tt('Đang hết giờ')
  if (['SUBMITTED', 'AUTO_SUBMITTED', 'CANCELLED', 'MISSED', 'CLOSED', 'EXAM_CLOSED', 'AFTER_END_AT'].includes(item.calendarState)) return null
  if (start !== null && start > nowMs) return tt('Mở sau {0}', { 0: describeCountdown(start - nowMs) })
  if (end !== null && end > nowMs && (item.canStart || item.canResume || item.calendarState === 'AVAILABLE')) return tt('Đóng sau {0}', { 0: describeCountdown(end - nowMs) })
  return null
}

/** Short window label for a compact block, in the Exam's zone. Each end carries its offset when a DST change separates them. */
export function blockRangeLabel(item: Pick<CalendarItem, 'startAt' | 'endAt' | 'timezone'>): string {
  const span = itemSpan(item), tz = item.timezone
  if (!span) return ''
  if (span.kind === 'open-start') return tt('từ {0} {1}', { 0: formatTimeIn(span.start, tz), 1: zoneLabel(span.start, tz) })
  if (span.kind === 'deadline') return tt('đóng {0} {1}', { 0: formatTimeIn(span.end, tz), 1: zoneLabel(span.end, tz) })
  if (offsetMinutes(span.start, tz) !== offsetMinutes(span.end, tz)) return `${formatTimeIn(span.start, tz)} ${zoneLabel(span.start, tz)}–${formatTimeIn(span.end, tz)} ${zoneLabel(span.end, tz)}`
  return `${formatTimeIn(span.start, tz)}–${formatTimeIn(span.end, tz)} ${zoneLabel(span.start, tz)}`
}

/**
 * Window text in the Exam's own zone, plus a "your time" line when the viewer's clock differs. When the two ends sit
 * on different UTC offsets (a DST change inside the window) each end carries its own offset, so 01:30 and 01:30 are told apart.
 */
export function windowText(item: Pick<CalendarItem, 'startAt' | 'endAt' | 'timezone'>, viewerTz: string): { primary: string; zone: string; mine?: string; mineText?: string; mineZone?: string } {
  const start = toMs(item.startAt), end = toMs(item.endAt), tz = item.timezone
  const ref = start ?? end
  if (ref === null) return { primary: tt('Chưa đặt lịch'), zone: '' }
  const split = (zoneId: string) => start !== null && end !== null && offsetMinutes(start, zoneId) !== offsetMinutes(end, zoneId)
  const tag = (ms: number, zoneId: string, on: boolean) => (on ? ` ${zoneLabel(ms, zoneId)}` : '')
  const render = (zoneId: string) => {
    if (start !== null && end !== null) {
      const od = split(zoneId)
      const sameDay = instantToDay(start, zoneId) === instantToDay(end, zoneId)
      return sameDay
        ? `${formatDateIn(start, zoneId)}, ${formatTimeIn(start, zoneId)}${tag(start, zoneId, od)}–${formatTimeIn(end, zoneId)}${tag(end, zoneId, od)}`
        : `${formatDateTimeIn(start, zoneId)}${tag(start, zoneId, od)} → ${formatDateTimeIn(end, zoneId)}${tag(end, zoneId, od)}`
    }
    if (start !== null) return tt('Từ {0}', { 0: formatDateTimeIn(start, zoneId) })
    return tt('Đến {0}', { 0: formatDateTimeIn(end as number, zoneId) })
  }
  const label = zoneLabel(ref, tz)
  const zone = split(tz) ? tt('{0} → {1} · {2} (đổi giờ trong khoảng này)', { 0: zoneLabel(start as number, tz), 1: zoneLabel(end as number, tz), 2: tz }) : label === tz ? tz : `${label} · ${tz}`
  const different = viewerTz !== tz && (offsetMinutes(ref, viewerTz) !== offsetMinutes(ref, tz) || (end !== null && offsetMinutes(end, viewerTz) !== offsetMinutes(end, tz)))
  if (!different) return { primary: render(tz), zone }
  const mineZone = split(viewerTz) ? `${zoneLabel(start as number, viewerTz)} → ${zoneLabel(end as number, viewerTz)}` : zoneLabel(ref, viewerTz), mineText = render(viewerTz)
  return { primary: render(tz), zone, mine: tt('Giờ của bạn ({0}): {1}', { 0: mineZone, 1: mineText }), mineText, mineZone }
}

export const isTeacherItem = (item: Pick<CalendarItem, 'relation'>) => ['OWNER', 'MANAGER', 'PROCTOR'].includes(item.relation)

/** The one-line key figures shown on a compact card. Only fields the API provides. */
export function cardMetrics(item: CalendarItem, nowMs: number): string[] {
  if (isTeacherItem(item)) {
    return [
      item.participantCount !== null && item.maxParticipants !== null ? tt('{0}/{1} người', { 0: item.participantCount, 1: item.maxParticipants }) : '',
      ruleLabel(item.deadlineRule), tt('{0} phút', { 0: item.durationMinutes }), relationLabel(item.relation),
    ].filter(Boolean)
  }
  return [
    countdownLine(item, nowMs) ?? '', tt('{0} phút', { 0: item.durationMinutes }), ruleLabel(item.deadlineRule),
    item.attemptsRemaining !== null ? tt('Còn {0} lượt', { 0: item.attemptsRemaining }) : '', relationLabel(item.relation),
  ].filter(Boolean)
}

export const isManager = (item: Pick<CalendarItem, 'relation'>) => item.relation === 'OWNER' || item.relation === 'MANAGER'

export const dayNumber = (day: string) => String(Number(day.slice(8, 10)))
export const dayHeading = (day: string) => new Intl.DateTimeFormat('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${day}T12:00:00Z`))
