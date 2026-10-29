import type { PaperQuestion } from './examApi'

/**
 * Paper exam with several exam codes (mã đề): each code reorders the questions and the multiple-choice options with a
 * generator seeded by exam id + code, so printing the same code again always gives the same paper and answer key.
 */
export interface PaperOptions { codes: number; firstCode: number; shuffleQuestions: boolean; shuffleOptions: boolean }
export interface PaperItem {
  number: number; question: PaperQuestion
  /** Options in printed order with their letter. */
  options: { letter: string; content: string; correct: boolean }[]
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
  if (q.questionType === 'MULTIPLE_CHOICE') return options.filter(o => o.correct).map(o => o.letter).join(',')
  if (q.questionType === 'SHORT_ANSWER') return q.expectedAnswer?.trim() ?? ''
  return 'TL'
}

export function makeVersion(examId: string, questions: PaperQuestion[], code: string, opts: Pick<PaperOptions, 'shuffleQuestions' | 'shuffleOptions'>): PaperVersion {
  const random = rng(hashSeed(`${examId}:${code}`))
  const base = [...questions].sort((a, b) => a.position - b.position)
  const order = opts.shuffleQuestions ? shuffled(base, random) : base
  const items = order.map((q, i) => {
    const sorted = [...(q.options ?? [])].sort((a, b) => a.displayOrder - b.displayOrder)
    const mixed = opts.shuffleOptions && q.questionType === 'MULTIPLE_CHOICE' ? shuffled(sorted, random) : sorted
    const options = mixed.map((o, k) => ({ letter: LETTERS[k] ?? String(k + 1), content: o.content, correct: o.correct }))
    return { number: i + 1, question: q, options, key: keyOf(q, options) }
  })
  return { code, items }
}

export function makeVersions(examId: string, questions: PaperQuestion[], o: PaperOptions): PaperVersion[] {
  const n = Math.max(1, Math.min(24, Math.floor(o.codes)))
  return Array.from({ length: n }, (_, i) => makeVersion(examId, questions, String(o.firstCode + i), o))
}

/** Options are laid out in one row when every option is short, otherwise one per line (like printed Vietnamese exams). */
export const compactOptions = (options: { content: string }[]) => options.length > 0 && options.every(o => o.content.length <= 22)
