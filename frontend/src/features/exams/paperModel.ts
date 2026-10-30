import type { PaperQuestion } from './examApi'

/**
 * Paper exam with several exam codes (mã đề): each code reorders the questions and the multiple-choice options with a
 * generator seeded by exam id + code, so printing the same code again always gives the same paper and answer key.
 */
export interface PaperOptions { codes: number; firstCode: number; shuffleQuestions: boolean; shuffleOptions: boolean }
export interface PaperItem {
  number: number; question: PaperQuestion
  /** Options in printed order with their letter. */
  options: { letter: string; content: string; correct: boolean; displayOrder?: number }[]
  /** What the key shows for this item: letters, Đúng/Sai, the expected answer or "TL" for essays. */
  key: string
}
export interface PaperVersion { code: string; items: PaperItem[] }

export const LETTERS = 'ABCDEFGHIJ'

/** FNV-1a: a stable 32-bit seed from text. */
export function hashSeed(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 0x01000193) }
  return h >>> 0
}

/** mulberry32: small, fast, deterministic. */
export function rng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function shuffled<T>(list: readonly T[], random: () => number): T[] {
  const a = [...list]
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]] }
  return a
}

/** Keys of one item; true/false keeps its option order and is keyed by the word, not a letter. */
function keyOf(q: PaperQuestion, options: PaperItem['options']): string {
  if (q.questionType === 'TRUE_FALSE') return options.find(o => o.correct)?.content ?? ''
  if (q.questionType === 'MULTIPLE_CHOICE' || q.questionType === 'MULTIPLE_RESPONSE') return options.filter(o => o.correct).map(o => o.letter).join(',')
  // THPT style key of a true/false group: one Đ/S per statement in order, e.g. "ĐSĐĐ".
  if (q.questionType === 'TRUE_FALSE_GROUP') return options.map(o => (o.correct ? 'Đ' : 'S')).join('')
  if (q.questionType === 'SHORT_ANSWER') return q.expectedAnswer?.trim() ?? ''
  return 'TL'
}

export function makeVersion(examId: string, questions: PaperQuestion[], code: string, opts: Pick<PaperOptions, 'shuffleQuestions' | 'shuffleOptions'>): PaperVersion {
  const random = rng(hashSeed(`${examId}:${code}`))
  const base = [...questions].sort((a, b) => a.position - b.position)
  // Questions sharing a reading/listening passage move as one block, like the online attempt.
  const groups: PaperQuestion[][] = []
  const byKey = new Map<string, PaperQuestion[]>()
  for (const q of base) {
    const key = q.presentation?.passage?.key
    if (key && byKey.has(key)) { byKey.get(key)!.push(q); continue }
    const g = [q]; groups.push(g); if (key) byKey.set(key, g)
  }
  const order = (opts.shuffleQuestions ? shuffled(groups, random) : groups).flat()
  const items = order.map((q, i) => {
    const sorted = [...(q.options ?? [])].sort((a, b) => a.displayOrder - b.displayOrder)
    const mixed = opts.shuffleOptions && (q.questionType === 'MULTIPLE_CHOICE' || q.questionType === 'MULTIPLE_RESPONSE') ? shuffled(sorted, random) : sorted
    const group = q.questionType === 'TRUE_FALSE_GROUP'
    const options = mixed.map((o, k) => ({ letter: group ? (LETTERS[k] ?? String(k + 1)).toLowerCase() : LETTERS[k] ?? String(k + 1), content: o.content, correct: o.correct, displayOrder: o.displayOrder }))
    return { number: i + 1, question: q, options, key: keyOf(q, options) }
  })
  return { code, items }
}

export function makeVersions(examId: string, questions: PaperQuestion[], o: PaperOptions): PaperVersion[] {
  const n = Math.max(1, Math.min(24, Math.floor(o.codes)))
  return Array.from({ length: n }, (_, i) => makeVersion(examId, questions, String(o.firstCode + i), o))
}

/** Options are laid out in one row when every option is short, otherwise one per line (like printed Vietnamese exams). */
/** First and last item number of each passage block, keyed by the number of its first item. */
export function passageRanges(items: PaperItem[]): Map<number, { from: number; to: number }> {
  const out = new Map<number, { from: number; to: number }>()
  let cur: { key: string; from: number; to: number } | null = null
  for (const it of items) {
    const key = it.question.presentation?.passage?.key
    if (key && cur && cur.key === key) { cur.to = it.number; continue }
    cur = key ? { key, from: it.number, to: it.number } : null
    if (cur) out.set(it.number, cur)
  }
  return out
}

export const compactOptions = (options: { content: string }[]) => options.length > 0 && options.every(o => o.content.length <= 22)
