import type { AddOutcome, AddRequest, ParticipantRow } from './participantApi'
import { t as tt, i18nMap } from '../../lib/i18n'

/** Pure Participants UI rules. They mirror what the server enforces so the UI does not offer actions in vain; the server stays the authority. */

export const OUTCOME_LABEL: Record<AddOutcome, { text: string; tone: 'success' | 'info' | 'warning' | 'danger' | 'muted' }> = i18nMap({
  ASSIGNED: { text: 'Đã thêm', tone: 'success' }, REINSTATED: { text: 'Đã khôi phục lại', tone: 'success' },
  ALREADY_PARTICIPANT: { text: 'Đã có trong danh sách', tone: 'info' }, CAPACITY_FULL: { text: 'Hết chỗ', tone: 'danger' },
  NOT_FOUND: { text: 'Không tìm thấy tài khoản', tone: 'warning' }, NOT_A_STUDENT: { text: 'Không phải học sinh', tone: 'warning' },
})
export const SOURCE_LABEL: Record<string, string> = i18nMap({ ASSIGNED: 'Được phân công', JOIN_CODE: 'Mã tham gia', PUBLIC: 'Công khai' })
export const STATUS_LABEL: Record<string, string> = i18nMap({ ACTIVE: 'Đủ điều kiện', REVOKED: 'Đã gỡ' })
export const ATTEMPT_STATUS_LABEL: Record<string, string> = i18nMap({ IN_PROGRESS: 'Đang làm', SUBMITTED: 'Đã nộp', AUTO_SUBMITTED: 'Tự động nộp', CANCELLED: 'Đã dừng' })

export const MAX_BATCH = 100
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export type BatchParse =
  | { ok: true; request: AddRequest; count: number; kind: 'ids' | 'emails'; duplicates: number }
  | { ok: false; error: string; count: number }

/** One value per line (commas/semicolons/spaces also separate). Either all user ids or all e-mails, 1..100, exactly as typed (e-mails are not normalised). */
export function parseBatch(raw: string): BatchParse {
  const tokens = raw.split(/[\s,;]+/).map(t => t.trim()).filter(Boolean)
  if (tokens.length === 0) return { ok: false, error: tt('Nhập ít nhất một mã người dùng hoặc email.'), count: 0 }
  const unique = [...new Set(tokens)]
  const duplicates = tokens.length - unique.length
  if (unique.length > MAX_BATCH) return { ok: false, error: tt('Tối đa {0} dòng mỗi lần (đang có {1}).', { 0: MAX_BATCH, 1: unique.length }), count: unique.length }
  const ids = unique.filter(t => UUID.test(t)), emails = unique.filter(t => !UUID.test(t) && EMAIL.test(t))
  const bad = unique.length - ids.length - emails.length
  if (bad > 0) return { ok: false, error: tt('{0} dòng không phải mã người dùng (UUID) hay email hợp lệ.', { 0: bad }), count: unique.length }
  if (ids.length > 0 && emails.length > 0) return { ok: false, error: tt('Mỗi lần chỉ dùng một loại: toàn mã người dùng hoặc toàn email.'), count: unique.length }
  return ids.length > 0
    ? { ok: true, request: { userIds: ids }, count: ids.length, kind: 'ids', duplicates }
    : { ok: true, request: { emails }, count: emails.length, kind: 'emails', duplicates }
}

/** Rows to keep in the box after a partial result: only the ones that did not succeed, so the Teacher can fix and resend. */
export function retryInput(results: { input: string; outcome: AddOutcome }[]): string {
  const keep = results.filter(r => r.outcome === 'NOT_FOUND' || r.outcome === 'NOT_A_STUDENT' || r.outcome === 'CAPACITY_FULL').map(r => r.input)
  return keep.join('\n')
}
export const outcomeSummary = (results: { outcome: AddOutcome }[]) => {
  const n = (o: AddOutcome) => results.filter(r => r.outcome === o).length
  return { added: n('ASSIGNED') + n('REINSTATED'), existing: n('ALREADY_PARTICIPANT'), failed: n('NOT_FOUND') + n('NOT_A_STUDENT') + n('CAPACITY_FULL') }
}

export type ExamStatusLike = 'DRAFT' | 'PUBLISHED' | 'OPEN' | 'CLOSED' | 'COMPLETED' | string
/** Adding is possible before the Exam closes; the server also checks capacity. */
export const canAddParticipants = (manage: boolean, status: ExamStatusLike) => manage && ['DRAFT', 'PUBLISHED', 'OPEN'].includes(status)

export interface RowAbilities { revoke: boolean; grant: boolean; invalidate: boolean }
/** Only OWNER/MANAGER write. Revoking applies to an ACTIVE participant; granting to an ACTIVE one; invalidating is per attempt. */
export function rowAbilities(manage: boolean, row: Pick<ParticipantRow, 'status'>): RowAbilities {
  return { revoke: manage && row.status === 'ACTIVE', grant: manage && row.status === 'ACTIVE', invalidate: manage }
}

export function participantName(p: { displayName?: string; userId: string }): { text: string; fallback: boolean } {
  const n = p.displayName?.trim()
  return n ? { text: n, fallback: false } : { text: tt('Thí sinh {0}', { 0: p.userId.slice(0, 8) }), fallback: true }
}

export function attemptSummary(row: Pick<ParticipantRow, 'attempts' | 'effectiveAttemptLimit'>): string {
  const a = row.attempts
  if (!a) return tt('Chưa làm / {0}', { 0: row.effectiveAttemptLimit })
  const base = `${a.used}/${row.effectiveAttemptLimit}`
  return a.invalidated > 0 ? tt('{0} ({1} bị vô hiệu)', { 0: base, 1: a.invalidated }) : base
}

/** Per-attempt invalidation needs a public reason and the attempt number (or name) typed back. */
export const invalidateReady = (reason: string, typed: string, attemptNumber: number, name: string) =>
  reason.trim().length > 0 && [String(attemptNumber), name.trim()].filter(Boolean).includes(typed.trim())

export const grantQuantityError = (raw: string): string | null => {
  const n = Number(raw)
  return Number.isInteger(n) && n >= 1 && n <= 100 ? null : tt('Số lượt cấp thêm phải là số nguyên từ 1 đến 100.')
}
/** One id per dialog opening: pressing Cấp thêm twice (or a retry after a lost response) replays the same request instead of granting twice. */
export const newRequestId = () => (globalThis.crypto?.randomUUID?.() ?? `req-${Date.now()}-${Math.random().toString(36).slice(2)}`)

/** Hide staff-only wording: the Student's own attempts carry only the public reason. */
export const publicReason = (a: { invalidationReason?: string }) => a.invalidationReason?.trim() || null

export function resumable(a: { status: string; invalidated: boolean }) { return a.status === 'IN_PROGRESS' && !a.invalidated }
export function resultLink(a: { sessionId: string; status: string }) { return a.status === 'SUBMITTED' || a.status === 'AUTO_SUBMITTED' ? `/exam/result/${a.sessionId}` : null }

export function isInvalidatedConflict(error: unknown): boolean {
  const e = error as { response?: { status?: number; data?: { detail?: string } } }
  return e?.response?.status === 409 && /RESULT_INVALIDATED/.test(e.response.data?.detail ?? '')
}
