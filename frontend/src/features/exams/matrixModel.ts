import type { BlueprintRowCfg } from './builderModel'
import { t } from '../../lib/i18n'

/**
 * Exam matrix ("ma trận đề"): topics (categories) × difficulty levels, each cell a number of questions.
 * Points are set per level. Every non-empty cell becomes one blueprint row, so the server picks each cell
 * only from its own topic and level and reports a shortfall instead of borrowing from another cell.
 */
export const LEVELS = ['EASY', 'MEDIUM', 'HARD'] as const
export type Level = typeof LEVELS[number]

/** Level names as Vietnamese teachers know them from the exam matrix (mức độ nhận thức). */
export const levelLabel = (l: Level) => ({ EASY: t('Dễ · Nhận biết'), MEDIUM: t('Trung bình · Thông hiểu'), HARD: t('Khó · Vận dụng') })[l]
export const levelShort = (l: Level) => ({ EASY: t('Dễ'), MEDIUM: t('Trung bình'), HARD: t('Khó') })[l]

export interface MatrixTopic { categoryId: string; name: string }
export interface Matrix {
  topics: MatrixTopic[]
  /** counts[categoryId][level] */
  counts: Record<string, Partial<Record<Level, number>>>
  points: Record<Level, number>
  questionType?: string
  bankId?: string
}
/** available[categoryId][level] from the question bank. */
export type Availability = Record<string, Partial<Record<Level, number>>>

export const emptyMatrix = (): Matrix => ({ topics: [], counts: {}, points: { EASY: 0.25, MEDIUM: 0.25, HARD: 0.25 } })

export const cell = (m: Matrix, categoryId: string, level: Level) => m.counts[categoryId]?.[level] ?? 0
export const available = (a: Availability | undefined, categoryId: string, level: Level) => a?.[categoryId]?.[level] ?? 0

export function setCell(m: Matrix, categoryId: string, level: Level, value: number): Matrix {
  const v = Number.isFinite(value) ? Math.max(0, Math.min(200, Math.floor(value))) : 0
  return { ...m, counts: { ...m.counts, [categoryId]: { ...m.counts[categoryId], [level]: v } } }
}

export interface MatrixTotals {
  perTopic: Record<string, number>; perLevel: Record<Level, number>; questions: number
  pointsPerLevel: Record<Level, number>; points: number
  /** Share of the total score per level, 0..100. */
  percentPerLevel: Record<Level, number>
}

export function totals(m: Matrix): MatrixTotals {
  const perTopic: Record<string, number> = {}
  const perLevel = { EASY: 0, MEDIUM: 0, HARD: 0 } as Record<Level, number>
  for (const t of m.topics) {
    perTopic[t.categoryId] = 0
    for (const l of LEVELS) { const n = cell(m, t.categoryId, l); perTopic[t.categoryId] += n; perLevel[l] += n }
  }
  const pointsPerLevel = { EASY: 0, MEDIUM: 0, HARD: 0 } as Record<Level, number>
  for (const l of LEVELS) pointsPerLevel[l] = round(perLevel[l] * (m.points[l] || 0))
  const points = round(LEVELS.reduce((s, l) => s + pointsPerLevel[l], 0))
  const questions = LEVELS.reduce((s, l) => s + perLevel[l], 0)
  const percentPerLevel = { EASY: 0, MEDIUM: 0, HARD: 0 } as Record<Level, number>
  for (const l of LEVELS) percentPerLevel[l] = points > 0 ? round((pointsPerLevel[l] * 100) / points, 1) : 0
  return { perTopic, perLevel, questions, pointsPerLevel, points, percentPerLevel }
}

const round = (n: number, digits = 2) => Math.round(n * 10 ** digits) / 10 ** digits

export type MatrixProblem =
  | { kind: 'NO_TOPICS' } | { kind: 'EMPTY' } | { kind: 'TOO_MANY'; questions: number }
  | { kind: 'TOO_MANY_CELLS'; cells: number } | { kind: 'BAD_POINTS'; level: Level }

/** Blocking problems only; a cell asking for more than is available is a warning (the preview reports the shortfall). */
export function matrixProblem(m: Matrix, alreadyInExam = 0): MatrixProblem | null {
  if (!m.topics.length) return { kind: 'NO_TOPICS' }
  const t = totals(m)
  if (t.questions === 0) return { kind: 'EMPTY' }
  if (t.questions + alreadyInExam > 200) return { kind: 'TOO_MANY', questions: t.questions + alreadyInExam }
  const cells = m.topics.reduce((n, topic) => n + LEVELS.filter(l => cell(m, topic.categoryId, l) > 0).length, 0)
  if (cells > 50) return { kind: 'TOO_MANY_CELLS', cells }
  for (const l of LEVELS) if (t.perLevel[l] > 0 && !(m.points[l] > 0 && m.points[l] <= 1000)) return { kind: 'BAD_POINTS', level: l }
  return null
}

