import { t } from '../../lib/i18n'

// Exam Builder selection rules (scope lock B). Pure so they are testable without the UI.
export const MAX_EXAM_QUESTIONS = 200
export const BATCH_CHUNK = 200

/**
 * Adds ids to an ordered selection without duplicates (by questionId) and without
 * passing the exam limit. Returns what was added and what did not fit.
 */
export function addToSelection(selected: string[], incoming: string[], inExam: string[], limit = MAX_EXAM_QUESTIONS) {
  const taken = new Set([...inExam, ...selected])
  const room = Math.max(0, limit - inExam.length - selected.length)
  const fresh = incoming.filter((id, i) => !taken.has(id) && incoming.indexOf(id) === i)
  const added = fresh.slice(0, room)
  return { next: [...selected, ...added], added: added.length, duplicates: incoming.length - fresh.length, overflow: fresh.length - added.length }
}

export const chunk = <T,>(items: T[], size = BATCH_CHUNK) =>
  Array.from({ length: Math.ceil(items.length / size) }, (_, i) => items.slice(i * size, i * size + size))

export const SKIP_REASON: Record<string, string> = {
  QUESTION_NOT_FOUND: 'Câu hỏi không còn tồn tại',
  NOT_ACCESSIBLE: 'Bạn không còn quyền dùng câu này',
  NOT_ACTIVE: 'Câu chưa kích hoạt hoặc đã lưu trữ',
}

export const totalPoints = (items: { weight: number }[]) => items.reduce((s, i) => s + (Number.isFinite(i.weight) ? i.weight : 0), 0)

/** Sets the same points on every listed question (bulk edit in the selected panel). */
export const setAllPoints = <T extends { weight: number },>(items: T[], weight: number) => items.map(i => ({ ...i, weight }))

// ---- Blueprint (contracts/exam-builder.md) ----
export interface BlueprintRowCfg { key: string; bankId?: string; categoryId?: string; questionType?: string; difficulty?: string; count: number; pointsPerQuestion: number }
export interface BlueprintQuestion { questionId: string; questionVersion: number; questionType: string; content: string; points: number; rowIndex: number; pinned: boolean }
export interface BlueprintRowResult {
  rowIndex: number; bankId: string | null; categoryId: string | null; questionType: string | null; difficulty: string | null
  requested: number; picked: number; available: number; shortfall: number; pointsPerQuestion: number; droppedPinnedIds: string[]; questions: BlueprintQuestion[]
}
export interface BlueprintPreview {
  rows: BlueprintRowResult[]; questions: BlueprintQuestion[]; requestedQuestions: number; totalQuestions: number; totalPoints: number
  totalShortfall: number; complete: boolean; shuffle: boolean; seed: number
}
export interface BlueprintRequest {
  rows: { bankId?: string; categoryId?: string; questionType?: string; difficulty?: string; count: number; pointsPerQuestion: number; pinnedQuestionIds?: string[] }[]
  shuffle: boolean; seed?: number; excludeQuestionIds?: string[]
}

export const MAX_BLUEPRINT_ROWS = 50
export const MAX_EXCLUDE = 1000

export function rowError(r: BlueprintRowCfg) {
  if (!Number.isInteger(r.count) || r.count < 1 || r.count > MAX_EXAM_QUESTIONS) return t('Số câu phải là số nguyên từ 1 đến 200.')
  if (!(r.pointsPerQuestion > 0) || r.pointsPerQuestion > 1000) return t('Điểm mỗi câu phải lớn hơn 0 và không quá 1000.')
  return null
}

export function blueprintError(rows: BlueprintRowCfg[], alreadyInExam = 0) {
  if (!rows.length) return t('Thêm ít nhất một dòng khung đề.')
  if (rows.length > MAX_BLUEPRINT_ROWS) return t('Tối đa {n} dòng.', { n: MAX_BLUEPRINT_ROWS })
  const bad = rows.findIndex(r => rowError(r))
  if (bad >= 0) return t('Dòng {n}: {error}', { n: bad + 1, error: rowError(rows[bad]) ?? '' })
  const sum = rows.reduce((s, r) => s + r.count, 0)
  if (sum + alreadyInExam > MAX_EXAM_QUESTIONS) return alreadyInExam ? t('Tổng số câu ({sum} + {existing} câu đã có) vượt quá {max}.', { sum, existing: alreadyInExam, max: MAX_EXAM_QUESTIONS }) : t('Tổng số câu ({sum}) vượt quá {max}.', { sum, max: MAX_EXAM_QUESTIONS })
  return null
}

/**
 * Builds the preview request for each regenerate flow of the contract:
 * - pins: rowIndex -> ids kept in that row (other rows' current ids when regenerating one row or swapping);
 * - exclude: ids never to pick (already in the exam, swapped or removed).
 */
export function blueprintRequest(rows: BlueprintRowCfg[], shuffle: boolean, pins: Record<number, string[]> = {}, exclude: string[] = [], seed?: number): BlueprintRequest {
  const pinned = new Set(Object.values(pins).flat())
  return {
    rows: rows.map((r, i) => ({
      ...(r.bankId ? { bankId: r.bankId } : {}), ...(r.categoryId ? { categoryId: r.categoryId } : {}),
      ...(r.questionType ? { questionType: r.questionType } : {}), ...(r.difficulty ? { difficulty: r.difficulty } : {}),
      count: r.count, pointsPerQuestion: r.pointsPerQuestion,
      ...(pins[i]?.length ? { pinnedQuestionIds: pins[i].slice(0, r.count) } : {}),
    })),
    shuffle, ...(seed !== undefined ? { seed } : {}),
    ...(exclude.length ? { excludeQuestionIds: [...new Set(exclude)].filter(id => !pinned.has(id)).slice(0, MAX_EXCLUDE) } : {}),
  }
}

/** Current ids per row from a preview, optionally leaving one row unpinned or dropping one question. */
export function pinsFrom(preview: BlueprintPreview, opts: { skipRow?: number; dropId?: string } = {}): Record<number, string[]> {
  const out: Record<number, string[]> = {}
  for (const row of preview.rows) {
    if (row.rowIndex === opts.skipRow) continue
    out[row.rowIndex] = row.questions.map(q => q.questionId).filter(id => id !== opts.dropId)
  }
  return out
}

/** Remove one question without replacement: the row asks for one less and keeps the rest; an emptied row disappears. */
export function removeFromRow(rows: BlueprintRowCfg[], preview: BlueprintPreview, rowIndex: number, questionId: string) {
  const pins = pinsFrom(preview, { dropId: questionId })
  if (rows[rowIndex].count <= 1) {
    const nextRows = rows.filter((_, i) => i !== rowIndex)
    const nextPins: Record<number, string[]> = {}
    Object.entries(pins).forEach(([k, v]) => { const i = Number(k); if (i !== rowIndex) nextPins[i > rowIndex ? i - 1 : i] = v })
    return { rows: nextRows, pins: nextPins }
  }
  return { rows: rows.map((r, i) => i === rowIndex ? { ...r, count: r.count - 1 } : r), pins }
}
