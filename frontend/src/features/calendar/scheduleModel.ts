import { instantToWall, rawToOffsetIso, resolveWall, toIsoWithOffset, isValidZone, type WallOption } from '../../lib/zone'
import type { DeadlineRule, Schedule, ScheduleInput } from './calendarApi'

// ---- reminders ------------------------------------------------------------------------
export const DEFAULT_REMINDERS = [1440, 15]
export const MAX_REMINDERS = 10, MIN_OFFSET = 1, MAX_OFFSET = 43200
export const REMINDER_PRESETS = [10080, 2880, 1440, 180, 60, 15]

export function offsetLabel(min: number): string {
  if (min >= 2880 && min % 1440 === 0) return `${min / 1440} ngày`
  if (min >= 60 && min % 60 === 0) return `${min / 60} giờ`
  return `${min} phút`
}
export const customToMinutes = (value: number, unit: 'minute' | 'hour' | 'day') => Math.round(value * (unit === 'day' ? 1440 : unit === 'hour' ? 60 : 1))
export const normalizeReminders = (list: number[]) => [...new Set(list)].sort((a, b) => b - a)
export function reminderIssue(list: number[]): string | null {
  if (list.length > MAX_REMINDERS) return `Tối đa ${MAX_REMINDERS} mốc nhắc.`
  if (list.some(n => !Number.isInteger(n) || n < MIN_OFFSET || n > MAX_OFFSET)) return `Mỗi mốc nhắc từ ${MIN_OFFSET} phút đến 30 ngày (${MAX_OFFSET} phút).`
  if (new Set(list).size !== list.length) return 'Các mốc nhắc không được trùng nhau.'
  return null
}
/** When a reminder would be sent, and whether it is already behind us (those are never sent late). */
export const reminderSend = (startMs: number, offset: number, nowMs: number) => ({ at: startMs - offset * 60000, past: startMs - offset * 60000 <= nowMs })

// ---- form state -----------------------------------------------------------------------
export interface Field { wall: string; ms: number | null; raw: string | null }
export interface FormState {
  tz: string; start: Field; end: Field; rule: DeadlineRule; autoOpen: boolean; autoClose: boolean
  reminders: number[]; remindersOff: boolean; reason: string
}
const EMPTY: Field = { wall: '', ms: null, raw: null }

const fieldFrom = (iso: string | null, tz: string): Field => (iso ? { wall: instantToWall(Date.parse(iso), tz), ms: Date.parse(iso), raw: iso } : EMPTY)

export function initForm(s: Schedule): FormState {
  const fresh = s.scheduleRevision === 0 && !s.startAt && !s.endAt
  const reminders = fresh ? DEFAULT_REMINDERS : normalizeReminders(s.reminderOffsetsMinutes)
  return {
    tz: s.timezone, start: fieldFrom(s.startAt, s.timezone), end: fieldFrom(s.endAt, s.timezone), rule: s.deadlineRule,
    // A fresh schedule preselects auto open/close; they apply only once a valid start/end is set (toInput). Saved schedules keep their value.
    autoOpen: fresh ? true : s.autoOpen, autoClose: fresh ? true : s.autoClose, reminders, remindersOff: !fresh && reminders.length === 0, reason: '',
  }
}

export function setWall(wall: string, tz: string): Field {
  if (!wall) return EMPTY
  const r = resolveWall(wall, tz)
  return { wall, raw: null, ms: r.kind === 'valid' ? r.options[0].ms : null }
}
export const chooseOption = (f: Field, ms: number, tz: string): Field => ({ wall: instantToWall(ms, tz), ms, raw: f.raw && Date.parse(f.raw) === ms ? f.raw : null })

/** Changing the zone never changes the instant: wall text is re-derived from it. */
export function changeZone(state: FormState, tz: string): FormState {
  const move = (f: Field): Field => (f.ms !== null ? { ...f, wall: instantToWall(f.ms, tz) } : f)
  return { ...state, tz, start: move(state.start), end: move(state.end) }
}