/** Cells asking for more questions than the bank has, as [categoryId, level, missing]. */
export function shortages(m: Matrix, a: Availability | undefined): [string, Level, number][] {
  if (!a) return []
  const out: [string, Level, number][] = []
  for (const t of m.topics) for (const l of LEVELS) {
    const missing = cell(m, t.categoryId, l) - available(a, t.categoryId, l)
    if (missing > 0) out.push([t.categoryId, l, missing])
  }
  return out
}

/** One blueprint row per non-empty cell, in topic order then level order; the label names the cell for the preview. */
export function toBlueprintRows(m: Matrix, levelLabel: (l: Level) => string): (BlueprintRowCfg & { label: string })[] {
  const rows: (BlueprintRowCfg & { label: string })[] = []
  for (const t of m.topics) for (const l of LEVELS) {
    const n = cell(m, t.categoryId, l)
    if (n > 0) rows.push({
      key: `matrix-${t.categoryId}-${l}`, categoryId: t.categoryId, difficulty: l, count: n, pointsPerQuestion: m.points[l],
      ...(m.questionType ? { questionType: m.questionType } : {}), ...(m.bankId ? { bankId: m.bankId } : {}),
      label: `${t.name} · ${levelLabel(l)}`,
    })
  }
  return rows
}

/**
 * Fills the matrix from a total and a level ratio (e.g. 40/30/30): each level's count is spread over the topics
 * evenly, never above what a topic has, and leftovers go to topics that still have questions.
 * Returns the matrix and how many questions could not be placed.
 */
export function distribute(m: Matrix, total: number, ratio: Record<Level, number>, a: Availability | undefined): { matrix: Matrix; unplaced: number } {
  const sum = LEVELS.reduce((s, l) => s + Math.max(0, ratio[l] || 0), 0)
  if (!m.topics.length || total <= 0 || sum <= 0) return { matrix: m, unplaced: Math.max(0, total) }
  // Largest remainder so the level counts add up exactly to the total.
  const exact = LEVELS.map(l => (total * Math.max(0, ratio[l] || 0)) / sum)
  const perLevel = exact.map(Math.floor)
  let left = total - perLevel.reduce((s, n) => s + n, 0)
  exact.map((v, i) => [v - Math.floor(v), i] as const).sort((x, y) => y[0] - x[0]).forEach(([, i]) => { if (left > 0) { perLevel[i]++; left-- } })

  const counts: Matrix['counts'] = {}
  for (const t of m.topics) counts[t.categoryId] = { EASY: 0, MEDIUM: 0, HARD: 0 }
  let unplaced = 0
  LEVELS.forEach((l, li) => {
    let need = perLevel[li]
    const cap = (id: string) => (a ? available(a, id, l) : Infinity)
    // Round-robin one question at a time over topics that still have room: even and capacity-aware.
    while (need > 0) {
      let placed = false
      for (const t of m.topics) {
        if (need === 0) break
        const c = counts[t.categoryId]
        if ((c[l] ?? 0) < cap(t.categoryId)) { c[l] = (c[l] ?? 0) + 1; need--; placed = true }
      }
      if (!placed) { unplaced += need; break }
    }
  })
  return { matrix: { ...m, counts }, unplaced }
}

/** Common Vietnamese ratios (by number of questions) for quick filling. */
export const RATIO_PRESETS: { key: string; ratio: Record<Level, number> }[] = [
  { key: '40-30-30', ratio: { EASY: 40, MEDIUM: 30, HARD: 30 } },
  { key: '50-30-20', ratio: { EASY: 50, MEDIUM: 30, HARD: 20 } },
  { key: '30-40-30', ratio: { EASY: 30, MEDIUM: 40, HARD: 30 } },
]

/** Saved matrices live in this browser per teacher (a convenience, not shared data). */
export interface SavedMatrix { name: string; savedAt: string; matrix: Matrix; subjectId?: string }
const storeKey = (userId: string) => `exam-matrix-templates:${userId}`

export function loadTemplates(userId: string): SavedMatrix[] {
  try {
    const raw = localStorage.getItem(storeKey(userId))
    const list = raw ? JSON.parse(raw) : []
    return Array.isArray(list) ? list.filter(x => x && typeof x.name === 'string' && x.matrix && Array.isArray(x.matrix.topics)) : []
  } catch { return [] }
}

export function saveTemplate(userId: string, entry: SavedMatrix): SavedMatrix[] {
  const list = [entry, ...loadTemplates(userId).filter(x => x.name !== entry.name)].slice(0, 20)
  try { localStorage.setItem(storeKey(userId), JSON.stringify(list)) } catch { /* storage unavailable: keep in memory only */ }
  return list
}

export function deleteTemplate(userId: string, name: string): SavedMatrix[] {
  const list = loadTemplates(userId).filter(x => x.name !== name)
  try { localStorage.setItem(storeKey(userId), JSON.stringify(list)) } catch { /* ignore */ }
  return list
}
