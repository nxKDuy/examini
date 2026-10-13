import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, shortId } from '../../lib/format'
import type { FieldChange, OrderEntry, QuestionSyncPreview, SetSyncPreview, SyncApplyBody, SyncChoice } from './copySyncApi'

export const SYNC_FIELD_LABEL: Record<string, string> = {
  CATEGORY: 'Chủ đề', CONTENT: 'Nội dung câu hỏi', QUESTION_TYPE: 'Loại câu hỏi', DIFFICULTY: 'Độ khó',
  OPTIONS: 'Phương án', EXPECTED_ANSWER: 'Đáp án chuẩn', EXPLANATION: 'Giải thích', TITLE: 'Tên bộ', DESCRIPTION: 'Mô tả',
}
export const SYNC_CHOICE_LABEL: Record<SyncChoice, string> = { KEEP_LOCAL: 'Giữ bản của tôi', TAKE_SOURCE: 'Lấy từ nguồn' }
export const ITEM_KIND_LABEL: Record<string, string> = { ADDED: 'Thêm', UPDATED: 'Cập nhật', REMOVED: 'Xóa' }

export type SyncTab = 'info' | 'items' | 'order'
export interface SyncRow { key: string; tab: SyncTab; conflict: boolean }
export type Decisions = Record<string, SyncChoice>

const fieldRows = (changes: FieldChange[]): SyncRow[] =>
  changes.filter(c => c.sourceChanged).map(c => ({ key: c.field, tab: 'info', conflict: c.conflict }))

/** Rows the user can decide on: only source-side changes, conflicts first inside each tab. Never pre-selected. */
export function syncRows(p: QuestionSyncPreview | SetSyncPreview): SyncRow[] {
  if (p.availability !== 'AVAILABLE') return []
  if ('changes' in p) return sortConflicts(fieldRows(p.changes))
  const items: SyncRow[] = p.itemChanges.map(i => ({ key: `ITEM:${i.sourceQuestionId}`, tab: 'items', conflict: i.conflict }))
  const order: SyncRow[] = p.sourceReordered ? [{ key: 'ORDER', tab: 'order', conflict: p.reorderConflict }] : []
  return [...sortConflicts(fieldRows(p.metadataChanges)), ...sortConflicts(items), ...order]
}

function sortConflicts(rows: SyncRow[]) {
  return [...rows].sort((a, b) => Number(b.conflict) - Number(a.conflict))
}

/** Conflicts still waiting for an explicit choice, counted per tab for the tab badges. */
export function unresolvedByTab(rows: SyncRow[], decisions: Decisions): Record<SyncTab, number> {
  const out: Record<SyncTab, number> = { info: 0, items: 0, order: 0 }
  for (const r of rows) if (r.conflict && !decisions[r.key]) out[r.tab]++
  return out
}

/** Apply is possible only with at least one choice and every conflict resolved; the frontend never auto-merges. */
export function canApply(rows: SyncRow[], decisions: Decisions) {
  const unresolved = unresolvedByTab(rows, decisions)
  return Object.values(unresolved).every(n => n === 0) && rows.some(r => decisions[r.key])
}

/** Request body: the four expected values from the preview that was rendered, plus choices for rows that still exist. */
export function applyBody(p: QuestionSyncPreview | SetSyncPreview, rows: SyncRow[], decisions: Decisions): SyncApplyBody {
  return {
    expectedLocalVersion: p.localVersion, expectedReferenceSourceVersion: p.referenceSourceVersion,
    expectedCursorRevision: p.cursorRevision, expectedSourceVersion: p.latestSourceVersion ?? 0,
    decisions: keepChoices(rows, decisions),
  }
}

/** After a 409 reload: keep choices only for rows that still exist and are still changed. */
export function keepChoices(rows: SyncRow[], decisions: Decisions): Decisions {
  const keys = new Set(rows.map(r => r.key))
  return Object.fromEntries(Object.entries(decisions).filter(([k]) => keys.has(k)))
}

export const tookSource = (decisions: Decisions) => Object.values(decisions).includes('TAKE_SOURCE')

/** History `actions` is the provider's map text, e.g. "{CONTENT=KEEP_LOCAL, ITEM:<id>=TAKE_SOURCE}". */
export function parseActions(actions: string): { key: string; choice: string }[] {
  return actions.replace(/^\{|\}$/g, '').split(',').map(s => s.trim()).filter(Boolean).map(pair => {
    const at = pair.lastIndexOf('=')
    return at < 0 ? { key: pair, choice: '' } : { key: pair.slice(0, at), choice: pair.slice(at + 1) }
  })
}

export function decisionLabel(key: string) {
  if (key === 'ORDER') return 'Thứ tự câu'
  if (key.startsWith('ITEM:')) return `Câu ${key.slice(5, 13)}`
  return SYNC_FIELD_LABEL[key] ?? key
}

/** Comparison value as plain text; null means "không có". Redacted fields never get here. */
export function syncValue(field: string, value: unknown): string | null {
  if (value === null || value === undefined || value === '') return null
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  if (field === 'QUESTION_TYPE') return QUESTION_TYPE_LABEL[text] ?? text
  if (field === 'DIFFICULTY') return DIFFICULTY_LABEL[text] ?? text
  // The provider compares category ids; names are not part of the sync contract.
  if (field === 'CATEGORY') return `Chủ đề mã ${shortId(text)}`
  return text
}

export const pendingChanges = (p: QuestionSyncPreview | SetSyncPreview) => syncRows(p).length

export interface OrderRow { key: string; content: string | null; local: number | null; source: number | null; reference: number | null; onlyLocal: boolean; notCopied: boolean }
/**
 * Joins the three full orders on the source item (local-only items join on their local id) so the
 * Order tab can show every item: current position, latest source position and the last synced position.
 */
export function orderRows(local: OrderEntry[], source: OrderEntry[], reference: OrderEntry[]): OrderRow[] {
  const key = (e: OrderEntry) => e.sourceQuestionId ? `s:${e.sourceQuestionId}` : `l:${e.localQuestionId}`
  const rows = new Map<string, OrderRow>()
  const touch = (e: OrderEntry) => {
    const k = key(e)
    const r = rows.get(k) ?? { key: k, content: e.content, local: null, source: null, reference: null, onlyLocal: !e.sourceQuestionId, notCopied: false }
    if (!r.content && e.content) r.content = e.content
    rows.set(k, r); return r
  }
  local.forEach(e => { touch(e).local = e.position })
  source.forEach(e => { const r = touch(e); r.source = e.position; if (!e.localQuestionId && r.local === null) r.notCopied = true })
  reference.forEach(e => { touch(e).reference = e.position })
  const rank = (r: OrderRow) => r.local ?? (1000 + (r.source ?? r.reference ?? 0))
  return [...rows.values()].sort((a, b) => rank(a) - rank(b))
}