export type FieldIssue = { kind: 'invalid' | 'gap' } | { kind: 'overlap'; options: WallOption[] } | null
export function fieldIssue(f: Field, tz: string): FieldIssue {
  if (!f.wall) return null
  const r = resolveWall(f.wall, tz)
  if (r.kind === 'invalid') return { kind: 'invalid' }
  if (r.kind === 'gap') return { kind: 'gap' }
  if (r.kind === 'overlap') return f.ms !== null && r.options.some(o => o.ms === f.ms) ? null : { kind: 'overlap', options: r.options }
  return null
}

export type Status = 'DRAFT' | 'PUBLISHED' | 'OPEN' | 'CLOSED' | string
const sameMs = (a: string | null, b: Field) => (a === null ? b.ms === null && !b.wall : b.ms !== null && Date.parse(a) === b.ms)

export function isChanged(state: FormState, s: Schedule): boolean {
  const original = normalizeReminders(s.reminderOffsetsMinutes), current = state.remindersOff ? [] : normalizeReminders(state.reminders)
  return state.tz !== s.timezone || state.rule !== s.deadlineRule || (state.autoOpen && state.start.ms !== null) !== s.autoOpen || (state.autoClose && state.end.ms !== null) !== s.autoClose
    || !sameMs(s.startAt, state.start) || !sameMs(s.endAt, state.end) || original.join() !== current.join()
}

/** Messages that block saving. The backend still has the last word (revision, lock after Start, state rules). */
export function validateForm(state: FormState, s: Schedule, status: Status): string[] {
  const out: string[] = []
  if (!isValidZone(state.tz)) out.push('Chọn múi giờ IANA hợp lệ.')
  for (const [label, f] of [['bắt đầu', state.start], ['kết thúc', state.end]] as const) {
    const issue = fieldIssue(f, state.tz)
    if (issue?.kind === 'invalid') out.push(`Giờ ${label} chưa hợp lệ.`)
    if (issue?.kind === 'gap') out.push(`Giờ ${label} không tồn tại ở ${state.tz} do chuyển giờ mùa hè. Hãy chọn giờ khác.`)
    if (issue?.kind === 'overlap') out.push(`Giờ ${label} xuất hiện hai lần ở ${state.tz} (chuyển giờ). Hãy chọn một trong hai độ lệch.`)
  }
  if (state.start.ms !== null && state.end.ms !== null && state.end.ms <= state.start.ms) out.push('Giờ kết thúc phải sau giờ bắt đầu.')
  if (state.rule !== 'FULL_DURATION' && state.end.ms === null) out.push('Cách tính hạn nộp này cần có giờ kết thúc.')
  const rem = state.remindersOff ? null : reminderIssue(state.reminders)
  if (rem) out.push(rem)
  if (status === 'OPEN') {
    const was = s.endAt ? Date.parse(s.endAt) : null
    if (was === null && state.end.ms !== null) out.push('Đề đang mở và chưa có giờ kết thúc nên không thêm giờ kết thúc mới được.')
    if (was !== null && state.end.ms !== null && state.end.ms < was) out.push('Khi đề đang mở chỉ được kéo dài giờ kết thúc, không rút ngắn.')
    if (was !== null && state.end.ms === null) out.push('Không gỡ được giờ kết thúc khi đề đang mở.')
  }
  if (status !== 'DRAFT' && isChanged(state, s) && !state.reason.trim()) out.push('Nhập lý do thay đổi lịch (bắt buộc sau khi đã xuất bản).')
  return out
}

export function toInput(state: FormState, s: Schedule): ScheduleInput {
  const iso = (f: Field) => (f.ms === null ? null : f.raw ? rawToOffsetIso(f.raw, state.tz) : toIsoWithOffset(f.ms, state.tz))
  const startAt = iso(state.start), endAt = iso(state.end)
  const reason = state.reason.trim()
  return {
    startAt, endAt, timezone: state.tz, deadlineRule: state.rule, autoOpen: state.autoOpen && startAt !== null, autoClose: state.autoClose && endAt !== null,
    reminderOffsetsMinutes: state.remindersOff ? [] : normalizeReminders(state.reminders), expectedRevision: s.scheduleRevision, ...(reason ? { reason } : {}),
  }
}
